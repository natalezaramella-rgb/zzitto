# Google Play – sezione "Sicurezza dei dati"

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §8, §8.1, §10. Proprietario: agente E.*

Risposte da inserire in Play Console → Criteri → Contenuti dell'app → Sicurezza dei dati. La sezione si basa sulla
definizione di Google: "raccogliere" significa **trasmettere dati dall'app fuori dal dispositivo**. I dati elaborati
solo sul dispositivo non vanno dichiarati.

## Esito: nessun dato raccolto, nessun dato condiviso

| Domanda | Risposta | Motivo |
|---|---|---|
| L'app raccoglie o condivide uno dei tipi di dati utente richiesti? | **No** | Audio e testo sono elaborati solo sul dispositivo (Spec §1, §10). |
| (Le domande successive su tipi di dati, crittografia in transito ed eliminazione non compaiono se la risposta è No.) | — | — |

Il riepilogo pubblico mostrerà: "Nessun dato raccolto" e "Nessun dato condiviso con terze parti".

## Perché ogni flusso di dati rientra nelle esenzioni di Google

| Flusso | Esce dal dispositivo? | Regola di Google applicata |
|---|---|---|
| Audio del microfono | No: solo RAM, distrutto dopo la trascrizione | Elaborazione sul dispositivo: non va dichiarata |
| Testo dettato, corretto, riscritto, tradotto | No | Elaborazione sul dispositivo |
| Testo letto tramite il servizio di accessibilità (campo attivo, testo selezionato per la riscrittura) | No | Elaborazione sul dispositivo |
| Cronologia, dizionario, scorciatoie | No: database cifrato locale | Elaborazione sul dispositivo |
| Acquisto in-app tramite Google Play Billing | I dati di pagamento li raccoglie Google Play direttamente dall'utente | Esenzione per i servizi di pagamento: non va dichiarata se l'app non accede a quei dati e il servizio li raccoglie direttamente dall'utente. Zzitto legge solo lo stato "acquistato / non acquistato" del prodotto `zzitto_unlock`. |
| Data di inizio prova in Block Store | Sì, nel backup dell'account Google, **solo con crittografia end-to-end** | Esenzione per i dati cifrati end-to-end, illeggibili per lo sviluppatore e per gli intermediari. Condizione: vedi sotto. |

## Condizione da rispettare nel codice (agente D)

La risposta "No" vale solo se il modulo `:licensing` abilita il backup nel cloud di Block Store **solo quando
`isEndToEndEncryptionAvailable()` restituisce `true`** (come nell'esempio ufficiale di Google), e altrimenti salva la data
solo in locale (`setShouldBackupToCloud(false)`). Se si volesse fare il backup anche senza crittografia end-to-end,
la risposta cambierebbe: andrebbe dichiarato un dato raccolto (categoria "Attività nelle app" o "Info e prestazioni
dell'app", da valutare) e la sezione andrebbe rivista.

Altre condizioni:

- Nessuna libreria di statistiche, pubblicità o segnalazione degli arresti anomali nell'app (Firebase Analytics,
  Crashlytics e simili sono esclusi). I dati di Android vitals raccolti da Google Play a livello di sistema non dipendono
  dall'app e non vanno dichiarati dallo sviluppatore.
- Se in futuro si aggiunge una libreria che trasmette dati, questa sezione va aggiornata prima del rilascio.

## Pratiche di sicurezza (facoltative, mostrate solo se si dichiarano dati)

Non applicabili con "nessun dato raccolto". Per completezza: nessun dato trasmesso; archivi locali cifrati con chiave
nell'Android Keystore (Spec §10); nessun account, quindi nessuna procedura di eliminazione dell'account.

## Eliminazione dell'account

Non applicabile: l'app non prevede account (Spec §6).

## Fonti consultate (22/10/2026)

- Guida alla sezione Sicurezza dei dati: https://support.google.com/googleplay/android-developer/answer/10787469
  (definizione di raccolta, esenzioni per elaborazione sul dispositivo, crittografia end-to-end e servizi di pagamento)
- Block Store e crittografia end-to-end: https://developer.android.com/identity/block-store
