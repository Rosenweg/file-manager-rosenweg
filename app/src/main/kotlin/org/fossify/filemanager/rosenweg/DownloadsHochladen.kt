package org.fossify.filemanager.rosenweg

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ch.rosenweg4303.cloud.Ablage
import ch.rosenweg4303.cloud.Hochladen
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads auf geteilten Telefonen (Stefan 25.11.2026: "auch Downloads automatisch hochladen"):
 * Was im Download-Ordner landet (Browser, Mail ...), kommt nach Persoenlich/Downloads in die
 * Nextcloud und wird danach lokal geloescht. Rosenweg Geraet wartet beim Abmelden, bis die
 * Warteschlange leer ist (HochladenStatus) - so geht kein Download verloren.
 *
 * Nur mit Rosenweg Geraet; auf eigenen Handys bleiben Downloads, wo sie sind.
 * Ausgeloest bei jeder Aenderung im MediaStore der Downloads und sicherheitshalber alle 15 Min.
 */
object DownloadsHochladen {
    private const val TAG = "RosenwegDownloads"
    private const val SOFORT = "rosenweg-downloads-sofort"
    private const val REGELMAESSIG = "rosenweg-downloads"
    private const val PREFS = "rosenweg_downloads"

    fun einrichten(c: Context) {
        if (!RosenwegAnmeldung.geraetDa(c)) return
        val wm = WorkManager.getInstance(c.applicationContext)
        wm.enqueueUniquePeriodicWork(REGELMAESSIG, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Pruefung>(15, TimeUnit.MINUTES).build())
        sofortPlanen(c)
    }

    /** Wartet auf die naechste Aenderung bei den Downloads (einmalig, plant sich danach neu). */
    private fun sofortPlanen(c: Context) {
        val req = OneTimeWorkRequestBuilder<Pruefung>()
            .setConstraints(Constraints.Builder()
                .addContentUriTrigger(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                .build())
            .build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(SOFORT, ExistingWorkPolicy.REPLACE, req)
    }

    class Pruefung(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            try {
                pruefen(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Pruefung: ${e.javaClass.simpleName}")
            } finally {
                if (tags.isEmpty() || REGELMAESSIG !in tags) sofortPlanen(applicationContext)
            }
            return Result.success()
        }
    }

    /** Fertige Downloads einreihen (Hochladen kuemmert sich um Netz, Wiederholung, Loeschen). */
    fun pruefen(c: Context) {
        if (!RosenwegAnmeldung.geraetDa(c) || Ablage.webDav(c) == null) return   // niemand mit Nextcloud angemeldet
        @Suppress("DEPRECATION")
        val ordner = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dateien = ordner.listFiles() ?: run {
            Log.w(TAG, "Download-Ordner nicht lesbar (Recht 'Alle Dateien' fehlt?)")
            return
        }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val schon = p.getStringSet("eingereiht", emptySet())!!.toMutableSet()
        val jetzt = System.currentTimeMillis()
        var neu = 0
        for (f in dateien) {
            if (!f.isFile || f.length() == 0L || f.name.startsWith(".")) continue
            if (UNFERTIG.any { f.name.endsWith(it, ignoreCase = true) }) continue
            if (jetzt - f.lastModified() < 15_000) continue     // wird vielleicht noch geschrieben
            val schluessel = "${f.name}|${f.length()}|${f.lastModified()}"
            if (schluessel in schon) continue
            Hochladen.einreihen(c, f, "${Hochladen.PERSOENLICH}/Downloads/${f.name}", lokalLoeschen = true)
            schon += schluessel
            neu++
        }
        // nur Eintraege fuer Dateien behalten, die es noch gibt
        val vorhanden = dateien.map { "${it.name}|${it.length()}|${it.lastModified()}" }.toSet()
        p.edit().putStringSet("eingereiht", schon.intersect(vorhanden)).apply()
        if (neu > 0) {
            Log.i(TAG, "$neu Download(s) zum Hochladen eingereiht")
            Hochladen.anstossen(c)
        }
    }

    private val UNFERTIG = listOf(".crdownload", ".part", ".tmp", ".pending", ".download")
}
