# Regole minime per non rompere le librerie che usano reflection/JNI —
# senza queste, R8 potrebbe rimuovere/rinominare classi che quelle librerie
# si aspettano di trovare con un nome esatto a runtime, causando crash SOLO
# in build di release (mai in debug, dove minify è disattivato) — motivo
# per cui vanno sempre testate build di release reali prima di fidarsi.

# Vosk (JNI — i binding nativi si aspettano nomi di classe/metodo esatti)
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# Mantieni intatte le librerie JNA (necessarie per Vosk)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Kotlin coroutines — regole standard raccomandate dal progetto kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
