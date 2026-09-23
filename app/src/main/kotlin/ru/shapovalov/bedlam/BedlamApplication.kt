package ru.shapovalov.bedlam

import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import ru.shapovalov.bedlam.core.crash.CrashRecorder
import ru.shapovalov.bedlam.core.datastore.PreferencesCorruption
import ru.shapovalov.bedlam.core.log.AppLog
import ru.shapovalov.bedlam.core.log.processExitLevel
import ru.shapovalov.bedlam.core.log.processExitMessage
import ru.shapovalov.bedlam.core.routing.work.RouteRefreshWorker
import ru.shapovalov.bedlam.di.AppComponent
import ru.shapovalov.bedlam.di.create
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.ZoneId

class BedlamApplication : Application() {

    lateinit var component: AppComponent
        private set

    var nativeLoadError: LinkageError? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val crashRecorder = CrashRecorder(this)
        crashRecorder.install()
        component = AppComponent::class.create(this)
        PreferencesCorruption.reporter = { name, error ->
            PreferencesCorruption.logReporter(name, error)
            component.appLog.warn(AppLog.SOURCE_APP, "Replaced the corrupt $name preferences: $error")
        }
        try {
            component.logBuffer
        } catch (error: LinkageError) {
            Log.e(TAG, "The native library failed to load", error)
            nativeLoadError = error
        }
        crashRecorder.unreportedCrash()?.let { report ->
            component.appLog.error(AppLog.SOURCE_APP, "Previous launch crashed\n$report")
        }
        logLastProcessExitReason()
        RouteRefreshWorker.schedule(this)
    }

    private fun logLastProcessExitReason() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = getSystemService(ActivityManager::class.java) ?: return
        val info = activityManager
            .getHistoricalProcessExitReasons(packageName, 0, 1)
            .firstOrNull()
            ?: return
        logProcessExit(info)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logProcessExit(info: ApplicationExitInfo) {
        val level = processExitLevel(info.reason, info.importance)
        val message = processExitMessage(
            code = info.reason,
            importance = info.importance,
            description = info.description,
            timestampMillis = info.timestamp,
            zone = ZoneId.systemDefault(),
        )
        Log.println(level.priority(), TAG, message)
        component.appLog.log(level, AppLog.SOURCE_APP, message)
    }

    private fun LogLevel.priority(): Int = when (this) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private companion object {
        const val TAG = "BedlamApp"
    }
}
