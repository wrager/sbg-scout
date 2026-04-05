package com.github.wrager.sbgscout.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.diagnostic.BugReportCollector
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.launcher.ScriptListFragment
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.updater.AppUpdateChecker
import com.github.wrager.sbgscout.updater.AppUpdateInstaller
import com.github.wrager.sbgscout.updater.AppUpdateResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("app_version")?.summary = BuildConfig.VERSION_NAME

        findPreference<Preference>("manage_scripts")?.setOnPreferenceClickListener {
            if (activity is GameActivity) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedInstance())
                    .addToBackStack(null)
                    .commit()
            } else {
                startActivity(Intent(requireContext(), LauncherActivity::class.java))
            }
            true
        }

        findPreference<Preference>("reload_game")?.setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, true).apply()
            if (activity is GameActivity) {
                (activity as GameActivity).closeSettings()
            } else {
                startActivity(
                    Intent(requireContext(), GameActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            true
        }

        findPreference<Preference>("check_app_update")?.setOnPreferenceClickListener {
            checkAppUpdate()
            true
        }

        findPreference<Preference>("check_script_updates")?.setOnPreferenceClickListener {
            if (activity is GameActivity) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedAutoCheckInstance())
                    .addToBackStack(null)
                    .commit()
            } else {
                startActivity(Intent(requireContext(), LauncherActivity::class.java))
            }
            true
        }

        findPreference<Preference>("report_bug")?.setOnPreferenceClickListener {
            reportBug()
            true
        }
    }

    /**
     * Собирает диагностику, копирует в буфер обмена, показывает Toast и открывает GitHub Issues.
     *
     * В контексте GameActivity доступен лог консоли, все скрипты и снапшот инжекции.
     * В контексте SettingsActivity (без WebView) — только информация об устройстве и скриптах
     * (снапшот инжекции недоступен, все enabled-скрипты помечаются как ⏳).
     */
    private fun reportBug() {
        val gameActivity = activity as? GameActivity
        val consoleLogBuffer = gameActivity?.consoleLogBuffer
        val allScripts = gameActivity?.getAllScripts() ?: getAllScriptsFromStorage()
        val injectedSnapshot = gameActivity?.getInjectedSnapshot()

        val collector = BugReportCollector(BuildConfig.VERSION_NAME, consoleLogBuffer)
        val report = collector.collect(allScripts, injectedSnapshot)

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SBG Scout diagnostics", report.clipboardText))

        Toast.makeText(requireContext(), R.string.bug_report_copied, Toast.LENGTH_SHORT).show()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(report.issueUrl)))
    }

    /** Получает все установленные скрипты напрямую из хранилища (для контекста без GameActivity). */
    private fun getAllScriptsFromStorage(): List<UserScript> {
        val context = requireContext()
        val preferences = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(context.filesDir, "scripts"))
        val storage = ScriptStorageImpl(preferences, fileStorage)
        return storage.getAll()
    }

    private fun checkAppUpdate() {
        if (activity is GameActivity) {
            (activity as GameActivity).showAppUpdateCheckDialog()
            return
        }
        // Fallback для контекста без GameActivity
        val httpFetcher = DefaultHttpFetcher()
        val githubReleaseProvider = GithubReleaseProvider(httpFetcher)
        val checker = AppUpdateChecker(githubReleaseProvider, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            val preference = findPreference<Preference>("check_app_update")
            preference?.isEnabled = false
            try {
                when (val result = checker.check()) {
                    is AppUpdateResult.UpdateAvailable -> showUpdateDialog(result, httpFetcher)
                    is AppUpdateResult.UpToDate ->
                        Toast.makeText(requireContext(), R.string.app_up_to_date, Toast.LENGTH_SHORT).show()
                    is AppUpdateResult.CheckFailed ->
                        Toast.makeText(requireContext(), R.string.app_update_check_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                preference?.isEnabled = true
            }
        }
    }

    private fun showUpdateDialog(result: AppUpdateResult.UpdateAvailable, httpFetcher: DefaultHttpFetcher) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_update_available)
            .setPositiveButton(R.string.app_update_download) { _, _ ->
                downloadUpdate(result.downloadUrl, httpFetcher)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadUpdate(downloadUrl: String, httpFetcher: DefaultHttpFetcher) {
        val context = requireContext()
        val installer = AppUpdateInstaller(context.applicationContext, httpFetcher)
        Toast.makeText(context, R.string.app_update_downloading, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                installer.downloadAndInstall(downloadUrl)
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.app_update_download_failed, exception.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

}
