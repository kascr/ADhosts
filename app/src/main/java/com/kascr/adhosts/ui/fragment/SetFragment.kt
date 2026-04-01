package com.kascr.adhosts.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.kascr.adhosts.R
import com.kascr.adhosts.databinding.FragmentSetBinding
import com.kascr.adhosts.ui.activity.function.Decibel
import com.kascr.adhosts.ui.activity.function.Metal
import com.kascr.adhosts.ui.base.BaseFragment
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 功能工具 Fragment
 * 参照参考项目 tools.tsx 的分组列表式设计重构。
 * 布局中的工具项使用 LinearLayout 替代 Button，通过 setOnClickListener 实现交互。
 */
class SetFragment : BaseFragment<FragmentSetBinding>(R.layout.fragment_set) {

    private val sdcardPath: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    override fun createBinding(view: View): FragmentSetBinding {
        return FragmentSetBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- 传感器工具 ---

        binding.decibelButton.setOnClickListener {
            startActivity(Intent(requireContext(), Decibel::class.java))
        }

        binding.MetalButton.setOnClickListener {
            startActivity(Intent(requireContext(), Metal::class.java))
        }

        // --- 系统清理 ---

        binding.cleanEmpButton.setOnClickListener {
            executeCleanupTask(
                "find \"$sdcardPath\" -type d -empty -delete",
                getString(R.string.cleanup_done)
            )
        }

        binding.cleanFileButton.setOnClickListener {
            executeCleanupTask(
                "find \"$sdcardPath\" -type f -empty -delete",
                getString(R.string.cleanup_done)
            )
        }

        binding.wallpaperButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val targetPath = "$sdcardPath/Download/wallpaper.png"
                val result = Shell.cmd("cp /data/system/users/0/wallpaper \"$targetPath\"").exec()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), getString(R.string.wallpaper_saved), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.exec_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // --- 系统调试 ---

        binding.usbButton.setOnClickListener {
            executeShellCommand(
                listOf(
                    "settings put global development_settings_enabled 1",
                    "settings put global adb_enabled 1"
                ),
                getString(R.string.action_enabled)
            )
        }

        binding.wifiUsbButton.setOnClickListener {
            executeShellCommand(
                listOf(
                    "setprop service.adb.tcp.port 5555",
                    "stop adbd",
                    "start adbd"
                ),
                getString(R.string.wireless_adb_enabled)
            )
        }

        binding.AcButtonon.setOnClickListener {
            executeShellCommand(
                listOf(
                    "dumpsys battery set ac 0",
                    "dumpsys battery set usb 0"
                ),
                getString(R.string.action_enabled)
            )
        }

        binding.AcButtonoff.setOnClickListener {
            executeShellCommand("dumpsys battery reset", getString(R.string.action_disabled))
        }

        // --- 其他 ---

        binding.killmp4.setOnClickListener {
            val externalDir = requireContext().getExternalFilesDir(null)
            if (externalDir == null) {
                Toast.makeText(requireContext(), getString(R.string.exec_failed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val scriptPath = "${externalDir.absolutePath}/Script/mp4.sh"
            if (File(scriptPath).exists()) {
                executeShellCommand(
                    "nohup sh \"$scriptPath\" >/dev/null 2>&1 &",
                    getString(R.string.script_running)
                )
            } else {
                Toast.makeText(requireContext(), getString(R.string.script_not_found), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeCleanupTask(command: String, successMsg: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = Shell.cmd(command).exec()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), successMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.exec_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeShellCommand(command: String, successMsg: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = Shell.cmd(command).exec()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), successMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.exec_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeShellCommand(commands: List<String>, successMsg: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = Shell.cmd(*commands.toTypedArray()).exec()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), successMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.exec_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
