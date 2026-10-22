// Logica condivisa tra motore reale e mock (core-native).
// Spec §5.2, §5.3, §5.9, §5.11, §7. Aggiunto il 22/10/2026 alle 11:05.

#include "zz_common.h"
#include "zz_sha256.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

namespace zz {

std::string trim(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

std::string to_lower_ascii_it(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        // UTF-8 maiuscole accentate italiane: À È É Ì Ò Ù = C3 80/88/89/8C/92/99
        if (c == 0xC3 && i + 1 < s.size()) {
            unsigned char n = static_cast<unsigned char>(s[i + 1]);
            if (n >= 0x80 && n <= 0x9E && n != 0x97) n = static_cast<unsigned char>(n + 0x20);
            out.push_back(static_cast<char>(c));
            out.push_back(static_cast<char>(n));
            ++i;
            continue;
        }
        out.push_back(static_cast<char>(std::tolower(c)));
    }
    return out;
}

namespace {

bool is_punct(char c) {
    return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '"';
}

// Forma normalizzata di una parola per il confronto: minuscola, senza punteggiatura ai bordi.
std::string norm_word(const std::string &w) {
    size_t b = 0, e = w.size();
    while (b < e && is_punct(w[b])) ++b;
    while (e > b && is_punct(w[e - 1])) --e;
    std::string core = to_lower_ascii_it(w.substr(b, e - b));
    // apostrofi tipografici -> ASCII
    std::string out;
    for (size_t i = 0; i < core.size(); ++i) {
        if (i + 2 < core.size() && static_cast<unsigned char>(core[i]) == 0xE2 &&
            static_cast<unsigned char>(core[i + 1]) == 0x80 &&
            static_cast<unsigned char>(core[i + 2]) == 0x99) {
            out.push_back('\'');
            i += 2;
        } else {
            out.push_back(core[i]);
        }
    }
    return out;
}

std::vector<std::string> split_words(const std::string &s) {
    std::vector<std::string> words;
    std::string cur;
    for (char c : s) {
        if (std::isspace(static_cast<unsigned char>(c))) {
            if (!cur.empty()) words.push_back(cur), cur.clear();
            // I ritorni a capo (comandi "a capo", "nuovo paragrafo") restano come parole
            // a sé, così la pulizia non li perde. Integrazione 22/10/2026 12:17, Spec §5.9.
            if (c == '\n') words.emplace_back("\n");
        } else {
            cur.push_back(c);
        }
    }
    if (!cur.empty()) words.push_back(cur);
    return words;
}

// Rimuove spazi prima della punteggiatura e attorno ai ritorni a capo.
std::string tidy(const std::string &s) {
    std::string out;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if ((is_punct(c) && c != '"') || c == '\n') {
            while (!out.empty() && out.back() == ' ') out.pop_back();
            out.push_back(c);
            if (c == '\n') {
                while (i + 1 < s.size() && s[i + 1] == ' ') ++i;
            }
            continue;
        }
        if (c == ' ' && !out.empty() && out.back() == ' ') continue;
        out.push_back(c);
    }
    return trim(out);
}

// Cancella l'ultima frase dal testo; restituisce false se non c'era nessuna frase.
bool drop_last_sentence(std::string &text) {
    std::string t = text;
    while (!t.empty() && (t.back() == ' ' || t.back() == '\n')) t.pop_back();
    if (t.empty()) return false;
    size_t end = t.size();
    // salta la punteggiatura finale della frase da cancellare
    while (end > 0 && (t[end - 1] == '.' || t[end - 1] == '!' || t[end - 1] == '?')) --end;
    size_t cut = 0;
    for (size_t i = end; i > 0; --i) {
        char c = t[i - 1];
        if (c == '.' || c == '!' || c == '?' || c == '\n') {
            cut = i;
            break;
        }
    }
    text = t.substr(0, cut);
    return true;
}

}  // namespace

