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

# Prevent code obfuscation to keep the original class, method, and field names.
-dontobfuscate

# Keep the original class and method names for debugging on release builds.
-keepattributes LineNumberTable
-keepattributes SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod

# Strip all Log.v calls
-assumenosideeffects class android.util.Log {
  v(...);
}

# Also strip your our custom AppLogger verbose calls
-assumenosideeffects class com.baba.callvault.utils.AppLogger {
  v(...);
}

# Conscrypt (pulled in transitively by the embedded-ADB TLS layer) references optional legacy platform
# SSL classes that are not on the compile classpath and are never present at runtime on supported
# Android versions. Without these rules R8 fails the release build on the dangling references.
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
