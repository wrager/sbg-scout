package com.github.wrager.sbgscout.settings

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.preference.PreferenceViewHolder
import androidx.preference.R
import androidx.preference.SeekBarPreference

/**
 * SeekBarPreference с двумя отличиями от стандартного:
 *
 * 1. **Непрерывное обновление** — значение сохраняется при каждом движении ползунка,
 *    а не только при отпускании. Это позволяет [OnSharedPreferenceChangeListener]
 *    применять изменения в реальном времени (например, двигать pull-tab).
 *
 * 2. **Блокировка перехвата касаний родителем** — при касании ползунка вызывается
 *    [requestDisallowInterceptTouchEvent], чтобы DrawerLayout не интерпретировал
 *    горизонтальное движение по SeekBar как жест закрытия панели.
 */
class LiveSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0,
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        updatesContinuously = true
    }

    @SuppressLint("ClickableViewAccessibility") // Не меняем accessibility — только блокируем перехват
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val seekBar = holder.findViewById(R.id.seekbar) as? SeekBar ?: return
        seekBar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }
}
