package com.github.wrager.sbgscout.launcher

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.preset.ConflictDetector
import com.github.wrager.sbgscout.script.preset.StaticConflictRules
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.script.updater.ScriptUpdateChecker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import kotlinx.coroutines.launch

/**
 * Фрагмент со списком скриптов и управлением ими.
 *
 * Используется в двух контекстах:
 * - Внутри [LauncherActivity] как standalone экран (с кнопкой «Запустить»)
 * - Внутри drawer в GameActivity (embedded mode, без кнопки запуска)
 */
class ScriptListFragment : Fragment() {

    private lateinit var scriptAdapter: ScriptListAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { readFileAndInstall(it) } }

    private val viewModel: LauncherViewModel by viewModels {
        val context = requireContext()
        val preferences = context.getSharedPreferences("scripts", android.content.Context.MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(context.filesDir, "scripts"))
        val scriptStorage = ScriptStorageImpl(preferences, fileStorage)
        val conflictDetector = ConflictDetector(StaticConflictRules())
        val httpFetcher = DefaultHttpFetcher()
        val scriptInstaller = ScriptInstaller(scriptStorage)
        val downloader = ScriptDownloader(httpFetcher, scriptInstaller)
        val updateChecker = ScriptUpdateChecker(httpFetcher, scriptStorage)
        val githubReleaseProvider = GithubReleaseProvider(httpFetcher)
        val injectionStateStorage = InjectionStateStorage(preferences)
        val scriptProvisioner = DefaultScriptProvisioner(scriptStorage, downloader, preferences)
        LauncherViewModel.Factory(
            scriptStorage,
            conflictDetector,
            downloader,
            scriptInstaller,
            updateChecker,
            githubReleaseProvider,
            injectionStateStorage,
            scriptProvisioner,
        )
    }

    private val isEmbedded: Boolean
        get() = arguments?.getBoolean(ARG_EMBEDDED, false) == true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_script_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(view)
        setupCheckUpdatesButton(view)
        setupScriptList(view)
        setupButtons(view)
        observeViewModel(view)
        when {
            arguments?.getBoolean(ARG_AUTO_UPDATE, false) == true -> viewModel.checkAndUpdateAll()
            arguments?.getBoolean(ARG_AUTO_CHECK, false) == true -> viewModel.checkUpdates()
        }
    }

    private fun setupToolbar(view: View) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        if (isEmbedded) {
            // В embedded-режиме нет кнопки «Назад» в toolbar — возврат к
            // SettingsFragment выполняется через плавающую кнопку [x] в GameActivity.
            // В drawer не показываем пункт «Настройки» — мы уже в настройках.
        } else {
            toolbar.inflateMenu(R.menu.menu_launcher)
        }
    }

    private fun setupCheckUpdatesButton(view: View) {
        val button = view.findViewById<MaterialButton>(R.id.checkUpdatesButton)
        button.setOnClickListener {
            val hasUpdates = viewModel.uiState.value.scripts.any {
                it.operationState is ScriptOperationState.UpdateAvailable
            }
            if (hasUpdates) {
                viewModel.updateAll()
            } else {
                viewModel.checkUpdates()
            }
        }
    }

    private fun setupScriptList(view: View) {
        val scriptList = view.findViewById<RecyclerView>(R.id.scriptList)
        scriptList.layoutManager = LinearLayoutManager(requireContext())
        scriptAdapter = ScriptListAdapter(
            onToggleChanged = { identifier, enabled ->
                viewModel.toggleScript(identifier, enabled)
            },
            onDownloadRequested = { identifier ->
                viewModel.downloadScript(identifier)
            },
            onUpdateRequested = { identifier ->
                viewModel.updateScript(identifier)
            },
            onOverflowClick = { anchor, item ->
                showScriptOverflowMenu(anchor, item)
            },
            onAddScriptClick = { showAddScriptDialog() },
            onAddScriptFromFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
        scriptList.adapter = scriptAdapter
    }

    private fun setupButtons(view: View) {
        val reloadButton = view.findViewById<MaterialButton>(R.id.reloadButton)
        reloadButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, true).apply()
            if (isEmbedded) {
                // В игровом экране: форсированно закрываем экран настроек целиком
                // (сбрасывая ScriptListFragment из back stack), reload произойдёт
                // в applySettingsAfterClose. closeSettings() здесь не подходит —
                // он сделал бы popBackStack обратно в SettingsFragment.
                (requireActivity() as com.github.wrager.sbgscout.GameActivity).dismissSettings()
            }
        }

    }

    private fun observeViewModel(view: View) {
        val scriptList = view.findViewById<RecyclerView>(R.id.scriptList)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val reloadButton = view.findViewById<MaterialButton>(R.id.reloadButton)
        val checkUpdatesButton = view.findViewById<MaterialButton>(R.id.checkUpdatesButton)
        val adapter = scriptAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    scriptList.visibility = if (state.isLoading) View.GONE else View.VISIBLE
                    reloadButton.visibility = if (state.reloadNeeded) View.VISIBLE else View.GONE

                    if (!state.isLoading) {
                        adapter.submitList(state.scripts)
                        emptyText.visibility =
                            if (state.scripts.isEmpty()) View.VISIBLE else View.GONE
                        checkUpdatesButton.isEnabled = state.scripts.any { it.isDownloaded }
                        val hasUpdates = state.scripts.any {
                            it.operationState is ScriptOperationState.UpdateAvailable
                        }
                        checkUpdatesButton.setText(
                            if (hasUpdates) R.string.update_all else R.string.check_updates,
                        )
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event -> handleEvent(event) }
            }
        }
    }

    private fun handleEvent(event: LauncherEvent) {
        if (handleSpecialEvent(event)) return
        val message = when (event) {
            is LauncherEvent.ScriptAdded ->
                getString(R.string.script_added, formatNameWithVersion(event.scriptName, event.scriptVersion))
            is LauncherEvent.ScriptAddFailed ->
                getString(R.string.script_add_failed, event.errorMessage)
            is LauncherEvent.ScriptDeleted ->
                getString(R.string.script_deleted, event.scriptName)
            is LauncherEvent.UpdatesCompleted ->
                if (event.updatedCount > 0) {
                    getString(R.string.updates_applied, event.updatedCount)
                } else {
                    getString(R.string.no_updates)
                }
            is LauncherEvent.VersionsLoaded -> return
            is LauncherEvent.VersionInstallCompleted ->
                getString(
                    R.string.version_install_completed,
                    formatNameWithVersion(event.scriptName, event.scriptVersion),
                )
            is LauncherEvent.VersionInstallFailed ->
                getString(R.string.version_load_failed, event.errorMessage)
            is LauncherEvent.ReinstallCompleted ->
                getString(
                    R.string.reinstall_completed,
                    formatNameWithVersion(event.scriptName, event.scriptVersion),
                )
            is LauncherEvent.ReinstallFailed ->
                getString(R.string.reinstall_failed, event.errorMessage)
            is LauncherEvent.CheckCompleted ->
                if (event.availableCount > 0) {
                    getString(R.string.updates_available, event.availableCount)
                } else {
                    getString(R.string.no_updates)
                }
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Обрабатывает события, требующие отдельного UI (диалоги). Возвращает true, если событие обработано. */
    private fun handleSpecialEvent(event: LauncherEvent): Boolean = when {
        event is LauncherEvent.VersionsLoaded -> {
            showVersionSelectionDialog(event.identifier, event.versions); true
        }
        event is LauncherEvent.CheckCompleted && event.releaseNotesSummary != null -> {
            showUpdateReleaseNotesDialog(event.releaseNotesSummary); true
        }
        else -> false
    }

    /** Показывает диалог с release notes обновлений и кнопкой «Обновить». */
    private fun showUpdateReleaseNotesDialog(releaseNotes: String) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val maxHeightPx = (RELEASE_NOTES_MAX_HEIGHT_DP * density).toInt()
        val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()
        val textView = TextView(context).apply {
            text = releaseNotes
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setTextIsSelectable(true)
        }
        val scrollView = android.widget.ScrollView(context).apply { addView(textView) }
        val container = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val constrainedHeight = View.MeasureSpec.makeMeasureSpec(
                    maxHeightPx, View.MeasureSpec.AT_MOST,
                )
                super.onMeasure(widthMeasureSpec, constrainedHeight)
            }
        }
        container.addView(scrollView)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.script_updates_title)
            .setView(container)
            .setPositiveButton(R.string.update_all) { _, _ ->
                viewModel.updateAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun readFileAndInstall(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return
            viewModel.addScriptFromContent(content)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.file_read_error, exception.message),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showAddScriptDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_script, null)
        val urlInput = dialogView.findViewById<TextInputEditText>(R.id.scriptUrlInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_script)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = urlInput.text?.toString()?.trim()
                if (!url.isNullOrEmpty()) {
                    viewModel.addScript(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showScriptOverflowMenu(anchor: View, item: ScriptUiItem) {
        val popup = PopupMenu(requireContext(), anchor)
        if (item.isGithubHosted) {
            popup.menu.add(R.string.select_version)
        } else {
            popup.menu.add(R.string.reinstall)
        }
        popup.menu.add(R.string.delete_script)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.title) {
                getString(R.string.select_version) -> {
                    viewModel.loadVersions(item.identifier)
                    true
                }
                getString(R.string.reinstall) -> {
                    viewModel.reinstallScript(item.identifier)
                    true
                }
                getString(R.string.delete_script) -> {
                    showDeleteConfirmation(item.identifier)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showVersionSelectionDialog(
        identifier: ScriptIdentifier,
        versions: List<VersionOption>,
    ) {
        val labels = versions.map { version ->
            if (version.isCurrent) {
                "${version.tagName} ${getString(R.string.version_current_marker)}"
            } else {
                version.tagName
            }
        }.toTypedArray()

        val currentIndex = versions.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_version)
            .setSingleChoiceItems(labels, currentIndex, null)
            .setPositiveButton(R.string.install) { dialog, _ ->
                val listView = (dialog as androidx.appcompat.app.AlertDialog).listView
                val selectedPosition = listView.checkedItemPosition
                if (selectedPosition >= 0) {
                    // versions[0] — самая новая (GitHub API отдаёт в обратном хронологическом порядке)
                    val selected = versions[selectedPosition]
                    val isLatest = selectedPosition == 0
                    viewModel.installVersion(identifier, selected.downloadUrl, isLatest, selected.tagName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatNameWithVersion(name: String, version: String?): String =
        if (version != null) "$name v$version" else name

    private fun showDeleteConfirmation(identifier: ScriptIdentifier) {
        val scriptName = viewModel.uiState.value.scripts
            .find { it.identifier == identifier }?.name ?: return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_script)
            .setMessage(getString(R.string.delete_script_confirmation, scriptName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteScript(identifier)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val ARG_EMBEDDED = "embedded"
        private const val ARG_AUTO_CHECK = "auto_check"
        private const val ARG_AUTO_UPDATE = "auto_update"
        private const val RELEASE_NOTES_MAX_HEIGHT_DP = 200
        private const val RELEASE_NOTES_PADDING_DP = 24

        /** Создать фрагмент для использования внутри drawer (embedded mode). */
        fun newEmbeddedInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_EMBEDDED to true)
        }

        /** Создать embedded фрагмент, который автоматически проверит обновления. */
        fun newEmbeddedAutoCheckInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_EMBEDDED to true, ARG_AUTO_CHECK to true)
        }

        /** Создать embedded фрагмент, который автоматически проверит и обновит все скрипты. */
        fun newEmbeddedAutoUpdateInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_EMBEDDED to true, ARG_AUTO_UPDATE to true)
        }
    }
}
