package com.zaramella.zzitto.dictation.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Macchina a stati del ciclo di dettatura (Spec §5.2). Aggiunto il 22/10/2026 alle 12:40. */
class DictationStateMachineTest {

    private val all: List<DictationState> = listOf(
        DictationState.Idle,
        DictationState.Listening(rewrite = false),
        DictationState.Listening(rewrite = true),
        DictationState.Transcribing,
        DictationState.Correcting,
        DictationState.Inserting,
        DictationState.Error(ErrorKind.ENGINE),
    )

    private fun next(s: DictationState, e: DictationEvent) = DictationStateMachine.next(s, e)

    @Test
    fun `percorso completo Inattivo-Ascolto-Trascrizione-Correzione-Inserimento-Inattivo`() {
        var s: DictationState = DictationState.Idle
        s = next(s, DictationEvent.StartListening(false))!!
        assertEquals(DictationState.Listening(false), s)
        s = next(s, DictationEvent.StopListening)!!
        assertEquals(DictationState.Transcribing, s)
        s = next(s, DictationEvent.CorrectionStarted)!!
        assertEquals(DictationState.Correcting, s)
        s = next(s, DictationEvent.ResultReady)!!
        assertEquals(DictationState.Inserting, s)
        s = next(s, DictationEvent.InsertionDone)!!
        assertEquals(DictationState.Idle, s)
    }

    @Test
    fun `scorciatoia vocale salta la correzione`() {
        assertEquals(DictationState.Inserting, next(DictationState.Transcribing, DictationEvent.ResultReady))
    }

    @Test
    fun `errore raggiungibile da ogni stato`() {
        all.forEach { s ->
            assertEquals(DictationState.Error(ErrorKind.MODELS), next(s, DictationEvent.Failed(ErrorKind.MODELS)), "da $s")
        }
    }

    @Test
    fun `annullamento da ogni stato riporta a Inattivo`() {
        all.forEach { s -> assertEquals(DictationState.Idle, next(s, DictationEvent.Cancel), "da $s") }
    }

    @Test
    fun `transizioni non ammesse`() {
        assertNull(next(DictationState.Idle, DictationEvent.StopListening))
        assertNull(next(DictationState.Idle, DictationEvent.ResultReady))
        assertNull(next(DictationState.Transcribing, DictationEvent.StartListening(false)))
        assertNull(next(DictationState.Listening(false), DictationEvent.StartListening(false)))
        assertNull(next(DictationState.Listening(false), DictationEvent.ResultReady))
        assertNull(next(DictationState.Inserting, DictationEvent.CorrectionStarted))
        assertNull(next(DictationState.Idle, DictationEvent.ErrorShown))
        assertNull(next(DictationState.Correcting, DictationEvent.InsertionDone))
    }

    @Test
    fun `dopo un errore si torna a Inattivo o si riparte`() {
        val err = DictationState.Error(ErrorKind.NO_SPEECH)
        assertEquals(DictationState.Idle, next(err, DictationEvent.ErrorShown))
        assertEquals(DictationState.Listening(true), next(err, DictationEvent.StartListening(true)))
    }

    @Test
    fun `stati occupati`() {
        assertFalse(DictationStateMachine.isBusy(DictationState.Idle))
        assertFalse(DictationStateMachine.isBusy(DictationState.Error(ErrorKind.ENGINE)))
        assertTrue(DictationStateMachine.isBusy(DictationState.Listening(false)))
        assertTrue(DictationStateMachine.isBusy(DictationState.Correcting))
    }
}
