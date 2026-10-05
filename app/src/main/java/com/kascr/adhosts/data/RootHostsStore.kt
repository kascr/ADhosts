package com.kascr.adhosts.data

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException

/** Root writes keep module files and the running Hosts generation recoverable. */
internal object RootHostsStore {
    const val systemHostsPath = "/data/adb/modules/AD_lite/system/etc/hosts"
    const val metaModuleHostsPath = "/data/adb/metamodule/mnt/AD_lite/system/etc/hosts"
    const val runtimeHostsPath = "/system/etc/hosts"
    const val moduleDirPath = "/data/adb/modules/AD_lite"
    const val moduleDisablePath = "$moduleDirPath/disable"
    const val moduleRemovePath = "$moduleDirPath/remove"

    fun write(sourcePath: String, secondarySource: String = sourcePath,
              runtimeSource: String = systemHostsPath, forceRuntime: Boolean = false): Boolean {
        val manager = RootEnvironment.detect()
        if (sourcePath.isNotEmpty() && manager == null) return false
        val needsSystemMount = sourcePath.isNotEmpty() && !forceRuntime &&
            File(sourcePath).useLines { lines -> lines.any { it.startsWith("# Merged ADhosts - ") } }
        if (needsSystemMount && manager?.requiresMetaModule == true && !RootEnvironment.hasReadyMetaModule()) {
            return false
        }
        if (needsSystemMount && Shell.cmd(
                "[ -f ${RootEnvironment.quote(moduleDisablePath)} ] || " +
                    "[ -f ${RootEnvironment.quote(moduleRemovePath)} ] || " +
                    "[ -f ${RootEnvironment.quote("$moduleDirPath/skip_mount")} ]"
            ).exec().isSuccess) return false
        val replaceSecondary = Shell.cmd("[ -f ${RootEnvironment.quote(metaModuleHostsPath)} ]").exec().isSuccess
        if (sourcePath.isNotEmpty()) HostsOperationJournal.beforeRootWrite()
        // The journal survives a killed shell or app process and is replayed before the next write.
        val script = """
            source=${RootEnvironment.quote(sourcePath)}
            secondary_source=${RootEnvironment.quote(secondarySource)}
            runtime_source=${RootEnvironment.quote(runtimeSource)}
            mount_provider=${RootEnvironment.quote(manager?.kind?.name.orEmpty())}
            primary=${RootEnvironment.quote(systemHostsPath)}
            secondary=${RootEnvironment.quote(metaModuleHostsPath)}
            runtime=${RootEnvironment.quote(runtimeHostsPath)}
            # Magisk identifies module bind mounts by their root beneath /adb/modules.
            live=${RootEnvironment.quote("$moduleDirPath/.adhosts-live-hosts")}
            runtime_backing=
            for backing in "${'$'}primary" "${'$'}secondary" "${'$'}live" /data/adb/.adhosts-live-hosts; do
                if [ "${'$'}backing" -ef "${'$'}runtime" ]; then
                    runtime_backing=${'$'}backing
                    break
                fi
            done
            # Older builds renamed the live bind source. Linux rejects another bind
            # over that deleted dentry (ENOENT), but the mounted inode is still writable.
            if [ -z "${'$'}runtime_backing" ]; then
                runtime_root=${'$'}(awk -v target="${'$'}runtime" '${'$'}5 == target { root=${'$'}4 } END { print root }' /proc/self/mountinfo)
                case "${'$'}runtime_root" in
                    /adb/modules/AD_lite/.adhosts-live-hosts|/adb/modules/AD_lite/.adhosts-live-hosts//*|\
                    /data/adb/modules/AD_lite/.adhosts-live-hosts|/data/adb/modules/AD_lite/.adhosts-live-hosts//*|\
                    /adb/.adhosts-live-hosts|/adb/.adhosts-live-hosts//*|\
                    /data/adb/.adhosts-live-hosts|/data/adb/.adhosts-live-hosts//*)
                        runtime_backing=${'$'}runtime ;;
                esac
            fi
            replace_secondary=${if (replaceSecondary) 1 else 0}
            journal=/data/adb/.adhosts-hosts-journal
            preparing=${'$'}journal.new
            completed=${'$'}journal.completed
            primary_stage=
            secondary_stage=
            runtime_stage=
            phase=initialization
            update_runtime=${if (forceRuntime) 1 else 0}
            if [ -f "${'$'}runtime" ] && {
                { [ -d ${RootEnvironment.quote(moduleDirPath)} ] &&
                  [ ! -f ${RootEnvironment.quote(moduleDisablePath)} ] &&
                  [ ! -f ${RootEnvironment.quote(moduleRemovePath)} ]; } ||
                grep -q '^# Merged ADhosts - ' "${'$'}runtime";
            }; then
                update_runtime=1
            fi
            refresh_runtime() {
                [ "${'$'}update_runtime" -eq 1 ] || return 0
                cmp -s "${'$'}1" "${'$'}runtime" && return 0
                if [ -n "${'$'}runtime_backing" ]; then
                    # Preserve the mounted inode on every provider. Renaming it both
                    # leaves the old rules visible and prevents the next bind mount.
                    phase=update_runtime_backing
                    if [ ! "${'$'}1" -ef "${'$'}runtime_backing" ]; then
                        cat "${'$'}1" > "${'$'}runtime_backing" || return 1
                    fi
                    cmp -s "${'$'}1" "${'$'}runtime"
                    return ${'$'}?
                fi
                # Magisk must update its existing bind instead of stacking another one.
                [ "${'$'}mount_provider" != MAGISK ] || return 1
                phase=prepare_runtime
                runtime_stage=${'$'}(mktemp "${'$'}live.new.XXXXXX") || return 1
                if ! { cp "${'$'}1" "${'$'}runtime_stage" &&
                       chown 0:0 "${'$'}runtime_stage" &&
                       chmod 644 "${'$'}runtime_stage" &&
                       chcon u:object_r:system_file:s0 "${'$'}runtime_stage" &&
                       mv -f "${'$'}runtime_stage" "${'$'}live"; }; then
                    rm -f "${'$'}runtime_stage"
                    runtime_stage=
                    return 1
                fi
                runtime_stage=
                cmp -s "${'$'}live" "${'$'}runtime" && return 0
                # Renaming a module file leaves OverlayFS/bind mounts on the old inode.
                # Replace the Hosts mount in init's namespace, without remounting /system.
                # Keep the old mount available if the new bind fails.
                if [ "${'$'}mount_provider" = KERNEL_SU ]; then
                    phase=mount_runtime_ksu
                    [ -x /data/adb/ksu/bin/busybox ] || return 1
                    /data/adb/ksu/bin/busybox mount -o bind,dev=KSU "${'$'}live" "${'$'}runtime" || return 1
                else
                    phase=mount_runtime
                    mount -o bind "${'$'}live" "${'$'}runtime" || return 1
                fi
                phase=verify_runtime
                cmp -s "${'$'}live" "${'$'}runtime"
            }
            finish_journal() {
                # Remove recovery markers only after the directory has its inactive name.
                rm -rf "${'$'}completed" || return 1
                [ ! -e "${'$'}completed" ] || return 1
                mv "${'$'}journal" "${'$'}completed" || return 1
                sync
                rm -rf "${'$'}completed" || true
            }
            recover() {
                # Partial cleanup of an already retired journal cannot block Root recovery.
                rm -rf "${'$'}completed" || true
                [ -d "${'$'}journal" ] || return 0
                if [ "${'$'}(cat "${'$'}journal/committed" 2>/dev/null)" = 1 ]; then
                    refresh_runtime "${'$'}primary" || return 1
                    sync
                    finish_journal
                    return ${'$'}?
                fi
                [ -f "${'$'}journal/ready" ] && [ -f "${'$'}journal/primary" ] || return 1
                if [ "${'$'}runtime_backing" = "${'$'}primary" ]; then
                    cat "${'$'}journal/primary" > "${'$'}primary" || return 1
                else
                    recovery=${'$'}(mktemp "${'$'}primary.recover.XXXXXX") || return 1
                    cp -p "${'$'}journal/primary" "${'$'}recovery" &&
                        mv -f "${'$'}recovery" "${'$'}primary" || return 1
                fi
                if [ -f "${'$'}journal/secondary" ]; then
                    if [ "${'$'}runtime_backing" = "${'$'}secondary" ]; then
                        cat "${'$'}journal/secondary" > "${'$'}secondary" || return 1
                    else
                        recovery=${'$'}(mktemp "${'$'}secondary.recover.XXXXXX") || return 1
                        cp -p "${'$'}journal/secondary" "${'$'}recovery" &&
                            mv -f "${'$'}recovery" "${'$'}secondary" || return 1
                    fi
                fi
                if [ -f "${'$'}journal/runtime" ]; then
                    refresh_runtime "${'$'}journal/runtime" || return 1
                else
                    # Journals written by older versions contain only the module files.
                    refresh_runtime "${'$'}primary" || return 1
                fi
                sync
                finish_journal
            }
            finish() {
                result=${'$'}?
                trap - EXIT HUP INT TERM
                if [ "${'$'}result" -ne 0 ]; then
                    printf 'ADhosts Root write failed: phase=%s status=%s\n' "${'$'}phase" "${'$'}result" >&2
                fi
                if [ "${'$'}result" -ne 0 ] && [ -d "${'$'}journal" ]; then
                    recover || result=1
                fi
                [ -z "${'$'}primary_stage" ] || rm -f "${'$'}primary_stage"
                [ -z "${'$'}secondary_stage" ] || rm -f "${'$'}secondary_stage"
                [ -z "${'$'}runtime_stage" ] || rm -f "${'$'}runtime_stage"
                exit "${'$'}result"
            }
            recover || exit 1
            [ -n "${'$'}source" ] || exit 0
            trap finish EXIT
            trap 'exit 1' HUP INT TERM
            phase=prepare_primary
            [ -f "${'$'}source" ] && [ -f "${'$'}primary" ] || exit 1
            primary_stage=${'$'}(mktemp "${'$'}primary.new.XXXXXX") || exit 1
            cp "${'$'}source" "${'$'}primary_stage" && chmod 644 "${'$'}primary_stage" || exit 1
            if [ "${'$'}replace_secondary" -eq 1 ]; then
                phase=prepare_secondary
                [ -f "${'$'}secondary" ] || exit 1
                secondary_stage=${'$'}(mktemp "${'$'}secondary.new.XXXXXX") || exit 1
                cp "${'$'}secondary_source" "${'$'}secondary_stage" && chmod 644 "${'$'}secondary_stage" || exit 1
            fi
            phase=backup_hosts
            rm -rf "${'$'}preparing" || exit 1
            mkdir "${'$'}preparing" || exit 1
            cp -p "${'$'}primary" "${'$'}preparing/primary" || exit 1
            if [ "${'$'}replace_secondary" -eq 1 ]; then
                cp -p "${'$'}secondary" "${'$'}preparing/secondary" || exit 1
            fi
            if [ "${'$'}update_runtime" -eq 1 ]; then
                cp -p "${'$'}runtime" "${'$'}preparing/runtime" || exit 1
            fi
            printf '1' > "${'$'}preparing/ready" || exit 1
            sync
            mv "${'$'}preparing" "${'$'}journal" || exit 1
            sync
            phase=replace_primary
            if [ "${'$'}runtime_backing" = "${'$'}primary" ]; then
                cat "${'$'}primary_stage" > "${'$'}primary" || exit 1
            else
                mv -f "${'$'}primary_stage" "${'$'}primary" || exit 1
                primary_stage=
            fi
            if [ "${'$'}replace_secondary" -eq 1 ]; then
                phase=replace_secondary
                if [ "${'$'}runtime_backing" = "${'$'}secondary" ]; then
                    cat "${'$'}secondary_stage" > "${'$'}secondary" || exit 1
                else
                    mv -f "${'$'}secondary_stage" "${'$'}secondary" || exit 1
                    secondary_stage=
                fi
            fi
            phase=verify_primary
            cmp -s "${'$'}source" "${'$'}primary" || exit 1
            if [ "${'$'}replace_secondary" -eq 1 ]; then
                phase=verify_secondary
                cmp -s "${'$'}secondary_source" "${'$'}secondary" || exit 1
            fi
            refresh_runtime "${'$'}runtime_source" || exit 1
            phase=commit_hosts
            sync
            printf '1' > "${'$'}journal/committed" || exit 1
            sync
            finish_journal || exit 1
        """.trimIndent()
        val result = RootEnvironment.inMountNamespace(script)
        if (!result.isSuccess) {
            Log.w("HostsFragment", "Root Hosts commit failed (${result.code}): " +
                (result.out + result.err).takeLast(6).joinToString("; "))
        }
        return result.isSuccess
    }

