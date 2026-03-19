package com.github.wrager.sbguserscripts.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbguserscripts.GameActivity
import com.github.wrager.sbguserscripts.R
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.preset.ConflictDetector
import com.github.wrager.sbguserscripts.script.preset.StaticConflictRules
import com.github.wrager.sbguserscripts.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbguserscripts.script.storage.ScriptStorageImpl
import com.github.wrager.sbguserscripts.script.injector.InjectionStateStorage
import com.github.wrager.sbguserscripts.script.updater.DefaultHttpFetcher
import com.github.wrager.sbguserscripts.script.updater.GithubReleaseProvider
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloader
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateChecker
import com.github.wrager.sbguserscripts.settings.SettingsActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {

    private val viewModel: LauncherViewModel by viewModels {
        val preferences = getSharedPreferences("scripts", MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(filesDir, "scripts"))
        val scriptStorage = ScriptStorageImpl(preferences, fileStorage)
        val conflictDetector = ConflictDetector(StaticConflictRules())
        val httpFetcher = DefaultHttpFetcher()
        val downloader = ScriptDownloader(httpFetcher, scriptStorage)
        val updateChecker = ScriptUpdateChecker(httpFetcher, scriptStorage)
        val githubReleaseProvider = GithubReleaseProvider(httpFetcher)
        val injectionStateStorage = InjectionStateStorage(preferences)
        LauncherViewModel.Factory(
            scriptStorage,
            conflictDetector,
            downloader,
            updateChecker,
            githubReleaseProvider,
            injectionStateStorage,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val openedFromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (!openedFromSettings && prefs.getBoolean(KEY_SETUP_COMPLETED, false)) {
            startActivity(Intent(this, GameActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_launcher)
        setupToolbar()
        setupScriptList()
        setupButtons(showLaunchButton = !openedFromSettings)
        observeViewModel()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener { finish() }
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_check_updates -> {
                    viewModel.checkUpdates()
                    Toast.makeText(this, R.string.checking_updates, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_update_all -> {
                    viewModel.updateAllScripts()
                    Toast.makeText(this, R.string.checking_updates, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupScriptList() {
        val scriptList = findViewById<RecyclerView>(R.id.scriptList)
        scriptList.layoutManager = LinearLayoutManager(this)
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

    private fun setupButtons(showLaunchButton: Boolean) {
        val launchButton = findViewById<MaterialButton>(R.id.launchButton)
        if (showLaunchButton) {
            launchButton.setOnClickListener {
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
                startActivity(Intent(this, GameActivity::class.java))
            }
        } else {
            launchButton.visibility = View.GONE
        }
        findViewById<FloatingActionButton>(R.id.addScriptButton).setOnClickListener {
            showAddScriptDialog()
        }
    }

    private fun observeViewModel() {
        val scriptList = findViewById<RecyclerView>(R.id.scriptList)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val emptyText = findViewById<TextView>(R.id.emptyText)
        val launchButton = findViewById<MaterialButton>(R.id.launchButton)
        val reloadButton = findViewById<MaterialButton>(R.id.reloadButton)
        val adapter = scriptList.adapter as ScriptListAdapter

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
                    launchButton.isEnabled = !state.isLoading
                    reloadButton.visibility = if (state.reloadNeeded) View.VISIBLE else View.GONE

                    if (!state.isLoading) {
                        adapter.submitList(state.scripts)
                        emptyText.visibility =
                            if (state.scripts.isEmpty()) View.VISIBLE else View.GONE
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
                getString(R.string.script_added, event.scriptName)
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
                getString(R.string.version_install_completed, event.scriptName)
            is LauncherEvent.VersionInstallFailed ->
                getString(R.string.version_load_failed, event.errorMessage)
            is LauncherEvent.ReinstallCompleted ->
                getString(R.string.reinstall_completed, event.scriptName)
            is LauncherEvent.ReinstallFailed ->
                getString(R.string.reinstall_failed, event.errorMessage)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
                    viewModel.installVersion(identifier, versions[selectedPosition].downloadUrl)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        const val EXTRA_FROM_SETTINGS = "from_settings"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        internal const val KEY_RELOAD_REQUESTED = "reload_requested"
    }
}
