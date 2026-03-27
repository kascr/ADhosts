package com.kascr.adhosts.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    // 用于存储 ViewBinding 对象
    private lateinit var _binding: VB
    protected val binding get() = _binding

    // 在 onCreate() 中初始化 ViewBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取 ViewBinding 对象
        _binding = getViewBinding()

        // 设置布局内容
        setContentView(binding.root)
    }
    // 由子类实现，提供获取 ViewBinding 实例的方法
    abstract fun getViewBinding(): VB
}
