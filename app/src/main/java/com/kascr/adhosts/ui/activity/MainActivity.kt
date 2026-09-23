package com.kascr.adhosts.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kascr.adhosts.R
import com.kascr.adhosts.databinding.ActivityMainBinding
import com.kascr.adhosts.ui.base.BaseActivity
import com.kascr.adhosts.ui.fragment.HostsFragment
import com.kascr.adhosts.ui.fragment.SetFragment
import com.kascr.adhosts.ui.fragment.SettingsFragment
import com.kascr.adhosts.utils.AppBackgroundManager

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private lateinit var adapter: ViewPagerAdapter
    private lateinit var mainBackgroundImage: ImageView
    private lateinit var mainBackgroundScrim: View
    private lateinit var navBar: BottomNavigationView

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            selectNavItem(position)
        }
    }

    private val selectedColor by lazy { ContextCompat.getColor(this, R.color.md_theme_primary) }
    private val unselectedColor by lazy { ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant) }
    private val navHorizontalMargin by lazy { 16.dpToPx() }
    private val navBottomMargin by lazy { 16.dpToPx() }

    private var customBackgroundBitmap: Bitmap? = null
    private var navGlassBitmap: Bitmap? = null
    private var currentNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        applyBackgroundAwareTheme()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        initViews()
        initInsets()
        initCustomNav()
        initViewPager()
        restoreSelectedTab(savedInstanceState)
        binding.root.post { refreshCustomBackground() }
    }

    override fun onResume() {
        super.onResume()
        refreshCustomBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_INITIAL_TAB)) {
            currentNavIndex = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_HOSTS)
            binding.viewPager.setCurrentItem(currentNavIndex, false)
            selectNavItem(currentNavIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, currentNavIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // AppCompat locale updates normally recreate the activity immediately, which exposes a
        // blank starting surface before the wallpaper is restored. Restart from the live window
        // instead, without animations, and keep the user on the current tab.
        restartWithoutAnimation(currentNavIndex)
    }

    override fun getViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onDestroy() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        customBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
        navGlassBitmap?.takeIf { !it.isRecycled }?.recycle()
        super.onDestroy()
    }

    fun refreshCustomBackground() {
        if (isFinishing || isDestroyed) {
            return
        }

        if (!isBackgroundLayoutReady()) {
            binding.root.post { refreshCustomBackground() }
            return
        }

        val bitmap = AppBackgroundManager.decodeBackgroundBitmap(
            this,
            mainBackgroundImage.width,
            mainBackgroundImage.height
        )

        customBackgroundBitmap?.takeIf { it != bitmap && !it.isRecycled }?.recycle()
        customBackgroundBitmap = bitmap

        if (bitmap == null) {
            clearCustomBackgroundState(applyWindowBackground = false)
        } else {
            mainBackgroundImage.setImageBitmap(bitmap)
            mainBackgroundImage.visibility = View.VISIBLE
            mainBackgroundScrim.visibility = View.VISIBLE
            updateNavGlassBackground(bitmap)
        }
    }

    fun onCustomBackgroundPreferenceChanged() {
        refreshCustomBackground()
    }

    private fun initViews() {
        mainBackgroundImage = binding.root.findViewById(R.id.mainBackgroundImage)
        mainBackgroundScrim = binding.root.findViewById(R.id.mainBackgroundScrim)
        mainBackgroundScrim.setBackgroundColor(resolveThemeColor(R.attr.mainBackgroundScrimColor))
    }

    private fun initInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewPager) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val navBottomSpace = navigationBarHeight + navBottomMargin
            val navView = binding.customNav.root

            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                // Navigation is a floating overlay; pages can render underneath it.
                bottomMargin = 0
            }
            navView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navHorizontalMargin
                rightMargin = navHorizontalMargin
                bottomMargin = navBottomSpace
            }
            insets
        }
    }

    private fun initCustomNav() {
        navBar = binding.customNav.navBar
        navBar.itemIconTintList = navItemColorStateList()
        navBar.itemTextColor = navItemColorStateList()
        navBar.itemActiveIndicatorColor =
            ColorStateList.valueOf(resolveThemeColor(R.attr.appSelectedNavPillColor))
        navBar.itemBackground = null
        navBar.setOnItemSelectedListener { item ->
            val target = navPositionForMenuId(item.itemId)
            if (target < 0) {
                false
            } else {
                if (binding.viewPager.currentItem != target) {
                    binding.viewPager.setCurrentItem(target, true)
                }
                true
            }
        }
    }

    private fun initViewPager() {
        adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.offscreenPageLimit = 1
        (binding.viewPager.getChildAt(0) as? RecyclerView)?.apply {
            clipToPadding = true
            clipChildren = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
    }

    private fun restoreSelectedTab(savedInstanceState: Bundle?) {
        currentNavIndex = savedInstanceState?.getInt(STATE_SELECTED_TAB)
            ?: intent.getIntExtra(EXTRA_INITIAL_TAB, 0)
        binding.viewPager.setCurrentItem(currentNavIndex, false)
        selectNavItem(currentNavIndex)
    }

    fun restartWithoutAnimation(targetTab: Int = currentNavIndex) {
        startActivity(newIntent(this, targetTab))
        applyPendingTransition(0, 0)
        finish()
        applyPendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun applyPendingTransition(enterAnimation: Int, exitAnimation: Int) {
        overridePendingTransition(enterAnimation, exitAnimation)
    }

    private fun applyBackgroundAwareTheme() {
        setTheme(R.style.AppTheme_NoAnimation_Background)
    }

    private fun selectNavItem(index: Int) {
        currentNavIndex = index
        val menuId = navMenuIdForPosition(index)
        if (navBar.selectedItemId != menuId) {
            navBar.selectedItemId = menuId
        }
    }

    private fun clearCustomBackgroundState(applyWindowBackground: Boolean = true) {
        customBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
        customBackgroundBitmap = null
        mainBackgroundImage.setImageDrawable(null)
        mainBackgroundImage.visibility = View.INVISIBLE
        mainBackgroundScrim.visibility = View.INVISIBLE
        clearNavGlassBackground()

        if (applyWindowBackground) {
            val backgroundColor = ContextCompat.getColor(this, R.color.md_theme_background)
            binding.root.setBackgroundColor(backgroundColor)
            window.decorView.setBackgroundColor(backgroundColor)
        }
    }

    private fun isBackgroundLayoutReady(): Boolean {
        return mainBackgroundImage.width > 0 && mainBackgroundImage.height > 0
    }

    private fun updateNavGlassBackground(source: Bitmap) {
        val navView = binding.customNav.root
        val blurLayer = binding.customNav.navGlassBlurLayer

        if (isFinishing || isDestroyed || source.isRecycled) {
            clearNavGlassBackground()
            return
        }

        if (navView.width == 0 || navView.height == 0 || !isBackgroundLayoutReady()) {
            navView.post { updateNavGlassBackground(source) }
            return
        }

        val navLocation = IntArray(2)
        val imageLocation = IntArray(2)
        navView.getLocationInWindow(navLocation)
        mainBackgroundImage.getLocationInWindow(imageLocation)

        val navLeft = navLocation[0] - imageLocation[0]
        val navTop = navLocation[1] - imageLocation[1]
        val navBitmap = Bitmap.createBitmap(navView.width, navView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(navBitmap)

        val scale = maxOf(
            mainBackgroundImage.width.toFloat() / source.width.toFloat(),
            mainBackgroundImage.height.toFloat() / source.height.toFloat()
        )
        val dx = (mainBackgroundImage.width - source.width * scale) / 2f - navLeft
        val dy = (mainBackgroundImage.height - source.height * scale) / 2f - navTop

        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        navGlassBitmap?.takeIf { !it.isRecycled }?.recycle()
        navGlassBitmap = navBitmap
        blurLayer.setImageBitmap(navBitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurLayer.setRenderEffect(RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP))
        }
        blurLayer.visibility = View.VISIBLE
    }

    private fun clearNavGlassBackground() {
        binding.customNav.navGlassBlurLayer.apply {
            setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(null)
            }
            visibility = View.GONE
        }
        navGlassBitmap?.takeIf { !it.isRecycled }?.recycle()
        navGlassBitmap = null
    }

    private fun navMenuIdForPosition(position: Int): Int {
        return when (position) {
            0 -> R.id.nav_hosts
            1 -> R.id.nav_tools
            2 -> R.id.nav_settings
            else -> R.id.nav_hosts
        }
    }

    private fun navPositionForMenuId(menuId: Int): Int {
        return when (menuId) {
            R.id.nav_hosts -> 0
            R.id.nav_tools -> 1
            R.id.nav_settings -> 2
            else -> -1
        }
    }

    private fun navItemColorStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(selectedColor, unselectedColor)
        )
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val TAB_HOSTS = 0
        const val TAB_TOOLS = 1
        const val TAB_SETTINGS = 2
        private const val STATE_SELECTED_TAB = "state_selected_tab"

        fun newIntent(context: Context, initialTab: Int = TAB_HOSTS): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_TAB, initialTab)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }
}

private class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HostsFragment()
            1 -> SetFragment()
            2 -> SettingsFragment()
            else -> HostsFragment()
        }
    }
}
