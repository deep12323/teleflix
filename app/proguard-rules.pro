# Keep TDLib JNI classes and methods for native callbacks
-keep class org.drinkless.** { *; }
-keep interface org.drinkless.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Keep Media3 ExoPlayer classes
-keep class androidx.media3.** { *; }

# Keep standard Android view constructors and app data structures
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.teleflix.app.** { *; }
