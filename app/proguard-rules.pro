# --- Moshi ---
-keep class com.aibill.android.data.remote.dto.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class **JsonAdapter { *; }

# --- Retrofit ---
# Retrofit 自带 consumer-rules.pro（保留泛型签名 / Method），无需全量 keep
-keepattributes Signature
-keepattributes *Annotation*
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# --- Room ---
# Room 自带 consumer-rules.pro
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# --- Hilt ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}

# --- Hilt Worker ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker
-keep class * implements androidx.hilt.work.HiltWorkerFactory { *; }

# --- Kotlin Serialization (Navigation type-safe routes) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.aibill.android.presentation.navigation.Route$* { *; }
-keepclassmembers class com.aibill.android.presentation.navigation.Route$* {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---
# OkHttp 自带 consumer-rules.pro
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Biometric ---
-keep class androidx.biometric.** { *; }

# --- Glance (Widget) ---
# Glance 自带 consumer-rules.pro，仅保留必要反射入口
-keep class androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# --- General ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Tink (EncryptedSharedPreferences 依赖) ---
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
