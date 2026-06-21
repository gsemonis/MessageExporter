package com.sway.messageexporter

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = 1001
    private val channelId = "export_channel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val threadId = intent?.getLongExtra("threadId", -1L) ?: -1L
        val contactName = intent?.getStringExtra("contactName") ?: "Unknown"

        if (threadId != -1L) {
            startForegroundService(contactName)
            serviceScope.launch {
                performExport(threadId, contactName)
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(contactName: String) {
        createNotificationChannel()
        val notification = createNotification("Exporting messages for $contactName...", isDone = false)
        startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            "Message Export Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(text: String, isDone: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_switch_prompt", isDone)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Message Exporter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(!isDone)
            .setAutoCancel(isDone)
            .build()
    }

    private fun performExport(threadId: Long, contactName: String) {
        serviceScope.launch {
            try {
                val dir = getExternalFilesDir(null) ?: return@launch
                val safeName = contactName.replace(Regex("[^a-zA-Z0-9]"), "_").take(20)
                val baseFileName = "export_${safeName}_$threadId"
                val xmlFile = File(dir, "$baseFileName.xml")
                val attachmentsDir = File(dir, "attachments_$baseFileName")
                val zipFile = File(dir, "$baseFileName.zip")

                val exporter = MessageExporter(contentResolver)
                val success = exporter.exportThread(threadId, xmlFile) { current, total ->
                    updateNotification("Exporting $current of $total messages...")
                    val intent = Intent("com.sway.messageexporter.EXPORT_PROGRESS").apply {
                        putExtra("current", current)
                        putExtra("total", total)
                    }
                    sendBroadcast(intent)
                }

                if (success) {
                    createZipArchive(zipFile, xmlFile, attachmentsDir)
                    showDoneNotification("Export complete for $contactName!")
                    sendBroadcast(Intent("com.sway.messageexporter.EXPORT_COMPLETE"))
                } else {
                    showDoneNotification("Export failed for $contactName")
                    sendBroadcast(Intent("com.sway.messageexporter.EXPORT_FAILED"))
                }
            } catch (e: Exception) {
                Log.e("ExportService", "Export failed", e)
                showDoneNotification("Error exporting $contactName: ${e.message}")
                sendBroadcast(Intent("com.sway.messageexporter.EXPORT_FAILED"))
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun showDoneNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId + 1, createNotification(text, isDone = true))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, createNotification(text, isDone = false))
    }

    private fun createZipArchive(zipFile: File, xmlFile: File, attachmentsDir: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { out ->
            if (xmlFile.exists()) {
                addToZip(out, xmlFile, "")
            }
            if (attachmentsDir.exists()) {
                attachmentsDir.listFiles()?.forEach { file ->
                    addToZip(out, file, "attachments/")
                }
            }
        }
        xmlFile.delete()
        attachmentsDir.deleteRecursively()
    }

    private fun addToZip(out: ZipOutputStream, file: File, folderPrefix: String) {
        if (!file.exists()) return
        FileInputStream(file).use { input ->
            out.putNextEntry(ZipEntry(folderPrefix + file.name))
            input.copyTo(out)
            out.closeEntry()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
