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
