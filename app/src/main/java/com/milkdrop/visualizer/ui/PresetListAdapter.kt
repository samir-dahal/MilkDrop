package com.milkdrop.visualizer.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.presets.PresetRatings
import java.io.File

/**
 * One List entry: the preset file, and its index in the native playlist — null when it's hidden,
 * since hidden presets are left out of the playlist.
 */
data class PresetEntry(val path: String, val playlistIndex: Int?)

class PresetListAdapter(
    private val ratings: () -> PresetRatings,
    private val onHeaderFocus: (Boolean) -> Unit,
    private val onPresetClick: (PresetEntry) -> Unit,
    private val onFavouriteToggled: (PresetEntry) -> Unit,
    private val onHiddenToggled: (PresetEntry) -> Unit,
) : RecyclerView.Adapter<PresetListAdapter.ViewHolder>() {

    private var entries: List<PresetEntry> = emptyList()
    private var currentPath: String? = null

    private var lastFocusedPath: String? = null

    fun submit(newEntries: List<PresetEntry>, highlightPath: String?) {
        if (entries == newEntries) { updateCurrent(highlightPath); return }
        val old = entries
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newEntries.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].path == newEntries[b].path
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == newEntries[b]
        })
        entries = newEntries
        diff.dispatchUpdatesTo(this)
        updateCurrent(highlightPath)
    }

    fun refreshRatings(path: String) {
        val index = entries.indexOfFirst { it.path == path }
        if (index >= 0) notifyItemChanged(index, RATINGS_PAYLOAD)
    }

    /** Playback changes highlight only; never replace the list, scroll or move focus. */
    fun updateCurrent(highlightPath: String?) {
        if (highlightPath == currentPath) return
        val previous = entries.indexOfFirst { it.path == currentPath }
        currentPath = highlightPath
        val current = entries.indexOfFirst { it.path == currentPath }
        if (previous >= 0) notifyItemChanged(previous, CURRENT_PRESET_PAYLOAD)
        if (current >= 0) notifyItemChanged(current, CURRENT_PRESET_PAYLOAD)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Parent activation propagates to children: always restore each icon's
            // own saved state after changing the current-preset row highlight.
            bindState(holder, entries[position])
        } else onBindViewHolder(holder, position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val file = File(entry.path)

        holder.name.text = file.nameWithoutExtension
        holder.folder.text = file.parentFile?.name.orEmpty()
        bindState(holder, entry)
        holder.itemView.setOnClickListener {
            val selected = entries.getOrNull(holder.bindingAdapterPosition)
            if (selected != null && !ratings().isHidden(selected.path)) onPresetClick(selected)
        }
        holder.favourite.setOnClickListener {
            entries.getOrNull(holder.bindingAdapterPosition)?.let {
                onFavouriteToggled(it)
                refreshRatings(it.path)
            }
        }
        holder.hide.setOnClickListener {
            entries.getOrNull(holder.bindingAdapterPosition)?.let {
                onHiddenToggled(it)
                refreshRatings(it.path)
            }
        }
        listOf(holder.itemView, holder.favourite, holder.hide).forEachIndexed { column, control ->
            control.setOnFocusChangeListener { _, focused ->
                if (focused) lastFocusedPath = entries.getOrNull(holder.bindingAdapterPosition)?.path
            }
            control.setOnKeyListener { _, key, event ->
                if (key !in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
                else {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val row = holder.bindingAdapterPosition
                        val list = holder.itemView.parent as? RecyclerView
                        if (row != RecyclerView.NO_POSITION && list != null) when (key) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> when (column) {
                                0 -> holder.favourite.requestFocus()
                                1 -> holder.hide.requestFocus()
                                else -> onHeaderFocus(true)
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> when (column) {
                                2 -> holder.favourite.requestFocus()
                                1 -> holder.itemView.requestFocus()
                                else -> onHeaderFocus(false)
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> if (row == 0) onHeaderFocus(true) else focusRow(list, row - 1, column)
                            KeyEvent.KEYCODE_DPAD_DOWN -> if (row + 1 < entries.size) focusRow(list, row + 1, column)
                        }
                    }
                    true
                }
            }
        }
    }

    private fun bindState(holder: ViewHolder, entry: PresetEntry) {
        val currentRatings = ratings()
        val hidden = currentRatings.isHidden(entry.path)
        val favourite = currentRatings.isFavourite(entry.path)
        val context = holder.itemView.context
        holder.text.alpha = if (hidden) HIDDEN_ALPHA else 1f
        holder.itemView.isActivated = entry.path == currentPath
        holder.favourite.isActivated = favourite
        holder.hide.isActivated = hidden
        ViewCompat.setStateDescription(holder.favourite, context.getString(if (favourite) R.string.state_on else R.string.state_off))
        ViewCompat.setStateDescription(holder.hide, context.getString(if (hidden) R.string.state_on else R.string.state_off))
    }

    fun focusLastRow(list: RecyclerView, column: Int): Boolean {
        if (entries.isEmpty()) return false
        val row = entries.indexOfFirst { it.path == lastFocusedPath }.takeIf { it >= 0 }
            ?: entries.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
        focusRow(list, row, column)
        return true
    }

    private fun focusRow(list: RecyclerView, row: Int, column: Int) {
        val path = entries.getOrNull(row)?.path ?: return
        fun focus(): Boolean {
            val position = entries.indexOfFirst { it.path == path }
            val holder = list.findViewHolderForAdapterPosition(position) as? ViewHolder ?: return false
            return when (column) {
                1 -> holder.favourite.requestFocus()
                2 -> holder.hide.requestFocus()
                else -> holder.itemView.requestFocus()
            }
        }
        if (focus()) return
        list.doOnNextLayout { if (list.isShown) focus() }
        list.scrollToPosition(row)
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: View = view.findViewById(R.id.presetText)
        val name: TextView = view.findViewById(R.id.presetName)
        val folder: TextView = view.findViewById(R.id.presetFolder)
        val favourite: ImageButton = view.findViewById(R.id.btnPresetFavourite)
        val hide: ImageButton = view.findViewById(R.id.btnPresetHide)
    }

    private companion object {
        const val HIDDEN_ALPHA = 0.4f
        val CURRENT_PRESET_PAYLOAD = Any()
        val RATINGS_PAYLOAD = Any()
    }
}
