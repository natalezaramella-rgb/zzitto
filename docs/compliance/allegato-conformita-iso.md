# Allegato di conformità ISO/IEC 27001, 27017 e 27018 – Zzitto

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §1, §2.2, §5.6, §6, §7, §8.1, §10, §12, §13, §16.1 (fase 6).
Proprietario: agente E. Stato: bozza da sottoporre al titolare (gate della fase 6, capitolo 16).*

## 1. Scopo e perimetro

Questo allegato documenta come i principi delle norme ISO/IEC 27001:2022, ISO/IEC 27017:2015 e ISO/IEC 27018:2019 sono
applicati all'app **Zzitto** (Android e Windows), come richiesto dal §10 della specifica, e contiene la **dichiarazione di
applicabilità** (SoA) motivata, compresi i controlli non applicabili.

- **Perimetro:** il prodotto Zzitto (codice, modelli AI, pacchetti per gli store) e il suo ciclo di sviluppo e rilascio
  presso Zaramella Consulting Srl.
- **Fuori perimetro:** il sistema di gestione aziendale di Zaramella Consulting Srl nel suo complesso (risorse umane,
  sedi, rete aziendale). I controlli che dipendono da esso sono indicati come "aziendale" e rimandano alle procedure
  dell'azienda.
- **Natura del prodotto:** app consumer **senza server**. Nessun dato degli utenti viene ricevuto, conservato o
  trattato dal titolare. L'unico traffico di rete è la verifica della licenza tramite Google Play e Microsoft Store
  durante la prova (§8.1) e il salvataggio della data di prova in Block Store su Android.

Questo documento non è una certificazione: descrive misure e prove a supporto di un eventuale audit.

## 2. Flussi di dati

| Dato | Origine | Elaborazione | Conservazione | Uscita dal dispositivo |
|---|---|---|---|---|
| Audio | Microfono, solo dopo il tocco o la scorciatoia dell'utente | Motore `core-native` (whisper.cpp) | Solo RAM; buffer azzerato e liberato dopo la trascrizione (§10) | Mai |
| Testo grezzo e corretto | Motore | Motore (llama.cpp + Phi-4-mini) | RAM; facoltativamente cronologia cifrata (§5.6) | Mai |
| Testo selezionato (riscrittura, §5.10) | Campo attivo tramite servizio di accessibilità / UI Automation | Motore | RAM | Mai |
| Dizionario, scorciatoie | Utente | App | Database cifrato (Room + SQLCipher su Android) | Mai |
| Stato della licenza | Google Play Billing / Microsoft Store | App (agente D) | Locale, cifrato | Richiesta allo store durante la prova |
| Data di inizio prova (Android) | App | App | Block Store | Backup dell'account Google, solo con crittografia end-to-end |
| Modelli AI | Pacchetto dell'app | Motore, dopo verifica SHA-256 | Sola lettura | — |

## 3. Applicazione dei principi richiesti dal §10

### 3.1 Minimizzazione dei dati

- Unico permesso "pericoloso": `RECORD_AUDIO`. Gli altri (servizio di accessibilità, sovrapposizione, internet) sono
  limitati allo stretto necessario e spiegati all'utente (§5.1-bis, §8.1).
- Microfono attivo solo durante la dettatura richiesta dall'utente; nessun ascolto continuo o parola di attivazione.
- Audio mai scritto su disco; nessun file temporaneo audio.
- Servizio di accessibilità configurato con i soli eventi necessari (vedi `docs/store/google-play-accessibilita.md`);
  nessuna lettura dei tasti, nessuna lettura di contenuti oltre il campo attivo e il testo selezionato.
- Cronologia disattivabile, con conservazione configurabile e cancellazione totale in un tocco (§5.6).
- Nessun account, nessun identificativo, nessuna libreria di statistiche, pubblicità o segnalazione degli arresti.
- Log senza testo dettato né audio (§10); i log di rilascio non contengono dati degli utenti.

