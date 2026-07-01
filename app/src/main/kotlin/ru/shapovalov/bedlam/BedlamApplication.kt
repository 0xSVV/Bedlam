package ru.shapovalov.bedlam

import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import ru.shapovalov.bedlam.core.routing.work.RouteRefreshWorker
import ru.shapovalov.bedlam.di.AppComponent
import ru.shapovalov.bedlam.di.create

class BedlamApplication : Application() {

    lateinit var component: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        component = AppComponent::class.create(this)
        component.logBuffer
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
        Log.i(
            TAG,
            "Last process exit: reason=${info.reasonLabel()}, " +
                    "importance=${info.importance}, description=${info.description.orEmpty()}",
        )
    }

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
