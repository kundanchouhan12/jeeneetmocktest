# ─── Markwon / JLatexMath (math rendering) ───────────────────────────────────
# Keep classes AND all members (methods/fields) — same root cause as the Guava crash:
# library uses reflection internally; stripping any member = silent crash at runtime.

-keep class io.noties.markwon.** { *; }
-keepclassmembers class io.noties.markwon.** { *; }

# jlatexmath-android public API
-keep class ru.noties.jlatexmath.** { *; }
-keepclassmembers class ru.noties.jlatexmath.** { *; }

# jlatexmath-android wraps the original scilab jlatexmath Java library internally —
# it loads TeXFormula / TeXEnvironment etc. by name via Class.forName(); if these
# are renamed or removed by R8, rendering silently crashes with ClassNotFoundException.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keepclassmembers class org.scilab.forge.jlatexmath.** { *; }

# Keep generic type signatures — Markwon's plugin system reads these at runtime
# (same class of bug as Gson/TypeToken crashes when Signature attribute is stripped)
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-dontwarn io.noties.markwon.**
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.scilab.forge.jlatexmath.**

# ─── Firebase / Firestore ────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Firebase model classes (needed for Firestore deserialization)
-keep class com.jeeneet.mocktest.data.model.** { *; }
-keep class com.jeeneet.mocktest.data.repository.** { *; }

# ─── Room Database ───────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ─── Gson ────────────────────────────────────────────────────────────────────
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ─── Google Play Billing ─────────────────────────────────────────────────────
-keep class com.android.billingclient.** { *; }

# ─── AdMob ───────────────────────────────────────────────────────────────────
-keep class com.google.android.gms.ads.** { *; }

# ─── Meta (Facebook) Audience Network ────────────────────────────────────────
-dontwarn com.facebook.infer.annotation.Nullsafe$Mode
-dontwarn com.facebook.infer.annotation.Nullsafe
-keep class com.facebook.** { *; }
-keep interface com.facebook.** { *; }
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod
-keep public class com.google.ads.mediation.facebook.FacebookAdapter
-keep public class com.google.ads.mediation.facebook.FacebookMediationAdapter

# ─── AppLovin ─────────────────────────────────────────────────────────────────
-keep class com.applovin.** { *; }
-keep interface com.applovin.** { *; }
-keep public class com.google.ads.mediation.applovin.AppLovinMediationAdapter

# ─── MPAndroidChart ──────────────────────────────────────────────────────────
-keep class com.github.mikephil.charting.** { *; }

# ─── Unity Ads (SDK now bundled alongside the mediation adapter) ─────────────
# Adapter reaches into the SDK via reflection during initialize() — same class of bug as
# the Guava/Markwon crashes above. Keep everything so R8 can't strip what it can't see used.
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-keep public class com.google.ads.mediation.unity.UnityMediationAdapter
-dontwarn com.unity3d.ads.**
-dontwarn com.unity3d.services.**

# ─── Guava (transitive dep of Firebase/Firestore) ────────────────────────────
# R8 strips Guava internals used by Firebase via reflection → runtime crashes.
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
# ListenableFuture is used extensively by Firebase SDK
-keep class com.google.guava.** { *; }
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }

# ─── Kotlin Coroutines ───────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
