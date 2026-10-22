# Licenza, prova gratuita e acquisto

*Aggiunto il 22/10/2026 alle 11:42. Riferimenti: Spec §2.3, §8.1, §8.2, §10, §12. Proprietario: agente D (Acquisti).*

Questo documento descrive come Zzitto gestisce la prova gratuita di 30 giorni e lo sblocco definitivo
su Android e su Windows, i casi limite e cosa configurare negli store prima della pubblicazione.

## Codice

| Piattaforma | Percorso | Ingresso |
|---|---|---|
| Android | `android/licensing` (modulo Gradle `:licensing`) | `PlayLicenseGate`, legato a `LicenseGate` da Hilt (`LicensingBindingsModule`) |
| Windows | `windows/src/Zzitto.Licensing` | `StoreLicenseService.CreateDefault()` implementa `ILicenseService` |
| Test Android | `android/licensing/src/test` | `./gradlew :licensing:check` (JUnit 5) |
| Test Windows | `windows/tests/Zzitto.Licensing.Tests` | `dotnet test -p:Platform=x64` (xUnit) |

Uso dall'app: chiamare `refresh()` / `RefreshAsync()` a ogni avvio e osservare lo stato
(`state` / `StateChanged`). La dettatura è consentita solo con `Trial` o `Purchased` (`canDictate()` / `CanDictate`).

## Android (Spec §8.1)

L'app è **gratuita** sul Play Store; lo sblocco è un **acquisto in-app non consumabile una tantum**
con ID prodotto `zzitto_unlock`. Nessun abbonamento.

### Flusso a ogni avvio (`refresh`)

1. **Sblocco salvato?** Se nel file cifrato `noBackupFilesDir/zzitto_unlock.bin` c'è uno sblocco valido
   → stato `Purchased`, nessun contatto con Google Play, connessione Billing chiusa.
2. **Prova.** Si leggono la data di inizio prova da Block Store (legata all'account Google) e dalla copia
   locale (SharedPreferences). Vince la data più vecchia. Al primo avvio la prova parte adesso e si scrive
   su entrambi. Stato `Trial(giorni)` oppure `TrialExpired`.
3. **Verifica online dell'acquisto** (`queryPurchasesAsync`, prodotti INAPP), con timeout di 15 secondi:
   - acquisto `PURCHASED` già riconosciuto → sblocco salvato cifrato, stato `Purchased`, niente più verifiche;
   - acquisto `PURCHASED` non riconosciuto → si chiama `acknowledgePurchase`; se riesce si salva lo sblocco,
     se fallisce l'app è sbloccata solo in questa sessione e al prossimo avvio si riprova (Google rimborsa
     in automatico gli acquisti non riconosciuti entro 3 giorni, quindi lo sblocco non si salva prima);
   - acquisto `PENDING` → nessuno sblocco, resta lo stato della prova;
   - nessun acquisto, niente rete, Play Store non disponibile, timeout → **la prova continua**.

### Acquisto (`launchPurchase`) e ripristino (`restorePurchases`)

- `launchPurchase(activity)` legge i dettagli di `zzitto_unlock`, sceglie la prima offerta disponibile e apre
  la finestra di Google Play. Esiti: `Purchased`, `Pending`, `Cancelled`, `AlreadyOwned`, `Error`.
- `ITEM_ALREADY_OWNED` (già acquistato, ad esempio su un altro telefono con lo stesso account) → si interroga
  la cronologia acquisti e lo sblocco si ripristina (`AlreadyOwned`).
- Un acquisto in sospeso completato più tardi arriva da `onPurchasesUpdated` se l'app è aperta, altrimenti
  dalla verifica al prossimo avvio; in entrambi i casi viene riconosciuto e salvato.
- `restorePurchases()` (pulsante "Ripristina acquisto") interroga la cronologia acquisti di Google Play;
  serve dopo una reinstallazione, perché il file di sblocco non viene ripristinato dai backup.

### Persistenza e sicurezza (Spec §8.1, §10)

| Dato | Dove | Protezione |
|---|---|---|
| Sblocco | `noBackupFilesDir/zzitto_unlock.bin` (escluso dal backup automatico) | AES-256-GCM, chiave non esportabile nell'Android Keystore (`zzitto_licensing_unlock_v1`). Contiene solo un testo fisso legato a pacchetto e prodotto; nessun token d'acquisto né dato personale |
| Inizio prova e ultimo istante visto | Block Store, chiave `com.zaramella.zzitto.trial.v1` | Backup su cloud solo con cifratura end-to-end (vedi sotto) |
| Copia locale della prova | SharedPreferences `zzitto_licensing` | In chiaro: solo due istanti, nessun dato personale |

