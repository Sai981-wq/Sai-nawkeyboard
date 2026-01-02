package com.shan.tts.manager;

import java.nio.ShortBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Sonic {
    private int inputSampleRate;
    private int outputSampleRate; // Fixed at 24000
    private int numChannels;
    private float speed;
    private float pitch;
    private float volume;
    
    // Buffers
    private short[] inputBuffer;
    private short[] outputBuffer;
    private int numInputSamples;
    private int numOutputSamples;
    private int inputBufferSize = 4096;
    private int outputBufferSize = 4096;

    public Sonic(int inputSampleRate, int numChannels) {
        this.inputSampleRate = inputSampleRate;
        this.numChannels = numChannels;
        this.outputSampleRate = 24000; // Default Target
        this.speed = 1.0f;
        this.pitch = 1.0f;
        this.volume = 1.0f;
        
        this.inputBuffer = new short[inputBufferSize];
        this.outputBuffer = new short[outputBufferSize];
        
        AppLogger.INSTANCE.log("Sonic (Java) Created: In=" + inputSampleRate + ", Out=" + outputSampleRate);
    }

    public void setSampleRate(int rate) {
        if (this.inputSampleRate != rate) {
            this.inputSampleRate = rate;
            AppLogger.INSTANCE.log("Sonic: Input Rate changed to " + rate);
        }
    }
    
    public void setOutputSampleRate(int rate) {
        this.outputSampleRate = rate;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    // Input: Writes bytes from TTS Engine into Sonic
    public void writeBytesToStream(byte[] data, int length) {
        int samples = length / 2;
        ensureInputBufferSize(samples);
        
        // Convert byte[] to short[]
        ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputBuffer, numInputSamples, samples);
        numInputSamples += samples;
        
        processAudio();
    }

    // Output: Reads processed bytes from Sonic to AudioTrack
    public int readBytesFromStream(byte[] data, int maxBytes) {
        int maxSamples = maxBytes / 2;
        int available = numOutputSamples;
        
        if (available == 0) return 0;
        
        int samplesToCopy = Math.min(available, maxSamples);
        
        // Convert short[] back to byte[]
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0; i<samplesToCopy; i++) {
            buffer.putShort(outputBuffer[i]);
        }
        
        // Shift remaining buffer
        int remaining = numOutputSamples - samplesToCopy;
        if (remaining > 0) {
            System.arraycopy(outputBuffer, samplesToCopy, outputBuffer, 0, remaining);
        }
        numOutputSamples = remaining;
        
        return samplesToCopy * 2; // return bytes count
    }
    
    public int samplesAvailable() {
        return numOutputSamples * 2; // return bytes
    }

    // Core Processing Logic (Linear Resampling + Speed)
    private void processAudio() {
        if (numInputSamples == 0) return;

        // Calculate Scale Factor: Speed * (InputHz / OutputHz)
        // Example: Speed 1.0, Input 16k, Output 24k => Scale = 1.0 * (16/24) = 0.666
        float rateRatio = (float) inputSampleRate / (float) outputSampleRate;
        float scale = speed * rateRatio; 
        
        if (scale < 0.01f) scale = 0.01f; // Prevent div by zero

        int requiredOutput = (int) (numInputSamples / scale);
        ensureOutputBufferSize(requiredOutput);

        // Linear Interpolation Resampling
        for (int i = 0; i < requiredOutput; i++) {
            float sourceIndex = i * scale;
            int idx = (int) sourceIndex;
            float frac = sourceIndex - idx;

            if (idx >= numInputSamples - 1) idx = numInputSamples - 2;
            if (idx < 0) idx = 0;

            short v1 = inputBuffer[idx];
            short v2 = inputBuffer[idx + 1];
            
            // Interpolate
            short val = (short) (v1 + frac * (v2 - v1));
            outputBuffer[numOutputSamples + i] = val;
        }

        numOutputSamples += requiredOutput;
        numInputSamples = 0; // All processed
    }

    // Buffer Helper
    private void ensureInputBufferSize(int needed) {
        if (numInputSamples + needed > inputBuffer.length) {
            int newSize = (numInputSamples + needed) * 2;
            short[] newBuf = new short[newSize];
            System.arraycopy(inputBuffer, 0, newBuf, 0, numInputSamples);
            inputBuffer = newBuf;
            // AppLogger.INSTANCE.log("Sonic: Input Buffer Resized to " + newSize);
        }
    }

    private void ensureOutputBufferSize(int needed) {
        if (numOutputSamples + needed > outputBuffer.length) {
            int newSize = (numOutputSamples + needed) * 2;
            short[] newBuf = new short[newSize];
            System.arraycopy(outputBuffer, 0, newBuf, 0, numOutputSamples);
            outputBuffer = newBuf;
            // AppLogger.INSTANCE.log("Sonic: Output Buffer Resized to " + newSize);
        }
    }
    
    public void flush() {
        numInputSamples = 0;
        numOutputSamples = 0;
    }
}
