# Informativa sul trattamento dei dati personali – Zzitto

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §1, §5.1-bis, §5.6, §8.1, §8.2, §10. Versione 1.0.*
*Testo destinato alla pubblicazione (pagina web linkata da app e schede degli store). Prima della pubblicazione: completare
i campi `[[DA COMPLETARE]]` e farla rivedere da un consulente privacy.*

**Ultimo aggiornamento:** [[DA COMPLETARE: data di pubblicazione]]

Questa informativa spiega come l'app **Zzitto** per Android e per Windows tratta i dati, ai sensi degli articoli 13 e 14
del Regolamento (UE) 2016/679 (GDPR) e del D.Lgs. 196/2003 (Codice privacy).

**In sintesi:** Zzitto elabora la voce e il testo **solo sul tuo dispositivo**. Nessun audio e nessun testo dettato
viene inviato a noi o a terzi. Non abbiamo server, non usiamo pubblicità, statistiche d'uso o profilazione.
L'unico accesso a internet serve, durante la prova gratuita, a verificare tramite Google Play o Microsoft Store se hai
acquistato l'app.

## 1. Titolare del trattamento

Zaramella Consulting Srl
Sede legale: [[DA COMPLETARE: indirizzo completo]]
Partita IVA: [[DA COMPLETARE: P.IVA]]
Email per la privacy: [[DA COMPLETARE: indirizzo email di contatto]]

Il titolare non ha nominato un Responsabile della protezione dei dati (DPO), perché non ne ricorrono gli obblighi
(art. 37 GDPR). [[DA COMPLETARE: confermare o indicare il DPO]]

## 2. Come funziona Zzitto e quali dati tocca

| Dato | Cosa succede | Dove resta | Esce dal dispositivo? |
|---|---|---|---|
| **Audio della voce** | Registrato dal microfono solo mentre detti, trascritto dal modello Whisper sul dispositivo | Solo nella memoria di lavoro (RAM); cancellato subito dopo la trascrizione, mai salvato su disco | **No** |
| **Testo dettato** | Corretto, riscritto o tradotto dal modello Phi-4-mini sul dispositivo e inserito nel campo di testo che stai usando | Nel campo di testo dell'app in cui scrivi; se attivi la cronologia, anche nell'archivio cifrato di Zzitto | **No** |
| **Testo selezionato per la riscrittura a voce** | Letto solo quando selezioni un testo e chiedi a voce di riscriverlo; il risultato sostituisce la selezione | Solo in memoria durante l'elaborazione | **No** |
| **Cronologia delle dettature** (facoltativa) | Ultime dettature (testo grezzo e corretto), per consultarle | Archivio cifrato sul dispositivo; disattivabile, cancellabile con un tocco, conservazione configurabile | **No** |
| **Dizionario personale e scorciatoie vocali** | Parole, nomi e testi che inserisci tu, usati per migliorare il riconoscimento | Archivio cifrato sul dispositivo | **No** |
| **Impostazioni** | Lingua, modalità di correzione, tempi | Sul dispositivo | **No** |
| **Appunti (clipboard)** | Solo se un'app non accetta l'inserimento diretto: il testo passa dagli appunti e il contenuto precedente viene ripristinato | Appunti del sistema, per pochi istanti | **No** |
| **Stato della licenza** | Esito della verifica di acquisto | Sul dispositivo, cifrato | Vedi §3 |
| **Data di inizio della prova** (solo Android) | Salvata con il servizio Block Store di Google | Sul dispositivo e nel backup del tuo account Google | Vedi §4 |

Zzitto **non** raccoglie: nome, email, contatti, posizione, identificativi pubblicitari, dati di utilizzo, statistiche,
rapporti sugli arresti anomali. Non ti chiede di creare un account.

## 3. Verifica della licenza durante la prova gratuita

- **Android:** l'app si scarica gratis e si sblocca per sempre con un acquisto in-app una tantum su Google Play
  (prodotto "Sblocca Zzitto"). Durante i 30 giorni di prova, a ogni avvio Zzitto chiede alla libreria Google Play Billing
  se l'acquisto è stato fatto.
- **Windows:** l'app è venduta sul Microsoft Store con una prova gratuita di 30 giorni gestita dallo Store. Durante la
  prova, a ogni avvio Zzitto legge lo stato della licenza tramite le API del Microsoft Store.
- In questa verifica non inviamo alcun dato nostro: la richiesta passa dall'app dello store installata sul dispositivo,
  che usa il tuo account Google o Microsoft. Noi non riceviamo i tuoi dati di pagamento né il tuo account.
- Senza connessione la prova continua normalmente. Dopo l'acquisto confermato, lo sblocco viene salvato in modo cifrato
  sul dispositivo e **Zzitto non si collega più a internet**.

Google (Google Ireland Ltd) e Microsoft (Microsoft Ireland Operations Ltd) trattano i dati dell'acquisto come titolari
autonomi, secondo le proprie informative:
Google: https://policies.google.com/privacy — Microsoft: https://privacy.microsoft.com/privacystatement

