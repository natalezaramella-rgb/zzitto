// Logica condivisa tra motore reale e mock (core-native).
// Spec §5.2, §5.9, §5.11, §7. Aggiunto il 22/10/2026 alle 11:05.
#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "zz_engine.h"

namespace zz {

std::string trim(const std::string &s);
std::string to_lower_ascii_it(const std::string &s);  // minuscole per ASCII e lettere accentate italiane

// Comandi vocali (Spec §5.9): sostituisce "a capo", "nuovo paragrafo", "punto",
// "virgola", ecc. e gestisce "cancella l'ultima frase", "annulla", "invia".
struct CommandResult {
    std::string text;
    uint32_t actions = ZZ_ACTION_NONE;
};
CommandResult apply_voice_commands(const std::string &raw);

// Scorciatoie (Spec §5.11): vale solo se l'enunciato è la sola parola chiave
// (ignorando maiuscole, spazi e punteggiatura finale).
bool match_shortcut(const std::vector<std::pair<std::string, std::string>> &shortcuts,
                    const std::string &utterance, std::string *expansion);

// Pulizia deterministica minima: intercalari, ripetizioni immediate,
// maiuscola iniziale, punto finale. Usata dal mock e nei test.
std::string basic_cleanup(const std::string &text, zz_correction_mode mode);

}  // namespace zz
