package com.github.wrager.sbgscout.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.diagnostic.BugReportCollector
import com.github.wrager.sbgscout.launcher.ScriptListFragment

/**
 * Preference-фрагмент, работающий только как overlay внутри [GameActivity]
 * (путь через клик по `settingsButton` или преmapку preference `manage_scripts`).
 * Standalone-запуска нет — единственный entry-point приложения — [GameActivity].
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // `requirePref` падает с понятным сообщением, если XML не содержит
        // preference — это недостижимо в проде, но даёт non-null reference
        // и устраняет synthetic `?.` branches, которые JaCoCo считает
        // непокрытыми.
        requirePref<Preference>("app_version").summary = BuildConfig.VERSION_NAME

        requirePref<Preference>("manage_scripts").setOnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedInstance())
                .addToBackStack(null)
                .commit()
            true
        }

        requirePref<Preference>("reload_game").setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(GameActivity.KEY_RELOAD_REQUESTED, true).apply()
            (requireActivity() as GameActivity).closeSettings()
            true
        }

        requirePref<Preference>("check_app_update").setOnPreferenceClickListener {
            (requireActivity() as GameActivity).showAppUpdateCheckDialog()
            true
        }

        requirePref<Preference>("check_script_updates").setOnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedAutoCheckInstance())
                .addToBackStack(null)
                .commit()
            true
        }

        requirePref<Preference>("report_bug").setOnPreferenceClickListener {
            reportBug()
            true
        }
    }

    fun scrollToTop() {
        // listView nullable только когда view ещё не inflated — вызов после
        // onResume гарантирует, что он установлен.
        requireNotNull(listView).scrollToPosition(0)
    }

    private inline fun <reified T : Preference> requirePref(key: String): T =
        requireNotNull(findPreference<T>(key)) { "Preference '$key' missing in R.xml.preferences" }

    /**
     * Собирает диагностику, копирует в буфер обмена, показывает Toast и открывает GitHub Issues.
     *
     * Работает внутри [GameActivity]: доступны лог консоли, все скрипты и
     * снапшот инжекции, необходимые для полного баг-репорта.
     */
    private fun reportBug() {
        val gameActivity = requireActivity() as GameActivity
        val consoleLogBuffer = gameActivity.consoleLogBuffer
        val allScripts = gameActivity.getAllScripts()
        val injectedSnapshot = gameActivity.getInjectedSnapshot()

        val collector = BugReportCollector(BuildConfig.VERSION_NAME, consoleLogBuffer)
        val report = collector.collect(allScripts, injectedSnapshot)

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SBG Scout diagnostics", report.clipboardText))

        Toast.makeText(requireContext(), R.string.bug_report_copied, Toast.LENGTH_SHORT).show()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(report.issueUrl)))
    }
}