### Backup su cloud di Block Store solo con cifratura end-to-end

*Aggiunto il 22/10/2026 alle 11:52, su richiesta dell'agente E tramite l'orchestratore (Spec §8.1, §10).*

Per poter dichiarare "nessun dato raccolto" nella sezione Sicurezza dei dati di Google Play, la data di
inizio prova va nel cloud **solo** se `BlockstoreClient.isEndToEndEncryptionAvailable()` restituisce true:
solo allora si usa `setShouldBackupToCloud(true)` (`CloudBackupPolicy`). Se la cifratura end-to-end non è
disponibile (es. nessun blocco schermo) o la verifica fallisce, il dato resta solo sul dispositivo
(Block Store locale + copia locale). Conseguenza: senza cifratura end-to-end la prova non sopravvive al
passaggio a un nuovo telefono tramite cloud; resta valida la protezione da disinstallazione e reinstallazione
sullo stesso dispositivo. Test: `CloudBackupPolicyTest`.

### Permessi di rete: nessuno

Il modulo **non dichiara** `INTERNET` né `ACCESS_NETWORK_STATE`. Google Play Billing e Block Store
comunicano con il Play Store e con Google Play Services tramite IPC (bindService/AIDL): è il processo di
Google ad andare in rete, non Zzitto. L'assenza di rete si riconosce dai codici di risposta
(`SERVICE_UNAVAILABLE`, `NETWORK_ERROR`, ...). Il manifest del modulo dichiara solo
`com.android.vending.BILLING` (già incluso dalla libreria Billing, non è un permesso di runtime).
Resta così valido il §10 ("solo `RECORD_AUDIO`"). Alla prima build va controllato il manifest unito
(`app/build/intermediates/merged_manifests`): se una libreria Google aggiunge `INTERNET`, decidere con il
titolare se rimuoverlo con `tools:node="remove"` nel manifest dell'app.

### Casi limite

| Caso | Comportamento |
|---|---|
| Nessuna connessione durante la prova | La prova continua fino alla scadenza; la verifica riprova al prossimo avvio |
| Nessuna connessione a prova scaduta | Dettatura bloccata; dopo l'acquisto serve la connessione una volta per confermarlo |
| Disinstallazione e reinstallazione | La data di inizio arriva da Block Store: la prova non riparte. Lo sblocco si ripristina da Google Play |
| Block Store non disponibile (Play Services assente, errore) | Si usa solo la copia locale, senza errori |
| Backup Google disattivato, reset di fabbrica, cambio account | La prova può ripartire: rischio residuo accettato (Spec §8.1) |
| Orologio spostato all'indietro | Si usa il massimo tra ora attuale e ultimo istante visto (salvato su Block Store e in locale): i giorni non tornano. Spostarlo avanti e poi indietro consuma giorni (rischio accettato, l'ora di rete è attiva per impostazione predefinita) |
| Orologio prima del 01/01/2026 | Data sicuramente errata: la prova non viene registrata in quell'avvio (30 giorni mostrati) per non fissare una data assurda |
| Acquisto in sospeso (`PENDING`) | Nessuno sblocco finché Google non conferma il pagamento; l'utente va avvisato |
| Riconoscimento (`acknowledge`) fallito | Sblocco solo nella sessione; nuovo tentativo al prossimo avvio |
| Rimborso dopo lo sblocco salvato | Lo sblocco resta: dopo l'acquisto l'app non verifica più (scelta della Spec §8.1) |
| File di sblocco manomesso o copiato da un altro telefono | Non supera la verifica GCM: ignorato, si ripristina da Google Play |

### Configurazione nella Play Console

1. **Profilo pagamenti** del titolare attivo (necessario per vendere prodotti in-app).
2. App pubblicata come **gratuita**.
3. Caricare almeno una build con la libreria Billing su una traccia di test (test interno), altrimenti
   non si possono creare prodotti in-app.
