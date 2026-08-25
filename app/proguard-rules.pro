# Jiyu — ProGuard / R8 pravidla pro release build

# Hilt – generované tříedy musí zůstat
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# Room – entity třídy (sloupeček mapování probíhá přes reflection)
-keep class com.haise.jiyu.data.db.entity.** { *; }
-keepclassmembers class com.haise.jiyu.data.db.entity.** { *; }

# Kotlin data class serialization (org.json nemá reflection ale pro jistotu)
-keepclassmembers class com.haise.jiyu.translate.** { *; }

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coil
-dontwarn coil.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime (manga-ocr, bublinovy detektor) - nativni .so kod si Java tridy
# (OnnxTensor, TensorInfo, OrtSession, ...) hleda pres JNI GetMethodID/FindClass podle
# natvrdo zapsaneho nazvu (napr. "ai/onnxruntime/TensorInfo"). Bez -keep je R8 v release
# buildu prejmenuje/odstrani a nativni volani spadne na
# "JNI DETECTED ERROR IN APPLICATION: java_class == null" (fatalni SIGABRT primo v
# OrtSession.run, videno v produkcnim logcatu - appka pri prekladu tvrde spadla).
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin metadata (needed for reflection-free coroutines)
-keep class kotlin.Metadata { *; }

# BuildConfig (must survive – used at runtime for API key)
-keep class com.haise.jiyu.BuildConfig { *; }

# Enum values (Room DownloadStatus)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager workers
-keep class com.haise.jiyu.download.** { *; }
-keepclassmembers class com.haise.jiyu.download.** { *; }
-keep class com.haise.jiyu.work.** { *; }
-keepclassmembers class com.haise.jiyu.work.** { *; }

# Jsoup (HTML parsing pro generický Madara zdroj)
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep @kotlinx.serialization.Serializable class * { *; }

# Ktor (used by Supabase)
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep class io.ktor.client.** { *; }

# Supabase
-dontwarn io.github.jan.tennert.supabase.**
-keep class io.github.jan.tennert.supabase.** { *; }

# ZXing (QR code generation)
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# slf4j - volitelná vazba na logovací backend, kterou si tahá tranzitivní závislost
# (Jsoup/Ktor). Za běhu se nepoužívá, slf4j si absenci backendu ošetří sám, ale R8 na ni
# jinak spadne jako na chybějící třídu.
-dontwarn org.slf4j.impl.StaticLoggerBinder
