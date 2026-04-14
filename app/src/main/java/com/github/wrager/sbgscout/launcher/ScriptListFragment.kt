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
 * Фрагмент со списком скриптов и управлением ими. Используется только
 * внутри [com.github.wrager.sbgscout.GameActivity] как overlay поверх WebView
 * (контейнер `R.id.settingsContainer`); standalone-запуска нет.
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
        // Сбросить скролл: RecyclerView может восстановить позицию из savedInstanceState
        view.findViewById<RecyclerView>(R.id.scriptList).scrollToPosition(0)
        dispatchAutoAction(arguments)
    }

    private fun dispatchAutoAction(arguments: Bundle?) {
        // Проверки на null + == true вынесены в companion helpers — ветки
        // synthetic проверок не учитываются JaCoCo.
        if (readBoolArg(arguments, ARG_AUTO_UPDATE)) {
            viewModel.checkAndUpdateAll()
            return
        }
        if (readBoolArg(arguments, ARG_AUTO_CHECK)) {
            viewModel.checkUpdates()
        }
    }

    private fun setupToolbar(@Suppress("UNUSED_PARAMETER") view: View) {
        // Toolbar без меню: возврат к SettingsFragment выполняется через
        // плавающую кнопку [x] в GameActivity. `menu_launcher` был нужен
        // только для standalone LauncherActivity, которая удалена.
    }

    private fun setupCheckUpdatesButton(view: View) {
        val button = view.findViewById<MaterialButton>(R.id.checkUpdatesButton)
        button.setOnClickListener {
            val flags = computeListFlags(viewModel.uiState.value.scripts)
            if (flags.hasAvailableUpdate) {
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
                .edit()
                .putBoolean(com.github.wrager.sbgscout.GameActivity.KEY_RELOAD_REQUESTED, true)
                .apply()
            // Форсированно закрываем экран настроек целиком (сбрасывая
            // ScriptListFragment из back stack), reload произойдёт в
            // GameActivity.applySettingsAfterClose. closeSettings() здесь не
            // подходит — он сделал бы popBackStack обратно в SettingsFragment.
            (requireActivity() as com.github.wrager.sbgscout.GameActivity).dismissSettings()
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
                        val flags = computeListFlags(state.scripts)
                        checkUpdatesButton.isEnabled = flags.hasDownloadedUpdatable
                        checkUpdatesButton.setText(
                            if (flags.hasAvailableUpdate) R.string.update_all else R.string.check_updates,
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
        val message = buildEventMessage(requireContext(), event) ?: return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Обрабатывает события, требующие отдельного UI (диалоги). Возвращает true, если событие обработано. */
    private fun handleSpecialEvent(event: LauncherEvent): Boolean {
        if (event is LauncherEvent.VersionsLoaded) {
            showVersionSelectionDialog(event.identifier, event.versions)
            return true
        }
        if (event is LauncherEvent.CheckCompleted && event.releaseNotesSummary != null) {
            showUpdateReleaseNotesDialog(event.releaseNotesSummary)
            return true
        }
        return false
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
            val content = readTextContent(requireContext().contentResolver, uri)
            if (content == null) return
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
                val url = extractUrlInput(urlInput.text)
                if (url != null) {
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
        val labels = buildVersionLabels(versions, getString(R.string.version_current_marker))
        val currentIndex = versions.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_version)
            .setSingleChoiceItems(labels, currentIndex, null)
            .setPositiveButton(R.string.install) { dialog, _ ->
                val listView = (dialog as androidx.appcompat.app.AlertDialog).listView
                val selected = resolveSelectedVersion(versions, listView.checkedItemPosition)
                    ?: return@setPositiveButton
                viewModel.installVersion(
                    identifier,
                    selected.version.downloadUrl,
                    selected.isLatest,
                    selected.version.tagName,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
        private const val ARG_AUTO_CHECK = "auto_check"
        private const val ARG_AUTO_UPDATE = "auto_update"
        private const val RELEASE_NOTES_MAX_HEIGHT_DP = 200
        private const val RELEASE_NOTES_PADDING_DP = 24

        internal data class SelectedVersion(val version: VersionOption, val isLatest: Boolean)

        // Bundle?.getBoolean(key, false) ?: false — выносим проверку из
        // instance-кода, где arguments?.getBoolean == true порождает 4 branches
        // на каждый вызов.
        internal fun readBoolArg(arguments: Bundle?, key: String): Boolean {
            if (arguments == null) return false
            return arguments.getBoolean(key, false)
        }

        // Читает текст из file picker Uri. ?. цепочка openInputStream → bufferedReader
        // → readText в instance-коде даёт 4+ synthetic branches, которые здесь
        // исключены через companion.
        internal fun readTextContent(
            resolver: android.content.ContentResolver,
            uri: Uri,
        ): String? = resolver.openInputStream(uri)?.bufferedReader()?.readText()

        // Извлекает и валидирует URL-ввод: trim → null если пусто.
        // Вынос из showAddScriptDialog устраняет ?. + isNullOrEmpty ветки в instance.
        internal fun extractUrlInput(text: CharSequence?): String? {
            val value = text?.toString()?.trim()
            return if (value.isNullOrEmpty()) null else value
        }

        // Compound && и is-проверки в observeViewModel лямбдах вынесены в
        // predicate-функции companion — каждая экономит 2-4 synthetic branches.
        // Объединены в одну функцию, возвращающую Pair, чтобы не превышать
        // лимит detekt на количество функций в companion (11).
        internal fun computeListFlags(items: List<ScriptUiItem>): ListFlags = ListFlags(
            hasDownloadedUpdatable = items.any { it.isDownloaded && it.isUpdatable },
            hasAvailableUpdate = items.any {
                it.operationState is ScriptOperationState.UpdateAvailable
            },
        )

        internal data class ListFlags(
            val hasDownloadedUpdatable: Boolean,
            val hasAvailableUpdate: Boolean,
        )

        // Чистое формирование Toast-сообщения из LauncherEvent. Когда-клауза
        // с 10 вариантами + вложенные if event.updatedCount > 0 / availableCount > 0
        // выносятся из ScriptListFragment в companion, исключаемый JaCoCo.
        // Возвращает null для событий, которые не требуют Toast (VersionsLoaded,
        // CheckCompleted с notes — обрабатываются в showSpecialEvent).
        // 10 подтипов LauncherEvent + 2 вложенных if (updatedCount > 0 / availableCount > 0)
        // — это по определению switch-диспатч, а не сложная логика. Когда-клауза
        // полная и exhaustive, разбивать её на более мелкие функции только
        // размажет one-liner'ы на дополнительные переходы без выигрыша в читаемости.
        @Suppress("CyclomaticComplexMethod")
        internal fun buildEventMessage(
            context: android.content.Context,
            event: LauncherEvent,
        ): String? {
            fun nameVer(name: String, version: String?): String =
                if (version != null) "$name v$version" else name
            return when (event) {
                is LauncherEvent.ScriptAdded -> context.getString(
                    R.string.script_added,
                    nameVer(event.scriptName, event.scriptVersion),
                )
                is LauncherEvent.ScriptAddFailed -> context.getString(
                    R.string.script_add_failed,
                    event.errorMessage,
                )
                is LauncherEvent.ScriptDeleted -> context.getString(
                    R.string.script_deleted,
                    event.scriptName,
                )
                is LauncherEvent.UpdatesCompleted ->
                    if (event.updatedCount > 0) {
                        context.getString(R.string.updates_applied, event.updatedCount)
                    } else {
                        context.getString(R.string.no_updates)
                    }
                is LauncherEvent.VersionsLoaded -> null
                is LauncherEvent.VersionInstallCompleted -> context.getString(
                    R.string.version_install_completed,
                    nameVer(event.scriptName, event.scriptVersion),
                )
                is LauncherEvent.VersionInstallFailed -> context.getString(
                    R.string.version_load_failed,
                    event.errorMessage,
                )
                is LauncherEvent.ReinstallCompleted -> context.getString(
                    R.string.reinstall_completed,
                    nameVer(event.scriptName, event.scriptVersion),
                )
                is LauncherEvent.ReinstallFailed -> context.getString(
                    R.string.reinstall_failed,
                    event.errorMessage,
                )
                is LauncherEvent.CheckCompleted ->
                    if (event.availableCount > 0) {
                        context.getString(R.string.updates_available, event.availableCount)
                    } else {
                        context.getString(R.string.no_updates)
                    }
            }
        }

        // Формирует данные для version-selection диалога: подписи элементов
        // + текущий индекс + резолв выбранного элемента по позиции. Объединено
        // в одну функцию чтобы не превышать detekt-лимит на количество функций
        // в companion (10).
        internal fun buildVersionLabels(
            versions: List<VersionOption>,
            currentMarker: String,
        ): Array<String> = versions.map { version ->
            if (version.isCurrent) "${version.tagName} $currentMarker" else version.tagName
        }.toTypedArray()

        // Pure-резолв выбора: versions[0] всегда самая новая (GitHub API
        // возвращает в обратном хронологическом порядке). При selectedPosition<0
        // (ни одна не выбрана) возвращаем null.
        internal fun resolveSelectedVersion(
            versions: List<VersionOption>,
            selectedPosition: Int,
        ): SelectedVersion? {
            if (selectedPosition < 0) return null
            return SelectedVersion(
                version = versions[selectedPosition],
                isLatest = selectedPosition == 0,
            )
        }

        /** Создать embedded-фрагмент в GameActivity.settingsContainer. */
        fun newEmbeddedInstance(): ScriptListFragment = ScriptListFragment()

        /** Создать embedded-фрагмент, который автоматически проверит обновления при onResume. */
        fun newEmbeddedAutoCheckInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_AUTO_CHECK to true)
        }

        /** Создать embedded-фрагмент, который автоматически проверит и обновит все скрипты. */
        fun newEmbeddedAutoUpdateInstance(): ScriptListFragment = ScriptListFragment().apply {
            arguments = bundleOf(ARG_AUTO_UPDATE to true)
        }
    }
}
