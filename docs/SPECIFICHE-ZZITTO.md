# Specifiche – Zzitto, app di dettatura vocale con AI on-device per Android e Windows

*Documento creato il 22/10/2026 alle 09:10. Ogni modifica successiva va marcata con data e ora (ora locale italiana).*

## 0. Istruzioni per l'agente AI

- In ogni risposta in chat stampa per prima cosa data e ora correnti in ora locale italiana (Europe/Rome).
- Questa specifica è completa e vincolante. Non ridurla, non semplificarla e non sostituire componenti senza approvazione esplicita.
- Prima di scrivere il codice di produzione realizza il mockup descritto al capitolo 9 e attendi l'approvazione.
- Documenta ogni decisione nel registro decisioni (capitolo 14). Nel codice, in ogni punto rilevante, riporta un commento con il riferimento al capitolo e al paragrafo esatti di questa specifica (es. `// Spec §5.3`).
- Per ogni nuova funzione scrivi i test. I test vengono eseguiti a ogni compilazione (capitolo 12).
- Non fornire stime in giorni.
- Usa macchine a stati solo dove servono davvero (unico caso previsto: ciclo di dettatura, §5.2).

## Nome dell'app: Zzitto

*Aggiunto il 22/10/2026 alle 09:42.*

- Il nome commerciale dell'app è **Zzitto**, uguale su Android e su Windows, con due Z e due T.
- Va usato in schede degli store, icona, schermate, informativa privacy e materiale promozionale.
- Identificativi tecnici suggeriti: package Android `com.zaramella.zzitto`, nome pacchetto MSIX `Zaramella.Zzitto`, da confermare dal titolare prima della prima pubblicazione perché il package Android non si può più cambiare.
- Una ricerca web del 22/10/2026 non ha trovato app con questo nome. Prima della pubblicazione vanno comunque verificati: disponibilità del nome nella Play Console e in Partner Center, assenza di marchi registrati su EUIPO (TMview) e UIBM, disponibilità dei domini zzitto.com e zzitto.it.

## 1. Obiettivo

Un'app Android che funziona come tastiera di sistema (Input Method). Quando l'utente tocca un qualsiasi campo di testo, l'app compare. Con un tap sul microfono l'utente detta, l'app trascrive il parlato e poi lo corregge con un modello linguistico, eliminando esitazioni, ripetizioni e false partenze e sistemando la punteggiatura. Il testo finale viene inserito nel campo.

Vincolo fondamentale: **tutta l'elaborazione AI avviene in locale sul telefono**. Nessun audio e nessun testo esce dal dispositivo.

## 2. Ambito e vincoli

### 2.1 Piattaforma

- Android e Windows (vedi §2.4). Aggiornato il 22/10/2026 alle 09:16.
- Dispositivi target: Samsung Galaxy S24 e successivi (fascia alta).
- minSdk 34, targetSdk all'ultima versione richiesta da Google Play.
- Lingua principale: italiano. Interfaccia predisposta per altre lingue tramite risorse stringa.

### 2.2 Licenze

- Solo componenti con licenze permissive che consentono la vendita: MIT, Apache 2.0, BSD.
- Vietato usare modelli o librerie con licenze restrittive o proprietarie (es. Gemma Terms of Use, Gemini Nano, Llama Community License).
- Eccezioni ammesse (aggiornato il 22/10/2026 alle 12:17): componenti di piattaforma necessari per vendere e girare sugli store, cioè Google Play Billing e Block Store (licenza Android SDK di Google), Windows App SDK e Windows SDK (termini Microsoft). SQLite e LibTomCrypt sono di pubblico dominio. JUnit (EPL) è usato solo nei test e non viene distribuito.
- Ogni licenza va inclusa nell'app in una schermata "Licenze open source".

### 2.3 Distribuzione e vendita

- Distribuzione esclusivamente tramite Google Play Store.
- Modello di vendita: **app a pagamento con acquisto una tantum**. Nessun abbonamento.

### 2.4 Windows

*Aggiunto il 22/10/2026 alle 09:16.*

- L'app deve funzionare anche su Windows 11, con le stesse funzioni e la stessa elaborazione AI in locale.
- Su Windows l'app vive come una piccola icona nell'area di notifica e come icona del microfono flottante che compare vicino al campo di testo attivo.
- La dettatura si avvia con un clic sull'icona o con una scorciatoia da tastiera globale configurabile.
- Il testo si inserisce nel campo attivo di qualsiasi applicazione tramite UI Automation, con fallback sulla simulazione di input da tastiera (SendInput).
- Il motore AI (whisper.cpp, llama.cpp, prompt, comandi, scorciatoie) è un modulo nativo C++ condiviso tra Android e Windows. Le interfacce sono native per ogni piattaforma.
- UI Windows in C# con .NET 8 e WinUI 3 (Windows App SDK 1.8). Correzione del 22/10/2026 alle 12:17: Windows App SDK ha termini di licenza Microsoft, non MIT (eccezione al §2.2 come componente di piattaforma).
- Requisiti minimi Windows da validare con benchmark: 16 GB di RAM; accelerazione GPU o NPU usata se disponibile.
- Distribuzione Windows tramite Microsoft Store con acquisto una tantum. Le versioni Android e Windows si vendono separatamente, ognuna sul proprio store. Aggiornato il 22/10/2026 alle 09:17.

### 2.5 Interfaccia minimale

*Aggiunto il 22/10/2026 alle 09:16.*

- Su entrambe le piattaforme l'app deve essere semplice e discreta: in uso si vede solo un'icona.
- Nessuna finestra, barra o pannello durante la dettatura. Stato comunicato solo da colore e animazione dell'icona.
- Impostazioni, guida e tour sono in una schermata separata, aperta solo su richiesta.

### 2.6 Fallback dell'icona su Windows

*Aggiunto il 22/10/2026 alle 09:25.*

- L'icona flottante si posiziona vicino al campo attivo usando UI Automation per leggere la posizione del cursore o del controllo.
- Se l'applicazione non espone queste informazioni (alcune app Electron, giochi, finestre con privilegi elevati, desktop remoto), l'icona resta nell'ultima posizione scelta dall'utente.
- In ogni caso restano sempre disponibili l'icona nell'area di notifica e la scorciatoia da tastiera globale.
- Se l'inserimento nel campo fallisce, il testo viene copiato negli appunti e un piccolo avviso invita a incollarlo con Ctrl+V.
- L'app non chiede privilegi di amministratore; nelle finestre con privilegi elevati vale il fallback degli appunti.

