// Motore reale: struttura interna di zz_engine (Spec §4.3, §5.2–§5.12, §7).
// Aggiunto il 22/10/2026 alle 11:40.
//
// Le funzioni C di zz_engine.h (zz_engine.cpp) delegano ai metodi di questa struttura.
// I test interni (Spec §12) la creano con motori finti tramite zz::create_engine.
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "zz_backends.h"
#include "zz_engine.h"

struct zz_stream;

struct zz_engine {
    zz_engine(const zz_engine_config &config, std::unique_ptr<zz::SttBackend> stt,
              std::unique_ptr<zz::LlmBackend> llm);
    ~zz_engine();

    zz_status load();
    zz_status unload();
    bool is_loaded() const { return loaded_.load(); }
    zz_status set_dictionary(const char *const *words, size_t count);
    zz_status set_shortcuts(const char *const *keywords, const char *const *expansions, size_t count);
    zz_status dictate(const float *pcm, size_t n, const zz_dictation_options *options, zz_stage_callback cb,
                      void *ud, zz_result *out);
    zz_status rewrite(const char *selected, const float *pcm, size_t n, const zz_dictation_options *options,
                      zz_stage_callback cb, void *ud, zz_result *out);
    zz_status process_text(const char *raw, const zz_dictation_options *options, zz_result *out);
    void cancel() { cancel_.store(true); }
    // Dettatura in streaming (API 2, Spec §11): implementata in zz_stream.cpp.
    zz_status stream_begin(const zz_dictation_options *options, zz_stream **out_stream);

    // Solo per i test (Spec §12): quante volte i modelli sono stati caricati.
    int whisper_load_count() const { return whisper_loads_; }
    int llm_load_count() const { return llm_loads_; }

private:
    friend struct ::zz_stream;  // il flusso usa trascrizione e finish_text del motore (zz_stream.cpp)
    struct Guard;  // occupa il motore per un'operazione (ZZ_ERR_BUSY se già occupato)
    struct Verified {
        std::string path, sha;
        uint64_t size = 0;
        int64_t mtime = 0;
    };

    zz_status verify_models();
    zz_status verify_one(const std::string &path, const std::string &expected_sha, Verified *cache);
    zz_status ensure_whisper();
    zz_status ensure_llm();
    // initial_prompt: dizionario (Spec §5.5) e, nel flusso, coda del testo già trascritto (Spec §11).
    zz_status transcribe(const float *pcm, size_t n, const zz_dictation_options &opt,
                         const std::string &initial_prompt, const zz::AbortCheck &abort, std::string *out);
    // Comandi vocali, scorciatoie e correzione/traduzione sul testo grezzo (Spec §5.3, §5.9, §5.11, §5.12).
    zz_status finish_text(const std::string &raw, const zz_dictation_options &opt, zz_stage_callback cb, void *ud,
                          zz_result *out);
    // Corregge `text` pezzo per pezzo. used_fallback=true se timeout, errore o uscita non plausibile.
    zz_status correct(const std::string &text, const zz_dictation_options &opt, const std::vector<std::string> &dict,
                      std::string *out, bool *used_fallback);
    zz::AbortCheck abort_check(std::chrono::steady_clock::time_point deadline) const;
    zz::AbortCheck cancel_check() const;
    void snapshot(std::vector<std::string> *dict, std::vector<std::pair<std::string, std::string>> *sc);

    zz_engine_config config_{};
    std::string whisper_path_, llm_path_, whisper_sha_, llm_sha_;
    zz::BackendSettings settings_;
    std::unique_ptr<zz::SttBackend> stt_;
    std::unique_ptr<zz::LlmBackend> llm_;

    std::atomic<bool> loaded_{false};
    std::atomic<bool> busy_{false};
    std::atomic<bool> cancel_{false};
    Verified whisper_verified_, llm_verified_;
    int whisper_loads_ = 0, llm_loads_ = 0;

    std::mutex data_mutex_;  // dizionario e scorciatoie
    std::vector<std::string> dictionary_;
    std::vector<std::pair<std::string, std::string>> shortcuts_;
};

namespace zz {

// Crea un motore con i backend indicati (nullptr = whisper.cpp / llama.cpp reali).
zz_status create_engine(const zz_engine_config *config, std::unique_ptr<SttBackend> stt,
                        std::unique_ptr<LlmBackend> llm, zz_engine **out_engine);

// Spec §5.3: timeout di correzione predefinito quando le opzioni indicano 0.
constexpr int kDefaultCorrectionTimeoutMs = 10000;
// Pezzi di testo per chiamata al modello (contesto 2048 token, Spec §7: RAM limitata).
constexpr size_t kMaxCharsPerPiece = 1200;
// Audio minimo: 0,1 s (come il mock) e soglia di energia per "nessun parlato".
constexpr size_t kMinSamples = 1600;

// Opzioni con i valori predefiniti del contratto (Spec §5.3); false se non valide.
bool resolve_options(const zz_dictation_options *in, zz_dictation_options *out);

// Spec §5.2: silenzio digitale o quasi (nessun blocco da 30 ms sopra la soglia).
bool is_silence(const float *pcm, size_t n);

}  // namespace zz
