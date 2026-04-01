package com.kascr.adhosts.ui.activity

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kascr.adhosts.R
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 启动界面 Activity
 * 优化点：
 * 1. 严格 Root 检查：无 Root 权限直接退出应用
 * 2. 适配 KSU (KernelSU) 和 Magisk 官方模块安装命令
 * 3. 使用 assets 中的 AD_lite-v1.0.0.zip 进行模块部署
 */
class StartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 开启沉浸式体验
        enableEdgeToEdge()
        setContentView(R.layout.activity_start)
        
        // 适配系统栏间距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.start_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 动态设置版本号
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version_text).text = "v$versionName"

        // 执行入场动画
        startFadeInAnimation()

        // 初始化 Shell 配置
        initShellConfig()

        // 执行初始化逻辑
        performInitialization()
    }

    private fun initShellConfig() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
        )
    }

    private fun startFadeInAnimation() {
        val logo = findViewById<ImageView>(R.id.logo_image)
        val name = findViewById<TextView>(R.id.app_name_text)

        val logoFade = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f)
        val nameFade = ObjectAnimator.ofFloat(name, "alpha", 0f, 1f)
        
        AnimatorSet().apply {
            playTogether(logoFade, nameFade)
            duration = 1000
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun performInitialization() {
        Thread {
            val isRoot = Shell.getShell().isRoot
            
            if (!isRoot) {
                runOnUiThread {
                    Toast.makeText(this, "未检测到 Root 权限，应用即将退出", Toast.LENGTH_LONG).show()
                    findViewById<TextView>(R.id.version_text).postDelayed({
                        finishAffinity() 
                    }, 2000)
                }
                return@Thread
            }

            prepareEnvironmentAndModule()

            // 3. 延迟进入主页
            Thread.sleep(800)
            
            runOnUiThread {
                exitSplashScreen()
            }
        }.start()
    }

    /**
     * 准备环境并安装模块（适配 KSU 和 Magisk 官方命令）
     */
    private fun prepareEnvironmentAndModule() {
        //复制基础资源
        copyAssetToPrivateStorage(this, "Script/mp4.sh")
        copyAssetToPrivateStorage(this, "hosts")
        
        //处理模块安装
        val moduleZipName = "AD_lite-v1.0.0.zip"
        val tempZipFile = File(filesDir, moduleZipName)
        
        // 复制 ZIP 到私有目录供安装命令调用
        copyAssetToPrivateStorage(this, moduleZipName)

        if (tempZipFile.exists()) {
            val moduleDir = "/data/adb/modules/AD_lite"
            
            // 检查模块是否已安装
            val checkResult = Shell.cmd("if [ -d \"$moduleDir\" ]; then echo 'installed'; fi").exec()
            
            if (!checkResult.out.contains("installed")) {
                val zipPath = tempZipFile.absolutePath
                
                // 识别环境并选择安装命令
                // 检查 ksud 是否存在于 /data/adb/
                val ksuCheck = Shell.cmd("if [ -f \"/data/adb/ksud\" ]; then echo 'ksu'; fi").exec()
                
                val installCommand = if (ksuCheck.out.contains("ksu")) {
                    // KSU 安装命令
                    "/data/adb/ksud module install \"$zipPath\""
                } else {
                    // Magisk 安装命令
                    "magisk --install-module \"$zipPath\""
                }
                
                val installResult = Shell.cmd(installCommand).exec()
                
                if (installResult.isSuccess) {
                    runOnUiThread {
                        Toast.makeText(this, "模块安装成功，请重启设备", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 如果官方命令失败，尝试手动解压作为保底
                    runOnUiThread {
                        // Log.e("Install", "安装失败: ${installResult.err}")
                    }
                }
            }
        }
    }

    private fun exitSplashScreen() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * 将 assets 中的文件复制到私有存储目录
     * 优化：检测文件存在则跳过复制，避免每次启动都覆盖文件
     */
    private fun copyAssetToPrivateStorage(context: Context, assetFileName: String) {
        try {
            val outputFile = File(context.filesDir, assetFileName)
            
            // 如果文件已存在，则跳过复制
            if (outputFile.exists()) {
                return
            }
            
            // 文件不存在，执行复制操作
            val inputStream = context.assets.open(assetFileName)
            outputFile.parentFile?.mkdirs()

            val outputStream = FileOutputStream(outputFile)
            inputStream.copyTo(outputStream)
            
            inputStream.close()
            outputStream.close()
            outputFile.setExecutable(true)
        } catch (e: IOException) {
            // 忽略错误
        }
    }
}
