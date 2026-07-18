package vn.vnrp.mobile.cache

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.vnrp.mobile.BuildConfig
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
            setForeground(
                createForegroundInfo(
                    text = "Đang đọc manifest...",
                    progress = 0,
                )
            )

            val manifestUrl =
                inputData.getString(KEY_MANIFEST_URL)
                    ?: LauncherConfig.manifestUrl

            /*
             * Manifest luôn được tải với URL chống cache.
             * Các file cache thật vẫn dùng URL bình thường trong manifest.
             */
            val manifestJson = downloadText(manifestUrl)
            val manifest = CacheManifest.parse(manifestJson)

            val cacheRoot = CachePaths.root(applicationContext)

            val totalBytes = manifest.files
                .sumOf { it.size }
                .coerceAtLeast(1L)

            var processedBytes = 0L

            manifest.files.forEachIndexed { index, entry ->
                if (isStopped) {
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Tác vụ tải cache đã bị dừng.")
                    )
                }

                val target = CachePaths.safeTarget(
                    root = cacheRoot,
                    relativePath = entry.path,
                )

                val valid = isFileValid(
                    file = target,
                    expectedSize = entry.size,
                    expectedSha256 = entry.sha256,
                )

                if (!valid) {
                    target.parentFile?.mkdirs()

                    downloadFile(
                        entry = entry,
                        target = target,
                    ) { currentFileBytes ->
                        val absoluteBytes =
                            processedBytes + currentFileBytes

                        val percent =
                            ((absoluteBytes * 100L) / totalBytes)
                                .coerceIn(0L, 100L)
                                .toInt()

                        val progressData = workDataOf(
                            KEY_PROGRESS to percent,
                            KEY_CURRENT_FILE to entry.path,
                            KEY_FILE_INDEX to index + 1,
                            KEY_FILE_COUNT to manifest.files.size,
                        )

                        setProgress(progressData)

                        setForeground(
                            createForegroundInfo(
                                text = "${index + 1}/${manifest.files.size}: ${entry.path}",
                                progress = percent,
                            )
                        )
                    }

                    check(
                        isFileValid(
                            file = target,
                            expectedSize = entry.size,
                            expectedSha256 = entry.sha256,
                        )
                    ) {
                        "File không hợp lệ sau khi tải: ${entry.path}"
                    }
                }

                processedBytes += entry.size

                val completedPercent =
                    ((processedBytes * 100L) / totalBytes)
                        .coerceIn(0L, 100L)
                        .toInt()

                setProgress(
                    workDataOf(
                        KEY_PROGRESS to completedPercent,
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
                workDataOf(
                    KEY_ERROR to (
                        error.message
                            ?: error.javaClass.simpleName
                    )
                )
            )
        }
    }

    /**
     * Kiểm tra một file cache bằng dung lượng và SHA-256.
     */
    private fun isFileValid(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile) {
            return false
        }

        if (file.length() != expectedSize) {
            return false
        }

        return Sha256.of(file).equals(
            expectedSha256,
            ignoreCase = true,
        )
    }

    /**
     * Tải manifest dưới dạng văn bản.
     *
     * Timestamp được thêm vào URL nhằm tránh GitHub/CDN trả về
     * manifest cũ do cache.
     */
    private fun downloadText(url: String): String {
        val separator =
            if (url.contains("?")) "&" else "?"

        val noCacheUrl =
            "$url${separator}t=${System.currentTimeMillis()}"

        val connection = openConnection(
            rawUrl = noCacheUrl,
            noCache = true,
        )

        return try {
            connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    reader.readText()
                }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Tải một file cache vào file .part.
     *
     * Chỉ khi đúng dung lượng và SHA-256 thì file .part mới được
     * đổi thành file chính thức.
     */
    private suspend fun downloadFile(
        entry: CacheFile,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val parentDirectory =
            target.parentFile
                ?: error("Không xác định được thư mục cho ${entry.path}")

        if (!parentDirectory.exists()) {
            check(parentDirectory.mkdirs()) {
                "Không thể tạo thư mục: ${parentDirectory.absolutePath}"
            }
        }

        val partial = File(
            parentDirectory,
            "${target.name}.part",
        )

        if (partial.exists() && !partial.delete()) {
            error("Không thể xóa file tạm cũ: ${entry.path}")
        }

        val connection = openConnection(
            rawUrl = entry.url,
            noCache = false,
        )

        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)

                    var written = 0L
                    var lastReported = 0L

                    while (true) {
                        if (isStopped) {
                            error("Tác vụ tải cache đã bị dừng.")
                        }

                        val read = input.read(buffer)

                        if (read < 0) {
                            break
                        }

                        output.write(buffer, 0, read)
                        written += read

                        /*
                         * Báo tiến trình sau mỗi 512 KB hoặc khi
                         * tải đủ dung lượng dự kiến.
                         */
                        if (
                            written - lastReported >= 512 * 1024L ||
                            written == entry.size
                        ) {
                            onProgress(written)
                            lastReported = written
                        }
                    }

                    output.flush()
                    output.fd.sync()
                }
            }

            check(partial.length() == entry.size) {
                "Kích thước file tải về không đúng: ${entry.path}"
            }

            check(
                Sha256.of(partial).equals(
                    entry.sha256,
                    ignoreCase = true,
                )
            ) {
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

            /*
             * Nếu việc tải thất bại thì không giữ lại file .part.
             */
            if (partial.exists() && !target.exists()) {
                partial.delete()
            }
        }
    }

    /**
     * Mở kết nối HTTPS.
     *
     * noCache chỉ bật khi tải manifest. File game/cache vẫn được
     * phép sử dụng cache mạng và được xác minh bằng SHA-256.
     */
    private fun openConnection(
        rawUrl: String,
        noCache: Boolean = false,
    ): HttpURLConnection {
        require(rawUrl.startsWith("https://")) {
            "Chỉ chấp nhận HTTPS: $rawUrl"
        }

        return (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000

            instanceFollowRedirects = true
            requestMethod = "GET"

            setRequestProperty(
                "User-Agent",
                "VNRP-Mobile-Launcher/${BuildConfig.VERSION_NAME}",
            )

            if (noCache) {
                useCaches = false
                defaultUseCaches = false

                setRequestProperty(
                    "Cache-Control",
                    "no-cache, no-store, max-age=0",
                )

                setRequestProperty(
                    "Pragma",
                    "no-cache",
                )
            }

            connect()

            check(responseCode in 200..299) {
                "HTTP $responseCode từ $rawUrl"
            }
        }
    }

    private fun createForegroundInfo(
        text: String,
        progress: Int,
    ): ForegroundInfo {
        val channelId = "cache-download"

        val manager =
            applicationContext.getSystemService(
                NotificationManager::class.java
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Tải cache game",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val notification =
            NotificationCompat.Builder(
                applicationContext,
                channelId,
            )
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("VNRP Mobile Launcher")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(
                    100,
                    progress.coerceIn(0, 100),
                    progress <= 0,
                )
                .build()

        val serviceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        return ForegroundInfo(
            1001,
            notification,
            serviceType,
        )
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

        fun input(manifestUrl: String): Data {
            return workDataOf(
                KEY_MANIFEST_URL to manifestUrl
            )
        }
    }
}