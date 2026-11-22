package me.riverfrozer.penguinsmagic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.data.RulePatterns
import me.riverfrozer.penguinsmagic.databinding.FragmentEditBlockRuleBinding

/** 添加或编辑一条本机规则：整号，或一段号码前缀。 */
class EditBlockRuleFragment : Fragment() {

    private var _binding: FragmentEditBlockRuleBinding? = null
    private val binding get() = _binding!!

    private var ruleId = BlockRulesFragment.NEW_RULE
    private var existing: BlockRule? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEditBlockRuleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ruleId = arguments?.getLong(BlockRulesFragment.EXTRA_ID) ?: BlockRulesFragment.NEW_RULE
        existing = Repositories.rules.find(ruleId)

        binding.patternLayout.helperText = getString(R.string.rule_helper_pattern)
        binding.noteLayout.helperText = getString(R.string.rule_helper_note)

        val loaded = existing
        if (loaded != null) {
            binding.inputPattern.setText(loaded.pattern)
            binding.inputNote.setText(loaded.note)
            binding.ruleEnabled.isChecked = loaded.enabled
            binding.deleteRule.visibility = View.VISIBLE
        }
        binding.kindGroup.check(
            if (loaded?.kind == BlockRule.Kind.PREFIX) R.id.kind_prefix else R.id.kind_number
        )
        binding.kindGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) applyKind()
        }
        applyKind()

        binding.saveRule.setOnClickListener { save() }
        binding.deleteRule.setOnClickListener { confirmDelete() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun selectedKind(): BlockRule.Kind =
        if (binding.kindGroup.checkedButtonId == R.id.kind_prefix) BlockRule.Kind.PREFIX else BlockRule.Kind.NUMBER

    private fun applyKind() {
        val prefix = selectedKind() == BlockRule.Kind.PREFIX
        binding.kindHint.setText(if (prefix) R.string.rule_hint_prefix else R.string.rule_hint_number)
        binding.patternLayout.hint =
            getString(if (prefix) R.string.rule_field_prefix else R.string.rule_field_pattern)
    }

    private fun save() {
        val kind = selectedKind()
        val raw = binding.inputPattern.text?.toString()?.trim().orEmpty()
        val pattern = RulePatterns.canonical(raw, kind)
        if (pattern == null) {
            binding.patternLayout.error = getString(patternError(raw, kind))
            return
        }
        val base = existing
            ?: BlockRule(id = 0L, pattern = pattern, kind = kind, createdAt = System.currentTimeMillis())
        val candidate = base.copy(
            pattern = pattern,
            kind = kind,
            note = binding.inputNote.text?.toString()?.trim().orEmpty(),
            enabled = binding.ruleEnabled.isChecked,
        )
        // 同一条规则写两遍只会让列表变长、命中结果不变，直接拦下来告诉用户
        if (Repositories.rules.all().any { it.id != candidate.id && it.pattern == pattern && it.kind == kind }) {
            binding.patternLayout.error = getString(R.string.rule_error_duplicate)
            return
        }
        binding.patternLayout.error = null
        Repositories.rules.upsert(candidate)
        showSnackbar(R.string.rule_saved)
        findNavController().popBackStack()
    }

    private fun patternError(raw: String, kind: BlockRule.Kind): Int {
        if (raw.isBlank()) return R.string.rule_error_required
        val digits = NumberNormalizer.normalize(raw)?.count { it.isDigit() } ?: 0
        return if (kind == BlockRule.Kind.PREFIX && digits in 1 until RulePatterns.MIN_PREFIX_DIGITS) {
            R.string.rule_error_prefix_short
        } else {
            R.string.rule_error_invalid
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_delete_title)
            .setMessage(R.string.rule_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rule_delete) { _, _ ->
                Repositories.rules.delete(ruleId)
                showSnackbar(R.string.rule_removed)
                findNavController().popBackStack()
            }
            .show()
    }

    private fun showSnackbar(messageRes: Int) {
        _binding?.let {
            Snackbar.make(it.root, getString(messageRes), Snackbar.LENGTH_LONG).show()
        }
    }
}
