package com.omoai.simpleuvcstreamer.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object SafeArea {
    fun apply(activity: Activity, rootId: Int) {
        val root = activity.findViewById<View>(rootId)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val nextLeft = initialLeft + bars.left
            val nextTop = initialTop + bars.top
            val nextRight = initialRight + bars.right
            val nextBottom = initialBottom + bars.bottom
            if (v.paddingLeft != nextLeft ||
                v.paddingTop != nextTop ||
                v.paddingRight != nextRight ||
                v.paddingBottom != nextBottom
            ) {
                v.setPadding(nextLeft, nextTop, nextRight, nextBottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
