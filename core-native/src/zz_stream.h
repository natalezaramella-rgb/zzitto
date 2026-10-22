// Dettatura in streaming: scelta dei punti di taglio dell'audio (Spec §5.2, §11).
// Aggiunto il 22/10/2026 alle 16:12 (agente A).
//
// Logica pura, senza thread né modelli, testata direttamente in test_engine.cpp (Spec §12).
#pragma once

#include <cstddef>
#include <vector>

namespace zz {

// Spec §11: parametri del taglio nelle pause (registro decisioni, capitolo 14).
constexpr size_t kStreamFrame = 480;                  // blocco di analisi: 30 ms a 16 kHz (come zz_vad)
constexpr size_t kStreamMinPiece = 16000 * 4;         // si cerca una pausa solo dopo ~4 s non trascritti
constexpr size_t kStreamMinPauseFrames = 12;          // pausa di almeno 360 ms (~350 ms)
constexpr size_t kStreamMaxPiece = 16000 * 20;        // senza pause: taglio forzato a ~20 s
constexpr size_t kStreamForcedSearchFrom = 16000 * 15;  // il taglio forzato cade nel blocco più quieto tra 15 e 20 s
constexpr size_t kStreamPromptTail = 200;             // caratteri di testo precedente nel prompt di Whisper

// Analizza l'audio non ancora trascritto a blocchi da 30 ms con la soglia adattiva di zz_vad
// (Spec §5.2) e decide dove tagliare. I tagli cadono sempre su un multiplo di kStreamFrame
// dall'inizio del flusso, quindi i pezzi hanno la stessa griglia di blocchi dell'audio intero.
class PauseSegmenter {
public:
    // pending: audio non ancora tagliato, dall'inizio. Analizza solo i blocchi completi nuovi.
    void analyze(const float *pending, size_t n);
    // Punto di taglio in campioni (multiplo di kStreamFrame); 0 = nessun taglio per ora.
    // Prima pausa ≥ kStreamMinPauseFrames che termina dopo kStreamMinPiece (anche se ancora in corso):
    // taglio a metà pausa. Altrimenti, oltre kStreamMaxPiece, taglio forzato nel blocco più quieto.
    size_t find_cut() const;
    // Dopo un taglio: dimentica i primi `samples` campioni (multiplo di kStreamFrame).
    void consume(size_t samples);
    size_t analyzed_samples() const { return frames_.size() * kStreamFrame; }

private:
    struct Frame {
        float rms;
        bool voiced;
    };
    std::vector<Frame> frames_;
    float noise_floor_ = 0.003f;  // come zz_vad_reset
};

}  // namespace zz
