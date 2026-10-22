// Dettatura in streaming del motore reale (API 2 del contratto zz_engine.h).
// Spec §5.2, §5.5, §10, §11, §16.1. Aggiunto il 22/10/2026 alle 16:12 (agente A).
//
// Mentre l'utente parla, un thread del flusso taglia l'audio nelle pause e lo trascrive a pezzi; allo
// stop (zz_stream_finish) resta da trascrivere solo l'ultimo pezzo, poi il percorso è identico a
// zz_dictate (comandi, scorciatoie, correzione/traduzione). Spec §10: audio e testo restano solo in RAM.

#include "zz_stream.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "zz_common.h"
#include "zz_engine_core.h"
#include "zz_prompts.h"

using Clock = std::chrono::steady_clock;

// ---- Punti di taglio (Spec §5.2, §11) ---------------------------------------------------

namespace zz {

void PauseSegmenter::analyze(const float *pending, size_t n) {
    for (size_t off = analyzed_samples(); off + kStreamFrame <= n; off += kStreamFrame) {
        double sum = 0;
        for (size_t i = 0; i < kStreamFrame; ++i) sum += double(pending[off + i]) * pending[off + i];
        const float rms = static_cast<float>(std::sqrt(sum / kStreamFrame));
        // Stessa soglia adattiva di zz_vad_process (zz_common.cpp, Spec §5.2).
        const float threshold = std::max(0.01f, noise_floor_ * 3.0f);
        const bool voiced = rms > threshold;
        if (!voiced) noise_floor_ = 0.95f * noise_floor_ + 0.05f * rms;
        frames_.push_back({rms, voiced});
    }
}

size_t PauseSegmenter::find_cut() const {
    const size_t nf = frames_.size();
    size_t i = 0;
    while (i < nf) {
        if (frames_[i].voiced) {
            ++i;
            continue;
        }
        const size_t s = i;
        while (i < nf && !frames_[i].voiced) ++i;
        // Pausa lunga abbastanza che termina (o è ancora in corso) dopo ~4 s: taglio a metà pausa.
        if (i - s >= kStreamMinPauseFrames && i * kStreamFrame >= kStreamMinPiece) return ((s + i) / 2) * kStreamFrame;
    }
    if (nf * kStreamFrame >= kStreamMaxPiece) {
        // Nessuna pausa: taglio forzato nel blocco più quieto tra 15 e 20 s (meno probabile a metà parola).
        size_t best = kStreamForcedSearchFrom / kStreamFrame;
        const size_t end = kStreamMaxPiece / kStreamFrame;
        for (size_t f = best + 1; f < end; ++f)
            if (frames_[f].rms < frames_[best].rms) best = f;
        return best * kStreamFrame;
    }
    return 0;
}

void PauseSegmenter::consume(size_t samples) {
    const size_t nf = std::min(frames_.size(), samples / kStreamFrame);
    frames_.erase(frames_.begin(), frames_.begin() + static_cast<std::ptrdiff_t>(nf));
}

}  // namespace zz

// ---- Flusso ------------------------------------------------------------------------------

struct zz_stream {
    zz_engine *engine = nullptr;
    zz_dictation_options opt{};
    std::string language;  // copia: opt.language punta qui (il contratto copia le opzioni)
    std::vector<std::string> dict;

    std::mutex m;  // protegge pending, seg, total_samples, finishing, error
    std::condition_variable cv;
    std::vector<float> pending;  // audio ricevuto e non ancora tagliato (Spec §10: solo in RAM)
    zz::PauseSegmenter seg;
    size_t total_samples = 0;
    bool finishing = false;
    zz_status error = ZZ_OK;
    std::atomic<bool> stop{false};  // abort o chiusura: interrompe anche Whisper

    // Scritti solo dal thread del flusso; letti da finish dopo join (nessuna corsa).
    std::string text;
    bool speech_seen = false;
    std::thread worker;

    ~zz_stream() {
        {
            std::lock_guard<std::mutex> lock(m);
            stop.store(true);
        }
        cv.notify_all();
        if (worker.joinable()) worker.join();
        // Il motore resta occupato per tutta la vita del flusso (contratto, API 2).
        if (engine) engine->busy_.store(false);
    }

