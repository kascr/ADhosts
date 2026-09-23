package com.kascr.adhosts

import android.app.Application
import com.kascr.adhosts.crash.CrashHandler
import com.kascr.adhosts.data.AppUpdateInstaller

class ADHostsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The crash screen runs in an isolated process. Installing the handler there would
        // allow a rendering failure to recursively open another crash screen.
        if (getProcessName() == packageName) {
            CrashHandler.install(this)
            AppUpdateInstaller.cleanupStaleDownloads(this)
        }
    }
}
