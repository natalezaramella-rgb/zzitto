# Google Play – dichiarazione d'uso dell'API di accessibilità e video

*Aggiunto il 22/10/2026 alle 11:51. Riferimenti: Spec §5.1-bis, §5.8, §5.10, §10, capitolo 14 (rischio di approvazione
registrato il 22/10/2026 alle 09:21). Proprietario: agente E.*

Zzitto usa un `AccessibilityService` per riconoscere il campo di testo attivo e inserirvi il testo dettato (Spec §5.1-bis).
Zzitto **non** è uno strumento di accessibilità nel senso di Google (il suo scopo principale non è assistere persone con
disabilità), quindi:

1. nel file di metadati del servizio **non** si imposta `android:isAccessibilityTool="true"`;
2. l'app mostra un'**informativa ben visibile** con consenso esplicito prima di aprire le impostazioni di accessibilità;
3. nella Play Console si compila la **dichiarazione sull'API di accessibilità** con un **video**.

## 1. Informativa nell'app (per l'agente B)

Requisiti di Google: dentro l'app, nel normale flusso d'uso (non nascosta nelle impostazioni), descrive i dati a cui si
accede e come sono usati, richiede un'azione esplicita di consenso (es. "Accetto"), è separata da altre informative sui dati,
e non può essere sostituita dalla descrizione del servizio nelle impostazioni di sistema.

Testo italiano (schermata del tour, Spec §5.8):

> **Zzitto usa il servizio di accessibilità**
>
> Per mostrare l'icona del microfono quando tocchi un campo di testo e per scriverci dentro il testo che detti, Zzitto
> ha bisogno del servizio di accessibilità di Android.
>
> Con questo servizio Zzitto:
> - riconosce quale campo di testo è attivo e dove si trova sullo schermo;
> - inserisce il testo dettato in quel campo;
> - legge il testo che selezioni, solo quando chiedi di riscriverlo a voce.
>
> Zzitto **non** legge il resto dello schermo, **non** registra quello che scrivi con la tastiera, **non** salva e **non**
> invia a nessuno i contenuti delle altre app. Tutto resta sul tuo telefono.
>
> Puoi disattivare il servizio quando vuoi dalle impostazioni di Android. Senza il servizio non puoi dettare nelle altre app.
>
> [ Non ora ]  [ Accetto e attivo ]

Testo inglese:

> **Zzitto uses the accessibility service**
>
> To show the microphone icon when you tap a text field and to type the text you dictate into it, Zzitto needs Android's
> accessibility service.
>
> With this service Zzitto:
> - detects which text field is active and where it is on the screen;
> - inserts the dictated text into that field;
> - reads the text you select, only when you ask to rewrite it by voice.
>
> Zzitto does **not** read the rest of the screen, does **not** log what you type on your keyboard, and does **not** store
> or send anyone other apps' content. Everything stays on your phone.
>
> You can turn the service off at any time in Android settings. Without it you cannot dictate in other apps.
>
> [ Not now ]  [ Accept and turn on ]

Comportamento richiesto: "Accetto" apre `Settings.ACTION_ACCESSIBILITY_SETTINGS`; "Non ora" chiude senza aprire nulla e
l'informativa si può riaprire dal tour o dalle impostazioni (serve anche per il video, scena 5).

Descrizione del servizio (`android:description` nel file XML del servizio, mostrata nelle impostazioni di Android):

> IT: Zzitto usa questo servizio solo per riconoscere il campo di testo attivo e inserire il testo che detti. Non legge
> altri contenuti dello schermo e non invia dati fuori dal telefono.
>
> EN: Zzitto uses this service only to detect the active text field and insert the text you dictate. It does not read other
> screen content and sends no data off your phone.

Configurazione minima consigliata per il servizio (agente B): tipi di evento limitati a `typeViewFocused`,
`typeViewTextSelectionChanged`, `typeWindowStateChanged`; `canRetrieveWindowContent="true"` (necessario per il nodo del
campo attivo); niente `canPerformGestures`, niente `canRequestFilterKeyEvents` (non si leggono i tasti), niente
`typeViewTextChanged` se non indispensabile.

## 2. Dichiarazione nella Play Console

Criteri → Contenuti dell'app → API di accessibilità (dopo il caricamento di un AAB che dichiara il servizio).

| Campo | Risposta |
|---|---|
| L'app è uno strumento di accessibilità (`isAccessibilityTool`)? | No |
| Funzionalità principale che usa l'API | Dettatura vocale: l'icona del microfono compare vicino al campo di testo attivo e il testo dettato viene inserito in quel campo in qualsiasi app. |
| L'app raccoglie o condivide dati personali e sensibili tramite l'API? | No: i dati letti (campo attivo e testo selezionato per la riscrittura) sono elaborati solo sul dispositivo e non vengono salvati né trasmessi. |
| Link al video | URL di YouTube (non in elenco) del video descritto sotto |

Testo per il campo di descrizione (IT):