    zz::AbortCheck abort_check() const {
        return [this]() {
            return (engine->cancel_.load() || stop.load()) ? zz::AbortReason::Cancelled : zz::AbortReason::None;
        };
    }

    // Trascrive un pezzo e lo accoda al testo. Spec §5.5, §11: prompt = dizionario + coda del testo.
    zz_status transcribe_piece(const float *pcm, size_t n) {
        if (n < zz::kMinSamples || zz::is_silence(pcm, n)) return ZZ_OK;  // niente parlato: Whisper non parte
        speech_seen = true;
        const std::string prompt = zz::whisper_continuation_prompt(dict, text, zz::kStreamPromptTail);
        std::string piece;
        const zz_status st = engine->transcribe(pcm, n, opt, prompt, abort_check(), &piece);
        if (st != ZZ_OK) return st;
        if (!piece.empty()) {
            if (!text.empty()) text.push_back(' ');
            text += piece;
        }
        return ZZ_OK;
    }

    static zz_status feed(zz_stream *s, const float *pcm, size_t n);
    static zz_status finish(zz_stream *stream, zz_stage_callback cb, void *ud, zz_result *out);

    // Thread del flusso: taglia nelle pause e trascrive finché arriva finish, abort o zz_cancel.
    void run() {
        for (;;) {
            std::vector<float> piece;
            {
                std::unique_lock<std::mutex> lock(m);
                for (;;) {
                    if (stop.load() || finishing || error != ZZ_OK || engine->cancel_.load()) return;
                    seg.analyze(pending.data(), pending.size());
                    const size_t cut = seg.find_cut();
                    if (cut > 0) {
                        piece.assign(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(cut));
                        pending.erase(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(cut));
                        seg.consume(cut);
                        break;
                    }
                    // Nuovo audio, finish e abort notificano; zz_cancel (solo atomico) si vede entro 50 ms.
                    cv.wait_for(lock, std::chrono::milliseconds(50));
                }
            }
            const zz_status st = transcribe_piece(piece.data(), piece.size());
            if (st != ZZ_OK) {
                std::lock_guard<std::mutex> lock(m);
                error = st;  // restituito da finish (e da feed); niente più pezzi
                return;
            }
        }
    }
};

// ---- Operazioni del contratto (API 2) -----------------------------------------------------

zz_status zz_engine::stream_begin(const zz_dictation_options *options, zz_stream **out_stream) {
    if (!out_stream) return ZZ_ERR_INVALID_ARG;
    *out_stream = nullptr;
    bool expected = false;
    if (!busy_.compare_exchange_strong(expected, true)) return ZZ_ERR_BUSY;  // contratto: una operazione
    cancel_.store(false);
    // Da qui il flusso possiede l'occupazione: il suo distruttore la rilascia.
    std::unique_ptr<zz_stream> s(new (std::nothrow) zz_stream());
    if (!s) {
        busy_.store(false);
        return ZZ_ERR_OUT_OF_MEMORY;
    }
    s->engine = this;
    if (!zz::resolve_options(options, &s->opt)) return ZZ_ERR_INVALID_ARG;
    if (s->opt.language) {
        s->language = s->opt.language;
        s->opt.language = s->language.c_str();
    }
    if (!loaded_.load()) return ZZ_ERR_NOT_LOADED;
    snapshot(&s->dict, nullptr);  // Spec §5.5: dizionario fissato all'inizio del flusso

    // Modelli in RAM prima che l'utente parli (Spec §11). Con il caricamento sequenziale (Spec §7) solo
    // Whisper: Phi-4-mini si carica in finish. Un errore di Phi-4-mini qui non blocca: in finish la
    // correzione riprova e, se fallisce, usa il testo grezzo (used_fallback) come zz_dictate.
    zz_status st = ensure_whisper();
    if (st != ZZ_OK) return st;
    if (!config_.sequential_loading && ensure_llm() == ZZ_ERR_CANCELLED) return ZZ_ERR_CANCELLED;
    if (cancel_.load()) return ZZ_ERR_CANCELLED;
    try {
        s->worker = std::thread(&zz_stream::run, s.get());
    } catch (...) {
        return ZZ_ERR_OUT_OF_MEMORY;
    }
    *out_stream = s.release();
    return ZZ_OK;
}

