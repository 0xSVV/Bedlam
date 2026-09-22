package ru.shapovalov.bedlam

import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import ru.shapovalov.bedlam.core.crash.CrashRecorder
import ru.shapovalov.bedlam.core.log.AppLog
import ru.shapovalov.bedlam.core.routing.work.RouteRefreshWorker
import ru.shapovalov.bedlam.di.AppComponent
import ru.shapovalov.bedlam.di.create

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
        val message = "Last process exit: ${info.reasonLabel()}, importance ${info.importance}" +
                info.description?.let { ", $it" }.orEmpty()
        Log.i(TAG, message)
        component.appLog.info(AppLog.SOURCE_APP, message)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.reasonLabel(): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> reason.toString()
    }

    private companion object {
        const val TAG = "BedlamApp"
    }
}