> Zzitto è un'app di dettatura vocale con intelligenza artificiale che funziona interamente sul dispositivo. Usiamo
> l'AccessibilityService per tre sole operazioni: (1) riconoscere quale campo di testo ha il focus e la sua posizione, per
> mostrare accanto un'icona del microfono; (2) inserire nel campo il testo dettato e corretto, con ACTION_SET_TEXT o incollando
> dagli appunti e ripristinandone il contenuto precedente; (3) leggere il testo selezionato quando l'utente chiede di
> riscriverlo a voce. Non leggiamo altri contenuti dello schermo, non intercettiamo la tastiera, non automatizziamo azioni
> e non prendiamo decisioni in modo autonomo. Nessun dato letto tramite l'API viene salvato o lascia il dispositivo: l'app non
> ha server e accede a internet solo per verificare l'acquisto in-app durante la prova. Prima di attivare il servizio l'app
> mostra un'informativa dedicata e chiede il consenso esplicito.

Text for the description field (EN):

> Zzitto is an AI voice dictation app that runs entirely on the device. We use the AccessibilityService for three operations
> only: (1) detecting which text field has focus and where it is, to show a microphone icon next to it; (2) inserting the
> dictated, corrected text into that field, via ACTION_SET_TEXT or by pasting from the clipboard and restoring its previous
> content; (3) reading the selected text when the user asks to rewrite it by voice. We do not read other screen content, do not
> intercept the keyboard, do not automate actions and do not make autonomous decisions. No data read through the API is stored
> or leaves the device: the app has no servers and goes online only to check the in-app purchase during the trial. Before the
> service is enabled, the app shows a dedicated disclosure and asks for explicit consent.

## 3. Sceneggiatura del video

Requisiti di Google: mostrare l'apertura dell'app, il percorso fino all'informativa e al consenso, il testo completo
dell'informativa (scorrendo lentamente se serve), il flusso con consenso (incluso l'abilitazione del permesso), il flusso
senza consenso (inclusa la riapertura dell'informativa) e una funzione principale che usa l'accessibilità.

Formato: registrazione dello schermo del telefono (registratore di Android), verticale, 1080p, lingua dell'interfaccia
inglese o italiana con sottotitoli in inglese; durata indicativa 2–3 minuti; pubblicato su YouTube come "Non in elenco".
Usare un'app di note di prova senza dati personali reali e nessun marchio riconoscibile in primo piano.

| Scena | Cosa si vede | Sottotitolo |
|---|---|---|
| 1 | Schermata Home, tocco sull'icona di Zzitto, si apre il tour al primo avvio | "Opening Zzitto for the first time" |
| 2 | Tour: schermata del microfono, concessione di `RECORD_AUDIO` | "Microphone permission for dictation" |
| 3 | Tour: informativa sull'accessibilità; scorrimento lento fino in fondo, testo tutto leggibile | "Prominent disclosure of the accessibility use" |
| 4 | Tocco su "Not now": l'informativa si chiude, il tour mostra che la dettatura nelle altre app non è attiva | "User declines: nothing is enabled" |
| 5 | Riapertura dell'informativa dal tour o da Impostazioni → Permessi | "The disclosure can be reopened" |
| 6 | Tocco su "Accept and turn on": si aprono le impostazioni di accessibilità di Android, attivazione di Zzitto, conferma di sistema | "User consents and enables the service" |
| 7 | Concessione di "Mostra sopra altre app" | "Overlay permission for the microphone icon" |
| 8 | Apertura di un'app di note, tocco su un campo vuoto: compare l'icona del microfono vicino al campo | "Core feature: the icon appears on the focused field" |
| 9 | Tocco sull'icona, dettatura di una frase con esitazioni, secondo tocco, il testo corretto compare nel campo | "Dictated text is inserted into the field" |
| 10 | Selezione di una frase, tocco sull'icona, istruzione "make it more formal", la selezione viene sostituita | "Voice rewriting reads only the selected text" |
| 11 | Modalità aereo attiva, nuova dettatura che funziona | "Everything runs on the device, offline" |
| 12 | Impostazioni di Android → Accessibilità → Zzitto → disattivazione | "The service can be turned off at any time" |

## 4. Altre dichiarazioni che potrebbero servire

- **Servizio in primo piano di tipo microfono**: se l'agente B usa un `ForegroundService` con
  `foregroundServiceType="microphone"` per registrare mentre l'app non è in primo piano, la Play Console chiede anche la
  dichiarazione dei servizi in primo piano, con descrizione e video. La scena 9 del video può essere riutilizzata.
- **Overlay** ("Mostra sopra altre app", `SYSTEM_ALERT_WINDOW`): non richiede dichiarazione nella Play Console, ma va
  spiegato nel tour (Spec §5.1-bis).

## Fonti consultate (22/10/2026)

- Uso dell'API AccessibilityService, informativa ben visibile, dichiarazione e video:
  https://support.google.com/googleplay/android-developer/answer/10964491
