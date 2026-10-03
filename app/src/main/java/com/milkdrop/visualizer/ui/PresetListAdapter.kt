package com.milkdrop.visualizer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.presets.PresetRatings
import java.io.File

/**
 * One List entry: the preset file, and its index in the native playlist — null when it's hidden,
 * since hidden presets are left out of the playlist.
 */
data class PresetEntry(val path: String, val playlistIndex: Int?)

class PresetListAdapter(
    private val ratings: PresetRatings,
    private val onPresetClick: (PresetEntry) -> Unit,
    private val onFavouriteToggled: (PresetEntry) -> Unit,
    private val onHiddenToggled: (PresetEntry) -> Unit,
) : RecyclerView.Adapter<PresetListAdapter.ViewHolder>() {

    private var entries: List<PresetEntry> = emptyList()
    private var currentPath: String? = null

    fun submit(newEntries: List<PresetEntry>, highlightPath: String?) {
        entries = newEntries
        currentPath = highlightPath
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val file = File(entry.path)
        val hidden = ratings.isHidden(entry.path)
        val favourite = ratings.isFavourite(entry.path)
        val context = holder.itemView.context

        holder.name.text = file.nameWithoutExtension
        holder.folder.text = file.parentFile?.name.orEmpty()
        holder.text.alpha = if (hidden) HIDDEN_ALPHA else 1f
        holder.itemView.setBackgroundColor(if (entry.path == currentPath) HIGHLIGHT_COLOR else 0)
        holder.itemView.setOnClickListener { if (entry.playlistIndex != null) onPresetClick(entry) }

        holder.favourite.isActivated = favourite
        ViewCompat.setStateDescription(holder.favourite, context.getString(if (favourite) R.string.state_on else R.string.state_off))
        holder.favourite.setOnClickListener {
            onFavouriteToggled(entry)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        holder.hide.isActivated = hidden
        ViewCompat.setStateDescription(holder.hide, context.getString(if (hidden) R.string.state_on else R.string.state_off))
        holder.hide.setOnClickListener { onHiddenToggled(entry) }
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
        const val HIGHLIGHT_COLOR = 0x552196F3
        const val HIDDEN_ALPHA = 0.4f
    }
}
