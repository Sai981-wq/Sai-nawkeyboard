-keepclassmembers class * {
    native <methods>;
}

-keep class com.mettavoice.tts.ShanTtsService { *; }
-keep class com.mettavoice.tts.ShanTtsSettingsActivity { *; }
-keep public class * extends android.speech.tts.TextToSpeechService

-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**

