# Guida al rilascio di Zzitto

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §2.3, §2.4, §7, §7.1, §8, §8.1, §8.2, §10, §12, §13, §16 (fasi 6–8).
Proprietario: agente E. Destinatario: il titolare (Zaramella Consulting Srl).*

Questa guida descrive, passo per passo, cosa fare per pubblicare Zzitto su Google Play e sul Microsoft Store.
Non c'è CI (decisione del capitolo 14): tutto si fa con gli script in `tools/` sulla macchina del titolare.

## 1. Verifiche prima di tutto (nome, marchio, domini)

Da fare **prima** di creare l'app nelle console, perché il package Android `com.zaramella.zzitto` non si può più cambiare
dopo il primo caricamento (Spec, "Nome dell'app").

| Verifica | Dove | Esito atteso |
|---|---|---|
| Marchi UE e internazionali uguali o simili a "Zzitto" nelle classi 9 (software) e 42 (servizi software) | TMview: https://www.tmdn.org/tmview/ (copre EUIPO e uffici nazionali) | Nessun marchio uguale o confondibile |
| Marchi italiani | Sito dell'Ufficio Italiano Brevetti e Marchi, sezione banche dati: https://uibm.mimit.gov.it (anche TMview include i marchi italiani) | Come sopra |
| Domini `zzitto.com` e `zzitto.it` | Un registrar o https://www.nic.it/ per il `.it` | Liberi; se liberi, registrarli |
| Nome dell'app nella Play Console | Al momento della creazione dell'app | Nessun avviso di nome già in uso |
| Nome dell'app in Partner Center | Prenotazione del nome (sezione 6) | Nome prenotato |
| Conferma degli identificativi | Package `com.zaramella.zzitto`, pacchetto `Zaramella.Zzitto` | Già confermati (capitolo 14, 22/10/2026 11:15) |

Facoltativo ma consigliato: deposito del marchio "Zzitto" (UIBM per l'Italia o EUIPO per l'UE) nelle classi 9 e 42.

## 2. Account sviluppatore

### 2.1 Google Play Console

