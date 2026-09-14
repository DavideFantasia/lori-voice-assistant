# Regole minime per non rompere le librerie che usano reflection/JNI —
# senza queste, R8 potrebbe rimuovere/rinominare classi che quelle librerie
# si aspettano di trovare con un nome esatto a runtime, causando crash SOLO
# in build di release (mai in debug, dove minify è disattivato) — motivo
# per cui vanno sempre testate build di release reali prima di fidarsi.

# Vosk (JNI — i binding nativi si aspettano nomi di classe/metodo esatti)
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# openWakeWord (usa ONNX Runtime + reflection interna)
-keep class com.rementia.openwakeword.** { *; }
-dontwarn com.rementia.openwakeword.**

# Kotlin coroutines — regole standard raccomandate dal progetto kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
