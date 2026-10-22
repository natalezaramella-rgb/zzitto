// Ponte JNI tra Kotlin (com.zaramella.zzitto.engine.jni.ZzNative) e l'API C di core-native.
// Spec §4.3, §5.2–§5.12, §7, §10. Agente B. Aggiunto il 22/10/2026 alle 11:20.
//
// Regole:
//  - Le stringhe passano come byte UTF-8 (ByteArray) in entrambe le direzioni: niente
//    "modified UTF-8" di NewStringUTF, che corrompe emoji e caratteri fuori dal BMP.
//  - L'audio e i testi copiati in memoria nativa vengono azzerati subito dopo l'uso (Spec §10).
//  - Nessun testo dettato né audio finisce nei log (Spec §10).
//  - Le funzioni bloccanti vengono chiamate dal thread del motore lato Kotlin (Spec §4.2).

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "zz_engine.h"

namespace {

JavaVM *g_vm = nullptr;

// Indici dell'array di uscita dei numeri (vedi ZzNative.kt).
constexpr jsize kNumActions = 0;
constexpr jsize kNumUsedFallback = 1;
constexpr jsize kNumFromShortcut = 2;
constexpr jsize kNumMsTranscribe = 3;
constexpr jsize kNumMsCorrect = 4;
constexpr jsize kNumCount = 5;

// Indici dell'array di uscita dei testi.
constexpr jsize kTextRaw = 0;
constexpr jsize kTextFinal = 1;
constexpr jsize kTextCount = 2;

void secure_zero(void *p, size_t n) {
    volatile unsigned char *v = static_cast<volatile unsigned char *>(p);
    while (n--) *v++ = 0;
}

void wipe(std::string &s) {
    if (!s.empty()) secure_zero(&s[0], s.size());
    s.clear();
}

void wipe(std::vector<float> &v) {
    if (!v.empty()) secure_zero(v.data(), v.size() * sizeof(float));
    v.clear();
}

void wipe_c(char *s) {
    if (s) secure_zero(s, std::strlen(s));
}

// Copia un ByteArray UTF-8 in una std::string (senza terminatore nell'array Java).
std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    std::string out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    if (n <= 0) return out;
    out.resize(static_cast<size_t>(n));
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray c_to_bytes(JNIEnv *env, const char *s) {
    if (!s) return nullptr;
    const size_t n = std::strlen(s);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (arr && n > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(s));
    }
    return arr;
}

// Converte un Array<ByteArray> Kotlin in un elenco di stringhe.
bool byte_arrays_to_strings(JNIEnv *env, jobjectArray arr, std::vector<std::string> *out) {
    out->clear();
    if (!arr) return true;
    const jsize n = env->GetArrayLength(arr);
    out->reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(arr, i));
        if (env->ExceptionCheck()) return false;
        out->push_back(bytes_to_string(env, item));
        if (item) env->DeleteLocalRef(item);
    }
    return true;
}

void wipe_all(std::vector<std::string> *v) {
    for (auto &s : *v) wipe(s);
    v->clear();
}

// ---- Callback di avanzamento (Spec §5.2) ---------------------------------

struct StageContext {
    jobject listener = nullptr;  // riferimento globale
    jmethodID on_stage = nullptr;
};

