package com.example.myown_browser_android

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView

class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var onScrollChangedCallback: ((l: Int, t: Int, oldl: Int, oldt: Int) -> Unit)? = null
    var onTapCallback: (() -> Unit)? = null
    var onSwipeDownCallback: (() -> Unit)? = null

    init {
        // Allow the WebView to be focused
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTapCallback?.invoke()
            return super.onSingleTapUp(e)
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val SWIPE_THRESHOLD_VELOCITY = 400
            if (scrollY == 0 && velocityY > SWIPE_THRESHOLD_VELOCITY && Math.abs(velocityY) > Math.abs(velocityX)) {
                onSwipeDownCallback?.invoke()
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    })

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedCallback?.invoke(l, t, oldl, oldt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}
