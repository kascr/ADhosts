package com.kascr.adhosts.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.kascr.adhosts.R
import com.kascr.adhosts.data.Subscription

/**
 * 订阅列表适配器 - 支持侧滑删除确认
 */
class SubscriptionAdapter(
    private val context: Context,
    var subscriptions: List<Subscription>,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SubscriptionAdapter.SubscriptionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_subscription, parent, false)
        return SubscriptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubscriptionViewHolder, position: Int) {
        val subscription = subscriptions[position]
        holder.bind(subscription)
    }

    override fun getItemCount(): Int = subscriptions.size

    fun updateData(newSubscriptions: List<Subscription>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = subscriptions.size
            override fun getNewListSize() = newSubscriptions.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                subscriptions[oldPos].url == newSubscriptions[newPos].url
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                subscriptions[oldPos] == newSubscriptions[newPos]
        })
        subscriptions = newSubscriptions
        diffResult.dispatchUpdatesTo(this)
    }

    inner class SubscriptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val subscriptionText: TextView = itemView.findViewById(R.id.subscriptionText)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnDelete)
        val contentCard: View = itemView.findViewById(R.id.contentCard)

        fun bind(subscription: Subscription) {
            subscriptionText.text = subscription.url

            // 点击删除按钮才执行真正的删除
            btnDelete.setOnClickListener {
                onDeleteClick(subscription.url)
            }

            // 重置内容卡片位置（防止复用时位置错乱）
            contentCard.translationX = 0f
        }
    }
}
