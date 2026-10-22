# Modelli di Zzitto: preparazione, integrità e pacchettizzazione

*Aggiunto il 22/10/2026 alle 11:29. Riferimenti: Spec §3, §7, §7.1, §10, §13. Proprietario: agente E.*

Questa cartella contiene gli script che preparano i due modelli AI inclusi nell'app. I modelli non stanno in git
(`.gitignore`): si rigenerano con questi script sulla macchina del titolare.

| Modello | File finale | Fonte | Licenza |
|---|---|---|---|
| Whisper medium, quantizzazione Q5_0 | `whisper-medium-q5_0.bin` (circa 539 MB) | `ggml-medium-q5_0.bin` pubblicato dal progetto whisper.cpp su [huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp), revisione fissa | MIT (pesi Whisper di OpenAI, MIT) |
| Phi-4-mini-instruct, quantizzazione Q4_K_M | `phi-4-mini-instruct-q4_k_m.gguf` (circa 2,5 GB) | pesi originali di [huggingface.co/microsoft/Phi-4-mini-instruct](https://huggingface.co/microsoft/Phi-4-mini-instruct), revisione fissa, convertiti in GGUF e quantizzati con llama.cpp | MIT |

Microsoft non pubblica un GGUF ufficiale di Phi-4-mini-instruct (verificato il 22/10/2026): le versioni GGUF di terzi
non si usano, perché non sono verificabili. Lo script parte dai pesi originali Microsoft e quantizza in locale.
Per Whisper, la versione Q5_0 già quantizzata è pubblicata dagli autori di whisper.cpp ed è usata di default;
con `--whisper-mode quantize` (bash) o `-WhisperMode quantize` (PowerShell) la si rigenera dal file F16.

## Uso

```bash
# Linux / macOS
tools/models/prepare-models.sh            # tutto
tools/models/prepare-models.sh --strict   # fallisce se un ModelManifest non ha i marcatori
```

```powershell
# Windows (PowerShell 7, Visual Studio 2022 con strumenti C++, Python 3, git, cmake)
pwsh tools/models/prepare-models.ps1
pwsh tools/models/prepare-models.ps1 -Strict
```

Cosa fanno, in ordine:

1. Scaricano i file a una **revisione fissa** di Hugging Face (mai `main`) e ne verificano lo SHA-256 con i valori in `models.conf`.
2. Convertono Phi-4-mini in GGUF (`convert_hf_to_gguf.py`, formato intermedio bf16) e lo quantizzano in Q4_K_M (`llama-quantize`).
   Se esiste `core-native/third_party/llama.cpp` (copia del motore, agente A) usano quella, altrimenti clonano `LLAMA_CPP_REF`.
3. Calcolano SHA-256 e dimensione dei file finali e la **versione dei modelli** (`whisper-medium-q5_0@<8 cifre>+phi-4-mini-q4_k_m@<8 cifre>`), riportata nel changelog (Spec §13).
4. Android (Spec §7): copiano Whisper nell'asset pack `models`, dividono Phi-4-mini in 2 parti negli asset pack
   `models_phi_1` e `models_phi_2`, scrivono `models-index.json` (vedi sotto).
5. Windows (Spec §7.1): copiano i due file interi nella cartella `Models` del pacchetto.
6. Aggiornano gli hash nei file `ModelManifest.kt` e `ModelManifest.cs` (formato sotto).
7. Scrivono `tools/models/models.lock` (da versionare in git: è la traccia di cosa è stato incluso).

Spazio richiesto: circa 25 GB nella cartella di lavoro `build/models-work` (pesi originali 7,7 GB, GGUF bf16 circa 7,7 GB,
pacchetti Python per la conversione). Opzione `--keep-work` / `-KeepWork` per conservare i file intermedi.

Variabili d'ambiente facoltative:

- `HF_TOKEN`: token Hugging Face (non serve per questi repository pubblici). Passato a curl da stdin, mai su disco.
- `ZZ_ALLOW_SOURCE_HASH_MISMATCH=1`: accetta un hash di origine diverso da `models.conf` (solo dopo averlo verificato a mano sulla pagina Hugging Face; aggiornare poi `models.conf`).

## Formato atteso di `ModelManifest.kt` e `ModelManifest.cs` (per gli agenti B e C)

Gli script cercano in tutto il repository i file chiamati esattamente `ModelManifest.kt` e `ModelManifest.cs`
(escluse le cartelle `build`, `bin`, `obj`, `.gradle`, `.git`). In ciascun file sostituiscono:

| Marcatore nel commento | Cosa viene sostituito | Valore iniziale |
|---|---|---|
| `zz-model-hash: <nome file>` | il primo letterale stringa `"..."` dell'assegnazione | `"DA_IMPOSTARE"` |
| `zz-model-size: <nome file>` | il primo letterale intero dopo `=` (il suffisso `L` resta) | `0L` (Kotlin) o `0` (C#) |
| `zz-models-version` | il primo letterale stringa `"..."` dell'assegnazione | `"DA_IMPOSTARE"` |

Regole:

- Il marcatore sta in un commento `//` **sulla riga dell'assegnazione** oppure **sulla riga immediatamente precedente** (righe vuote ignorate).
- `<nome file>` è esattamente `whisper-medium-q5_0.bin` o `phi-4-mini-instruct-q4_k_m.gguf`.
- L'hash è lo SHA-256 del **file intero** in esadecimale minuscolo (64 caratteri), uguale su Android e Windows.
- Un letterale hash viene sostituito solo se vale `DA_IMPOSTARE` o è già un hash di 64 cifre esadecimali.
- `zz-model-size` e `zz-models-version` sono facoltativi; `zz-model-hash` è obbligatorio per entrambi i modelli (con `--strict` lo script fallisce se manca o se resta `DA_IMPOSTARE`).

Esempio Kotlin (modulo `:engine`, agente B):

```kotlin
// Spec §7: hash SHA-256 dei modelli salvati nel codice. Aggiornati da tools/models/prepare-models.*
object ModelManifest {
    const val WHISPER_FILE = "whisper-medium-q5_0.bin"
    // zz-model-hash: whisper-medium-q5_0.bin
    const val WHISPER_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: whisper-medium-q5_0.bin
    const val WHISPER_SIZE_BYTES = 0L

    const val LLM_FILE = "phi-4-mini-instruct-q4_k_m.gguf"
    // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    const val LLM_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: phi-4-mini-instruct-q4_k_m.gguf
    const val LLM_SIZE_BYTES = 0L

    // zz-models-version
    const val MODELS_VERSION = "DA_IMPOSTARE"
}
```

Esempio C# (Windows, agente C):

```csharp
// Spec §7.1: stessi hash del §7. Aggiornati da tools/models/prepare-models.*
public static class ModelManifest
{
    public const string WhisperFile = "whisper-medium-q5_0.bin";
    // zz-model-hash: whisper-medium-q5_0.bin
    public const string WhisperSha256 = "DA_IMPOSTARE";

    public const string LlmFile = "phi-4-mini-instruct-q4_k_m.gguf";
    // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    public const string LlmSha256 = "DA_IMPOSTARE";

    // zz-models-version
    public const string ModelsVersion = "DA_IMPOSTARE";
}
```

Finché un hash vale `DA_IMPOSTARE`, l'app deve trattare il modello come non verificato e non caricarlo
(`ZZ_ERR_MODEL_INTEGRITY`, contratto del motore), tranne che con il motore finto.

## Cartella `Models` di Windows (agente C)

Lo script copia i modelli nella cartella che contiene un file vuoto chiamato `.zz-models-dir` sotto `windows/`.
Se non lo trova usa `windows/Models` e stampa un avviso. L'agente C crea il marcatore nella cartella che il progetto
di pacchettizzazione include come contenuto del pacchetto MSIX (Spec §7.1).

## Divisione di Phi-4-mini per Google Play (Spec §7)

Limiti di Google Play verificati il 22/10/2026 su
[support.google.com/googleplay/android-developer/answer/9859372](https://support.google.com/googleplay/android-developer/answer/9859372):
**1,5 GB per singolo asset pack** e **4 GB in totale per moduli e asset pack install-time** (dimensioni di download compresse).
Il file GGUF quantizzato è quasi incomprimibile, quindi i 2,5 GB di Phi-4-mini superano il limite del singolo pack.

Soluzione: tre asset pack install-time.

| Asset pack (packName) | Modulo Gradle | Contenuto in `assets/models/` |
|---|---|---|
| `models` | `:models` (`android/models`) | `whisper-medium-q5_0.bin`, `models-index.json` |
| `models_phi_1` | `:models_phi_1` (`android/models/phi_1`) | `phi-4-mini-instruct-q4_k_m.gguf.1.part` |
| `models_phi_2` | `:models_phi_2` (`android/models/phi_2`) | `phi-4-mini-instruct-q4_k_m.gguf.2.part` |

Le parti sono una divisione binaria semplice: concatenandole in ordine (1, 2) si ottiene il file originale, con lo
stesso SHA-256 di Windows. Tutte le parti tranne l'ultima hanno la stessa dimensione (multiplo di 1 MiB).
La ricomposizione al primo avvio spetta all'app Android (agente B): vedi `docs/release-guide.md`, sezione
"Istruzioni per gli agenti". `modeltool.py join` implementa lo stesso algoritmo ed è coperto dai test.

`models-index.json` (schema 1):

```json
{
  "schema": 1,
  "spec": "Spec §7",
  "modelsVersion": "whisper-medium-q5_0@xxxxxxxx+phi-4-mini-q4_k_m@yyyyyyyy",
  "files": [
    { "name": "whisper-medium-q5_0.bin", "sha256": "…", "size": 539212467, "parts": [] },
    { "name": "phi-4-mini-instruct-q4_k_m.gguf", "sha256": "…", "size": 2491874272,
      "parts": [
        { "name": "phi-4-mini-instruct-q4_k_m.gguf.1.part", "pack": "models_phi_1", "sha256": "…", "size": 1246758912 },
        { "name": "phi-4-mini-instruct-q4_k_m.gguf.2.part", "pack": "models_phi_2", "sha256": "…", "size": 1245115360 }
      ] }
  ]
}
```

I numeri dell'esempio sono indicativi. L'indice serve per l'elenco delle parti, la barra di avanzamento e la verifica dello
spazio libero; **l'hash di riferimento resta quello nel codice** (`ModelManifest`), come chiede il §7.

## Test

```bash
python3 -m unittest discover -s tools/tests -v
```

## Fonti consultate (22/10/2026)

- Limiti di dimensione Google Play: https://support.google.com/googleplay/android-developer/answer/9859372
- Play Asset Delivery: https://developer.android.com/guide/playcore/asset-delivery
- whisper.cpp (README, quantizzazione): https://github.com/ggml-org/whisper.cpp
- Modelli whisper.cpp: https://huggingface.co/ggerganov/whisper.cpp
- Phi-4-mini-instruct (file, licenza MIT, NOTICE): https://huggingface.co/microsoft/Phi-4-mini-instruct
- llama.cpp, conversione e quantizzazione: https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md
