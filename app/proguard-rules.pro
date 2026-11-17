# Keep TDLib JNI classes and methods for native callbacks
-keep class org.drinkless.** { *; }
-keep interface org.drinkless.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Keep standard Android view constructors and app data structures
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.teleflix.app.** { *; }
