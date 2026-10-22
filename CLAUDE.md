# Zzitto — istruzioni per gli agenti

*Aggiunto il 22/10/2026 alle 11:15.*

Questo repository contiene Zzitto, app di dettatura vocale con AI on-device per Android e Windows.
La specifica vincolante è `docs/SPECIFICHE-ZZITTO.md`: leggila prima di scrivere codice.

## Regole fisse

- Rispondi in italiano. In chat, ogni risposta inizia con data e ora correnti in ora locale italiana (Europe/Rome).
- Nella documentazione, ogni contenuto aggiunto va marcato con data e ora (ora locale italiana), es. `*Aggiunto il 22/10/2026 alle 11:15.*`
- Nel codice, in ogni punto rilevante, metti un commento con il riferimento esatto alla specifica, es. `// Spec §5.3`.
- Per ogni nuova funzione scrivi i test; i test girano a ogni compilazione (Spec §12).
- Ogni decisione va registrata nel capitolo 14 della specifica (registro decisioni), in fondo al file.
- Mai stime in giorni. Macchine a stati solo per il ciclo di dettatura (Spec §5.2).
- Nessun audio o testo dettato nei log o su disco (Spec §10). Nessun segreto nel repository.
- Solo dipendenze con licenza MIT, Apache 2.0 o BSD (Spec §2.2).

## Struttura e proprietari (Spec §16.1)

| Percorso | Proprietario |
|---|---|
| `core-native/include/zz_engine.h` | Solo orchestratore (contratto) |
| `core-native/` (resto) | Agente A – Motore |
| `android/app`, `android/engine`, `android/audio`, `android/dictation` | Agente B – Android |
| `android/core` | Orchestratore (interfacce condivise) |
| `android/licensing`, `windows/src/Zzitto.Licensing` | Agente D – Acquisti |
| `windows/` (resto, compresa la soluzione `Zzitto.sln`) | Agente C – Windows |
| `windows/src/Zzitto.Core/Licensing` | Orchestratore (interfaccia condivisa) |
| `android/models`, `docs/compliance`, `docs/store`, `docs/privacy`, `tools/`, `THIRD_PARTY_LICENSES.md` | Agente E – Conformità e rilascio |

Un agente non modifica i percorsi degli altri. File condivisi (`android/settings.gradle.kts`,
`android/gradle/libs.versions.toml`): ogni agente aggiunge solo nella propria sezione.

## Build

- Motore: `cmake -S core-native -B build/core -DZZ_ENGINE_MOCK=ON && cmake --build build/core` (i test girano a ogni build).
- Android: `cd android && ./gradlew assembleDebug check` (Android Studio, JDK 17).
- Windows: Visual Studio 2022 con il carico di lavoro .NET desktop + Windows App SDK; aprire `windows/Zzitto.sln`.
- Non c'è CI su GitHub Actions (decisione del titolare, capitolo 14).

## Git

Un branch per agente (`agent/a-engine`, `agent/b-android`, `agent/c-windows`, `agent/d-licensing`,
`agent/e-compliance`), unito in `develop`. La pull request finale `develop` → `main` la rivede il titolare.