void stage_callback(zz_stage stage, void *user_data) {
    auto *ctx = static_cast<StageContext *>(user_data);
    if (!ctx || !ctx->listener || !ctx->on_stage || !g_vm) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    const jint got = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (got == JNI_EDETACHED) {
        // Il contratto dice che la callback arriva dal thread dell'operazione; per prudenza
        // gestiamo anche un thread nativo non agganciato alla JVM.
#if defined(__ANDROID__)
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    } else if (got != JNI_OK) {
        return;
    }

    env->CallVoidMethod(ctx->listener, ctx->on_stage, static_cast<jint>(stage));
    if (env->ExceptionCheck()) {
        // Un'eccezione nel listener non deve interrompere il motore.
        env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
}

bool init_stage_context(JNIEnv *env, jobject listener, StageContext *ctx) {
    if (!listener) return true;
    jclass cls = env->GetObjectClass(listener);
    if (!cls) return false;
    ctx->on_stage = env->GetMethodID(cls, "onStage", "(I)V");
    env->DeleteLocalRef(cls);
    if (!ctx->on_stage || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    ctx->listener = env->NewGlobalRef(listener);
    return ctx->listener != nullptr;
}

void release_stage_context(JNIEnv *env, StageContext *ctx) {
    if (ctx->listener) env->DeleteGlobalRef(ctx->listener);
    ctx->listener = nullptr;
}

// ---- Opzioni e risultati -------------------------------------------------

struct OptionsHolder {
    std::string language;
    zz_dictation_options options{};
};

void fill_options(JNIEnv *env, jbyteArray language, jint mode, jint translate, jboolean voice_commands,
                  jint timeout_ms, OptionsHolder *holder) {
    zz_dictation_options_init(&holder->options);
    holder->language = bytes_to_string(env, language);
    if (!holder->language.empty()) holder->options.language = holder->language.c_str();
    holder->options.mode = static_cast<zz_correction_mode>(mode);
    holder->options.translate = static_cast<zz_translate_target>(translate);
    holder->options.voice_commands_enabled = voice_commands ? 1 : 0;
    holder->options.correction_timeout_ms = timeout_ms;
}

bool check_outputs(JNIEnv *env, jobjectArray out_texts, jlongArray out_numbers) {
    return out_texts && out_numbers && env->GetArrayLength(out_texts) >= kTextCount &&
           env->GetArrayLength(out_numbers) >= kNumCount;
}

void write_result(JNIEnv *env, const zz_result &res, jobjectArray out_texts, jlongArray out_numbers) {
    jbyteArray raw = c_to_bytes(env, res.raw_text);
    jbyteArray fin = c_to_bytes(env, res.final_text);
    env->SetObjectArrayElement(out_texts, kTextRaw, raw);
    env->SetObjectArrayElement(out_texts, kTextFinal, fin);
    if (raw) env->DeleteLocalRef(raw);
    if (fin) env->DeleteLocalRef(fin);

    jlong numbers[kNumCount];
    numbers[kNumActions] = static_cast<jlong>(res.actions);
    numbers[kNumUsedFallback] = static_cast<jlong>(res.used_fallback);
    numbers[kNumFromShortcut] = static_cast<jlong>(res.from_shortcut);
    numbers[kNumMsTranscribe] = static_cast<jlong>(res.ms_transcribe);
    numbers[kNumMsCorrect] = static_cast<jlong>(res.ms_correct);
    env->SetLongArrayRegion(out_numbers, 0, kNumCount, numbers);
}

// Libera il risultato azzerando prima i testi (Spec §10).
void free_result(zz_result *res) {
    wipe_c(res->raw_text);
    wipe_c(res->final_text);
    zz_result_free(res);
}

bool copy_pcm(JNIEnv *env, jfloatArray pcm, jint n_samples, std::vector<float> *out) {
    if (!pcm || n_samples < 0) return false;
    const jsize len = env->GetArrayLength(pcm);
    if (n_samples > len) return false;
    out->resize(static_cast<size_t>(n_samples));
    if (n_samples > 0) env->GetFloatArrayRegion(pcm, 0, n_samples, out->data());
    return !env->ExceptionCheck();
}

inline zz_engine *as_engine(jlong handle) { return reinterpret_cast<zz_engine *>(static_cast<intptr_t>(handle)); }
inline zz_vad *as_vad(jlong handle) { return reinterpret_cast<zz_vad *>(static_cast<intptr_t>(handle)); }
inline zz_stream *as_stream(jlong handle) { return reinterpret_cast<zz_stream *>(static_cast<intptr_t>(handle)); }

}  // namespace

#define ZZ_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_zaramella_zzitto_engine_jni_ZzNative_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---- Versioni e stati ----------------------------------------------------

ZZ_JNI(jint, apiVersion)(JNIEnv *, jclass) { return static_cast<jint>(zz_api_version()); }

ZZ_JNI(jbyteArray, versionString)(JNIEnv *env, jclass) { return c_to_bytes(env, zz_version_string()); }

ZZ_JNI(jbyteArray, statusString)(JNIEnv *env, jclass, jint status) {
    return c_to_bytes(env, zz_status_string(static_cast<zz_status>(status)));
}

// ---- Ciclo di vita (Spec §4.2, §7) ---------------------------------------

ZZ_JNI(jint, engineCreate)(JNIEnv *env, jclass, jbyteArray whisper_path, jbyteArray llm_path,
                           jbyteArray whisper_sha, jbyteArray llm_sha, jint n_threads, jboolean use_gpu,
                           jboolean sequential, jlongArray out_handle) {
    if (!whisper_path || !llm_path || !out_handle || env->GetArrayLength(out_handle) < 1) {
        return ZZ_ERR_INVALID_ARG;
    }
    const std::string whisper = bytes_to_string(env, whisper_path);
    const std::string llm = bytes_to_string(env, llm_path);
    const std::string wsha = bytes_to_string(env, whisper_sha);
    const std::string lsha = bytes_to_string(env, llm_sha);

    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    cfg.whisper_model_path = whisper.c_str();
    cfg.llm_model_path = llm.c_str();
    // Hash nullo = nessuna verifica (consentito dal contratto solo per i test, Spec §7).
    cfg.whisper_sha256 = whisper_sha ? wsha.c_str() : nullptr;
    cfg.llm_sha256 = llm_sha ? lsha.c_str() : nullptr;
    cfg.n_threads = n_threads;
    cfg.use_gpu = use_gpu ? 1 : 0;
    cfg.sequential_loading = sequential ? 1 : 0;

    zz_engine *engine = nullptr;
    const zz_status st = zz_engine_create(&cfg, &engine);
    jlong handle = st == ZZ_OK ? static_cast<jlong>(reinterpret_cast<intptr_t>(engine)) : 0;
    env->SetLongArrayRegion(out_handle, 0, 1, &handle);
    return st;
}

ZZ_JNI(void, engineDestroy)(JNIEnv *, jclass, jlong handle) {
    if (handle) zz_engine_destroy(as_engine(handle));
}

ZZ_JNI(jint, engineLoad)(JNIEnv *, jclass, jlong handle) {
    if (!handle) return ZZ_ERR_INVALID_ARG;
    return zz_engine_load(as_engine(handle));
}

ZZ_JNI(jint, engineUnload)(JNIEnv *, jclass, jlong handle) {
    if (!handle) return ZZ_ERR_INVALID_ARG;
    return zz_engine_unload(as_engine(handle));
}

ZZ_JNI(jboolean, engineIsLoaded)(JNIEnv *, jclass, jlong handle) {
    if (!handle) return JNI_FALSE;
    return zz_engine_is_loaded(as_engine(handle)) ? JNI_TRUE : JNI_FALSE;
}

// ---- Personalizzazioni (Spec §5.5, §5.11) --------------------------------

ZZ_JNI(jint, setDictionary)(JNIEnv *env, jclass, jlong handle, jobjectArray words) {
    if (!handle) return ZZ_ERR_INVALID_ARG;
    std::vector<std::string> items;
    if (!byte_arrays_to_strings(env, words, &items)) return ZZ_ERR_INVALID_ARG;
    std::vector<const char *> ptrs;
    ptrs.reserve(items.size());
    for (const auto &w : items) ptrs.push_back(w.c_str());
    const zz_status st = zz_engine_set_dictionary(as_engine(handle), ptrs.empty() ? nullptr : ptrs.data(), ptrs.size());
    wipe_all(&items);
    return st;
}

ZZ_JNI(jint, setShortcuts)(JNIEnv *env, jclass, jlong handle, jobjectArray keywords, jobjectArray expansions) {
    if (!handle) return ZZ_ERR_INVALID_ARG;
    std::vector<std::string> keys;
    std::vector<std::string> values;
    if (!byte_arrays_to_strings(env, keywords, &keys) || !byte_arrays_to_strings(env, expansions, &values) ||
        keys.size() != values.size()) {
        wipe_all(&keys);
        wipe_all(&values);
        return ZZ_ERR_INVALID_ARG;
    }
    std::vector<const char *> kp;
    std::vector<const char *> vp;
    kp.reserve(keys.size());
    vp.reserve(values.size());
    for (size_t i = 0; i < keys.size(); ++i) {
        kp.push_back(keys[i].c_str());
        vp.push_back(values[i].c_str());
    }
    const zz_status st = zz_engine_set_shortcuts(as_engine(handle), kp.empty() ? nullptr : kp.data(),
                                                 vp.empty() ? nullptr : vp.data(), kp.size());
    wipe_all(&keys);
    wipe_all(&values);
    return st;
}

// ---- Operazioni (Spec §5.2–§5.12) ----------------------------------------

ZZ_JNI(jint, dictate)(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint n_samples, jbyteArray language,
                      jint mode, jint translate, jboolean voice_commands, jint timeout_ms, jobject listener,
                      jobjectArray out_texts, jlongArray out_numbers) {
    if (!handle || !check_outputs(env, out_texts, out_numbers)) return ZZ_ERR_INVALID_ARG;
    std::vector<float> audio;
    if (!copy_pcm(env, pcm, n_samples, &audio)) return ZZ_ERR_INVALID_ARG;

    OptionsHolder opts;
    fill_options(env, language, mode, translate, voice_commands, timeout_ms, &opts);
    StageContext ctx;
    if (!init_stage_context(env, listener, &ctx)) {
        wipe(audio);
        return ZZ_ERR_INVALID_ARG;
    }

    zz_result res;
    std::memset(&res, 0, sizeof(res));
    const zz_status st = zz_dictate(as_engine(handle), audio.data(), audio.size(), &opts.options,
                                    ctx.listener ? stage_callback : nullptr, &ctx, &res);
    wipe(audio);  // Spec §10: l'audio viene distrutto subito dopo la trascrizione.
    release_stage_context(env, &ctx);
    if (st == ZZ_OK) write_result(env, res, out_texts, out_numbers);
    free_result(&res);
    return st;
}

ZZ_JNI(jint, rewrite)(JNIEnv *env, jclass, jlong handle, jbyteArray selected, jfloatArray pcm, jint n_samples,
                      jbyteArray language, jint mode, jint translate, jboolean voice_commands, jint timeout_ms,
                      jobject listener, jobjectArray out_texts, jlongArray out_numbers) {
    if (!handle || !selected || !check_outputs(env, out_texts, out_numbers)) return ZZ_ERR_INVALID_ARG;
    std::vector<float> audio;
    if (!copy_pcm(env, pcm, n_samples, &audio)) return ZZ_ERR_INVALID_ARG;
    std::string selected_text = bytes_to_string(env, selected);

    OptionsHolder opts;
    fill_options(env, language, mode, translate, voice_commands, timeout_ms, &opts);
    StageContext ctx;
    if (!init_stage_context(env, listener, &ctx)) {
        wipe(audio);
        wipe(selected_text);
        return ZZ_ERR_INVALID_ARG;
    }

    zz_result res;
    std::memset(&res, 0, sizeof(res));
    const zz_status st = zz_rewrite(as_engine(handle), selected_text.c_str(), audio.data(), audio.size(),
                                    &opts.options, ctx.listener ? stage_callback : nullptr, &ctx, &res);
    wipe(audio);
    wipe(selected_text);
    release_stage_context(env, &ctx);
    if (st == ZZ_OK) write_result(env, res, out_texts, out_numbers);
    free_result(&res);
    return st;
}

ZZ_JNI(jint, processText)(JNIEnv *env, jclass, jlong handle, jbyteArray raw, jbyteArray language, jint mode,
                          jint translate, jboolean voice_commands, jint timeout_ms, jobjectArray out_texts,
                          jlongArray out_numbers) {
    if (!handle || !raw || !check_outputs(env, out_texts, out_numbers)) return ZZ_ERR_INVALID_ARG;
    std::string text = bytes_to_string(env, raw);
    OptionsHolder opts;
    fill_options(env, language, mode, translate, voice_commands, timeout_ms, &opts);
    zz_result res;
    std::memset(&res, 0, sizeof(res));
    const zz_status st = zz_process_text(as_engine(handle), text.c_str(), &opts.options, &res);
    wipe(text);
    if (st == ZZ_OK) write_result(env, res, out_texts, out_numbers);
    free_result(&res);
    return st;
}

ZZ_JNI(void, cancel)(JNIEnv *, jclass, jlong handle) {
    if (handle) zz_cancel(as_engine(handle));
}

// ---- Dettatura in streaming (API 2, Spec §11) ----------------------------
// Aggiunto il 22/10/2026 alle 16:20. begin e finish sono bloccanti e girano sul thread del motore;
// feed è non bloccante e viene chiamata dal thread di registrazione (zz_engine.h).

ZZ_JNI(jint, streamBegin)(JNIEnv *env, jclass, jlong handle, jbyteArray language, jint mode, jint translate,
                          jboolean voice_commands, jint timeout_ms, jlongArray out_stream) {
    if (!handle || !out_stream || env->GetArrayLength(out_stream) < 1) return ZZ_ERR_INVALID_ARG;
    // Le opzioni vengono copiate dal motore (contratto): il contenitore locale può sparire subito.
    OptionsHolder opts;
    fill_options(env, language, mode, translate, voice_commands, timeout_ms, &opts);
    zz_stream *stream = nullptr;
    const zz_status st = zz_stream_begin(as_engine(handle), &opts.options, &stream);
    jlong out = st == ZZ_OK ? static_cast<jlong>(reinterpret_cast<intptr_t>(stream)) : 0;
    env->SetLongArrayRegion(out_stream, 0, 1, &out);
    return st;
}

// Spec §11: nessuna copia intermedia. L'array Java viene bloccato per il tempo della copia interna del
// motore (zz_stream_feed copia e ritorna subito, quindi la sezione critica è breve e senza chiamate JNI).
ZZ_JNI(jint, streamFeed)(JNIEnv *env, jclass, jlong stream, jfloatArray pcm, jint offset, jint length) {
    if (!stream || !pcm || offset < 0 || length < 0) return ZZ_ERR_INVALID_ARG;
    const jsize len = env->GetArrayLength(pcm);
    if (static_cast<jlong>(offset) + length > len) return ZZ_ERR_INVALID_ARG;
    if (length == 0) return ZZ_OK;
    jboolean is_copy = JNI_FALSE;
    auto *base = static_cast<float *>(env->GetPrimitiveArrayCritical(pcm, &is_copy));
    if (!base) return ZZ_ERR_OUT_OF_MEMORY;
    const zz_status st = zz_stream_feed(as_stream(stream), base + offset, static_cast<size_t>(length));
    // Spec §10: se la VM ha fatto una copia, la si azzera prima di liberarla (JNI_ABORT non riscrive nell'array Java).
    if (is_copy) secure_zero(base, static_cast<size_t>(len) * sizeof(float));
    env->ReleasePrimitiveArrayCritical(pcm, base, JNI_ABORT);
    return st;
}

ZZ_JNI(jint, streamFinish)(JNIEnv *env, jclass, jlong stream, jobject listener, jobjectArray out_texts,
                           jlongArray out_numbers) {
    if (!stream) return ZZ_ERR_INVALID_ARG;
    if (!check_outputs(env, out_texts, out_numbers)) {
        // Il contratto vuole il flusso sempre liberato: senza uscite valide lo si annulla.
        zz_stream_abort(as_stream(stream));
        return ZZ_ERR_INVALID_ARG;
    }
    StageContext ctx;
    if (!init_stage_context(env, listener, &ctx)) {
        zz_stream_abort(as_stream(stream));
        return ZZ_ERR_INVALID_ARG;
    }
    zz_result res;
    std::memset(&res, 0, sizeof(res));
    // zz_stream_finish libera sempre il flusso, anche in caso di errore (zz_engine.h).
    const zz_status st = zz_stream_finish(as_stream(stream), ctx.listener ? stage_callback : nullptr, &ctx, &res);
    release_stage_context(env, &ctx);
    if (st == ZZ_OK) write_result(env, res, out_texts, out_numbers);
    free_result(&res);
    return st;
}

ZZ_JNI(void, streamAbort)(JNIEnv *, jclass, jlong stream) {
    if (stream) zz_stream_abort(as_stream(stream));
}

// ---- VAD (Spec §5.2) -----------------------------------------------------

ZZ_JNI(jlong, vadCreate)(JNIEnv *, jclass, jint silence_ms) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(zz_vad_create(silence_ms)));
}

