// SHA-256 portabile e accelerato (Spec §7). Aggiunto il 22/10/2026 alle 11:22.
// La versione portabile deriva da quella del contratto iniziale (zz_common.cpp);
// i percorsi ARMv8/SHA-NI seguono lo schema documentato da ARM e Intel e sono
// verificati nei test confrontandoli con la versione portabile.

#include "zz_sha256.h"

#include <cstdio>
#include <cstring>
#include <vector>

#include <sys/stat.h>
#include <sys/types.h>
#if defined(_WIN32)
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#endif

#include "zz_engine.h"

#if defined(__aarch64__) || defined(_M_ARM64)
#  define ZZ_SHA_ARM64 1
#  if defined(_MSC_VER) && !defined(__clang__)
#    include <arm64_neon.h>
#    define ZZ_TARGET_SHA2
#  else
#    include <arm_neon.h>
#    if defined(__clang__)
#      define ZZ_TARGET_SHA2 __attribute__((target("sha2")))
#    else
#      define ZZ_TARGET_SHA2 __attribute__((target("+crypto")))
#    endif
#  endif
#  if defined(__linux__) || defined(__ANDROID__)
#    include <sys/auxv.h>
#    ifndef HWCAP_SHA2
#      define HWCAP_SHA2 (1 << 6)
#    endif
#  endif
#elif defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
#  define ZZ_SHA_X86 1
#  include <immintrin.h>
#  if defined(_MSC_VER)
#    include <intrin.h>  // __cpuid/__cpuidex (MSVC e clang-cl)
#  else
#    include <cpuid.h>   // GCC, Clang, MinGW
#  endif
#  if defined(__clang__) || defined(__GNUC__)
#    define ZZ_TARGET_SHANI __attribute__((target("sha,sse4.1,ssse3")))
#  else
#    define ZZ_TARGET_SHANI
#  endif
#endif

namespace zz {
namespace {

const uint32_t K256[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

inline uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void compress_portable(uint32_t *h, const uint8_t *p, size_t n_blocks) {
    for (; n_blocks; --n_blocks, p += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i)
            w[i] = (uint32_t(p[4 * i]) << 24) | (uint32_t(p[4 * i + 1]) << 16) |
                   (uint32_t(p[4 * i + 2]) << 8) | p[4 * i + 3];
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + K256[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }
}

#if defined(ZZ_SHA_ARM64)
// Spec §11.1: ARMv8 Crypto Extensions (SHA256H/SHA256H2/SHA256SU0/SHA256SU1).
ZZ_TARGET_SHA2 void compress_arm(uint32_t *state, const uint8_t *p, size_t n_blocks) {
    uint32x4_t s0 = vld1q_u32(&state[0]);  // ABCD
    uint32x4_t s1 = vld1q_u32(&state[4]);  // EFGH
    for (; n_blocks; --n_blocks, p += 64) {
        const uint32x4_t save0 = s0, save1 = s1;
        uint32x4_t msg[4];
        for (int k = 0; k < 4; ++k) msg[k] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p + 16 * k)));
        for (int i = 0; i < 16; ++i) {
            const uint32x4_t wk = vaddq_u32(msg[i & 3], vld1q_u32(&K256[4 * i]));
            const uint32x4_t abcd = s0;
            s0 = vsha256hq_u32(s0, s1, wk);
            s1 = vsha256h2q_u32(s1, abcd, wk);
            if (i < 12)
                msg[i & 3] = vsha256su1q_u32(vsha256su0q_u32(msg[i & 3], msg[(i + 1) & 3]),
                                             msg[(i + 2) & 3], msg[(i + 3) & 3]);
        }
        s0 = vaddq_u32(s0, save0);
        s1 = vaddq_u32(s1, save1);
    }
    vst1q_u32(&state[0], s0);
    vst1q_u32(&state[4], s1);
}