## 3. Stack tecnologico

| Componente | Scelta | Licenza |
| --- | --- | --- |
| Linguaggio | Kotlin | Apache 2.0 |
| UI | Jetpack Compose | Apache 2.0 |
| Riconoscimento vocale | whisper.cpp con modello Whisper (small o medium, quantizzato) | MIT |
| Correzione del testo | Phi-4-mini (quantizzato, formato GGUF) su llama.cpp | MIT |
| Ponte nativo | JNI + Android NDK | Apache 2.0 |
| Persistenza locale | Room (SQLite) cifrato con SQLCipher | Apache 2.0 / BSD |
| Preferenze | DataStore | Apache 2.0 |
| Dependency injection | Hilt | Apache 2.0 |
| Test | JUnit 5, MockK, Compose UI Test, Espresso | EPL / Apache 2.0 |
| Build | Gradle (Kotlin DSL), Android App Bundle | Apache 2.0 |

L'agente deve verificare la licenza attuale di ogni componente al momento dello sviluppo e registrarla nel capitolo 14.

## 4. Architettura

### 4.1 Moduli

- `app`: impostazioni, onboarding, guida, tour, licenze.
- `ime`: servizio tastiera (`InputMethodService`) e UI della barra di dettatura.
- `audio`: cattura del microfono a 16 kHz mono PCM, rilevamento della voce (VAD).
- `stt`: wrapper JNI di whisper.cpp.
- `llm`: wrapper JNI di llama.cpp e gestione dei prompt di correzione.
- `core`: modelli dati, repository, sicurezza, logging.
- Asset pack `models`: file dei modelli (capitolo 7).

### 4.2 Pattern

- Architettura a livelli (UI, dominio, dati) con MVVM.
- Elaborazione AI su un thread in background dedicato. Mai sul main thread.
- I modelli si caricano in memoria alla prima dettatura e si scaricano dalla RAM dopo un periodo di inattività configurabile.

### 4.3 Moduli condivisi e Windows

*Aggiunto il 22/10/2026 alle 09:25. Completa il §4.1, che descriveva solo Android.*

