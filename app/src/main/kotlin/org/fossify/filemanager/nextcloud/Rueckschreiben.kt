package org.fossify.filemanager.nextcloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.WebDav
import org.fossify.filemanager.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bringt Aenderungen, die andere Apps ueber den NextcloudProvider gemacht haben,
 * zurueck in die Nextcloud.
 *
 * Jede zum Schreiben geoeffnete Datei bekommt einen Auftrag: eigener Ordner
 * unter files/bearbeiten/ (nicht im Cache, darf nicht verschwinden) mit der
 * Arbeitskopie und auftrag.properties. Nach dem Schliessen wird hochgeladen;
 * erst wenn die Nextcloud bestaetigt, wird der Auftrag geloescht (Grundregel).
 *
 * - Hochladen nur ueber die Fassung, die geoeffnet wurde (If-Match). Hat jemand
 *   anderes die Datei inzwischen geaendert, wird nichts ueberschrieben: unsere
 *   Fassung kommt als Konfliktkopie daneben.
 * - Ohne Schreibrecht oder wenn der Ordner weg ist: Kopie nach "Vom Telefon".
 * - Netz weg / Nextcloud ueberlastet: spaeter nochmal, auch nach Neustart der App.
 */
object Rueckschreiben {
    private const val AUFTRAG = "auftrag.properties"
    private const val VOM_TELEFON = "Vom Telefon"
    private val WARTEN_S = longArrayOf(15, 60, 300, 900)

    private val ausfuehrer = Executors.newSingleThreadScheduledExecutor { Thread(it, "rosenweg-rueckschreiben") }
    private var versuche = 0
    private var geplant = false

    private fun ordner(ctx: Context) = File(ctx.filesDir, "bearbeiten")

    /** Legt einen Auftrag an und liefert die (noch leere) Arbeitskopie. */
    fun neuerAuftrag(ctx: Context, pfad: String, etag: String, name: String): File {
        val dir = File(ordner(ctx), "${System.currentTimeMillis()}-${UUID.randomUUID()}")
        dir.mkdirs()
        val p = Properties()
        p["pfad"] = pfad
        p["etag"] = etag
        p["fertig"] = "0"
        speichern(dir, p)
        return File(dir, name)
    }

    /** Stempel der Arbeitskopie beim Oeffnen — daran erkennt man spaeter, ob sich etwas geaendert hat. */
    fun stempeln(datei: File) {
        val dir = datei.parentFile ?: return
        val p = laden(dir) ?: return
        p["vorher"] = stempel(datei)
        speichern(dir, p)
    }

    /** Die App hat die Datei geschlossen. */
    fun geschlossen(ctx: Context, datei: File) {
        val dir = datei.parentFile ?: return
        val p = laden(dir) ?: return
        if (p.getProperty("vorher") == stempel(datei)) {
            dir.deleteRecursively() // nichts geaendert
            return
        }
        p["fertig"] = "1"
        speichern(dir, p)
        anstossen(ctx)
    }

    /**
     * Beim Start des Prozesses: Auftraege, die beim letzten Mal noch offen waren
     * (App abgestuerzt, Telefon neu gestartet), gelten als geschlossen.
     */
    fun beimStart(ctx: Context) {
        ausfuehrer.execute {
            auftraege(ctx).filter { it.second.getProperty("fertig") == "0" }.forEach { (dir, p) ->
                val datei = datei(dir)
                if (datei == null || p.getProperty("vorher") == stempel(datei)) {
                    dir.deleteRecursively()
                } else {
                    p["fertig"] = "1"
                    speichern(dir, p)
                }
            }
        }
        anstossen(ctx)
    }

    /** Neueste noch nicht hochgeladene Fassung von [pfad] — die soll man beim erneuten Oeffnen sehen. */
    fun ausstehend(ctx: Context, pfad: String): File? =
        auftraege(ctx).lastOrNull { it.second.getProperty("pfad") == pfad && it.second.getProperty("fertig") == "1" }
            ?.let { datei(it.first) }

    fun anzahl(ctx: Context) = auftraege(ctx).size

    fun anstossen(ctx: Context) {
        val app = ctx.applicationContext
        ausfuehrer.execute { abarbeiten(app) }
    }

    // ---- intern, nur auf dem eigenen Thread ----

