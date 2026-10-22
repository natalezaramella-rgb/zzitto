package com.zaramella.zzitto.licensing

import kotlinx.coroutines.CancellationException

/**
 * Come [runCatching], ma lascia propagare [CancellationException] per non rompere la
 * cancellazione strutturata delle coroutine. Aggiunto il 22/10/2026 alle 11:28.
 */
internal inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
