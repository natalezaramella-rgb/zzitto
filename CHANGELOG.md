# Changelog di Zzitto

*Aggiunto il 22/10/2026 alle 11:29. Riferimento: Spec §13.*

Versionamento semantico (MAJOR.MINOR.PATCH). Ogni versione riporta il codice versione Android, la versione del
pacchetto Windows e la versione dei modelli inclusi (`tools/models/models.lock`), come chiede il §13.
Le voci nuove vanno sotto "Non rilasciato"; `python3 tools/release/version.py bump patch|minor|major` le sposta
nella sezione della nuova versione con data e ora (ora italiana).

## [Non rilasciato]

### Aggiunto

- Asset pack install-time dei modelli: `models` (Whisper) e `models_phi_1`, `models_phi_2` (Phi-4-mini diviso in due parti per il limite di 1,5 GB per asset pack di Google Play) (Spec §7).
- Script di preparazione dei modelli con verifica SHA-256 e aggiornamento automatico di `ModelManifest` (`tools/models/`).
- Script di rilascio locali: test obbligatori, AAB firmato con la chiave di upload, bundle MSIX ARM64 + x64 (`tools/release/`) (Spec §12, §13).
- Allegato di conformità ISO 27001/27017/27018 e checklist OWASP MASVS (`docs/compliance/`) (Spec §10).
- Informativa privacy in italiano e inglese, anche in HTML generata da `tools/docs/build-privacy-html.py` (`docs/privacy/`) (Spec §10).
- Testi per Google Play e Microsoft Store (`docs/store/`) (Spec §8, §8.2, §5.1-bis).
- Elenco delle licenze di terze parti (`THIRD_PARTY_LICENSES.md`) (Spec §2.2).
- Guida al rilascio per il titolare (`docs/release-guide.md`).