ZZ_JNI(void, vadDestroy)(JNIEnv *, jclass, jlong vad) {
    if (vad) zz_vad_destroy(as_vad(vad));
}

ZZ_JNI(void, vadReset)(JNIEnv *, jclass, jlong vad) {
    if (vad) zz_vad_reset(as_vad(vad));
}

ZZ_JNI(jint, vadProcess)(JNIEnv *env, jclass, jlong vad, jfloatArray pcm, jint offset, jint length) {
    if (!vad || !pcm || offset < 0 || length < 0) return 0;
    const jsize len = env->GetArrayLength(pcm);
    if (static_cast<jlong>(offset) + length > len) return 0;
    std::vector<float> chunk(static_cast<size_t>(length));
    if (length > 0) env->GetFloatArrayRegion(pcm, offset, length, chunk.data());
    const jint ended = zz_vad_process(as_vad(vad), chunk.data(), chunk.size());
    wipe(chunk);
    return ended;
}

ZZ_JNI(jboolean, vadHasSpeech)(JNIEnv *, jclass, jlong vad) {
    return vad && zz_vad_has_speech(as_vad(vad)) ? JNI_TRUE : JNI_FALSE;
}

// ---- Integrità dei modelli (Spec §7) -------------------------------------

ZZ_JNI(jint, sha256File)(JNIEnv *env, jclass, jbyteArray path, jobjectArray out_hex) {
    if (!path || !out_hex || env->GetArrayLength(out_hex) < 1) return ZZ_ERR_INVALID_ARG;
    const std::string p = bytes_to_string(env, path);
    char hex[65];
    std::memset(hex, 0, sizeof(hex));
    const zz_status st = zz_sha256_file(p.c_str(), hex);
    if (st == ZZ_OK) {
        jbyteArray arr = c_to_bytes(env, hex);
        env->SetObjectArrayElement(out_hex, 0, arr);
        if (arr) env->DeleteLocalRef(arr);
    }
    return st;
}
