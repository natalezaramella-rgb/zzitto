// zz_bench — benchmark del motore Zzitto da riga di comando.
// Spec §11, §11.1, §16 fase 1 (prova di fattibilità). Aggiunto il 22/10/2026 alle 11:34.
//
// Legge un WAV 16 kHz, esegue la dettatura completa con zz_dictate (trascrizione,
// comandi, correzione) e stampa i tempi e il picco di RAM. Serve a misurare gli
// obiettivi del §11 su Galaxy S24 (adb shell) e su Surface Snapdragon / PC x64.
//
// Spec §10: il testo dettato NON viene stampato, se non con --print-text.

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "zz_engine.h"
#include "zz_wav.h"

#if defined(_WIN32)
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#  include <psapi.h>
#else
#  include <sys/resource.h>
#  include <unistd.h>
#endif

namespace {

using Clock = std::chrono::steady_clock;

// Memoria residente attuale e di picco del processo, in byte.
uint64_t current_rss() {
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc))) return pmc.WorkingSetSize;
    return 0;
#else
    std::FILE *f = std::fopen("/proc/self/statm", "r");
    if (!f) return 0;
    unsigned long size = 0, resident = 0;
    const int n = std::fscanf(f, "%lu %lu", &size, &resident);
    std::fclose(f);
    return n == 2 ? uint64_t(resident) * uint64_t(sysconf(_SC_PAGESIZE)) : 0;
#endif
}

uint64_t peak_rss() {
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc))) return pmc.PeakWorkingSetSize;
    return 0;
#else
    struct rusage ru;
    if (getrusage(RUSAGE_SELF, &ru) != 0) return 0;
#  if defined(__APPLE__)
    return uint64_t(ru.ru_maxrss);
#  else
    return uint64_t(ru.ru_maxrss) * 1024;
#  endif
#endif
}

double mb(uint64_t b) { return double(b) / (1024.0 * 1024.0); }

