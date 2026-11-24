package org.fossify.filemanager.nextcloud

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.WebDav
import org.fossify.filemanager.R
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Die Rosenweg-Nextcloud in der Dateiauswahl des Systems ("Rosenweg Nextcloud"):
 * Jede App kann darueber Dateien oeffnen, speichern und neu anlegen — ohne
 * eigenes Nextcloud-Konto. Ohne Anmeldung in Rosenweg Files gibt es keine Wurzel.
 *
 * Lesen geht ueber den Cache, Schreiben ueber eine Arbeitskopie, die nach dem
 * Schliessen zurueckgeschrieben wird ([Rueckschreiben]).
 */
class NextcloudProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        Rueckschreiben.beimStart(context!!)
        return true
    }

    private val ctx get() = context!!

    private fun dav(): WebDav = Nextcloud.dav(ctx) ?: throw FileNotFoundException("Nicht in Rosenweg Files angemeldet")

    // ---- Abfragen ----

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_SPALTEN)
        val konto = KontoSpeicher(ctx).laden() ?: return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "nextcloud")
            add(Root.COLUMN_DOCUMENT_ID, Nextcloud.id(""))
            add(Root.COLUMN_TITLE, ctx.getString(R.string.nc_wurzel))
            add(Root.COLUMN_SUMMARY, konto.benutzer)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.drawable.ic_cloud_vector)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOKUMENT_SPALTEN)
        val pfad = pfad(documentId)
        if (pfad.isEmpty()) {
            c.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_DISPLAY_NAME, ctx.getString(R.string.nc_wurzel))
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            }
            return c
        }
        val e = Listen.eintrag(pfad) ?: try {
            netz { dav().eigenschaften(pfad) } ?: throw FileNotFoundException(pfad)
        } catch (ex: FileNotFoundException) {
            if (ex.message == pfad) throw ex // gibt es wirklich nicht
            // Nextcloud gerade nicht erreichbar: Name und Typ kennen wir aus dem Pfad,
            // damit die App wenigstens den richtigen Titel zeigt
            Log.w(TAG, "queryDocument $pfad: ${ex.message}")
            val name = pfad.substringAfterLast('/')
            Eintrag(name, pfad, false, 0, 0, "", "")
        }
        zeile(c, e)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOKUMENT_SPALTEN)
        val pfad = pfad(parentDocumentId)
        c.setNotificationUri(ctx.contentResolver, DocumentsContract.buildChildDocumentsUri(Nextcloud.authority(ctx), parentDocumentId))
        try {
            val dav = dav()
            val liste = netz { dav.liste(pfad) }
            Listen.merken(pfad, liste)
            liste.forEach { zeile(c, it) }
        } catch (e: IOException) {
            c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Nextcloud: ${e.message}") }
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val eltern = pfad(parentDocumentId)
        val kind = pfad(documentId)
        return kind.isNotEmpty() && (eltern.isEmpty() || kind.startsWith("$eltern/"))
    }

    // ---- Oeffnen ----

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val pfad = pfad(documentId)
        val dav = dav()
        val e = netz { dav.eigenschaften(pfad) } ?: throw FileNotFoundException(pfad)
        if (e.istOrdner) throw FileNotFoundException("$pfad ist ein Ordner")

        val nurLesen = mode == "r"
        val abschneiden = mode.startsWith("w") && !mode.contains('a') || mode.contains('t')
        if (!nurLesen && !e.darfSchreiben) throw SecurityException("Keine Schreibrechte: ${e.name}")

        // Noch nicht hochgeladene eigene Aenderung hat Vorrang vor dem Server
        val quelle = Rueckschreiben.ausstehend(ctx, pfad) ?: if (nurLesen || !abschneiden) {
            Nextcloud.cacheDatei(ctx, e).also { if (!it.exists()) netz(LADEN_S) { dav.herunterladen(pfad, it) } }
        } else {
            null
        }

        if (nurLesen) return ParcelFileDescriptor.open(quelle, ParcelFileDescriptor.MODE_READ_ONLY)

        val arbeit = Rueckschreiben.neuerAuftrag(ctx, pfad, e.etag, e.name)
        if (quelle != null && !abschneiden) quelle.copyTo(arbeit, overwrite = true) else arbeit.createNewFile()
        Rueckschreiben.stempeln(arbeit)
        return ParcelFileDescriptor.open(arbeit, ParcelFileDescriptor.parseMode(mode), schliessHandler) {
            Rueckschreiben.geschlossen(ctx, arbeit)
        }
    }

    // ---- Aendern ----

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val eltern = pfad(parentDocumentId)
        val dav = dav()
        val ordner = mimeType == Document.MIME_TYPE_DIR
        val name = netz { freierName(dav, eltern, gueltig(displayName)) }
        val ziel = Nextcloud.kind(eltern, name)
        netz {
            if (ordner) dav.ordnerAnlegen(ziel)
            else dav.hochladen(ziel, { ByteArrayInputStream(ByteArray(0)) }, 0, mimeType)
        }
        aendert(eltern)
        return Nextcloud.id(ziel)
    }

    override fun deleteDocument(documentId: String) {
        val pfad = pfad(documentId)
        netz { dav().loeschen(pfad) }
        aendert(Nextcloud.eltern(pfad))
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val pfad = pfad(documentId)
        val ziel = Nextcloud.kind(Nextcloud.eltern(pfad), gueltig(displayName))
        if (ziel == pfad) return null
        netz { dav().verschieben(pfad, ziel) }
        aendert(Nextcloud.eltern(pfad))
        return Nextcloud.id(ziel)
    }

    // ---- Hilfen ----

    private fun zeile(c: MatrixCursor, e: Eintrag) {
        var flags = 0
        if (e.istOrdner) {
            if (e.darfAnlegen) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (e.darfSchreiben) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (e.darfLoeschen) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (e.darfUmbenennen) flags = flags or Document.FLAG_SUPPORTS_RENAME
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, Nextcloud.id(e.pfad))
            add(Document.COLUMN_MIME_TYPE, if (e.istOrdner) Document.MIME_TYPE_DIR else mime(e))
            add(Document.COLUMN_DISPLAY_NAME, e.name)
            add(Document.COLUMN_LAST_MODIFIED, if (e.geaendert > 0) e.geaendert else null)
            add(Document.COLUMN_SIZE, if (e.istOrdner) null else e.groesse)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mime(e: Eintrag): String {
        if (e.mime.isNotEmpty() && e.mime != "application/octet-stream") return e.mime
        val endung = e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(endung) ?: "application/octet-stream"
    }

    private fun pfad(id: String) = try {
        Nextcloud.pfad(id)
    } catch (e: IllegalArgumentException) {
        throw FileNotFoundException(id)
    }

    private fun gueltig(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/') || n == "." || n == "..") throw IllegalArgumentException("Ungueltiger Name")
        return n
    }

    /** "Bericht.txt" -> "Bericht (1).txt", falls es den Namen schon gibt (PUT wuerde ueberschreiben). */
    private fun freierName(dav: WebDav, eltern: String, wunsch: String): String {
        val vorhanden = dav.liste(eltern).map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (wunsch.lowercase(Locale.ROOT) !in vorhanden) return wunsch
        val basis = wunsch.substringBeforeLast('.', wunsch)
        val endung = if (wunsch.contains('.')) "." + wunsch.substringAfterLast('.') else ""
        var i = 1
        while ("$basis ($i)$endung".lowercase(Locale.ROOT) in vorhanden) i++
        return "$basis ($i)$endung"
    }

    private fun aendert(ordner: String) {
        Listen.vergessen(ordner)
        Nextcloud.geaendert(ctx, ordner)
    }

    /**
     * Jeder Netzzugriff laeuft auf einem eigenen Thread: Ruft eine App den
     * Provider von ihrem Haupt-Thread auf, uebertraegt Android deren StrictMode
     * auf unseren Binder-Thread (NetworkOnMainThreadException). Fehler kommen
     * als FileNotFoundException — so erwartet es die Dateiauswahl.
     */
    private fun <T> netz(maxSekunden: Long = ABFRAGE_S, block: () -> T): T = try {
        try {
            NETZ.submit(Callable { block() }).get(maxSekunden, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IOException("Zeitüberschreitung")
        }
    } catch (e: NextcloudFehler) {
        Log.w(TAG, "Nextcloud ${e.code}: ${e.message}")
        if (e.code == 404) throw FileNotFoundException(e.message)
        throw FileNotFoundException("Nextcloud: ${e.message}")
    } catch (e: IOException) {
        if (e is FileNotFoundException) throw e
        Log.w(TAG, "Netz: $e")
        throw FileNotFoundException("Nextcloud nicht erreichbar: ${e.message}")
    }

    private companion object {
        const val TAG = "RosenwegNextcloud"
        const val ABFRAGE_S = 60L
        const val LADEN_S = 15 * 60L
        val NETZ = Executors.newCachedThreadPool { Thread(it, "rosenweg-nextcloud-netz") }
        val ROOT_SPALTEN = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        val DOKUMENT_SPALTEN = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
        )
        val schliessHandler: Handler by lazy {
            Handler(HandlerThread("rosenweg-nextcloud-schliessen").apply { start() }.looper)
        }
    }
}
