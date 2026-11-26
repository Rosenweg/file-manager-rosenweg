package org.fossify.filemanager.rosenweg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Richtet das Hochladen der Downloads ein, ohne dass jemand die App oeffnet: nach einem Update
 * aus dem Katalog und nach einem Neustart. Am 25.11.2026 am A15 gesehen: v33 installiert, aber
 * kein Auftrag geplant - App.onCreate laeuft sonst erst, wenn jemand Dateien startet.
 * (WorkManager behaelt die Auftraege danach selbst ueber Neustarts.)
 */
class DownloadsStart : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        DownloadsHochladen.einrichten(c)
    }
}
