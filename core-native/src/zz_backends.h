// Interfacce interne dei due motori AI (trascrizione e modello linguistico).
// Spec §4.3, §7, §12. Aggiunto il 22/10/2026 alle 11:40.
//
// zz_engine usa queste interfacce: in produzione le implementano whisper.cpp
// (zz_backend_whisper.cpp) e llama.cpp (zz_backend_llama.cpp); nei test (Spec §12)
// si iniettano versioni finte per provare timeout, annullamento, caricamento
// sequenziale e stati senza i modelli veri.
#pragma once

#include <cstddef>
#include <functional>
#include <memory>
#include <string>

#include "zz_engine.h"
#include "zz_prompts.h"

namespace zz {

enum class AbortReason { None, Cancelled, Timeout };
// Interrogata spesso durante le operazioni lunghe; deve essere economica e thread-safe.
using AbortCheck = std::function<AbortReason()>;

struct BackendSettings {
    int n_threads = 4;
    bool use_gpu = true;
};

struct SttRequest {
    std::string language;        // "it", "en", ... o "auto"
    std::string initial_prompt;  // dizionario personale (Spec §5.5)
};

class SttBackend {
public:
    virtual ~SttBackend() = default;
    // ZZ_OK, ZZ_ERR_MODEL_LOAD, ZZ_ERR_OUT_OF_MEMORY
    virtual zz_status load(const std::string &path, const BackendSettings &settings) = 0;
    virtual void unload() = 0;
    virtual bool is_loaded() const = 0;
    // Testo trascritto (segmenti uniti, allucinazioni note rimosse). ZZ_OK,
    // ZZ_ERR_CANCELLED, ZZ_ERR_INVALID_ARG (lingua sconosciuta), ZZ_ERR_INTERNAL.
    virtual zz_status transcribe(const float *pcm, size_t n_samples, const SttRequest &req,
                                 const AbortCheck &abort, std::string *out_text) = 0;
};

class LlmBackend {
public:
    virtual ~LlmBackend() = default;
    // abort: consente di annullare anche il caricamento (Spec §5.2: annullabile in ogni stato).
    virtual zz_status load(const std::string &path, const BackendSettings &settings, const AbortCheck &abort) = 0;
    virtual void unload() = 0;
    virtual bool is_loaded() const = 0;
    // Genera la risposta dell'assistente. ZZ_OK, ZZ_ERR_CANCELLED, ZZ_ERR_TIMEOUT, ZZ_ERR_INTERNAL.
    virtual zz_status generate(const ChatPrompt &prompt, int max_new_tokens, const AbortCheck &abort,
                               std::string *out_text) = 0;
    // Valuta in anticipo il prompt (prefisso in cache) senza generare. Spec §11, 22/10/2026 15:20.
    virtual void warmup(const ChatPrompt &prompt, const AbortCheck &abort) { (void)prompt; (void)abort; }
};

std::unique_ptr<SttBackend> make_whisper_backend();
std::unique_ptr<LlmBackend> make_llama_backend();

// Spec §10: i log di whisper.cpp, llama.cpp e ggml non vengono mai scritti
// (a meno di ZZ_ENGINE_DEBUG_LOGS in sviluppo). Idempotente.
void silence_backend_logs();

// Numero di thread automatico (config.n_threads = 0).
int default_thread_count();

}  // namespace zz
