# ── Log stripping ─────────────────────────────────────────────────────────────
# Strip all android.util.Log calls from release builds. Permitted Log.w/Log.e in main
# sources are still removed here so no log text survives in a shipped APK.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(...);
}

# Strip kotlin.io.println (maps to System.out.println on JVM).
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void println(...);
    public static void print(...);
}

# ── Keep rules ────────────────────────────────────────────────────────────────
# The accessibility service is bound by the platform by name; it lives in :overlay but the
# release R8 run for this app must not strip it from the merged DEX.
-keep public class dev.tally.overlay.TallyOverlayService { *; }

# Preserve the SharedPreferences key constants so R8 does not rename them and break
# the store shared with the accessibility service across updates.
-keepclassmembers class dev.tally.glue.TallyPreferences$Companion {
    public static final java.lang.String *;
}

# Preserve Parcelable implementations (none currently, but guard for future).
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Suppress notes about missing classes from the default ProGuard file.
-dontnote **
