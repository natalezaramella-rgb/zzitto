# Zzitto per Windows

*Aggiunto il 22/10/2026 alle 12:09. Proprietario: agente C (Spec §16.1). Riferimenti: Spec §2.4–§2.6, §4.3, §5, §7.1, §8.2, §10, §11.1, §12.*

App residente WinUI 3 (C#, .NET 8, Windows App SDK 1.8) pacchettizzata MSIX, per ARM64 e x64.
In uso si vede solo l'icona del microfono (flottante e nell'area di notifica); le impostazioni si aprono su richiesta.

## Struttura

| Percorso | Contenuto |
|---|---|
| `Zzitto.sln` | Soluzione con tutti i progetti Windows (app, librerie, test) |
| `src/Zzitto.Core` | Interfacce condivise (orchestratore): `ILicenseService` |
| `src/Zzitto.Licensing` | Licenza Microsoft Store, prova di 30 giorni, acquisto (agente D) |
| `src/Zzitto.Platform` | Tutta la logica non grafica: P/Invoke e wrapper del motore, WASAPI, macchina a stati, inserimento (UI Automation, SendInput, appunti), icona flottante e area di notifica Win32, archiviazione cifrata DPAPI |
| `src/Zzitto.App` | App WinUI 3: avvio, istanza unica, finestra impostazioni, tour, guida, licenze, acquisto |
| `tests/Zzitto.Tests` | Test xUnit (Spec §12) |
| `tests/Zzitto.Licensing.Tests` | Test xUnit della licenza (agente D) |
| `build-native.ps1` | Compila `zz_engine.dll` con CMake ed esegue i test del motore |
| `msbuild/` | Impostazioni comuni e target che invoca `build-native.ps1` prima di ogni build |

## Requisiti sul Surface Snapdragon (Windows 11 ARM64)

