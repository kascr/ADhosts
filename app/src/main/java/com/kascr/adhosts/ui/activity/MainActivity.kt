package com.kascr.adhosts.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.kascr.adhosts.R
import com.kascr.adhosts.ui.base.BaseActivity
import com.kascr.adhosts.databinding.ActivityMainBinding
import com.kascr.adhosts.ui.fragment.SetFragment
import com.kascr.adhosts.ui.fragment.SettingsFragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.kascr.adhosts.ui.fragment.HostsFragment

/**
 * 主界面 Activity
 * 采用 ViewPager2 + 完全自定义导航栏，彻底控制图标与文字间距。
 */
class MainActivity : BaseActivity<ActivityMainBinding>() {

    private lateinit var adapter: ViewPagerAdapter

    // 导航项容器（胶囊）
    private lateinit var navPills: List<LinearLayout>
    // 导航图标
    private lateinit var navIcons: List<ImageView>
    // 导航文字
    private lateinit var navLabels: List<TextView>

    private val selectedColor = 0xFF42A5F5.toInt()   // 蓝色
    private val unselectedColor = 0xFF8B949E.toInt() // 灰色
    private val pillSelectedColor = 0xFF3D4A6B.toInt() // 胶囊选中背景色

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.viewPager) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            insets
        }

        initCustomNav()
        initViewPager()
        selectNavItem(0)
    }

    private fun initViewPager() {
        adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectNavItem(position)
            }
        })
    }

    private fun initCustomNav() {
        val root = binding.root

        navPills = listOf(
            root.findViewById(R.id.nav_pill_1),
            root.findViewById(R.id.nav_pill_2),
            root.findViewById(R.id.nav_pill_3)
        )
        navIcons = listOf(
            root.findViewById(R.id.nav_icon_1),
            root.findViewById(R.id.nav_icon_2),
            root.findViewById(R.id.nav_icon_3)
        )
        navLabels = listOf(
            root.findViewById(R.id.nav_label_1),
            root.findViewById(R.id.nav_label_2),
            root.findViewById(R.id.nav_label_3)
        )

        // 点击事件
        listOf(R.id.nav_item_1, R.id.nav_item_2, R.id.nav_item_3).forEachIndexed { index, id ->
            root.findViewById<LinearLayout>(id).setOnClickListener {
                binding.viewPager.currentItem = index
            }
        }
    }

    /**
     * 切换选中的导航项：
     * - 选中项：胶囊背景亮起，图标+文字变蓝，文字显示
     * - 未选中项：胶囊背景透明，图标变灰，文字隐藏
     */
    private fun selectNavItem(index: Int) {
        navPills.forEachIndexed { i, pill ->
            val icon = navIcons[i]
            val label = navLabels[i]
            if (i == index) {
                pill.setBackgroundResource(R.drawable.nav_item_selected_bg)
                icon.setColorFilter(selectedColor)
                label.setTextColor(selectedColor)
                label.visibility = View.VISIBLE
            } else {
                pill.background = null
                icon.setColorFilter(unselectedColor)
                label.setTextColor(unselectedColor)
                label.visibility = View.GONE
            }
        }
    }

    override fun getViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    companion object {
        const val EXTRA_SKIP_SPLASH = "skip_splash"

        fun newIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_SKIP_SPLASH, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }
}

class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
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
