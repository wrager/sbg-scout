package com.github.wrager.sbguserscripts.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.wrager.sbguserscripts.BuildConfig
import com.github.wrager.sbguserscripts.GameActivity
import com.github.wrager.sbguserscripts.R
import com.github.wrager.sbguserscripts.launcher.LauncherActivity
import com.github.wrager.sbguserscripts.launcher.ScriptListFragment

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("app_version")?.summary = BuildConfig.VERSION_NAME

        findPreference<Preference>("manage_scripts")?.setOnPreferenceClickListener {
            if (activity is GameActivity) {
                // В drawer: навигация внутри того же контейнера
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedInstance())
                    .addToBackStack(null)
                    .commit()
            } else {
                // Из standalone SettingsActivity: как раньше
                val intent = Intent(requireContext(), LauncherActivity::class.java)
                intent.putExtra(LauncherActivity.EXTRA_FROM_SETTINGS, true)
                startActivity(intent)
            }
            true
        }

        findPreference<Preference>("reload_game")?.setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, true).apply()
            if (activity is GameActivity) {
                // В drawer: закрываем drawer, reload произойдёт в applySettingsAfterDrawerClose
                (activity as GameActivity).closeSettingsDrawer()
            } else {
                startActivity(
                    Intent(requireContext(), GameActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            true
        }

        findPreference<Preference>("report_bug")?.setOnPreferenceClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
            true
        }
    }

    companion object {
        private const val ISSUES_URL = "https://github.com/wrager/sbg-userscripts-apk/issues"
    }
}
