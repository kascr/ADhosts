package com.kascr.adhosts.data

import com.topjohnwu.superuser.Shell

/** Detects the provider of su, rather than preferring binaries left by another installation. */
internal object RootEnvironment {
    enum class Kind(val displayName: String) { MAGISK("Magisk"), KERNEL_SU("KernelSU"), APATCH("APatch") }
    data class Manager(val kind: Kind, val executable: String, val requiresMetaModule: Boolean)

    fun detect(): Manager? {
        val output = Shell.cmd("su -v 2>/dev/null; cat /proc/self/attr/current 2>/dev/null").exec().out.joinToString(" ")
        val kind = when {
            output.contains("APatch", true) -> Kind.APATCH
            output.contains("KernelSU", true) || output.contains("u:r:ksu:") -> Kind.KERNEL_SU
            output.contains("Magisk", true) -> Kind.MAGISK
            else -> return null
        }
        val candidates = when (kind) {
            Kind.KERNEL_SU -> listOf("/data/adb/ksu/bin/ksud", "/data/adb/ksud", "ksud")
            Kind.APATCH -> listOf("/data/adb/apd", "/data/adb/ap/bin/apd", "apd")
            Kind.MAGISK -> listOf("magisk")
        }
        val executable = candidates.firstNotNullOfOrNull { candidate ->
            Shell.cmd("command -v ${quote(candidate)} 2>/dev/null").exec()
                .takeIf { it.isSuccess }?.out?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
        } ?: return null
        val version = Shell.cmd("${quote(executable)} -V 2>/dev/null").exec().out.joinToString(" ")
        val requiresMeta = when (kind) {
            Kind.KERNEL_SU -> Regex("(?:^|\\s)v?(\\d+)\\.").find(version)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 } == true
            Kind.APATCH -> Regex("\\d+").find(version)?.value?.toIntOrNull()?.let { it >= 11219 } == true
            Kind.MAGISK -> false
        }
        return Manager(kind, executable, requiresMeta)
    }

    fun hasReadyMetaModule(): Boolean {
        val script = """
            for dir in /data/adb/metamodule /data/adb/modules/*; do
                [ -f "${'$'}dir/module.prop" ] || continue
                [ ! -f "${'$'}dir/disable" ] && [ ! -f "${'$'}dir/remove" ] &&
                    [ ! -f "${'$'}dir/update" ] && [ -f "${'$'}dir/metamount.sh" ] || continue
                grep -Eq '^metamodule=(1|true)${'$'}' "${'$'}dir/module.prop" || continue
                id=${'$'}(sed -n 's/^id=//p' "${'$'}dir/module.prop" | head -n 1)
                [ ! -d "/data/adb/modules_update/${'$'}id" ] || continue
                exit 0
            done
            exit 1
        """.trimIndent()
        // Shell.cmd runs in libsu's persistent shell. exit must affect only this child.
        return Shell.cmd("sh -c ${quote(script)}").exec().isSuccess
    }

    fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    fun inMountNamespace(script: String): Shell.Result = Shell.cmd(
        "if command -v nsenter >/dev/null 2>&1; then nsenter -t 1 -m -- sh -c ${quote(script)}; " +
            "else sh -c ${quote(script)}; fi"
    ).to(ArrayList<String>(), ArrayList<String>()).exec()
}
