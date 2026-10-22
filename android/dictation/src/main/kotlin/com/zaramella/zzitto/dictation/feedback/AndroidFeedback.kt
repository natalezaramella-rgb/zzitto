package com.zaramella.zzitto.dictation.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import androidx.annotation.StringRes
import com.zaramella.zzitto.dictation.ListeningFeedback
import com.zaramella.zzitto.dictation.Notice
import com.zaramella.zzitto.dictation.R
import com.zaramella.zzitto.dictation.UserNotifier
import com.zaramella.zzitto.dictation.settings.DictationSettings

/**
 * Vibrazione e suono all'avvio dell'ascolto (Spec §5.7). Aggiunto il 22/10/2026 alle 11:59.
 */
class AndroidListeningFeedback(private val context: Context) : ListeningFeedback {

    private val handler = Handler(Looper.getMainLooper())

    override fun onListeningStarted(settings: DictationSettings) {
        if (settings.vibrateOnStart) {
            runCatching {
                val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
            }
        }
        if (settings.soundOnStart) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
                handler.postDelayed({ tone.release() }, TONE_MS.toLong() + 100)
            }
        }
    }

    private companion object {
        const val TONE_VOLUME = 60
        const val TONE_MS = 80
    }
}

/**
 * Avvisi brevi con Toast, solo quando serve un'azione dell'utente o un fallback (Spec §2.5, §2.6, §5.3).
 * Gli stati normali si vedono solo dal colore dell'icona.
 */
class ToastNotifier(private val context: Context) : UserNotifier {

    private val handler = Handler(Looper.getMainLooper())

    override fun notify(notice: Notice) {
        val message = context.getString(messageFor(notice))
        handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @StringRes
    private fun messageFor(notice: Notice): Int = when (notice) {
        Notice.RAW_TEXT_INSERTED -> R.string.zz_notice_raw_text
        Notice.COPIED_TO_CLIPBOARD -> R.string.zz_notice_copied
        Notice.MIC_PERMISSION -> R.string.zz_notice_mic_permission
        Notice.MIC_UNAVAILABLE -> R.string.zz_notice_mic_unavailable
        Notice.MODELS_ERROR -> R.string.zz_notice_models
        Notice.ENGINE_ERROR -> R.string.zz_notice_engine
        Notice.UNDO_UNAVAILABLE -> R.string.zz_notice_undo_unavailable
        Notice.SEND_UNAVAILABLE -> R.string.zz_notice_send_unavailable
        Notice.NOTHING_TO_DELETE -> R.string.zz_notice_nothing_to_delete
        Notice.TRIAL_EXPIRED -> R.string.zz_notice_trial_expired
    }
}
