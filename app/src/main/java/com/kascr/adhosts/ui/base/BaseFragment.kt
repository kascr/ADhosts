package com.kascr.adhosts.ui.base

import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding


abstract class BaseFragment<VB : ViewBinding>(@LayoutRes layoutRes: Int) : Fragment(layoutRes) {




    // 用于存储 ViewBinding 对象
    private var _binding: VB? = null
    protected val binding get() = _binding!!

    // 在 onViewCreated() 中绑定视图
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = createBinding(view)
    }

    // 在销毁视图时清空绑定
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // 提供一个方法来初始化 ViewBinding
    abstract fun createBinding(view: View): VB

}

