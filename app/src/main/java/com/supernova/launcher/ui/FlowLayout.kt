package com.supernova.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

// Wrapping horizontal layout for pills: children flow left to right and wrap
// onto the next line. maxLines caps the number of lines; children that would
// land beyond it are measured to zero and skipped, which is how the feed's
// "suggested shortcuts rows" count becomes literal rows on screen.
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var horizontalGap: Int = dp(8)
    var verticalGap: Int = dp(8)
    var maxLines: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    // Children that fit within maxLines on the last measure.
    private val placed = ArrayList<View>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        var lineWidth = 0
        var lineHeight = 0
        var lines = 1
        var totalHeight = 0
        var maxLineWidth = 0
        placed.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (lineWidth > 0 && lineWidth + horizontalGap + cw > available) {
                if (lines >= maxLines) break
                lines++
                totalHeight += lineHeight + verticalGap
                maxLineWidth = max(maxLineWidth, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += (if (lineWidth > 0) horizontalGap else 0) + cw
            lineHeight = max(lineHeight, ch)
            placed.add(child)
        }
        totalHeight += lineHeight
        maxLineWidth = max(maxLineWidth, lineWidth)
        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> width
            else -> maxLineWidth + paddingLeft + paddingRight
        }
        val wantedHeight = totalHeight + paddingTop + paddingBottom
        val measuredHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wantedHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> wantedHeight
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (child !in placed) {
                // Beyond maxLines: parked out of view, never drawn.
                child.layout(0, 0, 0, 0)
                continue
            }
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > paddingLeft && x + cw > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight + verticalGap
                lineHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + horizontalGap
            lineHeight = max(lineHeight, ch)
        }
    }

}
