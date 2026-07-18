package vn.vnrp.mobile.cache

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.vnrp.mobile.LauncherConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CacheDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo("Đang đọc manifest...", 0))
            val manifestUrl = inputData.getString(KEY_MANIFEST_URL)
                ?: LauncherConfig.manifestUrl

            val manifest = CacheManifest.parse(downloadText(manifestUrl))
            val cacheRoot = CachePaths.root(applicationContext)
            val totalBytes = manifest.files.sumOf { it.size }.coerceAtLeast(1L)
            var processedBytes = 0L

            manifest.files.forEachIndexed { index, entry ->
                if (isStopped) return@withContext Result.failure()

                val target = CachePaths.safeTarget(cacheRoot, entry.path)
                val valid = target.isFile &&
                    target.length() == entry.size &&
                    Sha256.of(target).equals(entry.sha256, ignoreCase = true)

                if (!valid) {
                    target.parentFile?.mkdirs()
                    downloadFile(entry, target) { currentFileBytes ->
                        val absoluteBytes = processedBytes + currentFileBytes
                        val percent = ((absoluteBytes * 100L) / totalBytes)
                            .coerceIn(0L, 100L)
                            .toInt()

                        val progress = workDataOf(
                            KEY_PROGRESS to percent,
                            KEY_CURRENT_FILE to entry.path,
                            KEY_FILE_INDEX to index + 1,
                            KEY_FILE_COUNT to manifest.files.size,
                        )
                        setProgress(progress)
                        setForeground(
                            createForegroundInfo(
                                "${index + 1}/${manifest.files.size}: ${entry.path}",
                                percent,
                            )
                        )
                    }

                    check(target.length() == entry.size) {
                        "Sai kích thước sau khi tải: ${entry.path}"
                    }
                    check(Sha256.of(target).equals(entry.sha256, ignoreCase = true)) {
                        "Sai SHA-256: ${entry.path}"
                    }
                }

                processedBytes += entry.size
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to ((processedBytes * 100L) / totalBytes).toInt(),
                        KEY_CURRENT_FILE to entry.path,
                        KEY_FILE_INDEX to index + 1,
                        KEY_FILE_COUNT to manifest.files.size,
                    )
                )
            }

            Result.success(
                workDataOf(
                    KEY_MANIFEST_VERSION to manifest.version,
                    KEY_CACHE_ROOT to cacheRoot.absolutePath,
                )
            )
        } catch (error: Exception) {
            Result.failure(
                workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName))
            )
        }
    }

    private fun downloadText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadFile(
        entry: CacheFile,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val partial = File(target.parentFile, target.name + ".part")
        if (partial.exists()) partial.delete()

        val connection = openConnection(entry.url)
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastReported = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read

                        if (written - lastReported >= 512 * 1024 || written == entry.size) {
                            onProgress(written)
                            lastReported = written
                        }
                    }
                    output.fd.sync()
                }
            }

            check(partial.length() == entry.size) {
                "Kích thước file tải về không đúng: ${entry.path}"
            }
            check(Sha256.of(partial).equals(entry.sha256, ignoreCase = true)) {
                "Hash file tải về không đúng: ${entry.path}"
            }

            if (target.exists() && !target.delete()) {
                error("Không thể thay file cũ: ${entry.path}")
            }
            check(partial.renameTo(target)) {
                "Không thể hoàn tất file: ${entry.path}"
            }
        } finally {
            connection.disconnect()
            if (partial.exists() && !target.exists()) partial.delete()
        }
    }

    private fun openConnection(rawUrl: String): HttpURLConnection {
        require(rawUrl.startsWith("https://")) { "Chỉ chấp nhận HTTPS: $rawUrl" }
        return (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VNRP-Mobile-Launcher/${vn.vnrp.mobile.BuildConfig.VERSION_NAME}")
            connect()
            check(responseCode in 200..299) {
                "HTTP $responseCode từ $rawUrl"
            }
        }
    }

    private fun createForegroundInfo(text: String, progress: Int): ForegroundInfo {
        val channelId = "cache-download"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Tải cache game",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VNRP Mobile Launcher")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, progress <= 0)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(1001, notification, serviceType)
    }

    companion object {
        const val KEY_MANIFEST_URL = "manifest_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_FILE_INDEX = "file_index"
        const val KEY_FILE_COUNT = "file_count"
        const val KEY_MANIFEST_VERSION = "manifest_version"
        const val KEY_CACHE_ROOT = "cache_root"
        const val KEY_ERROR = "error"

        fun input(manifestUrl: String): Data = workDataOf(KEY_MANIFEST_URL to manifestUrl)
    }
}
