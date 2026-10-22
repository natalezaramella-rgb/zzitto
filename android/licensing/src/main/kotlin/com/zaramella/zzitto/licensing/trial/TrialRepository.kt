package com.zaramella.zzitto.licensing.trial

import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.licensing.AccountTrial
import com.zaramella.zzitto.licensing.LicensingConfig
import com.zaramella.zzitto.licensing.LocalTrial
import com.zaramella.zzitto.licensing.WallClock
import com.zaramella.zzitto.licensing.catchingNonCancellation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestisce la data di inizio prova su due supporti: Block Store (account Google) e copia locale.
 * Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * - Primo avvio: la prova inizia adesso e viene scritta su entrambi.
 * - Reinstallazione: la copia locale è vuota, Block Store restituisce la data originale → la prova non riparte.
 * - Block Store non disponibile: si usa solo la copia locale (fallback), senza errori.
 * - Orologio implausibile (prima del 2026): la prova non viene registrata in questo avvio.
 */
@Singleton
class TrialRepository @Inject constructor(
    @param:LocalTrial private val local: TrialStore,
    @param:AccountTrial private val account: TrialStore,
    private val clock: WallClock,
) {

    /** Stato della prova ([LicenseState.Trial] o [LicenseState.TrialExpired]). Non lancia eccezioni. */
    suspend fun currentState(): LicenseState {
        val now = clock.nowMillis()
        val localRecord = catchingNonCancellation { local.read() }.getOrNull()
        val accountRead = catchingNonCancellation { account.read() }
        val accountRecord = accountRead.getOrNull()
        val accountAvailable = accountRead.isSuccess

        val merged = TrialPolicy.merge(localRecord, accountRecord)
        val current = when {
            merged != null -> TrialPolicy.advance(merged, now)
            TrialPolicy.isPlausible(now) -> TrialPolicy.start(now)
            // Orologio sicuramente sbagliato: niente registrazione, prova piena per questo avvio.
            else -> return LicenseState.Trial(LicensingConfig.TRIAL_DAYS)
        }

        if (current != localRecord) {
            catchingNonCancellation { local.write(current) }
        }
        if (accountAvailable && TrialPolicy.needsAccountWrite(accountRecord, current)) {
            catchingNonCancellation { account.write(current) }
        }
        return TrialPolicy.evaluate(current, now)
    }
}
