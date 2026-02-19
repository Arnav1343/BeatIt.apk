# ─── Keep our app code ───────────────────────────────────────
-keep class com.beatit.app.** { *; }

# ─── NewPipe Extractor ───────────────────────────────────────
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn mozilla.**
-dontwarn org.mozilla.**

# ─── NanoHTTPD ───────────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }

# ─── OkHttp ──────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ─── Gson ────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ─── General ─────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn sun.misc.Unsafe
