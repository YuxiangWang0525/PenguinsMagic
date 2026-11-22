package me.riverfrozer.penguinsmagic.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.databinding.ItemBlockRuleBinding

class BlockRuleAdapter(
    private val onOpen: (BlockRule) -> Unit,
    private val onToggle: (BlockRule, Boolean) -> Unit,
) : ListAdapter<BlockRule, BlockRuleAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemBlockRuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemBlockRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = getItem(position)
        val context = holder.binding.root.context
        holder.binding.apply {
            rulePattern.text = rule.pattern
            ruleKind.setText(
                if (rule.kind == BlockRule.Kind.PREFIX) R.string.rule_kind_prefix_row else R.string.rule_kind_number_row
            )
            ruleNote.visibility = if (rule.note.isBlank()) View.GONE else View.VISIBLE
            ruleNote.text = rule.note
            ruleMeta.text = origin(context, rule)
            ruleEnabled.setOnCheckedChangeListener(null)
            ruleEnabled.isChecked = rule.enabled
            ruleEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
            root.setOnClickListener { onOpen(rule) }
            root.contentDescription = context.getString(R.string.rule_edit)
        }
    }

    /** 自动学来的条目要写清是哪条订阅判的，用户才知道该不该把它升级成号段。 */
    private fun origin(context: Context, rule: BlockRule): String =
        if (rule.learnedFrom.isBlank()) {
            context.getString(R.string.rule_origin_manual, StatusText.time(rule.createdAt))
        } else {
            context.getString(R.string.rule_origin_learned, rule.learnedFrom, StatusText.time(rule.createdAt))
        }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BlockRule>() {
            override fun areItemsTheSame(oldItem: BlockRule, newItem: BlockRule) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: BlockRule, newItem: BlockRule) = oldItem == newItem
        }
    }
}