zz_status zz_stream::feed(zz_stream *s, const float *pcm, size_t n) {
    if (!s || (n && !pcm)) return ZZ_ERR_INVALID_ARG;
    if (s->engine->cancel_.load()) return ZZ_ERR_CANCELLED;
    {
        std::lock_guard<std::mutex> lock(s->m);
        if (s->error != ZZ_OK) return s->error;  // pezzo fallito: finish restituirà lo stesso errore
        if (s->finishing) return ZZ_ERR_INVALID_ARG;
        try {
            s->pending.insert(s->pending.end(), pcm, pcm + n);
        } catch (const std::bad_alloc &) {
            return ZZ_ERR_OUT_OF_MEMORY;
        }
        s->total_samples += n;
    }
    s->cv.notify_one();
    return ZZ_OK;
}

zz_status zz_stream::finish(zz_stream *stream, zz_stage_callback cb, void *ud, zz_result *out) {
    if (!stream) return ZZ_ERR_INVALID_ARG;
    std::unique_ptr<zz_stream> s(stream);  // il contratto: finish libera sempre il flusso
    if (!out) return ZZ_ERR_INVALID_ARG;
    std::memset(out, 0, sizeof(*out));
    zz_engine *e = s->engine;

    if (cb) cb(ZZ_STAGE_TRANSCRIBING, ud);
    const auto t0 = Clock::now();
    {
        std::lock_guard<std::mutex> lock(s->m);
        s->finishing = true;
    }
    s->cv.notify_all();
    s->worker.join();  // attende il pezzo in corso; da qui tutto il flusso è di questo thread

    if (e->cancel_.load()) return ZZ_ERR_CANCELLED;
    if (s->error != ZZ_OK) return s->error;
    if (s->total_samples < zz::kMinSamples) return ZZ_ERR_NO_SPEECH;  // come zz_dictate
    // Ultimo pezzo: l'audio dopo l'ultimo taglio (Spec §11: l'unica trascrizione dopo lo stop).
    zz_status st = s->transcribe_piece(s->pending.data(), s->pending.size());
    std::vector<float>().swap(s->pending);  // Spec §10: l'audio non serve più
    if (st != ZZ_OK) return st;
    const int64_t ms_tr = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count();
    if (!s->speech_seen) return ZZ_ERR_NO_SPEECH;  // tutto silenzio, come is_silence in zz_dictate
    const std::string raw = zz::trim(s->text);
    if (raw.empty()) return ZZ_ERR_NO_SPEECH;

    // Da qui identico a zz_dictate: comandi (§5.9), scorciatoie (§5.11), correzione (§5.3), traduzione (§5.12).
    st = e->finish_text(raw, s->opt, cb, ud, out);
    if (st == ZZ_OK) out->ms_transcribe = ms_tr;
    return st;
}

extern "C" {

ZZ_API zz_status zz_stream_begin(zz_engine *engine, const zz_dictation_options *options, zz_stream **out_stream) {
    if (!engine) {
        if (out_stream) *out_stream = nullptr;
        return ZZ_ERR_INVALID_ARG;
    }
    return engine->stream_begin(options, out_stream);
}

ZZ_API zz_status zz_stream_feed(zz_stream *stream, const float *pcm_16k_mono, size_t n_samples) {
    return zz_stream::feed(stream, pcm_16k_mono, n_samples);
}

ZZ_API zz_status zz_stream_finish(zz_stream *stream, zz_stage_callback on_stage, void *user_data,
                                  zz_result *out_result) {
    return zz_stream::finish(stream, on_stage, user_data, out_result);
}

ZZ_API void zz_stream_abort(zz_stream *stream) { delete stream; }  // ferma Whisper, attende il thread, libera

}  // extern "C"
