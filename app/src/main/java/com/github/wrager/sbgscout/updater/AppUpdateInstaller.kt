package com.github.wrager.sbgscout.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.wrager.sbgscout.script.updater.HttpFetcher
import java.io.File

/**
 * Скачивает APK-обновление и запускает системный установщик.
 */
class AppUpdateInstaller(
    private val context: Context,
    private val httpFetcher: HttpFetcher,
) {

    /**
     * Скачивает APK по [downloadUrl] в кеш-директорию и запускает установку.
     */
    suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val updateDir = File(context.cacheDir, UPDATE_DIR)
        val apkFile = File(updateDir, APK_FILENAME)
        httpFetcher.fetchToFile(downloadUrl, apkFile, onProgress = onProgress)
        launchInstaller(apkFile)
    }

    private fun launchInstaller(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val UPDATE_DIR = "updates"
        private const val APK_FILENAME = "sbg-scout.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