- `core-native` (C++17, CMake): whisper.cpp, llama.cpp, VAD, prompt di correzione, riscrittura e traduzione, riconoscimento comandi e scorciatoie. Espone un'API C stabile usata da Android (JNI) e da Windows (P/Invoke).
- `win-app` (C#, .NET, WinUI 3): impostazioni, onboarding, tour, guida, licenze, acquisto.
- `win-tray`: icona nell'area di notifica, scorciatoia globale, avvio automatico con Windows.
- `win-overlay`: icona flottante e rilevamento del campo attivo con UI Automation.
- `win-input`: inserimento del testo (UI Automation, SendInput, fallback appunti, §2.6).
- `win-audio`: cattura del microfono a 16 kHz mono con WASAPI.
- Su Android il modulo `ime` è sostituito da `overlay` e `accessibility` (§5.1-bis).
- Il prompt di sistema, i comandi vocali e le regole di correzione stanno in `core-native`, così le due piattaforme si comportano allo stesso modo.

## 5. Funzionalità

### 5.1 Tastiera di dettatura

- L'app si registra come Input Method di sistema.
- Al tocco su un campo di testo compare solo una piccola icona del microfono, che cambia colore o si anima per indicare lo stato. Annulla, cambio tastiera e selettore lingua si aprono con una pressione lunga sull'icona, per non ingombrare lo schermo (§2.5). Aggiornato il 22/10/2026 alle 09:16.
- Il pulsante "cambia tastiera" usa `switchToPreviousInputMethod()`.

### 5.1-bis Icona flottante su Android

*Aggiunto il 22/10/2026 alle 09:21. Sostituisce l'approccio a tastiera del §5.1: l'app non è più un Input Method.*

- Su Android l'app mostra un'icona flottante del microfono, come Wispr Flow e Letterly, che compare quando un campo di testo riceve il focus e sparisce quando lo perde. L'icona si può trascinare e ricorda la posizione.
- Componenti: un `AccessibilityService` che rileva il campo di testo attivo e vi inserisce il testo (`ACTION_SET_TEXT` o incolla tramite appunti, con ripristino degli appunti precedenti), più una finestra overlay per l'icona.
- Permessi: servizio di accessibilità, spiegato nel tour al primo avvio con una schermata di informativa ben visibile. Il permesso "mostra sopra altre app" non serve: l'icona usa una finestra `TYPE_ACCESSIBILITY_OVERLAY` del servizio. Aggiornato il 22/10/2026 alle 12:17.
- Google Play: va compilata la dichiarazione sull'uso dell'API di accessibilità, con descrizione e video, spiegando che serve solo a inserire il testo dettato e che nessun dato lascia il dispositivo.
- Samsung: il tour guida l'utente a escludere l'app dall'ottimizzazione della batteria, perché il sistema non chiuda il servizio.
- La tastiera dell'utente resta quella che usa di solito.

### 5.2 Ciclo di dettatura (macchina a stati)

Stati: `Inattivo` → `Ascolto` → `Trascrizione` → `Correzione` → `Inserimento` → `Inattivo`. Stato `Errore` raggiungibile da ogni stato.

- Un tap sul microfono avvia l'ascolto. Un secondo tap lo ferma. Stop automatico dopo un silenzio configurabile (predefinito 2 secondi).
- Durata massima di una dettatura configurabile (predefinita 5 minuti).
- L'utente può annullare in qualsiasi stato.

### 5.3 Correzione del testo

- Il testo grezzo di Whisper viene inviato a Phi-4-mini con un prompt di sistema fisso che chiede di: togliere intercalari ed esitazioni, eliminare ripetizioni e autocorrezioni mantenendo la versione finale, correggere punteggiatura e maiuscole, non aggiungere contenuti e non cambiare il significato.
- Modalità selezionabili: "Fedele" (solo pulizia minima), "Pulito" (predefinita), "Formale".
- Se la correzione fallisce o supera il timeout, si inserisce il testo grezzo e lo si segnala all'utente.

### 5.4 Inserimento

- Il testo si inserisce nella posizione del cursore: su Android tramite il servizio di accessibilità (`ACTION_SET_TEXT` o incolla), su Windows tramite UI Automation, SendInput o appunti (§2.6). `InputConnection` valeva per la tastiera, abbandonata col §5.1-bis. Aggiornato il 22/10/2026 alle 12:17.
- L'ultimo inserimento si può annullare con un tap.

### 5.5 Dizionario personale

- Elenco di parole e nomi propri (aziende, persone, termini tecnici) passati come prompt iniziale a Whisper e come contesto al modello di correzione.

### 5.6 Cronologia

- Cronologia locale delle ultime dettature (testo grezzo e corretto), cifrata, disattivabile, con cancellazione totale in un tap e conservazione configurabile.

### 5.7 Impostazioni

- Lingua di dettatura, modalità di correzione, timeout di silenzio, durata massima, cronologia sì/no, dizionario personale, vibrazione/suono all'avvio dell'ascolto.

### 5.8 Guida e tour

- Ogni schermata ha una guida contestuale accessibile da un'icona "?".
- Al primo avvio parte un tour guidato che spiega come attivare la tastiera, come concedere il permesso del microfono e come fare la prima dettatura. Il tour si può ripetere dalle impostazioni.

### 5.9 Comandi vocali

*Aggiunto il 22/10/2026 alle 09:14.*

- Durante la dettatura l'app riconosce comandi in italiano: "a capo", "nuovo paragrafo", "punto", "virgola", "cancella l'ultima frase", "annulla", "invia" (dove il campo lo consente).
- I comandi si distinguono dal testo dettato tramite una lista fissa nel motore (versione 1 del contratto; la lista configurabile è rinviata) e una conferma visiva data dall'icona, perché la barra non esiste più (§2.5). Aggiornato il 22/10/2026 alle 12:17.
- Il riconoscimento dei comandi avviene in locale, prima della fase di correzione.

### 5.10 Riscrittura a voce

*Aggiunto il 22/10/2026 alle 09:14.*

- L'utente seleziona un testo nel campo, tocca il microfono e detta un'istruzione, per esempio "rendilo più formale", "accorcialo" o "correggi gli errori".
- Il testo selezionato si legge dal nodo di accessibilità su Android e con UI Automation su Windows (aggiornato il 22/10/2026 alle 12:17), Phi-4-mini lo riscrive secondo l'istruzione e il risultato sostituisce la selezione.
- La riscrittura si può annullare con un tap.

### 5.11 Scorciatoie vocali

*Aggiunto il 22/10/2026 alle 09:14.*

- L'utente definisce nelle impostazioni coppie parola chiave → testo, per esempio "firma" → blocco con nome e recapiti.
- Quando la parola chiave viene pronunciata da sola, si inserisce il testo associato senza passare dalla correzione.
- Le scorciatoie sono salvate cifrate come il dizionario personale (§10).

### 5.12 Traduzione offline

*Aggiunto il 22/10/2026 alle 09:14.*

- L'utente detta in italiano e ottiene il testo tradotto in inglese o in spagnolo, a scelta (aggiornato il 22/10/2026 alle 09:23).
- La traduzione è fatta in locale da Phi-4-mini nella stessa fase di correzione, con un prompt dedicato.
- Un selettore di lingua sulla barra di dettatura attiva o disattiva la traduzione.
- La qualità della traduzione va verificata nei test; se non è sufficiente, l'agente propone un modello alternativo con licenza permissiva e lo registra nel capitolo 14.

## 6. Profili e accesso

L'app funziona interamente offline e senza account, quindi **non prevede** multi-tenant, login Google/Office 365/email, super user né database PostgreSQL. Questi requisiti dello standard aziendale non si applicano a un'app consumer locale. L'eccezione è registrata nel capitolo 14.

- Un solo profilo utente locale per dispositivo.
- La licenza d'uso è verificata tramite Google Play (capitolo 8).

## 7. Pacchettizzazione dei modelli

- Formato di pubblicazione: Android App Bundle (AAB).
- I modelli **non** si scaricano da server esterni. Vanno in asset pack di Play Asset Delivery di tipo **install-time**, installati insieme all'app. Poiché ogni asset pack ha un limite di 1,5 GB, Whisper sta nel pack `models` e Phi-4-mini è diviso in due parti nei pack `models_phi_1` e `models_phi_2`, ricomposte dall'app al primo avvio con verifica dell'hash del file intero (aggiornato il 22/10/2026 alle 12:17).
- La versione dei modelli è sempre legata alla versione dell'app. Non esistono aggiornamenti indipendenti dei modelli.
- Il modulo base deve stare sotto il limite di dimensione di Google Play. I modelli vanno solo nell'asset pack.
- Al primo avvio i modelli vengono copiati o mappati in memoria dall'asset pack e se ne verifica l'integrità con un hash SHA-256 salvato nel codice.
- Quantizzazioni di riferimento da validare con benchmark sul Galaxy S24: Whisper medium Q5 e Phi-4-mini Q4\_K\_M, caricati in memoria uno dopo l'altro (aggiornato il 22/10/2026 alle 09:24).

### 7.1 Pacchettizzazione su Windows

*Aggiunto il 22/10/2026 alle 09:25.*

- Formato: pacchetto MSIX pubblicato sul Microsoft Store, con build separate per ARM64 e x64 raccolte in un unico bundle.
- I modelli sono inclusi nel pacchetto, con la stessa regola di Android: la versione dei modelli è legata a quella dell'app e non si scarica nulla a parte.
- I modelli restano nella cartella di installazione in sola lettura e vengono mappati in memoria; l'integrità si verifica con lo stesso hash SHA-256 del §7.
- L'agente deve verificare il limite di dimensione del pacchetto sul Microsoft Store e registrare l'esito nel capitolo 14.
- Gli aggiornamenti arrivano dallo Store; se i modelli non cambiano, lo Store scarica solo le parti modificate.

## 8. Vendita e monetizzazione

- App a pagamento sul Play Store con prezzo una tantum impostato nella Play Console.
- Nessun acquisto in-app e nessun abbonamento.
- Integrazione con la Play Integrity API per verificare che l'app sia stata installata dal Play Store, con un periodo di tolleranza offline.
- Scheda dello store: descrizione in italiano e inglese, screenshot, icona, grafica in evidenza, video promozionale facoltativo.
- Classificazione dei contenuti, sezione "Sicurezza dei dati" (dichiarando che nessun dato viene raccolto o condiviso) e pubblico di destinazione compilati nella Play Console.
- Profilo pagamenti e account sviluppatore del titolare configurati prima della pubblicazione.

### 8.1 Prova gratuita di 30 giorni e sblocco

*Aggiunto il 22/10/2026 alle 09:20. Sostituisce le righe precedenti del §8 su "app a pagamento", "nessun acquisto in-app" e Play Integrity.*

- **Android:** su Google Play un'app a pagamento non può avere un periodo di prova. L'app si pubblica quindi gratuita e lo sblocco definitivo è un **acquisto in-app non consumabile, una tantum** (Google Play Billing). Nessun abbonamento.
- **Windows:** app a pagamento sul Microsoft Store con la prova gratuita di 30 giorni gestita dallo Store, verificata con le API di licenza dello Store.
- Durante i 30 giorni di prova, a ogni avvio l'app si collega a internet solo per verificare se l'acquisto è stato fatto. Senza connessione la prova continua normalmente.
- Quando l'acquisto viene confermato, lo sblocco si salva in locale in modo cifrato e l'app non si collega più a internet.
- Allo scadere dei 30 giorni senza acquisto la dettatura si blocca e l'icona porta alla schermata di acquisto.
- La data di inizio prova su Android si salva con la Block Store API di Google, legata all'account Google dell'utente, che la conserva anche dopo disinstallazione e reinstallazione: la prova non riparte. Nessun server proprio. Rischio residuo accettato: reset di fabbrica o cambio di account Google. Aggiornato il 22/10/2026 alle 09:29.
- L'acquisto si ripristina dopo una reinstallazione tramite la cronologia acquisti dello store.
- L'app non dichiara il permesso INTERNET: Google Play Billing e Block Store comunicano con Google Play Services via IPC ed è Google Play a collegarsi a internet. Nessun audio o testo esce mai dal dispositivo (§10). Da verificare nel manifest unito alla prima build. Aggiornato il 22/10/2026 alle 12:17.
- Su Block Store la data di inizio prova va nel cloud solo se è disponibile la crittografia end-to-end; altrimenti resta sul dispositivo e la prova riparte solo su un telefono nuovo (aggiornato il 22/10/2026 alle 12:17).

### 8.2 Vendita su Windows

*Aggiunto il 22/10/2026 alle 09:25.*

- Account sviluppatore Microsoft Partner Center intestato al titolare.
- App a pagamento con acquisto una tantum e prova gratuita di 30 giorni configurata nello Store, venduta separatamente dalla versione Android.
- La licenza si legge con le API dello Store (`Windows.Services.Store`, `StoreContext`): durante la prova a ogni avvio, dopo l'acquisto mai più.
- Scheda dello Store in italiano e inglese: descrizione, screenshot, icona, classificazione dell'età, informativa privacy e dichiarazione che nessun dato viene raccolto.
- Prezzo impostato dal titolare in Partner Center.

## 9. Mockup (obbligatorio prima dello sviluppo)

*Aggiornato il 22/10/2026 alle 11:15: su decisione del titolare il mockup è stato saltato e lo sviluppo procede direttamente (vedi capitolo 14).*

L'agente produce un mockup navigabile (Compose con dati finti oppure HTML) che comprende:

- barra di dettatura in tutti gli stati del §5.2;
- onboarding e tour guidato;
- impostazioni, dizionario personale, cronologia, licenze, guida contestuale.

Lo sviluppo parte solo dopo l'approvazione del mockup, registrata nel capitolo 14.

## 10. Sicurezza e privacy (ISO 27001, 27017, 27018)

- Il principio di base è che nessun dato lasci il dispositivo. L'unico scambio di rete è la verifica dell'acquisto durante la prova, fatta da Google Play o dal Microsoft Store per conto dell'app (§8.1, §8.2); su Android l'app non dichiara il permesso INTERNET (aggiornato il 22/10/2026 alle 12:17). Aggiornato il 22/10/2026 alle 09:20.
- Permessi richiesti: solo `RECORD_AUDIO`.
- L'audio resta solo in memoria e viene distrutto subito dopo la trascrizione. Non viene mai salvato su disco.
- Cronologia e dizionario sono cifrati con chiave conservata nell'Android Keystore.
- Nessun log contiene testo dettato o audio.
- Gestione degli asset secondo OWASP MASVS (equivalente mobile di OWASP ASVS): offuscamento con R8, nessun segreto nel codice, controlli di integrità.
- I principi delle norme ISO 27001, 27017 e 27018 (minimizzazione dei dati, controllo degli accessi, cifratura, tracciabilità delle modifiche, gestione delle vulnerabilità delle dipendenze) vanno applicati e documentati in un allegato di conformità.
- Informativa privacy GDPR pubblicata su una pagina web e linkata nella scheda dello store e nell'app.

## 11. Prestazioni

Obiettivi da misurare su Galaxy S24:

- avvio dell'ascolto in meno di 300 ms dal tap;
- testo corretto in meno di 5 secondi dopo la fine di una dettatura di 15 secondi (aggiornato il 22/10/2026 alle 09:24);
- nessun ANR, consumo di RAM monitorato, modelli scaricati dalla memoria quando inattivi.

Se gli obiettivi non vengono raggiunti, l'agente propone alternative (quantizzazione diversa, modello Whisper più piccolo) e le registra nel capitolo 14 prima di applicarle.

### 11.1 Riferimento Windows

*Aggiunto il 22/10/2026 alle 09:22.*

- Macchina di riferimento: portatile semplice con 16 GB di RAM, senza scheda grafica dedicata.
- Dispositivo di test del titolare: Microsoft Surface con Snapdragon (Windows su ARM).
- L'app Windows va compilata nativamente sia per ARM64 sia per x64; il motore C++ usa le ottimizzazioni ARM (NEON) e, se disponibile, l'NPU Snapdragon.
- Obiettivi: gli stessi del §11 per il Galaxy S24, misurati su entrambe le architetture.

## 12. Test

- Test unitari per ogni funzione (stati, prompt, repository, cifratura).
- Test di integrazione dei wrapper JNI con file audio di esempio in italiano.
- Test UI con Compose UI Test.
- Test di regressione della qualità: insieme di registrazioni campione con testo atteso, e misura del WER prima e dopo la correzione.
- Tutti i test vengono eseguiti a ogni compilazione, sia in locale sia in CI. Una build con test falliti non si pubblica.

## 13. Build, installazione e rilascio

*Aggiornato il 22/10/2026 alle 11:15: su decisione del titolare non si usa GitHub Actions. Al posto della pipeline CI ci sono script di build locali (Gradle per Android, CMake per il motore, MSBuild/dotnet per Windows) che eseguono i test a ogni compilazione. Le voci su CI qui sotto valgono solo se in futuro si attiverà una pipeline.*

- Pipeline CI (es. GitHub Actions) che compila le librerie native, esegue i test, genera l'AAB firmato e lo carica sulla traccia di test interno della Play Console.
- Firma con Play App Signing. Chiave di upload conservata in modo sicuro e fuori dal repository.
- Percorso di rilascio: test interno → test chiuso → produzione con rilascio graduale.
- Versionamento semantico. Il changelog riporta anche la versione dei modelli inclusi.
- README con le istruzioni per compilare il progetto da zero, incluso il download dei modelli originali e la loro quantizzazione.

## 15. Verifica della specifica

*Aggiunto il 22/10/2026 alle 09:17. Punti aperti da risolvere prima del mockup.*

| # | Punto | Problema | Proposta |
| --- | --- | --- | --- |
| 1 | §3, §4.1 | Stack e moduli descrivono solo Android | Aggiungere il modulo `core-native` in C++ condiviso e i moduli Windows (app residente, overlay, inserimento testo) |
| 2 | §10 vs §8 | "Nessun permesso INTERNET" contrasta con la verifica Play Integrity | Scegliere: verifica una tantum online all'attivazione, oppure affidarsi solo alla protezione dello store senza verifica |
| 3 | §7 | La pacchettizzazione dei modelli è definita solo per Android | Windows: modelli inclusi nel pacchetto MSIX, verificando il limite di dimensione del Microsoft Store |
| 4 | §8 | Manca la parte di vendita Windows | Aggiungere scheda Microsoft Store, prezzo, verifica licenza tramite Store API |
| 5 | §11 | Obiettivi di prestazione solo per Galaxy S24 | Definire una macchina Windows di riferimento e i suoi obiettivi |
| 6 | §5.1, §2.5 | Su Android una tastiera occupa sempre un'area in basso; non può essere solo un'icona flottante | Tastiera di altezza minima con la sola icona; niente overlay di accessibilità, che Google Play limita |
| 7 | §2.4 | Mostrare l'icona vicino al cursore non funziona in tutte le app Windows | Fallback: icona fissa nell'area di notifica e scorciatoia da tastiera |
| 8 | §5.12 | La qualità di traduzione di Phi-4-mini in italiano non è garantita | Prova sui testi del titolare prima di confermarla |
| 9 | §3 | RAM: Whisper medium e Phi-4-mini insieme sono pesanti su 8 GB | Caricare i modelli in sequenza; partire con Whisper small |

*Aggiornato il 22/10/2026 alle 09:25: tutti i punti risolti. 1 → §4.3; 2 → §8.1; 3 → §7.1; 4 → §8.1 e §8.2; 5 → §11.1; 6 → §5.1-bis; 7 → §2.6; 8 → §5.12; 9 → §7 e §11.*

## 16. Piano di sviluppo

*Aggiunto il 22/10/2026 alle 09:17. Nessuna stima in giorni. Ogni fase si chiude con un gate approvato dal titolare.*

1. **Fase 0 – Chiusura della specifica.** Risolvere i punti del capitolo 15 e aggiornare la specifica. Gate: specifica approvata.
2. **Fase 1 – Prova di fattibilità del motore.** Modulo C++ condiviso con whisper.cpp e llama.cpp, eseguito da riga di comando su Windows e da un'app di prova su Galaxy S24. Benchmark di velocità, RAM e qualità sull'italiano. Gate: obiettivi del §11 raggiunti o alternative approvate.
3. **Fase 2 – Mockup.** Mockup dell'icona e dei suoi stati, del menu a pressione lunga, delle impostazioni, del tour, su entrambe le piattaforme (§9). Gate: mockup approvato.
4. **Fase 3 – Android base.** Icona flottante con servizio di accessibilità e overlay (§5.1-bis), dichiarazione di accessibilità per Google Play, esclusione dall'ottimizzazione batteria Samsung, ciclo di dettatura, correzione, inserimento, impostazioni, dizionario, cronologia cifrata, guida e tour, test automatici. Gate: test superati e prova del titolare.
5. **Fase 4 – Funzioni avanzate su Android.** Comandi vocali, riscrittura, scorciatoie, traduzione (§5.9–§5.12). Gate: prova del titolare.
6. **Fase 5 – Windows.** App residente, icona, scorciatoia globale, inserimento nel campo attivo con fallback sugli appunti (§2.6), build ARM64 e x64 (§11.1), stesse funzioni di Android con il motore condiviso. Gate: prova del titolare sulle applicazioni Windows più usate.
7. **Fase 6 – Sicurezza e conformità.** Revisione OWASP MASVS, allegato ISO 27001/27017/27018, informativa privacy, controllo licenze di tutte le dipendenze. Gate: allegato approvato.
8. **Fase 7 – Pacchettizzazione e store.** Prova gratuita di 30 giorni e acquisto una tantum: Google Play Billing con data di prova in Block Store su Android, Store API con prova gestita dal Microsoft Store su Windows (§8.1, §8.2). AAB con asset pack, MSIX (§7.1), schede degli store, pipeline CI di rilascio, test interno e chiuso. Gate: approvazione al rilascio.
9. **Fase 8 – Pubblicazione.** Rilascio graduale su Google Play e pubblicazione sul Microsoft Store, raccolta segnalazioni.

### 16.1 Orchestrazione di agenti in parallelo

*Aggiunto il 22/10/2026 alle 09:27. Vincolante per l'agente che legge questa specifica.*

L'agente che riceve questa specifica fa da **orchestratore**: non sviluppa tutto da solo, ma avvia e coordina più agenti che lavorano in parallelo, il massimo possibile.

**Passo iniziale, sequenziale (orchestratore):**

1. Definire il contratto del motore: l'API C di `core-native` (§4.3), con tipi, funzioni, codici di errore e callback di stato, documentata in un file di intestazione e in un capitolo dedicato.
2. Creare un motore finto (mock) che rispetta il contratto e restituisce testi di prova con tempi realistici.
3. Creare il repository con la struttura dei moduli, la pipeline CI e le regole comuni (riferimenti alla specifica, test, data e ora).

**Flussi paralleli (un agente ciascuno), avviati dopo il passo iniziale:**

| Agente | Ambito | Riferimenti | Dipende da |
| --- | --- | --- | --- |
| A – Motore | `core-native`: whisper.cpp, llama.cpp, VAD, correzione, riscrittura, traduzione, comandi, scorciatoie, benchmark | §3, §4.3, §5.2–§5.12, §7, §11 | Contratto |
| B – Android | Overlay, servizio di accessibilità, inserimento, impostazioni, tour, cronologia cifrata, mockup Android | §2.5, §5.1-bis, §5, §9, §10 | Contratto + mock |
| C – Windows | Tray, overlay, scorciatoia globale, UI Automation, fallback appunti, WASAPI, mockup Windows | §2.4, §2.6, §4.3, §9 | Contratto + mock |
| D – Acquisti | Prova 30 giorni, Google Play Billing, Store API Windows, sblocco cifrato | §8.1, §8.2 | Contratto |
| E – Conformità e rilascio | Allegato ISO, OWASP MASVS, privacy, licenze, schede store, AAB con asset pack, MSIX, CI di rilascio | §7, §7.1, §8, §10, §13 | Nessuna |

**Regole di orchestrazione:**

- Ogni agente lavora sul proprio modulo in un branch separato e non modifica il codice degli altri moduli.
- Il contratto del motore può cambiare solo tramite l'orchestratore, che aggiorna la specifica, il registro decisioni e avvisa tutti gli agenti.
- Ogni agente scrive i test del proprio modulo; la CI li esegue tutti a ogni compilazione.
- I gate del piano (capitolo 16) restano validi: l'orchestratore raccoglie i risultati e li sottopone al titolare.
- **Integrazione finale, sequenziale:** quando il motore A supera i benchmark, l'orchestratore sostituisce il mock con il motore vero su Android e Windows ed esegue i test completi su Galaxy S24 e Surface Snapdragon.

## 14. Registro decisioni

| Data e ora | Decisione | Motivazione | Riferimento |
| --- | --- | --- | --- |
| 22/10/2026 09:10 | Elaborazione AI interamente on-device | Privacy e funzionamento offline | §1 |
| 22/10/2026 09:10 | Whisper + Phi-4-mini, entrambi con licenza MIT | Permettono la vendita senza vincoli | §2.2, §3 |
| 22/10/2026 09:10 | Modelli in un asset pack install-time, niente download separati | Versione dei modelli legata all'app | §7 |
| 22/10/2026 09:10 | Vendita una tantum sul Play Store | Scelta del titolare | §8 |
| 22/10/2026 09:10 | Esclusi multi-tenant, login e PostgreSQL | App consumer offline e senza account | §6 |

*22/10/2026 09:14 – Aggiunte le funzioni di comandi vocali, riscrittura a voce, scorciatoie vocali e traduzione offline (§5.9–§5.12), su richiesta del titolare. Esclusi l'adattamento del tono per app e la suite di test con voci simulate: i test di dettatura li esegue il titolare.*

*22/10/2026 09:16 – Aggiunta la piattaforma Windows (§2.4) con motore AI condiviso in C++, e interfaccia ridotta a una sola icona su entrambe le piattaforme (§2.5), su richiesta del titolare.*

*22/10/2026 09:17 – Versioni Android e Windows vendute separatamente, ciascuna con acquisto una tantum sul proprio store (§2.4), su decisione del titolare.*

*22/10/2026 09:17 – Aggiunti la verifica della specifica (capitolo 15) e il piano di sviluppo in nove fasi con gate (capitolo 16).*

*22/10/2026 09:20 – Punto 2 del capitolo 15 risolto: prova gratuita di 30 giorni, verifica dell'acquisto online a ogni avvio solo durante la prova, poi nessun accesso a internet. Su Android: app gratuita con acquisto in-app una tantum (§8.1), su decisione del titolare.*

*22/10/2026 09:21 – Punto 6 del capitolo 15 risolto: su Android icona flottante con servizio di accessibilità e overlay, invece della tastiera (§5.1-bis), su decisione del titolare. Rischio: approvazione Google Play della dichiarazione di accessibilità.*

*22/10/2026 09:22 – Punto 5 del capitolo 15 risolto: riferimento Windows portatile con 16 GB di RAM, test su Surface Snapdragon ARM, build ARM64 e x64 (§11.1).*

*22/10/2026 09:23 – Punto 8 del capitolo 15: lingue di traduzione fissate a inglese e spagnolo (§5.12). La qualità va comunque provata dal titolare.*

*22/10/2026 09:24 – Punto 9 del capitolo 15 risolto: si usa Whisper medium, modelli caricati in sequenza, obiettivo di risposta alzato a 5 secondi (§7, §11), su decisione del titolare. Tutti i punti del capitolo 15 sono ora risolti o assegnati.*

*22/10/2026 09:25 – Punti 1, 3, 4 e 7 del capitolo 15 risolti: moduli condivisi e Windows (§4.3), pacchettizzazione Windows (§7.1), vendita su Microsoft Store (§8.2), fallback dell'icona su Windows (§2.6). Fase 0 del piano completata, in attesa di approvazione del titolare.*

*22/10/2026 09:27 – Sviluppo parallelizzato su cinque agenti coordinati da un orchestratore, con contratto del motore e mock come primo passo (§16.1), su richiesta del titolare.*

*22/10/2026 09:29 – Contro il riavvio della prova reinstallando l'app: su Android Block Store API di Google (§8.1); su Windows la prova è gestita dal Microsoft Store e legata all'account Microsoft. Su decisione del titolare.*

*22/10/2026 09:30 – Piano di sviluppo (capitolo 16) allineato alle decisioni: icona flottante Android nella fase 3, fallback e build ARM64/x64 nella fase 5, prova di 30 giorni con Block Store e Store API nella fase 7.*

*22/10/2026 09:42 – Scelto il nome commerciale Zzitto per Android e Windows, su decisione del titolare. Verifiche di nome, marchio e domini da completare prima della pubblicazione.*

*22/10/2026 11:15 – Avvio dello sviluppo in modalità silenziosa, su decisione del titolare: mockup (§9) e gate di fase (capitolo 16) saltati; il titolare rivede il lavoro in un'unica pull request finale da `develop` a `main`.*

*22/10/2026 11:15 – Niente GitHub Actions né pipeline CI (§13), su decisione del titolare. Build e test con script locali. Il codice Android e Windows viene scritto senza poterlo compilare nell'ambiente dell'agente (nessun accesso ad Android SDK, Maven, NuGet e Hugging Face): la prima compilazione avviene sulla macchina del titolare. Il motore C++ viene compilato e testato nell'ambiente dell'agente.*

*22/10/2026 11:15 – Git: un branch per ogni agente (§16.1), unito nel branch `develop`; pull request finale `develop` → `main` per la revisione del titolare.*

*22/10/2026 11:15 – Identificativi confermati dal titolare: package Android `com.zaramella.zzitto`, pacchetto Windows `Zaramella.Zzitto`, editore Zaramella Consulting Srl.*

*22/10/2026 11:15 – Contratto del motore definito in `core-native/include/zz_engine.h` (API versione 1), con motore finto in `core-native/mock/` e interfacce condivise `LicenseGate` (Android, modulo `:core`) e `ILicenseService` (Windows, `Zzitto.Core`). Dettagli in `docs/engine-contract.md`.*

*22/10/2026 12:17 – Integrazione del lavoro dei cinque agenti in `develop`. Decisioni registrate:*

| Data e ora | Decisione | Motivazione | Riferimento |
| --- | --- | --- | --- |
| 22/10/2026 12:17 | whisper.cpp (commit 231638a) e llama.cpp (tag b11815) come sottomoduli; un solo ggml condiviso, quello di llama.cpp | Evitare simboli duplicati | §3, §4.3 |
| 22/10/2026 12:17 | Prompt di sistema in inglese con esempi in italiano; template ufficiale Phi-4-mini; contesto 3072 token, testo diviso in pezzi da 1200 caratteri | Qualità e limiti di memoria | §5.3, §7 |
| 22/10/2026 12:17 | Il timeout di correzione copre solo l'inferenza; se la riscrittura fallisce la selezione resta invariata | Comportamento prevedibile | §5.3, §5.10 |
| 22/10/2026 12:17 | `zz_engine.dll` con runtime statico `/MT`; su Windows ARM64 serve clang-cl; GPU/NPU disattivate di default fino ai test sui dispositivi | Nessuna dipendenza dal runtime VC++; prestazioni da misurare | §7.1, §11.1 |
| 22/10/2026 12:17 | Android: overlay `TYPE_ACCESSIBILITY_OVERLAY`, niente SYSTEM_ALERT_WINDOW né foreground service; campi password esclusi; modelli copiati in `noBackupFilesDir` (circa 3 GB in più); `allowBackup=false`; cronologia attiva di default | Meno permessi, privacy | §5.1-bis, §5.6, §7, §10 |
| 22/10/2026 12:17 | Android: appunti precedenti non sempre leggibili da Android 10, quindi dopo l'incolla vengono svuotati invece che ripristinati | Limite di sistema | §5.1-bis |
| 22/10/2026 12:17 | Motore finto solo nelle build Debug, motore reale in Release su Android e Windows | Rilasci sempre col motore vero | §16.1 |
| 22/10/2026 12:17 | Phi-4-mini diviso in due asset pack install-time; bundle Microsoft Store circa 6,2 GB, sotto il limite di 25 GB | Limite di 1,5 GB per asset pack | §7, §7.1 |
| 22/10/2026 12:17 | Phi-4-mini quantizzato in locale dai pesi Microsoft (nessun GGUF ufficiale), revisioni Hugging Face fissate in `tools/models/models.conf` | Riproducibilità | §3, §7 |
| 22/10/2026 12:17 | Licenze: sblocco salvato dopo il riconoscimento dell'acquisto e mai più verificato (un rimborso non lo revoca); difesa contro l'orologio spostato indietro; su Windows con Store irraggiungibile e nessuna lettura precedente la dettatura resta bloccata | Semplicità e protezione della prova | §8.1, §8.2 |
| 22/10/2026 12:17 | Windows: Windows App SDK 1.8 su .NET 8 e Visual Studio 2022; UI Automation SetValue solo sui campi Edit nativi, altrimenti SendInput; "a capo" come Maiusc+Invio; Esc annulla la dettatura; testi negli appunti esclusi da cronologia e cloud; Ctrl+C per leggere la selezione mai nelle console; licenza simulata in Debug | Compatibilità e sicurezza | §2.4, §2.6, §5.2, §5.9, §5.10, §8.2, §10 |
| 22/10/2026 12:17 | Dipendenza System.Security.Cryptography.ProtectedData 8.0.0 (MIT) per DPAPI; xunit (Apache 2.0) per i test | Cifratura locale su Windows | §2.2, §10 |
| 22/10/2026 12:17 | Versione unica in `tools/release/version.properties`, prima versione 0.1.0; firma dell'AAB con `jarsigner` | Versionamento semantico | §13 |
| 22/10/2026 12:17 | Mock: i ritorni a capo dei comandi vocali non vengono più persi dalla pulizia; `zz_result_free` azzera i testi prima di liberarli | Correzioni emerse in integrazione | §5.9, §10 |

*Punti aperti dopo l'integrazione (22/10/2026 12:17): prima compilazione Android e Windows sulla macchina del titolare; benchmark con `zz_bench` su Galaxy S24 e Surface Snapdragon (eventuale `+i8mm` su ARM); conferma degli SHA-256 dei modelli; verifica dell'API `StoreBytesData.Builder.setKey` e delle firme di Billing 8; Publisher di Partner Center; URL definitivo dell'informativa privacy (segnaposto `https://zzitto.it/privacy`); percorsi dei modelli con caratteri non ASCII su Windows non supportati da whisper.cpp/llama.cpp (i percorsi del pacchetto MSIX sono ASCII); funzione facoltativa nel contratto per riusare la verifica degli hash già fatta.*

*22/10/2026 12:37 – Revisioni indipendenti di compilabilità. Android: aggiunto il plugin `foojay-resolver-convention` 1.0.0 per procurare il JDK 17 richiesto dai moduli (§3); in debug col motore finto l'app parte anche senza i modelli negli asset (§16.1). Windows: il motore reale ARM64 si compila con il toolset ClangCL (serve il componente "Strumenti C++ Clang per Windows" di Visual Studio), DLL del motore separate per app e test, test sempre sul motore finto, attributi `Executable` ed `EntryPoint` nello startup task (§2.4, §11.1, §12).*

*22/10/2026 15:08 – Prima compilazione sul Surface Snapdragon del titolare (Windows 11 ARM64, Visual Studio 2022 17.13, Android SDK con NDK 28.2). Esiti e correzioni:*

| Data e ora | Decisione o esito | Motivazione | Riferimento |
| --- | --- | --- | --- |
| 22/10/2026 15:08 | APK Android di debug compilato (motore finto e motore reale); app Windows ARM64 compilata e avviata senza errori | Prima build reale | §12, §13 |
| 22/10/2026 15:08 | Moduli Android compilati col JDK di Android Studio con bytecode Java 17 (`jvmTarget`), senza toolchain JDK 17 dedicata; NDK fissato a 28.2.13676358 | Il download automatico del JDK 17 falliva | §3 |
| 22/10/2026 15:08 | Debug Android con `-PzzRealEngine=true` usa il motore reale e legge i modelli anche da `Android/data/<app>/files/models` (copiati via USB) | Test sul telefono senza Play Store | §7, §16.1 |
| 22/10/2026 15:08 | Motore reale Windows ARM64 compilato con clang-cl di LLVM 23 portatile, CMake e Ninja di Visual Studio; DLL senza dipendenze esterne (solo KERNEL32 e ADVAPI32) | Su ARM64 ggml richiede clang | §11.1 |
| 22/10/2026 15:08 | Correzioni di compilazione: import di `Properties` in Gradle, condizione MSBuild annidata, `NOMINMAX` su Windows, `unsafe` e lambda in C#, `Executable` esplicito nello startup task, opzioni di avviso per clang-cl, DLL accanto ai test su Windows | Errori emersi alla prima build | §12 |
| 22/10/2026 15:08 | Per i test locali Phi-4-mini Q4_K_M è stato preso dal GGUF pubblicato da bartowski su Hugging Face; il rilascio resta con la quantizzazione dai pesi Microsoft (`tools/models`). Gli hash di test non sono nel repository | Rapidità dei test | §3, §7 |

*Benchmark del 22/10/2026 15:03 sul Surface Snapdragon (CPU, senza GPU/NPU), frase italiana sintetica di 10,2 s con esitazioni e autocorrezione:*

| Prova | Trascrizione | Correzione | Totale | RAM di picco |
| --- | --- | --- | --- | --- |
| Pulito, 1ª esecuzione | 6,4 s | 11,4 s | 17,8 s | 5,2 GB |
| Pulito, 2ª esecuzione | 7,4 s | 8,3 s | 15,8 s | 5,2 GB |
| Traduzione EN, 1ª esecuzione | 7,6 s | 12,5 s | 20,1 s | 5,2 GB |
| Traduzione EN, 2ª esecuzione | 8,4 s | 11,3 s | 19,8 s | 5,2 GB |

*Esito: il testo corretto è giusto (ripetizione e autocorrezione "anzi no, alle 11" risolte), ma l'obiettivo del §11 (meno di 5 s) non è raggiunto, e la traduzione in inglese ha invertito l'autocorrezione ("at 11 … no, at 10"). Punti aperti: accelerazione GPU/NPU, Whisper small o turbo, prompt di traduzione applicato dopo la correzione, verifica della RAM di picco con il caricamento sequenziale.*

*22/10/2026 16:51 – Ottimizzazione della reattività su richiesta del titolare ("troppi secondi"). Decisioni:*

| Data e ora | Decisione | Motivazione | Riferimento |
| --- | --- | --- | --- |
| 22/10/2026 16:51 | Modelli sempre residenti in RAM (`sequential_loading = 0` predefinito su Android e Windows); il caricamento sequenziale resta per dispositivi con poca memoria | Il ricaricamento a ogni dettatura costava oltre 10 s | §7, §11 |
| 22/10/2026 16:51 | Prompt di sistema valutato subito dopo il caricamento di Phi-4-mini e tenuto in cache | Alla correzione resta solo il testo dettato | §5.3, §11 |
| 22/10/2026 16:51 | Whisper: una sola ipotesi greedy, nessun ritentativo a temperatura più alta, contesto dell'encoder proporzionale alla durata dell'audio | Trascrizione più veloce senza perdita di qualità sul test | §11 |
| 22/10/2026 16:51 | Windows: il motore chiede a Windows di non limitare il processo (EcoQoS) | L'app sta nell'area di notifica e veniva rallentata | §2.4, §11 |
| 22/10/2026 16:51 | 6 thread di calcolo predefiniti su desktop (6 su Android come prima) | Con 8 o più thread picchi fino a 10 s quando altre app occupano i core | §11, §11.1 |
| 22/10/2026 16:51 | API 2 del contratto: dettatura in streaming (`zz_stream_*`), trascrizione a pezzi nelle pause mentre l'utente parla; app Android e Windows aggiornate con ripiego su `zz_dictate`; la riscrittura resta su `zz_rewrite` | Allo stop restano solo l'ultimo pezzo e la correzione | §5.2, §11, §16.1 |
| 22/10/2026 16:51 | Traduzione: prima si risolvono le autocorrezioni, poi si traduce solo il testo finale | Errore "at 11 … no, at 10" del primo benchmark | §5.12 |

*Latenza percepita (dallo stop al testo inserito) sul Surface Snapdragon X, frase di 10,2 s, CPU:*

| Configurazione | Latenza percepita |
| --- | --- |
| Prima delle ottimizzazioni (Whisper medium) | 16–18 s |
| Modelli residenti, senza streaming (Whisper medium, 8 thread) | circa 4 s, con picchi fino a 13 s |
| Streaming, Whisper medium, 6 thread | 2,0–3,3 s |
| Streaming, Whisper small, 8 thread | 1,1–1,6 s (qualità leggermente inferiore: resta "E ma allora") |

*Punti aperti: misure sul Galaxy S24; eventuale opzione "massima velocità" con Whisper small; accelerazione GPU/NPU.*

*22/10/2026 17:19 – Windows, su segnalazione del titolare dopo le prime prove: scorciatoia globale predefinita Ctrl+Alt+Z (Ctrl+Alt+Spazio attivava anche l'app Claude), modificabile dalle impostazioni e dal menu dell'area di notifica; l'icona del microfono resta ferma dove l'utente la trascina (predefinito) invece di seguire il cursore, con "vicino al testo" e "nascosta" come alternative; migrazione automatica delle impostazioni salvate (§2.4, §2.6).*

