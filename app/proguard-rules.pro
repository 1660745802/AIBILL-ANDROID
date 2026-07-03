# --- Moshi ---
-keep class com.aibill.android.data.remote.dto.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class **JsonAdapter { *; }

# --- Retrofit ---
-keepattributes Signature
-keepattributes *Annotation*
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# --- Room ---
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
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# --- Navigation Compose ---
-keep class androidx.navigation.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# --- Biometric ---
-keep class androidx.biometric.** { *; }

# --- Glance (Widget) ---
-keep class androidx.glance.** { *; }

# --- App 自身 domain model (防止 enum 被混淆) ---
-keep class com.aibill.android.domain.model.** { *; }

# --- General ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Tink (EncryptedSharedPreferences 依赖) ---
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
