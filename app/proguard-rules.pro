# Add project specific ProGuard rules here.
-keep class com.jarvis.assistant.ai.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
