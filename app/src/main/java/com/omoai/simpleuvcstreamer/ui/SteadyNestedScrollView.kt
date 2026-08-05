package com.omoai.simpleuvcstreamer.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * NestedScrollView that does not auto-scroll when a child gains focus or
 * requests to be brought on screen (e.g. EditText after periodic layout).
 * Manual user scrolling is unchanged.
 */
class SteadyNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean,
    ): Boolean = false
}
