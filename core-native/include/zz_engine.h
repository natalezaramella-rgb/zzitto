/*
 * zz_engine.h — Contratto del motore Zzitto (core-native)
 *
 * Spec §4.3, §16.1 passo 1. Aggiunto il 22/10/2026 alle 11:05.
 *
 * API C stabile usata da Android (JNI) e Windows (P/Invoke).
 * Qualsiasi modifica a questo file passa SOLO dall'orchestratore (Spec §16.1)
 * e va registrata in docs/engine-contract.md e nel registro decisioni (Spec §14).
 *
 * Regole generali:
 *  - Tutte le stringhe sono UTF-8, terminate da '\0'.
 *  - Le stringhe restituite dal motore sono di proprietà del motore e si liberano
 *    solo con le funzioni *_free indicate.
 *  - L'audio è PCM float32 mono a 16 kHz, valori in [-1, 1] (Spec §4.1).
 *  - Le funzioni bloccanti (zz_dictate, zz_rewrite, zz_engine_load) vanno chiamate
 *    da un thread di background, mai dal main/UI thread (Spec §4.2).
 *  - Un'istanza zz_engine esegue una sola operazione alla volta; una seconda
 *    chiamata concorrente restituisce ZZ_ERR_BUSY. zz_cancel è thread-safe.
 *  - Nessun audio e nessun testo dettato viene mai scritto su disco o nei log
 *    (Spec §10).
 */
#ifndef ZZ_ENGINE_H
#define ZZ_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(ZZ_ENGINE_BUILD)
#    define ZZ_API __declspec(dllexport)
#  else
#    define ZZ_API __declspec(dllimport)
#  endif
#else
#  define ZZ_API __attribute__((visibility("default")))
#endif

#define ZZ_ENGINE_API_VERSION 1

/* ---- Codici di stato ------------------------------------------------- */
typedef enum zz_status {
    ZZ_OK = 0,
    ZZ_ERR_INVALID_ARG = 1,
    ZZ_ERR_NOT_LOADED = 2,      /* modelli non caricati: chiamare zz_engine_load */
    ZZ_ERR_MODEL_LOAD = 3,      /* file modello mancante o illeggibile */
    ZZ_ERR_MODEL_INTEGRITY = 4, /* hash SHA-256 non corrispondente (Spec §7) */
    ZZ_ERR_OUT_OF_MEMORY = 5,
    ZZ_ERR_CANCELLED = 6,       /* operazione annullata con zz_cancel */
    ZZ_ERR_TIMEOUT = 7,
    ZZ_ERR_BUSY = 8,            /* un'altra operazione è in corso */
    ZZ_ERR_NO_SPEECH = 9,       /* audio senza parlato */
    ZZ_ERR_INTERNAL = 99
} zz_status;

/* ---- Stati del ciclo di dettatura (Spec §5.2) ------------------------ */
/* Il motore notifica Transcribing e Correcting; Idle, Listening, Inserting
 * ed Error sono gestiti dalla piattaforma, che possiede la macchina a stati. */
typedef enum zz_stage {
    ZZ_STAGE_TRANSCRIBING = 1,
    ZZ_STAGE_CORRECTING = 2,
    ZZ_STAGE_DONE = 3
} zz_stage;

/* ---- Modalità di correzione (Spec §5.3) ------------------------------ */
typedef enum zz_correction_mode {
    ZZ_MODE_FAITHFUL = 0, /* "Fedele": pulizia minima */
    ZZ_MODE_CLEAN = 1,    /* "Pulito": predefinita */
    ZZ_MODE_FORMAL = 2    /* "Formale" */
} zz_correction_mode;

/* ---- Traduzione (Spec §5.12) ----------------------------------------- */
typedef enum zz_translate_target {
    ZZ_TRANSLATE_NONE = 0,
    ZZ_TRANSLATE_EN = 1,
    ZZ_TRANSLATE_ES = 2
} zz_translate_target;

/* ---- Azioni che la piattaforma deve eseguire dopo l'inserimento ------ */
/* Bitmask in zz_result.actions (Spec §5.9). */
typedef enum zz_action {
    ZZ_ACTION_NONE = 0,
    ZZ_ACTION_UNDO_LAST_INSERT = 1 << 0,        /* comando "annulla": annullare l'ultimo inserimento, nessun testo */
    ZZ_ACTION_DELETE_PREVIOUS_SENTENCE = 1 << 1, /* "cancella l'ultima frase" senza frasi nella dettatura corrente: cancellare l'ultima frase nel campo */
    ZZ_ACTION_SEND = 1 << 2                      /* comando "invia": premere invio/invio messaggio dopo l'inserimento, se il campo lo consente */
} zz_action;

/* ---- Configurazione -------------------------------------------------- */
typedef struct zz_engine_config {
    uint32_t struct_size;            /* = sizeof(zz_engine_config) */
    const char *whisper_model_path;  /* GGML/GGUF Whisper medium Q5 (Spec §7) */
    const char *llm_model_path;      /* GGUF Phi-4-mini Q4_K_M (Spec §7) */
    const char *whisper_sha256;      /* hex minuscolo, NULL = nessuna verifica (solo test) */
    const char *llm_sha256;          /* hex minuscolo, NULL = nessuna verifica (solo test) */
    int32_t n_threads;               /* 0 = automatico */
    int32_t use_gpu;                 /* 1 = usa GPU/NPU se disponibile */
    int32_t sequential_loading;      /* 1 = un solo modello in RAM alla volta; predefinito 0 dal 22/10/2026 (Spec §7, §11) */
} zz_engine_config;

