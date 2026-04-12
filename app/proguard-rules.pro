-keepclassmembers class * {
    native <methods>;
}

-keep class com.shan.tts.ShanTtsService { *; }
-keep class com.shan.tts.ShanTtsSettingsActivity { *; }
-keep public class * extends android.speech.tts.TextToSpeechService

-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**
