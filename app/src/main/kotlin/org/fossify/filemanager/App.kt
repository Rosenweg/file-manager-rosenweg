package org.fossify.filemanager

import com.github.ajalt.reprint.core.Reprint
import org.fossify.commons.FossifyApp

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        Reprint.initialize(this)
        // Rosenweg: auf geteilten Telefonen Downloads automatisch in die Nextcloud
        org.fossify.filemanager.rosenweg.DownloadsHochladen.einrichten(this)
    }
}
