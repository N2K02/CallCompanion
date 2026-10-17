# VGS uses no reflection; R8's defaults plus the rules bundled with
# AndroidX/Compose are enough. Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku launches this class by name in its shell process.
-keep class com.vgs.app.UplinkInjectionService { *; }
-keep class com.vgs.app.IVgsUplinkInjectionService { *; }
