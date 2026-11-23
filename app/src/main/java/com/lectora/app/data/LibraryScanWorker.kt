package com.lectora.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lectora.app.LectoraApp
import java.util.concurrent.TimeUnit

/** Sincroniza las carpetas de la biblioteca en segundo plano. */
class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LectoraApp).container
        return runCatching { container.library.scanAll() }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("library-scan", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("library-scan-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LibraryScanWorker>().build())
        }
    }
}
