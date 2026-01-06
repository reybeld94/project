# Optimizaciones para Compose
-keep class androidx.compose.** { *; }
-keep class kotlin.Metadata { *; }

# Optimizaciones para Coil (carga de imágenes)
-keep class coil.** { *; }
-dontwarn coil.**

# Mantén las clases de data de la API
-keep class com.reybel.ellentv.data.api.** { *; }

# ExoPlayer optimizations
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Optimizaciones generales
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# Permite que R8 haga inline más agresivo
-allowaccessmodification
-repackageclasses