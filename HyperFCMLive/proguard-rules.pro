-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Provided by the device at runtime (androidx.window uses-library, required=false)
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