bool cpu_has_arm_sha2() {
#  if defined(__APPLE__)
    return true;
#  elif defined(__linux__) || defined(__ANDROID__)
    return (getauxval(AT_HWCAP) & HWCAP_SHA2) != 0;
#  elif defined(_WIN32)
#    ifndef PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE
#      define PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE 30
#    endif
    return IsProcessorFeaturePresent(PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE) != 0;
#  else
    return false;
#  endif
}
#endif

#if defined(ZZ_SHA_X86)
ZZ_TARGET_SHANI void compress_shani(uint32_t *state, const uint8_t *p, size_t n_blocks) {
    const __m128i mask = _mm_set_epi64x(0x0c0d0e0f08090a0bULL, 0x0405060700010203ULL);
    __m128i tmp = _mm_loadu_si128(reinterpret_cast<const __m128i *>(&state[0]));
    __m128i s1 = _mm_loadu_si128(reinterpret_cast<const __m128i *>(&state[4]));
    tmp = _mm_shuffle_epi32(tmp, 0xB1);          // CDAB
    s1 = _mm_shuffle_epi32(s1, 0x1B);            // EFGH
    __m128i s0 = _mm_alignr_epi8(tmp, s1, 8);    // ABEF
    s1 = _mm_blend_epi16(s1, tmp, 0xF0);         // CDGH
    for (; n_blocks; --n_blocks, p += 64) {
        const __m128i save0 = s0, save1 = s1;
        __m128i msg[4];
        for (int k = 0; k < 4; ++k)
            msg[k] = _mm_shuffle_epi8(_mm_loadu_si128(reinterpret_cast<const __m128i *>(p + 16 * k)), mask);
        for (int i = 0; i < 16; ++i) {
            __m128i wk = _mm_add_epi32(msg[i & 3], _mm_loadu_si128(reinterpret_cast<const __m128i *>(&K256[4 * i])));
            s1 = _mm_sha256rnds2_epu32(s1, s0, wk);
            wk = _mm_shuffle_epi32(wk, 0x0E);
            s0 = _mm_sha256rnds2_epu32(s0, s1, wk);
            if (i < 12) {
                const __m128i w7 = _mm_alignr_epi8(msg[(i + 3) & 3], msg[(i + 2) & 3], 4);
                const __m128i x = _mm_add_epi32(_mm_sha256msg1_epu32(msg[i & 3], msg[(i + 1) & 3]), w7);
                msg[i & 3] = _mm_sha256msg2_epu32(x, msg[(i + 3) & 3]);
            }
        }
        s0 = _mm_add_epi32(s0, save0);
        s1 = _mm_add_epi32(s1, save1);
    }
    tmp = _mm_shuffle_epi32(s0, 0x1B);           // FEBA
    s1 = _mm_shuffle_epi32(s1, 0xB1);            // DCHG
    s0 = _mm_blend_epi16(tmp, s1, 0xF0);         // DCBA
    s1 = _mm_alignr_epi8(s1, tmp, 8);            // HGFE
    _mm_storeu_si128(reinterpret_cast<__m128i *>(&state[0]), s0);
    _mm_storeu_si128(reinterpret_cast<__m128i *>(&state[4]), s1);
}

bool cpu_has_shani() {
    unsigned int a = 0, b = 0, c = 0, d = 0;
#  if defined(_MSC_VER)
    int r[4];
    __cpuid(r, 0);
    if (r[0] < 7) return false;
    __cpuidex(r, 1, 0);
    c = static_cast<unsigned int>(r[2]);
    const bool sse41 = (c & (1u << 19)) != 0, ssse3 = (c & (1u << 9)) != 0;
    __cpuidex(r, 7, 0);
    b = static_cast<unsigned int>(r[1]);
    (void)a; (void)d;
    return sse41 && ssse3 && (b & (1u << 29)) != 0;
#  else
    if (__get_cpuid_max(0, nullptr) < 7) return false;
    __cpuid_count(1, 0, a, b, c, d);
    const bool sse41 = (c & (1u << 19)) != 0, ssse3 = (c & (1u << 9)) != 0;
    __cpuid_count(7, 0, a, b, c, d);
    return sse41 && ssse3 && (b & (1u << 29)) != 0;
#  endif
}
#endif