// Campiona la RAM ogni 20 ms e tiene il picco per fase (trascrizione / correzione).
struct MemSampler {
    std::atomic<bool> stop{false};
    std::atomic<int> phase{0};  // 0 = altro, 1 = trascrizione, 2 = correzione
    uint64_t peak[3] = {0, 0, 0};
    std::mutex m;
    std::thread th;
    void start() {
        th = std::thread([this]() {
            while (!stop.load()) {
                const uint64_t r = current_rss();
                {
                    std::lock_guard<std::mutex> l(m);
                    int p = phase.load();
                    if (r > peak[p]) peak[p] = r;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
        });
    }
    void finish() {
        stop.store(true);
        if (th.joinable()) th.join();
    }
};

struct StageLog {
    MemSampler *mem;
    Clock::time_point t_start, t_correct, t_done;
};

void on_stage(zz_stage stage, void *ud) {
    auto *s = static_cast<StageLog *>(ud);
    if (stage == ZZ_STAGE_TRANSCRIBING) {
        s->mem->phase.store(1);
    } else if (stage == ZZ_STAGE_CORRECTING) {
        s->t_correct = Clock::now();
        s->mem->phase.store(2);
    } else if (stage == ZZ_STAGE_DONE) {
        s->t_done = Clock::now();
        s->mem->phase.store(0);
    }
}

long long ms(Clock::duration d) { return std::chrono::duration_cast<std::chrono::milliseconds>(d).count(); }

void usage() {
    std::fprintf(stderr,
                 "Uso: zz_bench --audio file.wav --whisper whisper-medium-q5_0.bin --llm phi-4-mini-instruct-q4_k_m.gguf\n"
                 "              [--whisper-sha HEX] [--llm-sha HEX] [--lang it] [--mode fedele|pulito|formale]\n"
                 "              [--translate en|es] [--threads N] [--gpu 0|1] [--parallel-load] [--runs N]\n"
                 "              [--timeout MS] [--dict \"Parola1,Parola2\"] [--rewrite \"testo selezionato\"]\n"
                 "              [--print-text]\n"
                 "Spec §11: obiettivo < 5000 ms dalla fine di una dettatura di 15 s al testo corretto.\n");
}

}  // namespace

int main(int argc, char **argv) {
    std::string audio, whisper, llm, whisper_sha, llm_sha, lang = "it", dict, rewrite_sel;
    int threads = 0, gpu = 1, runs = 1, timeout = 0;
    bool parallel = false, print_text = false, has_rewrite = false;
    zz_correction_mode mode = ZZ_MODE_CLEAN;
    zz_translate_target tr = ZZ_TRANSLATE_NONE;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) {
                usage();
                std::exit(2);
            }
            return argv[++i];
        };
        if (a == "--audio") audio = next();
        else if (a == "--whisper") whisper = next();
        else if (a == "--llm") llm = next();
        else if (a == "--whisper-sha") whisper_sha = next();
        else if (a == "--llm-sha") llm_sha = next();
        else if (a == "--lang") lang = next();
        else if (a == "--threads") threads = std::atoi(next().c_str());
        else if (a == "--gpu") gpu = std::atoi(next().c_str());
        else if (a == "--runs") runs = std::max(1, std::atoi(next().c_str()));
        else if (a == "--timeout") timeout = std::atoi(next().c_str());
        else if (a == "--dict") dict = next();
        else if (a == "--rewrite") rewrite_sel = next(), has_rewrite = true;
        else if (a == "--parallel-load") parallel = true;
        else if (a == "--print-text") print_text = true;
        else if (a == "--mode") {
            std::string m = next();
            mode = m == "fedele" ? ZZ_MODE_FAITHFUL : m == "formale" ? ZZ_MODE_FORMAL : ZZ_MODE_CLEAN;
        } else if (a == "--translate") {
            std::string t = next();
            tr = t == "en" ? ZZ_TRANSLATE_EN : t == "es" ? ZZ_TRANSLATE_ES : ZZ_TRANSLATE_NONE;
        } else if (a == "-h" || a == "--help") {
            usage();
            return 0;
        } else {
            std::fprintf(stderr, "Opzione sconosciuta: %s\n", a.c_str());
            usage();
            return 2;
        }
    }
    if (audio.empty() || whisper.empty() || llm.empty()) {
        usage();
        return 2;
    }

    std::vector<float> pcm;
    std::string err;
    if (!zz::read_wav_16k_mono(audio, &pcm, &err)) {
        std::fprintf(stderr, "WAV non valido: %s\n", err.c_str());
        return 1;
    }
    std::printf("%s\n", zz_version_string());
    std::printf("audio: %.2f s\n", pcm.size() / 16000.0);

    MemSampler mem;
    mem.start();

    zz_engine_config cfg;
    zz_engine_config_init(&cfg);
    cfg.whisper_model_path = whisper.c_str();
    cfg.llm_model_path = llm.c_str();
    cfg.whisper_sha256 = whisper_sha.empty() ? nullptr : whisper_sha.c_str();
    cfg.llm_sha256 = llm_sha.empty() ? nullptr : llm_sha.c_str();
    cfg.n_threads = threads;
    cfg.use_gpu = gpu;
    cfg.sequential_loading = parallel ? 0 : 1;  // Spec §7
    zz_engine *e = nullptr;
    zz_status st = zz_engine_create(&cfg, &e);
    if (st != ZZ_OK) {
        std::fprintf(stderr, "zz_engine_create: %s\n", zz_status_string(st));
        return 1;
    }
    if (!dict.empty()) {
        std::vector<std::string> words;
        size_t p = 0;
        while (p <= dict.size()) {
            size_t q = dict.find(',', p);
            if (q == std::string::npos) q = dict.size();
            if (q > p) words.push_back(dict.substr(p, q - p));
            p = q + 1;
        }
        std::vector<const char *> ptrs;
        for (auto &w : words) ptrs.push_back(w.c_str());
        zz_engine_set_dictionary(e, ptrs.data(), ptrs.size());
    }

    auto t0 = Clock::now();
    st = zz_engine_load(e);
    std::printf("load (verifica SHA-256%s): %lld ms -> %s\n", parallel ? " + caricamento modelli" : "",
                ms(Clock::now() - t0), zz_status_string(st));
    if (st != ZZ_OK) {
        zz_engine_destroy(e);
        mem.finish();
        return 1;
    }

    zz_dictation_options opt;
    zz_dictation_options_init(&opt);
    opt.language = lang.c_str();
    opt.mode = mode;
    opt.translate = tr;
    if (timeout > 0) opt.correction_timeout_ms = timeout;

    int rc = 0;
    for (int r = 0; r < runs; ++r) {
        StageLog log{&mem, {}, {}, {}};
        zz_result res{};
        log.t_start = Clock::now();
        st = has_rewrite ? zz_rewrite(e, rewrite_sel.c_str(), pcm.data(), pcm.size(), &opt, on_stage, &log, &res)
                         : zz_dictate(e, pcm.data(), pcm.size(), &opt, on_stage, &log, &res);
        const auto t_end = Clock::now();
        if (st != ZZ_OK) {
            std::printf("run %d: %s\n", r + 1, zz_status_string(st));
            rc = 1;
            continue;
        }
        const long long total = ms(t_end - log.t_start);
        // I tempi di fase includono l'eventuale caricamento sequenziale del modello (Spec §7).
        const long long phase_tr = log.t_correct.time_since_epoch().count() ? ms(log.t_correct - log.t_start) : total;
        const long long phase_corr = log.t_correct.time_since_epoch().count() ? ms(t_end - log.t_correct) : 0;
        std::printf("run %d: totale %lld ms | trascrizione %lld ms (fase %lld ms) | correzione %lld ms (fase %lld ms)"
                    " | fallback=%d scorciatoia=%d azioni=0x%x | obiettivo §11 (<5000 ms): %s\n",
                    r + 1, total, (long long)res.ms_transcribe, phase_tr, (long long)res.ms_correct, phase_corr,
                    res.used_fallback, res.from_shortcut, res.actions, total < 5000 ? "OK" : "NO");
        if (print_text) {
            std::printf("  grezzo: %s\n  finale: %s\n", res.raw_text, res.final_text);
        }
        zz_result_free(&res);
    }
    zz_engine_destroy(e);
    mem.finish();
    std::printf("RAM picco processo: %.0f MB | picco in trascrizione: %.0f MB | picco in correzione: %.0f MB\n",
                mb(peak_rss()), mb(mem.peak[1]), mb(mem.peak[2]));
    return rc;
}
