# ProGuard / R8 rules for Goony app

# Keep data models serialized by Moshi / Room / Gson
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.JsonClass <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# Keep app data entities and network models
-keep class com.example.data.** { *; }
-keep class com.example.network.** { *; }

# Keep Room entities and DAOs
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Keep Retrofit annotations and interfaces
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# Keep Coroutines
-dontwarn kotlinx.coroutines.**
