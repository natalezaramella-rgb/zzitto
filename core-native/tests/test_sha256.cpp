// Test SHA-256 (Spec §7): vettori noti e confronto tra percorso accelerato e portabile.
// Aggiunto il 22/10/2026 alle 11:45.
#include <algorithm>
#include <cstdio>
#include <random>
#include <string>
#include <vector>

#include "zz_engine.h"
#include "zz_sha256.h"
#include "zz_test.h"

#if defined(_WIN32)
#  include <windows.h>
#endif

// Cancella un file con percorso UTF-8 (su Windows std::remove usa la code page ANSI).
static void remove_utf8(const char *path) {
#if defined(_WIN32)
    wchar_t w[512];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 512) > 0) _wremove(w);
#else
    std::remove(path);
#endif
}

static std::string hash(const std::string &data, zz::Sha256Impl impl, size_t step) {
    zz::Sha256 s(impl);
    for (size_t i = 0; i < data.size(); i += step) s.update(data.data() + i, std::min(step, data.size() - i));
    char hex[65] = {0};
    s.final_hex(hex);
    return hex;
}

int main() {
    std::printf("SHA-256 automatico: %s\n", zz::sha256_auto_impl_name());
    const zz::Sha256Impl impls[] = {zz::Sha256Impl::Portable, zz::Sha256Impl::Auto};
    for (auto impl : impls) {
        // Vettori FIPS 180-2
        ZZ_CHECK_EQ_STR(hash("", impl, 1), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        ZZ_CHECK_EQ_STR(hash("abc", impl, 1), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        ZZ_CHECK_EQ_STR(hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", impl, 7),
                        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
        ZZ_CHECK_EQ_STR(hash(std::string(1000000, 'a'), impl, 4096),
                        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }
    // Dati casuali di molte lunghezze, a blocchi irregolari: accelerato == portabile.
    std::mt19937 rng(42);
    for (size_t len : {1u, 55u, 56u, 63u, 64u, 65u, 119u, 120u, 127u, 128u, 1000u, 4096u, 100003u}) {
        std::string d(len, '\0');
        for (auto &c : d) c = static_cast<char>(rng());
        for (size_t step : {1u, 3u, 64u, 100u, 1u << 20}) {
            ZZ_CHECK_EQ_STR(hash(d, zz::Sha256Impl::Auto, step), hash(d, zz::Sha256Impl::Portable, len));
        }
    }
    // File con percorso UTF-8 (Spec §7.1: cartelle Windows con caratteri accentati).
    {
        const char *path = "zz_sha_test_\xC3\xA0\xC3\xA8.tmp";  // "àè"
        std::FILE *f = zz::fopen_utf8(path, "wb");
        ZZ_CHECK(f != nullptr);
        if (f) {
            std::fwrite("abc", 1, 3, f);
            std::fclose(f);
        }
        char hex[65] = {0};
        ZZ_CHECK(zz_sha256_file(path, hex) == ZZ_OK);
        ZZ_CHECK_EQ_STR(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        uint64_t size = 0;
        int64_t mtime = 0;
        ZZ_CHECK(zz::file_info_utf8(path, &size, &mtime) && size == 3 && mtime > 0);
        ZZ_CHECK(zz::sha256_file(path, hex, zz::Sha256Impl::Portable) == ZZ_OK);
        remove_utf8(path);
        ZZ_CHECK(!zz::file_info_utf8(path, &size, &mtime));
        ZZ_CHECK(zz_sha256_file(nullptr, hex) == ZZ_ERR_INVALID_ARG);
    }
    ZZ_TEST_MAIN_END();
}
