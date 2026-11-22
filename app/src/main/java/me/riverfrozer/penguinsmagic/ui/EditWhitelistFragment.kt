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
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.data.WhitelistEntry
import me.riverfrozer.penguinsmagic.databinding.FragmentEditWhitelistBinding

/** 添加或编辑一个白名单号码。 */
class EditWhitelistFragment : Fragment() {

    private var _binding: FragmentEditWhitelistBinding? = null
    private val binding get() = _binding!!

    private var entryId = WhitelistFragment.NEW_ENTRY
    private var existing: WhitelistEntry? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEditWhitelistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        entryId = arguments?.getLong(WhitelistFragment.EXTRA_ID) ?: WhitelistFragment.NEW_ENTRY
        existing = Repositories.whitelist.find(entryId)

        binding.numberLayout.helperText = getString(R.string.whitelist_helper_number)
        binding.noteLayout.helperText = getString(R.string.whitelist_helper_note)

        val loaded = existing
        if (loaded != null) {
            binding.inputNumber.setText(loaded.number)
            binding.inputNote.setText(loaded.note)
            binding.deleteEntry.visibility = View.VISIBLE
        }

        binding.saveEntry.setOnClickListener { save() }
        binding.deleteEntry.setOnClickListener { confirmDelete() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun save() {
        val raw = binding.inputNumber.text?.toString()?.trim().orEmpty()
        val number = NumberNormalizer.forMatching(raw)
        if (number == null) {
            binding.numberLayout.error = getString(
                if (raw.isBlank()) R.string.whitelist_error_required else R.string.whitelist_error_invalid
            )
            return
        }
        val base = existing
            ?: WhitelistEntry(id = 0L, number = number, createdAt = System.currentTimeMillis())
        val candidate = base.copy(
            number = number,
            note = binding.inputNote.text?.toString()?.trim().orEmpty(),
        )
        if (Repositories.whitelist.all().any { it.id != candidate.id && it.number == number }) {
            binding.numberLayout.error = getString(R.string.whitelist_error_duplicate)
            return
        }
        binding.numberLayout.error = null
        Repositories.whitelist.upsert(candidate)
        showSnackbar(R.string.whitelist_saved)
        findNavController().popBackStack()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.whitelist_delete_title)
            .setMessage(R.string.whitelist_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.whitelist_delete) { _, _ ->
                Repositories.whitelist.delete(entryId)
                showSnackbar(R.string.whitelist_removed)
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