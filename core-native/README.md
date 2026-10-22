# core-native — motore Zzitto

*Aggiunto il 22/10/2026 alle 11:40. Riferimenti: Spec §3, §4.3, §5.2–§5.12, §7, §10, §11, §11.1, §12, §16.1.*

Motore C++17 condiviso tra Android e Windows: trascrizione con **whisper.cpp**, comandi vocali e
scorciatoie, correzione/traduzione/riscrittura con **Phi-4-mini** su **llama.cpp**, VAD e verifica
SHA-256 dei modelli. L'unica interfaccia verso le app è l'API C di `include/zz_engine.h` (contratto,
vedi `docs/engine-contract.md`): il motore reale la rispetta esattamente, come il mock.

## Struttura

| Percorso | Contenuto |
|---|---|
| `include/zz_engine.h` | Contratto (solo orchestratore) |
| `mock/` | Motore finto (`-DZZ_ENGINE_MOCK=ON`) |
| `src/zz_common.*` | Comandi vocali, scorciatoie, VAD, funzioni C comuni (mock e reale) |
| `src/zz_sha256.*` | SHA-256 portabile + ARMv8 Crypto + x86 SHA-NI, file con percorsi UTF-8 |
| `src/zz_engine.cpp` | API C del motore reale |
| `src/zz_engine_core.*` | Orchestrazione: busy, annullamento, hash, caricamento sequenziale, pipeline |
| `src/zz_prompts.*` | Prompt di sistema, template Phi-4-mini, pulizia dell'uscita, suddivisione del testo |
| `src/zz_backends.h` | Interfacce interne `SttBackend` / `LlmBackend` (punto di iniezione per i test) |
| `src/zz_backend_whisper.cpp`, `src/zz_backend_llama.cpp` | Implementazioni con whisper.cpp e llama.cpp |
| `src/zz_wav.*` | Lettura WAV per `zz_bench` e test (non è nella libreria: Spec §10) |
| `tools/zz_bench.cpp` | Benchmark da riga di comando (Spec §11) |
| `cmake/real_engine.cmake` | Build del motore reale e delle dipendenze |
| `third_party/` | Sottomoduli git whisper.cpp e llama.cpp |
| `tests/` | Test (Spec §12), eseguiti a ogni build |

## Dipendenze fissate (Spec §2.2: licenza MIT)

| Sottomodulo | Commit | Note |
|---|---|---|
| `third_party/llama.cpp` | `7170cad4d02f7907517613931840cbb2367399a3` (tag `b11815`, 22/10/2026) | ggml 0.27.0, MIT |
| `third_party/whisper.cpp` | `231638a0717b7822784297e5980965ffcec05810` (master del 19/10/2026, dopo v1.9.5) | ggml 0.27.0, MIT |

Gli script dell'agente E (`tools/models/`) usano questa stessa copia di llama.cpp per convertire e
quantizzare Phi-4-mini, se presente.

Dopo il clone: `git submodule update --init --depth 1 core-native/third_party/whisper.cpp core-native/third_party/llama.cpp`
(i sottomoduli sono marcati `shallow = true` in `.gitmodules`).

### Un solo ggml

whisper.cpp e llama.cpp contengono ciascuno una copia di ggml: compilarle entrambe e collegarle
nella stessa libreria dà simboli duplicati (o, peggio, due versioni diverse di ggml nello stesso
processo). Soluzione adottata (`cmake/real_engine.cmake`):

1. si compila **una sola** copia, `third_party/llama.cpp/ggml`, prima dei due progetti;
2. whisper.cpp e llama.cpp aggiungono la propria copia solo `if (NOT TARGET ggml)`: trovando il
   target già definito, usano quello condiviso;
3. le due revisioni sono scelte con lo **stesso ggml 0.27.0**; la copia di llama.cpp è un
   sovrainsieme di quella di whisper.cpp (una sola operazione in più, `GGML_OP_TOP_K_MASK`),
   quindi l'API usata da whisper.cpp è identica.

Per aggiornare: scegliere un commit di whisper.cpp il cui `ggml/CMakeLists.txt` abbia la stessa
`GGML_VERSION_MINOR` del commit di llama.cpp, verificare con `diff -r whisper.cpp/ggml/include llama.cpp/ggml/include`
che le differenze siano solo aggiunte, ricompilare ed eseguire i test (compreso `integration`).

