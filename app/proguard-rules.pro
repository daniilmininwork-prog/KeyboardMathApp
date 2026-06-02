# ── Log stripping ─────────────────────────────────────────────────────────────
# Strip all android.util.Log calls from release builds.
# This is a backstop — main sources must not call Log.* at all (enforced by
# the noTextLoggingGuard CI task). R8 still removes any that slip through.
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
# Preserve the InputMethodService entry point — the platform binds it by name.
-keep public class dev.tally.ime.TallyInputMethodService { *; }

# Preserve Parcelable implementations (none currently, but guard for future).
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve SharedPreferences keys (string constants) so ProGuard does not
# rename them and break stored preferences across updates.
-keepclassmembers class dev.tally.glue.TallyPreferences$Companion {
    public static final java.lang.String *;
}

# Preserve the MathEngine public API (called reflectively by nothing, but
# keep it stable for test coverage reporting).
-keep class dev.tally.math.MathEngine { *; }

# Suppress notes about missing classes from the default ProGuard file.
-dontnote **
