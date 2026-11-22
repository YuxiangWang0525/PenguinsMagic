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
import me.riverfrozer.penguinsmagic.data.Subscription
import me.riverfrozer.penguinsmagic.databinding.FragmentSubscriptionsBinding

/** 订阅了哪些拦截接口，可以启停、编辑、删除。 */
class SubscriptionsFragment : Fragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = SubscriptionAdapter(
        onOpen = { open(it) },
        onToggle = { subscription, enabled ->
            Repositories.subscriptions.update(subscription.id) { it.enabled = enabled }
        },
        onPolicyToggle = { subscription, learn ->
            Repositories.subscriptions.update(subscription.id) { it.learnBlocked = learn }
        },
    )

    private val changes: () -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.subscriptionList.layoutManager = LinearLayoutManager(requireContext())
        binding.subscriptionList.adapter = adapter
        binding.addSubscription.setOnClickListener { open(null) }
    }

    override fun onStart() {
        super.onStart()
        Repositories.subscriptions.onChange(changes)
        render()
    }

    override fun onStop() {
        Repositories.subscriptions.offChange(changes)
        super.onStop()
    }

    override fun onDestroyView() {
        binding.subscriptionList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun open(subscription: Subscription?) {
        val args = Bundle().apply {
            putLong(EXTRA_ID, subscription?.id ?: NEW_SUBSCRIPTION)
        }
        findNavController().navigate(R.id.action_subscriptions_to_edit, args)
    }

    private fun render() {
        val binding = _binding ?: return
        val subscriptions = Repositories.subscriptions.all()
        adapter.submitList(subscriptions)
        binding.emptyState.visibility = if (subscriptions.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_ID = "subscriptionId"
        const val NEW_SUBSCRIPTION = -1L
    }
}
