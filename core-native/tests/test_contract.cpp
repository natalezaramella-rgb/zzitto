// Test del contratto zz_engine.h: valgono per il mock e per il motore reale.
// Spec §16.1. Aggiunto il 22/10/2026 alle 11:05.
// Aggiornato il 22/10/2026 alle 11:40 (agente A): con il motore reale (ZZ_TEST_REAL_ENGINE)
// si usano i modelli indicati da ZZ_TEST_WHISPER_MODEL e ZZ_TEST_LLM_MODEL; senza modelli
// si verificano gli errori attesi. I controlli sul mock sono invariati.
// Aggiornato il 22/10/2026 alle 16:12 (agente A): API 2, dettatura in streaming (Spec §11).
#include <atomic>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "zz_engine.h"
#include "zz_test.h"
#if defined(ZZ_TEST_REAL_ENGINE)
#include "zz_wav.h"
#endif

namespace {
[[maybe_unused]] const char *env(const char *name) {
    const char *v = std::getenv(name);
    return v && v[0] ? v : nullptr;
}

// Solo motore reale: con l'audio sintetico (senza ZZ_TEST_AUDIO_WAV) Whisper può
// correttamente non trovare parlato. Con il mock restituisce sempre false.
bool synthetic_no_speech(zz_status st) {
#if defined(ZZ_TEST_REAL_ENGINE)
    return st == ZZ_ERR_NO_SPEECH && !env("ZZ_TEST_AUDIO_WAV");
#else
    (void)st;
    return false;
#endif
}
}  // namespace

static void on_stage(zz_stage stage, void *ud) {
    static_cast<std::vector<int> *>(ud)->push_back(static_cast<int>(stage));
}

// API 2: alimenta il flusso a blocchi da 20 ms da un altro thread, come il thread di cattura audio.
static zz_status feed_20ms(zz_stream *s, const std::vector<float> &pcm) {
    zz_status last = ZZ_OK;
    std::thread t([&]() {
        for (size_t off = 0; off < pcm.size(); off += 320) {
            const size_t n = pcm.size() - off < 320 ? pcm.size() - off : 320;
            const zz_status st = zz_stream_feed(s, pcm.data() + off, n);
            if (st != ZZ_OK) last = st;
        }
    });
    t.join();
    return last;
}

