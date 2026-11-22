package me.riverfrozer.penguinsmagic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.data.WhitelistEntry
import me.riverfrozer.penguinsmagic.databinding.FragmentWhitelistBinding

/** 白名单：这里面的号码永远放行，连锁封禁也拦不到。 */
class WhitelistFragment : Fragment() {

    private var _binding: FragmentWhitelistBinding? = null
    private val binding get() = _binding!!

    private val adapter = WhitelistAdapter(onOpen = { open(it) })

    private val changes: () -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentWhitelistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.whitelistList.layoutManager = LinearLayoutManager(requireContext())
        binding.whitelistList.adapter = adapter
        binding.addWhitelist.setOnClickListener { open(null) }
    }

    override fun onStart() {
        super.onStart()
        Repositories.whitelist.onChange(changes)
        render()
    }

    override fun onStop() {
        Repositories.whitelist.offChange(changes)
        super.onStop()
    }

    override fun onDestroyView() {
        binding.whitelistList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun open(entry: WhitelistEntry?) {
        val args = Bundle().apply { putLong(EXTRA_ID, entry?.id ?: NEW_ENTRY) }
        findNavController().navigate(R.id.action_whitelist_to_edit, args)
    }

    private fun render() {
        val binding = _binding ?: return
        val entries = Repositories.whitelist.all().sortedByDescending { it.createdAt }
        adapter.submitList(entries)
        binding.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_ID = "whitelistId"
        const val NEW_ENTRY = -1L
    }
}