package com.zaramella.zzitto.dictation.state

/**
 * Macchina a stati del ciclo di dettatura: l'unica del progetto (Spec §0, §5.2).
 * Aggiunto il 22/10/2026 alle 12:05.
 *
 * Inattivo → Ascolto → Trascrizione → Correzione → Inserimento → Inattivo;
 * Errore raggiungibile da ogni stato; l'utente può annullare in qualsiasi stato.
 * La fase di Correzione può mancare (scorciatoia vocale, Spec §5.11): Trascrizione → Inserimento.
 */
sealed interface DictationState {
    /** Inattivo: icona a riposo. */
    data object Idle : DictationState

    /** Ascolto: microfono aperto. [rewrite] = riscrittura del testo selezionato (Spec §5.10). */
    data class Listening(val rewrite: Boolean) : DictationState

    /** Trascrizione in corso (Whisper). */
    data object Transcribing : DictationState

    /** Correzione o traduzione in corso (Phi-4-mini, Spec §5.3, §5.12). */
    data object Correcting : DictationState

    /** Inserimento del testo e azioni nel campo (Spec §5.4, §5.9). */
    data object Inserting : DictationState

    /** Errore, mostrato brevemente dall'icona e poi di nuovo Inattivo. */
    data class Error(val kind: ErrorKind) : DictationState
}

enum class ErrorKind {
    /** Nessun campo di testo con focus. */
    NO_FIELD,

    /** Permesso microfono non concesso. */
    MIC_PERMISSION,

    /** Microfono occupato o non disponibile. */
    MIC_UNAVAILABLE,

    /** Audio senza parlato. */
    NO_SPEECH,

    /** Modelli mancanti, corrotti o non caricabili (Spec §7). */
    MODELS,

    /** Errore del motore durante l'elaborazione. */
    ENGINE,

    /** Inserimento non riuscito: il testo è negli appunti. */
    INSERT_FAILED,

    /** Prova scaduta: la dettatura è bloccata (Spec §8.1). */
    LICENSE,
}

/** Eventi del ciclo di dettatura. */
sealed interface DictationEvent {
    data class StartListening(val rewrite: Boolean) : DictationEvent
    data object StopListening : DictationEvent
    data object CorrectionStarted : DictationEvent
    data object ResultReady : DictationEvent
    data object InsertionDone : DictationEvent
    data class Failed(val kind: ErrorKind) : DictationEvent
    data object Cancel : DictationEvent
    data object ErrorShown : DictationEvent
}

object DictationStateMachine {

    /** Stato successivo, oppure null se l'evento non è ammesso nello stato corrente. */
    fun next(state: DictationState, event: DictationEvent): DictationState? = when (event) {
        // Errore e annullamento da ogni stato (Spec §5.2).
        is DictationEvent.Failed -> DictationState.Error(event.kind)
        DictationEvent.Cancel -> DictationState.Idle

        is DictationEvent.StartListening -> when (state) {
            DictationState.Idle, is DictationState.Error -> DictationState.Listening(event.rewrite)
            else -> null
        }
        DictationEvent.StopListening -> when (state) {
            is DictationState.Listening -> DictationState.Transcribing
            else -> null
        }
        DictationEvent.CorrectionStarted -> when (state) {
            DictationState.Transcribing -> DictationState.Correcting
            DictationState.Correcting -> DictationState.Correcting
            else -> null
        }
        DictationEvent.ResultReady -> when (state) {
            DictationState.Transcribing, DictationState.Correcting -> DictationState.Inserting
            else -> null
        }
        DictationEvent.InsertionDone -> when (state) {
            DictationState.Inserting -> DictationState.Idle
            else -> null
        }
        DictationEvent.ErrorShown -> when (state) {
            is DictationState.Error -> DictationState.Idle
            else -> null
        }
    }

    /** True se l'icona deve restare visibile anche senza campo con focus (operazione in corso). */
    fun isBusy(state: DictationState): Boolean =
        state !is DictationState.Idle && state !is DictationState.Error
}
