// Asset pack install-time "models_phi_2": parte 2 di 2 di phi-4-mini-instruct-q4_k_m.gguf.
// Spec §7. Aggiunto il 22/10/2026 alle 11:29.
// Il file intero (circa 2,5 GB) supera il limite di 1,5 GB per singolo asset pack di Google Play,
// quindi è diviso in parti (vedi android/models/build.gradle.kts e docs/release-guide.md).
//
// Contenuto (generato da tools/models/prepare-models.*, non versionato in git):
//   src/main/assets/models/phi-4-mini-instruct-q4_k_m.gguf.2.part
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName = "models_phi_2"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
