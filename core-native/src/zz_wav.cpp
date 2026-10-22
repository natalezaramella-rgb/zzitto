// Lettura WAV (Spec §11, §12). Aggiunto il 22/10/2026 alle 11:33.
#include "zz_wav.h"

#include <cstdint>
#include <cstdio>
#include <cstring>

#if defined(_WIN32)
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#endif

namespace zz {

namespace {
uint32_t le32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
uint16_t le16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

// Percorsi UTF-8 anche su Windows.
std::FILE *open_utf8(const std::string &path) {
#if defined(_WIN32)
    const int n = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    if (n <= 0) return nullptr;
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, &w[0], n);
    return _wfopen(w.c_str(), L"rb");
#else
    return std::fopen(path.c_str(), "rb");
#endif
}
}  // namespace

bool read_wav_16k_mono(const std::string &path, std::vector<float> *pcm, std::string *error) {
    pcm->clear();
    std::FILE *f = open_utf8(path);
    if (!f) {
        *error = "file non trovato";
        return false;
    }
    std::vector<uint8_t> data;
    uint8_t buf[1 << 16];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.insert(data.end(), buf, buf + n);
    std::fclose(f);
    if (data.size() < 12 || std::memcmp(data.data(), "RIFF", 4) != 0 || std::memcmp(data.data() + 8, "WAVE", 4) != 0) {
        *error = "non è un file WAV RIFF";
        return false;
    }
    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t rate = 0;
    const uint8_t *samples = nullptr;
    size_t samples_len = 0;
    for (size_t off = 12; off + 8 <= data.size();) {
        const uint8_t *ch = data.data() + off;
        const uint32_t len = le32(ch + 4);
        const size_t body = off + 8;
        const size_t avail = data.size() - body;
        if (std::memcmp(ch, "fmt ", 4) == 0 && len >= 16 && avail >= 16) {
            format = le16(ch + 8);
            channels = le16(ch + 10);
            rate = le32(ch + 12);
            bits = le16(ch + 22);
            if (format == 0xFFFE && len >= 40 && avail >= 40) format = le16(ch + 32);  // WAVE_FORMAT_EXTENSIBLE
        } else if (std::memcmp(ch, "data", 4) == 0) {
            samples = ch + 8;
            samples_len = len < avail ? len : avail;
            break;
        }
        off = body + len + (len & 1);
    }
    if (!samples || channels == 0) {
        *error = "intestazione WAV incompleta";
        return false;
    }
    if (rate != 16000) {
        *error = "frequenza " + std::to_string(rate) + " Hz: serve 16000 Hz mono (es. ffmpeg -i in -ar 16000 -ac 1 out.wav)";
        return false;
    }
    const size_t bytes = bits / 8;
    if (!((format == 1 && bits == 16) || (format == 3 && bits == 32))) {
        *error = "formato non supportato: servono PCM 16 bit o float 32 bit";
        return false;
    }
    const size_t frames = samples_len / (bytes * channels);
    pcm->resize(frames);
    for (size_t i = 0; i < frames; ++i) {
        float acc = 0;
        for (size_t c = 0; c < channels; ++c) {
            const uint8_t *p = samples + (i * channels + c) * bytes;
            if (format == 1) {
                acc += static_cast<int16_t>(le16(p)) / 32768.0f;
            } else {
                uint32_t u = le32(p);
                float v;
                std::memcpy(&v, &u, 4);
                acc += v;
            }
        }
        (*pcm)[i] = acc / channels;
    }
    return true;
}

}  // namespace zz
