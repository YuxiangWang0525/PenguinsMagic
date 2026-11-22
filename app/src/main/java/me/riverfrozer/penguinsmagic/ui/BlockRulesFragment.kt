package me.riverfrozer.penguinsmagic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.databinding.FragmentBlockRulesBinding

/** 本机拦截规则：整号或号段，命中即拦，不花订阅配额。 */
class BlockRulesFragment : Fragment() {

    private var _binding: FragmentBlockRulesBinding? = null
    private val binding get() = _binding!!

    private val adapter = BlockRuleAdapter(
        onOpen = { open(it) },
        onToggle = { rule, enabled ->
            Repositories.rules.update(rule.id) { it.enabled = enabled }
        },
    )

    private val changes: () -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBlockRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.ruleList.layoutManager = LinearLayoutManager(requireContext())
        binding.ruleList.adapter = adapter
        binding.addRule.setOnClickListener { open(null) }
    }

    override fun onStart() {
        super.onStart()
        Repositories.rules.onChange(changes)
        render()
    }

    override fun onStop() {
        Repositories.rules.offChange(changes)
        super.onStop()
    }

    override fun onDestroyView() {
        binding.ruleList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun open(rule: BlockRule?) {
        val args = Bundle().apply { putLong(EXTRA_ID, rule?.id ?: NEW_RULE) }
        findNavController().navigate(R.id.action_rules_to_edit, args)
    }

    private fun render() {
        val binding = _binding ?: return
        // 新学来的号码排最前：用户打开这页多半是想看刚才那条拦截留下了什么
        val rules = Repositories.rules.all().sortedByDescending { it.createdAt }
        adapter.submitList(rules)
        binding.emptyState.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_ID = "ruleId"
        const val NEW_RULE = -1L
    }
}
