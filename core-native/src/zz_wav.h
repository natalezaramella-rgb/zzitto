// Lettura di file WAV per zz_bench e per i test con modelli reali (Spec §11, §12).
// Non fa parte della libreria: l'app riceve l'audio dal microfono, mai da file (Spec §10).
// Aggiunto il 22/10/2026 alle 11:33.
#pragma once

#include <string>
#include <vector>

namespace zz {

// Legge un WAV PCM 16 bit o float 32 bit, mono o stereo (mescolato in mono), a 16 kHz.
// Restituisce false e un messaggio in *error se il formato non è supportato.
bool read_wav_16k_mono(const std::string &path, std::vector<float> *pcm, std::string *error);

}  // namespace zz
