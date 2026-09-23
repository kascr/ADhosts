package com.kascr.adhosts.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class HostsBackup(
    val subscriptions: List<Subscription>,
    /** Null means a legacy subscription-only backup; preserve existing manual rules. */
    val manualRules: List<ManualHostsRule>?
)

object HostsBackupCodec {

    private const val FORMAT_VERSION = 2
    private val gson = Gson()

    fun encode(
        subscriptions: List<Subscription>,
        manualRules: List<ManualHostsRule>
    ): String {
        val document = JsonObject().apply {
            addProperty("formatVersion", FORMAT_VERSION)
            add("subscriptions", gson.toJsonTree(subscriptions))
            add("manualRules", JsonArray().also { array ->
                manualRules.forEach { array.add(it.asHostsLine()) }
            })
        }
        return gson.toJson(document)
    }

    fun decode(json: String): HostsBackup {
        val root = JsonParser.parseString(json)
        if (root.isJsonArray) {
            return HostsBackup(
                subscriptions = HostsSubscriptionManager.parseSubscriptionsJson(json),
                manualRules = null
            )
        }
        require(root.isJsonObject) { "Invalid backup format" }
        val document = root.asJsonObject
        val version = document.get("formatVersion")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
        require(version == FORMAT_VERSION) { "Unsupported backup version" }

        val subscriptionsJson = document.get("subscriptions")
        require(subscriptionsJson?.isJsonArray == true) { "Missing subscription list" }
        val manualRulesJson = document.get("manualRules")
        require(manualRulesJson?.isJsonArray == true) { "Missing manual rules" }

        val lines = manualRulesJson.asJsonArray.map { entry ->
            require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) {
                "Invalid manual rule entry"
            }
            entry.asString
        }
        val parsed = ManualHostsRuleManager.parseEditorText(lines.joinToString("\n"))
        require(parsed.isValid) {
            "Invalid manual rules at line ${parsed.invalidLines.joinToString(", ")}"
        }
        return HostsBackup(
            subscriptions = HostsSubscriptionManager.parseSubscriptionsJson(
                subscriptionsJson.toString()
            ),
            manualRules = parsed.rules
        )
    }
}
