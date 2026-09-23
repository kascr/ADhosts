package com.kascr.adhosts.ui.activity

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kascr.adhosts.R
import com.kascr.adhosts.crash.CrashReportStore
import com.kascr.adhosts.crash.ExpectedExitGuard
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.data.RecommendedHosts
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StartActivity : AppCompatActivity() {

    @Volatile
    private var isFinishingFlow = false

    private var initialSetupRequired = false
    private var isBrandCompact = false
    private lateinit var initializationViewModel: InitializationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CrashReportStore.hasPending(this)) {
            startActivity(
                Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_start)

        val startLayout = findViewById<View>(R.id.start_layout)
        val basePaddingLeft = startLayout.paddingLeft
        val basePaddingTop = startLayout.paddingTop
        val basePaddingRight = startLayout.paddingRight
        val basePaddingBottom = startLayout.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(startLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePaddingLeft + systemBars.left,
                basePaddingTop + systemBars.top,
                basePaddingRight + systemBars.right,
                basePaddingBottom + systemBars.bottom
            )
            insets
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_name_label, versionName)

        startFadeInAnimation()
        initShellConfig()
        ExpectedExitGuard.reset()
        initialSetupRequired = isInitialSetupRequired()
        initializationViewModel = ViewModelProvider(this)[InitializationViewModel::class.java]
        initializationViewModel.state.observe(this, ::renderInitializationState)
        if (initializationViewModel.state.value == InitializationState.Idle) {
            if (initialSetupRequired) showWelcomeStep() else performInitialization()
        }
    }

    override fun onDestroy() {
        isFinishingFlow = true
        super.onDestroy()
    }

    private fun initShellConfig() {
        try {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20)
            )
        } catch (_: IllegalStateException) {
            // The main shell may already exist after activity recreation.
        }
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

    private fun showWelcomeStep() {
        showSetupCard(
            title = getString(R.string.setup_welcome_title),
            message = getString(R.string.setup_welcome_message)
        )
        configurePrimaryAction(R.string.setup_start, R.drawable.ic_chevron_right) {
            performInitialization()
        }
        hideSecondaryAction()
    }

    private fun showInitializationProgress() {
        findViewById<LinearLayout>(R.id.recommended_options).visibility = View.GONE
        if (initialSetupRequired) {
            showSetupCard(
                title = getString(R.string.hosts_checking_status),
                message = getString(R.string.hosts_checking_status_hint),
                showProgress = true
            )
            hidePrimaryAction()
            hideSecondaryAction()
        } else {
            hideSetupCard()
            findViewById<CircularProgressIndicator>(R.id.loading_progress).visibility = View.VISIBLE
        }
    }

    private fun showRootUnavailable() {
        showSetupCard(
            title = getString(R.string.root_unavailable_title),
            message = getString(R.string.root_unavailable_message)
        )
        configurePrimaryAction(R.string.root_retry, R.drawable.ic_restart) {
            performInitialization(resetCachedShell = true)
        }
        configureSecondaryAction(R.string.crash_exit, R.drawable.ic_exit, ::exitApplication)
    }

    private fun showInitializationFailure() {
        showSetupCard(
            title = getString(R.string.operation_failed),
            message = getString(R.string.hosts_operation_failed_root_module)
        )
        configurePrimaryAction(R.string.setup_retry, R.drawable.ic_restart) {
            performInitialization(resetCachedShell = true)
        }
        configureSecondaryAction(R.string.crash_exit, R.drawable.ic_exit, ::exitApplication)
    }

    private fun showRecommendedStep(outcome: InitializationOutcome) {
        showSetupCard(
            title = getString(R.string.recommended_sources_title),
            message = getString(R.string.recommended_sources_hint),
            detail = getString(
                R.string.setup_environment_summary,
                outcome.manager?.displayName ?: getString(R.string.setup_root_generic),
                outcome.moduleVersion
            ),
            showRecommendedOptions = true
        )

        val autumnCheckBox = findViewById<MaterialCheckBox>(R.id.autumn_checkbox)
        val githubCheckBox = findViewById<MaterialCheckBox>(R.id.github520_checkbox)
        bindRecommendedOption(autumnCheckBox, RecommendedHosts.AUTUMN_HOSTS_URL)
        bindRecommendedOption(githubCheckBox, RecommendedHosts.GITHUB520_HOSTS_URL)

        configurePrimaryAction(R.string.setup_finish, R.drawable.ic_check_circle) {
            saveInitialSubscriptions(outcome)
        }
        hideSecondaryAction()
    }

    private fun bindRecommendedOption(checkBox: MaterialCheckBox, url: String) {
        val alreadyAdded = HostsSubscriptionManager.hasSubscription(this, url)
        checkBox.isChecked = alreadyAdded
        checkBox.isEnabled = !alreadyAdded
        checkBox.alpha = if (alreadyAdded) RECOMMENDED_ADDED_ALPHA else 1f
    }

    private fun saveInitialSubscriptions(outcome: InitializationOutcome) {
        val addAutumn = findViewById<MaterialCheckBox>(R.id.autumn_checkbox).isChecked
        val addGithub = findViewById<MaterialCheckBox>(R.id.github520_checkbox).isChecked
        val primaryButton = findViewById<MaterialButton>(R.id.setup_primary_button)
        primaryButton.isEnabled = false

        Thread {
            var addedCount = 0
            if (addAutumn && HostsSubscriptionManager.addSubscription(
                    this,
                    getString(R.string.recommended_autumn_name),
                    RecommendedHosts.AUTUMN_HOSTS_URL
                )
            ) {
                addedCount++
            }
            if (addGithub && HostsSubscriptionManager.addSubscription(
                    this,
                    getString(R.string.recommended_github520_name),
                    RecommendedHosts.GITHUB520_HOSTS_URL
                )
            ) {
                addedCount++
            }

            setupPreferences().edit().putBoolean(KEY_INITIAL_SETUP_COMPLETE, true).apply()
            runOnUiThread {
                if (!isFinishingFlow && !isFinishing && !isDestroyed) {
                    showCompletionStep(outcome, addedCount)
                }
            }
        }.start()
    }

    private fun showCompletionStep(outcome: InitializationOutcome, addedCount: Int) {
        showSetupCard(
            title = getString(R.string.setup_complete_title),
            message = getString(
                R.string.setup_complete_summary,
                outcome.manager?.displayName ?: getString(R.string.setup_root_generic),
                outcome.moduleVersion,
                addedCount
            )
        )
        configurePrimaryAction(R.string.setup_enter_app, R.drawable.ic_chevron_right) {
            initialSetupRequired = false
            exitSplashScreen()
        }
        hideSecondaryAction()
    }

    private fun showSetupCard(
        title: String,
        message: String,
        detail: String? = null,
        showRecommendedOptions: Boolean = false,
        showProgress: Boolean = false
    ) {
        setBrandCompact(true)
        findViewById<CircularProgressIndicator>(R.id.loading_progress).visibility = View.GONE
        findViewById<TextView>(R.id.setup_title).text = title
        findViewById<TextView>(R.id.setup_message).text = message
        findViewById<TextView>(R.id.setup_detail).apply {
            text = detail.orEmpty()
            visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        findViewById<LinearLayout>(R.id.recommended_options).visibility =
            if (showRecommendedOptions) View.VISIBLE else View.GONE
        findViewById<LinearProgressIndicator>(R.id.setup_progress).visibility =
            if (showProgress) View.VISIBLE else View.GONE

        val card = findViewById<MaterialCardView>(R.id.setup_card)
        val needsAnimation = card.visibility != View.VISIBLE
        card.visibility = View.VISIBLE
        if (needsAnimation) {
            card.alpha = 0f
            card.translationY = resources.displayMetrics.density * SETUP_CARD_ENTER_OFFSET_DP
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SETUP_CARD_ANIMATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun hideSetupCard() {
        setBrandCompact(false)
        findViewById<MaterialCardView>(R.id.setup_card).visibility = View.GONE
    }

    private fun setBrandCompact(compact: Boolean) {
        if (isBrandCompact == compact) return
        isBrandCompact = compact

        val brandGroup = findViewById<View>(R.id.brand_group)
        val params = brandGroup.layoutParams as ConstraintLayout.LayoutParams
        if (compact) {
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.topMargin = (20 * resources.displayMetrics.density).toInt()
            params.verticalBias = 0f
        } else {
            params.bottomToTop = R.id.version_text
            params.topMargin = 0
            params.verticalBias = 0.42f
        }
        brandGroup.layoutParams = params
    }

    private fun configurePrimaryAction(
        textRes: Int,
        iconRes: Int,
        action: () -> Unit
    ) {
        findViewById<MaterialButton>(R.id.setup_primary_button).apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(textRes)
            setIconResource(iconRes)
            setOnClickListener { action() }
        }
    }

    private fun hidePrimaryAction() {
        findViewById<MaterialButton>(R.id.setup_primary_button).visibility = View.GONE
    }

    private fun configureSecondaryAction(
        textRes: Int,
        iconRes: Int,
        action: () -> Unit
    ) {
        findViewById<MaterialButton>(R.id.setup_secondary_button).apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(textRes)
            setIconResource(iconRes)
            setOnClickListener { action() }
        }
    }

    private fun hideSecondaryAction() {
        findViewById<MaterialButton>(R.id.setup_secondary_button).visibility = View.GONE
    }

    private fun isInitialSetupRequired(): Boolean {
        val preferences = setupPreferences()
        if (preferences.contains(KEY_INITIAL_SETUP_COMPLETE)) {
            return !preferences.getBoolean(KEY_INITIAL_SETUP_COMPLETE, false)
        }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val isExistingInstallation = packageInfo.lastUpdateTime > packageInfo.firstInstallTime
        if (isExistingInstallation) {
            preferences.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETE, true).apply()
        }
        return !isExistingInstallation
    }

    private fun setupPreferences() =
        getSharedPreferences(SETUP_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun exitApplication() {
        if (isFinishingFlow) return
        ExpectedExitGuard.markExpected()
        isFinishingFlow = true
        finishAffinity()
    }

    private fun performInitialization(resetCachedShell: Boolean = false) {
        if (isFinishingFlow) return
        initializationViewModel.start(resetCachedShell) { shouldResetShell ->
            if (shouldResetShell) {
                runCatching { Shell.getCachedShell()?.close() }
            }
            val hasRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
            if (!hasRoot) null else prepareEnvironmentAndModule()
        }
    }

    private fun renderInitializationState(state: InitializationState) {
        if (isFinishingFlow || isFinishing || isDestroyed) return
        when (state) {
            InitializationState.Idle -> Unit
            InitializationState.Running -> showInitializationProgress()
            InitializationState.RootUnavailable -> showRootUnavailable()
            is InitializationState.Failed -> {
                Log.e(TAG, "Root module initialization failed", state.error)
                showInitializationFailure()
            }
            is InitializationState.Complete -> {
                state.outcome.statusMessage?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
                if (initialSetupRequired) showRecommendedStep(state.outcome) else exitSplashScreen()
            }
        }
    }

    private fun prepareEnvironmentAndModule(): InitializationOutcome {
        cleanupLegacyBootstrapFiles()
        val installedVersion = installedModuleVersionCode()
        val installer = detectRootInstaller()
        var installedNow = false

        if (installedVersion < REQUIRED_MODULE_VERSION_CODE) {
            val availableInstaller = installer
                ?: throw IOException("No supported root module installer was available")
            installModule(availableInstaller)
            installedNow = true

            if (installedModuleVersionCode() < REQUIRED_MODULE_VERSION_CODE) {
                throw IOException("The root manager reported success but the module was not deployed")
            }
        }

        if (
            installer?.kind == RootManager.KERNEL_SU &&
            kernelSuRequiresMetaModule(installer.executable) &&
            !hasInstalledMetaModule()
        ) {
            return InitializationOutcome(
                manager = installer.kind,
                moduleVersion = installedModuleVersionCode(),
                statusMessage = R.string.module_metamodule_required
            )
        }

        return InitializationOutcome(
            manager = installer?.kind,
            moduleVersion = installedModuleVersionCode(),
            statusMessage = if (installedNow) R.string.module_installed_msg else null
        )
    }

    private fun installModule(installer: RootInstaller) {
        val tempZipFile = copyAssetToPrivateStorage(this, MODULE_ZIP_NAME)
        try {
            val zipPath = shellQuote(tempZipFile.absolutePath)
            val executable = shellQuote(installer.executable)
            val installCommand = when (installer.kind) {
                RootManager.KERNEL_SU,
                RootManager.APATCH -> "$executable module install $zipPath"
                RootManager.MAGISK -> "$executable --install-module $zipPath"
            }
            val installResult = Shell.cmd(installCommand).exec()
            if (!installResult.isSuccess) {
                val details = (installResult.out + installResult.err)
                    .joinToString("\n")
                    .take(MAX_INSTALL_ERROR_LENGTH)
                throw IOException("${installer.kind} module installation failed: $details")
            }
        } finally {
            tempZipFile.delete()
        }
    }

    private fun detectRootInstaller(): RootInstaller? {
        val command = """
            if [ -x /data/adb/ksud ]; then
                printf 'KERNEL_SU|/data/adb/ksud\n'
            elif [ -x /data/adb/ap/bin/apd ]; then
                printf 'APATCH|/data/adb/ap/bin/apd\n'
            elif [ -x /data/adb/apd ]; then
                printf 'APATCH|/data/adb/apd\n'
            elif command -v ksud >/dev/null 2>&1; then
                printf 'KERNEL_SU|%s\n' "${'$'}(command -v ksud)"
            elif command -v apd >/dev/null 2>&1; then
                printf 'APATCH|%s\n' "${'$'}(command -v apd)"
            elif command -v magisk >/dev/null 2>&1; then
                printf 'MAGISK|%s\n' "${'$'}(command -v magisk)"
            else
                exit 127
            fi
        """.trimIndent()
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) return null

        val parts = result.out.firstOrNull()?.trim()?.split('|', limit = 2) ?: return null
        if (parts.size != 2 || parts[1].isBlank()) return null
        val kind = runCatching { RootManager.valueOf(parts[0]) }.getOrNull() ?: return null
        return RootInstaller(kind, parts[1])
    }

    private fun kernelSuRequiresMetaModule(executable: String): Boolean {
        val result = Shell.cmd("${shellQuote(executable)} -V 2>/dev/null").exec()
        return result.isSuccess && kernelSuRequiresMetaModule(result.out.joinToString(" "))
    }

    private fun hasInstalledMetaModule(): Boolean {
        val command = """
            for prop in \
                /data/adb/metamodule/module.prop \
                /data/adb/modules/*/module.prop \
                /data/adb/modules_update/*/module.prop; do
                [ -f "${'$'}prop" ] || continue
                module_dir="${'$'}{prop%/module.prop}"
                [ -f "${'$'}module_dir/remove" ] && continue
                if grep -Eq '^metamodule=(1|true)${'$'}' "${'$'}prop"; then
                    exit 0
                fi
            done
            exit 1
        """.trimIndent()
        return Shell.cmd(command).exec().isSuccess
    }

    private fun cleanupLegacyBootstrapFiles() {
        LEGACY_BOOTSTRAP_FILES.forEach { fileName ->
            runCatching { File(filesDir, fileName).delete() }
        }
    }

    private fun installedModuleVersionCode(): Int {
        val command = """
            read_module_version() {
                module_dir="${'$'}1"
                prop="${'$'}module_dir/module.prop"
                hosts="${'$'}module_dir/system/etc/hosts"
                [ -f "${'$'}prop" ] || return 1
                [ -s "${'$'}hosts" ] || return 1
                [ -f "${'$'}module_dir/remove" ] && return 1
                grep -q '^id=AD_lite${'$'}' "${'$'}prop" || return 1
                version="${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" | head -n 1)"
                case "${'$'}version" in
                    ''|*[!0-9]*) return 1 ;;
                    *) printf '%s\n' "${'$'}version" ;;
                esac
            }

            if [ -d /data/adb/modules_update/AD_lite ]; then
                read_module_version /data/adb/modules_update/AD_lite || printf '0\n'
            else
                read_module_version /data/adb/modules/AD_lite || printf '0\n'
            fi
        """.trimIndent()
        return Shell.cmd(command).exec().out.firstOrNull()?.toIntOrNull() ?: 0
    }

    private fun exitSplashScreen() {
        if (isFinishingFlow || isFinishing || isDestroyed) return
        isFinishingFlow = true
        startActivity(Intent(this, MainActivity::class.java))
        applyPendingTransition(R.anim.main_activity_enter, R.anim.start_activity_exit)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun applyPendingTransition(enterAnimation: Int, exitAnimation: Int) {
        overridePendingTransition(enterAnimation, exitAnimation)
    }

    private fun copyAssetToPrivateStorage(context: Context, assetFileName: String): File {
        val outputFile = File(context.filesDir, assetFileName)
        var tempFile: File? = null
        try {
            outputFile.parentFile?.mkdirs()
            val copyTempFile = File.createTempFile("${outputFile.name}.", ".tmp", outputFile.parentFile)
            tempFile = copyTempFile

            context.assets.open(assetFileName).use { inputStream ->
                FileOutputStream(copyTempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    outputStream.fd.sync()
                }
            }

            try {
                Files.move(
                    copyTempFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(copyTempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return outputFile
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    companion object {
        private const val TAG = "StartActivity"
        private const val SETUP_PREFERENCES_NAME = "initial_setup"
        private const val KEY_INITIAL_SETUP_COMPLETE = "complete"
        private const val MODULE_ZIP_NAME = "AD_lite-v1.0.1.zip"
        private const val REQUIRED_MODULE_VERSION_CODE = 11
        private const val MAX_INSTALL_ERROR_LENGTH = 2_000
        private const val SETUP_CARD_ENTER_OFFSET_DP = 18f
        private const val SETUP_CARD_ANIMATION_MS = 260L
        private const val RECOMMENDED_ADDED_ALPHA = 0.6f
        private val LEGACY_BOOTSTRAP_FILES = listOf("hosts", "AD_lite-v1.0.0.zip")

        internal fun kernelSuRequiresMetaModule(versionOutput: String): Boolean {
            val match = Regex("(?:^|\\s)v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .find(versionOutput.trim()) ?: return false
            return match.groupValues[1].toIntOrNull()?.let { it >= 3 } == true
        }
    }

    internal enum class RootManager {
        KERNEL_SU {
            override val displayName = "KernelSU"
        },
        APATCH {
            override val displayName = "APatch"
        },
        MAGISK {
            override val displayName = "Magisk"
        };

        abstract val displayName: String
    }

    private data class RootInstaller(
        val kind: RootManager,
        val executable: String
    )

    internal data class InitializationOutcome(
        val manager: RootManager?,
        val moduleVersion: Int,
        @StringRes val statusMessage: Int?
    )

    internal sealed interface InitializationState {
        data object Idle : InitializationState
        data object Running : InitializationState
        data object RootUnavailable : InitializationState
        data class Complete(val outcome: InitializationOutcome) : InitializationState
        data class Failed(val error: Throwable) : InitializationState
    }

    internal class InitializationViewModel : ViewModel() {
        val state = MutableLiveData<InitializationState>(InitializationState.Idle)

        @Volatile
        private var running = false
        @Volatile
        private var worker: Thread? = null

        fun start(
            resetCachedShell: Boolean,
            initialize: (Boolean) -> InitializationOutcome?
        ) {
            synchronized(this) {
                if (running) return
                running = true
                state.value = InitializationState.Running
            }

            worker = Thread {
                val nextState = try {
                    val outcome = initialize(resetCachedShell)
                    if (outcome == null) {
                        InitializationState.RootUnavailable
                    } else {
                        Thread.sleep(800)
                        InitializationState.Complete(outcome)
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    InitializationState.Failed(error)
                } catch (error: Exception) {
                    InitializationState.Failed(error)
                }
                running = false
                worker = null
                state.postValue(nextState)
            }.also(Thread::start)
        }

        override fun onCleared() {
            worker?.interrupt()
            worker = null
            running = false
            super.onCleared()
        }
    }
}