1. **Visual Studio 2022** 17.12 o successivo (versione nativa ARM64), con i carichi di lavoro:
   - *Sviluppo di applicazioni WinUI* (o *Sviluppo desktop .NET* con *Strumenti Windows App SDK C#*);
   - *Sviluppo di applicazioni desktop con C++*, con i componenti **MSVC v143 ARM64/ARM64EC**, **MSVC v143 x64/x86**
     e **Strumenti CMake C++ per Windows** (servono per `zz_engine.dll`).
2. .NET 8 SDK (installato da Visual Studio).
3. Windows: Impostazioni → Sistema → Per sviluppatori → **Modalità sviluppatore** attiva (deploy del pacchetto MSIX da Visual Studio).

Nessun pacchetto va installato a mano: NuGet ripristina tutto alla prima apertura.

## Compilare ed eseguire

1. Aprire `windows\Zzitto.sln` in Visual Studio.
2. Barra degli strumenti: configurazione **Debug**, piattaforma **ARM64** (sul Surface) oppure **x64**.
3. Progetto di avvio: **Zzitto.App** (profilo "Zzitto (Package)").
4. **F5**. Alla prima compilazione:
   - il target `ZzBuildNativeEngine` esegue `build-native.ps1`, che compila `core-native` con CMake per la piattaforma
     scelta (motore finto, `-DZZ_ENGINE_MOCK=ON`, runtime statico `/MT`), esegue i test del motore e copia
     `zz_engine.dll` accanto a `Zzitto.exe`;
   - Visual Studio registra il pacchetto MSIX e avvia l'app: al primo avvio si apre il tour (Spec §5.8).
5. Prova: clic in Blocco note, `Ctrl+Alt+Z` (o clic sull'icona), parlare, pausa di 2 secondi. Con il motore finto
   il testo inserito è sempre la stessa frase di prova (Spec §16.1).

In Debug la licenza dello Store è simulata (prova di 30 giorni, `DevelopmentStoreLicenseSource` dell'agente D):
un'app avviata da Visual Studio non è installata dallo Store. In Release si usa sempre lo Store.

### Test (Spec §12)

- In Visual Studio i test di `Zzitto.Tests` girano **a ogni build** (target `ZzRunTestsAfterBuild`, esegue
  `dotnet test --no-build`); un test fallito fa fallire la build. Sono visibili anche in *Esplora test*.
- Da riga di comando (Developer PowerShell):

  ```powershell
  dotnet test windows\Zzitto.sln -c Debug -p:Platform=ARM64     # sul Surface
  dotnet test windows\Zzitto.sln -c Debug -p:Platform=x64       # su PC x64
  ```

  Indicare sempre `-p:Platform`: i test di integrazione caricano `zz_engine.dll` dell'architettura giusta.
  `tests\Zzitto.Tests\zzitto.runsettings` imposta `ZZ_MOCK_FAST=1` (motore finto senza attese).
- I test automatici sulla build x64 girano anche sul Surface (emulazione x64 di Windows 11) solo se è installato il
  runtime .NET 8 x64; il target li salta quando l'architettura della macchina è diversa da quella della build.

### Motore reale e rilascio

- Motore reale (agente A): `-p:ZzEngineMock=OFF` sulla build, oppure `-p:ZzEngineDir=<cartella con zz_engine.dll>`
  per usare una DLL già compilata (lo fa `tools\release\release-windows.ps1` dell'agente E, che crea i due MSIX e il
  bundle per lo Store).
- Modelli (Spec §7.1): `tools\models\prepare-models.ps1` (agente E) copia `whisper-medium-q5_0.bin` e
  `phi-4-mini-instruct-q4_k_m.gguf` in `src\Zzitto.App\Models\` (cartella marcata da `.zz-models-dir`) e scrive
  hash, dimensioni e versione in `src\Zzitto.Platform\Engine\ModelManifest.cs`. Finché gli hash non sono impostati
  il motore reale non viene usato (solo il motore finto funziona).
- `Package.appxmanifest`: `Identity Name="Zaramella.Zzitto"`; **`Publisher` è un segnaposto** da sostituire con il
  valore di Partner Center (Gestione prodotto → Identità del prodotto); la versione la allinea
  `tools\release\version.py sync-appx` (agente E).

## Come funziona

| Funzione | Implementazione | Spec |
|---|---|---|
| App residente | Thread Win32 dedicato (`ShellHost`) con icona nell'area di notifica (`Shell_NotifyIcon`), menu, fumetti; WinUI senza finestre (`DispatcherShutdownMode.OnExplicitShutdown`); istanza unica (`AppInstance`) | §2.4, §2.5 |
| Avvio con Windows | `StartupTask` MSIX `ZzittoStartup`, attivabile dalle impostazioni | §2.4 |
| Scorciatoia globale | `RegisterHotKey` (predefinita Ctrl+Alt+Z, configurabile); Esc annulla durante la dettatura; nessun hook di tastiera | §2.4, §5.2 |
| Icona flottante | Finestra a livelli `WS_EX_NOACTIVATE` (non toglie il focus), disegnata via software; colore e animazione per stato; trascinabile; pressione lunga o clic destro per il menu | §2.4, §2.5, §5.1 |
| Posizione | Cursore Win32 (`GetGUIThreadInfo`) → cursore UI Automation (`TextPattern2`) → rettangolo del controllo → ultima posizione scelta; nascosta con app a schermo intero | §2.6 |
| Audio | WASAPI condiviso a eventi sul microfono predefinito, conversione mono e ricampionamento a 16 kHz (FIR + interpolazione), solo in memoria, azzerato dopo la trascrizione | §4.1, §10 |
| Stop automatico | `zz_vad_*` del motore con il silenzio configurato; durata massima configurabile | §5.2 |
| Ciclo di dettatura | `DictationStateMachine` (unica macchina a stati) e `DictationController` | §5.2 |
| Inserimento | UI Automation `ValuePattern` solo su campi Edit nativi (Win32, WinForms, WPF, XAML) con verifica; altrimenti `SendInput` Unicode (a capo = Maiusc+Invio); infine appunti con avviso "premi Ctrl+V". Finestre elevate: solo appunti | §2.4, §2.6 |
| Comandi | `ZZ_ACTION_UNDO_LAST_INSERT`, `DELETE_PREVIOUS_SENTENCE` (prima del testo), `SEND` (dopo) | §5.9 |
| Riscrittura | Selezione letta con `TextPattern`, oppure con Ctrl+C temporaneo e ripristino completo degli appunti (mai nelle console) | §5.10 |
| Privacy appunti | Testi di Zzitto esclusi da cronologia e sincronizzazione degli appunti di Windows | §10 |
| Archiviazione | Impostazioni in JSON; dizionario, scorciatoie e cronologia cifrati con DPAPI (account utente) | §5.5, §5.6, §5.11, §10 |
| Licenza | `ILicenseService` (agente D): controllo `CanDictate` prima di ogni dettatura; prova scaduta o licenza sconosciuta → icona grigia e pagina di acquisto | §8.1, §8.2 |

## Dipendenze

| Pacchetto | Versione | Licenza | Uso |
|---|---|---|---|
| Microsoft.WindowsAppSDK | 1.8.260921001 | Termini Microsoft (sorgenti MIT) | WinUI 3, ciclo di vita |
| Microsoft.Windows.SDK.BuildTools | 10.0.26100.9169 | Termini Microsoft | Strumenti MSIX |
| xunit | 2.9.3 | Apache 2.0 | Test |
| xunit.runner.visualstudio | 3.1.5 | Apache 2.0 | Test |
| Microsoft.NET.Test.Sdk | 17.14.1 | MIT | Test |

Nessun'altra libreria: UI Automation, WASAPI, Win32 e DPAPI sono usati con dichiarazioni di interop scritte nel progetto.

## Limiti noti

- Codice scritto senza poterlo compilare (nessun SDK .NET/Windows nell'ambiente dell'agente): la prima compilazione
  avviene sul Surface. In caso di errori di compilazione, riportarli all'agente C.
- Windows App SDK 1.8 è in manutenzione (la linea attuale è la 2.x): scelta per la compatibilità certa con .NET 8 e
  Visual Studio 2022; il passaggio alla 2.x va valutato a parte.
- Il comando "cancella l'ultima frase" nel campo richiede UI Automation (`TextPattern`); nelle app che non lo espongono
  non ha effetto.
- `SetValue` di UI Automation sposta il cursore con `TextPattern`; nei controlli che non lo supportano il cursore può
  finire all'inizio del campo.

## Revisione di compilabilità del 22/10/2026

*Aggiunto il 22/10/2026 alle 12:30. Revisione indipendente senza SDK .NET/NuGet: lettura completa di csproj, soluzione,
manifest, XAML, risorse e codice C#; motore finto compilato con CMake (`-DZZ_ENGINE_MOCK=ON`) e verificato con un
programma C che ripete le attese di `NativeInteropTests` (dimensioni 56/32/48 byte, export, UTF-8, callback, VAD, SHA-256).*

Correzioni:

- `msbuild/Zzitto.Native.targets` e `build-native.ps1`: cartelle distinte per motore finto e reale
  (`build\native\<arch>-mock|real`, `build\native-bin\<arch>\<config>-mock|real`). Prima, in Release, l'app
  (motore reale) e i test (motore finto) scrivevano la stessa `zz_engine.dll`.
- `build-native.ps1`: motore reale su ARM64 configurato con `-T ClangCL` (`core-native/cmake/real_engine.cmake`
  interrompe la configurazione con cl.exe). Per la build Release su ARM64 serve il componente di Visual Studio
  **Strumenti C++ Clang per Windows**.
- `tests/Zzitto.Tests/Zzitto.Tests.csproj`: i test usano sempre il motore finto (`ZzEngineMock=ON` predefinito),
  anche in Release; con il motore reale senza modelli fallirebbero e, girando a ogni build, bloccherebbero la build.
- `Package.appxmanifest`: `Executable` ed `EntryPoint="Windows.FullTrustApplication"` espliciti sull'estensione
  `windows.startupTask`, come nell'esempio Microsoft per le app desktop pacchettizzate.

Verificati e corretti in origine: vtable COM di UI Automation e WASAPI, GUID, layout di `INPUT`/`NOTIFYICONDATAW`,
firme P/Invoke, chiavi `x:Uid` ↔ `Resources.resw` (nessuna mancante), versioni NuGet (esistono tutte; Windows App SDK
1.8.260921001 richiede BuildTools ≥ 10.0.26100.4654).

Da verificare alla prima build:

- se compare l'errore "requires Microsoft.Windows.SDK.NET.Ref …", aggiornare l'SDK .NET di Visual Studio (o impostare
  `WindowsSdkPackageVersion` come indicato dal messaggio);
- avvisi trasformati in errori da `TreatWarningsAsErrors` (analizzatori xUnit o CA): sono stati controllati a mano;
- i test girano a ogni build in Visual Studio: un test fallito (tempi, permessi DPAPI) fa fallire la build;
- prima build Release ARM64: compilazione lunga di whisper.cpp/llama.cpp con clang-cl.

## Dettatura in streaming (API 2 del motore)

*Aggiunto il 22/10/2026 alle 16:15. Spec §4.2, §5.2, §5.10, §10, §11. Scritto contro il contratto
`core-native/include/zz_engine.h` (API 2) mentre il motore e il mock lo implementano in parallelo; non compilato qui.*

- `ZzNativeMethods`/`IZzNative`/`ZzNativeLibrary`: P/Invoke di `zz_stream_begin`, `zz_stream_feed`, `zz_stream_finish`,
  `zz_stream_abort` (handle `IntPtr`; feed con `ReadOnlySpan<float>` e puntatore fisso, nessuna copia gestita).
  `ExpectedApiVersion` passa a 2: una `zz_engine.dll` v1 viene rifiutata alla creazione del motore.
- `ZzEngine.BeginStreamAsync(options)` → `IDictationStream` (`Feed`, `FinishAsync`, `Abort`, `IsFaulted`). Apertura e
  chiusura su thread dedicato, mai sul thread UI; `Feed` dal thread di cattura, non lancia mai e restituisce false se il
  flusso è chiuso o in errore. Errori come `DictateAsync` (`ZzEngineException`, `OperationCanceledException`; token →
  `zz_cancel`). Il flusso aperto tiene la coda del motore fino a `FinishAsync`/`Abort` (altrimenti `ZZ_ERR_BUSY`);
  `Abort` chiama prima `zz_cancel` per liberare il motore in fretta; `Dispose` del motore annulla un flusso rimasto aperto.
- `WasapiMicrophone`: ogni pacchetto (~10 ms) è consegnato subito a 16 kHz mono; i buffer di lavoro si azzerano anche
  quando la cattura termina per errore (Spec §10).
- `DictationController` (macchina a stati invariata): all'avvio dell'ascolto precarica i modelli e, se non c'è una
  selezione, apre il flusso; ogni blocco va nel buffer in memoria e al flusso (solo la parte accettata entro la durata
  massima); l'audio catturato prima dell'apertura è inoltrato all'aggancio. Allo stop: `FinishAsync`; all'annullamento:
  `Abort`. Ripiego su `DictateAsync` con il buffer se l'apertura fallisce o un feed fallisce. Riscrittura (Spec §5.10)
  sempre su `zz_rewrite`, senza aprire il flusso. Un errore del motore in `FinishAsync` non provoca ripiego.
- Test: `ZzEngineTests` (flusso con API finta: risultato, coda occupata, errori, annullamento, abort, dispose),
  `DictationControllerTests` (feed durante l'ascolto, apertura tardiva, stop prima dell'apertura, abort all'annullamento,
  ripieghi, durata massima), `NativeInteropTests` (API 2 e flusso attraverso il P/Invoke reale con il mock).
- Da registrare nel registro decisioni (Spec §14, a cura dell'orchestratore): ripiego automatico su `zz_dictate`;
  riscrittura senza streaming; `zz_cancel` prima di `zz_stream_abort`.
- Da verificare alla prima build sul Surface: compilazione (TreatWarningsAsErrors) e `NativeInteropTests` contro il mock
  con le funzioni `zz_stream_*` (falliscono con `EntryPointNotFoundException` finché il mock non le esporta).

## Scorciatoia e posizione dell'icona (22/10/2026 17:19)

*Aggiunto il 22/10/2026 alle 17:19, su segnalazione del titolare.*

- Scorciatoia predefinita **Ctrl+Alt+Z** (prima Ctrl+Alt+Spazio, che attivava anche l'app Claude). Si cambia da
  Impostazioni → Generale oppure dal menu dell'icona nell'area di notifica ("Scorciatoia: … (cambia…)").
- L'icona del microfono resta **ferma dove la trascini** (predefinito). "Vicino al testo che scrivi" e "Nascosta" si
  scelgono dal menu "Icona del microfono" o dalle impostazioni. Trascinare l'icona attiva la posizione fissa.
- Le impostazioni della versione 1 vengono migrate: la vecchia scorciatoia predefinita e la modalità "segue il campo"
  passano ai nuovi predefiniti; le scelte fatte dall'utente restano.
