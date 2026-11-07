# Kept apart from proguard-rules.pro so that the `benchmark` build — a release build
# for measuring and for checking behaviour on a device — can leave it out and keep its
# logcat output. Only `release` applies this file.

# A release build writes nothing to logcat. Every call is removed, ours and every
# library's that goes through android.util.Log — Media3's included — and with it
# the string each one built, on threads like playback's and the analyzer's that
# log several times a second. Nobody reads logcat on a user's phone: what a bug
# report needs is Copy Log in the player, which reads TrackLog's own buffer and
# never touched logcat to begin with (see TrackLog's class doc).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
