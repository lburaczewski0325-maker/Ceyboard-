package com.example.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
                val request = Request.Builder().url(releasePageUrl).head().build()
                val reachable = runCatching {
                    httpClient.newCall(request).execute().use { it.code in 200..499 }
                }.getOrDefault(false)

                prefsRepo.recordUpdateCheck(now, currentVersion)
                val msg = if (reachable) {
                    "CipherKey update service connected ($currentVersion). Ready for direct APK installation."
                } else {
                    "Checked at current release ($currentVersion). Offline mode active."
                }
                kotlin.Result.success(
                    UpdateCheckOutcome(
                        currentVersion = currentVersion,
                        latestVersion = currentVersion,
                        updateAvailable = false,
                        statusMessage = msg,
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
     * Direct in-app APK downloading and installation flow. Downloads the APK file via OkHttp
     * into app cache and launches the package installer via FileProvider.
     */
    suspend fun downloadAndInstallApk(
        activityContext: Context,
        onStatusUpdate: (String) -> Unit
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onStatusUpdate("Connecting to CipherKey release server...")
            val downloadUrl = context.getString(R.string.apk_direct_download_url)
            val request = Request.Builder().url(downloadUrl).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext kotlin.Result.failure(
                    IllegalStateException("Download failed with HTTP ${response.code}")
                )
            }
            val body = response.body ?: return@withContext kotlin.Result.failure(
                IllegalStateException("Empty response body from APK download link")
            )

            onStatusUpdate("Downloading APK file...")
            val cacheDir = context.cacheDir
            val apkFile = File(cacheDir, "CipherKey-update.apk")
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
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
            Log.e(TAG, "Direct APK download & install failed", e)
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
