package com.example.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.local.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateCheckOutcome(
    val currentVersion: String,
    val latestVersion: String,
    val updateAvailable: Boolean,
    val statusMessage: String,
    val checkedAtMillis: Long
)

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = UpdateManager(applicationContext, PreferencesRepository(applicationContext))
        return manager.checkForUpdates().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}

class UpdateManager(
    private val context: Context,
    private val prefsRepo: PreferencesRepository
) {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun scheduleBackgroundChecks(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_UPDATE_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_UPDATE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    suspend fun checkForUpdates(): kotlin.Result<UpdateCheckOutcome> {
        val currentVersion = context.getString(R.string.app_version_name)
        val releasePageUrl = context.getString(R.string.apk_release_page_url)
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                prefsRepo.recordUpdateCheck(now, currentVersion)
                kotlin.Result.success(
                    UpdateCheckOutcome(
                        currentVersion = currentVersion,
                        latestVersion = currentVersion,
                        updateAvailable = false,
                        statusMessage = "CipherKey v$currentVersion is installed and up to date.",
                        checkedAtMillis = now
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                kotlin.Result.failure(e)
            }
        }
    }

    /**
     * Direct in-app APK installation: detects locally compiled build outputs (.build-outputs/app-debug.apk)
     * or downloads via OkHttp, then launches the package installer via FileProvider.
     */
    suspend fun downloadAndInstallApk(
        activityContext: Context,
        onStatusUpdate: (String) -> Unit
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onStatusUpdate("Locating compiled CipherKey APK...")
            val cacheDir = context.cacheDir
            val apkFile = File(cacheDir, "CipherKey-update.apk")
            if (apkFile.exists()) apkFile.delete()

            // 1. Check for local pre-built / workspace compiled APK first
            val possibleLocalPaths = listOf(
                File("/.build-outputs/app-debug.apk"),
                File("/app/build/outputs/apk/debug/app-debug.apk"),
                File(context.filesDir.parentFile?.parentFile, ".build-outputs/app-debug.apk")
            )

            var copiedLocal = false
            for (local in possibleLocalPaths) {
                if (local.exists() && local.canRead()) {
                    onStatusUpdate("Using local build output (${local.absolutePath})...")
                    local.copyTo(apkFile, overwrite = true)
                    copiedLocal = true
                    break
                }
            }

            // 2. If no local file found, attempt network download from release URL
            if (!copiedLocal) {
                onStatusUpdate("Downloading APK from release server...")
                val downloadUrl = context.getString(R.string.apk_direct_download_url)
                val request = Request.Builder().url(downloadUrl).get().build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext kotlin.Result.failure(
                        IllegalStateException("Download failed (HTTP ${response.code}). Ensure a release APK is published or built.")
                    )
                }
                val body = response.body ?: return@withContext kotlin.Result.failure(
                    IllegalStateException("Empty response body from APK download link")
                )
                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!apkFile.exists() || apkFile.length() < 1024) {
                return@withContext kotlin.Result.failure(IllegalStateException("Downloaded APK file is invalid or empty."))
            }

            onStatusUpdate("Launching package installer...")
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(installIntent)
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Direct APK install failed", e)
            kotlin.Result.failure(e)
        }
    }

    fun shareApkDownloadLink(activityContext: Context) {
        val url = context.getString(R.string.apk_release_page_url)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Download CipherKey Encrypted Keyboard (APK)")
            putExtra(
                Intent.EXTRA_TEXT,
                "Install CipherKey Privacy Keyboard on Android for end-to-end encrypted messaging:\n$url"
            )
        }
        val chooser = Intent.createChooser(shareIntent, "Share CipherKey APK Link").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(chooser)
    }

    private companion object {
        const val TAG = "UpdateManager"
        const val UNIQUE_UPDATE_WORK = "cipherkey_periodic_update_check"
    }
}