CommandResult apply_voice_commands(const std::string &raw) {
    CommandResult r;
    std::vector<std::string> w = split_words(raw);
    std::vector<std::string> n;
    n.reserve(w.size());
    for (auto &x : w) n.push_back(norm_word(x));

    // "annulla" da solo = annulla l'ultimo inserimento
    if (n.size() == 1 && n[0] == "annulla") {
        r.actions |= ZZ_ACTION_UNDO_LAST_INSERT;
        return r;
    }

    auto is_command_start = [&](size_t i) {
        const std::string &a = n[i];
        const std::string b = i + 1 < n.size() ? n[i + 1] : "";
        return (a == "a" && b == "capo") || (a == "nuovo" && b == "paragrafo") || a == "punto" ||
               a == "virgola" || (a == "due" && b == "punti") || a == "cancella" || a == "invia";
    };

    std::string out;
    auto append = [&](const std::string &s) {
        if (!out.empty() && out.back() != '\n') out.push_back(' ');
        out += s;
    };

    for (size_t i = 0; i < w.size(); ++i) {
        const std::string &a = n[i];
        const std::string b = i + 1 < n.size() ? n[i + 1] : "";
        const std::string c = i + 2 < n.size() ? n[i + 2] : "";
        const bool at_end_after = [&](size_t k) { return k >= n.size() || is_command_start(k); }(i + 1);

        if (a == "a" && b == "capo") { out += "\n"; ++i; continue; }
        if (a == "nuovo" && b == "paragrafo") { out += "\n\n"; ++i; continue; }
        if (a == "punto" && b == "e" && c == "virgola") { out += ";"; i += 2; continue; }
        if (a == "punto" && b == "interrogativo") { out += "?"; ++i; continue; }
        if (a == "punto" && b == "esclamativo") { out += "!"; ++i; continue; }
        if (a == "due" && b == "punti") { out += ":"; ++i; continue; }
        // "punto" è un comando solo a fine frase o prima di un altro comando,
        // per non toccare frasi come "a che punto siamo".
        if (a == "punto" && i > 0 && at_end_after) { out += "."; continue; }
        if (a == "virgola" && i > 0) { out += ","; continue; }
        if (a == "cancella" && b == "l'ultima" && c == "frase") {
            if (!drop_last_sentence(out)) r.actions |= ZZ_ACTION_DELETE_PREVIOUS_SENTENCE;
            i += 2;
            continue;
        }
        if (a == "invia" && i + 1 == n.size()) { r.actions |= ZZ_ACTION_SEND; continue; }
        append(w[i]);
    }
    r.text = tidy(out);
    return r;
}

bool match_shortcut(const std::vector<std::pair<std::string, std::string>> &shortcuts,
                    const std::string &utterance, std::string *expansion) {
    std::vector<std::string> words = split_words(utterance);
    std::string u;
    for (auto &w : words) {
        std::string x = norm_word(w);
        if (x.empty()) continue;
        if (!u.empty()) u.push_back(' ');
        u += x;
    }
    if (u.empty()) return false;
    for (const auto &kv : shortcuts) {
        std::string k;
        for (auto &w : split_words(kv.first)) {
            std::string x = norm_word(w);
            if (x.empty()) continue;
            if (!k.empty()) k.push_back(' ');
            k += x;
        }
        if (!k.empty() && k == u) {
            if (expansion) *expansion = kv.second;
            return true;
        }
    }
    return false;
}

std::string basic_cleanup(const std::string &text, zz_correction_mode mode) {
    static const char *fillers[] = {"ehm", "eh", "uhm", "um", "mmm", "mm", "ehh", "allora"};
    std::vector<std::string> w = split_words(text);
    std::vector<std::string> kept;
    std::string prev;
    for (size_t i = 0; i < w.size(); ++i) {
        std::string x = norm_word(w[i]);
        bool filler = false;
        if (mode != ZZ_MODE_FAITHFUL || x != "allora") {
            for (const char *f : fillers)
                if (x == f) filler = true;
        }
        // "allora" è un intercalare solo a inizio frase
        if (x == "allora" && !kept.empty()) filler = false;
        if (filler) continue;
        if (!x.empty() && x == prev) continue;  // ripetizione immediata
        kept.push_back(w[i]);
        prev = x;
    }
    std::string out;
    for (auto &k : kept) {
        if (!out.empty() && out.back() != '\n') out.push_back(' ');
        out += k;
    }
    out = tidy(out);
    if (out.empty()) return out;
    if (std::islower(static_cast<unsigned char>(out[0])))
        out[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(out[0])));
    char last = out.back();
    if (last != '.' && last != '!' && last != '?' && last != '\n' && last != ':' && last != ';')
        out.push_back('.');
    return out;
}

}  // namespace zz

// ---- Funzioni C condivise ------------------------------------------------

