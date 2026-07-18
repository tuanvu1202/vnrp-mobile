package vn.vnrp.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import vn.vnrp.mobile.cache.CacheDownloadWorker
import vn.vnrp.mobile.cache.CachePaths
import vn.vnrp.mobile.databinding.ActivityMainBinding
import vn.vnrp.mobile.web.WebUiActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Download still works without notification permission while app is visible. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cachePathText.text = "Cache: ${CachePaths.root(this).absolutePath}"
        requestNotificationPermissionIfNeeded()
        observeWork()

        binding.updateButton.setOnClickListener { enqueueCacheUpdate() }
        binding.webUiButton.setOnClickListener {
            startActivity(Intent(this, WebUiActivity::class.java))
        }
        binding.playButton.setOnClickListener { GameLauncher.launch(this) }
    }

    private fun enqueueCacheUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<CacheDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(CacheDownloadWorker.input(LauncherConfig.manifestUrl))
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            LauncherConfig.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun observeWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(LauncherConfig.UNIQUE_WORK_NAME)
            .observe(this) { works ->
                val work = works.firstOrNull() ?: return@observe
                val progress = work.progress.getInt(CacheDownloadWorker.KEY_PROGRESS, 0)
                val currentFile = work.progress.getString(CacheDownloadWorker.KEY_CURRENT_FILE).orEmpty()
                val index = work.progress.getInt(CacheDownloadWorker.KEY_FILE_INDEX, 0)
                val count = work.progress.getInt(CacheDownloadWorker.KEY_FILE_COUNT, 0)

                binding.downloadProgress.progress = progress
                binding.updateButton.isEnabled = work.state !in setOf(
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.ENQUEUED,
                )

                when (work.state) {
                    WorkInfo.State.ENQUEUED -> binding.statusText.text = "Đang chờ mạng..."
                    WorkInfo.State.RUNNING -> binding.statusText.text =
                        "Đang tải $index/$count · $progress%\n$currentFile"
                    WorkInfo.State.SUCCEEDED -> {
                        val version = work.outputData.getString(
                            CacheDownloadWorker.KEY_MANIFEST_VERSION
                        ).orEmpty()
                        binding.statusText.text = "Cache đã sẵn sàng · phiên bản $version"
                        binding.downloadProgress.progress = 100
                        binding.playButton.isEnabled = true
                    }
                    WorkInfo.State.FAILED -> {
                        val error = work.outputData.getString(CacheDownloadWorker.KEY_ERROR)
                            ?: "Không rõ lỗi"
                        binding.statusText.text = "Tải cache thất bại: $error"
                        binding.playButton.isEnabled = false
                    }
                    WorkInfo.State.CANCELLED -> binding.statusText.text = "Đã hủy tải cache."
                    else -> Unit
                }
            }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
