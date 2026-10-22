package com.lytree.qbank.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15（targetSdk 35）强制 edge-to-edge 后，贴在屏幕底部的操作条必须自己
 * 让出系统导航栏高度，否则按钮会被手势条压住。
 */
fun View.applyNavBarBottomPadding() {
    val base = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (v.paddingBottom != base + nav) {
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + nav)
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
