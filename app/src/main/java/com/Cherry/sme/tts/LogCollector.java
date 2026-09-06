package com.cherry.sme.tts;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogCollector {
    private static StringBuilder logBuilder = new StringBuilder();

    public static synchronized void addLog(String tag, String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuilder.append("[").append(timeStamp).append("] ").append(tag).append(": ").append(message).append("\n\n");
    }

    public static String getLogs() {
        return logBuilder.length() > 0 ? logBuilder.toString() : "No logs recorded yet.";
    }

    public static void clear() {
        logBuilder.setLength(0);
    }
}

