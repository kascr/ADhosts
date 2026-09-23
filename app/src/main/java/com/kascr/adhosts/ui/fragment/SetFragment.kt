package com.kascr.adhosts.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kascr.adhosts.R
import com.kascr.adhosts.databinding.FragmentSetBinding
import com.kascr.adhosts.ui.activity.function.Decibel
import com.kascr.adhosts.ui.base.BaseFragment
import com.kascr.adhosts.utils.GlassDialog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SetFragment : BaseFragment<FragmentSetBinding>(R.layout.fragment_set) {

    private val sdcardPath by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    override fun createBinding(view: View): FragmentSetBinding {
        return FragmentSetBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initToolActions()
        initCleanupActions()
        initDebugActions()
        initMiscActions()
    }

    private fun initToolActions() {
        binding.decibelButton.setOnClickListener {
            startActivity(Intent(requireContext(), Decibel::class.java))
        }
    }

    private fun initCleanupActions() {
        val downloadPath = "$sdcardPath/Download"
        binding.cleanEmpButton.setOnClickListener {
            confirmAndExecute(
                title = getString(R.string.tool_clean_folder),
                message = getString(R.string.tool_clean_folder_desc),
                commands = listOf(
                    "if [ -d \"$downloadPath\" ]; then " +
                        "find \"$downloadPath\" -mindepth 1 -type d -empty -delete; fi"
                ),
                successMessage = getString(R.string.cleanup_done)
            )
        }
        binding.cleanFileButton.setOnClickListener {
            confirmAndExecute(
                title = getString(R.string.tool_clean_file),
                message = getString(R.string.tool_clean_file_desc),
                commands = listOf(
                    "if [ -d \"$downloadPath\" ]; then " +
                        "find \"$downloadPath\" -type f -empty ! -path '*/.*' -delete; fi"
                ),
                successMessage = getString(R.string.cleanup_done)
            )
        }
        binding.wallpaperButton.setOnClickListener {
            val targetPath = "$sdcardPath/Download/wallpaper.png"
            confirmAndExecute(
                title = getString(R.string.tool_extract_wallpaper),
                message = getString(R.string.tool_extract_wallpaper_desc),
                commands = listOf(buildWallpaperExtractionCommand(targetPath)),
                successMessage = getString(R.string.wallpaper_saved),
                failureMessage = getString(R.string.exec_failed),
                longToast = true
            )
        }
    }

    private fun initDebugActions() {
        binding.usbButton.setOnClickListener {
            confirmAndExecute(
                title = getString(R.string.tool_usb_debug),
                message = getString(R.string.tool_usb_debug_desc),
                commands = listOf(
                    "settings put global development_settings_enabled 1",
                    "settings put global adb_enabled 1"
                ),
                successMessage = getString(R.string.action_enabled)
            )
        }
        binding.wifiUsbButton.setOnClickListener {
            showWirelessAdbDialog()
        }
        binding.disableChargeButton.setOnClickListener {
            confirmAndExecute(
                title = getString(R.string.tool_disable_charge),
                message = getString(R.string.tool_disable_charge_desc),
                commands = listOf(
                    "dumpsys battery set ac 0",
                    "dumpsys battery set usb 0"
                ),
                successMessage = getString(R.string.action_disabled)
            )
        }
        binding.enableChargeButton.setOnClickListener {
            confirmAndExecute(
                title = getString(R.string.tool_enable_charge),
                message = getString(R.string.tool_enable_charge_desc),
                commands = listOf("dumpsys battery reset"),
                successMessage = getString(R.string.action_enabled)
            )
        }
    }

    private fun initMiscActions() {
        binding.killMp4Button.setOnClickListener {
            val scriptPath = try {
                extractAssetIfChanged(MP4_SCRIPT_ASSET)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.script_not_found), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            confirmAndExecute(
                title = getString(R.string.tool_kill_player),
                message = getString(R.string.tool_kill_player_desc),
                commands = listOf("sh \"${scriptPath.absolutePath}\""),
                successMessage = getString(R.string.script_running)
            )
        }
    }

    private fun showWirelessAdbDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val enabled = Shell.cmd("[ \"$(getprop service.adb.tcp.port)\" = 5555 ]").exec().isSuccess
            withContext(Dispatchers.Main) {
                if (bindingOrNull == null) return@withContext
                val commands = if (enabled) {
                    listOf(
                        "setprop service.adb.tcp.port -1",
                        "stop adbd",
                        "start adbd"
                    )
                } else {
                    listOf(
                        "setprop service.adb.tcp.port 5555",
                        "stop adbd",
                        "start adbd"
                    )
                }
                confirmAndExecute(
                    title = getString(R.string.tool_wireless_debug),
                    message = getString(
                        if (enabled) R.string.tool_wireless_debug_disable_desc
                        else R.string.tool_wireless_debug_desc
                    ),
                    commands = commands,
                    successMessage = getString(
                        if (enabled) R.string.wireless_adb_disabled
                        else R.string.wireless_adb_enabled
                    )
                )
            }
        }
    }

    private fun buildWallpaperExtractionCommand(targetPath: String): String {
        val quotedTarget = shellQuote(targetPath)
        val quotedTargetDirectory = shellQuote(File(targetPath).parent.orEmpty())
        return """
            current_user="${'$'}(am get-current-user 2>/dev/null)"
            case "${'$'}current_user" in ''|*[!0-9]*) current_user=0 ;; esac
            mkdir -p $quotedTargetDirectory || exit 1
            for source in \
                "/data/system/users/${'$'}current_user/wallpaper_orig" \
                "/data/system/users/${'$'}current_user/wallpaper" \
                "/data/system/users/0/wallpaper_orig" \
                "/data/system/users/0/wallpaper"; do
                [ -s "${'$'}source" ] || continue
                cp "${'$'}source" $quotedTarget && chmod 644 $quotedTarget
                exit ${'$'}?
            done
            exit 1
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun extractAssetIfChanged(assetPath: String): File {
        val assetBytes = requireContext().assets.open(assetPath).use { it.readBytes() }
        val destination = File(requireContext().filesDir, assetPath)
        if (destination.isFile && destination.length() == assetBytes.size.toLong()) {
            val unchanged = destination.inputStream().use { it.readBytes().contentEquals(assetBytes) }
            if (unchanged) return destination
        }

        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("${destination.name}.", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(assetBytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            return destination
        } finally {
            temporary.takeIf(File::exists)?.delete()
        }
    }

    private fun confirmAndExecute(
        title: String,
        message: String,
        commands: List<String>,
        successMessage: String,
        failureMessage: String = getString(R.string.exec_failed),
        longToast: Boolean = false
    ) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                executeShellCommands(commands, successMessage, failureMessage, longToast)
            }
            .create()
        GlassDialog.show(dialog)
    }

    private fun executeShellCommands(
        commands: List<String>,
        successMessage: String,
        failureMessage: String = getString(R.string.exec_failed),
        longToast: Boolean = false
    ) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = Shell.cmd(*commands.toTypedArray()).exec()
            withContext(Dispatchers.Main) {
                val context = context ?: return@withContext
                val message = if (result.isSuccess) successMessage else failureMessage
                val duration = if (longToast) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(context, message, duration).show()
            }
        }
    }

    companion object {
        private const val MP4_SCRIPT_ASSET = "Script/mp4.sh"
    }
}
