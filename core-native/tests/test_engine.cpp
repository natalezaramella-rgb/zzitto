// Test del motore reale con backend finti (Spec §12): nessun modello richiesto.
// Verificano errori di caricamento, hash (Spec §7), busy, annullamento (Spec §5.2),
// timeout con fallback (Spec §5.3), caricamento sequenziale (Spec §7), comandi e
// scorciatoie prima della correzione (Spec §5.9, §5.11), traduzione (§5.12),
// riscrittura (§5.10) e dizionario (§5.5). Aggiunto il 22/10/2026 alle 11:35.
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "zz_engine_core.h"
#include "zz_test.h"
#include "zz_wav.h"

using namespace std::chrono_literals;
using zz::AbortCheck;
using zz::AbortReason;

namespace {

// Stato condiviso tra i due finti, per controllare quanti modelli sono in RAM insieme.
struct Shared {
    std::atomic<int> in_ram{0};
    std::atomic<int> max_in_ram{0};
    void loaded() {
        int n = ++in_ram;
        int m = max_in_ram.load();
        while (n > m && !max_in_ram.compare_exchange_weak(m, n)) {
        }
    }
    void unloaded() { --in_ram; }
};

// Attende fino a `ms` ms controllando l'interruzione; restituisce il motivo.
AbortReason wait_abortable(const AbortCheck &abort, int ms) {
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while (std::chrono::steady_clock::now() < end) {
        AbortReason r = abort();
        if (r != AbortReason::None) return r;
        std::this_thread::sleep_for(1ms);
    }
    return AbortReason::None;
}

struct FakeStt : zz::SttBackend {
    std::shared_ptr<Shared> shared;
    std::string text = "ehm ciao come stai";
    int delay_ms = 0;
    std::atomic<bool> *gate = nullptr;  // se impostato, attende che diventi true
    zz_status load_status = ZZ_OK;
    bool is = false;
    std::vector<zz::SttRequest> requests;

    explicit FakeStt(std::shared_ptr<Shared> s) : shared(std::move(s)) {}
    ~FakeStt() override { unload(); }
    zz_status load(const std::string &, const zz::BackendSettings &) override {
        if (load_status != ZZ_OK) return load_status;
        if (!is) shared->loaded();
        is = true;
        return ZZ_OK;
    }
    void unload() override {
        if (is) shared->unloaded();
        is = false;
    }
    bool is_loaded() const override { return is; }
    zz_status transcribe(const float *, size_t, const zz::SttRequest &req, const AbortCheck &abort,
                         std::string *out) override {
        requests.push_back(req);
        if (gate)
            while (!gate->load()) {
                if (abort() == AbortReason::Cancelled) return ZZ_ERR_CANCELLED;
                std::this_thread::sleep_for(1ms);
            }
        if (wait_abortable(abort, delay_ms) == AbortReason::Cancelled) return ZZ_ERR_CANCELLED;
        *out = text;
        return ZZ_OK;
    }
};

struct FakeLlm : zz::LlmBackend {
    std::shared_ptr<Shared> shared;
    // Risposta per un prompt; predefinita: estrae il testo tra <testo> e </testo> e
    // lo "corregge" togliendo "ehm " e mettendo la maiuscola e il punto.
    std::function<zz_status(const zz::ChatPrompt &, const AbortCheck &, std::string *)> respond;
    zz_status load_status = ZZ_OK;
    bool is = false;
    std::vector<std::string> prompts;
    std::mutex m;

    explicit FakeLlm(std::shared_ptr<Shared> s) : shared(std::move(s)) {}
    ~FakeLlm() override { unload(); }
    zz_status load(const std::string &, const zz::BackendSettings &, const AbortCheck &) override {
        if (load_status != ZZ_OK) return load_status;
        if (!is) shared->loaded();
        is = true;
        return ZZ_OK;
    }
    void unload() override {
        if (is) shared->unloaded();
        is = false;
    }
    bool is_loaded() const override { return is; }
    zz_status generate(const zz::ChatPrompt &p, int, const AbortCheck &abort, std::string *out) override {
        {
            std::lock_guard<std::mutex> l(m);
            prompts.push_back(p.render());
        }
        if (respond) return respond(p, abort, out);
        *out = default_fix(user_text(p));
        return ZZ_OK;
    }
    static std::string user_text(const zz::ChatPrompt &p) {
        const std::string &u = p.parts[4].text;
        size_t a = u.find("<testo>\n"), b = u.rfind("\n</testo>");
        return u.substr(a + 8, b - a - 8);
    }
    static std::string default_fix(std::string t) {
        size_t pos;
        while ((pos = t.find("ehm ")) != std::string::npos) t.erase(pos, 4);
        if (!t.empty() && t[0] >= 'a' && t[0] <= 'z') t[0] = static_cast<char>(t[0] - 32);
        if (!t.empty() && t.back() != '.' && t.back() != '?') t += ".";
        return t;
    }
};

struct Rig {
    std::shared_ptr<Shared> shared = std::make_shared<Shared>();
    FakeStt *stt = nullptr;
    FakeLlm *llm = nullptr;
    zz_engine *e = nullptr;
    std::string wpath, lpath;

    explicit Rig(int sequential = 1, const char *wsha = nullptr, const char *lsha = nullptr, bool files = true) {
        static int counter = 0;
        ++counter;
        wpath = "zz_test_whisper_" + std::to_string(counter) + ".bin";
        lpath = "zz_test_llm_" + std::to_string(counter) + ".gguf";
        if (files) {
            write(wpath, "whisper finto");
            write(lpath, "llm finto");
        }
        zz_engine_config cfg;
        zz_engine_config_init(&cfg);
        cfg.whisper_model_path = wpath.c_str();
        cfg.llm_model_path = lpath.c_str();
        cfg.whisper_sha256 = wsha;
        cfg.llm_sha256 = lsha;
        cfg.sequential_loading = sequential;
        auto s = std::make_unique<FakeStt>(shared);
        auto l = std::make_unique<FakeLlm>(shared);
        stt = s.get();
        llm = l.get();
        zz_status st = zz::create_engine(&cfg, std::move(s), std::move(l), &e);
        ZZ_CHECK(st == ZZ_OK && e);
    }
    ~Rig() {
        zz_engine_destroy(e);
        std::remove(wpath.c_str());
        std::remove(lpath.c_str());
    }
    static void write(const std::string &p, const char *data) {
        std::FILE *f = std::fopen(p.c_str(), "wb");
        std::fwrite(data, 1, std::strlen(data), f);
        std::fclose(f);
    }
};

std::vector<float> speech(double seconds = 1.0) {
    std::vector<float> pcm(static_cast<size_t>(16000 * seconds));
    for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = (i % 40 < 20) ? 0.2f : -0.2f;
    return pcm;
}

void on_stage(zz_stage stage, void *ud) { static_cast<std::vector<int> *>(ud)->push_back(static_cast<int>(stage)); }

std::string sha_of(const char *text) {
    const char *p = "zz_test_sha.tmp";
    Rig::write(p, text);
    char hex[65] = {0};
    zz_sha256_file(p, hex);
    std::remove(p);
    return hex;
}

}  // namespace

int main() {
    zz_dictation_options opt;
    zz_dictation_options_init(&opt);
    const std::vector<float> pcm = speech();
    zz_result r{};

    // ---- Creazione: argomenti non validi -----------------------------------------
    {
        zz_engine_config cfg;
        zz_engine_config_init(&cfg);
        zz_engine *e = nullptr;
        cfg.whisper_sha256 = "abc";  // non 64 cifre esadecimali
        ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_ERR_INVALID_ARG && !e);
        zz_engine_config_init(&cfg);
        cfg.struct_size = 8;
        ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_ERR_INVALID_ARG && !e);
        zz_engine_config_init(&cfg);
        cfg.n_threads = -1;
        ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_ERR_INVALID_ARG && !e);
        // Motore reale senza percorsi: caricamento fallito.
        ZZ_CHECK(zz_engine_create(nullptr, &e) == ZZ_OK && e);
        ZZ_CHECK(zz_engine_load(e) == ZZ_ERR_MODEL_LOAD);
        ZZ_CHECK(zz_engine_is_loaded(e) == 0);
        zz_engine_destroy(e);
        // File inesistente con il backend reale: ZZ_ERR_MODEL_LOAD, nessun crash.
        zz_engine_config_init(&cfg);
        cfg.whisper_model_path = "inesistente-whisper.bin";
        cfg.llm_model_path = "inesistente-llm.gguf";
        ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_OK);
        ZZ_CHECK(zz_engine_load(e) == ZZ_ERR_MODEL_LOAD);
        zz_engine_destroy(e);
        // File non valido per whisper.cpp/llama.cpp, caricamento non sequenziale.
        Rig::write("zz_test_bad_model.bin", "non e' un modello");
        zz_engine_config_init(&cfg);
        cfg.whisper_model_path = "zz_test_bad_model.bin";
        cfg.llm_model_path = "zz_test_bad_model.bin";
        cfg.sequential_loading = 0;
        ZZ_CHECK(zz_engine_create(&cfg, &e) == ZZ_OK);
        ZZ_CHECK(zz_engine_load(e) == ZZ_ERR_MODEL_LOAD);
        ZZ_CHECK(zz_engine_is_loaded(e) == 0);
        zz_engine_destroy(e);
        std::remove("zz_test_bad_model.bin");
    }

    // ---- Integrità SHA-256 (Spec §7) -----------------------------------------------
    {
        const std::string good_w = sha_of("whisper finto"), good_l = sha_of("llm finto");
        const std::string bad(64, '0');
        {
            Rig rig(1, good_w.c_str(), bad.c_str());
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_ERR_MODEL_INTEGRITY);
            ZZ_CHECK(zz_engine_is_loaded(rig.e) == 0);
            ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
        }
        {
            Rig rig(1, bad.c_str(), good_l.c_str());
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_ERR_MODEL_INTEGRITY);
        }
        {
            // Maiuscole accettate; caricamento idempotente.
            std::string upper_w = good_w;
            for (auto &c : upper_w) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
            Rig rig(1, upper_w.c_str(), good_l.c_str());
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
            ZZ_CHECK(zz_engine_is_loaded(rig.e) == 1);
            // sequential_loading=1: solo verifica, nessun modello in RAM (Spec §7).
            ZZ_CHECK(rig.shared->in_ram.load() == 0);
            // Modello modificato dopo la verifica: rilevato al caricamento successivo.
            ZZ_CHECK(zz_engine_unload(rig.e) == ZZ_OK);
            Rig::write(rig.lpath, "llm manomesso");  // dimensione diversa: si ricalcola l'hash
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_ERR_MODEL_INTEGRITY);
        }
        {
            // File mancante: prevale ZZ_ERR_MODEL_LOAD.
            Rig rig(1, nullptr, nullptr, false);
            ZZ_CHECK(zz_engine_load(rig.e) == ZZ_ERR_MODEL_LOAD);
        }
    }

    // ---- Dettatura completa e stadi (Spec §5.2, §5.9) -------------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(zz_rewrite(rig.e, "x", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(zz_process_text(rig.e, "ehm ciao", &opt, &r) == ZZ_ERR_NOT_LOADED);
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);

        rig.stt->text = "ehm ciao a capo come stai invia";
        std::vector<int> stages;
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, on_stage, &stages, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.raw_text, "ehm ciao a capo come stai invia");
        ZZ_CHECK_EQ_STR(r.final_text, "Ciao.\nCome stai.");
        ZZ_CHECK(r.actions == ZZ_ACTION_SEND);
        ZZ_CHECK(r.used_fallback == 0 && r.from_shortcut == 0);
        ZZ_CHECK(r.ms_transcribe >= 0 && r.ms_correct >= 0);
        ZZ_CHECK((stages == std::vector<int>{ZZ_STAGE_TRANSCRIBING, ZZ_STAGE_CORRECTING, ZZ_STAGE_DONE}));
        ZZ_CHECK(rig.llm->prompts.size() == 2);  // un pezzo per riga: gli a capo non passano dal modello
        zz_result_free(&r);

        // Spec §5.2: audio corto, silenzio o trascrizione vuota => nessun parlato.
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), 1000, &opt, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);
        std::vector<float> silence(16000, 0.0f);
        ZZ_CHECK(zz_dictate(rig.e, silence.data(), silence.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);
        rig.stt->text = "   ";
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_NO_SPEECH);
        ZZ_CHECK(r.raw_text == nullptr);

        // Argomenti non validi
        ZZ_CHECK(zz_dictate(rig.e, nullptr, 10, &opt, nullptr, nullptr, &r) == ZZ_ERR_INVALID_ARG);
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, nullptr) == ZZ_ERR_INVALID_ARG);
        zz_dictation_options bad = opt;
        bad.mode = static_cast<zz_correction_mode>(7);
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &bad, nullptr, nullptr, &r) == ZZ_ERR_INVALID_ARG);
        bad = opt;
        bad.struct_size = 4;
        ZZ_CHECK(zz_process_text(rig.e, "ciao", &bad, &r) == ZZ_ERR_INVALID_ARG);
        ZZ_CHECK(zz_process_text(rig.e, nullptr, &opt, &r) == ZZ_ERR_INVALID_ARG);

        // Opzioni NULL = valori predefiniti
        rig.stt->text = "ciao";
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), nullptr, nullptr, nullptr, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.final_text, "Ciao.");
        zz_result_free(&r);
        ZZ_CHECK(rig.stt->requests.back().language == "it");

        // Comandi vocali disattivati: "a capo" resta testo.
        zz_dictation_options nocmd = opt;
        nocmd.voice_commands_enabled = 0;
        ZZ_CHECK(zz_process_text(rig.e, "vai a capo", &nocmd, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.final_text, "Vai a capo.");
        ZZ_CHECK(r.actions == 0);
        zz_result_free(&r);

        // Solo azioni: "annulla" e "cancella l'ultima frase" senza correzione.
        size_t before = rig.llm->prompts.size();
        ZZ_CHECK(zz_process_text(rig.e, "annulla", &opt, &r) == ZZ_OK);
        ZZ_CHECK(r.actions == ZZ_ACTION_UNDO_LAST_INSERT && r.final_text[0] == '\0');
        zz_result_free(&r);
        ZZ_CHECK(zz_process_text(rig.e, "cancella l'ultima frase", &opt, &r) == ZZ_OK);
        ZZ_CHECK(r.actions == ZZ_ACTION_DELETE_PREVIOUS_SENTENCE);
        zz_result_free(&r);
        ZZ_CHECK(rig.llm->prompts.size() == before);
    }

    // ---- Scorciatoie (Spec §5.11) e dizionario (Spec §5.5) ----------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        const char *keys[] = {"firma", nullptr};
        const char *vals[] = {"Natale Zaramella\nZaramella Consulting Srl", "x"};
        ZZ_CHECK(zz_engine_set_shortcuts(rig.e, keys, vals, 2) == ZZ_OK);
        ZZ_CHECK(zz_engine_set_shortcuts(rig.e, nullptr, vals, 1) == ZZ_ERR_INVALID_ARG);
        rig.stt->text = " Firma.";
        std::vector<int> stages;
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, on_stage, &stages, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.final_text, "Natale Zaramella\nZaramella Consulting Srl");
        ZZ_CHECK(r.from_shortcut == 1 && r.ms_correct == 0);
        ZZ_CHECK((stages == std::vector<int>{ZZ_STAGE_TRANSCRIBING, ZZ_STAGE_DONE}));
        ZZ_CHECK(rig.llm->prompts.empty() && !rig.llm->is_loaded());
        zz_result_free(&r);
        // "firma invia": scorciatoia + azione
        rig.stt->text = "firma invia";
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        ZZ_CHECK(r.from_shortcut == 1 && r.actions == ZZ_ACTION_SEND);
        zz_result_free(&r);

        const char *words[] = {"Zzitto", nullptr, "Zaramella", "zzitto"};
        ZZ_CHECK(zz_engine_set_dictionary(rig.e, words, 4) == ZZ_OK);
        ZZ_CHECK(zz_engine_set_dictionary(rig.e, nullptr, 2) == ZZ_ERR_INVALID_ARG);
        rig.stt->text = "ehm zitto funziona";
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        zz_result_free(&r);
        ZZ_CHECK_EQ_STR(rig.stt->requests.back().initial_prompt, "Zzitto, Zaramella.");
        ZZ_CHECK(rig.llm->prompts.back().find("Zzitto, Zaramella.") != std::string::npos);
        ZZ_CHECK(zz_engine_set_dictionary(rig.e, nullptr, 0) == ZZ_OK);
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        zz_result_free(&r);
        ZZ_CHECK(rig.stt->requests.back().initial_prompt.empty());
    }

    // ---- Modalità e traduzione (Spec §5.3, §5.12) -------------------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        zz_dictation_options o = opt;
        o.mode = ZZ_MODE_FORMAL;
        o.translate = ZZ_TRANSLATE_ES;
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &, std::string *out) {
            *out = "Traducción: Hola, ¿cómo estás?<|end|>";
            return ZZ_OK;
        };
        ZZ_CHECK(zz_process_text(rig.e, "ciao come stai", &o, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.final_text, "Hola, ¿cómo estás?");
        ZZ_CHECK(r.used_fallback == 0);
        zz_result_free(&r);
        const std::string &p = rig.llm->prompts.back();
        ZZ_CHECK(p.find("translate ONLY that final text into Spanish") != std::string::npos);
        ZZ_CHECK(p.find("formal, professional") != std::string::npos);
        ZZ_CHECK(p.rfind("<|system|>", 0) == 0);
        ZZ_CHECK(p.size() > 13 && p.compare(p.size() - 13, 13, "<|assistant|>") == 0);
    }

    // ---- Timeout e fallback al testo grezzo (Spec §5.3) --------------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &abort, std::string *) {
            AbortReason why = wait_abortable(abort, 10000);
            return why == AbortReason::Timeout ? ZZ_ERR_TIMEOUT : ZZ_ERR_CANCELLED;
        };
        zz_dictation_options o = opt;
        o.correction_timeout_ms = 80;
        rig.stt->text = "ehm ciao virgola come stai";
        const auto t0 = std::chrono::steady_clock::now();
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &o, nullptr, nullptr, &r) == ZZ_OK);
        const auto elapsed = std::chrono::steady_clock::now() - t0;
        ZZ_CHECK(elapsed < 3s);
        ZZ_CHECK(r.used_fallback == 1);
        ZZ_CHECK_EQ_STR(r.final_text, "ehm ciao, come stai");  // grezzo con i comandi applicati
        zz_result_free(&r);

        // Uscita vuota o sproporzionata => fallback (Spec §5.3: non aggiungere contenuti).
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &, std::string *out) {
            *out = std::string(500, 'x');
            return ZZ_OK;
        };
        ZZ_CHECK(zz_process_text(rig.e, "ciao", &opt, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        ZZ_CHECK_EQ_STR(r.final_text, "ciao");
        zz_result_free(&r);
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &, std::string *out) {
            *out = "<|end|>";
            return ZZ_OK;
        };
        ZZ_CHECK(zz_process_text(rig.e, "ciao", &opt, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        zz_result_free(&r);
        // Errore interno del modello => fallback.
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &, std::string *) { return ZZ_ERR_INTERNAL; };
        ZZ_CHECK(zz_process_text(rig.e, "ciao", &opt, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        zz_result_free(&r);
    }
    {
        // Modello di correzione non caricabile => fallback, la dettatura non si perde.
        Rig rig;
        rig.llm->load_status = ZZ_ERR_MODEL_LOAD;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        ZZ_CHECK_EQ_STR(r.final_text, "ehm ciao come stai");
        zz_result_free(&r);
        // Whisper non caricabile => errore esplicito.
        rig.stt->load_status = ZZ_ERR_OUT_OF_MEMORY;
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_ERR_OUT_OF_MEMORY);
    }

    // ---- Testi lunghi: più pezzi, stesso risultato concatenato ---------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        std::string text;
        for (int i = 0; i < 60; ++i) text += "questa è la frase numero " + std::to_string(i) + ". ";
        ZZ_CHECK(zz_process_text(rig.e, text.c_str(), &opt, &r) == ZZ_OK);
        ZZ_CHECK(rig.llm->prompts.size() > 1);
        ZZ_CHECK(r.used_fallback == 0);
        ZZ_CHECK(std::strlen(r.final_text) + 5 > text.size());
        zz_result_free(&r);
    }

    // ---- Caricamento sequenziale (Spec §7) ---------------------------------------------
    {
        Rig rig(1);
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        for (int i = 0; i < 2; ++i) {
            ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
            zz_result_free(&r);
        }
        ZZ_CHECK(rig.shared->max_in_ram.load() == 1);
        ZZ_CHECK(rig.e->whisper_load_count() == 2 && rig.e->llm_load_count() == 2);
        ZZ_CHECK(zz_engine_unload(rig.e) == ZZ_OK);
        ZZ_CHECK(rig.shared->in_ram.load() == 0 && zz_engine_is_loaded(rig.e) == 0);
    }
    {
        Rig rig(0);
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        ZZ_CHECK(rig.shared->in_ram.load() == 2);
        for (int i = 0; i < 2; ++i) {
            ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
            zz_result_free(&r);
        }
        ZZ_CHECK(rig.e->whisper_load_count() == 1 && rig.e->llm_load_count() == 1);
        ZZ_CHECK(zz_engine_unload(rig.e) == ZZ_OK);
        ZZ_CHECK(rig.shared->in_ram.load() == 0);
    }

    // ---- Busy e annullamento (contratto; Spec §5.2) -------------------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        std::atomic<bool> gate{false};
        rig.stt->gate = &gate;
        zz_status st1 = ZZ_ERR_INTERNAL;
        zz_result r1{};
        std::thread t([&]() { st1 = zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r1); });
        std::this_thread::sleep_for(50ms);
        zz_result r2{};
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r2) == ZZ_ERR_BUSY);
        ZZ_CHECK(zz_rewrite(rig.e, "x", pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r2) == ZZ_ERR_BUSY);
        ZZ_CHECK(zz_process_text(rig.e, "ciao", &opt, &r2) == ZZ_ERR_BUSY);
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_ERR_BUSY);
        ZZ_CHECK(zz_engine_unload(rig.e) == ZZ_ERR_BUSY);
        gate.store(true);
        t.join();
        ZZ_CHECK(st1 == ZZ_OK);
        zz_result_free(&r1);
        rig.stt->gate = nullptr;

        // Annullamento durante la trascrizione
        rig.stt->delay_ms = 5000;
        t = std::thread([&]() { st1 = zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r1); });
        std::this_thread::sleep_for(50ms);
        auto t0 = std::chrono::steady_clock::now();
        zz_cancel(rig.e);
        t.join();
        ZZ_CHECK(st1 == ZZ_ERR_CANCELLED);
        ZZ_CHECK(std::chrono::steady_clock::now() - t0 < 2s);
        ZZ_CHECK(r1.raw_text == nullptr && r1.final_text == nullptr);
        rig.stt->delay_ms = 0;

        // Annullamento durante la correzione
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &abort, std::string *) {
            AbortReason why = wait_abortable(abort, 10000);
            return why == AbortReason::Timeout ? ZZ_ERR_TIMEOUT : ZZ_ERR_CANCELLED;
        };
        std::vector<int> stages;
        t = std::thread([&]() { st1 = zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, on_stage, &stages, &r1); });
        std::this_thread::sleep_for(80ms);
        zz_cancel(rig.e);
        t.join();
        ZZ_CHECK(st1 == ZZ_ERR_CANCELLED);
        ZZ_CHECK((stages == std::vector<int>{ZZ_STAGE_TRANSCRIBING, ZZ_STAGE_CORRECTING}));
        rig.llm->respond = nullptr;

        // Un annullamento a motore fermo non influenza l'operazione successiva.
        zz_cancel(rig.e);
        ZZ_CHECK(zz_dictate(rig.e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &r) == ZZ_OK);
        zz_result_free(&r);
        zz_cancel(nullptr);
    }
    {
        // Distruzione durante un'operazione: annulla e attende.
        auto *rig = new Rig();
        ZZ_CHECK(zz_engine_load(rig->e) == ZZ_OK);
        rig->stt->delay_ms = 10000;
        zz_status st = ZZ_OK;
        zz_result rr{};
        zz_engine *e = rig->e;
        std::thread t([&]() { st = zz_dictate(e, pcm.data(), pcm.size(), &opt, nullptr, nullptr, &rr); });
        std::this_thread::sleep_for(50ms);
        delete rig;  // zz_engine_destroy
        t.join();
        ZZ_CHECK(st == ZZ_ERR_CANCELLED);
    }

    // ---- Riscrittura (Spec §5.10) -------------------------------------------------------
    {
        Rig rig;
        ZZ_CHECK(zz_engine_load(rig.e) == ZZ_OK);
        rig.stt->text = "rendilo più formale";
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &, std::string *out) {
            *out = "Testo riscritto: Buongiorno, come sta?";
            return ZZ_OK;
        };
        std::vector<int> stages;
        ZZ_CHECK(zz_rewrite(rig.e, "ciao come va", pcm.data(), pcm.size(), &opt, on_stage, &stages, &r) == ZZ_OK);
        ZZ_CHECK_EQ_STR(r.raw_text, "rendilo più formale");
        ZZ_CHECK_EQ_STR(r.final_text, "Buongiorno, come sta?");
        ZZ_CHECK(r.used_fallback == 0 && r.actions == 0);
        ZZ_CHECK((stages == std::vector<int>{ZZ_STAGE_TRANSCRIBING, ZZ_STAGE_CORRECTING, ZZ_STAGE_DONE}));
        zz_result_free(&r);
        const std::string &p = rig.llm->prompts.back();
        ZZ_CHECK(p.find("Istruzione: rendilo più formale\n<testo>\nciao come va\n</testo>") != std::string::npos);
        // Timeout: la selezione resta invariata.
        rig.llm->respond = [](const zz::ChatPrompt &, const AbortCheck &abort, std::string *) {
            return wait_abortable(abort, 10000) == AbortReason::Timeout ? ZZ_ERR_TIMEOUT : ZZ_ERR_CANCELLED;
        };
        zz_dictation_options o = opt;
        o.correction_timeout_ms = 50;
        ZZ_CHECK(zz_rewrite(rig.e, "ciao come va", pcm.data(), pcm.size(), &o, nullptr, nullptr, &r) == ZZ_OK);
        ZZ_CHECK(r.used_fallback == 1);
        ZZ_CHECK_EQ_STR(r.final_text, "ciao come va");
        zz_result_free(&r);
        ZZ_CHECK(zz_rewrite(rig.e, nullptr, pcm.data(), pcm.size(), &o, nullptr, nullptr, &r) == ZZ_ERR_INVALID_ARG);
    }

    // ---- Lettura WAV per zz_bench (Spec §11) ---------------------------------------------
    {
        const char *path = "zz_test.wav";
        std::FILE *f = std::fopen(path, "wb");
        auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
        auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
        const int16_t samples[4] = {0, 16384, -16384, 32767};
        std::fwrite("RIFF", 1, 4, f);
        w32(36 + 8);
        std::fwrite("WAVEfmt ", 1, 8, f);
        w32(16); w16(1); w16(2); w32(16000); w32(16000 * 4); w16(4); w16(16);
        std::fwrite("data", 1, 4, f);
        w32(8);
        std::fwrite(samples, 2, 4, f);  // 2 frame stereo
        std::fclose(f);
        std::vector<float> out;
        std::string err;
        ZZ_CHECK(zz::read_wav_16k_mono(path, &out, &err));
        ZZ_CHECK(out.size() == 2 && out[0] == 0.25f && out[1] > 0.24f && out[1] < 0.26f);
        std::remove(path);
        ZZ_CHECK(!zz::read_wav_16k_mono("inesistente.wav", &out, &err));
    }

    ZZ_TEST_MAIN_END();
}
