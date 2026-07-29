# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===================================================================
# Fix #1 — Native libraries / Shell Packer
# ===================================================================
# Keep all JNI methods so the linker can resolve them at runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve the native library loading entry points used by common
# shell-packer / obfuscation SDKs (e.g. Oceanwing SoundCore).
-keep class s.h.e.l.l.** { *; }
-keep class com.oceanwing.soundcore.** { *; }

# Ensure all native .so metadata survives shrinking.
-keepattributes *Annotation*

# ===================================================================
# Fix #2 — CLDR / ResourceBundle / i18n locale formatting
# ===================================================================
# Keep all java.util.ResourceBundle subclasses and their lookup
# infrastructure so that MissingResourceException is not triggered
# after minification.
-keep class java.util.ResourceBundle$** { *; }
-keep class java.util.spi.ResourceBundleProvider { *; }
-keep class sun.util.locale.** { *; }
-keep class libcore.icu.** { *; }

# Preserve CLDR data used by android.icu (runtime locale formatting).
-keep class android.icu.** { *; }
-keep class com.ibm.icu.** { *; }

# Keep MessageFormat / String.format locale resolution helpers.
-keep class java.text.MessageFormat { *; }
-keep class java.text.DecimalFormat { *; }
-keep class java.text.SimpleDateFormat { *; }

# ===================================================================
# Fix #3 — SharedPreferences in isolated processes
# ===================================================================
# Keep the custom Application class so onCreate/attachBaseContext is
# not optimised away.
-keep class com.antor.sosblue.SOSBlueApplication { *; }

# ===================================================================
# Fix #4 — Concurrency / reflection-sensitive structures
# ===================================================================
# Preserve the adapter data-observer mechanism used by ListAdapter.
-keep class androidx.recyclerview.widget.RecyclerView$Adapter { *; }
-keep class androidx.recyclerview.widget.ListAdapter { *; }
-keep class androidx.recyclerview.widget.DiffUtil$** { *; }