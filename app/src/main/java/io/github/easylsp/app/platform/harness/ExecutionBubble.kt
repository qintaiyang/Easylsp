package io.github.easylsp.app.platform.harness

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.TextView
import android.widget.Toast
import io.github.easylsp.app.MainActivity
import kotlin.math.abs

/** A user-visible entry to the foreground execution service. */
internal class ExecutionBubble(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val size = (48 * context.resources.displayMetrics.density).toInt()
    private val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.END; x = size / 4; y = size * 3
    }
    private var attached = true
    private val view = TextView(context).apply {
        text = "EL"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        contentDescription = "Easylsp 运行中，点击返回，长按隐藏"
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(58, 89, 148)) }
        elevation = 4 * resources.displayMetrics.density
        setOnClickListener { context.startActivity(Intent(context, MainActivity::class.java).putExtra("route", "conversations").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        setOnLongClickListener { close(); Toast.makeText(context, "已隐藏悬浮圈，任务继续运行", Toast.LENGTH_SHORT).show(); true }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originalX = params.x; originalY = params.y; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) { cancelLongPress(); isPressed = false; params.x = originalX - dx.toInt(); params.y = originalY + dy.toInt(); manager.updateViewLayout(this, params) }
                    dragging
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }
    init { manager.addView(view, params) }
    override fun close() { if (attached) { manager.removeView(view); attached = false } }
}