using CompressFn = void (*)(uint32_t *, const uint8_t *, size_t);

struct AutoChoice {
    CompressFn fn = compress_portable;
    const char *name = "portabile";
    AutoChoice() {
#if defined(ZZ_SHA_ARM64)
        if (cpu_has_arm_sha2()) fn = compress_arm, name = "arm-crypto";
#elif defined(ZZ_SHA_X86)
        if (cpu_has_shani()) fn = compress_shani, name = "x86-sha-ni";
#endif
    }
};

const AutoChoice &auto_choice() {
    static const AutoChoice c;
    return c;
}

}  // namespace

Sha256::Sha256(Sha256Impl impl) {
    static const uint32_t init[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                     0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::memcpy(h_, init, sizeof(h_));
    compress_ = impl == Sha256Impl::Portable ? compress_portable : auto_choice().fn;
}

void Sha256::update(const void *data, size_t n) {
    const uint8_t *p = static_cast<const uint8_t *>(data);
    len_ += n;
    if (used_) {
        size_t take = n < 64 - used_ ? n : 64 - used_;
        std::memcpy(buf_ + used_, p, take);
        used_ += take; p += take; n -= take;
        if (used_ < 64) return;
        compress_(h_, buf_, 1);
        used_ = 0;
    }
    if (n >= 64) {
        compress_(h_, p, n / 64);
        p += n - n % 64;
        n %= 64;
    }
    if (n) {
        std::memcpy(buf_, p, n);
        used_ = n;
    }
}

void Sha256::final_hex(char *out) {
    const uint64_t bits = len_ * 8;
    uint8_t pad[72] = {0x80};
    const size_t pad_len = (used_ < 56 ? 56 - used_ : 120 - used_);
    update(pad, pad_len);
    uint8_t l[8];
    for (int i = 0; i < 8; ++i) l[i] = uint8_t(bits >> (56 - 8 * i));
    update(l, 8);
    for (int i = 0; i < 8; ++i) std::snprintf(out + 8 * i, 9, "%08x", h_[i]);
}

#if defined(_WIN32)
static std::wstring to_wide(const char *utf8) {
    const int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    if (n <= 0) return std::wstring();
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, &w[0], n);
    w.resize(static_cast<size_t>(n - 1));
    return w;
}
#endif

std::FILE *fopen_utf8(const char *path, const char *mode) {
    if (!path || !mode) return nullptr;
#if defined(_WIN32)
    const std::wstring wp = to_wide(path), wm = to_wide(mode);
    if (wp.empty()) return nullptr;
    return _wfopen(wp.c_str(), wm.c_str());
#else
    return std::fopen(path, mode);
#endif
}

bool file_info_utf8(const char *path, uint64_t *size, int64_t *mtime) {
    if (!path) return false;
#if defined(_WIN32)
    const std::wstring wp = to_wide(path);
    struct _stat64 st;
    if (wp.empty() || _wstat64(wp.c_str(), &st) != 0) return false;
    if ((st.st_mode & _S_IFMT) != _S_IFREG) return false;
#else
    struct stat st;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return false;
#endif
    if (size) *size = static_cast<uint64_t>(st.st_size);
    if (mtime) *mtime = static_cast<int64_t>(st.st_mtime);
    return true;
}

const char *sha256_auto_impl_name() { return auto_choice().name; }

int sha256_file(const char *path, char *out_hex, Sha256Impl impl) {
    if (!path || !out_hex) return ZZ_ERR_INVALID_ARG;
    FILE *f = fopen_utf8(path, "rb");
    if (!f) return ZZ_ERR_MODEL_LOAD;
    Sha256 s(impl);
    std::vector<uint8_t> chunk(size_t(4) << 20);
    size_t n;
    while ((n = std::fread(chunk.data(), 1, chunk.size(), f)) > 0) s.update(chunk.data(), n);
    const bool err = std::ferror(f) != 0;
    std::fclose(f);
    if (err) return ZZ_ERR_MODEL_LOAD;
    s.final_hex(out_hex);
    return ZZ_OK;
}

}  // namespace zz