    private val paths = listOf(systemHostsPath, metaModuleHostsPath, runtimeHostsPath)

    fun snapshot(directory: File) {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create Root snapshot")
        val commands = paths.mapIndexed { index, path ->
            val target = File(directory, index.toString()).apply { createNewFile() }
            "if [ -f ${RootEnvironment.quote(path)} ]; then " +
                "cp ${RootEnvironment.quote(path)} ${RootEnvironment.quote(target.path)} && " +
                "chmod 600 ${RootEnvironment.quote(target.path)} && " +
                "chown ${android.os.Process.myUid()}:${android.os.Process.myUid()} ${RootEnvironment.quote(target.path)} || exit 1; " +
                "else rm -f ${RootEnvironment.quote(target.path)}; fi"
        }
        if (!RootEnvironment.inMountNamespace(commands.joinToString("\n") + "\nsync").isSuccess) {
            throw IOException("Cannot preserve Root Hosts")
        }
    }

    fun restore(directory: File) {
        val primary = File(directory, "0")
        if (!primary.isFile) return
        val secondary = File(directory, "1").takeIf(File::isFile) ?: primary
        val runtime = File(directory, "2").takeIf(File::isFile) ?: primary
        if (!write(primary.path, secondary.path, runtime.path, forceRuntime = true)) {
            throw IOException("Cannot restore Root Hosts")
        }
    }