typedef struct zz_dictation_options {
    uint32_t struct_size;            /* = sizeof(zz_dictation_options) */
    const char *language;            /* codice ISO lingua parlata, es. "it" */
    zz_correction_mode mode;
    zz_translate_target translate;
    int32_t voice_commands_enabled;  /* 1 = riconosci i comandi vocali (Spec §5.9) */
    int32_t correction_timeout_ms;   /* oltre questo tempo si usa il testo grezzo (Spec §5.3); 0 = 10000 */
} zz_dictation_options;

/* ---- Risultato ------------------------------------------------------- */
typedef struct zz_result {
    char *raw_text;          /* trascrizione Whisper senza correzione */
    char *final_text;        /* testo da inserire (può essere vuoto se solo azioni) */
    uint32_t actions;        /* bitmask di zz_action */
    int32_t used_fallback;   /* 1 = correzione fallita o in timeout: final_text è il testo grezzo (Spec §5.3) */
    int32_t from_shortcut;   /* 1 = final_text proviene da una scorciatoia vocale (Spec §5.11) */
    int64_t ms_transcribe;   /* durata trascrizione */
    int64_t ms_correct;      /* durata correzione/traduzione */
} zz_result;

typedef struct zz_engine zz_engine;

/* Callback di avanzamento, chiamata dal thread che esegue l'operazione. */
typedef void (*zz_stage_callback)(zz_stage stage, void *user_data);

/* ---- Ciclo di vita --------------------------------------------------- */
ZZ_API uint32_t zz_api_version(void);
ZZ_API const char *zz_version_string(void);         /* versione motore + versioni modelli attesi */
ZZ_API const char *zz_status_string(zz_status status);

ZZ_API void zz_engine_config_init(zz_engine_config *config);
ZZ_API void zz_dictation_options_init(zz_dictation_options *options);

ZZ_API zz_status zz_engine_create(const zz_engine_config *config, zz_engine **out_engine);
ZZ_API void zz_engine_destroy(zz_engine *engine);

/* Verifica gli hash e carica i modelli. Con sequential_loading=1 verifica
 * soltanto e carica ogni modello quando serve. Idempotente. */
ZZ_API zz_status zz_engine_load(zz_engine *engine);
/* Scarica i modelli dalla RAM (Spec §4.2, inattività). */
ZZ_API zz_status zz_engine_unload(zz_engine *engine);
ZZ_API int32_t zz_engine_is_loaded(const zz_engine *engine);

/* ---- Personalizzazioni ----------------------------------------------- */
/* Dizionario personale (Spec §5.5). Sostituisce l'elenco precedente. */
ZZ_API zz_status zz_engine_set_dictionary(zz_engine *engine, const char *const *words, size_t count);
/* Scorciatoie vocali parola chiave -> testo (Spec §5.11). Sostituisce l'elenco precedente. */
ZZ_API zz_status zz_engine_set_shortcuts(zz_engine *engine, const char *const *keywords,
                                         const char *const *expansions, size_t count);

/* ---- Operazioni ------------------------------------------------------ */
/* Dettatura: trascrive, applica comandi/scorciatoie, corregge o traduce. */
ZZ_API zz_status zz_dictate(zz_engine *engine, const float *pcm_16k_mono, size_t n_samples,
                            const zz_dictation_options *options,
                            zz_stage_callback on_stage, void *user_data,
                            zz_result *out_result);

/* Riscrittura a voce di un testo selezionato (Spec §5.10): l'audio contiene
 * l'istruzione (es. "rendilo più formale"). final_text sostituisce la selezione. */
ZZ_API zz_status zz_rewrite(zz_engine *engine, const char *selected_text,
                            const float *pcm_16k_mono, size_t n_samples,
                            const zz_dictation_options *options,
                            zz_stage_callback on_stage, void *user_data,
                            zz_result *out_result);

/* Variante testuale per test e per istruzioni già trascritte. */
ZZ_API zz_status zz_process_text(zz_engine *engine, const char *raw_text,
                                 const zz_dictation_options *options, zz_result *out_result);

/* Annulla l'operazione in corso (thread-safe). */
ZZ_API void zz_cancel(zz_engine *engine);

ZZ_API void zz_result_free(zz_result *result);

/* ---- Rilevamento voce (VAD) per lo stop automatico (Spec §5.2) ------- */
typedef struct zz_vad zz_vad;
/* silence_ms: silenzio dopo il parlato che fa scattare lo stop (predefinito 2000). */
ZZ_API zz_vad *zz_vad_create(int32_t silence_ms);
ZZ_API void zz_vad_destroy(zz_vad *vad);
ZZ_API void zz_vad_reset(zz_vad *vad);
/* Elabora un blocco audio. Restituisce 1 se il parlato è terminato
 * (c'è stato parlato e poi silence_ms di silenzio), altrimenti 0. */
ZZ_API int32_t zz_vad_process(zz_vad *vad, const float *pcm_16k_mono, size_t n_samples);
ZZ_API int32_t zz_vad_has_speech(const zz_vad *vad);

/* ---- Integrità modelli (Spec §7) ------------------------------------- */
/* Calcola lo SHA-256 di un file; out_hex deve avere almeno 65 byte. */
ZZ_API zz_status zz_sha256_file(const char *path, char *out_hex);

#ifdef __cplusplus
}
#endif

#endif /* ZZ_ENGINE_H */
