package com.github.wrager.sbgscout.settings

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
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.launcher.ScriptListFragment
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.updater.AppUpdateChecker
import com.github.wrager.sbgscout.updater.AppUpdateInstaller
import com.github.wrager.sbgscout.updater.AppUpdateResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

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
                (activity as GameActivity).closeSettingsDrawer()
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
                // В drawer: переходим к менеджеру скриптов, где пользователь
                // может нажать кнопку проверки обновлений
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedInstance())
                    .addToBackStack(null)
                    .commit()
            } else {
                startActivity(Intent(requireContext(), LauncherActivity::class.java))
            }
            true
        }

        findPreference<Preference>("report_bug")?.setOnPreferenceClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
            true
        }
    }

    private fun checkAppUpdate() {
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
        if (activity is GameActivity) {
            (activity as GameActivity).showAppUpdateDialog(
                result.downloadUrl, result.releaseNotes, httpFetcher,
            )
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_update_available)
                .setPositiveButton(R.string.app_update_download) { _, _ ->
                    downloadUpdate(result.downloadUrl, httpFetcher)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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

    companion object {
        private const val ISSUES_URL = "https://github.com/wrager/sbg-scout/issues"
    }
}
