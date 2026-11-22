package me.riverfrozer.penguinsmagic.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.BlockRecord
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.data.ChainBanPolicy
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.databinding.FragmentHistoryBinding

/** 首页：谁被打掉了、属于什么类型、是哪个品牌或哪种诈骗手段。 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = HistoryAdapter(
        onChainAction = { record, action -> handleChainAction(record, action) },
    )

    private val changes: () -> Unit = { render() }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderChainStatus()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter
        binding.filterGroup.setOnCheckedStateChangeListener { _, _ -> render() }
        binding.clearHistory.setOnClickListener { confirmClear() }
        binding.openSubscriptions.setOnClickListener {
            findNavController().navigate(R.id.action_history_to_subscriptions)
        }
        binding.chainBanSwitch.isChecked = Repositories.prefs.chainBanEnabled
        binding.chainBanSwitch.setOnCheckedChangeListener { _, checked ->
            Repositories.prefs.chainBanEnabled = checked
            // 关掉就立刻解除窗口，否则用户以为停了、陌生号码却还在被拒接
            if (!checked) {
                Repositories.chain.clear()
                showSnackbar(getString(R.string.chain_ban_cleared))
            }
            binding.chainCapRow.visibility = if (checked) View.VISIBLE else View.GONE
            renderChainStatus()
        }
        binding.chainCapRow.visibility =
            if (Repositories.prefs.chainBanEnabled) View.VISIBLE else View.GONE
        binding.chainCapSlider.value = Repositories.prefs.chainBanMaxMinutes.toFloat()
        binding.chainCapSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Repositories.prefs.chainBanMaxMinutes = value.toInt()
            // 调小上限要立刻收紧正在进行的封禁，不能等下一通电话
            Repositories.chain.clampToCap()
            renderChainStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        Repositories.history.onChange(changes)
        Repositories.chain.onChange(changes)
        ticker.post(tick)
        render()
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        Repositories.history.offChange(changes)
        Repositories.chain.offChange(changes)
        super.onStop()
    }

    override fun onDestroyView() {
        binding.historyList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        val stats = Repositories.history.stats(System.currentTimeMillis())
        binding.statBlocked.text = stats.blockedTotal.toString()
        binding.statToday.text = stats.blockedToday.toString()
        binding.statMarked.text = stats.markedTotal.toString()

        val records = when (binding.filterGroup.checkedChipId) {
            R.id.filter_blocked -> Repositories.history.all().filter { it.blocked }
            R.id.filter_marked -> Repositories.history.all().filter { !it.blocked }
            else -> Repositories.history.all()
        }
        adapter.submitList(records)
        binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        renderChainStatus()
    }

    /** 封禁窗口按秒倒数：用户要能看见它在延长，也要能看见它什么时候结束、什么时候顶到上限。 */
    private fun renderChainStatus() {
        val binding = _binding ?: return
        val status = binding.chainBanStatus
        val maxMinutes = Repositories.prefs.chainBanMaxMinutes
        binding.chainCapLabel.text = getString(R.string.chain_cap_label, maxMinutes)
        if (!Repositories.prefs.chainBanEnabled) {
            status.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val chain = Repositories.chain
        status.visibility = View.VISIBLE
        if (!chain.isActive(now)) {
            status.setText(R.string.chain_ban_idle)
            status.setTextColor(StatusText.colorOf(requireContext(), StatusText.ATTR_MUTED))
            return
        }
        val remaining = StatusText.countdown(chain.remainingMs(now))
        val trigger = chain.triggerNumber()
        status.text = when {
            chain.isCapped(now) -> getString(R.string.chain_ban_active_capped, remaining, maxMinutes)
            trigger.isBlank() -> getString(R.string.chain_ban_active, remaining)
            else -> getString(R.string.chain_ban_active_trigger, remaining, trigger)
        }
        status.setTextColor(StatusText.colorOf(requireContext(), StatusText.ATTR_ERROR))
    }

    private fun handleChainAction(record: BlockRecord, action: ChainAction) {
        val now = System.currentTimeMillis()
        when (action) {
            ChainAction.RULE -> {
                val pattern = NumberNormalizer.forMatching(record.number) ?: return
                val exists = Repositories.rules.all().any {
                    it.kind == BlockRule.Kind.NUMBER && it.pattern == pattern
                }
                if (!exists) {
                    Repositories.rules.upsert(
                        BlockRule(
                            id = 0L,
                            pattern = pattern,
                            kind = BlockRule.Kind.NUMBER,
                            note = getString(R.string.chain_note_from_history),
                            createdAt = now,
                        )
                    )
                }
                showSnackbar(getString(R.string.chain_action_rule_done))
            }
            ChainAction.TEMP_PASS -> {
                Repositories.chain.tempAllow(record.number, now)
                showSnackbar(
                    getString(R.string.chain_action_pass_done, ChainBanPolicy.TEMP_PASS_MS / 60_000)
                )
            }
            ChainAction.WHITELIST -> {
                Repositories.whitelist.add(record.number, getString(R.string.chain_note_from_history), now)
                showSnackbar(getString(R.string.chain_action_whitelist_done))
            }
        }
        render()
        // 记录本身没变，DiffUtil 不会重绑；处理结果只体现在按钮与状态行上，得手动刷这一行
        val index = adapter.currentList.indexOf(record)
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> Repositories.history.clear() }
            .show()
    }

    private fun showSnackbar(message: CharSequence) {
        _binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show() }
    }

    private companion object {
        const val TICK_MS = 1000L
    }
}