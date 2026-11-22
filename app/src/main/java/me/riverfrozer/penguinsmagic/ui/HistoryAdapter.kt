package me.riverfrozer.penguinsmagic.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.BlockRecord
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.databinding.ItemBlockRecordBinding
import me.riverfrozer.penguinsmagic.screening.ScreeningEngine

/** 连锁封禁记录上的一键处理。 */
enum class ChainAction { RULE, TEMP_PASS, WHITELIST }

class HistoryAdapter(
    private val onChainAction: (BlockRecord, ChainAction) -> Unit,
) : ListAdapter<BlockRecord, HistoryAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemBlockRecordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemBlockRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = getItem(position)
        val context = holder.binding.root.context
        val binding = holder.binding

        binding.recordNumber.text = record.number
        binding.recordStatus.setText(
            if (record.blocked) R.string.record_blocked else R.string.record_marked
        )
        binding.recordStatus.setTextColor(
            StatusText.colorOf(
                context,
                if (record.blocked) StatusText.ATTR_ERROR else StatusText.ATTR_MUTED,
            )
        )
        binding.recordTime.text = if (record.count > 1) {
            context.getString(R.string.record_time_count, StatusText.time(record.lastSeen), record.count)
        } else {
            StatusText.time(record.lastSeen)
        }

        binding.chipType.text = record.typeName.ifBlank {
            context.getString(R.string.record_unknown_type)
        }
        paintChip(binding.chipType, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer)

        if (record.detail.isBlank()) {
            binding.chipDetail.visibility = View.GONE
        } else {
            binding.chipDetail.visibility = View.VISIBLE
            binding.chipDetail.text = record.detail
            paintChip(
                binding.chipDetail,
                if (record.isScam) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorTertiaryContainer,
                if (record.isScam) MaterialR.attr.colorOnErrorContainer else MaterialR.attr.colorOnTertiaryContainer,
            )
        }

        binding.recordSource.text = context.getString(R.string.record_source, record.sourceName)
        bindChainPanel(binding, record, context)
    }

    /**
     * 连锁封禁拦下的号码多半有正常人被连累，所以每条都给出三个一键出口：
     * 升级成本机规则、暂时放行、永久加白。已经处理过的按钮就地收起，避免重复点。
     */
    private fun bindChainPanel(binding: ItemBlockRecordBinding, record: BlockRecord, context: Context) {
        if (record.reason != ScreeningEngine.REASON_CHAIN_BAN) {
            binding.chainPanel.visibility = View.GONE
            return
        }
        binding.chainPanel.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        val pattern = NumberNormalizer.forMatching(record.number)
        val ruled = pattern != null && Repositories.rules.all().any {
            it.kind == BlockRule.Kind.NUMBER && it.pattern == pattern
        }
        val whitelisted = Repositories.whitelist.contains(record.number)
        val passUntil = Repositories.chain.tempPassUntil(record.number, now)

        binding.chainToRule.visibility = if (ruled) View.GONE else View.VISIBLE
        binding.chainTempPass.visibility = if (passUntil > 0L) View.GONE else View.VISIBLE
        binding.chainToWhitelist.visibility = if (whitelisted) View.GONE else View.VISIBLE

        val states = buildList {
            if (ruled) add(context.getString(R.string.chain_state_rule))
            if (passUntil > 0L) add(context.getString(R.string.chain_state_pass, StatusText.time(passUntil)))
            if (whitelisted) add(context.getString(R.string.chain_state_whitelist))
        }
        binding.chainState.visibility = if (states.isEmpty()) View.GONE else View.VISIBLE
        binding.chainState.text = states.joinToString(" · ")

        binding.chainToRule.setOnClickListener { onChainAction(record, ChainAction.RULE) }
        binding.chainTempPass.setOnClickListener { onChainAction(record, ChainAction.TEMP_PASS) }
        binding.chainToWhitelist.setOnClickListener { onChainAction(record, ChainAction.WHITELIST) }
    }

    private fun paintChip(chip: com.google.android.material.chip.Chip, containerAttr: Int, textAttr: Int) {
        val context = chip.context
        chip.chipBackgroundColor = ColorStateList.valueOf(StatusText.colorOf(context, containerAttr))
        chip.setTextColor(StatusText.colorOf(context, textAttr))
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BlockRecord>() {
            override fun areItemsTheSame(oldItem: BlockRecord, newItem: BlockRecord) =
                oldItem.normalized == newItem.normalized && oldItem.sourceName == newItem.sourceName

            override fun areContentsTheSame(oldItem: BlockRecord, newItem: BlockRecord) = oldItem == newItem
        }
    }
}
