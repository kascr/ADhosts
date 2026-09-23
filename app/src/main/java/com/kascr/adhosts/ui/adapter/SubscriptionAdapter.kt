package com.kascr.adhosts.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kascr.adhosts.R
import com.kascr.adhosts.data.Subscription
import com.kascr.adhosts.ui.widget.SubscriptionSwipeLayout

class SubscriptionAdapter(
    private val context: Context,
    private var subscriptions: List<Subscription>,
    private val onDeleteClick: (String) -> Unit,
    private val onToggleClick: (String, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<SubscriptionAdapter.SubscriptionViewHolder>() {

    private var activeRow: SubscriptionSwipeLayout? = null

    override fun onViewRecycled(holder: SubscriptionViewHolder) {
        releaseRow(holder)
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: SubscriptionViewHolder) {
        releaseRow(holder)
        super.onViewDetachedFromWindow(holder)
    }

    private fun releaseRow(holder: SubscriptionViewHolder) {
        if (activeRow === holder.swipeLayout) activeRow = null
        holder.swipeLayout.reset()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_subscription, parent, false)
        return SubscriptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubscriptionViewHolder, position: Int) {
        holder.bind(subscriptions[position])
    }

    override fun getItemCount(): Int = subscriptions.size

    fun getSubscription(position: Int): Subscription? = subscriptions.getOrNull(position)

    fun refreshRow(url: String) {
        val index = subscriptions.indexOfFirst { it.url == url }
        if (index >= 0) notifyItemChanged(index)
    }

    fun updateData(newSubscriptions: List<Subscription>) {
        activeRow?.reset()
        activeRow = null
        val diffResult = DiffUtil.calculateDiff(SubscriptionDiffCallback(subscriptions, newSubscriptions))
        subscriptions = newSubscriptions
        diffResult.dispatchUpdatesTo(this)
    }

    private class SubscriptionDiffCallback(
        private val oldItems: List<Subscription>,
        private val newItems: List<Subscription>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size

        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition].url == newItems[newItemPosition].url
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition] == newItems[newItemPosition]
        }
    }

    inner class SubscriptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val swipeLayout = itemView as SubscriptionSwipeLayout
        private val subscriptionNameText: android.widget.TextView = itemView.findViewById(R.id.subscriptionNameText)
        private val subscriptionText: android.widget.TextView = itemView.findViewById(R.id.subscriptionText)
        private val enabledSwitch: MaterialSwitch = itemView.findViewById(R.id.subscriptionEnabledSwitch)
        private val deleteActionText: View = itemView.findViewById(R.id.deleteActionText)
        val contentCard: MaterialCardView = itemView.findViewById(R.id.contentCard)

        fun bind(subscription: Subscription) {
            releaseRow(this)
            swipeLayout.onInteraction = { row ->
                if (activeRow !== row) activeRow?.close()
                activeRow = row
            }
            subscriptionNameText.text = subscription.name
            subscriptionText.text = subscription.url
            subscriptionNameText.alpha = if (subscription.enabled) 1f else 0.58f
            subscriptionText.alpha = if (subscription.enabled) 1f else 0.58f
            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isEnabled = true
            enabledSwitch.isChecked = subscription.enabled
            enabledSwitch.contentDescription =
                "${subscription.name} · ${itemView.context.getString(R.string.subscription_switch)}"
            enabledSwitch.setOnCheckedChangeListener { button, checked ->
                button.isEnabled = false
                onToggleClick(subscription.url, checked)
            }
            contentCard.setCardBackgroundColor(itemView.context.getColor(R.color.subscription_card))
            contentCard.strokeColor = itemView.context.getColor(R.color.subscription_card_stroke)
            deleteActionText.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    swipeLayout.reset()
                    onDeleteClick(subscription.url)
                }
            }
        }

    }
}
