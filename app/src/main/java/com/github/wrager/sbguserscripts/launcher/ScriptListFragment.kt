package com.github.wrager.sbguserscripts.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbguserscripts.R
import com.github.wrager.sbguserscripts.script.injector.InjectionStateStorage
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.preset.ConflictDetector
import com.github.wrager.sbguserscripts.script.preset.StaticConflictRules
import com.github.wrager.sbguserscripts.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbguserscripts.script.storage.ScriptStorageImpl
import com.github.wrager.sbguserscripts.script.updater.DefaultHttpFetcher
import com.github.wrager.sbguserscripts.script.updater.GithubReleaseProvider
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloader
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateChecker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    private val viewModel: LauncherViewModel by viewModels {
        val context = requireContext()
        val preferences = context.getSharedPreferences("scripts", android.content.Context.MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(context.filesDir, "scripts"))
        val scriptStorage = ScriptStorageImpl(preferences, fileStorage)
        val conflictDetector = ConflictDetector(StaticConflictRules())
        val httpFetcher = DefaultHttpFetcher()
        val downloader = ScriptDownloader(httpFetcher, scriptStorage)
        val updateChecker = ScriptUpdateChecker(httpFetcher, scriptStorage)
        val githubReleaseProvider = GithubReleaseProvider(httpFetcher)
        val injectionStateStorage = InjectionStateStorage(preferences)
        val autoUpdateEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("auto_update_scripts", true)
        LauncherViewModel.Factory(
            scriptStorage,
            conflictDetector,
            downloader,
            updateChecker,
            githubReleaseProvider,
            injectionStateStorage,
            autoUpdateEnabled,
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
        setupUpdateControls(view)
        setupScriptList(view)
        setupButtons(view)
        observeViewModel(view)
    }

    private fun setupToolbar(view: View) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        if (isEmbedded) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
            // В drawer не показываем пункт «Настройки» — мы уже в настройках
        } else {
            toolbar.inflateMenu(R.menu.menu_launcher)
        }
    }

    private fun setupUpdateControls(view: View) {
        val autoUpdateCheckbox = view.findViewById<android.widget.CheckBox>(R.id.autoUpdateCheckbox)
        autoUpdateCheckbox.isChecked = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("auto_update_scripts", true)
        autoUpdateCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean("auto_update_scripts", isChecked).apply()
        }

        view.findViewById<MaterialButton>(R.id.checkUpdatesButton).setOnClickListener {
            viewModel.checkUpdates()
            Toast.makeText(requireContext(), R.string.checking_updates, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.updateAllButton).setOnClickListener {
            viewModel.updateAllScripts()
            Toast.makeText(requireContext(), R.string.checking_updates, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupScriptList(view: View) {
        val scriptList = view.findViewById<RecyclerView>(R.id.scriptList)
        scriptList.layoutManager = LinearLayoutManager(requireContext())
        scriptList.adapter = ScriptListAdapter(
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
        )
    }

    private fun setupButtons(view: View) {
        val reloadButton = view.findViewById<MaterialButton>(R.id.reloadButton)
        reloadButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, true).apply()
            if (isEmbedded) {
                // В drawer: закрываем весь drawer, reload произойдёт в applySettingsAfterDrawerClose
                (requireActivity() as com.github.wrager.sbguserscripts.GameActivity).closeSettingsDrawer()
            }
        }

        view.findViewById<FloatingActionButton>(R.id.addScriptButton).setOnClickListener {
            showAddScriptDialog()
        }
    }

    private fun observeViewModel(view: View) {
        val scriptList = view.findViewById<RecyclerView>(R.id.scriptList)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val reloadButton = view.findViewById<MaterialButton>(R.id.reloadButton)
        val adapter = scriptList.adapter as ScriptListAdapter

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
        if (event is LauncherEvent.VersionsLoaded) {
            showVersionSelectionDialog(event.identifier, event.versions)
            return
        }
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

        /** Создать фрагмент для использования внутри drawer (embedded mode). */
        fun newEmbeddedInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_EMBEDDED to true)
        }
    }
}
