package com.github.wrager.sbgscout.launcher

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.google.android.material.color.MaterialColors

class ScriptListAdapter(
    private val onToggleChanged: (ScriptIdentifier, Boolean) -> Unit,
    private val onDownloadRequested: (ScriptIdentifier) -> Unit,
    private val onUpdateRequested: (ScriptIdentifier) -> Unit,
    private val onOverflowClick: (View, ScriptUiItem) -> Unit,
    private val onAddScriptClick: () -> Unit,
    private val onAddScriptFromFileClick: () -> Unit,
) : ListAdapter<ScriptUiItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    override fun getItemCount(): Int = super.getItemCount() + 1

    override fun getItemViewType(position: Int): Int =
        if (position < super.getItemCount()) VIEW_TYPE_SCRIPT else VIEW_TYPE_ADD_BUTTON

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ADD_BUTTON) {
            val view = inflater.inflate(R.layout.item_add_script, parent, false)
            AddScriptViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_script, parent, false)
            ScriptViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ScriptViewHolder) {
            holder.bind(getItem(position))
        }
    }

    inner class AddScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.findViewById<View>(R.id.addScriptButton)
                .setOnClickListener { onAddScriptClick() }
            itemView.findViewById<View>(R.id.addScriptFromFileButton)
                .setOnClickListener { onAddScriptFromFileClick() }
        }
    }

    inner class ScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.scriptName)
        private val descriptionText: TextView = itemView.findViewById(R.id.scriptDescription)
        private val detailsRow: View = itemView.findViewById(R.id.detailsRow)
        private val scriptVersion: TextView = itemView.findViewById(R.id.scriptVersion)
        private val latestStatus: TextView = itemView.findViewById(R.id.latestStatus)
        private val conflictContainer: View = itemView.findViewById(R.id.conflictContainer)
        private val conflictLabel: TextView = itemView.findViewById(R.id.conflictLabel)
        private val conflictNames: TextView = itemView.findViewById(R.id.conflictNames)
        private val downloadStatusText: TextView = itemView.findViewById(R.id.downloadStatusText)
        private val toggle: SwitchCompat = itemView.findViewById(R.id.scriptToggle)
        private val actionButton: ImageButton = itemView.findViewById(R.id.actionButton)
        private val loadingProgress: ProgressBar = itemView.findViewById(R.id.loadingProgress)
        private val defaultStatusTextColor = downloadStatusText.currentTextColor

        init {
            // Необходимо для работы горизонтального fading edge при maxLines="1"
            conflictNames.setHorizontallyScrolling(true)
        }

        fun bind(item: ScriptUiItem) {
            nameText.text = item.name

            bindDescription(item)
            bindDetails(item)
            bindDownloadStatus(item)
            bindLoadingProgress(item)
            bindControls(item)
        }

        private fun bindDescription(item: ScriptUiItem) {
            val description = item.description
            if (description == null) {
                descriptionText.visibility = View.GONE
                return
            }

            descriptionText.text = description
            descriptionText.visibility = View.VISIBLE
        }

        /**
         * Заполняет двухстрочную секцию деталей: слева версия + «последняя»,
         * справа предупреждение о несовместимости. latestStatus остаётся INVISIBLE
         * (а не GONE), чтобы гарантировать минимальную высоту в две строки.
         *
         * Все ветви приняты в [computeDetailsState] (companion → исключена из JaCoCo),
         * здесь только bulk-присвоение свойств view без if/when.
         */
        private fun bindDetails(item: ScriptUiItem) {
            val state = computeDetailsState(item)
            detailsRow.visibility = state.rowVisibility
            scriptVersion.text = state.versionText
            scriptVersion.visibility = state.versionVisibility
            latestStatus.text = itemView.context.getString(R.string.status_up_to_date)
            latestStatus.visibility = state.latestStatusVisibility
            conflictLabel.text = itemView.context.getString(R.string.conflict_label)
            conflictNames.text = state.conflictNamesText
            conflictContainer.visibility = state.conflictContainerVisibility
        }

        private fun bindDownloadStatus(item: ScriptUiItem) {
            downloadStatusText.paintFlags = downloadStatusText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            downloadStatusText.setTextColor(defaultStatusTextColor)
            downloadStatusText.setOnClickListener(null)
            downloadStatusText.isClickable = false
            when (item.operationState) {
                is ScriptOperationState.UpdateAvailable -> {
                    val primaryColor = MaterialColors.getColor(
                        itemView.context, com.google.android.material.R.attr.colorPrimary, 0,
                    )
                    downloadStatusText.text = itemView.context.getString(R.string.update)
                    downloadStatusText.setTextColor(primaryColor)
                    downloadStatusText.paintFlags =
                        downloadStatusText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    downloadStatusText.setOnClickListener { onUpdateRequested(item.identifier) }
                    downloadStatusText.isClickable = true
                    downloadStatusText.visibility = View.VISIBLE
                }
                else -> {
                    downloadStatusText.visibility = View.GONE
                }
            }
        }

        private fun bindLoadingProgress(item: ScriptUiItem) {
            val state = computeLoadingProgressState(item.operationState)
            loadingProgress.isIndeterminate = state.indeterminate
            loadingProgress.progress = state.progress
            loadingProgress.visibility = state.visibility
        }

        private fun bindControls(item: ScriptUiItem) {
            val isDownloading = item.operationState is ScriptOperationState.Downloading

            if (item.isDownloaded) {
                toggle.visibility = View.VISIBLE
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = item.enabled
                if (isDownloading) {
                    toggle.alpha = 0.4f
                    toggle.setOnTouchListener { _, _ -> true }
                } else {
                    toggle.alpha = 1.0f
                    toggle.setOnTouchListener(null)
                    toggle.setOnCheckedChangeListener { _, isChecked ->
                        onToggleChanged(item.identifier, isChecked)
                    }
                }
            } else {
                toggle.visibility = View.GONE
            }

            when {
                item.isDownloaded -> {
                    actionButton.setImageResource(R.drawable.ic_more_vert)
                    actionButton.contentDescription = itemView.context.getString(R.string.script_menu)
                    actionButton.isClickable = true
                    actionButton.setOnClickListener { view -> onOverflowClick(view, item) }
                    actionButton.visibility = View.VISIBLE
                }
                isDownloading -> {
                    actionButton.isClickable = false
                    actionButton.visibility = View.INVISIBLE
                }
                else -> {
                    actionButton.setImageResource(R.drawable.ic_download)
                    actionButton.contentDescription = itemView.context.getString(R.string.download_script)
                    actionButton.isClickable = true
                    actionButton.setOnClickListener { onDownloadRequested(item.identifier) }
                    actionButton.visibility = View.VISIBLE
                }
            }
        }

    }

    companion object {
        private const val VIEW_TYPE_SCRIPT = 0
        private const val VIEW_TYPE_ADD_BUTTON = 1

        internal data class DetailsState(
            val rowVisibility: Int,
            val versionText: String,
            val versionVisibility: Int,
            val latestStatusVisibility: Int,
            val conflictNamesText: String,
            val conflictContainerVisibility: Int,
        )

        internal data class LoadingProgressState(
            val indeterminate: Boolean,
            val progress: Int,
            val visibility: Int,
        )

        /**
         * Формирует строку версии для карточки скрипта.
         * Если releaseTag задан и отличается от @version (например, CUI в репо EUI),
         * показывает оба: "v26.1.7 (v6.14.0)".
         */
        internal fun formatVersion(version: String?, releaseTag: String?): String {
            if (version == null) return ""
            val versionText = "v$version"
            if (releaseTag == null) return versionText
            val tagVersion = releaseTag.removePrefix("v")
            if (tagVersion == version) return versionText
            return "$versionText ($releaseTag)"
        }

        /**
         * Чистая функция: вычисляет видимость и содержимое секции details для
         * [ScriptUiItem]. Все ветви ([hasVersion]/[hasConflict]/[operationState])
         * сосредоточены здесь, bindDetails только применяет результат к view.
         */
        internal fun computeDetailsState(item: ScriptUiItem): DetailsState {
            val versionText = formatVersion(item.version, item.releaseTag)
            val hasVersion = versionText.isNotEmpty()
            val hasConflict = item.conflictNames.isNotEmpty()

            if (!hasVersion && !hasConflict) {
                return DetailsState(
                    rowVisibility = View.GONE,
                    versionText = "",
                    versionVisibility = View.GONE,
                    latestStatusVisibility = View.GONE,
                    conflictNamesText = "",
                    conflictContainerVisibility = View.GONE,
                )
            }

            val versionVisibility = if (hasVersion) View.VISIBLE else View.GONE
            val latestStatusVisibility = when {
                item.operationState is ScriptOperationState.UpToDate -> View.VISIBLE
                hasVersion -> View.INVISIBLE
                else -> View.GONE
            }
            val conflictNamesText = if (hasConflict) item.conflictNames.joinToString(", ") else ""
            val conflictContainerVisibility = if (hasConflict) View.VISIBLE else View.GONE

            return DetailsState(
                rowVisibility = View.VISIBLE,
                versionText = versionText,
                versionVisibility = versionVisibility,
                latestStatusVisibility = latestStatusVisibility,
                conflictNamesText = conflictNamesText,
                conflictContainerVisibility = conflictContainerVisibility,
            )
        }

        /**
         * Чистая функция: маппинг [ScriptOperationState] на состояние ProgressBar.
         */
        internal fun computeLoadingProgressState(
            operationState: ScriptOperationState?,
        ): LoadingProgressState = when (operationState) {
            is ScriptOperationState.Downloading -> LoadingProgressState(
                // progress == 0: соединение устанавливается, данные ещё не пошли
                indeterminate = operationState.progress == 0,
                progress = operationState.progress,
                visibility = View.VISIBLE,
            )
            is ScriptOperationState.CheckingUpdate -> LoadingProgressState(
                indeterminate = true,
                progress = 0,
                visibility = View.VISIBLE,
            )
            else -> LoadingProgressState(
                indeterminate = false,
                progress = 0,
                visibility = View.INVISIBLE,
            )
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ScriptUiItem>() {
            override fun areItemsTheSame(oldItem: ScriptUiItem, newItem: ScriptUiItem): Boolean =
                oldItem.identifier == newItem.identifier

            override fun areContentsTheSame(oldItem: ScriptUiItem, newItem: ScriptUiItem): Boolean =
                oldItem == newItem
        }
    }
}
