package org.fossify.filemanager.nextcloud

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.WebDav
import java.io.File

/** Was NextcloudActivity, NextcloudProvider und Rueckschreiben gemeinsam brauchen. */
object Nextcloud {
    const val APP_NAME = "Rosenweg Files (Android)"
    private const val PRAEFIX = "nc:"

    fun authority(ctx: Context) = "${ctx.packageName}.nextcloud"

    fun dav(ctx: Context): WebDav? = KontoSpeicher(ctx).laden()?.let { WebDav(it, APP_NAME) }

    /** Dokument-ID im Provider: "nc:" + Pfad relativ zur Wurzel der Person ("nc:" = Wurzel). */
    fun id(pfad: String) = PRAEFIX + pfad.trim('/')

    fun pfad(id: String): String {
        require(id.startsWith(PRAEFIX)) { "Unbekannte ID" }
        val p = id.removePrefix(PRAEFIX).trim('/')
        require(p.split('/').none { it == ".." || it == "." }) { "Ungueltiger Pfad" }
        return p
    }

    fun eltern(pfad: String) = pfad.substringBeforeLast('/', "")
    fun kind(eltern: String, name: String) = if (eltern.isEmpty()) name else "$eltern/$name"

    fun dokumentUri(ctx: Context, pfad: String): Uri = DocumentsContract.buildDocumentUri(authority(ctx), id(pfad))

    /** Aenderungen an einem Ordner melden (Dateiauswahl, offene Listen). */
    fun geaendert(ctx: Context, ordnerPfad: String) {
        ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(ctx), id(ordnerPfad)), null)
    }

    fun anmeldungGeaendert(ctx: Context) {
        Listen.leeren()
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(ctx)), null)
    }

    // ---- Zwischenspeicher (Grundregel: auf dem Telefon nur Kopien) ----

    fun cacheOrdner(ctx: Context) = File(ctx.cacheDir, "nextcloud")

    /** Je Fassung (ETag) ein eigener Ordner: eine geaenderte Datei wird neu geladen. */
    fun cacheDatei(ctx: Context, e: Eintrag) =
        File(File(cacheOrdner(ctx), e.etag.ifEmpty { "x" }.replace(Regex("[^A-Za-z0-9]"), "")), e.name)

    fun cacheAufraeumen(ctx: Context, maxAlterMs: Long) {
        val grenze = System.currentTimeMillis() - maxAlterMs
        cacheOrdner(ctx).listFiles()?.forEach { d ->
            if (maxAlterMs == 0L || d.lastModified() < grenze) d.deleteRecursively()
        }
    }
}

/**
 * Kurzzeit-Merker fuer Ordnerinhalte: Die Dateiauswahl fragt nach einer Liste
 * jedes Kind einzeln ab — das soll nicht jedes Mal ein PROPFIND sein.
 */
object Listen {
    private const val GUELTIG_MS = 15_000L
    private val merker = HashMap<String, Pair<Long, List<Eintrag>>>()

    @Synchronized
    fun merken(pfad: String, liste: List<Eintrag>) {
        merker[pfad] = System.currentTimeMillis() to liste
    }

    @Synchronized
    fun eintrag(pfad: String): Eintrag? {
        val (zeit, liste) = merker[Nextcloud.eltern(pfad)] ?: return null
        if (System.currentTimeMillis() - zeit > GUELTIG_MS) return null
        return liste.firstOrNull { it.pfad == pfad }
    }

    @Synchronized
    fun vergessen(ordnerPfad: String) {
        merker.remove(ordnerPfad)
    }

    @Synchronized
    fun leeren() = merker.clear()
}
