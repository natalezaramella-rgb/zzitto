// Correzione, traduzione e riscrittura con Phi-4-mini su llama.cpp
// (Spec §3, §5.3, §5.10, §5.12, §7, §10, §11). Aggiunto il 22/10/2026 alle 11:32.

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "whisper.h"
#include "zz_backends.h"

#if defined(ZZ_ENGINE_DEBUG_LOGS)
#  if defined(__ANDROID__)
#    include <android/log.h>
#  endif
#endif

namespace zz {

// ---- Log (Spec §10) ------------------------------------------------------------------

namespace {
void zz_log_sink(enum ggml_log_level level, const char *text, void *) {
#if defined(ZZ_ENGINE_DEBUG_LOGS)
    // Solo build di sviluppo: whisper/llama non scrivono il testo dettato nei log
    // (le stampe del testo sono disattivate nei parametri), ma in rilascio non si
    // scrive comunque nulla.
    if (level >= GGML_LOG_LEVEL_WARN && text) {
#  if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_WARN, "zz_engine", "%s", text);
#  else
        std::fputs(text, stderr);
#  endif
    }
#else
    (void)level;
    (void)text;
#endif
}
}  // namespace

void silence_backend_logs() {
    static std::once_flag once;
    std::call_once(once, []() {
        whisper_log_set(zz_log_sink, nullptr);  // imposta anche il log di ggml
        llama_log_set(zz_log_sink, nullptr);
    });
}

int default_thread_count() {
    const int hw = static_cast<int>(std::thread::hardware_concurrency());
    if (hw <= 0) return 4;
#if defined(__ANDROID__)
    // Misure con zz_bench sul Galaxy S25 Ultra (Snapdragon 8 Elite, 8 core) del 22/10/2026 21:48: con 6 thread
    // la latenza dello streaming è 3,1–3,9 s, con 4 thread 4,4–4,7 s, con 8 thread 4,0–6,0 s (Spec §11).
    return std::max(2, std::min(6, hw - 2));
#else
    // Desktop: 6 thread (misure del 22/10/2026 sul Surface Snapdragon X a 12 core). Con 8 o più thread
    // le barriere di ggml aspettano i core occupati da altre app e compaiono picchi fino a 10 s;
    // con 6 la latenza dello streaming resta tra 2 e 3,3 s (Spec §11).
    return std::max(1, std::min(6, hw - 2));
#endif
}

namespace {

constexpr uint32_t kContextTokens = 3072;  // KV f16 di Phi-4-mini: ~384 MB (Spec §7: RAM limitata)
constexpr uint32_t kBatch = 512;

struct AbortHolder {
    const AbortCheck *check = nullptr;
    AbortReason last = AbortReason::None;
};

bool llama_abort_cb(void *data) {
    auto *h = static_cast<AbortHolder *>(data);
    if (!h->check) return false;
    h->last = (*h->check)();
    return h->last != AbortReason::None;
}

bool llama_progress_cb(float, void *data) {
    const auto *abort = static_cast<const AbortCheck *>(data);
    return !abort || (*abort)() == AbortReason::None;  // false = interrompe il caricamento
}

zz_status abort_status(AbortReason r) {
    return r == AbortReason::Timeout ? ZZ_ERR_TIMEOUT : ZZ_ERR_CANCELLED;
}

class LlamaBackend final : public LlmBackend {
public:
    ~LlamaBackend() override { unload(); }

    zz_status load(const std::string &path, const BackendSettings &settings, const AbortCheck &abort) override {
        static std::once_flag once;
        std::call_once(once, []() { llama_backend_init(); });
        unload();
        llama_model_params mp = llama_model_default_params();
        // Spec §2.4, §11.1: GPU/NPU se presente nel build e richiesta; altrimenti CPU (NEON/AVX2).
        mp.n_gpu_layers = settings.use_gpu ? 999 : 0;
        mp.progress_callback = llama_progress_cb;
        mp.progress_callback_user_data = const_cast<AbortCheck *>(&abort);
        try {
            model_ = llama_model_load_from_file(path.c_str(), mp);
        } catch (const std::bad_alloc &) {
            model_ = nullptr;
            return ZZ_ERR_OUT_OF_MEMORY;
        } catch (...) {
            model_ = nullptr;
        }
        if (!model_) return abort() == AbortReason::Cancelled ? ZZ_ERR_CANCELLED : ZZ_ERR_MODEL_LOAD;

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = kContextTokens;
        cp.n_batch = kBatch;
        cp.n_ubatch = kBatch;
        cp.n_seq_max = 1;
        cp.n_threads = settings.n_threads;
        cp.n_threads_batch = settings.n_threads;
        cp.no_perf = true;
        cp.offload_kqv = settings.use_gpu;
        cp.abort_callback = llama_abort_cb;  // Spec §5.2, §5.3: annullamento e timeout
        cp.abort_callback_data = &abort_;
        try {
            ctx_ = llama_init_from_model(model_, cp);
        } catch (...) {
            ctx_ = nullptr;
        }
        if (!ctx_) {
            llama_model_free(model_);
            model_ = nullptr;
            return ZZ_ERR_OUT_OF_MEMORY;
        }
        vocab_ = llama_model_get_vocab(model_);
        n_ctx_ = static_cast<int>(llama_n_ctx(ctx_));
        // Spec §5.3: decodifica greedy (temperatura 0), deterministica e fedele al testo.
        sampler_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler_, llama_sampler_init_greedy());
        cached_.clear();
        return ZZ_OK;
    }

