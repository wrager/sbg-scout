package com.github.wrager.sbgscout.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.preset.ConflictDetector
import com.github.wrager.sbgscout.script.preset.StaticConflictRules
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.script.updater.ScriptUpdateChecker
import com.github.wrager.sbgscout.settings.SettingsActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {

    private lateinit var scriptAdapter: ScriptListAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { readFileAndInstall(it) } }

    private val viewModel: LauncherViewModel by viewModels {
        val preferences = getSharedPreferences("scripts", MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(filesDir, "scripts"))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)
        setupEdgeToEdge()
        setupToolbar()
        setupUpdateControls()
        setupScriptList()
        observeViewModel()
    }

    private fun setupEdgeToEdge() {
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupUpdateControls() {
        val autoUpdateCheckbox = findViewById<android.widget.CheckBox>(R.id.autoUpdateCheckbox)
        autoUpdateCheckbox.isChecked = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("auto_update_scripts", true)
        autoUpdateCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("auto_update_scripts", isChecked).apply()
        }

        findViewById<MaterialButton>(R.id.checkUpdatesButton).setOnClickListener {
            viewModel.checkUpdates()
        }
    }

    private fun setupScriptList() {
        val scriptList = findViewById<RecyclerView>(R.id.scriptList)
        scriptList.layoutManager = LinearLayoutManager(this)
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

    private fun observeViewModel() {
        val scriptList = findViewById<RecyclerView>(R.id.scriptList)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val emptyText = findViewById<TextView>(R.id.emptyText)
        val reloadButton = findViewById<MaterialButton>(R.id.reloadButton)
        val checkUpdatesButton = findViewById<MaterialButton>(R.id.checkUpdatesButton)
        val adapter = scriptAdapter

        reloadButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(KEY_RELOAD_REQUESTED, true).apply()
            startActivity(
                Intent(this, GameActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    scriptList.visibility = if (state.isLoading) View.GONE else View.VISIBLE
                    reloadButton.visibility = if (state.reloadNeeded) View.VISIBLE else View.GONE

                    if (!state.isLoading) {
                        adapter.submitList(state.scripts)
                        emptyText.visibility =
                            if (state.scripts.isEmpty()) View.VISIBLE else View.GONE
                        checkUpdatesButton.isEnabled = state.scripts.any { it.isDownloaded }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun readFileAndInstall(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return
            viewModel.addScriptFromContent(content)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            Toast.makeText(
                this,
                getString(R.string.file_read_error, exception.message),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showAddScriptDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_script, null)
        val urlInput = dialogView.findViewById<TextInputEditText>(R.id.scriptUrlInput)

        MaterialAlertDialogBuilder(this)
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
        val popup = PopupMenu(this, anchor)
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

        MaterialAlertDialogBuilder(this)
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

    private fun formatNameWithVersion(name: String, version: String?): String {
        return if (version != null) "$name v$version" else name
    }

    private fun showDeleteConfirmation(identifier: ScriptIdentifier) {
        val scriptName = viewModel.uiState.value.scripts
            .find { it.identifier == identifier }?.name ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_script)
            .setMessage(getString(R.string.delete_script_confirmation, scriptName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteScript(identifier)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        internal const val KEY_RELOAD_REQUESTED = "reload_requested"
    }
}