int main() {
    ZZ_CHECK(zz_api_version() == ZZ_ENGINE_API_VERSION);
    ZZ_CHECK(zz_version_string() != nullptr);
    ZZ_CHECK(zz_engine_create(nullptr, nullptr) == ZZ_ERR_INVALID_ARG);

    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    ZZ_CHECK(cfg.struct_size == sizeof(cfg));
    ZZ_CHECK(cfg.sequential_loading == 0);  // predefinito dal 22/10/2026 (Spec §11)

    // Motore reale: modelli dalle variabili d'ambiente (Spec §12), se presenti.
    bool have_models = true;  // il mock non ha bisogno di modelli
    std::vector<float> pcm(16000 * 2, 0.1f);
#if defined(ZZ_TEST_REAL_ENGINE)
    const char *whisper_model = env("ZZ_TEST_WHISPER_MODEL");
    const char *llm_model = env("ZZ_TEST_LLM_MODEL");
    have_models = whisper_model && llm_model;
    if (have_models) {
        cfg.whisper_model_path = whisper_model;
        cfg.llm_model_path = llm_model;
        cfg.whisper_sha256 = env("ZZ_TEST_WHISPER_SHA256");
        cfg.llm_sha256 = env("ZZ_TEST_LLM_SHA256");
        std::string err;
        if (const char *wav = env("ZZ_TEST_AUDIO_WAV")) ZZ_CHECK(zz::read_wav_16k_mono(wav, &pcm, &err));
    }
#endif

    zz_engine *e = nullptr;
    ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_OK && e);

    zz_result r{};
    zz_dictation_options opt;
    zz_dictation_options_init(&opt);

    // Senza caricamento: errore esplicito
    ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
    zz_stream *stream = nullptr;
    ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_ERR_NOT_LOADED && stream == nullptr);
    ZZ_CHECK(zz_stream_begin(nullptr, &opt, &stream) == ZZ_ERR_INVALID_ARG);
    ZZ_CHECK(zz_stream_begin(e, &opt, nullptr) == ZZ_ERR_INVALID_ARG);
    ZZ_CHECK(zz_stream_feed(nullptr, pcm.data(), 1) == ZZ_ERR_INVALID_ARG);
    ZZ_CHECK(zz_stream_finish(nullptr, nullptr, nullptr, &r) == ZZ_ERR_INVALID_ARG);
    zz_stream_abort(nullptr);

    if (have_models) {
        ZZ_CHECK(zz_engine_load(e) == ZZ_OK);
        ZZ_CHECK(zz_engine_is_loaded(e) == 1);

        // Dettatura con callback degli stadi
        std::vector<int> stages;
        const zz_status st = zz_dictate(e, pcm.data(), pcm.size(), &opt, on_stage, &stages, &r);
        if (synthetic_no_speech(st)) {
            // Motore reale con audio sintetico: "nessun parlato" è una risposta corretta.
            ZZ_CHECK(r.raw_text == nullptr && r.final_text == nullptr);
        } else {
            ZZ_CHECK(st == ZZ_OK);
            ZZ_CHECK(r.raw_text && r.final_text);
            ZZ_CHECK(!stages.empty() && stages.front() == ZZ_STAGE_TRANSCRIBING && stages.back() == ZZ_STAGE_DONE);
            zz_result_free(&r);
            ZZ_CHECK(r.raw_text == nullptr && r.final_text == nullptr);
        }

        // Audio troppo corto
        ZZ_CHECK(zz_dictate(e, pcm.data(), 100, &opt, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);

        // ---- API 2: dettatura in streaming (Spec §11) ----
        std::string dictated;  // risultato di zz_dictate sullo stesso audio (mock: deve coincidere)
        if (zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK) {
            dictated = r.final_text;
            zz_result_free(&r);
        }
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK && stream);
        zz_stream *other = nullptr;
        ZZ_CHECK(zz_stream_begin(e, &opt, &other) == ZZ_ERR_BUSY && other == nullptr);
        ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_BUSY);
        ZZ_CHECK(feed_20ms(stream, pcm) == ZZ_OK);
        std::vector<int> sstages;
        const zz_status sst = zz_stream_finish(stream, on_stage, &sstages, &r);
        if (!synthetic_no_speech(sst)) {
            ZZ_CHECK(sst == ZZ_OK);
            ZZ_CHECK(r.raw_text && r.final_text && r.ms_transcribe >= 0);
            ZZ_CHECK(!sstages.empty() && sstages.front() == ZZ_STAGE_TRANSCRIBING && sstages.back() == ZZ_STAGE_DONE);
#if !defined(ZZ_TEST_REAL_ENGINE)
            ZZ_CHECK_EQ_STR(r.final_text, dictated);
#endif
            zz_result_free(&r);
        }
        // abort: nessun risultato, motore di nuovo libero.
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK);
        ZZ_CHECK(feed_20ms(stream, pcm) == ZZ_OK);
        zz_stream_abort(stream);
        // zz_cancel durante il flusso.
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK);
        ZZ_CHECK(feed_20ms(stream, pcm) == ZZ_OK);
        zz_cancel(e);
        ZZ_CHECK(zz_stream_feed(stream, pcm.data(), 320) == ZZ_ERR_CANCELLED);
        ZZ_CHECK(zz_stream_finish(stream, nullptr, nullptr, &r) == ZZ_ERR_CANCELLED);
        ZZ_CHECK(r.raw_text == nullptr && r.final_text == nullptr);
        // Audio troppo corto.
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK);
        ZZ_CHECK(zz_stream_feed(stream, pcm.data(), 100) == ZZ_OK);
        ZZ_CHECK(zz_stream_finish(stream, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);
        // finish libera sempre, anche con argomenti non validi.
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK);
        ZZ_CHECK(zz_stream_finish(stream, nullptr, nullptr, nullptr) == ZZ_ERR_INVALID_ARG);
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_OK);
        zz_stream_abort(stream);
    } else {
        // Motore reale senza modelli: caricamento fallito, nessuna dettatura possibile.
        ZZ_CHECK(zz_engine_load(e) == ZZ_ERR_MODEL_LOAD);
        ZZ_CHECK(zz_engine_is_loaded(e) == 0);
        ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(zz_dictate(e, pcm.data(), 100, &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(zz_stream_begin(e, &opt, &stream) == ZZ_ERR_NOT_LOADED && stream == nullptr);
        ZZ_CHECK(zz_process_text(e, "ehm ciao", &opt, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(r.raw_text == nullptr && r.final_text == nullptr);
    }

    // Scorciatoie: l'enunciato "firma" restituisce l'espansione senza correzione
    const char *keys[] = {"firma"};
    const char *vals[] = {"Natale Zaramella"};
    ZZ_CHECK(zz_engine_set_shortcuts(e, keys, vals, 1) == ZZ_OK);
    ZZ_CHECK(zz_process_text(e, "Firma.", &opt, &r) == ZZ_OK);
    ZZ_CHECK_EQ_STR(r.final_text, "Natale Zaramella");
    ZZ_CHECK(r.from_shortcut == 1);
    zz_result_free(&r);

    // Comando "annulla"
    ZZ_CHECK(zz_process_text(e, "annulla", &opt, &r) == ZZ_OK);
    ZZ_CHECK(r.actions & ZZ_ACTION_UNDO_LAST_INSERT);
    zz_result_free(&r);

    // Dizionario
    const char *words[] = {"Zzitto", "Zaramella"};
    ZZ_CHECK(zz_engine_set_dictionary(e, words, 2) == ZZ_OK);

    // Riscrittura (Spec §5.10)
    if (have_models) {
        const zz_status st = zz_rewrite(e, "ciao come va", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r);
        if (!synthetic_no_speech(st)) {
            ZZ_CHECK(st == ZZ_OK);
            ZZ_CHECK(r.final_text && r.final_text[0]);
            zz_result_free(&r);
        }
    } else {
        ZZ_CHECK(zz_rewrite(e, "ciao come va", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
    }

    ZZ_CHECK(zz_engine_unload(e) == ZZ_OK);
    ZZ_CHECK(zz_engine_is_loaded(e) == 0);
    zz_engine_destroy(e);

    ZZ_CHECK(zz_status_string(ZZ_ERR_BUSY) != nullptr);
    ZZ_TEST_MAIN_END();
}
