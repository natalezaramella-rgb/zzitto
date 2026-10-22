# Licenze di terze parti di Zzitto

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §2.2 (solo MIT, Apache 2.0, BSD; licenze mostrate nella schermata
"Licenze open source"), §3 (stack), §10 (gestione delle dipendenze). Proprietario: agente E.*

Questo file elenca tutti i componenti di terze parti previsti dalla specifica, con licenza, uso e fonte.
È la base della schermata "Licenze open source" dell'app su Android e Windows (agenti B e C) e va aggiornato a
ogni nuova dipendenza. Le licenze sono state verificate il 22/10/2026 sulle fonti indicate; prima di ogni rilascio
vanno ricontrollate (procedura in `docs/compliance/allegato-conformita-iso.md`, controllo A.8.8).

Legenda dell'esito rispetto al §2.2:

- **OK**: MIT, Apache 2.0 o BSD.
- **OK-T**: solo per test o build, non distribuito nell'app.
- **SEGNALATO**: licenza non elencata nel §2.2; spiegazione e proposta nella sezione "Licenze da segnalare".

## 1. Motore AI (core-native, Android e Windows)

| Componente | Uso | Licenza | Esito | Fonte |
|---|---|---|---|---|
| whisper.cpp | Riconoscimento vocale (Spec §3) | MIT, © The ggml authors | OK | https://github.com/ggml-org/whisper.cpp |
| llama.cpp | Esecuzione di Phi-4-mini (Spec §3) | MIT, © The ggml authors | OK | https://github.com/ggml-org/llama.cpp |
| ggml | Libreria tensoriale usata da whisper.cpp e llama.cpp | MIT, © The ggml authors | OK | https://github.com/ggml-org/ggml |
| Librerie incluse in llama.cpp/whisper.cpp usate dal motore (es. nlohmann/json, minja, stb, se l'agente A le compila) | Parsing, modelli di chat | MIT | OK | cartelle `vendor/` dei due progetti |
| Modello Whisper medium (pesi OpenAI), conversione ggml Q5_0 | Modello di trascrizione | MIT, © 2022 OpenAI | OK | https://github.com/openai/whisper, https://huggingface.co/ggerganov/whisper.cpp |
| Phi-4-mini-instruct (pesi Microsoft), quantizzato Q4_K_M | Modello di correzione, riscrittura, traduzione | MIT, © Microsoft Corporation | OK | https://huggingface.co/microsoft/Phi-4-mini-instruct |

Note sui modelli:

- Il `NOTICE.md` di Phi-4-mini-instruct cita flash-attention (BSD 3-Clause), usato per l'addestramento; non è incluso nei pesi
  né nell'app. Il testo è riportato comunque nella sezione 6 per completezza.
- Il README di Phi-4-mini rimanda alle linee guida sui marchi Microsoft: nelle schede degli store si può scrivere in modo
  descrittivo "usa il modello Phi-4-mini di Microsoft", ma non usare "Phi" o loghi Microsoft come marchio di Zzitto né
  suggerire un'approvazione di Microsoft.

## 2. Android

| Componente | Uso | Licenza | Esito | Fonte |
|---|---|---|---|---|
| Kotlin, kotlin-stdlib | Linguaggio | Apache 2.0 | OK | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | Concorrenza | Apache 2.0 | OK | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Core, Activity, Lifecycle (runtime, service, viewmodel) | Base app, servizi | Apache 2.0 | OK | https://developer.android.com/jetpack/androidx |
| Jetpack Compose (BOM, UI, Material 3, tooling) | Interfaccia | Apache 2.0 | OK | https://developer.android.com/jetpack/compose |
| Room | Persistenza (cronologia, dizionario, scorciatoie) | Apache 2.0 | OK | https://developer.android.com/jetpack/androidx/releases/room |
| AndroidX SQLite | Ponte Room/SQLCipher | Apache 2.0 | OK | https://developer.android.com/jetpack/androidx/releases/sqlite |
| DataStore Preferences | Impostazioni | Apache 2.0 | OK | https://developer.android.com/topic/libraries/architecture/datastore |
| Hilt / Dagger, javax.inject | Dependency injection | Apache 2.0 | OK | https://github.com/google/dagger |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | Cifratura del database (Spec §10) | BSD 3-Clause (stile BSD), © ZETETIC LLC | OK | https://github.com/sqlcipher/sqlcipher-android |
| SQLite (incluso in SQLCipher) | Motore database | Pubblico dominio | SEGNALATO (1) | https://sqlite.org/copyright.html |
| LibTomCrypt (fornitore crittografico predefinito di SQLCipher for Android) | Primitive crittografiche | Unlicense / pubblico dominio | SEGNALATO (1) | https://github.com/libtom/libtomcrypt |
| Google Play Billing Library (`com.android.billingclient:billing-ktx`) | Acquisto in-app `zzitto_unlock` (Spec §8.1) | Android Software Development Kit License Agreement | SEGNALATO (2) | https://developer.android.com/google/play/billing/release-notes, https://developer.android.com/studio/terms |
| Google Play services Block Store (`play-services-auth-blockstore`) e dipendenze `play-services-base`, `-basement`, `-tasks` | Data di inizio prova (Spec §8.1) | Android Software Development Kit License Agreement | SEGNALATO (2) | https://developer.android.com/identity/block-store |
| Android Gradle Plugin, Gradle | Build (non distribuiti) | Apache 2.0 | OK-T | https://gradle.org |
| JUnit 5 (Jupiter, Platform) | Test | Eclipse Public License 2.0 | OK-T (3) | https://junit.org/junit5 |
| JUnit 4 | Test strumentali | Eclipse Public License 1.0 | OK-T (3) | https://junit.org/junit4 |
| MockK | Test | Apache 2.0 | OK-T | https://github.com/mockk/mockk |
| AndroidX Test, Espresso, Compose UI Test | Test | Apache 2.0 | OK-T | https://developer.android.com/training/testing |

## 3. Windows

| Componente | Uso | Licenza | Esito | Fonte |
|---|---|---|---|---|
| .NET runtime e librerie (`net8.0-windows`) | Piattaforma dell'app | MIT | OK | https://github.com/dotnet/runtime |
| C#/WinRT (proiezioni Windows) | Accesso alle API Windows | MIT | OK | https://github.com/microsoft/CsWinRT |
| Windows App SDK / WinUI 3 (`Microsoft.WindowsAppSDK`) | Interfaccia, runtime dell'app | Codice sorgente MIT; pacchetto NuGet distribuito con le **Microsoft Software License Terms** (file ridistribuibili ammessi) | SEGNALATO (4) | https://github.com/microsoft/WindowsAppSDK, https://www.nuget.org/packages/Microsoft.WindowsAppSDK |
| Windows SDK: `Windows.Services.Store` (`StoreContext`), UI Automation, WASAPI, SendInput | API del sistema operativo (licenza, inserimento testo, audio) | Parte di Windows; nessun codice ridistribuito | OK | https://learn.microsoft.com/windows/uwp/monetize/in-app-purchases-and-trials |
| Microsoft Visual C++ Runtime (se `zz_engine.dll` è compilata con runtime dinamico) | Runtime C++ del motore | Microsoft Software License Terms (Visual Studio, file ridistribuibili) | SEGNALATO (4) | https://learn.microsoft.com/cpp/windows/redistributing-visual-cpp-files |
| Altre librerie scelte dall'agente C (es. CommunityToolkit, libreria per l'icona nell'area di notifica) | Interfaccia | Da registrare qui; ammesse solo MIT, Apache 2.0, BSD | Da confermare | — |
| Framework di test .NET scelto dall'agente C (xUnit, MSTest o NUnit) | Test | Apache 2.0 / MIT | OK-T | — |

## 4. Strumenti usati solo per preparare i modelli (non distribuiti)

| Componente | Uso | Licenza | Esito |
|---|---|---|---|
| `convert_hf_to_gguf.py` e `llama-quantize` (llama.cpp) | Conversione e quantizzazione di Phi-4-mini | MIT | OK-T |
| Dipendenze Python della conversione (PyTorch, transformers, sentencepiece, numpy, gguf-py, safetensors) | Lettura dei pesi originali | BSD / Apache 2.0 / MIT | OK-T |
| `quantize` di whisper.cpp | Quantizzazione facoltativa di Whisper | MIT | OK-T |

## 5. Licenze da segnalare

1. **Pubblico dominio (SQLite, LibTomCrypt).** Non sono MIT, Apache o BSD, ma sono più permissive: nessun obbligo
   e nessun limite alla vendita. Proposta: accettarle e registrare l'eccezione nel capitolo 14. Verificare alla prima build
   che l'AAR di SQLCipher usi davvero LibTomCrypt (README del progetto) e non OpenSSL; se usasse OpenSSL 3, la licenza
   sarebbe Apache 2.0 (OK).
2. **Google Play Billing e Google Play services (Block Store): Android SDK License Agreement.** Sono librerie proprietarie
   di Google, con termini propri e non open source. Sono però l'unico modo ammesso da Google Play per vendere lo sblocco
   (la Billing Library è obbligatoria per i contenuti digitali venduti su Play) e Block Store è la soluzione scelta dal
   titolare per la data di prova (§8.1, capitolo 14). I termini consentono l'uso in app commerciali distribuite su Google Play.
   Il §2.2 vieta modelli o librerie con licenze proprietarie: va registrata un'eccezione esplicita nel capitolo 14,
   limitata a questi due componenti di piattaforma. Non vanno elencati come "open source" nella schermata delle licenze,
   ma in una sezione "Componenti di Google Play".
3. **JUnit (EPL).** Solo test, non distribuito; già previsto dal §3 ("EPL / Apache 2.0"). Nessuna azione.
4. **Windows App SDK e runtime Visual C++: Microsoft Software License Terms.** Il codice sorgente del Windows App SDK è MIT,
   ma i binari NuGet sono distribuiti con termini Microsoft che permettono di ridistribuire i file del pacchetto nell'app.
   Il §2.4 cita WinUI 3 "licenze MIT": la descrizione va corretta. Condizioni rilevanti: l'app deve aggiungere funzioni
   proprie significative (vero), non si può suggerire un'approvazione di Microsoft, gli utenti finali devono accettare
   termini che proteggano il codice Microsoft almeno quanto la licenza (coperto dai termini standard del Microsoft Store).
   Proposta: eccezione registrata nel capitolo 14 per i componenti di piattaforma Microsoft. Per il runtime Visual C++
   l'agente A può evitare il problema compilando `zz_engine.dll` con runtime statico (`/MT`).

Nessun componente previsto usa licenze copyleft (GPL, LGPL, AGPL) o licenze di modelli restrittive (Gemma Terms of Use,
Llama Community License, Gemini Nano), vietate dal §2.2.

## 6. Testi delle licenze

### MIT

Si applica a whisper.cpp, llama.cpp, ggml (© 2023-2026 The ggml authors), Whisper (© 2022 OpenAI),
Phi-4-mini-instruct (© Microsoft Corporation), .NET, C#/WinRT e alle altre voci MIT sopra, ciascuna con il proprio
avviso di copyright.

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Apache License 2.0

Si applica a Kotlin, kotlinx.coroutines, AndroidX, Jetpack Compose, Room, DataStore, Hilt/Dagger e alle altre voci
Apache 2.0. Testo completo: https://www.apache.org/licenses/LICENSE-2.0.txt — l'app deve includerlo per intero nella
schermata delle licenze, insieme agli eventuali file `NOTICE` dei componenti (AndroidX non ne ha di specifici).

### SQLCipher (BSD 3-Clause, ZETETIC LLC)

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Il testo va confrontato, prima del rilascio, con il file `LICENSE` della versione di SQLCipher effettivamente inclusa
(https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE); Zetetic chiede di mostrare licenza e copyright
in modo ben visibile (https://www.zetetic.net/sqlcipher/open-source/).

### flash-attention (BSD 3-Clause), citato nel NOTICE di Phi-4-mini-instruct

```
Copyright (c) 2022, the respective contributors, as shown by the AUTHORS file.
All rights reserved.
(testo BSD 3-Clause standard: https://github.com/Dao-AILab/flash-attention/blob/main/LICENSE)
```

### Android Software Development Kit License Agreement

https://developer.android.com/studio/terms (Google Play Billing Library, Google Play services).

### Microsoft Software License Terms – Windows App SDK

https://www.nuget.org/packages/Microsoft.WindowsAppSDK (link "License Info" della versione usata).

## Fonti consultate (22/10/2026)

- Licenza e NOTICE di Phi-4-mini-instruct: https://huggingface.co/microsoft/Phi-4-mini-instruct/blob/main/LICENSE, `NOTICE.md`, `README.md`
- Licenza di whisper.cpp e dei modelli ggml: https://github.com/ggml-org/whisper.cpp, https://huggingface.co/ggerganov/whisper.cpp
- Licenza della Play Billing Library (note di rilascio 1.2): https://developer.android.com/google/play/billing/release-notes
- Licenza di SQLCipher: https://github.com/sqlcipher/sqlcipher-android, https://www.zetetic.net/sqlcipher/open-source/
- Licenza del Windows App SDK 2.5.1: https://www.nuget.org/packages/Microsoft.WindowsAppSDK/2.5.1/License