### 3.2 Controllo degli accessi

- **Sul dispositivo:** i dati sono nella sandbox dell'app (Android) o nella cartella dati del pacchetto MSIX (Windows);
  nessun componente esportato senza permesso; il servizio di accessibilità è protetto da `BIND_ACCESSIBILITY_SERVICE`.
- **Sul codice:** repository privato; un branch per agente; unione in `develop` e revisione del titolare sulla pull request
  `develop` → `main` (capitolo 14). Accesso al repository solo agli autorizzati dal titolare.
- **Chiavi e account:** la chiave di upload Android e le credenziali di Play Console e Partner Center sono del titolare,
  fuori dal repository, con autenticazione a due fattori sugli account (`docs/release-guide.md`). La chiave di firma
  dell'app è custodita da Google (Play App Signing); il pacchetto Windows è firmato dal Microsoft Store.

### 3.3 Cifratura

- Cronologia, dizionario e scorciatoie: SQLCipher (AES-256) con chiave casuale generata sul dispositivo e protetta
  dall'Android Keystore (§10); su Windows chiave protetta con DPAPI dell'utente o API equivalente (agente C).
- Sblocco della licenza salvato cifrato (§8.1).
- Traffico verso gli store: TLS gestito dalle librerie di Google e Microsoft; l'app non apre altre connessioni.
- Block Store: backup nel cloud solo se la crittografia end-to-end è disponibile (condizione per l'agente D).
- Integrità dei modelli: SHA-256 nel codice, verificato prima del caricamento (§7); un modello alterato non viene caricato.

### 3.4 Tracciabilità delle modifiche

- Git con commit firmati dall'autore e messaggi con riferimento alla specifica; registro decisioni nel capitolo 14.
- Ogni documento riporta data e ora di aggiunta (regola del progetto).
- `CHANGELOG.md` con versione semantica, codice versione e **versione dei modelli** (§13).
- `tools/models/models.lock`: fonte, revisione, strumenti e SHA-256 dei modelli inclusi.
- Ogni pacchetto prodotto da `tools/release/` è accompagnato dal suo SHA-256 in `dist/`.
- Tracce di rilascio Play (interno → chiuso → produzione con rilascio graduale) e invii di Partner Center conservano lo storico.

### 3.5 Gestione delle vulnerabilità delle dipendenze

Procedura prima di ogni rilascio (responsabile: agente E, approvazione: titolare):

1. Elenco aggiornato dei componenti in `THIRD_PARTY_LICENSES.md` e delle versioni in `android/gradle/libs.versions.toml`,
   nei progetti .NET e in `tools/models/models.conf` (versione di llama.cpp e whisper.cpp).
2. Ricerca di vulnerabilità note su https://osv.dev e sul GitHub Advisory Database per ogni componente e versione
   (in particolare llama.cpp e whisper.cpp, che leggono file GGUF/GGML: i difetti di parsing sono mitigati dalla verifica
   SHA-256 dei modelli prima del caricamento).
3. Aggiornamento delle dipendenze con correzioni di sicurezza, nuovo giro di test (`tools/release/run-tests.*`), voce nel
   changelog.
4. Verifica della versione della Play Billing Library rispetto alle scadenze di Google Play.
5. Raccomandazioni per i proprietari dei moduli: verifica delle dipendenze Gradle (`gradle/verification-metadata.xml`) e
   blocco delle versioni; file `packages.lock.json` con `RestoreLockedMode` per NuGet.

Segnalazioni di sicurezza dall'esterno: indirizzo email di contatto indicato nelle schede degli store; risposta e
correzione tramite nuova versione negli store (non esistono server da correggere).

## 4. Dichiarazione di applicabilità – ISO/IEC 27001:2022, Allegato A

Legenda: **A** = applicabile al prodotto; **Az** = applicabile, gestito a livello aziendale (fuori dal perimetro);
**N/A** = non applicabile, con motivazione.

### 4.1 Controlli organizzativi (5.1–5.37)

| Controllo | Titolo | Esito | Motivazione e misura |
|---|---|---|---|
| 5.1 | Politiche per la sicurezza delle informazioni | A | Specifica §10 e questo allegato; regole fisse in `CLAUDE.md` |
| 5.2 | Ruoli e responsabilità | A | Titolare responsabile; agenti A–E con perimetri definiti (§16.1) |
| 5.3 | Separazione dei compiti | A | Chi sviluppa (agenti) non pubblica: il titolare rivede la PR finale e pubblica |
| 5.4 | Responsabilità della direzione | Az | Impegno della direzione di Zaramella Consulting Srl |
| 5.5 | Contatti con le autorità | Az | Garante privacy per eventuali violazioni (improbabili: nessun dato raccolto) |
| 5.6 | Contatti con gruppi di interesse | A | Monitoraggio di avvisi di sicurezza di llama.cpp, whisper.cpp, Android, Windows |
| 5.7 | Threat intelligence | A | Consultazione di OSV e GitHub Advisory prima di ogni rilascio (§3.5) |
| 5.8 | Sicurezza nella gestione dei progetti | A | Requisiti di sicurezza nella specifica (§10) e gate di fase (capitolo 16) |
| 5.9 | Inventario delle informazioni e degli asset | A | `THIRD_PARTY_LICENSES.md`, `models.lock`, struttura del repository |
| 5.10 | Uso accettabile degli asset | Az | Regole aziendali; per il prodotto: licenze ammesse (§2.2) |
| 5.11 | Restituzione degli asset | Az | Procedure aziendali |
| 5.12 | Classificazione delle informazioni | A | Classi: pubblico (schede, informativa), interno (codice), riservato (chiavi, account) |
| 5.13 | Etichettatura | A | Segreti mai nel repository; `.gitignore` per chiavi e modelli |
| 5.14 | Trasferimento delle informazioni | A | Nessun trasferimento di dati utente; pacchetti caricati solo sugli store ufficiali via TLS |
| 5.15 | Controllo degli accessi | A | §3.2 |
| 5.16 | Gestione delle identità | A | Account nominativi su GitHub, Play Console, Partner Center |
| 5.17 | Informazioni di autenticazione | A | Password e chiavi solo in variabili d'ambiente o file fuori dal repository; 2FA |
| 5.18 | Diritti di accesso | A | Accessi a repository e console concessi e revocati dal titolare |
| 5.19 | Sicurezza nelle relazioni con i fornitori | A | Fornitori: Google (Play, Billing, Block Store), Microsoft (Store, Windows App SDK); valutati in `THIRD_PARTY_LICENSES.md` |
| 5.20 | Sicurezza negli accordi con i fornitori | A | Accettazione dei termini per sviluppatori di Google Play e Microsoft Store |
| 5.21 | Catena di fornitura ICT | A | Download dei modelli a revisione fissa con SHA-256; dipendenze da repository ufficiali; licenze verificate |
| 5.22 | Monitoraggio dei servizi dei fornitori | A | Controllo delle modifiche alle norme degli store prima di ogni rilascio |
| 5.23 | Sicurezza nell'uso dei servizi cloud | A | Come cliente cloud: Block Store solo con E2EE; nessun altro servizio cloud (§5) |
| 5.24 | Pianificazione della gestione degli incidenti | A | Canale email di segnalazione; rilascio correttivo tramite gli store |
| 5.25 | Valutazione degli eventi | A | Valutazione del titolare sulle segnalazioni ricevute |
| 5.26 | Risposta agli incidenti | A | Ritiro o aggiornamento della versione nelle tracce degli store; rilascio graduale per limitare l'impatto |
| 5.27 | Apprendimento dagli incidenti | A | Voce nel capitolo 14 e test di regressione |
| 5.28 | Raccolta delle evidenze | A | Storico git, changelog, SHA-256 dei pacchetti |
| 5.29 | Sicurezza durante le interruzioni | N/A | Nessun servizio online da mantenere: l'app funziona offline; senza connessione la prova continua (§8.1) |
| 5.30 | Prontezza ICT per la continuità | Az | Backup del repository e delle chiavi (vedi 8.13) |
| 5.31 | Requisiti legali e contrattuali | A | GDPR (`docs/privacy/`), norme degli store, licenze (§2.2) |
| 5.32 | Diritti di proprietà intellettuale | A | `THIRD_PARTY_LICENSES.md`; verifica del marchio Zzitto (`docs/release-guide.md`) |
| 5.33 | Protezione delle registrazioni | A | Storico git e documenti datati, non riscritti |
| 5.34 | Privacy e protezione dei dati personali | A | Privacy by design (§1, §10); informativa; nessun dato raccolto |
| 5.35 | Riesame indipendente | A | Revisione del titolare sulla PR finale; consigliato un riesame esterno prima della produzione |
| 5.36 | Conformità alle politiche | A | Script di rilascio che bloccano test falliti, motore finto, hash errati (`tools/release/`) |
| 5.37 | Procedure operative documentate | A | `docs/release-guide.md`, `tools/*/README.md` |

### 4.2 Controlli sulle persone (6.1–6.8)

| Controllo | Titolo | Esito | Motivazione |
|---|---|---|---|
| 6.1 | Screening | Az | Procedure aziendali di selezione |
| 6.2 | Termini e condizioni di impiego | Az | Contratti aziendali |
| 6.3 | Consapevolezza e formazione | Az | Formazione aziendale (es. GDPR) |
| 6.4 | Processo disciplinare | Az | Regolamento aziendale |
| 6.5 | Responsabilità dopo la cessazione | Az | Revoca degli accessi (5.18) |
| 6.6 | Accordi di riservatezza | Az | Per collaboratori con accesso al repository |
| 6.7 | Lavoro da remoto | Az | Policy aziendale |
| 6.8 | Segnalazione degli eventi di sicurezza | A | Canale email nelle schede degli store; segnalazione interna al titolare |

### 4.3 Controlli fisici (7.1–7.14)

| Controllo | Titolo | Esito | Motivazione |
|---|---|---|---|
| 7.1–7.6 | Perimetri, accessi fisici, uffici, monitoraggio, minacce ambientali, aree sicure | Az | Il prodotto non ha data center né server propri; valgono le misure delle sedi aziendali |
| 7.7 | Scrivania e schermo puliti | Az | Policy aziendale |
| 7.8 | Posizionamento e protezione delle apparecchiature | Az | Postazioni di sviluppo aziendali |
| 7.9 | Sicurezza degli asset fuori sede | A | Portatili e dispositivi di test (Galaxy S24, Surface) con cifratura del disco e blocco schermo |
| 7.10 | Supporti di memorizzazione | A | Backup della chiave di upload su supporto cifrato custodito dal titolare |
| 7.11 | Servizi di supporto (alimentazione, ecc.) | N/A | Nessuna infrastruttura di produzione |
| 7.12 | Sicurezza del cablaggio | N/A | Nessuna infrastruttura di produzione |
| 7.13 | Manutenzione delle apparecchiature | Az | Procedure aziendali |
| 7.14 | Dismissione sicura | A | Cancellazione sicura dei dispositivi di test e dei supporti con chiavi |

### 4.4 Controlli tecnologici (8.1–8.34)

| Controllo | Titolo | Esito | Motivazione e misura |
|---|---|---|---|
| 8.1 | Dispositivi endpoint degli utenti | A | Sull'app: dati cifrati, sandbox, nessun dato in chiaro su disco |
| 8.2 | Diritti di accesso privilegiati | A | L'app Windows non chiede privilegi di amministratore (§2.6); su Android nessun permesso di sistema oltre quelli dichiarati |
| 8.3 | Limitazione dell'accesso alle informazioni | A | Servizio di accessibilità limitato al campo attivo (§3.1) |
| 8.4 | Accesso al codice sorgente | A | Repository privato, accessi gestiti dal titolare |
| 8.5 | Autenticazione sicura | A | 2FA su GitHub, Play Console, Partner Center. Nell'app: nessun login (§6) |
| 8.6 | Gestione della capacità | A | Requisiti di RAM e spazio (§11, §11.1); verifica dello spazio libero prima della ricomposizione dei modelli |
| 8.7 | Protezione dal malware | A | Pacchetti solo dagli store ufficiali; modelli verificati con SHA-256; postazioni con antivirus |
| 8.8 | Gestione delle vulnerabilità tecniche | A | §3.5 |
| 8.9 | Gestione della configurazione | A | Versioni fissate (`libs.versions.toml`, `models.conf`), configurazione nel repository |
| 8.10 | Cancellazione delle informazioni | A | Audio distrutto dopo la trascrizione; cancellazione della cronologia in un tocco; dati rimossi con la disinstallazione |
| 8.11 | Mascheramento dei dati | N/A | Nessun dato utente in ambienti di sviluppo, test o log; nessun dato da mascherare |
| 8.12 | Prevenzione della fuga di dati | A | Nessun permesso o libreria che trasmetta dati; `aabcheck.py` blocca chiavi nei pacchetti; R8 e controlli MASVS |
| 8.13 | Backup delle informazioni | A | Repository e chiave di upload con copia di sicurezza; i dati degli utenti restano sotto il loro controllo (esclusi dal backup cloud Android: vedi MASVS-STORAGE-2) |
| 8.14 | Ridondanza | N/A | Nessun servizio da mantenere disponibile; la distribuzione è a carico degli store |
| 8.15 | Logging | A | Log solo tecnici, senza testo dettato né audio (§10) |
| 8.16 | Monitoraggio | A | Monitoraggio di Android vitals e dei rapporti di Partner Center sugli arresti, senza dati personali |
| 8.17 | Sincronizzazione degli orologi | A | Calcolo della prova: data dello store o di Block Store; tolleranza alle modifiche dell'orologio (agente D) |
| 8.18 | Programmi di utilità privilegiati | N/A | Nessun programma privilegiato usato dall'app |
| 8.19 | Installazione di software sui sistemi operativi | A | Installazione solo tramite store; nessun download di codice o modelli a runtime (§7) |
| 8.20 | Sicurezza delle reti | A | Unica connessione verso gli store, via TLS; nessun server in ascolto |
| 8.21 | Sicurezza dei servizi di rete | A | Servizi di Google e Microsoft, valutati come fornitori (5.19) |
| 8.22 | Segregazione delle reti | N/A | Nessuna rete di produzione propria |
| 8.23 | Filtraggio web | Az | Postazioni aziendali |
| 8.24 | Uso della crittografia | A | §3.3 |
| 8.25 | Ciclo di vita dello sviluppo sicuro | A | Specifica, test a ogni build, revisione finale, controlli di rilascio |
| 8.26 | Requisiti di sicurezza delle applicazioni | A | §10 della specifica e checklist MASVS (`owasp-masvs.md`) |
| 8.27 | Architettura sicura | A | Elaborazione locale, motore isolato dietro API C (`zz_engine.h`), minimo privilegio |
| 8.28 | Codifica sicura | A | Regole del progetto; R8; nessun segreto nel codice; analisi statica (lint, avvisi come errori su Windows) |
| 8.29 | Test di sicurezza | A | Test unitari e di integrazione; verifica MASVS prima della produzione; test di integrità dei modelli |
| 8.30 | Sviluppo esternalizzato | A | Sviluppo con agenti AI sotto supervisione del titolare; ogni modifica revisionata nella PR finale |
| 8.31 | Separazione degli ambienti | A | Motore finto per lo sviluppo, motore reale per il rilascio; build debug e release separate; tracce di test degli store |
| 8.32 | Gestione delle modifiche | A | Branch per agente, PR, changelog, capitolo 14 |
| 8.33 | Informazioni di test | A | Registrazioni campione per i test del titolare, senza dati personali di terzi; nessun dato reale degli utenti |
| 8.34 | Protezione durante gli audit | N/A | Nessun sistema di produzione da sottoporre ad audit tecnico |

## 5. ISO/IEC 27017:2015 – controlli aggiuntivi per i servizi cloud

Zzitto non è un servizio cloud e Zaramella Consulting Srl non è un fornitore di servizi cloud per questo prodotto.
L'unico rapporto cloud è come **cliente** di servizi di Google (Play Billing, Block Store) e Microsoft (Store, licenze).

| Controllo | Titolo | Esito | Motivazione |
|---|---|---|---|
| Linee guida dei controlli 27002 per il cliente cloud | — | A | Applicate tramite 5.19–5.23 (valutazione dei fornitori, uso del cloud limitato e cifrato) |
| CLD.6.3.1 | Ruoli condivisi nel cloud | A | Ripartizione: Google/Microsoft gestiscono pagamenti, licenze e backup; Zzitto legge solo lo stato della licenza e salva solo la data di prova |
| CLD.8.1.5 | Rimozione degli asset del cliente cloud | A | La data di prova in Block Store si elimina cancellando il backup dell'app dall'account Google (informativa §4) |
| CLD.9.5.1 | Segregazione negli ambienti virtuali | N/A | Nessun ambiente virtuale gestito |
| CLD.9.5.2 | Hardening delle macchine virtuali | N/A | Nessuna macchina virtuale |
| CLD.12.1.5 | Sicurezza operativa dell'amministratore | N/A | Nessuna amministrazione di servizi cloud |
| CLD.12.4.5 | Monitoraggio dei servizi cloud | A (limitato) | Controllo delle comunicazioni di Google e Microsoft sulle API usate (deprecazioni Billing, Store API) |
| CLD.13.1.4 | Allineamento reti virtuali e fisiche | N/A | Nessuna rete virtuale |

## 6. ISO/IEC 27018:2019 – protezione dei dati personali nel cloud pubblico

La norma riguarda chi tratta dati personali **come responsabile del trattamento in un cloud pubblico**. Zaramella
Consulting Srl non tratta dati personali degli utenti per conto di terzi né nel cloud: i dati restano sul dispositivo.
I principi della norma (derivati da ISO/IEC 29100) sono comunque applicati alla progettazione.
L'elenco segue l'Allegato A della norma; la numerazione va confrontata con la copia ufficiale prima di un audit.

| Controllo | Titolo | Esito | Motivazione |
|---|---|---|---|
| A.2.1 | Cooperazione per i diritti degli interessati | A (by design) | Diritti esercitabili direttamente nell'app (informativa §10) |
| A.3.1 | Finalità del trattamento nel cloud pubblico | N/A | Nessun trattamento nel cloud da parte del titolare |
| A.3.2 | Uso commerciale dei dati | A (by design) | Nessun uso per marketing o pubblicità; nessun addestramento di modelli |
| A.5.1 | Cancellazione sicura dei file temporanei | A | Audio solo in RAM, nessun file temporaneo; file di ricomposizione dei modelli scritti in modo atomico (nessun dato personale) |
| A.6.1 | Notifica di richieste di divulgazione | N/A | Il titolare non possiede dati da divulgare |
| A.6.2 | Registro delle divulgazioni | N/A | Come sopra |
| A.8.1 | Divulgazione dei sub-responsabili | A | L'informativa indica Google e Microsoft come titolari autonomi per acquisti e backup |
| A.10.1 | Notifica di violazioni dei dati | A (limitato) | Procedura aziendale; nessun archivio centrale da violare |
| A.10.2 | Conservazione delle politiche di sicurezza | A | Documenti versionati in git |
| A.10.3 | Restituzione, trasferimento e smaltimento | A (by design) | Dati sotto il controllo dell'utente; rimossi con la disinstallazione |
| A.11.1 | Accordi di riservatezza | Az | Vedi 6.6 |
| A.11.2 | Limitazione delle copie cartacee | N/A | Nessuna copia cartacea di dati utente |
| A.11.3 | Controllo e registrazione dei ripristini | N/A | Nessun ripristino di dati utente da parte del titolare |
| A.11.4 | Protezione dei supporti che lasciano la sede | N/A | Nessun dato utente su supporti del titolare |
| A.11.5 | Supporti portatili non cifrati | A | Divieto di copiare chiavi su supporti non cifrati |
| A.11.6 | Cifratura dei dati trasmessi su reti pubbliche | A | Solo traffico verso gli store, via TLS; Block Store con E2EE |
| A.11.7 | Smaltimento sicuro del cartaceo | N/A | Nessun cartaceo |
| A.11.8 | Uso univoco degli identificativi utente | A | Account nominativi per gli strumenti di sviluppo e pubblicazione |
| A.11.9 | Registro degli utenti autorizzati | A | Elenco degli accessi a repository e console tenuto dal titolare |
| A.11.10 | Gestione degli identificativi utente | A | Revoca alla cessazione (6.5) |
| A.11.11 | Misure contrattuali | N/A | Nessun cliente cloud a cui offrire garanzie contrattuali |
| A.11.12 | Trattamento affidato a sub-responsabili | N/A | Nessun sub-responsabile per dati degli utenti |
| A.11.13 | Accesso a spazi di memoria già usati | N/A | Nessuna infrastruttura di memorizzazione condivisa |
| A.12.1 | Localizzazione geografica dei dati | A (by design) | I dati restano sul dispositivo dell'utente |
| A.12.2 | Destinazione prevista dei dati | A (by design) | Nessuna destinazione esterna |

## 7. Rischi residui accettati

| Rischio | Trattamento | Riferimento |
|---|---|---|
| Riavvio della prova con ripristino di fabbrica o cambio di account Google | Accettato dal titolare | §8.1, capitolo 14 (09:29) |
| Rifiuto della dichiarazione di accessibilità da parte di Google Play | Informativa e video conformi; alternativa: tastiera di sistema (§5.1) | §5.1-bis, capitolo 14 (09:21) |
| Lettura del testo nel campo attivo tramite accessibilità | Limitata al campo attivo e alla selezione, solo in RAM, dichiarata all'utente | §5.1-bis, §10 |
| Contenuto degli appunti durante l'inserimento di ripiego | Ripristino immediato del contenuto precedente | §2.6, §5.1-bis |
| Doppia occupazione di spazio su Android (asset pack + copia ricomposta, circa 6 GB) | Requisito di spazio dichiarato nella scheda; verifica dello spazio prima della copia | §7, `docs/release-guide.md` |
| Componenti proprietari di piattaforma (Billing, Block Store, Windows App SDK) | Eccezione al §2.2 da registrare nel capitolo 14 | `THIRD_PARTY_LICENSES.md` §5 |

## 8. Evidenze per un audit

- Specifica e registro decisioni: `docs/SPECIFICHE-ZZITTO.md`.
- Checklist MASVS compilata: `docs/compliance/owasp-masvs.md`.
- Licenze: `THIRD_PARTY_LICENSES.md`. Modelli: `tools/models/models.lock`.
- Rilasci: `CHANGELOG.md`, file `.sha256` in `dist/` (da archiviare con ogni rilascio), storico delle tracce Play e degli invii Partner Center.
- Informativa privacy pubblicata e dichiarazioni degli store (`docs/privacy/`, `docs/store/`).

## 9. Riesame

Il titolare riesamina questo allegato a ogni versione MAJOR o MINOR, a ogni nuova dipendenza o nuovo flusso di dati,
e comunque prima della pubblicazione in produzione.