4. *Monetizza → Prodotti → Prodotti una tantum*: creare il prodotto con **ID `zzitto_unlock`**
   (l'ID non si può più cambiare), nome e descrizione in italiano e inglese, un'opzione d'acquisto
   "Acquista" con il prezzo scelto dal titolare, stato **Attivo**.
5. *Impostazioni → Test delle licenze*: aggiungere gli account Gmail dei tester di licenza e impostare la
   risposta di licenza. I tester vedono le carte di prova ("Test card, always approves",
   "...always declines", "Slow test card", che permette di provare gli acquisti `PENDING`) e non pagano.
6. Installare l'app per i test dalla traccia di test interno (link di attivazione), con un account tester.
7. *Sicurezza dei dati*: l'acquisto è gestito da Google Play; l'app non raccoglie né condivide dati.

## Windows (Spec §8.2)

L'app è **a pagamento** sul Microsoft Store con **prova gratuita di 30 giorni gestita dallo Store**,
venduta separatamente dalla versione Android.

### Flusso a ogni avvio (`RefreshAsync`)

1. **Stato "acquistato" salvato?** Se il file `%LOCALAPPDATA%\Zzitto\Licensing\purchase.bin`, cifrato con
   DPAPI (ambito utente corrente), è valido → `Purchased`, lo Store non viene più letto.
2. Altrimenti `StoreContext.GetAppLicenseAsync()` con timeout di 15 secondi:
   - `IsActive` e non `IsTrial` → `Purchased` e stato salvato cifrato;
   - `IsActive` e `IsTrial` → `Trial(giorni)` con i giorni arrotondati per eccesso da `TrialTimeRemaining`;
     si salva (cifrata) la fine della prova per l'uso senza Store;
   - non `IsActive` → `TrialExpired`.
3. **Store non raggiungibile** (eccezione o timeout): si usa l'ultima lettura salvata e la prova continua;
   l'orologio spostato indietro non restituisce giorni. Senza alcuna lettura precedente lo stato resta
   `Unknown` (caso raro: la licenza dello Store è conservata in locale da Windows).
4. L'evento `StoreContext.OfflineLicensesChanged` fa rileggere la licenza (es. acquisto fatto dallo Store).

### Acquisto (`PurchaseAsync`)

- Va chiamato **dal thread UI** con l'handle della finestra principale
  (`WinRT.Interop.WindowNative.GetWindowHandle(window)`): `StoreContext` viene associato alla finestra con
  `WinRT.Interop.InitializeWithWindow.Initialize`, obbligatorio per le app desktop WinUI 3.
- Si ricava lo Store ID dell'app con `GetStoreProductForCurrentAppAsync` e si chiama `RequestPurchaseAsync`.
- `Succeeded` → sblocco immediato nella sessione; lo stato si salva solo quando la licenza riletta
  dallo Store risulta definitiva. `AlreadyPurchased` → `AlreadyOwned`. `NotPurchased` → `Cancelled`.
  `NetworkError`, `ServerError` o altri errori → `Error`.
- L'acquisto nello Store non funziona in un processo con privilegi elevati (Spec §2.6: l'app non li chiede).

### Casi limite

| Caso | Comportamento |
|---|---|
| Nessuna connessione | La licenza dello Store è conservata da Windows e in genere si legge anche offline; se la lettura fallisce si usa l'ultima lettura salvata |
| Prova scaduta prima dell'avvio | Il Microsoft Store stesso impedisce l'avvio e propone l'acquisto |
| Prova scaduta con l'app aperta | Alla successiva lettura `TrialExpired`: dettatura bloccata, l'icona porta all'acquisto |
| File cifrato copiato su un altro PC o utente | DPAPI non lo decifra: ignorato |
| Debug da Visual Studio | L'app non è installata dallo Store e la licenza risulta non attiva. Solo nelle build Debug esiste `DevelopmentStoreLicenseSource` (prova di 30 giorni simulata; l'acquisto simulato sblocca solo la sessione e non salva nulla) |

### Configurazione in Partner Center

1. Account sviluppatore intestato al titolare, profilo pagamenti e fiscale completati.
2. Riservare il nome **Zzitto** e associare il progetto (identità del pacchetto `Zaramella.Zzitto`).
3. *Prezzi e disponibilità → Prezzo di base*: il prezzo una tantum scelto dal titolare.
4. *Prezzi e disponibilità → Versione di valutazione gratuita*: **30 giorni**.
5. Nessun componente aggiuntivo (add-on): si vende l'app stessa.
6. Per i test prima della pubblicazione: invio come *pacchetto privato* (pubblico limitato) o tramite
   un *flight* di pacchetti, con account Microsoft di test; un'app installata da Visual Studio non ha licenza.
7. *Dichiarazione privacy*: nessun dato raccolto; la verifica della licenza è svolta da Windows e dallo Store.

## Test (Spec §12)

Coperti su entrambe le piattaforme: calcolo dei giorni rimanenti, scadenza, orologio spostato indietro,
persistenza cifrata dello sblocco (anche manomissione e chiave diversa), acquisto in sospeso (Android),
assenza di rete e timeout, ripristino, acquisto riuscito, annullato, già posseduto ed errori.
I test Android non usano l'Android SDK a runtime (doppi di test al posto di Billing, Block Store e Keystore).
I test Windows usano uno Store finto; un test usa DPAPI reale e il file system.
