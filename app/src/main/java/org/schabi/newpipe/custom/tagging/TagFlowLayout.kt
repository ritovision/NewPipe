/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Small native wrapping layout for tag chips, retaining full accessible text at large font sizes. */
internal class TagFlowLayout(context: Context) : ViewGroup(context) {
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet) = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(params: LayoutParams) = MarginLayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams?) = params is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val usable = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val margin = child.layoutParams as MarginLayoutParams
            val itemWidth = child.measuredWidth + margin.leftMargin + margin.rightMargin
            val itemHeight = child.measuredHeight + margin.topMargin + margin.bottomMargin
            if (x > 0 && x + itemWidth > usable) {
                y += rowHeight
                x = 0
                rowHeight = 0
            }
            x += itemWidth
            rowHeight = maxOf(rowHeight, itemHeight)
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(y + rowHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val margin = child.layoutParams as MarginLayoutParams
            val itemWidth = child.measuredWidth + margin.leftMargin + margin.rightMargin
            val itemHeight = child.measuredHeight + margin.topMargin + margin.bottomMargin
            if (x > paddingLeft && x + itemWidth > available) {
                y += rowHeight
                x = paddingLeft
                rowHeight = 0
            }
            val childX = x + margin.leftMargin
            val childY = y + margin.topMargin
            child.layout(childX, childY, childX + child.measuredWidth, childY + child.measuredHeight)
            x += itemWidth
            rowHeight = maxOf(rowHeight, itemHeight)
        }
    }
}
