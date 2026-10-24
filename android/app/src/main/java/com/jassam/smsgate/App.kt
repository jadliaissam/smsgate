package com.jassam.smsgate

import android.app.Application
import android.util.Log
import androidx.work.Configuration

/**
 * Application entry point. WorkManager is initialised on demand from this
 * [Configuration.Provider] (its App Startup initializer is removed in the
 * manifest), so it costs nothing at cold start (D10).
 */
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()
}
