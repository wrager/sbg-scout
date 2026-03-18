package com.github.wrager.sbguserscripts.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbguserscripts.R
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.google.android.material.materialswitch.MaterialSwitch

class ScriptListAdapter(
    private val onToggleChanged: (ScriptIdentifier, Boolean) -> Unit,
    private val onOverflowClick: (View, ScriptUiItem) -> Unit,
) : ListAdapter<ScriptUiItem, ScriptListAdapter.ScriptViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_script, parent, false)
        return ScriptViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.scriptName)
        private val detailsText: TextView = itemView.findViewById(R.id.scriptDetails)
        private val toggle: MaterialSwitch = itemView.findViewById(R.id.scriptToggle)
        private val overflowButton: ImageButton = itemView.findViewById(R.id.overflowButton)
        private val conflictWarning: TextView = itemView.findViewById(R.id.conflictWarning)

        fun bind(item: ScriptUiItem) {
            nameText.text = item.name

            val details = buildList {
                item.version?.let { add("v$it") }
                item.author?.let { add(it) }
            }.joinToString(" \u00b7 ")
            detailsText.text = details
            detailsText.visibility = if (details.isNotEmpty()) View.VISIBLE else View.GONE

            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = item.enabled
            toggle.setOnCheckedChangeListener { _, isChecked ->
                onToggleChanged(item.identifier, isChecked)
            }

            overflowButton.setOnClickListener { view ->
                onOverflowClick(view, item)
            }

            if (item.conflictNames.isNotEmpty()) {
                conflictWarning.text = itemView.context.getString(
                    R.string.conflict_warning,
                    item.conflictNames.joinToString(", "),
                )
                conflictWarning.visibility = View.VISIBLE
            } else {
                conflictWarning.visibility = View.GONE
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ScriptUiItem>() {
            override fun areItemsTheSame(oldItem: ScriptUiItem, newItem: ScriptUiItem): Boolean =
                oldItem.identifier == newItem.identifier

            override fun areContentsTheSame(oldItem: ScriptUiItem, newItem: ScriptUiItem): Boolean =
                oldItem == newItem
        }
    }
}
