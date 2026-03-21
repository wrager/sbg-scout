package com.github.wrager.sbguserscripts.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.wrager.sbguserscripts.R

/**
 * Одноэлементный адаптер с кнопкой «Добавить скрипт».
 * Используется как footer через ConcatAdapter после списка скриптов.
 */
class AddScriptAdapter(
    private val onAddScriptClick: () -> Unit,
) : RecyclerView.Adapter<AddScriptAdapter.ButtonViewHolder>() {

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_add_script, parent, false)
        return ButtonViewHolder(view)
    }

    override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
        // Статичная кнопка, биндить нечего
    }

    inner class ButtonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.setOnClickListener { onAddScriptClick() }
        }
    }
}
