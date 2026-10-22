// Asset pack install-time "models": modello Whisper e indice dei modelli.
// Spec §7 (modelli nell'asset pack install-time, versione legata all'app). Aggiunto il 22/10/2026 alle 11:29.
//
// Limiti Google Play verificati il 22/10/2026 (support.google.com/googleplay/android-developer/answer/9859372):
//   - singolo asset pack: 1,5 GB di download compresso;
//   - totale moduli + asset pack install-time: 4 GB.
// Phi-4-mini Q4_K_M (circa 2,5 GB) supera il limite del singolo pack, quindi è diviso in due parti
// che stanno negli asset pack :models_phi_1 e :models_phi_2 (cartelle phi_1 e phi_2).
// Le parti vengono ricomposte al primo avvio dall'app (agente B). Vedi docs/release-guide.md.
//
// Contenuto (generato da tools/models/prepare-models.*, non versionato in git):
//   src/main/assets/models/whisper-medium-q5_0.bin
//   src/main/assets/models/models-index.json
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName = "models"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
