package audio.soniqo.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException

/**
 * Downloads the speech models in a foreground worker so the transfer survives
 * app backgrounding and process death. Wraps [ModelManager.ensureModels] —
 * resumes partial downloads via the same on-disk `.tmp` files, retries on
 * `IOException`, and reports progress via [setProgress].
 *
 * ### Usage
 *
 * ```
 * WorkManager.getInstance(context).enqueueUniqueWork(
 *     ModelDownloadWorker.UNIQUE_NAME,
 *     ExistingWorkPolicy.KEEP,
 *     ModelDownloadWorker.request(ModelPrecision.INT8),
 * )
 *
 * WorkManager.getInstance(context)
 *     .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.UNIQUE_NAME)
 *     .observe(this) { infos ->
 *         val info = infos.firstOrNull() ?: return@observe
 *         when (info.state) {
 *             WorkInfo.State.RUNNING -> {
 *                 val pct = info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
 *                 ...
 *             }
 *             WorkInfo.State.SUCCEEDED -> {
 *                 val dir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
 *                 ...
 *             }
 *             else -> Unit
 *         }
 *     }
 * ```
 *
 * Requires the host app to declare `POST_NOTIFICATIONS` (API 33+) for the
 * progress notification to appear; the worker still runs without it.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val precision = inputData.getString(KEY_PRECISION)
            ?.let { runCatching { ModelPrecision.valueOf(it) }.getOrNull() }
            ?: ModelPrecision.INT8

        runCatching { setForeground(buildForegroundInfo(0, 0, "Preparing speech models…")) }

        return try {
            val modelDir = ModelManager.ensureModels(applicationContext, precision) { p ->
                val pct = if (p.totalFiles > 0) {
                    (p.completed * 100 / p.totalFiles).coerceIn(0, 100)
                } else 0
                setProgressAsync(workDataOf(
                    KEY_FILE to p.file,
                    KEY_COMPLETED to p.completed,
                    KEY_TOTAL to p.totalFiles,
                    KEY_BYTES_DOWNLOADED to p.bytesDownloaded,
                    KEY_PERCENT to pct,
                ))
                runCatching {
                    setForegroundAsync(buildForegroundInfo(
                        completed = p.completed,
                        total = p.totalFiles,
                        text = "${p.file}  ${p.completed}/${p.totalFiles}",
                    ))
                }
            }
            Result.success(workDataOf(KEY_MODEL_DIR to modelDir))
        } catch (e: IOException) {
            // Network / disk hiccup — let WorkManager retry with backoff.
            Result.retry()
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    private fun buildForegroundInfo(completed: Int, total: Int, text: String): ForegroundInfo {
        ensureChannel()
        val indeterminate = total <= 0
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Speech models")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(if (indeterminate) 100 else total, completed, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Speech model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress for downloading on-device speech models" })
    }

    companion object {
        /** Pass to [WorkManager.enqueueUniqueWork] to dedupe concurrent downloads. */
        const val UNIQUE_NAME = "audio.soniqo.speech.modelDownload"

        // Input keys
        const val KEY_PRECISION = "precision"

        // Output keys
        const val KEY_MODEL_DIR = "modelDir"
        const val KEY_ERROR = "error"

        // Progress keys
        const val KEY_FILE = "file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "totalFiles"
        const val KEY_BYTES_DOWNLOADED = "bytesDownloaded"
        const val KEY_PERCENT = "percent"

        private const val CHANNEL_ID = "audio.soniqo.speech.models"
        // Stable, unlikely-to-collide id (decimal of 0xC0FFEE).
        private const val NOTIFICATION_ID = 12648430

        /** Build a one-shot download request. Requires a network connection. */
        fun request(precision: ModelPrecision = ModelPrecision.INT8) =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_PRECISION to precision.name))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        /**
         * Convenience: enqueue under the standard unique name with
         * [ExistingWorkPolicy.KEEP] (a running download is reused; otherwise a
         * new one starts). Returns the request id so callers can observe it.
         */
        fun enqueue(
            context: Context,
            precision: ModelPrecision = ModelPrecision.INT8,
        ): java.util.UUID {
            val req = request(precision)
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, req,
            )
            return req.id
        }
    }
}
