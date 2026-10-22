package com.zaramella.zzitto.dictation.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.zaramella.zzitto.dictation.R
import com.zaramella.zzitto.dictation.state.DictationState
import kotlin.math.abs
import kotlin.math.min

/** Aspetto dell'icona: oltre agli stati del ciclo c'è la prova scaduta (Spec §8.1). */
sealed interface IconAppearance {
    data class Cycle(val state: DictationState) : IconAppearance
    data object Locked : IconAppearance
}

/**
 * Icona del microfono disegnata su Canvas. Lo stato si comunica solo con colore e
 * animazione (Spec §2.5). Aggiunto il 22/10/2026 alle 12:20.
 *
 * Gesti: tap (avvia/ferma), pressione lunga (menu), trascinamento (sposta e ricorda, Spec §5.1-bis).
 */
@SuppressLint("ViewConstructor")
class MicIconView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragBy(dx: Int, dy: Int)
        fun onDragEnd()
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()
    private val micDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.zz_ic_mic)?.mutate()

    private var appearance: IconAppearance = IconAppearance.Cycle(DictationState.Idle)
    private var level = 0f
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    // Gesti
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        performLongClick()
    }

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        setOnClickListener { listener.onTap() }
        setOnLongClickListener {
            listener.onLongPress()
            true
        }
        updateContentDescription()
    }

    fun render(newAppearance: IconAppearance, newLevel: Float) {
        val changed = newAppearance != appearance
        appearance = newAppearance
        level = newLevel.coerceIn(0f, 1f)
        if (changed) {
            updateContentDescription()
            if (needsAnimation()) {
                if (!animator.isStarted) animator.start()
            } else {
                animator.cancel()
            }
        }
        invalidate()
    }

    private fun needsAnimation(): Boolean {
        val a = appearance
        return a is IconAppearance.Cycle && a.state !is DictationState.Idle && a.state !is DictationState.Error
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        mainHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = size * 0.36f

        val color = colorFor(appearance)
        circlePaint.color = color

        when (val a = appearance) {
            is IconAppearance.Cycle -> when (a.state) {
                is DictationState.Listening -> {
                    // Alone pulsante proporzionale al livello della voce.
                    val pulse = baseRadius * (1f + 0.25f * maxOf(level * 4f, 0.15f + 0.1f * phaseWave()))
                    circlePaint.alpha = 70
                    canvas.drawCircle(cx, cy, min(pulse, size / 2f), circlePaint)
                    circlePaint.alpha = 255
                }
                DictationState.Transcribing, DictationState.Correcting, DictationState.Inserting -> {
                    // Arco rotante: elaborazione in corso.
                    ringPaint.color = color
                    ringPaint.strokeWidth = size * 0.07f
                    val r = baseRadius + size * 0.09f
                    arcBounds.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(arcBounds, phase * 360f, 100f, false, ringPaint)
                }
                else -> Unit
            }
            IconAppearance.Locked -> Unit
        }

        canvas.drawCircle(cx, cy, baseRadius, circlePaint)

        micDrawable?.let { d ->
            val half = (baseRadius * 0.62f).toInt()
            d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            d.setTint(Color.WHITE)
            d.alpha = if (appearance is IconAppearance.Locked) 140 else 255
            d.draw(canvas)
        }
    }

    private fun phaseWave(): Float = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
                longPressed = false
                isPressed = true
                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longPressed &&
                    (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)
                ) {
                    dragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) {
                        lastX += dx
                        lastY += dy
                        listener.onDragBy(dx, dy)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                isPressed = false
                when {
                    dragging -> listener.onDragEnd()
                    !longPressed -> performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                isPressed = false
                if (dragging) listener.onDragEnd()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateContentDescription() {
        // Descrizione per TalkBack: l'icona resta comunque senza testo visibile (Spec §2.5).
        val res = when (val a = appearance) {
            IconAppearance.Locked -> R.string.zz_icon_locked
            is IconAppearance.Cycle -> when (a.state) {
                DictationState.Idle -> R.string.zz_icon_idle
                is DictationState.Listening -> R.string.zz_icon_listening
                DictationState.Transcribing -> R.string.zz_icon_transcribing
                DictationState.Correcting -> R.string.zz_icon_correcting
                DictationState.Inserting -> R.string.zz_icon_inserting
                is DictationState.Error -> R.string.zz_icon_error
            }
        }
        contentDescription = context.getString(res)
    }

    private fun colorFor(a: IconAppearance): Int = ContextCompat.getColor(
        context,
        when (a) {
            IconAppearance.Locked -> R.color.zz_icon_locked
            is IconAppearance.Cycle -> when (a.state) {
                DictationState.Idle -> R.color.zz_icon_idle
                is DictationState.Listening -> R.color.zz_icon_listening
                DictationState.Transcribing -> R.color.zz_icon_transcribing
                DictationState.Correcting -> R.color.zz_icon_correcting
                DictationState.Inserting -> R.color.zz_icon_inserting
                is DictationState.Error -> R.color.zz_icon_error
            }
        },
    )
}
