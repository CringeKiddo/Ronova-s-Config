package com.ronova.config.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.ronova.config.adb.AdbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Reads real frame-present timestamps from SurfaceFlinger; no GPU polling, no extra rendering. */
internal class FpsMonitor(private val adb: AdbProtocol, private val pkg: String) {

    private var layer: String? = null

    private suspend fun findLayer(): String? {
        val r = adb.executeShell("dumpsys SurfaceFlinger --list")
        if (!r.success) return null
        val layers = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(pkg) }
        return layers.firstOrNull { it.contains("SurfaceView") && !it.contains("Background for") }
            ?: layers.firstOrNull { it.contains("BLAST") && !it.contains("Background for") }
            ?: layers.firstOrNull { !it.contains("Background for") }
    }

    suspend fun sample(): Int? {
        val name = layer ?: findLayer().also { layer = it } ?: return null
        val quoted = name.replace("'", "'\\''")
        val r = adb.executeShell("dumpsys SurfaceFlinger --latency '$quoted'")
        if (!r.success) {
            layer = null
            return null
        }
        val lines = r.stdout.lines().filter { it.isNotBlank() }
        if (lines.size < 2) {
            layer = null
            return null
        }
        val presents = lines.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) parts[1].toLongOrNull() else null
        }.filter { it > 0L && it < Long.MAX_VALUE / 2 }.sorted()

        if (presents.size < 2) return 0
        val newest = presents.last()
        if (System.nanoTime() - newest > 1_500_000_000L) return 0
        val window = presents.filter { newest - it <= 1_000_000_000L }
        if (window.size < 2) return 0
        return ((window.size - 1) * 1_000_000_000.0 / (newest - window.first())).roundToInt()
    }
}

class ZeroLagFpsOverlay(
    private val context: Context,
    private val adb: AdbProtocol,
    private val scope: CoroutineScope
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var job: Job? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    suspend fun start(targetPackage: String, intervalMs: Long): Boolean {
        if (!canDraw()) return false
        stop()
        withContext(Dispatchers.Main) { attach() }
        val monitor = FpsMonitor(adb, targetPackage)
        val interval = intervalMs.coerceIn(250L, 5_000L)
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val fps = try { monitor.sample() } catch (_: Exception) { null }
                val text = fps?.toString() ?: "--"
                withContext(Dispatchers.Main) { view?.text = text }
                delay(interval)
            }
        }
        return true
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        withContext(Dispatchers.Main) { detachNow() }
    }

    /** Must be called on the main thread. */
    fun detachNow() {
        view?.let {
            try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}
        }
        view = null
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun attach() {
        if (view != null) return
        val tv = TextView(context).apply {
            text = "--"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            includeFontPadding = false
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8f)
            y = dp(4f)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(tv, params)
            view = tv
        } catch (_: Exception) {
            view = null
        }
    }
}
