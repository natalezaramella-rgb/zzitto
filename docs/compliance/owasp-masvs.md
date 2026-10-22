# Checklist OWASP MASVS v2 – Zzitto (Android) e note per Windows

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §5.1-bis, §7, §8.1, §10, §12, §16.1 (fase 6). Proprietario: agente E.*

Il §10 chiede di gestire gli asset secondo OWASP MASVS. Questa checklist elenca i controlli di MASVS v2
(https://mas.owasp.org/MASVS/), cosa si richiede a Zzitto, chi lo realizza e come si verifica. Lo stato va
aggiornato prima della pubblicazione in produzione (gate della fase 6).

Stato: **Previsto** (requisito definito, da verificare sull'app compilata), **OK** (verificato), **N/A** (motivato).
Profilo scelto: MASVS-L1 + controlli di privacy e di integrità dei modelli. MASVS-R (resilienza) parziale: l'app non
contiene segreti lato server da proteggere, ma il controllo della licenza è un obiettivo di manomissione.

## Android

| Controllo | Requisito per Zzitto | Responsabile | Verifica | Stato |
|---|---|---|---|---|
| **MASVS-STORAGE-1** Archiviazione sicura dei dati sensibili | Cronologia, dizionario, scorciatoie in Room + SQLCipher; chiave casuale cifrata con chiave AES dell'Android Keystore; stato di sblocco cifrato; audio mai su disco | B, D | Ispezione di `/data/data/com.zaramella.zzitto` su dispositivo di test: nessun file in chiaro con testo dettato; test unitari della cifratura | Previsto |
| **MASVS-STORAGE-2** Prevenzione delle fughe | `android:allowBackup="false"` o regole `dataExtractionRules` che escludono database e preferenze dal backup cloud e dal trasferimento tra dispositivi; nessun testo nei log (`Log`, eccezioni, crash); nessun testo in notifiche; `FLAG_SECURE` sulle schermate di cronologia; appunti ripristinati subito dopo l'inserimento di ripiego e testo marcato come sensibile (`ClipDescription.EXTRA_IS_SENSITIVE`) | B | Lettura di `logcat` durante una dettatura; revisione del manifest; prova di backup con `adb shell bmgr` | Previsto |
| **MASVS-CRYPTO-1** Crittografia forte e corretta | SQLCipher 4 (AES-256, PBKDF2-HMAC-SHA512 o chiave grezza da Keystore); SHA-256 per i modelli; nessun algoritmo proprietario | B, A | Revisione del codice | Previsto |
| **MASVS-CRYPTO-2** Gestione delle chiavi | Chiavi generate sul dispositivo, mai nel codice; Keystore con `setUserAuthenticationRequired(false)` (servizio in background) e, se disponibile, StrongBox | B, D | Revisione; ricerca di chiavi nel bytecode (`aabcheck.py` + `apkanalyzer`) | Previsto |
| **MASVS-AUTH-1** Autenticazione e autorizzazione | N/A: nessun account né login (Spec §6) | — | — | N/A |
| **MASVS-AUTH-2** Autenticazione locale | N/A: nessuno sblocco biometrico previsto | — | — | N/A |
| **MASVS-AUTH-3** Operazioni sensibili | Acquisto gestito dalla finestra di Google Play (autenticazione dello store) | D | Prova di acquisto in test interno | Previsto |
| **MASVS-NETWORK-1** Traffico sicuro | Solo traffico delle librerie Google (TLS); `networkSecurityConfig` con `cleartextTrafficPermitted="false"`; nessun endpoint proprio | B, D | Revisione del manifest; analisi del traffico con un proxy durante la prova | Previsto |
| **MASVS-NETWORK-2** Pinning delle identità | N/A: nessun endpoint sotto il controllo dello sviluppatore | — | — | N/A |
| **MASVS-PLATFORM-1** IPC sicuro | Servizio di accessibilità protetto da `BIND_ACCESSIBILITY_SERVICE`; nessuna Activity/Service/Receiver/Provider esportato senza necessità; nessun `PendingIntent` mutabile | B | `apkanalyzer manifest print`; revisione | Previsto |
| **MASVS-PLATFORM-2** WebView | Nessuna WebView (la guida è in Compose); se servisse: JavaScript disattivato, solo contenuti locali | B | Ricerca di `WebView` nel codice | Previsto |
| **MASVS-PLATFORM-3** Interfaccia sicura | Informativa sull'accessibilità prima del consenso; overlay che non copre finestre di sistema sensibili; `FLAG_SECURE` sulla cronologia; nessun dato nelle schermate recenti della cronologia | B | Prova manuale; video della dichiarazione | Previsto |
| **MASVS-CODE-1** Piattaforma aggiornata | `minSdk 34`, `targetSdk` all'ultima versione richiesta da Google Play (Spec §2.1) | B | `build.gradle.kts` | Previsto |
| **MASVS-CODE-2** Aggiornamenti | Aggiornamenti tramite Google Play; facoltativo In-App Updates in futuro | B | — | Previsto |
| **MASVS-CODE-3** Componenti senza vulnerabilità note | Procedura dell'allegato ISO §3.5 prima di ogni rilascio; versioni fissate | E | Ricerca su OSV / GitHub Advisory annotata nel changelog | Previsto |
| **MASVS-CODE-4** Validazione degli input non fidati | Modelli verificati con SHA-256 prima del caricamento (difesa contro file GGUF malevoli); testo del campo attivo trattato come dato, mai come comando; prompt del motore che isola il testo dell'utente dalle istruzioni (Spec §5.3, §5.10); limiti di lunghezza su testo selezionato e durata dell'audio (§5.2) | A, B | Test di integrità con un modello alterato (`ZZ_ERR_MODEL_INTEGRITY`); test con testi molto lunghi | Previsto |
| **MASVS-RESILIENCE-1** Integrità della piattaforma | Facoltativo: Play Integrity API non prevista (sostituita dal §8.1). Nessun blocco su dispositivi con root | D | — | N/A (scelta del §8.1) |
| **MASVS-RESILIENCE-2** Anti-manomissione | Integrità dei modelli (SHA-256); stato di sblocco cifrato e legato al dispositivo; verifica della firma dell'acquisto Google Play (`Purchase.getOriginalJson` + firma) | A, D | Prova con app re-impacchettata: lo stato di sblocco non deve trasferirsi | Previsto |
| **MASVS-RESILIENCE-3** Anti-analisi statica | R8 con offuscamento attivo in release (Spec §10); regole `-keep` minime per JNI | B | `minifyEnabled true` e mapping caricato in Play Console | Previsto |
| **MASVS-RESILIENCE-4** Anti-analisi dinamica | Non previsto: nessun segreto lato server da proteggere; costo non giustificato | — | — | N/A |
| **MASVS-PRIVACY-1** Accesso minimo ai dati | Solo `RECORD_AUDIO` come permesso pericoloso; microfono solo su richiesta; accessibilità limitata al campo attivo | B | Revisione del manifest e del file XML del servizio | Previsto |
| **MASVS-PRIVACY-2** Nessuna identificazione dell'utente | Nessun identificativo, nessuna libreria di terzi che traccia; Block Store contiene solo una data | B, D | Elenco delle dipendenze (`THIRD_PARTY_LICENSES.md`) | Previsto |
| **MASVS-PRIVACY-3** Trasparenza | Informativa privacy, informativa sull'accessibilità, sezione Sicurezza dei dati coerenti tra loro | E, B | Confronto dei testi in `docs/privacy/` e `docs/store/` con il comportamento reale | Previsto |
| **MASVS-PRIVACY-4** Controllo dell'utente | Cronologia disattivabile e cancellabile; dizionario e scorciatoie modificabili; servizio disattivabile | B | Prova manuale | Previsto |

## Note per Windows (agente C)

MASVS è pensato per il mobile; per Windows si applicano gli stessi principi con questi equivalenti:

| Tema | Requisito |
|---|---|
| Archiviazione (STORAGE-1/2) | Database in `ApplicationData.Current.LocalFolder` (cartella del pacchetto, per utente); chiave protetta con DPAPI (`ProtectedData`, ambito utente) o `DataProtectionProvider`; nessun dato in `RoamingFolder` (sincronizzato nel cloud); nessun testo nei log, in Event Log o in file di dump |
| Appunti (§2.6) | Il ripiego copia il testo negli appunti e avvisa l'utente; escludere il contenuto dalla cronologia degli appunti e dalla sincronizzazione cloud (`ClipboardContentOptions.IsAllowedInHistory = false`, `IsRoamable = false`) |
| Crittografia (CRYPTO) | API di sistema (CNG); SHA-256 dei modelli come su Android |
| Rete (NETWORK) | Solo `StoreContext` durante la prova; nessun altro endpoint; nessuna capability di rete aggiuntiva oltre `internetClient` |
| Privilegi (PLATFORM) | Nessuna richiesta di amministratore (§2.6); `runFullTrust` motivato in Partner Center; scorciatoia globale con `RegisterHotKey`, nessun hook globale della tastiera (`SetWindowsHookEx` WH_KEYBOARD_LL) che leggerebbe tutti i tasti |
| UI Automation | Lettura limitata all'elemento con il focus e alla selezione; nessuna enumerazione del contenuto delle altre finestre |
| Integrità (CODE-4, RESILIENCE-2) | Modelli in sola lettura nella cartella di installazione (protetta da Windows per i pacchetti MSIX) e verificati con SHA-256; `zz_engine.dll` caricata solo dalla cartella del pacchetto (percorso assoluto, niente ricerca DLL nel PATH) |
| Componenti (CODE-3) | Windows App SDK e .NET aggiornati; `packages.lock.json` |
| Compilazione | `TreatWarningsAsErrors` (già nel progetto `Zzitto.Core`), CFG e ASLR attivi nel motore C++ (`/guard:cf`, `/DYNAMICBASE`), stack protection (`/GS`) |

## Fonti consultate (22/10/2026)

- OWASP MASVS v2: https://mas.owasp.org/MASVS/
- OWASP MASTG (procedure di verifica): https://mas.owasp.org/MASTG/
