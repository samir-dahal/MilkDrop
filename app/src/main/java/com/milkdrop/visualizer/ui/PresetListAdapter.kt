package com.milkdrop.visualizer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.milkdrop.visualizer.R
import java.io.File

/** One playlist entry: its real index in the native playlist, and the full preset file path. */
data class PresetEntry(val index: Int, val path: String)

class PresetListAdapter(
    private val onPresetClick: (PresetEntry) -> Unit,
) : RecyclerView.Adapter<PresetListAdapter.ViewHolder>() {

    private var entries: List<PresetEntry> = emptyList()
    private var currentIndex: Int = -1

    fun submit(newEntries: List<PresetEntry>, highlightIndex: Int) {
        entries = newEntries
        currentIndex = highlightIndex
        notifyDataSetChanged()
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
        holder.itemView.isSelected = entry.index == currentIndex
        holder.itemView.setBackgroundColor(if (entry.index == currentIndex) HIGHLIGHT_COLOR else 0)
        holder.itemView.setOnClickListener { onPresetClick(entry) }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.presetName)
        val folder: TextView = view.findViewById(R.id.presetFolder)
    }

    private companion object {
        const val HIGHLIGHT_COLOR = 0x552196F3
    }
}
