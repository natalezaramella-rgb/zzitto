package com.zaramella.zzitto.licensing

import javax.inject.Qualifier

// Qualificatori Hilt del modulo :licensing. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.

/** Scope applicativo del modulo (SupervisorJob), vive quanto il processo. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LicensingScope

/** Dispatcher per I/O su disco e Keystore (sostituibile nei test). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LicensingIo

/** Copia locale della data di inizio prova. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalTrial

/** Copia della data di inizio prova legata all'account Google (Block Store). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountTrial
