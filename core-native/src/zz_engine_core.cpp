// Motore reale: orchestrazione di trascrizione, comandi, scorciatoie e correzione.
// Spec §4.2, §5.2–§5.12, §7, §10. Aggiunto il 22/10/2026 alle 11:45.

#include "zz_engine_core.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>

#include "zz_common.h"
#include "zz_prompts.h"
#include "zz_sha256.h"

namespace {

using Clock = std::chrono::steady_clock;

char *dup_string(const std::string &s) {
    char *out = static_cast<char *>(std::malloc(s.size() + 1));
    if (out) std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

int64_t ms_since(Clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count();
}

bool valid_sha_hex(const char *s) {
    if (!s) return true;  // NULL = nessuna verifica (solo test, vedi contratto)
    if (std::strlen(s) != 64) return false;
    for (const char *p = s; *p; ++p)
        if (!std::isxdigit(static_cast<unsigned char>(*p))) return false;
    return true;
}

std::string lower(const char *s) {
    std::string r = s ? s : "";
    for (auto &c : r) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return r;
}

bool has_alnum(const std::string &s) {
    for (unsigned char c : s)
        if (std::isalnum(c) || c >= 0x80) return true;
    return false;
}

// Spec §5.2: silenzio digitale o quasi (nessun blocco da 30 ms sopra la soglia).
bool is_silence(const float *pcm, size_t n) {
    const size_t frame = 480;
    for (size_t off = 0; off + frame <= n; off += frame) {
        double sum = 0;
        for (size_t i = 0; i < frame; ++i) sum += double(pcm[off + i]) * pcm[off + i];
        if (std::sqrt(sum / frame) > 0.002) return false;
    }
    return true;
}

// Opzioni con i valori predefiniti del contratto; false se non valide.
bool resolve_options(const zz_dictation_options *in, zz_dictation_options *out) {
    zz_dictation_options_init(out);
    if (!in) return true;
    if (in->struct_size < sizeof(zz_dictation_options)) return false;
    std::memcpy(out, in, sizeof(zz_dictation_options));
    out->struct_size = sizeof(zz_dictation_options);
    if (out->mode < ZZ_MODE_FAITHFUL || out->mode > ZZ_MODE_FORMAL) return false;
    if (out->translate < ZZ_TRANSLATE_NONE || out->translate > ZZ_TRANSLATE_ES) return false;
    if (out->correction_timeout_ms <= 0) out->correction_timeout_ms = zz::kDefaultCorrectionTimeoutMs;  // Spec §5.3
    return true;
}

}  // namespace

// ---- Occupazione del motore: una sola operazione alla volta (contratto) ----------

struct zz_engine::Guard {
    zz_engine *e;
    bool ok;
    explicit Guard(zz_engine *engine) : e(engine) {
        bool expected = false;
        ok = e->busy_.compare_exchange_strong(expected, true);
        if (ok) e->cancel_.store(false);
    }
    ~Guard() {
        if (ok) e->busy_.store(false);
    }
};

zz_engine::zz_engine(const zz_engine_config &config, std::unique_ptr<zz::SttBackend> stt,
                     std::unique_ptr<zz::LlmBackend> llm)
    : config_(config), stt_(std::move(stt)), llm_(std::move(llm)) {
    if (config.whisper_model_path) whisper_path_ = config.whisper_model_path;
    if (config.llm_model_path) llm_path_ = config.llm_model_path;
    whisper_sha_ = lower(config.whisper_sha256);
    llm_sha_ = lower(config.llm_sha256);
    // I puntatori della configurazione non restano validi dopo zz_engine_create.
    config_.whisper_model_path = config_.llm_model_path = config_.whisper_sha256 = config_.llm_sha256 = nullptr;
    settings_.n_threads = config.n_threads > 0 ? config.n_threads : zz::default_thread_count();
    settings_.use_gpu = config.use_gpu != 0;  // Spec §2.4, §11.1
}

zz_engine::~zz_engine() {
    // Annulla l'eventuale operazione in corso e attende che termini.
    cancel_.store(true);
    while (busy_.load()) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    if (stt_) stt_->unload();
    if (llm_) llm_->unload();
}

zz::AbortCheck zz_engine::cancel_check() const {
    return [this]() { return cancel_.load() ? zz::AbortReason::Cancelled : zz::AbortReason::None; };
}

zz::AbortCheck zz_engine::abort_check(Clock::time_point deadline) const {
    return [this, deadline]() {
        if (cancel_.load()) return zz::AbortReason::Cancelled;
        if (Clock::now() >= deadline) return zz::AbortReason::Timeout;  // Spec §5.3
        return zz::AbortReason::None;
    };
}

void zz_engine::snapshot(std::vector<std::string> *dict, std::vector<std::pair<std::string, std::string>> *sc) {
    std::lock_guard<std::mutex> lock(data_mutex_);
    if (dict) *dict = dictionary_;
    if (sc) *sc = shortcuts_;
}

// ---- Integrità e caricamento dei modelli (Spec §7) ---------------------------------

zz_status zz_engine::verify_one(const std::string &path, const std::string &expected_sha, Verified *cache) {
    uint64_t size = 0;
    int64_t mtime = 0;
    if (path.empty() || !zz::file_info_utf8(path.c_str(), &size, &mtime)) return ZZ_ERR_MODEL_LOAD;
    if (expected_sha.empty()) return ZZ_OK;  // NULL: nessuna verifica (solo test)
    // Già verificato e non modificato: evita di ricalcolare l'hash di file da GB a ogni
    // ricaricamento sequenziale (Spec §7, §11).
    if (cache->path == path && cache->size == size && cache->mtime == mtime && cache->sha == expected_sha)
        return ZZ_OK;
    char hex[65] = {0};
    zz_status st = static_cast<zz_status>(zz::sha256_file(path.c_str(), hex));
    if (st != ZZ_OK) return st;
    if (expected_sha != hex) {
        *cache = Verified();
        return ZZ_ERR_MODEL_INTEGRITY;
    }
    cache->path = path;
    cache->sha = expected_sha;
    cache->size = size;
    cache->mtime = mtime;
    return ZZ_OK;
}

zz_status zz_engine::verify_models() {
    // I due file si verificano in parallelo (Spec §11: prima dettatura più rapida).
    zz_status st_w = ZZ_OK, st_l = ZZ_OK;
    std::thread tw([&]() { st_w = verify_one(whisper_path_, whisper_sha_, &whisper_verified_); });
    st_l = verify_one(llm_path_, llm_sha_, &llm_verified_);
    tw.join();
    // Priorità: file mancante, poi integrità.
    if (st_w == ZZ_ERR_MODEL_LOAD || st_l == ZZ_ERR_MODEL_LOAD) return ZZ_ERR_MODEL_LOAD;
    if (st_w != ZZ_OK) return st_w;
    return st_l;
}

zz_status zz_engine::ensure_whisper() {
    if (stt_->is_loaded()) return ZZ_OK;
    zz_status st = verify_one(whisper_path_, whisper_sha_, &whisper_verified_);
    if (st != ZZ_OK) return st;
    // Spec §7: con il caricamento sequenziale un solo modello alla volta in RAM.
    if (config_.sequential_loading) llm_->unload();
    st = stt_->load(whisper_path_, settings_);
    if (st == ZZ_OK) ++whisper_loads_;
    return st;
}

zz_status zz_engine::ensure_llm() {
    if (llm_->is_loaded()) return ZZ_OK;
    zz_status st = verify_one(llm_path_, llm_sha_, &llm_verified_);
    if (st != ZZ_OK) return st;
    if (config_.sequential_loading) stt_->unload();  // Spec §7
    st = llm_->load(llm_path_, settings_, cancel_check());
    if (st == ZZ_OK) {
        ++llm_loads_;
        // Spec §11 (ottimizzazione del 22/10/2026 15:20): il prompt di sistema della modalità predefinita viene
        // valutato subito (di solito mentre l'utente parla), così alla prima correzione resta da
        // elaborare solo il testo dettato. Errori qui non bloccano: la correzione rifà il prefisso.
        std::vector<std::string> dict;
        {
            std::lock_guard<std::mutex> lock(data_mutex_);
            dict = dictionary_;
        }
        llm_->warmup(zz::correction_prompt(ZZ_MODE_CLEAN, ZZ_TRANSLATE_NONE, dict, "."), cancel_check());
    }
    return st;
}

zz_status zz_engine::load() {
    Guard g(this);
    if (!g.ok) return ZZ_ERR_BUSY;
    if (loaded_.load()) return ZZ_OK;  // idempotente
    zz_status st = verify_models();
    if (st != ZZ_OK) return st;
    if (!config_.sequential_loading) {
        // Entrambi i modelli in RAM subito (solo dispositivi con molta memoria).
        if ((st = ensure_whisper()) != ZZ_OK) return st;
        if ((st = ensure_llm()) != ZZ_OK) {
            stt_->unload();
            return st;
        }
    }
    // Con sequential_loading=1 si verifica soltanto: ogni modello si carica quando serve.
    loaded_.store(true);
    return ZZ_OK;
}

zz_status zz_engine::unload() {
    Guard g(this);
    if (!g.ok) return ZZ_ERR_BUSY;
    stt_->unload();  // Spec §4.2: modelli fuori dalla RAM dopo l'inattività
    llm_->unload();
    loaded_.store(false);
    return ZZ_OK;
}

// ---- Personalizzazioni ----------------------------------------------------------

zz_status zz_engine::set_dictionary(const char *const *words, size_t count) {
    if (count && !words) return ZZ_ERR_INVALID_ARG;
    std::vector<std::string> w;
    for (size_t i = 0; i < count; ++i)
        if (words[i]) w.emplace_back(words[i]);
    w = zz::sanitize_dictionary(w);  // Spec §5.5
    std::lock_guard<std::mutex> lock(data_mutex_);
    dictionary_ = std::move(w);
    return ZZ_OK;
}

zz_status zz_engine::set_shortcuts(const char *const *keywords, const char *const *expansions, size_t count) {
    if (count && (!keywords || !expansions)) return ZZ_ERR_INVALID_ARG;
    std::vector<std::pair<std::string, std::string>> sc;
    for (size_t i = 0; i < count; ++i)
        if (keywords[i] && expansions[i]) sc.emplace_back(keywords[i], expansions[i]);  // Spec §5.11
    std::lock_guard<std::mutex> lock(data_mutex_);
    shortcuts_ = std::move(sc);
    return ZZ_OK;
}

// ---- Trascrizione (Spec §5.2, §5.5) -----------------------------------------------

zz_status zz_engine::transcribe(const float *pcm, size_t n, const zz_dictation_options &opt,
                                const std::vector<std::string> &dict, std::string *out) {
    zz_status st = ensure_whisper();
    if (st != ZZ_OK) return st;
    if (cancel_.load()) return ZZ_ERR_CANCELLED;
    zz::SttRequest req;
    req.language = (opt.language && opt.language[0]) ? opt.language : "auto";
    req.initial_prompt = zz::whisper_initial_prompt(dict);  // Spec §5.5
    st = stt_->transcribe(pcm, n, req, cancel_check(), out);
    if (st == ZZ_OK) *out = zz::trim(*out);
    return st;
}

// ---- Correzione e traduzione (Spec §5.3, §5.12) ------------------------------------

zz_status zz_engine::correct(const std::string &text, const zz_dictation_options &opt,
                             const std::vector<std::string> &dict, std::string *out, bool *used_fallback) {
    *used_fallback = false;
    zz_status st = ensure_llm();
    if (st == ZZ_ERR_CANCELLED || cancel_.load()) return ZZ_ERR_CANCELLED;
    if (st != ZZ_OK) {
        // Spec §5.3: se la correzione fallisce si inserisce il testo grezzo.
        *out = text;
        *used_fallback = true;
        return ZZ_OK;
    }
    // Il timeout copre l'inferenza (prompt e generazione), non il caricamento del modello.
    const auto deadline = Clock::now() + std::chrono::milliseconds(opt.correction_timeout_ms);
    const zz::AbortCheck abort = abort_check(deadline);
    const bool translating = opt.translate != ZZ_TRANSLATE_NONE;

    std::string result;
    for (const auto &piece : zz::split_for_correction(text, zz::kMaxCharsPerPiece)) {
        if (!piece.correct) {
            result += piece.text;  // a capo dei comandi vocali (Spec §5.9) e separatori
            continue;
        }
        const zz::ChatPrompt prompt = zz::correction_prompt(opt.mode, opt.translate, dict, piece.text);
        std::string gen;
        st = llm_->generate(prompt, zz::max_new_tokens_for(piece.text, translating), abort, &gen);
        if (st == ZZ_ERR_CANCELLED) return ZZ_ERR_CANCELLED;
        std::string cleaned = st == ZZ_OK ? zz::clean_model_output(gen, piece.text) : std::string();
        if (st != ZZ_OK || !zz::output_is_plausible(piece.text, cleaned, translating)) {
            // Spec §5.3: timeout o risposta non valida => testo grezzo, segnalato con used_fallback.
            *out = text;
            *used_fallback = true;
            return ZZ_OK;
        }
        result += cleaned;
    }
    *out = result;
    return ZZ_OK;
}

zz_status zz_engine::finish_text(const std::string &raw, const zz_dictation_options &opt, zz_stage_callback cb,
                                 void *ud, zz_result *out) {
    std::vector<std::string> dict;
    std::vector<std::pair<std::string, std::string>> shortcuts;
    snapshot(&dict, &shortcuts);

    // Spec §5.9: comandi vocali riconosciuti in locale, prima della correzione.
    zz::CommandResult cmd;
    if (opt.voice_commands_enabled) {
        cmd = zz::apply_voice_commands(raw);
    } else {
        cmd.text = zz::trim(raw);
    }

    std::string final_text, expansion;
    int from_shortcut = 0, used_fallback = 0;
    int64_t ms_correct = 0;
    if (zz::match_shortcut(shortcuts, cmd.text, &expansion)) {
        // Spec §5.11: parola chiave pronunciata da sola => testo associato, senza correzione.
        final_text = expansion;
        from_shortcut = 1;
    } else if (!has_alnum(cmd.text)) {
        final_text = cmd.text;  // solo comandi/azioni (es. "annulla", "a capo"): niente da correggere
    } else {
        if (!loaded_.load()) return ZZ_ERR_NOT_LOADED;
        if (cb) cb(ZZ_STAGE_CORRECTING, ud);
        const auto t0 = Clock::now();
        bool fb = false;
        zz_status st = correct(cmd.text, opt, dict, &final_text, &fb);
        if (st != ZZ_OK) return st;
        used_fallback = fb ? 1 : 0;
        ms_correct = ms_since(t0);
    }
    if (cancel_.load()) return ZZ_ERR_CANCELLED;
    if (cb) cb(ZZ_STAGE_DONE, ud);

    out->raw_text = dup_string(raw);
    out->final_text = dup_string(final_text);
    if (!out->raw_text || !out->final_text) {
        zz_result_free(out);
        return ZZ_ERR_OUT_OF_MEMORY;
    }
    out->actions = cmd.actions;
    out->used_fallback = used_fallback;
    out->from_shortcut = from_shortcut;
    out->ms_correct = ms_correct;
    return ZZ_OK;
}

// ---- Operazioni del contratto ------------------------------------------------------

zz_status zz_engine::dictate(const float *pcm, size_t n, const zz_dictation_options *options, zz_stage_callback cb,
                             void *ud, zz_result *out) {
    if (!out || (n && !pcm)) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    Guard g(this);
    if (!g.ok) return ZZ_ERR_BUSY;
    zz_dictation_options opt;
    if (!resolve_options(options, &opt)) return ZZ_ERR_INVALID_ARG;
    if (!loaded_.load()) return ZZ_ERR_NOT_LOADED;
    if (n < zz::kMinSamples || is_silence(pcm, n)) return ZZ_ERR_NO_SPEECH;

    std::vector<std::string> dict;
    snapshot(&dict, nullptr);
    if (cb) cb(ZZ_STAGE_TRANSCRIBING, ud);
    const auto t0 = Clock::now();
    std::string raw;
    zz_status st = transcribe(pcm, n, opt, dict, &raw);
    const int64_t ms_tr = ms_since(t0);
    if (st != ZZ_OK) return st;
    if (raw.empty()) return ZZ_ERR_NO_SPEECH;
    st = finish_text(raw, opt, cb, ud, out);
    if (st == ZZ_OK) out->ms_transcribe = ms_tr;
    return st;
}

zz_status zz_engine::rewrite(const char *selected, const float *pcm, size_t n, const zz_dictation_options *options,
                             zz_stage_callback cb, void *ud, zz_result *out) {
    if (!out || !selected || (n && !pcm)) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    Guard g(this);
    if (!g.ok) return ZZ_ERR_BUSY;
    zz_dictation_options opt;
    if (!resolve_options(options, &opt)) return ZZ_ERR_INVALID_ARG;
    if (!loaded_.load()) return ZZ_ERR_NOT_LOADED;
    if (n < zz::kMinSamples || is_silence(pcm, n)) return ZZ_ERR_NO_SPEECH;

    std::vector<std::string> dict;
    snapshot(&dict, nullptr);
    if (cb) cb(ZZ_STAGE_TRANSCRIBING, ud);
    auto t0 = Clock::now();
    std::string instruction;
    zz_status st = transcribe(pcm, n, opt, dict, &instruction);
    const int64_t ms_tr = ms_since(t0);
    if (st != ZZ_OK) return st;
    if (instruction.empty()) return ZZ_ERR_NO_SPEECH;

    // Spec §5.10: Phi-4-mini riscrive la selezione secondo l'istruzione.
    if (cb) cb(ZZ_STAGE_CORRECTING, ud);
    t0 = Clock::now();
    const std::string sel = selected;
    std::string final_text;
    int used_fallback = 0;
    st = ensure_llm();
    if (st == ZZ_ERR_CANCELLED || cancel_.load()) return ZZ_ERR_CANCELLED;
    if (st == ZZ_OK) {
        const auto deadline = t0 + std::chrono::milliseconds(opt.correction_timeout_ms);
        std::string gen;
        const int max_tokens = zz::max_new_tokens_for(sel, true) + 256;
        st = llm_->generate(zz::rewrite_prompt(instruction, sel, dict), max_tokens, abort_check(deadline), &gen);
        if (st == ZZ_ERR_CANCELLED) return ZZ_ERR_CANCELLED;
        if (st == ZZ_OK) {
            final_text = zz::clean_model_output(gen, sel);
            // una riscrittura può allungare ("espandi"), ma non deve essere vuota o sproporzionata
            if (final_text.empty() || final_text.size() > sel.size() * 3 + 600) st = ZZ_ERR_INTERNAL;
        }
    }
    if (st != ZZ_OK) {
        // Fallback: la selezione resta invariata (inserire l'istruzione al suo posto sarebbe un danno).
        final_text = sel;
        used_fallback = 1;
    }
    if (cancel_.load()) return ZZ_ERR_CANCELLED;
    if (cb) cb(ZZ_STAGE_DONE, ud);
    out->raw_text = dup_string(instruction);
    out->final_text = dup_string(final_text);
    if (!out->raw_text || !out->final_text) {
        zz_result_free(out);
        return ZZ_ERR_OUT_OF_MEMORY;
    }
    out->used_fallback = used_fallback;
    out->ms_transcribe = ms_tr;
    out->ms_correct = ms_since(t0);
    return ZZ_OK;
}

zz_status zz_engine::process_text(const char *raw, const zz_dictation_options *options, zz_result *out) {
    if (!raw || !out) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    Guard g(this);
    if (!g.ok) return ZZ_ERR_BUSY;
    zz_dictation_options opt;
    if (!resolve_options(options, &opt)) return ZZ_ERR_INVALID_ARG;
    return finish_text(zz::sanitize_text(raw), opt, nullptr, nullptr, out);
}

// ---- Creazione ---------------------------------------------------------------------

namespace zz {

zz_status create_engine(const zz_engine_config *config, std::unique_ptr<SttBackend> stt,
                        std::unique_ptr<LlmBackend> llm, zz_engine **out_engine) {
    if (!out_engine) return ZZ_ERR_INVALID_ARG;
    *out_engine = nullptr;
    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    if (config) {
        if (config->struct_size < sizeof(zz_engine_config)) return ZZ_ERR_INVALID_ARG;
        std::memcpy(&cfg, config, sizeof(cfg));
        cfg.struct_size = sizeof(cfg);
    }
    if (!valid_sha_hex(cfg.whisper_sha256) || !valid_sha_hex(cfg.llm_sha256)) return ZZ_ERR_INVALID_ARG;
    if (cfg.n_threads < 0) return ZZ_ERR_INVALID_ARG;
    silence_backend_logs();  // Spec §10
    if (!stt) stt = make_whisper_backend();
    if (!llm) llm = make_llama_backend();
    if (!stt || !llm) return ZZ_ERR_OUT_OF_MEMORY;
    auto *e = new (std::nothrow) zz_engine(cfg, std::move(stt), std::move(llm));
    if (!e) return ZZ_ERR_OUT_OF_MEMORY;
    *out_engine = e;
    return ZZ_OK;
}

}  // namespace zz
