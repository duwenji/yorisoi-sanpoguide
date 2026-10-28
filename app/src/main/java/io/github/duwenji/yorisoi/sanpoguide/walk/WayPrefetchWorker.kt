package io.github.duwenji.yorisoi.sanpoguide.walk

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import java.util.concurrent.TimeUnit

/**
 * Searches ahead along the frequent ways (platform REQ-FREQUENT-WAYS F-6〜F-8, D-9): once a day,
 * and once right after a walk. Only on a network that isn't metered, with the battery not low,
 * and never during a walk.
 */
class WayPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SanpoApp
        if (WalkService.walking.value) return Result.success()
        // The ways gone along last first: the likeliest to be walked next.
        val ways = app.ways.ways.value.map { it.points }
        val searches = app.spots.prefetch(ways)
        Log.i(TAG, "Searched ahead $searches times along ${ways.size} ways")
        return Result.success()
    }

    companion object {
        private const val TAG = "WayPrefetch"
        private const val DAILY = "way-prefetch-daily"
        private const val AFTER_WALK = "way-prefetch-after-walk"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Once a day, from the app's start on. */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<WayPrefetchWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Once, after a walk, when the network allows. */
        fun afterWalk(context: Context) {
            val request = OneTimeWorkRequestBuilder<WayPrefetchWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER_WALK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
