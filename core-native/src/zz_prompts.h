// Prompt di correzione, traduzione e riscrittura per Phi-4-mini e prompt iniziale
// per Whisper. Funzioni pure, testabili senza modelli (Spec §12).
// Spec §4.3 (il prompt di sistema sta in core-native), §5.3, §5.5, §5.10, §5.12.
// Aggiunto il 22/10/2026 alle 11:30.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "zz_engine.h"

namespace zz {

// Una parte del prompt. Le parti "special" sono i marcatori del template di chat
// (<|system|>, <|end|>, ...) e vengono tokenizzate come token speciali; tutto il
// resto (testo dettato, dizionario, istruzioni) è tokenizzato come testo semplice,
// così un testo dettato non può mai iniettare marcatori di controllo.
struct ChatPart {
    std::string text;
    bool special = false;
};

struct ChatPrompt {
    std::vector<ChatPart> parts;
    std::string render() const;  // concatenazione, per test e debug (mai nei log: Spec §10)
};

// Template di chat ufficiale di Phi-4-mini-instruct, senza a capo tra i marcatori:
// <|system|>SISTEMA<|end|><|user|>UTENTE<|end|><|assistant|>
ChatPrompt phi4_mini_chat(const std::string &system, const std::string &user);

// Rimuove caratteri di controllo (tranne \n e \t) e normalizza gli a capo \r\n.
std::string sanitize_text(const std::string &text);

// Dizionario personale ripulito: voci vuote o doppie scartate, limiti di numero e lunghezza.
std::vector<std::string> sanitize_dictionary(const std::vector<std::string> &words,
                                             size_t max_words = 200, size_t max_chars = 2000);

// Spec §5.5: prompt iniziale per Whisper con le parole del dizionario.
// Restituisce una stringa vuota se il dizionario è vuoto. Limitato a ~200 token.
std::string whisper_initial_prompt(const std::vector<std::string> &dictionary);

// Spec §5.3, §5.12: prompt di sistema fisso per la modalità e l'eventuale traduzione.
std::string correction_system_prompt(zz_correction_mode mode, zz_translate_target translate,
                                     const std::vector<std::string> &dictionary);
ChatPrompt correction_prompt(zz_correction_mode mode, zz_translate_target translate,
                             const std::vector<std::string> &dictionary, const std::string &text);

// Spec §5.10: riscrittura del testo selezionato secondo l'istruzione dettata.
std::string rewrite_system_prompt(const std::vector<std::string> &dictionary);
ChatPrompt rewrite_prompt(const std::string &instruction, const std::string &selected,
                          const std::vector<std::string> &dictionary);

// Pulisce l'uscita del modello: marcatori residui, etichette ("Testo corretto:"),
// tag <testo>, virgolette o blocchi di codice attorno al testo, note finali.
std::string clean_model_output(const std::string &output, const std::string &input);

// Controllo di plausibilità (Spec §5.3: non aggiungere contenuti): uscita vuota o
// molto più lunga dell'ingresso => si usa il testo grezzo.
bool output_is_plausible(const std::string &input, const std::string &output, bool translating);

// Pezzi da correggere: gli a capo dei comandi vocali (Spec §5.9) restano fuori dal
// modello, i testi lunghi sono spezzati a fine frase per stare nel contesto.
struct TextPiece {
    std::string text;
    bool correct = false;  // true = passa dal modello; false = copiato così com'è
};
std::vector<TextPiece> split_for_correction(const std::string &text, size_t max_chars);

// Numero massimo di token da generare per un pezzo di testo.
int max_new_tokens_for(const std::string &text, bool translating);

// Whisper: frasi tipiche "allucinate" sul silenzio (es. "Sottotitoli creati dalla
// comunità Amara.org"), da scartare (Spec §5.2: nessun testo se non c'è parlato).
bool is_whisper_hallucination(const std::string &segment);

}  // namespace zz
