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
    fun downloadAndMergeSubscriptions(context: Context): Int {
        val subscriptions = getSubscriptions(context)
        val urlList = subscriptions.map { it.url }

        if (urlList.isEmpty()) return 0

        // 使用 LinkedHashSet 对规则行去重，保留插入顺序
        val seenRules = LinkedHashSet<String>()
        // 固定基础条目，不计入广告规则数
        seenRules.add("127.0.0.1 localhost")
        seenRules.add("::1 localhost")

        val headerLines = mutableListOf(
            "# Merged ADhosts - ${System.currentTimeMillis()}",
            "127.0.0.1 localhost",
            "::1 localhost",
            ""
        )
        val ruleLines = mutableListOf<String>()

        // 遍历下载每个订阅源
        for (urlString in urlList) {
            try {
                val content = URL(urlString).readText()
                ruleLines.add("# Source: $urlString")
                for (line in content.lines()) {
                    val trimmed = line.trim()
                    // 注释行和空行直接保留，不参与去重
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        ruleLines.add(trimmed)
                    } else {
                        // 规则行去重：相同规则只保留第一次出现
                        if (seenRules.add(trimmed)) {
                            ruleLines.add(trimmed)
                        }
                    }
                }
                ruleLines.add("")
            } catch (e: Exception) {
                ruleLines.add("# Failed to download: $urlString")
                ruleLines.add("")
            }
        }

        val allLines = headerLines + ruleLines
        val targetFile = File(context.filesDir, MERGED_HOSTS_NAME)
        targetFile.writeText(allLines.joinToString("\n"))

        // 返回有效拦截规则数（以 0.0.0.0 或 127.0.0.1 开头，排除 localhost 基础条目）
        return ruleLines.count { line ->
            (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) &&
                !line.contains("localhost")
        }
    }
}
