// Motore finto (mock) che rispetta il contratto zz_engine.h.
// Spec §16.1 passo 2. Aggiunto il 22/10/2026 alle 11:05.
//
// Serve agli agenti Android e Windows per sviluppare senza i modelli reali.
// Restituisce testi di prova con tempi realistici (Spec §11: < 5 s).
// Con la variabile d'ambiente ZZ_MOCK_FAST=1 i ritardi sono azzerati (test).

#include "zz_engine.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "../src/zz_common.h"

struct zz_engine {
    zz_engine_config config{};
    std::string whisper_path;
    std::string llm_path;
    std::atomic<bool> loaded{false};
    std::atomic<bool> busy{false};
    std::atomic<bool> cancel{false};
    std::mutex mutex;
    std::vector<std::string> dictionary;
    std::vector<std::pair<std::string, std::string>> shortcuts;
};

namespace {

char *dup_string(const std::string &s) {
    char *out = static_cast<char *>(std::malloc(s.size() + 1));
    if (out) std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

bool fast_mode() {
    const char *v = std::getenv("ZZ_MOCK_FAST");
    return v && v[0] == '1';
}

// Attende ms millisecondi controllando l'annullamento.
bool wait_cancellable(zz_engine *e, int ms) {
    if (fast_mode()) return !e->cancel.load();
    for (int t = 0; t < ms; t += 20) {
        if (e->cancel.load()) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return !e->cancel.load();
}

struct BusyGuard {
    zz_engine *e;
    bool ok;
    explicit BusyGuard(zz_engine *engine) : e(engine) {
        bool expected = false;
        ok = e->busy.compare_exchange_strong(expected, true);
        if (ok) e->cancel.store(false);
    }
    ~BusyGuard() {
        if (ok) e->busy.store(false);
    }
};

std::string mock_translate(const std::string &text, zz_translate_target t) {
    if (t == ZZ_TRANSLATE_EN) return "[EN] " + text;
    if (t == ZZ_TRANSLATE_ES) return "[ES] " + text;
    return text;
}

zz_status finish(zz_engine *e, const std::string &raw, const zz_dictation_options *opt,
                 zz_stage_callback cb, void *ud, zz_result *out, bool rewrite,
                 const std::string &selected) {
    zz_dictation_options o;
    zz_dictation_options_init(&o);
    if (opt) o = *opt;

    zz::CommandResult cmd;
    if (!rewrite && o.voice_commands_enabled) {
        cmd = zz::apply_voice_commands(raw);
    } else {
        cmd.text = raw;
    }

    std::string final_text;
    int from_shortcut = 0;
    std::string expansion;
    if (!rewrite && zz::match_shortcut(e->shortcuts, cmd.text, &expansion)) {
        final_text = expansion;
        from_shortcut = 1;
    } else {
        if (cb) cb(ZZ_STAGE_CORRECTING, ud);
        if (!wait_cancellable(e, 1500)) return ZZ_ERR_CANCELLED;
        if (rewrite) {
            final_text = "[riscritto: " + zz::trim(raw) + "] " + selected;
        } else {
            final_text = mock_translate(zz::basic_cleanup(cmd.text, o.mode), o.translate);
        }
    }
    if (cb) cb(ZZ_STAGE_DONE, ud);

    out->raw_text = dup_string(raw);
    out->final_text = dup_string(final_text);
    out->actions = cmd.actions;
    out->used_fallback = 0;
    out->from_shortcut = from_shortcut;
    out->ms_transcribe = fast_mode() ? 0 : 1000;
    out->ms_correct = from_shortcut ? 0 : (fast_mode() ? 0 : 1500);
    return ZZ_OK;
}

const char *kMockTranscript = "ehm allora questo questo è un testo di prova della dettatura";

}  // namespace

extern "C" {

ZZ_API uint32_t zz_api_version(void) { return ZZ_ENGINE_API_VERSION; }

ZZ_API const char *zz_version_string(void) {
    return "zzitto-engine MOCK api=1 (whisper=none, llm=none)";
}

ZZ_API zz_status zz_engine_create(const zz_engine_config *config, zz_engine **out_engine) {
    if (!out_engine) return ZZ_ERR_INVALID_ARG;
    *out_engine = nullptr;
    auto *e = new (std::nothrow) zz_engine();
    if (!e) return ZZ_ERR_OUT_OF_MEMORY;
    zz_engine_config_init(&e->config);
    if (config) {
        e->config = *config;
        if (config->whisper_model_path) e->whisper_path = config->whisper_model_path;
        if (config->llm_model_path) e->llm_path = config->llm_model_path;
    }
    *out_engine = e;
    return ZZ_OK;
}

ZZ_API void zz_engine_destroy(zz_engine *engine) { delete engine; }

ZZ_API zz_status zz_engine_load(zz_engine *engine) {
    if (!engine) return ZZ_ERR_INVALID_ARG;
    // Il mock non legge i file: simula solo il tempo di caricamento.
    if (!engine->loaded.load()) {
        if (!fast_mode()) std::this_thread::sleep_for(std::chrono::milliseconds(300));
        engine->loaded.store(true);
    }
    return ZZ_OK;
}

ZZ_API zz_status zz_engine_unload(zz_engine *engine) {
    if (!engine) return ZZ_ERR_INVALID_ARG;
    engine->loaded.store(false);
    return ZZ_OK;
}

ZZ_API int32_t zz_engine_is_loaded(const zz_engine *engine) {
    return engine && engine->loaded.load() ? 1 : 0;
}

ZZ_API zz_status zz_engine_set_dictionary(zz_engine *engine, const char *const *words, size_t count) {
    if (!engine || (count && !words)) return ZZ_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->dictionary.clear();
    for (size_t i = 0; i < count; ++i)
        if (words[i]) engine->dictionary.emplace_back(words[i]);
    return ZZ_OK;
}

ZZ_API zz_status zz_engine_set_shortcuts(zz_engine *engine, const char *const *keywords,
                                         const char *const *expansions, size_t count) {
    if (!engine || (count && (!keywords || !expansions))) return ZZ_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->shortcuts.clear();
    for (size_t i = 0; i < count; ++i)
        if (keywords[i] && expansions[i]) engine->shortcuts.emplace_back(keywords[i], expansions[i]);
    return ZZ_OK;
}

ZZ_API zz_status zz_dictate(zz_engine *engine, const float *pcm, size_t n_samples,
                            const zz_dictation_options *options, zz_stage_callback on_stage,
                            void *user_data, zz_result *out) {
    if (!engine || !out || (n_samples && !pcm)) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    BusyGuard guard(engine);
    if (!guard.ok) return ZZ_ERR_BUSY;
    if (!engine->loaded.load()) return ZZ_ERR_NOT_LOADED;
    if (n_samples < 1600) return ZZ_ERR_NO_SPEECH;  // < 0,1 s
    if (on_stage) on_stage(ZZ_STAGE_TRANSCRIBING, user_data);
    if (!wait_cancellable(engine, 1000)) return ZZ_ERR_CANCELLED;
    return finish(engine, kMockTranscript, options, on_stage, user_data, out, false, "");
}

ZZ_API zz_status zz_rewrite(zz_engine *engine, const char *selected_text, const float *pcm,
                            size_t n_samples, const zz_dictation_options *options,
                            zz_stage_callback on_stage, void *user_data, zz_result *out) {
    if (!engine || !out || !selected_text || (n_samples && !pcm)) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    BusyGuard guard(engine);
    if (!guard.ok) return ZZ_ERR_BUSY;
    if (!engine->loaded.load()) return ZZ_ERR_NOT_LOADED;
    if (n_samples < 1600) return ZZ_ERR_NO_SPEECH;
    if (on_stage) on_stage(ZZ_STAGE_TRANSCRIBING, user_data);
    if (!wait_cancellable(engine, 600)) return ZZ_ERR_CANCELLED;
    return finish(engine, "rendilo più formale", options, on_stage, user_data, out, true,
                  selected_text);
}

ZZ_API zz_status zz_process_text(zz_engine *engine, const char *raw_text,
                                 const zz_dictation_options *options, zz_result *out) {
    if (!engine || !raw_text || !out) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    BusyGuard guard(engine);
    if (!guard.ok) return ZZ_ERR_BUSY;
    return finish(engine, raw_text, options, nullptr, nullptr, out, false, "");
}

ZZ_API void zz_cancel(zz_engine *engine) {
    if (engine) engine->cancel.store(true);
}

}  // extern "C"
