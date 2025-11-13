package com.example.geminichat.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ModelDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "model_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.geminichat.ACTION_START_MODEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.example.geminichat.ACTION_PAUSE_MODEL_DOWNLOAD"
        const val ACTION_RESUME = "com.example.geminichat.ACTION_RESUME_MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.geminichat.ACTION_CANCEL_MODEL_DOWNLOAD"

        const val EXTRA_URL = "extra_download_url"
        const val EXTRA_FILENAME = "extra_file_name"
        const val EXTRA_TOKEN = "extra_hf_token"

        const val ACTION_PROGRESS = "com.example.geminichat.ACTION_MODEL_DOWNLOAD_PROGRESS"
        const val ACTION_COMPLETE = "com.example.geminichat.ACTION_MODEL_DOWNLOAD_COMPLETE"
        const val ACTION_ERROR = "com.example.geminichat.ACTION_MODEL_DOWNLOAD_ERROR"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_FILEPATH = "extra_file_path"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var isDownloading: Boolean = false

    private var lastUrl: String? = null
    private var lastFileName: String? = null
    private var lastToken: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""

                if (!isDownloading) {
                    lastUrl = url
                    lastFileName = fileName
                    lastToken = token
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(progress = 0f, contentText = "Starting download…", ongoing = true)
                    )
                    startDownload(url, fileName, token)
                }
            }

            ACTION_PAUSE -> pauseDownload()
            ACTION_RESUME -> {
                intent.getStringExtra(EXTRA_URL)?.let { lastUrl = it }
                intent.getStringExtra(EXTRA_FILENAME)?.let { lastFileName = it }
                intent.getStringExtra(EXTRA_TOKEN)?.let { lastToken = it }
                resumeDownload()
            }
            ACTION_CANCEL -> cancelDownload()
        }

        return START_STICKY
    }

    private fun startDownload(url: String, fileName: String, token: String) {
        isPaused = false
        isDownloading = true

        serviceScope.launch {
            val llmDir = File(filesDir, "llm").apply { mkdirs() }
            val tmpFile = File(llmDir, "$fileName.part")
            val finalFile = File(llmDir, fileName)

            try {
                var downloadedBytes = tmpFile.length()
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")

                if (downloadedBytes > 0) {
                    requestBuilder.header("Range", "bytes=$downloadedBytes-")
                }

                val call = httpClient.newCall(requestBuilder.build())
                currentCall = call

                call.execute().use { response ->
                    if (response.code == 416) {
                        finalizeResumedFile(tmpFile, finalFile)
                        notifyComplete(finalFile)
                        return@launch
                    }

                    if (!response.isSuccessful) {
                        val msg = if (response.code == 401) {
                            "401 Unauthorized – invalid token"
                        } else {
                            "HTTP ${response.code}: ${response.message}"
                        }
                        throw IOException("Download failed: $msg")
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val reportedLength = body.contentLength()
                    val totalBytes = if (reportedLength > 0) reportedLength + downloadedBytes else -1L

                    if (totalBytes > 0 && downloadedBytes > 0) {
                        val initialProgress = downloadedBytes.toFloat() / totalBytes.toFloat()
                        updateProgress(initialProgress)
                    }

                    val buffer = ByteArray(8_192)
                    var bytesRead: Int

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile, true).use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (isPaused) break
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                                    updateProgress(progress)
                                }
                            }
                        }
                    }
                }

                if (isPaused) {
                    isDownloading = false
                    updateNotificationPaused()
                    sendErrorBroadcast("Download paused.")
                    return@launch
                }

                if (finalFile.exists() && finalFile != tmpFile && !finalFile.delete()) {
                    throw IOException("Unable to replace existing model file")
                }
                if (!tmpFile.renameTo(finalFile)) {
                    throw IOException("Failed to move downloaded file into place")
                }
                if (!isValidModelFile(finalFile)) {
                    finalFile.delete()
                    throw IOException("Downloaded file is smaller than expected size")
                }

                notifyComplete(finalFile)
            } catch (e: Exception) {
                val wasCanceled = currentCall?.isCanceled() == true
                isDownloading = false
                if (isPaused) {
                    updateNotificationPaused()
                    sendErrorBroadcast("Download paused.")
                } else if (wasCanceled) {
                    cleanupTmpFile(lastFileName)
                    updateNotificationError("Download cancelled.")
                    sendErrorBroadcast("Download cancelled.")
                    stopForeground(true)
                    stopSelf()
                } else {
                    updateNotificationError(e.message ?: "Download interrupted. Tap Resume to continue.")
                    sendErrorBroadcast(e.message ?: "Download interrupted. Tap Resume to continue.")
                }
            } finally {
                currentCall = null
            }
        }
    }

    private fun pauseDownload() {
        if (!isDownloading || isPaused) return
        isPaused = true
        currentCall?.cancel()
    }

    private fun resumeDownload() {
        if (isDownloading) return
        val url = lastUrl ?: return
        val fileName = lastFileName ?: return
        val token = lastToken ?: ""
        isPaused = false
        startForeground(
            NOTIFICATION_ID,
            buildNotification(progress = 0f, contentText = "Resuming download…", ongoing = true)
        )
        startDownload(url, fileName, token)
    }

    private fun cancelDownload() {
        isPaused = false
        currentCall?.cancel()
        cleanupTmpFile(lastFileName)
        isDownloading = false
        lastUrl = null
        lastFileName = null
        lastToken = null
        updateNotificationError("Download cancelled.")
        sendErrorBroadcast("Download cancelled.")
        stopForeground(true)
        stopSelf()
    }

    private fun cleanupTmpFile(fileName: String?) {
        if (fileName == null) return
        val tmp = File(File(filesDir, "llm"), "$fileName.part")
        if (tmp.exists()) tmp.delete()
    }

    private fun finalizeResumedFile(tmpFile: File, finalFile: File) {
        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!tmpFile.renameTo(finalFile)) {
            throw IOException("Failed to finalize resumed download")
        }
        if (!isValidModelFile(finalFile)) {
            finalFile.delete()
            throw IOException("Downloaded file is smaller than expected size")
        }
    }

    private fun notifyComplete(finalFile: File) {
        isDownloading = false
        updateProgress(1f)
        updateNotificationComplete()
        sendCompleteBroadcast(finalFile.absolutePath)
        lastUrl = null
        lastFileName = null
        lastToken = null
        stopForeground(false)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while downloading the on-device LLM."
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        progress: Float,
        contentText: String,
        ongoing: Boolean,
        showProgress: Boolean = true
    ): Notification {
        val max = 100
        val current = (progress * 100).toInt().coerceIn(0, 100)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading AI model")
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply {
                if (showProgress) {
                    setProgress(max, current, false)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun updateProgress(progress: Float) {
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(progress, "Downloading model…", true))

        val intent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateNotificationPaused() {
        NotificationManagerCompat.from(this)
            .notify(
                NOTIFICATION_ID,
                buildNotification(progress = 0f, contentText = "Download paused", ongoing = false, showProgress = false)
            )
    }

    private fun updateNotificationError(msg: String) {
        NotificationManagerCompat.from(this)
            .notify(
                NOTIFICATION_ID,
                buildNotification(progress = 0f, contentText = msg, ongoing = false, showProgress = false)
            )
    }

    private fun updateNotificationComplete() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Model download complete")
            .setContentText("Ready to initialize.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notif)
    }

    private fun sendCompleteBroadcast(path: String) {
        val intent = Intent(ACTION_COMPLETE).apply {
            putExtra(EXTRA_FILEPATH, path)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(msg: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun isValidModelFile(file: File?): Boolean {
        if (file == null) return false
        return file.exists() && file.length() >= LlmViewModel.MIN_MODEL_FILE_BYTES
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
