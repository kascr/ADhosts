package com.kascr.adhosts.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.lang.reflect.Type
import java.net.URL

/**
 * 订阅数据模型
 * @param url 订阅源的远程地址
 */
data class Subscription(val url: String)

/**
 * Hosts 订阅管理器 (单例对象)
 * 负责管理用户添加的 Hosts 订阅源，包括保存、读取、下载及合并逻辑。
 */
object HostsSubscriptionManager {

    private const val FILE_NAME = "hosts_subscriptions.json" // 存储订阅列表的 JSON 文件名
    private const val MERGED_HOSTS_NAME = "ADhosts"          // 合并后的 hosts 文件名

    /**
     * 从本地私有目录获取订阅列表
     * @param context 上下文
     * @return 订阅对象列表
     */
    fun getSubscriptions(context: Context): List<Subscription> {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) {
            try {
                FileReader(file).use { reader ->
                    val type: Type = object : TypeToken<List<Subscription>>() {}.type
                    return Gson().fromJson(reader, type) ?: emptyList()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /**
     * 将订阅列表保存到本地 JSON 文件
     * @param context 上下文
     * @param subscriptions 要保存的订阅列表
     */
    fun saveSubscriptions(context: Context, subscriptions: List<Subscription>) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            FileWriter(file).use { writer ->
                Gson().toJson(subscriptions, writer)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 添加新的订阅源
     * @param context 上下文
     * @param url 订阅源地址
     */
    fun addSubscription(context: Context, url: String) {
        val subscriptions = getSubscriptions(context).toMutableList()
        // 避免重复添加相同的 URL
        if (subscriptions.none { it.url == url }) {
            subscriptions.add(Subscription(url = url))
            saveSubscriptions(context, subscriptions)
        }
    }

    /**
     * 移除指定的订阅源
     * @param context 上下文
     * @param url 要移除的订阅源地址
     */
    fun removeSubscription(context: Context, url: String) {
        val subscriptions = getSubscriptions(context).toMutableList()
        subscriptions.removeAll { it.url == url }
        saveSubscriptions(context, subscriptions)
    }

    /**
     * 下载并合并所有订阅源的内容
     * 注意：此方法涉及网络请求，必须在 IO 线程中调用
     * @param context 上下文
     */
    @Throws(Exception::class)
    fun downloadAndMergeSubscriptions(context: Context) {
        val subscriptions = getSubscriptions(context)
        val urlList = subscriptions.map { it.url }
        
        if (urlList.isEmpty()) return

        val mergedContent = StringBuilder()
        // 添加文件头信息
        mergedContent.append("# Merged ADhosts - ${System.currentTimeMillis()}\n")
        mergedContent.append("127.0.0.1 localhost\n::1 localhost\n\n")

        // 遍历下载每个订阅源
        for (urlString in urlList) {
            try {
                val content = URL(urlString).readText()
                mergedContent.append("# Source: $urlString\n")
                mergedContent.append(content)
                mergedContent.append("\n\n")
            } catch (e: Exception) {
                // 如果单个下载失败，记录错误并继续下一个，保证整体流程不中断
                mergedContent.append("# Failed to download: $urlString\n\n")
            }
        }

        // 将合并后的内容保存到应用私有目录，准备通过 Root 权限 dd 到系统
        val targetFile = File(context.filesDir, MERGED_HOSTS_NAME)
        targetFile.writeText(mergedContent.toString())
    }
}
