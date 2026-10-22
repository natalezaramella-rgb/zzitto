# Regole R8 per il ponte JNI (Spec §10: offuscamento con R8). Aggiunto il 22/10/2026 alle 11:20.
# I metodi nativi e la callback chiamata dal C++ non devono essere rinominati.
-keepclasseswithmembernames class com.zaramella.zzitto.engine.jni.ZzNative {
    native <methods>;
}
-keep interface com.zaramella.zzitto.engine.jni.NativeStageListener { *; }
-keep class * implements com.zaramella.zzitto.engine.jni.NativeStageListener {
    public void onStage(int);
}
