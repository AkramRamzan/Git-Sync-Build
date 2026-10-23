# JGit – keep all reflection targets
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$serializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# Tree-Sitter / Nikel JNI
-keep class io.github.treesitter.** { *; }
-keep class io.github.g00fybuddy2.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# JavascriptInterface (EditorBridge)
-keepclassmembers class com.aro.code.ui.editor.EditorBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Prevent stripping PackageInstaller callbacks
-keep class com.aro.code.installer.InstallReceiver { *; }
