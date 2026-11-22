package me.riverfrozer.penguinsmagic.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.Subscription
import me.riverfrozer.penguinsmagic.databinding.ItemSubscriptionBinding

class SubscriptionAdapter(
    private val onOpen: (Subscription) -> Unit,
    private val onToggle: (Subscription, Boolean) -> Unit,
    private val onPolicyToggle: (Subscription, Boolean) -> Unit,
) : ListAdapter<Subscription, SubscriptionAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val subscription = getItem(position)
        val context = holder.binding.root.context
        val status = StatusText.status(context, subscription, System.currentTimeMillis())

        holder.binding.apply {
            subscriptionName.text = subscription.name
            subscriptionUrl.text = subscription.baseUrl
            subscriptionStatus.text = status.text
            subscriptionStatus.setTextColor(StatusText.colorOf(context, status.colorAttr))
            subscriptionMeta.text = status.meta
            subscriptionEnabled.setOnCheckedChangeListener(null)
            subscriptionEnabled.isChecked = subscription.enabled
            subscriptionEnabled.setOnCheckedChangeListener { _, checked -> onToggle(subscription, checked) }
            subscriptionPolicy.setOnCheckedChangeListener(null)
            subscriptionPolicy.isChecked = subscription.learnBlocked
            subscriptionPolicy.setOnCheckedChangeListener { _, checked -> onPolicyToggle(subscription, checked) }
            root.setOnClickListener { onOpen(subscription) }
            root.contentDescription = context.getString(R.string.subscription_edit)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Subscription>() {
            override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription) =
                oldItem == newItem
        }
    }
}
