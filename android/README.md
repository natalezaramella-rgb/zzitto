# Zzitto per Android

*Aggiunto il 22/10/2026 alle 12:00. Proprietario: agente B (moduli `:app`, `:engine`, `:audio`, `:dictation`). Riferimenti: Spec §2.5, §3, §4, §5, §7, §10, §12.*

## Moduli

| Modulo | Contenuto | Spec |
|---|---|---|
| `:engine` | Ponte JNI verso `core-native` (CMake), wrapper `ZzEngine` con coroutine, VAD, `ModelManifest`, installazione dei modelli | §4.3, §7 |
| `:audio` | Cattura microfono 16 kHz mono float con `AudioRecord`, stop col VAD, durata massima, audio solo in memoria | §5.2, §10 |
| `:dictation` | Servizio di accessibilità, icona flottante, menu a pressione lunga, macchina a stati della dettatura, inserimento del testo, azioni vocali, impostazioni (DataStore) | §5.1-bis, §5.2–§5.12 |
| `:app` | Application Hilt, schermate Compose (home, tour, impostazioni, dizionario, scorciatoie, cronologia, licenze, acquisto), database Room + SQLCipher | §5.5–§5.8, §8.1, §10 |
| `:core` | Interfacce condivise (`LicenseGate`) — orchestratore | §16.1 |
| `:licensing` | Implementazione di `LicenseGate` — agente D | §8.1 |
| `:models`, `:models_phi_1`, `:models_phi_2` | Asset pack install-time con i modelli — agente E | §7 |

## Compilare

