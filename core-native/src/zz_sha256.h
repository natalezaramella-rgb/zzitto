// SHA-256 per la verifica di integrità dei modelli (Spec §7, §7.1, §10).
// Aggiunto il 22/10/2026 alle 11:22.
//
// Implementazione portabile più due percorsi accelerati scelti a runtime:
//  - ARMv8 Crypto Extensions (Galaxy S24, Snapdragon X: Spec §11.1);
//  - x86 SHA-NI (CPU Intel/AMD recenti).
// L'hash di Phi-4-mini Q4_K_M (circa 2,5 GB) passa così da decine di secondi a pochi
// secondi sul telefono, a vantaggio della prima dettatura (Spec §11).
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

namespace zz {

enum class Sha256Impl { Auto, Portable };

class Sha256 {
public:
    explicit Sha256(Sha256Impl impl = Sha256Impl::Auto);
    void update(const void *data, size_t n);
    // Scrive 64 caratteri esadecimali minuscoli più '\0' (out deve avere almeno 65 byte).
    void final_hex(char *out);

private:
    uint32_t h_[8];
    uint64_t len_ = 0;
    uint8_t buf_[64];
    size_t used_ = 0;
    void (*compress_)(uint32_t *state, const uint8_t *blocks, size_t n_blocks);
};

// Nome del percorso scelto con Sha256Impl::Auto: "arm-crypto", "x86-sha-ni" o "portabile".
const char *sha256_auto_impl_name();

// Apre un file con percorso UTF-8 anche su Windows (dove fopen userebbe la code page ANSI).
std::FILE *fopen_utf8(const char *path, const char *mode);
// Dimensione e ora di modifica di un file (percorso UTF-8); false se non esiste.
bool file_info_utf8(const char *path, uint64_t *size, int64_t *mtime);

// SHA-256 di un file: ZZ_OK, ZZ_ERR_MODEL_LOAD se illeggibile. out_hex >= 65 byte.
int sha256_file(const char *path, char *out_hex, Sha256Impl impl = Sha256Impl::Auto);

}  // namespace zz