## 4. Data di inizio della prova su Android (Block Store)

Per evitare che la prova ricominci disinstallando e reinstallando l'app, Zzitto salva **solo la data di inizio della
prova** con il servizio Block Store di Google Play services. Il dato resta sul dispositivo e, se hai attivo il backup di
Google, viene salvato nel backup del tuo account Google con crittografia end-to-end quando il dispositivo lo consente
(Android 9 o successivo con blocco schermo). Non contiene altro: né audio, né testo, né informazioni su come usi l'app.
Puoi cancellarlo cancellando i dati del backup dell'app dal tuo account Google.

## 5. Permessi richiesti

**Android**

| Permesso | Perché |
|---|---|
| Microfono (`RECORD_AUDIO`) | Ascoltare la voce mentre detti. Il microfono è attivo solo dopo il tuo tocco sull'icona. |
| Servizio di accessibilità | **Solo** per capire quale campo di testo è attivo, mostrare l'icona del microfono vicino al campo, inserire il testo dettato e leggere il testo che selezioni per la riscrittura a voce. Zzitto non legge il resto dello schermo, non registra ciò che scrivi con la tastiera, non salva e non trasmette i contenuti delle altre app. |
| Internet | Solo per la verifica dell'acquisto durante la prova (§3). |
| Esclusione dall'ottimizzazione della batteria (facoltativa) | Evitare che il sistema chiuda il servizio di dettatura. |

Prima di attivare il servizio di accessibilità, l'app mostra una schermata che spiega questi usi e chiede il tuo consenso.

**Windows:** microfono; accesso a internet solo per la licenza dello Store; UI Automation e simulazione della tastiera
per inserire il testo nel campo attivo; appunti come ripiego.

## 6. Finalità e basi giuridiche

| Finalità | Dati | Base giuridica |
|---|---|---|
| Fornire la dettatura, la correzione, la riscrittura e la traduzione | Audio e testo, elaborati solo sul dispositivo | Esecuzione del contratto di licenza d'uso (art. 6.1.b GDPR) |
| Cronologia, dizionario, scorciatoie | Dati che decidi di salvare | Esecuzione del contratto; funzioni facoltative attivate da te |
| Verifica dell'acquisto e della prova | Stato della licenza, data di inizio prova | Esecuzione del contratto (art. 6.1.b) e legittimo interesse a impedire l'abuso della prova gratuita (art. 6.1.f) |

Poiché audio e testo non lasciano il dispositivo, il titolare non ha accesso a questi dati e non li conserva.

## 7. Conservazione

- Audio: nessuna conservazione (solo memoria di lavoro, cancellato dopo la trascrizione).
- Cronologia: per il periodo che scegli nelle impostazioni, finché non la cancelli o disinstalli l'app.
- Dizionario, scorciatoie, impostazioni, stato della licenza: sul dispositivo, finché non li cancelli o disinstalli l'app.
- Data di inizio prova (Android): nel Block Store finché resta il backup dell'app nel tuo account Google.

## 8. Destinatari e trasferimenti all'estero

Il titolare non comunica dati a terzi e non li trasferisce fuori dall'Unione europea. Google e Microsoft possono trattare
i dati dell'acquisto e del backup anche fuori dall'UE, come titolari autonomi e con le garanzie descritte nelle loro informative.

## 9. Sicurezza

Archivi cifrati con chiave conservata nell'Android Keystore (Android) o nelle protezioni del sistema Windows; nessun audio
o testo dettato nei registri di diagnostica; modelli AI verificati con impronta SHA-256; nessun segreto nel codice.
I dettagli sono nell'allegato di conformità ISO/IEC 27001, 27017 e 27018 del progetto.

## 10. I tuoi diritti

Hai diritto di accesso, rettifica, cancellazione, limitazione, portabilità e opposizione (artt. 15–21 GDPR).
Poiché i dati restano sul tuo dispositivo, puoi esercitarli direttamente dall'app: consultare e cancellare la cronologia,
modificare dizionario e scorciatoie, disattivare la cronologia, disinstallare l'app. Per qualsiasi richiesta scrivi a
[[DA COMPLETARE: indirizzo email di contatto]].

Puoi proporre reclamo al Garante per la protezione dei dati personali (https://www.garanteprivacy.it) o all'autorità
di controllo del tuo Paese.

## 11. Minori

Zzitto non è destinata a minori di 14 anni (art. 2-quinquies del Codice privacy).

## 12. Intelligenza artificiale

Zzitto usa modelli di intelligenza artificiale (Whisper per la trascrizione, Phi-4-mini per correzione, riscrittura
e traduzione) che funzionano interamente sul dispositivo. Il testo prodotto può contenere errori: rileggilo prima di inviarlo.
Nessun dato viene usato per addestrare modelli.

## 13. Modifiche

Eventuali modifiche a questa informativa saranno pubblicate su questa pagina con la data di aggiornamento e, se rilevanti,
segnalate nell'app.
