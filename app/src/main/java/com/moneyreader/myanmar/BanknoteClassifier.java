package com.moneyreader.myanmar;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.tensorflow.lite.Interpreter;

public class BanknoteClassifier {

    private Interpreter interpreter;
    private static final int INPUT_SIZE = 224;
    private static final String[] LABELS = {"50", "100", "200", "500", "1000", "5000", "10000", "20000"};
    private ByteBuffer inputBuffer;
    private float[][] outputBuffer;
    private boolean isModelLoaded = false;

    public BanknoteClassifier(Context context) {
        try {
            MappedByteBuffer model = loadModelFile(context);
            interpreter = new Interpreter(model);
            inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
            inputBuffer.order(ByteOrder.nativeOrder());
            outputBuffer = new float[1][LABELS.length];
            isModelLoaded = true;
        } catch (IOException e) {
            isModelLoaded = false;
            useColorBasedDetection();
        }
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd("banknote_model.tflite");
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        long startOffset = fd.getStartOffset();
        long declaredLength = fd.getDeclaredLength();
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public String classify(Bitmap bitmap) {
        if (bitmap == null) return "unknown";

        if (isModelLoaded) {
            return classifyWithModel(bitmap);
        } else {
            return classifyWithColor(bitmap);
        }
    }

    private String classifyWithModel(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        inputBuffer.rewind();

        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
                inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
                inputBuffer.putFloat((pixel & 0xFF) / 255.0f);
            }
        }

        interpreter.run(inputBuffer, outputBuffer);
        resized.recycle();

        int maxIndex = 0;
        float maxConf = outputBuffer[0][0];
        for (int i = 1; i < LABELS.length; i++) {
            if (outputBuffer[0][i] > maxConf) {
                maxConf = outputBuffer[0][i];
                maxIndex = i;
            }
        }

        if (maxConf > 0.6f) {
            return LABELS[maxIndex];
        }
        return "unknown";
    }

    private boolean colorDetectionMode = false;

    private void useColorBasedDetection() {
        colorDetectionMode = true;
    }

    private String classifyWithColor(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 100, 100, true);
        float totalR = 0, totalG = 0, totalB = 0;
        int count = 0;

        for (int y = 20; y < 80; y++) {
            for (int x = 20; x < 80; x++) {
                int pixel = resized.getPixel(x, y);
                totalR += Color.red(pixel);
                totalG += Color.green(pixel);
                totalB += Color.blue(pixel);
                count++;
            }
        }

        float avgR = totalR / count;
        float avgG = totalG / count;
        float avgB = totalB / count;
        resized.recycle();

        float brightness = (avgR + avgG + avgB) / 3;
        if (brightness < 30 || brightness > 240) return "unknown";

        if (avgR > 150 && avgG < 100 && avgB < 100) return "5000";
        if (avgR < 80 && avgG > 120 && avgB > 120) return "1000";
        if (avgR > 120 && avgG > 100 && avgB < 80) return "500";
        if (avgR > 100 && avgG > 130 && avgB > 100) return "10000";
        if (avgR > 140 && avgG > 100 && avgB > 120) return "20000";
        if (avgR > 100 && avgG > 80 && avgB < 60) return "200";
        if (avgR > 80 && avgG > 100 && avgB > 60) return "100";
        if (avgR > 60 && avgG > 80 && avgB > 100) return "50";

        return "unknown";
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
    }
}
