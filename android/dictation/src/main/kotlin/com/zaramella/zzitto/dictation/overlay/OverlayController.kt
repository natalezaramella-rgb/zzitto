package com.zaramella.zzitto.dictation.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zaramella.zzitto.dictation.R
import kotlin.math.roundToInt

/** Calcoli di posizione dell'icona, separati per i test (Spec §5.1-bis). Aggiunto il 22/10/2026 alle 11:59. */
object IconPlacement {
    /** Posizione predefinita: bordo destro, a circa il 60% dell'altezza. */
    fun default(screenWidth: Int, screenHeight: Int, iconSize: Int, margin: Int): Pair<Int, Int> =
        maxOf(0, screenWidth - iconSize - margin) to (screenHeight * 6 / 10)

    /** Mantiene l'icona interamente sullo schermo. */
    fun clamp(x: Int, y: Int, screenWidth: Int, screenHeight: Int, iconSize: Int): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, screenWidth - iconSize)) to y.coerceIn(0, maxOf(0, screenHeight - iconSize))
}

/** Voce del menu a pressione lunga (Spec §5.1). */
data class OverlayMenuItem(val label: String, val action: () -> Unit)

/**
 * Finestre overlay dell'icona e del menu, di tipo TYPE_ACCESSIBILITY_OVERLAY (Spec §5.1-bis):
 * disponibili al servizio di accessibilità senza il permesso SYSTEM_ALERT_WINDOW.
 * Le finestre non prendono il focus, così la tastiera dell'utente resta attiva.
 * Aggiunto il 22/10/2026 alle 11:59.
 *
 * [context] deve essere il servizio di accessibilità.
 */
class OverlayController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onIconTap()
        fun onIconLongPress()

        /** Nuova posizione scelta dall'utente, da salvare. */
        fun onIconMoved(x: Int, y: Int)
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val iconSizePx = dp(ICON_DP)
    private val marginPx = dp(MARGIN_DP)

    private val iconParams = WindowManager.LayoutParams(
        iconSizePx,
        iconSizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Zzitto"
    }

    private val iconView = MicIconView(
        context,
        object : MicIconView.Listener {
            override fun onTap() = callbacks.onIconTap()
            override fun onLongPress() = callbacks.onIconLongPress()
            override fun onDragBy(dx: Int, dy: Int) = moveBy(dx, dy)
            override fun onDragEnd() = callbacks.onIconMoved(iconParams.x, iconParams.y)
        },
    )

    private var iconAttached = false
    private var menuView: View? = null

    val isIconVisible: Boolean
        get() = iconAttached

    /** Mostra l'icona nella posizione salvata (x, y negativi = predefinita). */
    fun showIcon(savedX: Int, savedY: Int) {
        val screen = screenBounds()
        val (x, y) = if (savedX < 0 || savedY < 0) {
            IconPlacement.default(screen.width(), screen.height(), iconSizePx, marginPx)
        } else {
            IconPlacement.clamp(savedX, savedY, screen.width(), screen.height(), iconSizePx)
        }
        iconParams.x = x
        iconParams.y = y
        if (iconAttached) {
            windowManager.updateViewLayout(iconView, iconParams)
        } else {
            windowManager.addView(iconView, iconParams)
            iconAttached = true
        }
    }

    fun hideIcon() {
        dismissMenu()
        if (iconAttached) {
            windowManager.removeViewImmediate(iconView)
            iconAttached = false
        }
    }

    fun render(appearance: IconAppearance, level: Float) {
        iconView.render(appearance, level)
    }

    fun showMenu(items: List<OverlayMenuItem>) {
        dismissMenu()
        if (!iconAttached || items.isEmpty()) return
        val menu = buildMenu(items)
        val screen = screenBounds()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Zzitto menu"
        }
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.height(), View.MeasureSpec.AT_MOST),
        )
        // Il menu si apre verso il centro dello schermo rispetto all'icona.
        val openLeft = iconParams.x > screen.width() / 2
        val x = if (openLeft) iconParams.x - menu.measuredWidth - marginPx / 2 else iconParams.x + iconSizePx + marginPx / 2
        val y = iconParams.y + iconSizePx / 2 - menu.measuredHeight / 2
        val (cx, cy) = IconPlacement.clamp(x, y, screen.width(), screen.height(), 0)
        params.x = cx.coerceAtMost(maxOf(0, screen.width() - menu.measuredWidth))
        params.y = cy.coerceAtMost(maxOf(0, screen.height() - menu.measuredHeight))
        windowManager.addView(menu, params)
        menuView = menu
    }

    fun dismissMenu() {
        menuView?.let { windowManager.removeViewImmediate(it) }
        menuView = null
    }

    private fun moveBy(dx: Int, dy: Int) {
        if (!iconAttached) return
        val screen = screenBounds()
        val (x, y) = IconPlacement.clamp(iconParams.x + dx, iconParams.y + dy, screen.width(), screen.height(), iconSizePx)
        iconParams.x = x
        iconParams.y = y
        windowManager.updateViewLayout(iconView, iconParams)
        dismissMenu()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMenu(items: List<OverlayMenuItem>): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(context, R.color.zz_menu_background))
            }
            elevation = dp(6).toFloat()
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismissMenu()
                    true
                } else {
                    false
                }
            }
        }
        val hPad = dp(16)
        val vPad = dp(12)
        items.forEach { item ->
            val tv = TextView(context).apply {
                text = item.label
                setTextColor(ContextCompat.getColor(context, R.color.zz_menu_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(hPad, vPad, hPad, vPad)
                minWidth = dp(200)
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    dismissMenu()
                    item.action()
                }
            }
            layout.addView(tv)
        }
        return layout
    }

    private fun screenBounds(): Rect = windowManager.currentWindowMetrics.bounds

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val ICON_DP = 56
        const val MARGIN_DP = 12
    }
}
