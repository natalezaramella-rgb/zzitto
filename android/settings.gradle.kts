// Zzitto Android — Spec §4.1, §4.3, §5.1-bis. Aggiunto il 22/10/2026 alle 11:15.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Revisione di compilabilità del 22/10/2026 alle 12:36: tutti i moduli usano `kotlin { jvmToolchain(17) }`
// e Android Studio include solo il JBR 21. Senza un resolver Gradle non trova un JDK 17 e la build si
// ferma ("No matching toolchains found"); Foojay lo scarica in automatico quando manca.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Zzitto"

// Proprietari dei moduli (Spec §16.1): B = Android, D = Acquisti, E = Conformità e rilascio
include(":app")           // B: impostazioni, onboarding, tour, guida, licenze, cronologia, dizionario, scorciatoie
include(":core")          // orchestratore: interfacce condivise (LicenseGate, ...)
include(":engine")        // B: ponte JNI verso core-native (CMake)
include(":audio")         // B: cattura microfono 16 kHz mono e VAD
include(":dictation")     // B: icona flottante, servizio di accessibilità, inserimento (§5.1-bis)
include(":licensing")     // D: prova 30 giorni, Google Play Billing, Block Store (§8.1)
include(":models")        // E: asset pack install-time con i modelli (§7)
// E: Phi-4-mini supera 1,5 GB (limite del singolo asset pack Google Play): due parti in due pack
// install-time, ricomposte al primo avvio. Aggiunto il 22/10/2026 alle 11:29. Vedi docs/release-guide.md.
include(":models_phi_1")
project(":models_phi_1").projectDir = file("models/phi_1")
include(":models_phi_2")
project(":models_phi_2").projectDir = file("models/phi_2")
