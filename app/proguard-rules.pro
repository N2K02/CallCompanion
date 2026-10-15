# VGS uses no reflection; R8's defaults plus the rules bundled with
# AndroidX/Compose are enough. Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku starts this Binder by class name in a separate shell-UID process.
-keep class com.vgs.app.VgsPrivilegedCallAudioAgent { public <init>(...); *; }
-keep interface com.vgs.app.IVgsPrivilegedAudioAgent { *; }
-keep interface com.vgs.app.IVgsCallAudioListener { *; }
