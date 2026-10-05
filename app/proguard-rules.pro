# ─── App Data Models & Room Entities (needed for Gson & Firestore deserialization) ───
-keep class com.jeeneet.mocktest.data.model.** { *; }
-keepclassmembers class com.jeeneet.mocktest.data.model.** { *; }

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}

# ─── Markwon / JLatexMath (LaTeX math rendering) ──────────────────────────────
-keep class io.noties.markwon.** { *; }
-keepclassmembers class io.noties.markwon.** { *; }

-keep class ru.noties.jlatexmath.** { *; }
-keepclassmembers class ru.noties.jlatexmath.** { *; }

-keep class org.scilab.forge.jlatexmath.** { *; }
-keepclassmembers class org.scilab.forge.jlatexmath.** { *; }

-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*

-dontwarn io.noties.markwon.**
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.scilab.forge.jlatexmath.**

# ─── Gson ────────────────────────────────────────────────────────────────────
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ─── MPAndroidChart ──────────────────────────────────────────────────────────
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ─── AdMob Mediation Adapters ────────────────────────────────────────────────
-keep public class com.google.ads.mediation.facebook.FacebookAdapter
-keep public class com.google.ads.mediation.facebook.FacebookMediationAdapter
-keep public class com.google.ads.mediation.applovin.AppLovinMediationAdapter
-keep public class com.google.ads.mediation.unity.UnityMediationAdapter

-dontwarn com.facebook.**
-dontwarn com.applovin.**
-dontwarn com.unity3d.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# ─── Kotlin Coroutines & Attributes ──────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
