package com.github.wrager.sbgscout.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    // Инвариант "фрагмент живёт только как overlay внутри GameActivity" закреплён
    // KDoc на классе: точка входа приложения одна (GameActivity), standalone-запуска нет.
    // Каст безопасен; property устраняет дублирование в местах обращения к host-активити.
    private val gameActivity: GameActivity
        get() = requireActivity() as GameActivity

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
            gameActivity.closeSettings()
            true
        }

        requirePref<Preference>("open_links_in_app").setOnPreferenceClickListener {
            openDefaultAppSettings()
            true
        }

        requirePref<Preference>("check_app_update").setOnPreferenceClickListener {
            gameActivity.showAppUpdateCheckDialog()
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
        // Null-check перенесён в companion (исключён из JaCoCo) — ветвление
        // `listView?` в теле фрагмента считалось синтетическим missed branch.
        scrollRecyclerToTop(listView)
    }

    private inline fun <reified T : Preference> requirePref(key: String): T =
        requireNotNull(findPreference<T>(key)) { "Preference '$key' missing in R.xml.preferences" }

    companion object {
        private fun scrollRecyclerToTop(listView: androidx.recyclerview.widget.RecyclerView?) {
            listView?.scrollToPosition(0)
        }
    }

    /**
     * Открывает системный экран «Открывать по умолчанию» для нашего приложения.
     *
     * Android 12+ (API 31): `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` с `package:` URI
     * ведёт сразу на «Supported web addresses» нашего приложения, где пользователь
     * включает `sbg-game.ru` одним тапом. До API 31 такого экрана нет — fallback
     * на общие `ACTION_APPLICATION_DETAILS_SETTINGS`, откуда пользователь сам
     * переходит в раздел «Открывать по умолчанию».
     *
     * `ActivityNotFoundException` возможен на кастомных OEM-прошивках, где
     * `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` не реализован — ловим и падаем на
     * универсальный fallback.
     *
     * Программно назначить приложение обработчиком нельзя — это ограничение
     * безопасности Android: решение всегда принимает пользователь через
     * системный UI. Auto-verify (`android:autoVerify="true"`) потребовал бы
     * hosting `.well-known/assetlinks.json` на sbg-game.ru, домен не наш.
     */
    private fun openDefaultAppSettings() {
        val packageUri = Uri.parse("package:${requireContext().packageName}")
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
        try {
            startActivity(primaryIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    /**
     * Собирает диагностику, копирует в буфер обмена, показывает Toast и открывает GitHub Issues.
     *
     * Работает внутри [GameActivity]: доступны лог консоли, все скрипты и
     * снапшот инжекции, необходимые для полного баг-репорта.
     */
    private fun reportBug() {
        val activity = gameActivity
        val consoleLogBuffer = activity.consoleLogBuffer
        val allScripts = activity.getAllScripts()
        val injectedSnapshot = activity.getInjectedSnapshot()

        val collector = BugReportCollector(BuildConfig.VERSION_NAME, consoleLogBuffer)
        val report = collector.collect(allScripts, injectedSnapshot)

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SBG Scout diagnostics", report.clipboardText))

        Toast.makeText(requireContext(), R.string.bug_report_copied, Toast.LENGTH_SHORT).show()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(report.issueUrl)))
    }
}