extern "C" {

ZZ_API const char *zz_status_string(zz_status status) {
    switch (status) {
        case ZZ_OK: return "ok";
        case ZZ_ERR_INVALID_ARG: return "argomento non valido";
        case ZZ_ERR_NOT_LOADED: return "modelli non caricati";
        case ZZ_ERR_MODEL_LOAD: return "impossibile caricare il modello";
        case ZZ_ERR_MODEL_INTEGRITY: return "verifica di integrita' del modello fallita";
        case ZZ_ERR_OUT_OF_MEMORY: return "memoria insufficiente";
        case ZZ_ERR_CANCELLED: return "operazione annullata";
        case ZZ_ERR_TIMEOUT: return "tempo scaduto";
        case ZZ_ERR_BUSY: return "motore occupato";
        case ZZ_ERR_NO_SPEECH: return "nessun parlato rilevato";
        case ZZ_ERR_INTERNAL: return "errore interno";
    }
    return "errore sconosciuto";
}

ZZ_API void zz_engine_config_init(zz_engine_config *config) {
    if (!config) return;
    std::memset(config, 0, sizeof(*config));
    config->struct_size = sizeof(*config);
    config->sequential_loading = 1;
    config->use_gpu = 1;
}

ZZ_API void zz_dictation_options_init(zz_dictation_options *options) {
    if (!options) return;
    std::memset(options, 0, sizeof(*options));
    options->struct_size = sizeof(*options);
    options->language = "it";
    options->mode = ZZ_MODE_CLEAN;
    options->translate = ZZ_TRANSLATE_NONE;
    options->voice_commands_enabled = 1;
    options->correction_timeout_ms = 10000;
}

ZZ_API void zz_result_free(zz_result *result) {
    if (!result) return;
    // Azzera i testi prima di liberarli: il testo dettato non resta in memoria (Spec §10).
    // Integrazione 22/10/2026 12:17.
    if (result->raw_text) std::memset(result->raw_text, 0, std::strlen(result->raw_text));
    if (result->final_text) std::memset(result->final_text, 0, std::strlen(result->final_text));
    std::free(result->raw_text);
    std::free(result->final_text);
    std::memset(result, 0, sizeof(*result));
}

// ---- VAD a energia con soglia adattiva (Spec §5.2) -----------------------

struct zz_vad {
    int32_t silence_ms;
    float noise_floor;
    int32_t speech_frames;
    int32_t silence_after_speech_ms;
    std::vector<float> pending;
};

ZZ_API zz_vad *zz_vad_create(int32_t silence_ms) {
    auto *v = new (std::nothrow) zz_vad();
    if (!v) return nullptr;
    v->silence_ms = silence_ms > 0 ? silence_ms : 2000;
    zz_vad_reset(v);
    return v;
}

ZZ_API void zz_vad_destroy(zz_vad *vad) { delete vad; }

ZZ_API void zz_vad_reset(zz_vad *vad) {
    if (!vad) return;
    vad->noise_floor = 0.003f;
    vad->speech_frames = 0;
    vad->silence_after_speech_ms = 0;
    vad->pending.clear();
}

ZZ_API int32_t zz_vad_process(zz_vad *vad, const float *pcm, size_t n) {
    if (!vad || (!pcm && n)) return 0;
    const size_t frame = 480;  // 30 ms a 16 kHz
    vad->pending.insert(vad->pending.end(), pcm, pcm + n);
    size_t off = 0;
    int32_t ended = 0;
    while (vad->pending.size() - off >= frame) {
        double sum = 0;
        for (size_t i = 0; i < frame; ++i) sum += double(vad->pending[off + i]) * vad->pending[off + i];
        float rms = static_cast<float>(std::sqrt(sum / frame));
        off += frame;
        float threshold = std::max(0.01f, vad->noise_floor * 3.0f);
        if (rms > threshold) {
            vad->speech_frames++;
            vad->silence_after_speech_ms = 0;
        } else {
            vad->noise_floor = 0.95f * vad->noise_floor + 0.05f * rms;
            if (vad->speech_frames >= 3) {
                vad->silence_after_speech_ms += 30;
                if (vad->silence_after_speech_ms >= vad->silence_ms) ended = 1;
            }
        }
    }
    vad->pending.erase(vad->pending.begin(), vad->pending.begin() + static_cast<long>(off));
    return ended;
}

ZZ_API int32_t zz_vad_has_speech(const zz_vad *vad) { return vad && vad->speech_frames >= 3 ? 1 : 0; }

// ---- SHA-256 (Spec §7) ---------------------------------------------------
// Implementazione in zz_sha256.cpp (portabile + ARMv8 Crypto / SHA-NI).
// Aggiornato il 22/10/2026 alle 11:22 (agente A).

ZZ_API zz_status zz_sha256_file(const char *path, char *out_hex) {
    return static_cast<zz_status>(zz::sha256_file(path, out_hex));
}

}  // extern "C"
