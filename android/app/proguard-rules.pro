# Regole R8 dell'app (Spec §10). Aggiunto il 22/10/2026 alle 11:59.
# SQLCipher usa JNI e reflection sulle proprie classi.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
# Nessun log di debug nella release (Spec §10: niente testo dettato nei log).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
