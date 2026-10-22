// Mini framework di test senza dipendenze esterne. Aggiunto il 22/10/2026 alle 11:05.
#pragma once
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

static int zz_failures = 0;
#define ZZ_CHECK(cond)                                                              \
    do {                                                                            \
        if (!(cond)) {                                                              \
            std::fprintf(stderr, "%s:%d: CHECK fallito: %s\n", __FILE__, __LINE__, #cond); \
            ++zz_failures;                                                          \
        }                                                                           \
    } while (0)
#define ZZ_CHECK_EQ_STR(a, b)                                                       \
    do {                                                                            \
        std::string _a = (a), _b = (b);                                             \
        if (_a != _b) {                                                             \
            std::fprintf(stderr, "%s:%d: atteso \"%s\", ottenuto \"%s\"\n", __FILE__, __LINE__, _b.c_str(), _a.c_str()); \
            ++zz_failures;                                                          \
        }                                                                           \
    } while (0)
#define ZZ_TEST_MAIN_END()                                                          \
    do {                                                                            \
        if (zz_failures) { std::fprintf(stderr, "%d controlli falliti\n", zz_failures); return 1; } \
        std::printf("OK\n");                                                        \
        return 0;                                                                   \
    } while (0)