Tutto è statico dentro `zz_engine`; i simboli di ggml/llama/whisper sono nascosti (visibilità
`hidden`, `--exclude-libs,ALL` e version script `cmake/zz_engine.map` su Linux/Android). La libreria
esporta solo le funzioni `zz_*` del contratto.

## Compilazione

### Linux (sviluppo e test)

```sh
cmake -S core-native -B build/core -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/core        # compila ed esegue i test (Spec §12)
```

Con `-DZZ_ENGINE_MOCK=ON` si compila il mock, senza sottomoduli.

### Android (arm64-v8a, NDK)

Gradle `externalNativeBuild` punta a `core-native/CMakeLists.txt` (modulo `:engine` dell'agente B).
Esempio per `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DZZ_ENGINE_MOCK=OFF", "-DANDROID_STL=c++_static") } }
    }
    externalNativeBuild { cmake { path = file("../../core-native/CMakeLists.txt"); version = "3.22.1" } }
}
```

Produce `libzz_engine.so` (ARMv8.2-A + dotprod + fp16, NEON sempre attivo; pagine da 16 KB per
Google Play). Test e `zz_bench` non si compilano con `ANDROID`; per il benchmark sul telefono:

```sh
cmake -S core-native -B build/android -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DCMAKE_BUILD_TYPE=Release -DZZ_BUILD_BENCH=ON
cmake --build build/android --target zz_bench
adb push build/android/zz_bench build/android/libzz_engine.so modelli/*.bin modelli/*.gguf prova.wav /data/local/tmp/
adb shell "cd /data/local/tmp && LD_LIBRARY_PATH=. ./zz_bench --audio prova.wav \
    --whisper whisper-medium-q5_0.bin --llm phi-4-mini-instruct-q4_k_m.gguf --runs 3"
```

### Windows x64 e ARM64

Produce `zz_engine.dll` (nome fisso anche con MinGW) con gli export del contratto.

```bat
:: x64 (MSVC o clang-cl): AVX2/FMA/F16C attivi
cmake -S core-native -B build\win-x64 -G "Visual Studio 17 2022" -A x64
cmake --build build\win-x64 --config Release

:: ARM64 (Surface Snapdragon): serve clang-cl, NEON + dotprod + fp16
cmake -S core-native -B build\win-arm64 -G "Visual Studio 17 2022" -A ARM64 -T ClangCL
cmake --build build\win-arm64 --config Release
```

Con `cl.exe` su ARM64 la configurazione si ferma con un messaggio: ggml con MSVC "puro" su ARM può
solo sondare la macchina di build (`GGML_NATIVE`), cosa non portabile e impossibile in
cross-compilazione. Runtime C/C++ statico (`/MT`, `CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded`) per
la DLL e per tutte le dipendenze: `zz_engine.dll` non dipende dal runtime Visual C++ e non serve
alcuna DLL OpenMP (`GGML_OPENMP=OFF`).

### Opzioni CMake del motore

| Opzione | Predefinito | Effetto |
|---|---|---|
| `ZZ_ENGINE_MOCK` | OFF | mock al posto del motore reale |
| `ZZ_BUILD_TESTS` / `ZZ_RUN_TESTS_ON_BUILD` | ON / ON | test compilati ed eseguiti a ogni build |
| `ZZ_BUILD_BENCH` | ON (OFF su Android) | compila `zz_bench` |
| `ZZ_ARM_ARCH` | `armv8.2-a+dotprod+fp16` | `-march` di ggml su ARM64 (es. `+i8mm` dopo il benchmark, vedi problemi aperti) |
| `ZZ_GPU_VULKAN` | OFF | backend GPU Vulkan di ggml (richiede Vulkan SDK / `glslc`) |
| `ZZ_GPU_OPENCL` | OFF | backend OpenCL con kernel Adreno |
| `ZZ_NPU_HEXAGON` | OFF | backend NPU Hexagon (richiede Hexagon SDK) |
| `ZZ_ENGINE_DEBUG_LOGS` | OFF | inoltra gli avvisi di whisper/llama su stderr/logcat: solo sviluppo |

`use_gpu=1` nella configurazione attiva GPU/NPU **solo se** il backend è stato compilato; altrimenti
il motore usa la CPU con NEON (ARM64) o AVX2 (x64).

## Modelli

Nomi dei file concordati con gli altri agenti: **`whisper-medium-q5_0.bin`** (formato ggml di
whisper.cpp) e **`phi-4-mini-instruct-q4_k_m.gguf`**. Gli hash SHA-256 li fornisce la piattaforma
(costanti nel codice dell'app, Spec §7) in `zz_engine_config`. `zz_version_string()` riporta le
revisioni di whisper.cpp/llama.cpp e i nomi dei modelli attesi (Spec §13).

## Comportamento del motore reale

- **Verifica SHA-256 (Spec §7):** `zz_engine_load` verifica i due file in parallelo; hash errato →
  `ZZ_ERR_MODEL_INTEGRITY`, file mancante → `ZZ_ERR_MODEL_LOAD`. L'esito è ricordato per percorso,
  dimensione e data di modifica: i ricaricamenti sequenziali non ricalcolano l'hash di file da GB,
  ma un file modificato viene riverificato. SHA-256 accelerato con ARMv8 Crypto (Galaxy S24,
  Snapdragon X) o SHA-NI (x86), con scelta a runtime e ritorno alla versione portabile.
- **Caricamento sequenziale (Spec §7):** con `sequential_loading=1` (predefinito) `zz_engine_load`
  verifica soltanto; Whisper si carica all'inizio della trascrizione scaricando Phi-4-mini, e
  viceversa. Mai due modelli in RAM insieme. Con `0` entrambi restano caricati.
- **Trascrizione (Spec §5.2, §5.5):** lingua da `options.language` (`NULL`/`"auto"` = rilevamento),
  dizionario personale come prompt iniziale di Whisper (max ~200 token), decodifica greedy,
  token non vocali soppressi, frasi "allucinate" tipiche sul silenzio scartate ("Sottotitoli creati
  dalla comunità Amara.org", "[Musica]"...). Audio < 0,1 s o silenzio digitale → `ZZ_ERR_NO_SPEECH`
  senza avviare Whisper.
- **Comandi e scorciatoie (Spec §5.9, §5.11):** applicati sul testo grezzo prima della correzione
  (`zz_common`). Una scorciatoia restituisce l'espansione senza passare dal modello; se rimangono solo
  azioni (es. "annulla") il modello non viene caricato.
- **Correzione (Spec §5.3) e traduzione (Spec §5.12):** prompt di sistema fisso in `zz_prompts.cpp`
  per Fedele/Pulito/Formale, con le regole del §5.3, il dizionario come contesto e un esempio in
  italiano; la traduzione in inglese o spagnolo avviene nella stessa chiamata. Gli a capo dei comandi
  vocali non passano dal modello e i testi lunghi sono divisi a fine frase in pezzi da ≤ 1200
  caratteri (contesto di 3072 token, ~384 MB di cache KV). Il prefisso del prompt (sistema fisso)
  resta nella cache KV tra una chiamata e l'altra: si valuta solo il testo nuovo.
- **Template di chat:** quello ufficiale di Phi-4-mini-instruct,
  `<|system|>…<|end|><|user|>…<|end|><|assistant|>` senza a capo. Non si usa
  `llama_chat_apply_template`, che riconoscerebbe il template come Phi-3 (con a capo). Solo i
  marcatori sono tokenizzati come token speciali: il testo dettato non può iniettare `<|end|>` o simili.
- **Pulizia dell'uscita:** via etichette ("Testo corretto:"), virgolette, tag, note finali; un'uscita
  vuota o molto più lunga dell'ingresso è scartata (Spec §5.3: non aggiungere contenuti).
- **Timeout e fallback (Spec §5.3):** `correction_timeout_ms` (0 → 10 000) copre l'inferenza (prompt
  e generazione), non il caricamento del modello. Timeout, errore del modello o uscita non plausibile
  → `final_text` = testo grezzo con i comandi applicati, `used_fallback=1`, stato `ZZ_OK`.
- **Riscrittura (Spec §5.10):** l'audio contiene l'istruzione; `raw_text` = istruzione trascritta,
  `final_text` = testo riscritto. In caso di fallback la selezione resta **invariata** (inserire
  l'istruzione al suo posto la cancellerebbe).
- **Annullamento (Spec §5.2):** `zz_cancel` interrompe Whisper (abort callback e callback prima
  dell'encoder), il caricamento di Phi-4-mini (callback di avanzamento) e la generazione (abort
  callback di `llama_decode` più controllo a ogni token) → `ZZ_ERR_CANCELLED`.
  `zz_engine_destroy` annulla e attende la fine dell'operazione in corso.
- **Una operazione alla volta:** `zz_dictate`, `zz_rewrite`, `zz_process_text`, `zz_engine_load` e
  `zz_engine_unload` concorrenti → `ZZ_ERR_BUSY`. Dizionario e scorciatoie si possono aggiornare in
  ogni momento: valgono dall'operazione successiva.
- **Log (Spec §10):** i log di whisper.cpp, llama.cpp e ggml sono scartati (callback vuota); le
  stampe del testo di whisper (`print_realtime`, `print_progress`...) sono disattivate. Il motore non
  scrive nulla su disco.
- **Thread:** `n_threads=0` → su Android i soli core grandi (`hw-4`, tra 2 e 6), altrimenti fino a 8.
  Da tarare con `zz_bench --threads`.

## Test (Spec §12)

Eseguiti a ogni build (`ZZ_RUN_TESTS_ON_BUILD`):

| Test | Cosa verifica | Modelli |
|---|---|---|
| `contract` | contratto, uguale per mock e reale; col reale senza modelli verifica gli errori attesi | facoltativi |
| `common` | comandi vocali, scorciatoie, VAD, SHA-256 | no |
| `sha256` | vettori FIPS, accelerato = portabile, percorsi UTF-8 | no |
| `prompts` | template, prompt per modalità/traduzione/riscrittura, pulizia, plausibilità, suddivisione, allucinazioni | no |
| `engine` | motore con backend finti: hash, `NOT_LOADED`, busy, cancel, timeout/fallback, caricamento sequenziale, stadi, scorciatoie, dizionario, riscrittura, WAV | no |
| `integration` | whisper.cpp e llama.cpp veri con modelli minuscoli: cache del prefisso deterministica, abort dentro `llama_decode`, contesto superato, lingua non valida | generati (Python 3 + numpy), altrimenti saltato |
| `models` | qualità minima con i modelli veri (correzione, traduzione, timeout, cancel, dettatura da WAV) | `ZZ_TEST_WHISPER_MODEL`, `ZZ_TEST_LLM_MODEL` (+ `ZZ_TEST_*_SHA256`, `ZZ_TEST_AUDIO_WAV`); altrimenti saltato |

## Benchmark (Spec §11, §11.1)

```sh
zz_bench --audio dettatura-15s.wav --whisper whisper-medium-q5_0.bin --llm phi-4-mini-instruct-q4_k_m.gguf \
         [--whisper-sha HEX --llm-sha HEX] [--mode pulito] [--translate en] [--threads N] [--gpu 0|1] \
         [--parallel-load] [--runs 3] [--rewrite "testo selezionato"] [--print-text]
```

Stampa per ogni esecuzione il tempo totale, di trascrizione e di correzione (con e senza il
caricamento sequenziale dei modelli), l'esito rispetto all'obiettivo di 5 s del §11 e il picco di RAM
del processo e di ciascuna fase. Il testo non viene stampato senza `--print-text` (Spec §10).
WAV richiesto: 16 kHz, PCM 16 bit o float 32 bit (`ffmpeg -i in.m4a -ar 16000 -ac 1 out.wav`).

## Verifiche fatte e da fare

*Aggiunto il 22/10/2026 alle 11:40.*

- Linux x86_64 (GCC 13): compilato senza avvisi con `-Wall -Wextra` nel codice del motore, tutti i
  test superati (modelli veri esclusi: non scaricabili nell'ambiente dell'agente).
- Linux aarch64 (GCC 13 cross, esecuzione con qemu): stessa configurazione ARM di Android
  (`armv8.2-a+dotprod+fp16`), test eseguiti in emulazione, percorso SHA-256 ARMv8 Crypto verificato.
- Windows: compilazione incrociata con llvm-mingw (clang 23): x64 con tutti i test eseguiti in Wine
  (export di `zz_engine.dll` verificati, `zz_bench.exe` eseguito), ARM64 solo compilazione.
  **Non verificati** MSVC/clang-cl, Visual Studio e l'opzione `/MT` (vale solo per toolchain MSVC).
  Il MinGW-w64 11 di Ubuntu 24.04 non compila ggml (intestazioni senza `THREAD_POWER_THROTTLING_STATE`).
- Android NDK: **non verificato** (NDK non scaricabile nell'ambiente dell'agente).
- Prestazioni e qualità sui modelli veri, su Galaxy S24 e Surface Snapdragon: da misurare con
  `zz_bench` e con il test `models`.
