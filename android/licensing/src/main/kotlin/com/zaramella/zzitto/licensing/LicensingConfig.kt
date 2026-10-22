package com.zaramella.zzitto.licensing

/**
 * Costanti del modulo :licensing. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 */
object LicensingConfig {
    /** ID del prodotto in-app NON consumabile da creare nella Play Console. Spec §8.1. */
    const val PRODUCT_ID: String = "zzitto_unlock"

    /** Durata della prova gratuita. Spec §8.1. */
    const val TRIAL_DAYS: Int = 30

    /**
     * Tempo massimo di attesa per la verifica online all'avvio. Oltre questo limite la verifica
     * si considera non disponibile e la prova continua (Spec §8.1: "senza connessione la prova continua").
     */
    const val ONLINE_CHECK_TIMEOUT_MILLIS: Long = 15_000L
}
