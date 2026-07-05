# Tally: Calculate (PROCESS_TEXT) — R8 / ProGuard rules.
#
# The module has no reflection, no serialization, and no JNI. The only entry point
# is ProcessTextActivity, which the Android manifest references by name; the manifest
# merger keeps it automatically. The math engine (:core-math) is a pure Kotlin tree
# walker with no reflective access, so the default optimized rules are sufficient.
#
# Keep the public activity entry point explicitly for clarity and to be robust against
# aggressive class-merging changing its externally-referenced name.
-keep class dev.tally.mathtext.ProcessTextActivity { *; }