1. Registrare un account **organizzazione** intestato a Zaramella Consulting Srl su https://play.google.com/console
   (quota di registrazione una tantum; serve il numero D-U-N-S dell'azienda, ottenibile gratuitamente da Dun & Bradstreet).
   Con un account personale creato dopo il 13/11/2023 Google impone un test chiuso con almeno 12 tester per 14 giorni
   prima della produzione.
2. Completare la verifica dell'identità e i dati di contatto pubblici (email e sito di supporto).
3. Configurare il **profilo pagamenti** (account commerciante) per vendere l'acquisto in-app: dati fiscali italiani, IBAN.
4. Attivare la verifica in due passaggi per tutti gli utenti della console; dare accesso solo a chi serve.

### 2.2 Microsoft Partner Center

1. Registrare un account sviluppatore **aziendale** su https://partner.microsoft.com/dashboard (programma app e giochi),
   intestato a Zaramella Consulting Srl; completare la verifica dell'azienda.
2. Completare il **profilo fiscale** e il **profilo pagamenti** per ricevere i ricavi.
3. Attivare l'autenticazione a più fattori sull'account Microsoft.

## 3. Preparazione una tantum del repository

1. Preparare i modelli (circa 25 GB liberi, qualche ora la prima volta per scaricare e convertire):
   ```
   tools/models/prepare-models.sh --strict          # Linux/macOS
   pwsh tools/models/prepare-models.ps1 -Strict     # Windows
   ```
   Gli script scaricano i modelli originali, quantizzano Phi-4-mini, calcolano gli SHA-256, li scrivono in
   `ModelManifest.kt` e `ModelManifest.cs`, riempiono gli asset pack Android e la cartella `Models` di Windows e aggiornano
   `tools/models/models.lock`. Dettagli in `tools/models/README.md`.
2. Fare commit di `models.lock` e dei due `ModelManifest` aggiornati (i modelli restano fuori da git).

## 4. Chiave di upload Android (Play App Signing)

Con Play App Signing Google custodisce la chiave di firma dell'app; noi usiamo solo una **chiave di upload**, sostituibile
se viene persa.

1. Creare la chiave **fuori dal repository** (JDK 17):
   ```
   keytool -genkeypair -v -storetype PKCS12 -keystore ~/chiavi/zzitto-upload.p12 \
     -alias zzitto-upload -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Conservare una copia cifrata del file e delle password in un luogo sicuro diverso dal computer (es. gestore di password aziendale).
3. Per gli script, creare un file fuori dal repository, leggibile solo dal titolare, per esempio
   `~/chiavi/zzitto-signing.properties`:
   ```
   ZZ_UPLOAD_KEYSTORE=/home/titolare/chiavi/zzitto-upload.p12
   ZZ_UPLOAD_KEY_ALIAS=zzitto-upload
   ZZ_UPLOAD_STORE_PASSWORD=...
   ZZ_UPLOAD_KEY_PASSWORD=...
   ```
   e prima del rilascio: `export ZZ_SIGNING_PROPERTIES=~/chiavi/zzitto-signing.properties`
   (PowerShell: `$env:ZZ_SIGNING_PROPERTIES = "$HOME\chiavi\zzitto-signing.properties"`).
4. Mai copiare chiavi o password nel repository: `.gitignore` esclude `*.jks`, `*.keystore`, `*.pfx`, `.env`, e
   `release-android.*` rifiuta un keystore che si trova dentro il repository.

## 5. Rilascio Android su Google Play

### 5.1 Build

```
python3 tools/release/version.py bump 0.1.0        # prima volta; poi patch | minor | major
tools/release/release-android.sh                     # oppure: pwsh tools/release/release-android.ps1
```

Lo script si ferma se: il changelog non riporta la versione e i modelli, gli hash dei modelli non coincidono, un test
fallisce (Spec §12), l'AAB contiene il motore finto o chiavi, un asset pack supera i limiti di Google Play.
Risultato: `dist/zzitto-android-X.Y.Z-N.aab` e il suo `.sha256`.
Consigliato: impostare `BUNDLETOOL_JAR` (https://github.com/google/bundletool/releases) per la validazione completa.

### 5.2 Prima configurazione nella Play Console

1. **Crea app**: nome "Zzitto", lingua predefinita italiano, tipo **App**, **Gratuita** (Spec §8.1: l'app è gratuita con
   sblocco in-app; una volta pubblicata gratuita non può diventare a pagamento).
2. **Test → Test interno → Crea nuova release**: accettare **Play App Signing** e caricare l'AAB. Il primo caricamento
   fissa il package `com.zaramella.zzitto`.
3. **Monetizza → Prodotti in-app**: creare `zzitto_unlock` come descritto in `docs/store/google-play.md` §3 e attivarlo.
   (La sezione si sblocca dopo aver caricato un AAB che usa la Billing Library.)
4. **Impostazioni → Test delle licenze**: aggiungere gli account Google dei tester, così possono provare l'acquisto senza
   addebito.
5. **Contenuti dell'app** (Criteri): informativa privacy (URL, sezione 7), annunci = no, accesso all'app, classificazione
   dei contenuti, pubblico di destinazione, sicurezza dei dati, API di accessibilità con video
   (testi in `docs/store/`), eventuale dichiarazione del servizio in primo piano.
6. **Presenza sullo store**: testi di `docs/store/google-play.md`, icona, grafica in evidenza, screenshot.

### 5.3 Tracce di test e produzione (Spec §13)

| Traccia | Chi | Cosa verificare | Quando si passa oltre |
|---|---|---|---|
| Test interno | Fino a 100 tester scelti (es. il titolare e i colleghi) | Installazione con gli asset pack, ricomposizione dei modelli al primo avvio, prova di 30 giorni, acquisto di prova, dettatura in varie app | Nessun difetto bloccante |
| Test chiuso | Gruppo più ampio (con account personale: almeno 12 tester per 14 giorni) | Stabilità su più dispositivi, consumo di batteria, approvazione della dichiarazione di accessibilità | Revisione di Google superata |
| Produzione | Tutti | Rilascio **graduale** (es. 5 % → 20 % → 50 % → 100 %), controllo di Android vitals e recensioni a ogni passo | — |

La revisione della dichiarazione di accessibilità può richiedere più giorni e richieste di chiarimento: inviarla già
con la prima release di test chiuso.

## 6. Rilascio Windows sul Microsoft Store

### 6.1 Prima configurazione in Partner Center

1. **Crea una nuova app** e **prenota il nome** "Zzitto".
2. **Gestione prodotto → Identità del prodotto**: copiare `Package/Identity/Name`, `Package/Identity/Publisher` e il nome
   visualizzato dell'editore. L'agente C deve inserirli nel `Package.appxmanifest` (il nome previsto è `Zaramella.Zzitto`;
   se Partner Center assegna un nome diverso, vale quello di Partner Center e va registrato nel capitolo 14).
3. **Prezzi e disponibilità**: prezzo deciso dal titolare; **Versione di valutazione gratuita: 30 giorni** (Spec §8.1);
   mercati; visibilità iniziale privata (sezione 6.3).
4. **Proprietà**: categoria Produttività; URL dell'informativa privacy; sito e contatto di supporto.
5. **Classificazioni per età**: questionario IARC (`docs/store/microsoft-store.md` §6).
6. **Opzioni di invio → Funzionalità con restrizioni**: motivazione di `runFullTrust` (`docs/store/microsoft-store.md` §5).
7. **Elenchi dello Store** in italiano e inglese: testi di `docs/store/microsoft-store.md`, screenshot (almeno uno, consigliati quattro).

### 6.2 Build

Su Windows 11 con Visual Studio 2022 (carichi .NET desktop e C++ con strumenti ARM64) e Windows SDK:

```
py -3 tools\release\version.py show
pwsh tools\release\release-windows.ps1 -SyncManifest
```

Lo script esegue i test, compila `zz_engine.dll` per x64 e ARM64, crea i due MSIX, li unisce in
`dist\zzitto-windows-X.Y.Z.0.msixbundle` e verifica il limite di 25 GB dello Store (Spec §7.1). Il bundle non è firmato:
lo firma lo Store.

### 6.3 Test e pubblicazione

1. Primo invio con **visibilità privata** (solo chi ha il link o un gruppo di tester), oppure **flight di pacchetti** per
   un gruppo di tester dopo la prima pubblicazione.
2. Verificare su Surface Snapdragon (ARM64) e su un portatile x64 con 16 GB (Spec §11.1): avvio dalla tray, scorciatoia,
   inserimento nelle applicazioni più usate, ripiego negli appunti, prova di 30 giorni (`StoreContext`), acquisto.
3. Passare la visibilità a pubblica e inviare. La certificazione richiede di solito da poche ore a qualche giorno.

## 7. Pubblicazione dell'informativa privacy

1. Completare i campi `[[DA COMPLETARE]]` in `docs/privacy/informativa-privacy.it.md` e `privacy-policy.en.md`.
2. Rigenerare la pagina: `python3 tools/docs/build-privacy-html.py` (richiede `pip install markdown`).
3. Pubblicare `docs/privacy/index.html` su un indirizzo stabile, per esempio `https://zzitto.it/privacy` o
   `https://zaramella.com/zzitto/privacy`.
4. Inserire l'URL in Play Console, Partner Center e nella schermata dell'app (agenti B e C).

## 8. Checklist finale prima della produzione

- [ ] Verifiche di nome, marchio e domini (sezione 1) completate e annotate nel capitolo 14.
- [ ] `version.py check` senza errori; `CHANGELOG.md` con versione e modelli.
- [ ] Tutti i test superati (gli script lo impongono).
- [ ] Checklist MASVS (`docs/compliance/owasp-masvs.md`) verificata sull'app compilata.
- [ ] Allegato ISO approvato dal titolare (gate della fase 6).
- [ ] `THIRD_PARTY_LICENSES.md` aggiornato e schermata "Licenze open source" coerente; eccezioni di licenza registrate.
- [ ] Informativa privacy pubblicata e linkata.
- [ ] Sezione Sicurezza dei dati, dichiarazione di accessibilità, classificazioni compilate.
- [ ] Prova di acquisto e ripristino dopo reinstallazione su entrambe le piattaforme.
- [ ] Archiviati gli AAB/MSIX rilasciati con i loro `.sha256` e il tag git `vX.Y.Z`.

## 9. Pacchettizzazione dei modelli: limiti verificati e soluzione

Limiti verificati il 22/10/2026:

| Store | Limite | Fonte |
|---|---|---|
| Google Play | Modulo base 500 MB; **singolo asset pack 1,5 GB**; **totale moduli + asset pack install-time 4 GB**; dimensioni di download compresse | https://support.google.com/googleplay/android-developer/answer/9859372 |
| Google Play | Gli asset pack install-time richiedono durante l'installazione spazio libero pari ad almeno il doppio della loro dimensione | https://developer.android.com/guide/playcore/asset-delivery |
| Microsoft Store | **25 GB per pacchetto MSIX e 25 GB per bundle** | https://learn.microsoft.com/windows/apps/publish/publish-your-app/msix/app-package-requirements |

Dimensioni: Whisper medium Q5_0 circa 539 MB; Phi-4-mini-instruct Q4_K_M circa 2,5 GB; totale circa 3,0 GB.

- **Google Play:** Phi-4-mini supera da solo il limite di 1,5 GB per asset pack. Il totale (circa 3,0 GB più il modulo base)
  sta sotto i 4 GB install-time. Soluzione adottata: **tre asset pack install-time** (`models` con Whisper,
  `models_phi_1` e `models_phi_2` con le due metà di Phi-4-mini), ricomposti dall'app al primo avvio. Restano rispettati il
  §7 (install-time, nessun download da server esterni, versione legata all'app) e la verifica SHA-256 nel codice.
  Margine: con circa 0,5 GB di spazio residuo nel limite di 4 GB, un modello più grande richiederebbe di rivedere la scelta.
- **Microsoft Store:** nessun problema. Ogni pacchetto per architettura pesa circa 3,1 GB e il bundle ARM64 + x64 circa
  6,2 GB, contro un limite di 25 GB. I modelli restano file interi.

## 10. Istruzioni per gli agenti

### Agente B (Android)

1. `android/app/build.gradle.kts`:
   - `assetPacks += listOf(":models", ":models_phi_1", ":models_phi_2")` nel blocco `android { }`.
   - `versionName` e `versionCode` letti da `tools/release/version.properties` (percorso relativo `../../tools/release/version.properties`
     rispetto al modulo `app`), così gli script di rilascio controllano un'unica fonte (Spec §13).
   - **Nessun** `signingConfig` per il tipo `release`: l'AAB lo firma `release-android.*` con la chiave di upload.
   - `androidResources { noCompress += listOf("bin", "gguf", "part") }` così i modelli non vengono compressi nell'APK
     (lettura più veloce; i file sono comunque quasi incomprimibili).
   - R8 attivo in release (`isMinifyEnabled = true`), Spec §10.
   - Configurazione CMake del motore per la release **senza** `ZZ_ENGINE_MOCK=ON`: gli script rifiutano un AAB con il motore finto.
2. Modulo `:engine`: file `ModelManifest.kt` nel formato di `tools/models/README.md` (marcatori `zz-model-hash`,
   `zz-model-size`, `zz-models-version`, valori iniziali `"DA_IMPOSTARE"` e `0L`).
3. Installazione dei modelli al primo avvio (Spec §7), su un thread di background con avanzamento nel tour:
   - Gli asset pack install-time sono già installati con l'app e si leggono con `context.assets` come gli asset normali
     (non serve la libreria Play Asset Delivery).
   - Leggere `models/models-index.json` per l'elenco dei file e delle parti; l'hash di riferimento resta `ModelManifest`.
   - Cartella di destinazione: `context.noBackupFilesDir/models/` (esclusa dai backup).
   - Prima di copiare, verificare lo spazio libero (`StatFs`): somma delle dimensioni più un margine (es. 300 MB); se manca
     spazio, messaggio chiaro all'utente e nuovo tentativo al prossimo avvio.
   - Whisper: copia di `models/whisper-medium-q5_0.bin` in un file `.tmp`.
   - Phi-4-mini: concatenazione **in ordine numerico** di `models/phi-4-mini-instruct-q4_k_m.gguf.1.part`, `.2.part`
     (tutte le parti elencate nell'indice) in un unico file `.tmp`.
   - Durante la copia calcolare lo SHA-256 in streaming e confrontarlo con `ModelManifest`; solo se coincide rinominare il
     `.tmp` nel nome finale (rinomina atomica). Se non coincide: cancellare il `.tmp` e segnalare l'errore.
   - Salvare la `MODELS_VERSION` installata in un file accanto ai modelli; a ogni avvio, se differisce da
     `ModelManifest.MODELS_VERSION` (aggiornamento dell'app), rifare l'installazione e cancellare i vecchi file.
   - Passare al motore i percorsi dei file ricomposti (`whisper_model_path`, `llm_model_path`) con gli hash di `ModelManifest`.
   - Test: ricomposizione con parti finte, hash errato, spazio insufficiente, aggiornamento di versione.
4. Spazio occupato: circa 3,1 GB negli asset pack più 3,1 GB di copia (gli asset pack install-time non si possono
   cancellare). È dichiarato nella scheda ("circa 7 GB di spazio libero").
5. Manifest e sicurezza: `allowBackup=false` o `dataExtractionRules` che escludono database e preferenze; `FLAG_SECURE`
   sulla cronologia; servizio di accessibilità configurato come in `docs/store/google-play-accessibilita.md`; informativa
   di accessibilità con i testi di quel documento; URL dell'informativa privacy nella schermata delle informazioni.
6. Schermata "Licenze open source": contenuto da `THIRD_PARTY_LICENSES.md` (componenti Google Play in una sezione separata).

### Agente A (Motore)

1. Nessuna modifica al contratto per la divisione dei file: il motore riceve sempre **un file per modello** (su Android
   quello ricomposto dall'app) e ne verifica lo SHA-256 come oggi (`zz_engine_load`, `ZZ_ERR_MODEL_INTEGRITY`).
2. Versione di llama.cpp: il GGUF di Phi-4-mini viene prodotto con la llama.cpp di `core-native/third_party/llama.cpp` se
   esiste (gli script la usano automaticamente), altrimenti con `LLAMA_CPP_REF` di `tools/models/models.conf` (oggi `b6000`).
   Tenere allineate le due versioni e avvisare l'agente E se cambia la versione.
3. Il motore reale non deve contenere la stringa `zzitto-engine MOCK` (gli script la usano per riconoscere il motore finto).
4. Compilare `zz_engine.dll` con runtime C++ statico (`/MT`) per non dover ridistribuire il runtime Visual C++, e con
   `/guard:cf`, `/DYNAMICBASE`, `/GS`.
5. Facoltativo, da proporre all'orchestratore: in `zz_version_string` riportare la versione dei modelli attesi.
   Proposta da valutare: la verifica SHA-256 completa di circa 3 GB a ogni caricamento costa tempo; l'orchestratore può
   decidere se mantenerla a ogni `zz_engine_load` o solo al primo avvio (oggi la richiede il contratto).

### Agente C (Windows)

1. File `ModelManifest.cs` nel formato di `tools/models/README.md`.
2. Cartella dei modelli nel progetto impacchettato con un file vuoto `.zz-models-dir`; i file `*.bin` e `*.gguf` di quella
   cartella inclusi come contenuto del pacchetto (`<Content Include="Models\*.bin;Models\*.gguf" />` o equivalente). I
   modelli si leggono dalla cartella di installazione (`Package.Current.InstalledLocation`), in sola lettura, mappati in memoria.
3. `Package.appxmanifest`: `Identity Name="Zaramella.Zzitto"`, `Publisher` copiato da Partner Center, `Version` uguale a
   `version.py appx-version` (lo script può allinearla con `-SyncManifest`).
4. Il progetto dell'app deve accettare la proprietà MSBuild `ZzEngineDir` (cartella con la `zz_engine.dll` compilata da
   `release-windows.ps1`, `build\core-win-<arch>\Release`) e includere la DLL nel pacchetto; supportare
   `GenerateAppxPackageOnBuild=true` per `Platform=x64` e `Platform=ARM64`.
5. `windows\Zzitto.sln` con i progetti di test, così `dotnet test` li esegue (`run-tests.ps1`).
6. Appunti di ripiego esclusi dalla cronologia e dalla sincronizzazione (`docs/compliance/owasp-masvs.md`, note Windows);
   scorciatoia globale con `RegisterHotKey`, senza hook globali della tastiera.

### Agente D (Acquisti), per conoscenza

- Prodotto in-app Android: ID `zzitto_unlock`, non consumabile.
- Block Store: backup nel cloud solo se `isEndToEndEncryptionAvailable()` è vero; altrimenti solo locale. È la condizione
  per dichiarare "nessun dato raccolto" (`docs/store/google-play-sicurezza-dati.md`).
- La versione della Play Billing Library nel catalogo è 8.0.0 (giugno 2025); al 22/10/2026 l'ultima è 9.1.0. Google Play
  impone di aggiornare le versioni che scadono: verificarlo prima della pubblicazione.

## 11. Decisioni proposte per il capitolo 14

Da registrare dall'orchestratore dopo l'approvazione del titolare:

1. Phi-4-mini diviso in due asset pack install-time e ricomposto al primo avvio, per il limite di 1,5 GB per asset pack (§7).
2. Limite Microsoft Store verificato: 25 GB per pacchetto e per bundle; modelli inclusi interi (§7.1).
3. Phi-4-mini quantizzato in locale dai pesi originali Microsoft (nessun GGUF ufficiale); Whisper Q5_0 scaricato dalla
   pubblicazione ufficiale di whisper.cpp; revisioni Hugging Face fissate in `tools/models/models.conf` (§3, §7).
4. Eccezioni al §2.2 per componenti proprietari di piattaforma: Google Play Billing, Google Play services (Block Store),
   Windows App SDK e runtime Microsoft; accettazione di SQLite e LibTomCrypt (pubblico dominio) (§2.2, `THIRD_PARTY_LICENSES.md`).
5. Correzione del §2.4: WinUI 3 / Windows App SDK è distribuito con licenza Microsoft (sorgenti MIT).
6. Fonte unica della versione in `tools/release/version.properties`; firma dell'AAB fuori da Gradle con `jarsigner` (§13).
7. Alternativa non adottata, da tenere presente: asset pack **fast-follow** (scaricati da Google Play subito dopo
   l'installazione) eviterebbero la doppia occupazione di spazio, ma il §7 impone install-time.
