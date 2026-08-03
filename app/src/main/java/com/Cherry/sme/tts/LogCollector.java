package com.cherry.sme.tts;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class LogCollector {

    private static final StringBuilder logBuilder = new StringBuilder();
    private static final int MAX_LOG_SIZE = 100000;
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final AtomicInteger warnCount = new AtomicInteger(0);
    private static final AtomicInteger infoCount = new AtomicInteger(0);
    private static volatile long serviceStartTime = 0;
    private static volatile long lastSpeakTime = 0;
    private static volatile int totalSpeakRequests = 0;
    private static volatile int successfulSpeaks = 0;
    private static volatile int failedSpeaks = 0;

    public static synchronized void addLog(String tag, String message) {
        if (logBuilder.length() > MAX_LOG_SIZE) {
            logBuilder.delete(0, logBuilder.length() / 2);
            logBuilder.insert(0, "--- Old logs trimmed ---\n\n");
        }
        String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logBuilder.append("[").append(timeStamp).append("] ").append(tag).append(": ").append(message).append("\n");
        infoCount.incrementAndGet();
    }

    public static synchronized void addError(String tag, String message) {
        if (logBuilder.length() > MAX_LOG_SIZE) {
            logBuilder.delete(0, logBuilder.length() / 2);
            logBuilder.insert(0, "--- Old logs trimmed ---\n\n");
        }
        String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logBuilder.append("[").append(timeStamp).append("] ERROR ").append(tag).append(": ").append(message).append("\n");
        errorCount.incrementAndGet();
    }

    public static synchronized void addError(String tag, String message, Throwable t) {
        if (logBuilder.length() > MAX_LOG_SIZE) {
            logBuilder.delete(0, logBuilder.length() / 2);
            logBuilder.insert(0, "--- Old logs trimmed ---\n\n");
        }
        String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logBuilder.append("[").append(timeStamp).append("] ERROR ").append(tag).append(": ").append(message);
        if (t != null) {
            logBuilder.append(" | Exception: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            StackTraceElement[] stack = t.getStackTrace();
            if (stack != null && stack.length > 0) {
                logBuilder.append(" @ ").append(stack[0].toString());
            }
        }
        logBuilder.append("\n");
        errorCount.incrementAndGet();
    }

    public static synchronized void addWarn(String tag, String message) {
        if (logBuilder.length() > MAX_LOG_SIZE) {
            logBuilder.delete(0, logBuilder.length() / 2);
            logBuilder.insert(0, "--- Old logs trimmed ---\n\n");
        }
        String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logBuilder.append("[").append(timeStamp).append("] WARN ").append(tag).append(": ").append(message).append("\n");
        warnCount.incrementAndGet();
    }

    public static void recordServiceStart() {
        serviceStartTime = System.currentTimeMillis();
        addLog("SERVICE", "TTS Service started");
    }

    public static void recordSpeakRequest() {
        totalSpeakRequests++;
        lastSpeakTime = System.currentTimeMillis();
    }

    public static void recordSpeakSuccess() {
        successfulSpeaks++;
    }

    public static void recordSpeakFailure() {
        failedSpeaks++;
    }

    public static synchronized String getLogs() {
        if (logBuilder.length() == 0 && errorCount.get() == 0) {
            return "No logs recorded yet.\n\nService is idle.";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("========== TTS Service Status ==========\n");
        if (serviceStartTime > 0) {
            long uptime = System.currentTimeMillis() - serviceStartTime;
            long sec = uptime / 1000;
            long min = sec / 60;
            long hr = min / 60;
            summary.append("Uptime: ").append(hr).append("h ").append(min % 60).append("m ").append(sec % 60).append("s\n");
        }
        summary.append("Total Requests: ").append(totalSpeakRequests).append("\n");
        summary.append("Success: ").append(successfulSpeaks).append("\n");
        summary.append("Failed: ").append(failedSpeaks).append("\n");
        summary.append("Errors: ").append(errorCount.get()).append("\n");
        summary.append("Warnings: ").append(warnCount.get()).append("\n");
        summary.append("========================================\n\n");
        summary.append(logBuilder.toString());
        return summary.toString();
    }

    public static synchronized String getErrorsOnly() {
        if (logBuilder.length() == 0) return "No errors recorded.";
        StringBuilder errors = new StringBuilder();
        String[] lines = logBuilder.toString().split("\n");
        for (String line : lines) {
            if (line.contains("ERROR") || line.contains("WARN")) {
                errors.append(line).append("\n");
            }
        }
        if (errors.length() == 0) return "No errors found. All operations successful.";
        return errors.toString();
    }

    public static boolean hasErrors() {
        return errorCount.get() > 0;
    }

    public static int getErrorCount() {
        return errorCount.get();
    }

    public static synchronized void clear() {
        logBuilder.setLength(0);
        errorCount.set(0);
        warnCount.set(0);
        infoCount.set(0);
        totalSpeakRequests = 0;
        successfulSpeaks = 0;
        failedSpeaks = 0;
    }
}
