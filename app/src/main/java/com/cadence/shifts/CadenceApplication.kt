package com.cadence.shifts

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.cadence.shifts.notifications.BackgroundSyncScheduler
import com.cadence.shifts.util.LocaleHelper
import com.cadence.shifts.util.NotificationHelper

class CadenceApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        LocaleHelper.syncRuntimeLocale(this)
        NotificationHelper.ensureChannel(this)
        NotificationHelper.clearPersistentSyncNotification(this)
        BackgroundSyncScheduler.restoreFromPersistedSession(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleHelper.syncRuntimeLocale(this)
    }
}
