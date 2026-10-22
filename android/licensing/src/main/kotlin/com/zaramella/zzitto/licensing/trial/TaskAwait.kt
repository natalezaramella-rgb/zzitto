package com.zaramella.zzitto.licensing.trial

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Attende un [Task] di Google Play Services senza dipendere da kotlinx-coroutines-play-services.
 * Aggiunto il 22/10/2026 alle 11:28.
 */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (!cont.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> cont.cancel()
            task.isSuccessful -> cont.resume(task.result)
            else -> cont.resumeWithException(
                task.exception ?: IllegalStateException("Task di Google Play Services non riuscito"),
            )
        }
    }
}
