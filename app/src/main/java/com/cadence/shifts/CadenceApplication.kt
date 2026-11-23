package com.cadence.shifts

import android.app.Application
import com.cadence.shifts.notifications.BackgroundSyncScheduler
import com.cadence.shifts.util.NotificationHelper

class CadenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        NotificationHelper.clearPersistentSyncNotification(this)
        BackgroundSyncScheduler.restoreFromPersistedSession(this)
    }
}
