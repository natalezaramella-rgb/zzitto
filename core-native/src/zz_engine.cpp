// API C del motore reale (contratto core-native/include/zz_engine.h).
// Spec §4.3, §16.1. Aggiunto il 22/10/2026 alle 11:30.
//
// Le funzioni comuni con il mock (zz_status_string, zz_*_init, zz_result_free, VAD,
// zz_sha256_file) sono in zz_common.cpp.

#include "zz_engine.h"

#include "zz_engine_core.h"

#ifndef ZZ_WHISPER_REV
#  define ZZ_WHISPER_REV "sconosciuta"
#endif
#ifndef ZZ_LLAMA_REV
#  define ZZ_LLAMA_REV "sconosciuta"
#endif
#ifndef ZZ_ENGINE_VERSION
#  define ZZ_ENGINE_VERSION "0.0.0"
#endif

extern "C" {

ZZ_API uint32_t zz_api_version(void) { return ZZ_ENGINE_API_VERSION; }

// Spec §13: la versione riporta anche i modelli attesi (nomi concordati tra gli agenti).
ZZ_API const char *zz_version_string(void) {
    return "zzitto-engine " ZZ_ENGINE_VERSION " api=1 (whisper.cpp " ZZ_WHISPER_REV ", llama.cpp " ZZ_LLAMA_REV
           "; modelli: whisper-medium-q5_0.bin, phi-4-mini-instruct-q4_k_m.gguf)";
}

ZZ_API zz_status zz_engine_create(const zz_engine_config *config, zz_engine **out_engine) {
    return zz::create_engine(config, nullptr, nullptr, out_engine);
}

ZZ_API void zz_engine_destroy(zz_engine *engine) { delete engine; }

ZZ_API zz_status zz_engine_load(zz_engine *engine) { return engine ? engine->load() : ZZ_ERR_INVALID_ARG; }

ZZ_API zz_status zz_engine_unload(zz_engine *engine) { return engine ? engine->unload() : ZZ_ERR_INVALID_ARG; }

ZZ_API int32_t zz_engine_is_loaded(const zz_engine *engine) { return engine && engine->is_loaded() ? 1 : 0; }

ZZ_API zz_status zz_engine_set_dictionary(zz_engine *engine, const char *const *words, size_t count) {
    return engine ? engine->set_dictionary(words, count) : ZZ_ERR_INVALID_ARG;
}

ZZ_API zz_status zz_engine_set_shortcuts(zz_engine *engine, const char *const *keywords,
                                         const char *const *expansions, size_t count) {
    return engine ? engine->set_shortcuts(keywords, expansions, count) : ZZ_ERR_INVALID_ARG;
}

ZZ_API zz_status zz_dictate(zz_engine *engine, const float *pcm, size_t n_samples, const zz_dictation_options *options,
                            zz_stage_callback on_stage, void *user_data, zz_result *out_result) {
    return engine ? engine->dictate(pcm, n_samples, options, on_stage, user_data, out_result) : ZZ_ERR_INVALID_ARG;
}

ZZ_API zz_status zz_rewrite(zz_engine *engine, const char *selected_text, const float *pcm, size_t n_samples,
                            const zz_dictation_options *options, zz_stage_callback on_stage, void *user_data,
                            zz_result *out_result) {
    return engine ? engine->rewrite(selected_text, pcm, n_samples, options, on_stage, user_data, out_result)
                  : ZZ_ERR_INVALID_ARG;
}

ZZ_API zz_status zz_process_text(zz_engine *engine, const char *raw_text, const zz_dictation_options *options,
                                 zz_result *out_result) {
    return engine ? engine->process_text(raw_text, options, out_result) : ZZ_ERR_INVALID_ARG;
}

ZZ_API void zz_cancel(zz_engine *engine) {
    if (engine) engine->cancel();
}

}  // extern "C"
