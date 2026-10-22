// Test del contratto zz_engine.h: valgono per il mock e per il motore reale.
// Spec §16.1. Aggiunto il 22/10/2026 alle 11:05.
#include <atomic>
#include <thread>
#include <vector>

#include "zz_engine.h"
#include "zz_test.h"

static void on_stage(zz_stage stage, void *ud) {
    static_cast<std::vector<int> *>(ud)->push_back(static_cast<int>(stage));
}

int main() {
    ZZ_CHECK(zz_api_version() == ZZ_ENGINE_API_VERSION);
    ZZ_CHECK(zz_version_string() != nullptr);
    ZZ_CHECK(zz_engine_create(nullptr, nullptr) == ZZ_ERR_INVALID_ARG);

    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    ZZ_CHECK(cfg.struct_size == sizeof(cfg));
    ZZ_CHECK(cfg.sequential_loading == 1);

    zz_engine *e = nullptr;
    ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_OK && e);

    std::vector<float> pcm(16000 * 2, 0.1f);
    zz_result r{};
    zz_dictation_options opt;
    zz_dictation_options_init(&opt);

    // Senza caricamento: errore esplicito
    ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);

    ZZ_CHECK(zz_engine_load(e) == ZZ_OK);
    ZZ_CHECK(zz_engine_is_loaded(e) == 1);

    // Dettatura con callback degli stadi
    std::vector<int> stages;
    ZZ_CHECK(zz_dictate(e, pcm.data(), pcm.size(), &opt, on_stage, &stages, &r) == ZZ_OK);
    ZZ_CHECK(r.raw_text && r.final_text);
    ZZ_CHECK(!stages.empty() && stages.front() == ZZ_STAGE_TRANSCRIBING && stages.back() == ZZ_STAGE_DONE);
    zz_result_free(&r);
    ZZ_CHECK(r.raw_text == nullptr && r.final_text == nullptr);

    // Audio troppo corto
    ZZ_CHECK(zz_dictate(e, pcm.data(), 100, &opt, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);

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
    ZZ_CHECK(zz_rewrite(e, "ciao come va", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
    ZZ_CHECK(r.final_text && r.final_text[0]);
    zz_result_free(&r);

    ZZ_CHECK(zz_engine_unload(e) == ZZ_OK);
    ZZ_CHECK(zz_engine_is_loaded(e) == 0);
    zz_engine_destroy(e);

    ZZ_CHECK(zz_status_string(ZZ_ERR_BUSY) != nullptr);
    ZZ_TEST_MAIN_END();
}
