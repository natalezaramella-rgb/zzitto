# Contratto del motore Zzitto

*Aggiunto il 22/10/2026 alle 11:15. Riferimenti: Spec §4.3, §5.2–§5.12, §7, §16.1.*

Il contratto è l'API C in `core-native/include/zz_engine.h` (versione `ZZ_ENGINE_API_VERSION = 1`).
È l'unico punto di contatto tra il motore e le app. Lo può cambiare solo l'orchestratore, che aggiorna
questo documento e il registro decisioni.

## Divisione delle responsabilità

| Cosa | Dove |
|---|---|
| Macchina a stati della dettatura (Inattivo → Ascolto → Trascrizione → Correzione → Inserimento, Errore) | Piattaforma (Android/Windows) |
| Cattura microfono a 16 kHz mono float32 | Piattaforma |
| Stop automatico dopo il silenzio | Piattaforma, usando `zz_vad_*` del motore |
| Trascrizione, comandi vocali, scorciatoie, correzione, traduzione, riscrittura | Motore (`zz_dictate`, `zz_rewrite`) |
| Inserimento del testo e azioni (`ZZ_ACTION_*`) | Piattaforma |
| Verifica SHA-256 dei modelli | Motore (`zz_engine_load`, `zz_sha256_file`) |
| Scaricamento dei modelli per inattività | Piattaforma decide quando, chiama `zz_engine_unload` |

## Flusso tipico

1. All'avvio: `zz_engine_config_init`, imposta i percorsi dei modelli e gli hash, `zz_engine_create`.
2. Alla prima dettatura, su un thread di background: `zz_engine_load`.
3. Durante l'ascolto: passa i blocchi audio a `zz_vad_process`; quando restituisce 1, ferma l'ascolto.
4. `zz_dictate` con l'audio raccolto. La callback riceve `ZZ_STAGE_TRANSCRIBING`, `ZZ_STAGE_CORRECTING`, `ZZ_STAGE_DONE`.
5. Inserisci `final_text`, poi esegui le azioni in `actions`. Se `used_fallback = 1`, avvisa l'utente che il testo non è stato corretto.
6. `zz_result_free`.
7. Dopo l'inattività configurata: `zz_engine_unload`.

## API 2: dettatura in streaming (Spec §11)

*Aggiunto il 22/10/2026 alle 16:12 (agente A). Contratto: `ZZ_ENGINE_API_VERSION = 2`, funzioni
`zz_stream_begin/feed/finish/abort` (introdotte dall'orchestratore alle 16:05).*

Riduce la latenza percepita: il motore trascrive l'audio a pezzi mentre l'utente parla, quindi dopo lo
stop restano solo l'ultimo pezzo e la correzione. Il risultato è equivalente a `zz_dictate` sullo stesso
audio (stessi comandi, scorciatoie, correzione, `used_fallback`, azioni e stadi).

Flusso tipico sulla piattaforma:

1. All'inizio dell'ascolto, su un thread di background: `zz_stream_begin(engine, &opt, &stream)`
   (bloccante se deve caricare i modelli; restituisce `ZZ_ERR_NOT_LOADED` senza `zz_engine_load`,
   `ZZ_ERR_BUSY` se un'altra operazione è in corso). Le opzioni, compresa la stringa `language`, sono
   copiate.
2. Dal thread di cattura audio, per ogni blocco (anche da 10–20 ms): `zz_stream_feed(stream, pcm, n)`
   (non bloccante) e, come prima, `zz_vad_process` per lo stop automatico.
3. Allo stop, su un thread di background: `zz_stream_finish(stream, on_stage, ud, &result)`. Callback:
   `TRANSCRIBING` (subito), `CORRECTING`, `DONE`. `ms_transcribe` = trascrizione dopo lo stop.
   Il flusso è sempre liberato, anche in caso di errore: non va più usato.
4. Se l'utente annulla: `zz_stream_abort(stream)` (libera tutto, nessun risultato), oppure `zz_cancel`
   seguito da `zz_stream_finish` → `ZZ_ERR_CANCELLED`.

Regole:

- Per tutta la vita del flusso il motore è occupato: `zz_dictate`, `zz_rewrite`, `zz_process_text`,
  `zz_engine_load`/`unload` e un secondo `zz_stream_begin` restituiscono `ZZ_ERR_BUSY`.
- `zz_stream_feed` restituisce `ZZ_ERR_CANCELLED` dopo `zz_cancel` e l'errore di un pezzo fallito
  (es. `ZZ_ERR_OUT_OF_MEMORY`): la piattaforma può fermare l'ascolto, ma deve comunque chiudere il flusso
  con `finish` (che restituisce lo stesso errore) o `abort`.
- Non chiamare `zz_stream_feed` in concorrenza con `finish`/`abort` dello stesso flusso, né
  `zz_engine_destroy` con un flusso aperto.
- Audio < 0,1 s, solo silenzio o trascrizione vuota → `ZZ_ERR_NO_SPEECH`, come `zz_dictate`.
- Spec §10: l'audio resta solo in RAM e viene scartato man mano che è trascritto.
- Il motore finto (`-DZZ_ENGINE_MOCK=ON`) implementa la stessa API: `finish` restituisce il testo di prova
  dopo 200 ms di "trascrizione" (invece di 1 s) più 1,5 s di correzione; con `ZZ_MOCK_FAST=1` niente
  ritardi.

Dettagli del taglio nelle pause e del prompt di continuità: `core-native/README.md`, sezione
"Dettatura in streaming".

## Comandi vocali gestiti dal motore (Spec §5.9)

| Detto | Risultato |
|---|---|
| "a capo" | `\n` |
| "nuovo paragrafo" | `\n\n` |
| "punto" (a fine frase o prima di un altro comando) | `.` |
| "virgola" | `,` |
| "punto e virgola", "due punti", "punto interrogativo", "punto esclamativo" | `;` `:` `?` `!` |
| "cancella l'ultima frase" | cancella nella dettatura corrente; se non ce n'è, `ZZ_ACTION_DELETE_PREVIOUS_SENTENCE` |
| "annulla" (da solo) | `ZZ_ACTION_UNDO_LAST_INSERT`, nessun testo |
| "invia" (alla fine) | `ZZ_ACTION_SEND` dopo l'inserimento |

## Motore finto

`-DZZ_ENGINE_MOCK=ON` compila `core-native/mock/zz_engine_mock.cpp`: stessa API, nessun modello,
testo di prova fisso, tempi realistici (1 s trascrizione, 1,5 s correzione). Con `ZZ_MOCK_FAST=1` i
ritardi sono azzerati. Le app Android e Windows si sviluppano contro il mock e passano al motore reale
nell'integrazione finale (Spec §16.1).

## Binding

- Android: libreria `libzz_engine.so` compilata da Gradle con `externalNativeBuild` (CMake) che punta a `core-native/`; wrapper JNI nel modulo `:engine`.
- Windows: `zz_engine.dll` compilata con CMake (x64 e ARM64); P/Invoke nel progetto dell'agente C.
