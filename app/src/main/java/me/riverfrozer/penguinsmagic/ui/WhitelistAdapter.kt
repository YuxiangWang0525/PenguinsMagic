package me.riverfrozer.penguinsmagic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.WhitelistEntry
import me.riverfrozer.penguinsmagic.databinding.ItemWhitelistBinding

class WhitelistAdapter(
    private val onOpen: (WhitelistEntry) -> Unit,
) : ListAdapter<WhitelistEntry, WhitelistAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemWhitelistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemWhitelistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val context = holder.binding.root.context
        holder.binding.apply {
            whitelistNumber.text = entry.number
            whitelistNote.visibility = if (entry.note.isBlank()) View.GONE else View.VISIBLE
            whitelistNote.text = entry.note
            whitelistMeta.text = context.getString(
                R.string.whitelist_meta,
                StatusText.time(entry.createdAt),
            )
            root.setOnClickListener { onOpen(entry) }
            root.contentDescription = context.getString(R.string.whitelist_edit)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<WhitelistEntry>() {
            override fun areItemsTheSame(oldItem: WhitelistEntry, newItem: WhitelistEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: WhitelistEntry, newItem: WhitelistEntry) =
                oldItem == newItem
        }
    }
}