    void unload() override {
        if (sampler_) llama_sampler_free(sampler_);
        if (ctx_) llama_free(ctx_);
        if (model_) llama_model_free(model_);
        sampler_ = nullptr;
        ctx_ = nullptr;
        model_ = nullptr;
        vocab_ = nullptr;
        cached_.clear();
    }

    bool is_loaded() const override { return ctx_ != nullptr; }

    zz_status generate(const ChatPrompt &prompt, int max_new_tokens, const AbortCheck &abort,
                       std::string *out_text) override {
        if (!ctx_) return ZZ_ERR_NOT_LOADED;
        out_text->clear();
        std::vector<llama_token> tokens;
        if (!tokenize(prompt, &tokens)) return ZZ_ERR_INTERNAL;
        const int n_prompt = static_cast<int>(tokens.size());
        if (n_prompt + 16 >= n_ctx_) return ZZ_ERR_INTERNAL;  // testo troppo lungo: fallback al grezzo
        max_new_tokens = std::min(max_new_tokens, n_ctx_ - n_prompt - 1);

        abort_.check = &abort;
        abort_.last = AbortReason::None;
        struct Reset {
            AbortHolder &h;
            llama_sampler *s;
            ~Reset() {
                h.check = nullptr;
                if (s) llama_sampler_reset(s);
            }
        } reset{abort_, sampler_};

        // Riuso del prefisso già in cache (prompt di sistema fisso): solo la parte nuova
        // viene valutata, a vantaggio dei 5 s del §11.
        size_t common = 0;
        while (common < cached_.size() && common < tokens.size() && cached_[common] == tokens[common]) ++common;
        if (common == tokens.size()) --common;  // serve almeno un token per avere i logit
        llama_memory_t mem = llama_get_memory(ctx_);
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
            llama_memory_clear(mem, true);
            common = 0;
        }
        cached_.resize(common);

        for (size_t i = common; i < tokens.size();) {
            const int n = static_cast<int>(std::min<size_t>(kBatch, tokens.size() - i));
            zz_status st = decode(&tokens[i], n);
            if (st != ZZ_OK) return st;
            i += static_cast<size_t>(n);
        }

        std::string text;
        std::vector<char> piece(64);
        for (int i = 0; i < max_new_tokens; ++i) {
            const AbortReason r = abort();
            if (r != AbortReason::None) {
                invalidate();
                return abort_status(r);
            }
            llama_token tok = llama_sampler_sample(sampler_, ctx_, -1);
            if (llama_vocab_is_eog(vocab_, tok)) break;
            int n = llama_token_to_piece(vocab_, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            if (n < 0) {
                piece.resize(static_cast<size_t>(-n));
                n = llama_token_to_piece(vocab_, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            }
            if (n > 0) text.append(piece.data(), static_cast<size_t>(n));
            // Marcatori di ruolo non marcati come fine generazione: il turno è finito.
            if (text.find("<|end|>") != std::string::npos || text.find("<|user|>") != std::string::npos ||
                text.find("<|endoftext|>") != std::string::npos)
                break;
            zz_status st = decode(&tok, 1);
            if (st != ZZ_OK) return st;
        }
        *out_text = text;
        return ZZ_OK;
    }

    void warmup(const ChatPrompt &prompt, const AbortCheck &abort) override {
        std::string ignored;
        (void)generate(prompt, 0, abort, &ignored);  // 0 token: solo valutazione del prompt
    }

private:
    // Tokenizzazione a pezzi: solo i marcatori del template sono token speciali (Spec §10:
    // il testo dettato non può iniettare marcatori di controllo nel prompt).
    bool tokenize(const ChatPrompt &prompt, std::vector<llama_token> *out) {
        out->clear();
        bool first = true;
        for (const auto &part : prompt.parts) {
            if (part.text.empty()) continue;
            const int32_t len = static_cast<int32_t>(part.text.size());
            std::vector<llama_token> buf(part.text.size() + 8);
            int32_t n = llama_tokenize(vocab_, part.text.c_str(), len, buf.data(), static_cast<int32_t>(buf.size()),
                                       first, part.special);
            if (n < 0 && n != INT32_MIN) {
                buf.resize(static_cast<size_t>(-n));
                n = llama_tokenize(vocab_, part.text.c_str(), len, buf.data(), static_cast<int32_t>(buf.size()), first,
                                   part.special);
            }
            if (n < 0) return false;
            out->insert(out->end(), buf.begin(), buf.begin() + n);
            first = false;
        }
        return !out->empty();
    }

    zz_status decode(llama_token *toks, int n) {
        const int32_t rc = llama_decode(ctx_, llama_batch_get_one(toks, n));
        if (rc == 0) {
            cached_.insert(cached_.end(), toks, toks + n);
            return ZZ_OK;
        }
        invalidate();
        if (rc == 2) return abort_status(abort_.last == AbortReason::None ? AbortReason::Cancelled : abort_.last);
        return ZZ_ERR_INTERNAL;
    }

    void invalidate() {
        llama_memory_clear(llama_get_memory(ctx_), true);
        cached_.clear();
    }

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    const llama_vocab *vocab_ = nullptr;
    llama_sampler *sampler_ = nullptr;
    int n_ctx_ = 0;
    std::vector<llama_token> cached_;
    AbortHolder abort_;
};

}  // namespace

std::unique_ptr<LlmBackend> make_llama_backend() {
    return std::unique_ptr<LlmBackend>(new (std::nothrow) LlamaBackend());
}

}  // namespace zz
