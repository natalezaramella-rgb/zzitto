# Script di rilascio di Zzitto

*Aggiunto il 22/10/2026 alle 11:29. Riferimenti: Spec §7, §7.1, §12, §13. Proprietario: agente E.*

Non c'è CI (decisione del titolare, capitolo 14): questi script locali sono il percorso di rilascio. La guida
completa per il titolare è in [`docs/release-guide.md`](../../docs/release-guide.md).

| Script | Cosa fa |
|---|---|
| `version.py` | Versione semantica (fonte unica `version.properties`), sezione del `CHANGELOG.md` con data, ora e versione dei modelli, controllo e allineamento del `Package.appxmanifest` |
| `run-tests.sh`, `run-tests.ps1` | Tutti i test: strumenti, motore (finto e reale), Android (`gradlew check`), Windows (`dotnet test`) |
| `release-android.sh`, `release-android.ps1` | Test → AAB release → firma con la chiave di upload → controlli (`aabcheck.py`) → `dist/zzitto-android-X.Y.Z-N.aab` |
| `release-windows.ps1` | Test → `zz_engine.dll` ARM64 e x64 → MSIX per architettura → `dist/zzitto-windows-X.Y.Z.0.msixbundle` |
| `aabcheck.py` | Controlla asset pack, limiti di Google Play, assenza di chiavi e del motore finto nell'AAB |

Regole applicate dagli script:

- **Test obbligatori** (Spec §12): se un test fallisce lo script si ferma. Con `--skip-tests` / `-SkipTests` il file
  prodotto ha `NON-PUBBLICABILE` nel nome e non va caricato.
- **Motore reale**: il rilascio compila `core-native` con `ZZ_ENGINE_MOCK=OFF` e rifiuta i pacchetti che contengono il motore finto.
- **Modelli**: gli hash dei file inclusi devono coincidere con `tools/models/models.lock` e con `ModelManifest.kt` / `ModelManifest.cs`.
- **Versione**: `CHANGELOG.md` deve avere la sezione della versione corrente con la versione dei modelli (Spec §13).
- **Firma Android** (Play App Signing): l'AAB viene firmato con la chiave di upload da `jarsigner`; le password
  passano solo da variabili d'ambiente (`-storepass:env`), mai dalla riga di comando né dal repository.
- **Windows**: il bundle non è firmato; lo firma il Microsoft Store. Limite Store: 25 GB per pacchetto e per bundle.

Sequenza tipica:

```bash
python3 tools/release/version.py bump minor          # nuova versione + changelog
tools/release/release-android.sh                       # oppure release-android.ps1 su Windows
pwsh tools/release/release-windows.ps1 -SyncManifest   # su Windows
git add CHANGELOG.md tools/release/version.properties tools/models/models.lock && git commit
```