    private fun abarbeiten(ctx: Context) {
        val dav = Nextcloud.dav(ctx) ?: return
        for ((dir, p) in auftraege(ctx).filter { it.second.getProperty("fertig") == "1" }) {
            try {
                hochladen(ctx, dav, dir, p)
                versuche = 0
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) return // nach der Anmeldung geht es weiter
                if (e.voruebergehend || e.code == 0) {
                    spaeter(ctx)
                    return
                }
                melden(ctx, ctx.getString(R.string.nc_rueck_fehler, dir.name.let { datei(dir)?.name ?: it }, e.message))
                p["fehler"] = e.code.toString()
                speichern(dir, p)
            } catch (e: IOException) {
                spaeter(ctx) // Netz weg
                return
            }
        }
    }

    private fun spaeter(ctx: Context) {
        if (geplant) return
        geplant = true
        val s = WARTEN_S[minOf(versuche, WARTEN_S.size - 1)]
        versuche++
        ausfuehrer.schedule({
            geplant = false
            abarbeiten(ctx)
        }, s, TimeUnit.SECONDS)
    }

    private fun hochladen(ctx: Context, dav: WebDav, dir: File, p: Properties) {
        val datei = datei(dir) ?: run { dir.deleteRecursively(); return }
        val pfad = p.getProperty("pfad")
        val basis = p.getProperty("etag").ifEmpty { null }
        val eigene = ctx.getSharedPreferences("rosenweg_rueckschreiben", Context.MODE_PRIVATE)

        fun put(ziel: String, wenn: String?) =
            dav.hochladen(ziel, { datei.inputStream() }, datei.length(), null, datei.lastModified(), wenn)

        var ziel = pfad
        var neu: String?
        try {
            neu = put(pfad, basis)
        } catch (e: NextcloudFehler) {
            when (e.code) {
                412 -> {
                    // Fassung auf dem Server ist nicht mehr die geoeffnete.
                    val aktuell = dav.eigenschaften(pfad)?.etag
                    if (aktuell != null && aktuell == eigene.getString(pfad, null)) {
                        // ...aber das war unser eigenes Hochladen (mehrmals gespeichert)
                        neu = put(pfad, aktuell)
                    } else {
                        ziel = konfliktName(pfad)
                        neu = put(ziel, null)
                        melden(ctx, ctx.getString(R.string.nc_rueck_konflikt, ziel.substringAfterLast('/')))
                    }
                }
                403, 404, 405, 409 -> {
                    // kein Schreibrecht oder Ordner weg: in den eigenen Bereich retten
                    dav.ordnerSicherstellen(VOM_TELEFON)
                    ziel = Nextcloud.kind(VOM_TELEFON, konfliktName(pfad).substringAfterLast('/'))
                    neu = put(ziel, null)
                    melden(ctx, ctx.getString(R.string.nc_rueck_gerettet, "$VOM_TELEFON/${ziel.substringAfterLast('/')}"))
                }
                else -> throw e
            }
        }
        val etag = neu ?: dav.eigenschaften(ziel)?.etag
        etag?.let { eigene.edit().putString(ziel, it).apply() }

        // Arbeitskopie wird zur Cache-Kopie der neuen Fassung (schnelles Wiederoeffnen)
        if (etag != null && ziel == pfad) {
            val cache = File(File(Nextcloud.cacheOrdner(ctx), etag.replace(Regex("[^A-Za-z0-9]"), "")), datei.name)
            cache.parentFile?.mkdirs()
            if (!datei.renameTo(cache)) datei.copyTo(cache, overwrite = true)
        }
        dir.deleteRecursively()
        Listen.vergessen(Nextcloud.eltern(ziel))
        Nextcloud.geaendert(ctx, Nextcloud.eltern(ziel))
    }

    private fun konfliktName(pfad: String): String {
        val name = pfad.substringAfterLast('/')
        val basis = name.substringBeforeLast('.', name)
        val endung = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        val zeit = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
        return Nextcloud.kind(Nextcloud.eltern(pfad), "$basis (Konflikt Telefon $zeit)$endung")
    }

    private fun melden(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    private fun auftraege(ctx: Context): List<Pair<File, Properties>> =
        (ordner(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
            .mapNotNull { d -> laden(d)?.let { d to it } }
            .filter { it.second.getProperty("fehler") == null }

    private fun datei(dir: File) = dir.listFiles()?.firstOrNull { it.isFile && it.name != AUFTRAG }

    private fun stempel(f: File) = "${f.length()}/${f.lastModified()}"

    private fun laden(dir: File): Properties? {
        val f = File(dir, AUFTRAG)
        if (!f.exists()) return null
        return try {
            Properties().apply { f.inputStream().use { load(it) } }
        } catch (e: IOException) {
            null
        }
    }

    private fun speichern(dir: File, p: Properties) {
        val tmp = File(dir, "$AUFTRAG.neu")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(File(dir, AUFTRAG))
    }
}
