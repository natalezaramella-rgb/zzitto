// Trascrizione con whisper.cpp (Spec §3, §5.2, §5.5, §7, §10).
// Aggiunto il 22/10/2026 alle 11:31.

#include <cstring>
#include <new>
#include <string>

#include "whisper.h"
#include "zz_backends.h"
#include "zz_common.h"
#include "zz_prompts.h"

namespace zz {
namespace {

bool whisper_abort_cb(void *data) {
    const auto *abort = static_cast<const AbortCheck *>(data);
    return (*abort)() != AbortReason::None;
}

bool whisper_encoder_begin_cb(whisper_context *, whisper_state *, void *data) {
    return !whisper_abort_cb(data);  // false = interrompi prima dell'encoder
}

class WhisperBackend final : public SttBackend {
public:
    ~WhisperBackend() override { unload(); }

    zz_status load(const std::string &path, const BackendSettings &settings) override {
        unload();
        settings_ = settings;
        whisper_context_params cp = whisper_context_default_params();
        cp.use_gpu = settings.use_gpu;  // Spec §2.4, §11.1: GPU se disponibile nel build
        cp.flash_attn = true;           // più veloce anche su CPU (NEON/AVX2)
        try {
            ctx_ = whisper_init_from_file_with_params(path.c_str(), cp);
        } catch (const std::bad_alloc &) {
            ctx_ = nullptr;
            return ZZ_ERR_OUT_OF_MEMORY;
        } catch (...) {
            ctx_ = nullptr;
        }
        return ctx_ ? ZZ_OK : ZZ_ERR_MODEL_LOAD;
    }

    void unload() override {
        if (ctx_) whisper_free(ctx_);
        ctx_ = nullptr;
    }

    bool is_loaded() const override { return ctx_ != nullptr; }

    zz_status transcribe(const float *pcm, size_t n_samples, const SttRequest &req, const AbortCheck &abort,
                         std::string *out_text) override {
        if (!ctx_) return ZZ_ERR_NOT_LOADED;
        out_text->clear();
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = settings_.n_threads;
        // Spec §10: nessuna stampa del testo da parte di whisper.cpp.
        p.print_special = false;
        p.print_progress = false;
        p.print_realtime = false;
        p.print_timestamps = false;
        p.no_timestamps = true;
        p.no_context = true;
        p.single_segment = false;
        p.translate = false;      // la traduzione la fa Phi-4-mini (Spec §5.12)
        p.suppress_nst = true;    // niente "[Musica]" e simili
        const std::string lang = req.language.empty() ? "auto" : to_lower_ascii_it(req.language);
        if (lang != "auto" && whisper_lang_id(lang.c_str()) < 0) return ZZ_ERR_INVALID_ARG;
        p.language = lang.c_str();
        p.detect_language = false;
        // Spec §5.5: dizionario personale come prompt iniziale.
        p.initial_prompt = req.initial_prompt.empty() ? nullptr : req.initial_prompt.c_str();
        p.carry_initial_prompt = !req.initial_prompt.empty();
        // Spec §5.2: annullabile durante la trascrizione.
        p.abort_callback = whisper_abort_cb;
        p.abort_callback_user_data = const_cast<AbortCheck *>(&abort);
        p.encoder_begin_callback = whisper_encoder_begin_cb;
        p.encoder_begin_callback_user_data = const_cast<AbortCheck *>(&abort);

        int rc;
        try {
            rc = whisper_full(ctx_, p, pcm, static_cast<int>(n_samples));
        } catch (const std::bad_alloc &) {
            return ZZ_ERR_OUT_OF_MEMORY;
        } catch (...) {
            rc = -1;
        }
        if (abort() == AbortReason::Cancelled) return ZZ_ERR_CANCELLED;
        if (rc != 0) return ZZ_ERR_INTERNAL;

        std::string text;
        const int n = whisper_full_n_segments(ctx_);
        for (int i = 0; i < n; ++i) {
            const char *seg = whisper_full_get_segment_text(ctx_, i);
            if (!seg || is_whisper_hallucination(seg)) continue;
            std::string s = trim(seg);
            if (s.empty()) continue;
            if (!text.empty()) text.push_back(' ');
            text += s;
        }
        *out_text = text;
        return ZZ_OK;
    }

private:
    whisper_context *ctx_ = nullptr;
    BackendSettings settings_;
};

}  // namespace

std::unique_ptr<SttBackend> make_whisper_backend() {
    return std::unique_ptr<SttBackend>(new (std::nothrow) WhisperBackend());
}

}  // namespace zz
