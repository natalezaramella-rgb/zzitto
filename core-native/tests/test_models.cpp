// Test con i modelli reali (Spec §5.3, §5.12, §7, §11, §12). Aggiunto il 22/10/2026 alle 11:36.
//
// Si attivano solo se sono impostate le variabili d'ambiente:
//   ZZ_TEST_WHISPER_MODEL  percorso di whisper-medium-q5_0.bin
//   ZZ_TEST_LLM_MODEL      percorso di phi-4-mini-instruct-q4_k_m.gguf
// facoltative: ZZ_TEST_WHISPER_SHA256, ZZ_TEST_LLM_SHA256, ZZ_TEST_AUDIO_WAV (WAV 16 kHz
// con parlato italiano). Senza modelli il test termina con il codice 77 (saltato in CTest).
// Il testo prodotto non viene stampato (Spec §10).
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "zz_engine.h"
#include "zz_test.h"
#include "zz_wav.h"

namespace {
const char *env(const char *name) {
    const char *v = std::getenv(name);
    return v && v[0] ? v : nullptr;
}
std::string lower(const char *s) {
    std::string r = s ? s : "";
    for (auto &c : r) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return r;
}
bool contains(const char *s, const char *sub) { return s && lower(s).find(sub) != std::string::npos; }
}  // namespace

int main() {
    const char *wm = env("ZZ_TEST_WHISPER_MODEL"), *lm = env("ZZ_TEST_LLM_MODEL");
    if (!wm || !lm) {
        std::printf("SALTATO: impostare ZZ_TEST_WHISPER_MODEL e ZZ_TEST_LLM_MODEL\n");
        return 77;
    }
    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    cfg.whisper_model_path = wm;
    cfg.llm_model_path = lm;
    cfg.whisper_sha256 = env("ZZ_TEST_WHISPER_SHA256");
    cfg.llm_sha256 = env("ZZ_TEST_LLM_SHA256");

    // Hash sbagliato sul modello vero => ZZ_ERR_MODEL_INTEGRITY (Spec §7).
    {
        zz_engine_config bad = cfg;
        const std::string zeros(64, '0');
        bad.llm_sha256 = zeros.c_str();
        zz_engine *e = nullptr;
        ZZ_CHECK(zz_engine_create(&bad, &e) == ZZ_OK);
        ZZ_CHECK(zz_engine_load(e) == ZZ_ERR_MODEL_INTEGRITY);
        zz_engine_destroy(e);
    }

    zz_engine *e = nullptr;
    ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_OK);
    ZZ_CHECK(zz_engine_load(e) == ZZ_OK);
    zz_dictation_options opt;
    zz_dictation_options_init(&opt);
    opt.correction_timeout_ms = 120000;  // CPU di sviluppo lente: qui conta la qualità, non il tempo
    zz_result r{};

    // Correzione (Spec §5.3): intercalari, autocorrezione, ripetizioni.
    const char *dirty = "ehm allora domani ci vediamo alle tre anzi no alle quattro in in ufficio";
    ZZ_CHECK(zz_process_text(e, dirty, &opt, &r) == ZZ_OK);
    ZZ_CHECK(r.used_fallback == 0);
    ZZ_CHECK(r.final_text && std::strlen(r.final_text) > 10);
    ZZ_CHECK(!contains(r.final_text, "ehm"));
    ZZ_CHECK(contains(r.final_text, "quattro"));
    ZZ_CHECK(!contains(r.final_text, "in in"));
    std::printf("correzione: %lld ms\n", static_cast<long long>(r.ms_correct));
    zz_result_free(&r);

    // Comandi vocali prima della correzione (Spec §5.9): l'a capo resta.
    ZZ_CHECK(zz_process_text(e, "ciao Marco a capo ehm ci vediamo domani", &opt, &r) == ZZ_OK);
    ZZ_CHECK(r.final_text && std::strchr(r.final_text, '\n') != nullptr);
    zz_result_free(&r);

    // Traduzione (Spec §5.12)
    opt.translate = ZZ_TRANSLATE_EN;
    ZZ_CHECK(zz_process_text(e, "domani alle quattro ci vediamo in ufficio", &opt, &r) == ZZ_OK);
    ZZ_CHECK(r.used_fallback == 0 && contains(r.final_text, "tomorrow"));
    zz_result_free(&r);
    opt.translate = ZZ_TRANSLATE_ES;
    ZZ_CHECK(zz_process_text(e, "domani alle quattro ci vediamo in ufficio", &opt, &r) == ZZ_OK);
    ZZ_CHECK(r.used_fallback == 0 && contains(r.final_text, "mañana"));
    zz_result_free(&r);
    opt.translate = ZZ_TRANSLATE_NONE;

    // Timeout minimo => fallback al testo grezzo (Spec §5.3).
    {
        zz_dictation_options o = opt;
        o.correction_timeout_ms = 1;
        ZZ_CHECK(zz_process_text(e, dirty, &o, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        ZZ_CHECK_EQ_STR(r.final_text, dirty);
        zz_result_free(&r);
    }

    // Annullamento durante la generazione (Spec §5.2).
    {
        std::string longtext;
        for (int i = 0; i < 20; ++i) longtext += "ehm questa è una frase da correggere numero " + std::to_string(i) + " ";
        zz_status st = ZZ_OK;
        zz_result rr{};
        std::thread t([&]() { st = zz_process_text(e, longtext.c_str(), &opt, &rr); });
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        zz_cancel(e);
        t.join();
        ZZ_CHECK(st == ZZ_ERR_CANCELLED || st == ZZ_OK);
        zz_result_free(&rr);
    }

    // Dettatura da file audio, se presente.
    if (const char *wav = env("ZZ_TEST_AUDIO_WAV")) {
        std::vector<float> pcm;
        std::string err;
        ZZ_CHECK(zz::read_wav_16k_mono(wav, &pcm, &err));
        ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        ZZ_CHECK(r.raw_text && r.raw_text[0] && r.final_text && r.final_text[0]);
        std::printf("dettatura: trascrizione %lld ms, correzione %lld ms\n", static_cast<long long>(r.ms_transcribe),
                    static_cast<long long>(r.ms_correct));
        zz_result_free(&r);
        ZZ_CHECK(zz_rewrite(e, "ciao come va", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        zz_result_free(&r);
    }

    ZZ_CHECK(zz_engine_unload(e) == ZZ_OK);
    zz_engine_destroy(e);
    ZZ_TEST_MAIN_END();
}