Requisiti: Android Studio recente, JDK 17, Android SDK 36, NDK (quello predefinito dell'AGP) e CMake 3.22.1 dall'SDK Manager.

```bash
cd android
./gradlew assembleDebug check      # build debug + test unitari (JUnit 5) + lint
./gradlew connectedCheck           # test UI Compose su un dispositivo collegato
```

- I test unitari girano anche prima di ogni `assembleDebug`, `assembleRelease` e `bundleRelease` (Spec §12).
- **Debug** usa il motore finto (`-DZZ_ENGINE_MOCK=ON`, `BuildConfig.ENGINE_MOCK = true`): non servono i modelli
  veri e gli hash `DA_IMPOSTARE` sono tollerati.
- **Release** usa il motore reale (`-DZZ_ENGINE_MOCK=OFF`): finché l'agente A non fornisce
  `core-native/cmake/real_engine.cmake`, la configurazione CMake della release si ferma con un errore voluto.
  In release gli hash dei modelli sono obbligatori (`ModelInstaller.requireHashes`).
- Versione letta da `tools/release/version.properties` (Spec §13). Nessun `signingConfig`: firma con gli script dell'agente E.
- Solo ABI `arm64-v8a` (Spec §2.1).

Il codice Kotlin è stato scritto senza poterlo compilare (nessun Android SDK nell'ambiente degli agenti). Il ponte
JNI C++, il wrapper `ZzEngine` e la logica pura (macchina a stati, controller, inserimento, installazione modelli,
cattura audio, repository, cifratura) sono stati compilati ed eseguiti sull'host con kotlinc e JDK 21 tramite stub
minimi delle API Android: 103 test superati (esclusi `SettingsTest`, che usa DataStore, e i test UI). La parte Android (servizio, overlay, Compose, Room, Hilt) va
verificata alla prima build in Android Studio.

## Funzionamento

### Icona flottante e servizio di accessibilità (Spec §5.1-bis)

- `ZzittoAccessibilityService` ascolta solo `typeViewFocused`, `typeViewTextSelectionChanged`, `typeWindowStateChanged`
  e legge il campo con focus (`findFocus(FOCUS_INPUT)`). I campi password sono esclusi.
- L'icona è una finestra `TYPE_ACCESSIBILITY_OVERLAY` non focalizzabile: **non serve `SYSTEM_ALERT_WINDOW`** e la
  tastiera dell'utente resta attiva. Si trascina e la posizione è salvata in DataStore.
- Il microfono funziona anche con l'app in background senza foreground service: il sistema lega i servizi di
  accessibilità con `BIND_INCLUDE_CAPABILITIES` (verificato nel sorgente AOSP di `AccessibilityServiceConnection`).
- Stato solo con colore e animazione (Spec §2.5): blu inattivo, rosso ascolto (alone col volume), arancione
  trascrizione, viola correzione, verde inserimento, rosso scuro errore, grigio prova scaduta.
- Pressione lunga: annulla (dettatura in corso o ultimo inserimento), lingua, traduzione EN/ES, modalità, impostazioni.
- Gli avvisi (testo non corretto, testo negli appunti, permesso mancante) sono Toast brevi.

### Ciclo di dettatura (Spec §5.2)

`DictationController` possiede l'unica macchina a stati del progetto (`DictationStateMachine`):
Inattivo → Ascolto → Trascrizione → Correzione → Inserimento → Inattivo, Errore da ogni stato, annullamento sempre.

1. Tap: verifica `LicenseGate.canDictate()` (prova scaduta → schermata di acquisto, Spec §8.1).
2. Se nel campo c'è una selezione si fa la riscrittura (Spec §5.10), altrimenti la dettatura.
3. L'ascolto parte subito; i modelli si caricano in parallelo (Spec §11, < 300 ms).
4. Stop con secondo tap, VAD del motore o durata massima; audio azzerato dopo la trascrizione (Spec §10).
5. Inserimento: `ACTION_SET_TEXT` con cursore/selezione e verifica → incolla dagli appunti con ripristino →
   testo lasciato negli appunti con avviso. Poi le azioni `ZZ_ACTION_*` (annulla, cancella frase, invio con `ACTION_IME_ENTER`).
6. Cronologia (se attiva) e scaricamento dei modelli dopo l'inattività configurata (Spec §4.2).

### Modelli (Spec §7)

`ModelInstaller` ricompone e copia i modelli dagli asset pack in `noBackupFilesDir/models` al primo avvio (tour) o alla
prima dettatura: controllo dello spazio libero (dimensioni + 300 MB), SHA-256 calcolato durante la copia su file
`.tmp`, rinomina solo se coincide con `ModelManifest`, ricopia quando cambia `MODELS_VERSION`. Phi-4-mini arriva in
due parti (`.1.part`, `.2.part`) elencate in `models/models-index.json`. Gli hash e le dimensioni in
`ModelManifest.kt` li scrive `tools/models/prepare-models.*` (agente E) tramite i commenti marcatore.

### Dati e privacy (Spec §5.6, §10)

- Cronologia, dizionario e scorciatoie in Room cifrato con SQLCipher; la passphrase casuale (32 byte) è cifrata
  AES-256-GCM con una chiave non esportabile dell'Android Keystore e salvata in `noBackupFilesDir`.
- `PRAGMA secure_delete = ON`; `allowBackup="false"` e regole di estrazione che escludono tutto.
- `FLAG_SECURE` sulla schermata della cronologia. Nessun testo dettato nei log o nei `toString()`.
- Permessi: `RECORD_AUDIO` (`:audio`), `VIBRATE` (`:dictation`), `com.android.vending.BILLING` (`:licensing`). Nessun `INTERNET`.

### Licenze open source

Il build copia `THIRD_PARTY_LICENSES.md` (radice del repository, agente E) negli asset generati
(task `copyThirdPartyLicenses`); se manca, la schermata lo segnala.

## Revisione di compilabilità del 22/10/2026

*Aggiunto il 22/10/2026 alle 12:36 (revisione indipendente, prima build in Android Studio).*

### Correzioni

1. **Toolchain JDK 17** (`settings.gradle.kts`). Tutti i moduli dichiarano `kotlin { jvmToolchain(17) }`, ma Android Studio
   include solo il JBR 21 e nessun resolver di toolchain era configurato: senza un JDK 17 installato la prima build si
   sarebbe fermata con "No matching toolchains found". Aggiunto il plugin `org.gradle.toolchains.foojay-resolver-convention`
   1.0.0, che scarica il JDK 17 quando manca. Nessun cambiamento funzionale.

### Verifiche fatte senza Android SDK

- Ponte JNI (`engine/src/main/cpp`) compilato contro `core-native` con `-DZZ_ENGINE_MOCK=ON` con gcc e clang, anche con
  `-Wl,--no-undefined` (come fa l'NDK): nessun errore né avviso. I 20 simboli `Java_..._ZzNative_*` corrispondono alle
  dichiarazioni `external` di `ZzNative.kt`.
- Kotlin 2.2.0 (kotlinc) su tutti i sorgenti non Compose dei moduli `:core`, `:engine`, `:audio`, `:dictation`, `:licensing`
  e `:app` (servizio di accessibilità, overlay, inserimento, Billing 8, Block Store, Keystore, Room/SQLCipher, Hilt, DataStore),
  compilati contro stub Java delle API Android e delle librerie: nessun errore. I 103 test della logica pura passano,
  i test di `:licensing` compilano.
- Risorse: ogni `R.string`/`R.plurals`/`R.color`/`R.drawable` usato esiste; traduzioni `values`/`values-en` allineate; XML validi.
- Versioni del catalogo coerenti tra loro (AGP 8.11.1 + Gradle 8.14.3, Kotlin 2.2.0 + KSP 2.2.0-2.0.2, Hilt 2.56.2).

### Da verificare alla prima build

- **Schermate Compose** (`app/.../ui/*Screens.kt`, `HomeAndTour.kt`, `Components.kt`, `MainActivity.kt`) e test UI: controllate
  solo a lettura (firme Material 3 della BOM 2025.06.01), non compilate.
- **Billing 8**: `ProductDetails.oneTimePurchaseOfferDetailsList` e `OneTimePurchaseOfferDetails.offerToken` vanno confermati
  sulla 8.0.0 (`PlayBillingPurchaseVerifier.kt`).
- **Solo arm64**: su un emulatore x86_64 `libzz_engine_jni.so` e `libsqlcipher.so` non ci sono e il servizio/il database vanno
  in crash. Usare un dispositivo arm64 (o un emulatore arm64).
- **Modelli in debug**: gli asset pack install-time non entrano nell'APK "Default APK" di Android Studio e i modelli non sono in
  git. Senza `tools/models/prepare-models.*` e la configurazione di esecuzione "APK from app bundle", `ModelInstaller` segnala
  `MISSING_ASSET` anche con il motore finto.
- **Lint** (`./gradlew check`): non eseguito; eventuali errori di lint non bloccano `assembleDebug`.

## Dettatura in streaming (API 2 del motore)

*Aggiunto il 22/10/2026 alle 16:20 (agente B, branch `agent/b2-android-stream`).*

Obiettivo: ridurre la latenza percepita (Spec §11). Il motore trascrive mentre l'utente parla; allo stop resta da
trascrivere solo l'ultimo pezzo. Contratto: `zz_stream_begin/feed/finish/abort` in `core-native/include/zz_engine.h`.

### Modifiche

- **`:engine`**: binding JNI `streamBegin`, `streamFeed`, `streamFinish`, `streamAbort` (`zz_engine_jni.cpp`, `ZzNative.kt`).
  `streamFeed` riceve `FloatArray` + offset/lunghezza e usa `GetPrimitiveArrayCritical` (nessuna copia intermedia; se la VM
  copia, la copia viene azzerata prima del rilascio con `JNI_ABORT`). `streamFinish` libera sempre il flusso, anche con
  argomenti non validi (in quel caso chiama `zz_stream_abort`). Nuova API Kotlin `ZzEngine.beginStream(options): ZzStream`
  con `feed` (non bloccante, thread di registrazione), `finish` (sospesa, thread del motore, `zz_cancel` se annullata) e
  `abort` (non bloccante, idempotente; durante `finish` chiama `zz_cancel`). Un lock in `ZzStream` impedisce che `feed`
  si sovrapponga a `finish`/`abort` sullo stesso handle. `begin` non viene interrotto a metà: se la coroutine è stata
  annullata il flusso appena aperto viene annullato subito; `close()` annulla il flusso aperto prima di distruggere
  l'istanza. `ZzEngine.EXPECTED_API_VERSION` = 2: una libreria nativa con API 1 viene rifiutata.
- **`:audio`**: `AudioCaptureSession.record(onChunk: PcmChunkSink?)` consegna ogni blocco letto (anche quello che fa
  scattare lo stop) al ricevitore, oltre che al buffer e al VAD. Il blocco è il buffer di lettura riusato e azzerato
  (Spec §10); un'eccezione del ricevitore non ferma la registrazione.
- **`:dictation`**: `DictationEngine.beginStream` e `DictationStream` (adattatori `ZzDictationEngine`/`ZzDictationStream`).
  Nel ciclo (Spec §5.2) all'avvio dell'Ascolto si caricano i modelli (`prepare`) e poi si apre il flusso, in parallelo
  alla registrazione; `StreamFeeder` inoltra i blocchi (quelli arrivati prima dell'apertura vengono copiati in un
  arretrato, inoltrati in ordine e azzerati). Allo stop: `finish`; annullamento, errore o assenza di parlato: `abort`.
  Fallback a `zz_dictate` sull'audio registrato se il flusso non si apre o se il motore rifiuta un blocco; un errore di
  `finish` è invece un errore normale (nessun fallback). La riscrittura (Spec §5.10) resta su `zz_rewrite`. Gli stati
  della macchina non cambiano.

### Test

`ZzStreamTest` (engine, 14 test), 4 nuovi test in `AudioCaptureSessionTest`, 10 nuovi test in `DictationControllerTest`
(feed durante l'ascolto dal thread di registrazione, arretrato, finish allo stop, abort all'annullamento, annullamento
durante finish, fallback per errore all'apertura e per blocco rifiutato, errori di finish, assenza di parlato, errore
dei modelli), `ZzDictationStreamTest` (adattatori, MockK).

### Verifiche fatte senza Android SDK

- JNI compilato con gcc e clang (`-Wall -Wextra -Werror`, `-Wl,--no-undefined`) contro il mock `core-native` con lo
  streaming ancora in sviluppo nel worktree dell'agente A (copia del 22/10/2026 16:20). Prova da JVM host (anche con
  `-Xcheck:jni`): begin, feed da un thread "zz-audio", finish con fasi, `BUSY` di `zz_dictate` a flusso aperto,
  abort, nuovo flusso dopo abort, `close()` con flusso aperto.
- Kotlin 2.2.0 su `:engine`, `:audio`, `:dictation`, `:core` (senza i file Android): i test della logica pura passano
  (engine 47, audio 17, dictation 56). `ZzDictationStreamTest` (MockK) solo compilato contro uno stub delle firme MockK.

### Da fare fuori da `android/`

- Registrare la decisione nel capitolo 14 della specifica (streaming su Android, fallback a `zz_dictate`, riscrittura
  su `zz_rewrite`): spetta all'orchestratore.
- Il build Android richiede il mock/motore con API 2 dell'agente A: con la libreria API 1 il link di
  `libzz_engine_jni.so` fallisce (simboli `zz_stream_*` mancanti).
