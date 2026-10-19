# Release shrinking. Debug builds do not use this file.
# Line numbers stay in the mapping so crash reports can be retraced.

-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# The page calls AndRipBridge.found. The method name has to survive R8.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.andrip.browser.browser.BrowserWebView$Bridge { *; }

# WorkManager constructs workers from the class recorded in the work request.
-keep class * extends androidx.work.ListenableWorker { *; }

# Room entities, the database, and generated DAOs.
-keep class com.andrip.browser.data.** { *; }

# Manifest components. AGP keeps these too; this is the explicit list.
-keep class com.andrip.browser.MainActivity { *; }
-keep class com.andrip.browser.AndRipApp { *; }
-keep class com.andrip.browser.download.DownloadActionReceiver { *; }
-keep class com.andrip.browser.privacy.SessionGuardService { *; }

# Media3 loads extractors, renderers, and downloaders reflectively.
# The AARs also ship consumer rules; this keeps the players this app constructs.
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-dontwarn androidx.media3.**

# OkHttp optional TLS providers that are not packaged with the app.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
