package ru.shapovalov.bedlam.core.routing.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.shapovalov.bedlam.di.appComponent
import java.util.concurrent.TimeUnit

class RouteRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val outcome = applicationContext.appComponent.refreshRouteSources(staleAfterMillis = STALE_MS)
        if (outcome.transientFailures > 0) Result.retry() else Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Log.e(TAG, "Route refresh failed", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RouteRefreshWorker"
        private const val WORK_NAME = "bedlam-route-refresh"
        val STALE_MS: Long = TimeUnit.HOURS.toMillis(20)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RouteRefreshWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