    fun matchesRuntime(source: File): Boolean = source.isFile && Shell.cmd(
        "cmp -s ${RootEnvironment.quote(source.path)} ${RootEnvironment.quote(runtimeHostsPath)}"
    ).exec().isSuccess

    fun matchesRuntimePath(path: String): Boolean = Shell.cmd(
        "cmp -s ${RootEnvironment.quote(path)} ${RootEnvironment.quote(runtimeHostsPath)}"
    ).exec().isSuccess

    /** Null distinguishes an unreadable Root namespace from a readable Hosts file with no match. */
    fun runtimeAddresses(hostname: String): List<String>? {
        val domain = HostsRuleValidator.normalizeHostname(hostname.trim()) ?: return emptyList()
        val result = try {
            RootEnvironment.inMountNamespace(runtimeAddressesCommand(domain))
        } catch (_: Exception) {
            return null
        }
        if (!result.isSuccess) return null
        return parseRuntimeAddresses(domain, result.out.asSequence())
    }

    internal fun runtimeAddressesCommand(normalizedHostname: String): String {
        require(HostsRuleValidator.normalizeHostname(normalizedHostname) == normalizedHostname)
        val program = """
            {
                sub(/#.*/, "")
                for (i = 2; i <= NF; i++) {
                    name = tolower(${ '$' }i)
                    sub(/\.+${ '$' }/, "", name)
                    # IDN candidates need Java's IDN normalization after the ASCII prefilter.
                    if (name == host || name ~ /[^a-z0-9.-]/) {
                        print
                        break
                    }
                }
            }
        """.trimIndent()
        return "awk -v host=${RootEnvironment.quote(normalizedHostname)} " +
            "${RootEnvironment.quote(program)} ${RootEnvironment.quote(runtimeHostsPath)}"
    }

    internal fun parseRuntimeAddresses(hostname: String, lines: Sequence<String>): List<String> {
        val domain = HostsRuleValidator.normalizeHostname(hostname.trim()) ?: return emptyList()
        val addresses = linkedSetOf<String>()
        lines.forEach { line ->
            val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (fields.size >= 2 && HostsRuleValidator.isNumericIpAddress(fields.first()) &&
                fields.drop(1).any { HostsRuleValidator.normalizeHostname(it) == domain }) {
                addresses += fields.first()
            }
        }
        return addresses.toList()
    }

    fun runtimeContains(hostname: String, address: String): Boolean = Shell.cmd(
        "awk -v host=${RootEnvironment.quote(hostname)} -v ip=${RootEnvironment.quote(address)} " +
            "'${'$'}1 == ip { for (i = 2; i <= NF; i++) { if (substr(${ '$' }i, 1, 1) == \"#\") break; " +
            "if (tolower(${ '$' }i) == host || tolower(${ '$' }i) == host \".\") found=1 } } END { exit !found }' " +
            RootEnvironment.quote(runtimeHostsPath)
    ).exec().isSuccess


}
