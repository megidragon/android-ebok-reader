package com.lectora.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lectora.app.LectoraApp
import kotlinx.coroutines.flow.first

/**
 * Pregenera la narración de un rango de páginas en segundo plano (servicio en primer plano con
 * notificación de progreso; sigue con la pantalla apagada). Lo ya generado se conserva si se
 * cancela o se interrumpe, y al relanzarlo continúa donde quedó.
 */
class PrerenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LectoraApp).container
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val from = inputData.getInt(KEY_FROM, 0)
        val count = inputData.getInt(KEY_COUNT, 1)
        val book = runCatching { container.openNarrationBook(bookId) }.getOrElse { return Result.failure() }
        val settings = container.settings.narration.first()
        setForeground(foregroundInfo(book.title, 0, count, from))
        return try {
            container.renderer.render(book, settings, from, count) { p ->
                setProgress(workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_SECTION to p.section))
                setForeground(foregroundInfo(book.title, p.done, p.total, p.section))
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("Prerender", "Falló la pregeneración", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error")))
        }
    }

    private fun foregroundInfo(title: String, done: Int, total: Int, section: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pregeneración de narración", NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Pregenerando narración: $title")
            .setContentText("$done de $total · página ${section + 1}")
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_BOOK = "book"
        const val KEY_FROM = "from"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SECTION = "section"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "prerender"
        private const val NOTIFICATION_ID = 4711

        fun uniqueName(bookId: Long) = "prerender-$bookId"

        fun start(context: Context, bookId: Long, fromSection: Int, count: Int) {
            val request = OneTimeWorkRequestBuilder<PrerenderWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_FROM to fromSection, KEY_COUNT to count))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, bookId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bookId))
        }
    }
}
