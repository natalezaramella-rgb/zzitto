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
