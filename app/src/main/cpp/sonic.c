/* Sonic library
   Copyright 2010
   Bill Cox
   This file is part of the Sonic Library.

   This file is licensed under the Apache License, Version 2.0.
*/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <math.h>

/*
    Header definition embedded to make this a single file solution
    if you don't have sonic.h
*/
#ifndef SONIC_H_DEFINED
#define SONIC_H_DEFINED

#ifdef __cplusplus
extern "C" {
#endif

/* Defines for the sonic library */
#define SONIC_MIN_PITCH 65
#define SONIC_MAX_PITCH 400
#define SONIC_AMDF_FREQ 4000

struct sonicStreamStruct;
typedef struct sonicStreamStruct *sonicStream;

sonicStream sonicCreateStream(int sampleRate, int numChannels);
void sonicDestroyStream(sonicStream stream);
void sonicWriteFloatToStream(sonicStream stream, float *samples, int numSamples);
void sonicWriteShortToStream(sonicStream stream, short *samples, int numSamples);
void sonicWriteUnsignedCharToStream(sonicStream stream, unsigned char *samples, int numSamples);
int sonicReadFloatFromStream(sonicStream stream, float *samples, int maxSamples);
int sonicReadShortFromStream(sonicStream stream, short *samples, int maxSamples);
int sonicReadUnsignedCharFromStream(sonicStream stream, unsigned char *samples, int maxSamples);
int sonicFlushStream(sonicStream stream);
int sonicSamplesAvailable(sonicStream stream);
void sonicSetSpeed(sonicStream stream, float speed);
float sonicGetSpeed(sonicStream stream);
void sonicSetPitch(sonicStream stream, float pitch);
float sonicGetPitch(sonicStream stream);
void sonicSetRate(sonicStream stream, float rate);
float sonicGetRate(sonicStream stream);
void sonicSetVolume(sonicStream stream, float volume);
float sonicGetVolume(sonicStream stream);
void sonicSetQuality(sonicStream stream, int quality);
int sonicGetQuality(sonicStream stream);
void sonicSetSampleRate(sonicStream stream, int sampleRate);
int sonicGetSampleRate(sonicStream stream);
void sonicSetNumChannels(sonicStream stream, int numChannels);
int sonicGetNumChannels(sonicStream stream);
void sonicSetChordPitch(sonicStream stream, int useChordPitch);
int sonicGetChordPitch(sonicStream stream);

#ifdef __cplusplus
}
#endif
#endif /* SONIC_H_DEFINED */


/* Actual Implementation of sonic.c */

struct sonicStreamStruct {
    short *inputBuffer;
    short *outputBuffer;
    short *pitchBuffer;
    short *downSampleBuffer;
    float speed;
    float volume;
    float pitch;
    float rate;
    int oldRatePosition;
    int newRatePosition;
    int useChordPitch;
    int quality;
    int numChannels;
    int inputBufferSize;
    int pitchBufferSize;
    int outputBufferSize;
    int numInputSamples;
    int numOutputSamples;
    int numPitchSamples;
    int minPeriod;
    int maxPeriod;
    int maxRequired;
    int remainingInputToCopy;
    int sampleRate;
    int prevPeriod;
    int prevMinDiff;
    int minDiff;
    int maxDiff;
};

/* Scale the samples by the factor. */
static void scaleSamples(
    short *samples,
    int numSamples,
    float volume)
{
    int fixedPointVolume = volume * 4096.0f;
    int value;

    while(numSamples--) {
        value = (*samples * fixedPointVolume) >> 12;
        if(value > 32767) value = 32767;
        else if(value < -32767) value = -32767;
        *samples++ = value;
    }
}

/* Get the speed of the stream. */
float sonicGetSpeed(sonicStream stream)
{
    return stream->speed;
}

/* Set the speed of the stream. */
void sonicSetSpeed(sonicStream stream, float speed)
{
    stream->speed = speed;
}

/* Get the pitch of the stream. */
float sonicGetPitch(sonicStream stream)
{
    return stream->pitch;
}

/* Set the pitch of the stream. */
void sonicSetPitch(sonicStream stream, float pitch)
{
    stream->pitch = pitch;
}

/* Get the rate of the stream. */
float sonicGetRate(sonicStream stream)
{
    return stream->rate;
}

/* Set the rate of the stream. */
void sonicSetRate(sonicStream stream, float rate)
{
    stream->rate = rate;
    stream->oldRatePosition = 0;
    stream->newRatePosition = 0;
}

/* Get the vocal chord pitch setting. */
int sonicGetChordPitch(sonicStream stream)
{
    return stream->useChordPitch;
}

/* Set the vocal chord pitch setting. */
void sonicSetChordPitch(sonicStream stream, int useChordPitch)
{
    stream->useChordPitch = useChordPitch;
}

/* Get the quality setting. */
int sonicGetQuality(sonicStream stream)
{
    return stream->quality;
}

/* Set the "quality".  Default 0 is virtually as good as 1, but very much faster. */
void sonicSetQuality(sonicStream stream, int quality)
{
    stream->quality = quality;
}

/* Get the scaling factor. */
float sonicGetVolume(sonicStream stream)
{
    return stream->volume;
}

/* Set the scaling factor. */
void sonicSetVolume(sonicStream stream, float volume)
{
    stream->volume = volume;
}

/* Allocate stream buffers. */
static int allocateStreamBuffers(sonicStream stream, int sampleRate, int numChannels)
{
    int minPeriod = sampleRate / SONIC_MAX_PITCH;
    int maxPeriod = sampleRate / SONIC_MIN_PITCH;
    int maxRequired = 2 * maxPeriod;

    stream->inputBufferSize = maxRequired;
    stream->inputBuffer = (short *)calloc(maxRequired * numChannels, sizeof(short));
    if(stream->inputBuffer == NULL) {
        return 0;
    }
    stream->outputBufferSize = maxRequired;
    stream->outputBuffer = (short *)calloc(maxRequired * numChannels, sizeof(short));
    if(stream->outputBuffer == NULL) {
        return 0;
    }
    stream->pitchBufferSize = maxRequired;
    stream->pitchBuffer = (short *)calloc(maxRequired * numChannels, sizeof(short));
    if(stream->pitchBuffer == NULL) {
        return 0;
    }
    stream->downSampleBuffer = (short *)calloc(maxRequired, sizeof(short));
    if(stream->downSampleBuffer == NULL) {
        return 0;
    }
    stream->sampleRate = sampleRate;
    stream->numChannels = numChannels;
    stream->oldRatePosition = 0;
    stream->newRatePosition = 0;
    stream->minPeriod = minPeriod;
    stream->maxPeriod = maxPeriod;
    stream->maxRequired = maxRequired;
    stream->prevPeriod = 0;
    return 1;
}

/* Create a sonic stream. */
sonicStream sonicCreateStream(int sampleRate, int numChannels)
{
    sonicStream stream = (sonicStream)calloc(1, sizeof(struct sonicStreamStruct));

    if(stream == NULL) {
        return NULL;
    }
    if(!allocateStreamBuffers(stream, sampleRate, numChannels)) {
        sonicDestroyStream(stream);
        return NULL;
    }
    stream->speed = 1.0f;
    stream->pitch = 1.0f;
    stream->volume = 1.0f;
    stream->rate = 1.0f;
    stream->oldRatePosition = 0;
    stream->newRatePosition = 0;
    stream->useChordPitch = 0;
    stream->quality = 0;
    return stream;
}

/* Get the sample rate of the stream. */
int sonicGetSampleRate(sonicStream stream)
{
    return stream->sampleRate;
}

/* Set the sample rate of the stream. */
void sonicSetSampleRate(sonicStream stream, int sampleRate)
{
    sonicDestroyStream(stream); // Re-create buffers
    allocateStreamBuffers(stream, sampleRate, stream->numChannels);
}

/* Get the number of channels. */
int sonicGetNumChannels(sonicStream stream)
{
    return stream->numChannels;
}

/* Set the number of channels. */
void sonicSetNumChannels(sonicStream stream, int numChannels)
{
    sonicDestroyStream(stream); // Re-create buffers
    allocateStreamBuffers(stream, stream->sampleRate, numChannels);
}

/* Destroy the sonic stream. */
void sonicDestroyStream(sonicStream stream)
{
    if(stream != NULL) {
        if(stream->inputBuffer != NULL) {
            free(stream->inputBuffer);
        }
        if(stream->outputBuffer != NULL) {
            free(stream->outputBuffer);
        }
        if(stream->pitchBuffer != NULL) {
            free(stream->pitchBuffer);
        }
        if(stream->downSampleBuffer != NULL) {
            free(stream->downSampleBuffer);
        }
        free(stream);
    }
}

/* Enlarge the output buffer if needed. */
static int enlargeOutputBufferIfNeeded(sonicStream stream, int numSamples)
{
    if(stream->numOutputSamples + numSamples > stream->outputBufferSize) {
        stream->outputBufferSize += (stream->outputBufferSize >> 1) + numSamples;
        stream->outputBuffer = (short *)realloc(stream->outputBuffer,
            stream->outputBufferSize * stream->numChannels * sizeof(short));
        if(stream->outputBuffer == NULL) {
            return 0;
        }
    }
    return 1;
}

/* Enlarge the input buffer if needed. */
static int enlargeInputBufferIfNeeded(sonicStream stream, int numSamples)
{
    if(stream->numInputSamples + numSamples > stream->inputBufferSize) {
        stream->inputBufferSize += (stream->inputBufferSize >> 1) + numSamples;
        stream->inputBuffer = (short *)realloc(stream->inputBuffer,
            stream->inputBufferSize * stream->numChannels * sizeof(short));
        if(stream->inputBuffer == NULL) {
            return 0;
        }
    }
    return 1;
}

/* Add the input samples to the input buffer. */
static int addFloatSamplesToInputBuffer(
    sonicStream stream,
    float *samples,
    int numSamples)
{
    short *buffer;
    int count = numSamples * stream->numChannels;

    if(numSamples == 0) {
        return 1;
    }
    if(!enlargeInputBufferIfNeeded(stream, numSamples)) {
        return 0;
    }
    buffer = stream->inputBuffer + stream->numInputSamples * stream->numChannels;
    while(count--) {
        *buffer++ = (*samples++) * 32767.0f;
    }
    stream->numInputSamples += numSamples;
    return 1;
}

/* Add the input samples to the input buffer. */
static int addShortSamplesToInputBuffer(
    sonicStream stream,
    short *samples,
    int numSamples)
{
    if(numSamples == 0) {
        return 1;
    }
    if(!enlargeInputBufferIfNeeded(stream, numSamples)) {
        return 0;
    }
    memcpy(stream->inputBuffer + stream->numInputSamples * stream->numChannels, samples,
        numSamples * sizeof(short) * stream->numChannels);
    stream->numInputSamples += numSamples;
    return 1;
}

/* Add the input samples to the input buffer. */
static int addUnsignedCharSamplesToInputBuffer(
    sonicStream stream,
    unsigned char *samples,
    int numSamples)
{
    short *buffer;
    int count = numSamples * stream->numChannels;

    if(numSamples == 0) {
        return 1;
    }
    if(!enlargeInputBufferIfNeeded(stream, numSamples)) {
        return 0;
    }
    buffer = stream->inputBuffer + stream->numInputSamples * stream->numChannels;
    while(count--) {
        *buffer++ = (*samples++ - 128) << 8;
    }
    stream->numInputSamples += numSamples;
    return 1;
}

/* Remove input samples that we have already processed. */
static void removeInputSamples(sonicStream stream, int position)
{
    int remainingSamples = stream->numInputSamples - position;

    if(remainingSamples > 0) {
        memmove(stream->inputBuffer, stream->inputBuffer + position * stream->numChannels,
            remainingSamples * sizeof(short) * stream->numChannels);
    }
    stream->numInputSamples = remainingSamples;
}

/* Copy from the output buffer to the output samples. */
static int copyToFloat(
    sonicStream stream,
    float *samples,
    int maxSamples)
{
    short *buffer = stream->outputBuffer;
    int numSamples = stream->numOutputSamples;
    int count;

    if(numSamples > maxSamples) {
        numSamples = maxSamples;
    }
    count = numSamples * stream->numChannels;
    while(count--) {
        *samples++ = (*buffer++) / 32767.0f;
    }
    if(numSamples > 0) {
        int remainingSamples = stream->numOutputSamples - numSamples;
        if(remainingSamples > 0) {
            memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
                remainingSamples * sizeof(short) * stream->numChannels);
        }
        stream->numOutputSamples = remainingSamples;
    }
    return numSamples;
}

/* Copy from the output buffer to the output samples. */
static int copyToShort(
    sonicStream stream,
    short *samples,
    int maxSamples)
{
    int numSamples = stream->numOutputSamples;

    if(numSamples > maxSamples) {
        numSamples = maxSamples;
    }
    if(numSamples > 0) {
        memcpy(samples, stream->outputBuffer, numSamples * sizeof(short) * stream->numChannels);
        int remainingSamples = stream->numOutputSamples - numSamples;
        if(remainingSamples > 0) {
            memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
                remainingSamples * sizeof(short) * stream->numChannels);
        }
        stream->numOutputSamples = remainingSamples;
    }
    return numSamples;
}

/* Copy from the output buffer to the output samples. */
static int copyToUnsignedChar(
    sonicStream stream,
    unsigned char *samples,
    int maxSamples)
{
    short *buffer = stream->outputBuffer;
    int numSamples = stream->numOutputSamples;
    int count;

    if(numSamples > maxSamples) {
        numSamples = maxSamples;
    }
    count = numSamples * stream->numChannels;
    while(count--) {
        *samples++ = (*buffer++ >> 8) + 128;
    }
    if(numSamples > 0) {
        int remainingSamples = stream->numOutputSamples - numSamples;
        if(remainingSamples > 0) {
            memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
                remainingSamples * sizeof(short) * stream->numChannels);
        }
        stream->numOutputSamples = remainingSamples;
    }
    return numSamples;
}

/* Downsample the data. */
static void downSampleInput(
    sonicStream stream,
    short *samples,
    int skip)
{
    int numSamples = stream->maxRequired / skip;
    int samplesPerValue = stream->numChannels * skip;
    int i, j;
    int value;
    short *downSamples = stream->downSampleBuffer;

    for(i = 0; i < numSamples; i++) {
        value = 0;
        for(j = 0; j < samplesPerValue; j++) {
            value += *samples++;
        }
        value /= samplesPerValue;
        *downSamples++ = value;
    }
}

/* Find the best frequency match in the range, and given a sample skip multiple.
   For now, just find the pitch of the first channel. */
static int findPitchPeriodInRange(
    short *samples,
    int minPeriod,
    int maxPeriod,
    int *retMinDiff,
    int *retMaxDiff)
{
    int period, bestPeriod = 0, worstPeriod = 255;
    short *s, *p;
    short sVal, pVal;
    unsigned long diff, minDiff = ULONG_MAX, maxDiff = 0;
    int i;

    for(period = minPeriod; period <= maxPeriod; period++) {
        diff = 0;
        s = samples;
        p = samples + period;
        for(i = 0; i < period; i++) {
            sVal = *s++;
            pVal = *p++;
            diff += sVal >= pVal ? (unsigned short)(sVal - pVal) : (unsigned short)(pVal - sVal);
        }
        /* Note that the highest number of samples we add into diff is less than 256, since we
           skip samples.  Thus, diff is a 24 bit number, and we can safely multiply by numSamples
           without overflow */ 
        if(diff * bestPeriod < minDiff * period) {
            minDiff = diff;
            bestPeriod = period;
        }
        if(diff * worstPeriod > maxDiff * period) {
            maxDiff = diff;
            worstPeriod = period;
        }
    }
    *retMinDiff = minDiff / bestPeriod;
    *retMaxDiff = maxDiff / worstPeriod;
    return bestPeriod;
}

/* At 48000Hz, there are 4000 samples for the pitch period range.  This helps to speed up
   the search by skiping samples. */
static int findPitchPeriod(
    sonicStream stream,
    short *samples,
    int preferNewPeriod)
{
    int minPeriod = stream->minPeriod;
    int maxPeriod = stream->maxPeriod;
    int skip = 1;
    int period;
    int retMinDiff, retMaxDiff;

    if(stream->sampleRate > SONIC_AMDF_FREQ && stream->quality == 0) {
        skip = stream->sampleRate / SONIC_AMDF_FREQ;
    }
    if(stream->numChannels == 1 && skip == 1) {
        period = findPitchPeriodInRange(samples, minPeriod, maxPeriod, &retMinDiff, &retMaxDiff);
    } else {
        downSampleInput(stream, samples, skip);
        period = findPitchPeriodInRange(stream->downSampleBuffer, minPeriod / skip,
            maxPeriod / skip, &retMinDiff, &retMaxDiff);
        if(skip != 1) {
            period *= skip;
            int minP = period - (skip << 2);
            int maxP = period + (skip << 2);
            if(minP < minPeriod) {
                minP = minPeriod;
            }
            if(maxP > maxPeriod) {
                maxP = maxPeriod;
            }
            if(stream->numChannels == 1) {
                period = findPitchPeriodInRange(samples, minP, maxP, &retMinDiff, &retMaxDiff);
            } else {
                downSampleInput(stream, samples, 1);
                period = findPitchPeriodInRange(stream->downSampleBuffer, minP, maxP,
                    &retMinDiff, &retMaxDiff);
            }
        }
    }
    if(preferNewPeriod) {
        return period;
    }
    if(stream->prevPeriod != 0) {
        if(retMinDiff > stream->minDiff * 3 || retMaxDiff * 2 <= stream->maxDiff * 3) {
            // Old period is too bad, or new period is too good.
            // No strict transition rules.
        } else {
            if(stream->prevMinDiff * 5 >= retMinDiff * 2 && period > stream->prevPeriod) {
                 // The previous period was better, and the new one is larger.  Prefer the smaller one.
                 // This helps to avoid sub-harmonics.
                 period = stream->prevPeriod;
                 retMinDiff = stream->prevMinDiff;
            }
        }
    }
    stream->prevMinDiff = retMinDiff;
    stream->prevPeriod = period;
    stream->minDiff = retMinDiff;
    stream->maxDiff = retMaxDiff;
    return period;
}

/* Overlap two sound segments, ramp the volume of one down, while ramping the other one up. */
static void overlapAdd(
    int numSamples,
    int numChannels,
    short *out,
    short *rampDown,
    short *rampUp)
{
    short *o, *u, *d;
    int i, t;

    for(i = 0; i < numChannels; i++) {
        o = out + i;
        u = rampUp + i;
        d = rampDown + i;
        for(t = 0; t < numSamples; t++) {
#ifdef SONIC_USE_SIN
            float ratio = sin(t * M_PI / (2 * numSamples));
            *o = *d * (1.0f - ratio) + *u * ratio;
#else
            *o = (*d * (numSamples - t) + *u * t) / numSamples;
#endif
            o += numChannels;
            d += numChannels;
            u += numChannels;
        }
    }
}

/* Overlap two sound segments, ramp the volume of one down, while ramping the other one up. */
static void overlapAddWithSeparation(
    int numSamples,
    int numChannels,
    int separation,
    short *out,
    short *rampDown,
    short *rampUp)
{
    short *o, *u, *d;
    int i, t;

    for(i = 0; i < numChannels; i++) {
        o = out + i;
        u = rampUp + i;
        d = rampDown + i;
        for(t = 0; t < numSamples + separation; t++) {
            if(t < separation) {
                *o = *d * (numSamples - t) / numSamples;
                d += numChannels;
            } else if(t < numSamples) {
                *o = (*d * (numSamples - t) + *u * (t - separation)) / numSamples;
                d += numChannels;
                u += numChannels;
            } else {
                *o = *u * (t - separation) / numSamples;
                u += numChannels;
            }
            o += numChannels;
        }
    }
}

/* Just move the new samples in the output buffer to the pitch buffer */
static int moveNewSamplesToPitchBuffer(
    sonicStream stream,
    int originalNumOutputSamples)
{
    int numSamples = stream->numOutputSamples - originalNumOutputSamples;

    if(stream->numPitchSamples + numSamples > stream->pitchBufferSize) {
        stream->pitchBufferSize += (stream->pitchBufferSize >> 1) + numSamples;
        stream->pitchBuffer = (short *)realloc(stream->pitchBuffer,
            stream->pitchBufferSize * stream->numChannels * sizeof(short));
    }
    memcpy(stream->pitchBuffer + stream->numPitchSamples * stream->numChannels,
        stream->outputBuffer + originalNumOutputSamples * stream->numChannels,
        numSamples * sizeof(short) * stream->numChannels);
    stream->numOutputSamples = originalNumOutputSamples;
    stream->numPitchSamples += numSamples;
    return 1;
}

/* Remove processed samples from the pitch buffer. */
static void removePitchSamples(sonicStream stream, int numSamples)
{
    if(numSamples == 0) {
        return;
    }
    memmove(stream->pitchBuffer, stream->pitchBuffer + numSamples * stream->numChannels,
        (stream->numPitchSamples - numSamples) * sizeof(short) * stream->numChannels);
    stream->numPitchSamples -= numSamples;
}

/* Change the pitch.  The latency this introduces could be reduced by looking at
   past samples to determine pitch, rather than future. */
static int adjustPitch(
    sonicStream stream,
    int originalNumOutputSamples)
{
    float pitch = stream->pitch;
    int numSamples = stream->numOutputSamples - originalNumOutputSamples;
    int newSamples, position = 0;
    int period, separation;

    if(numSamples == 0) {
        return 0;
    }
    if(!moveNewSamplesToPitchBuffer(stream, originalNumOutputSamples)) {
        return 0;
    }
    while(stream->numPitchSamples - position >= stream->maxRequired) {
        period = findPitchPeriod(stream, stream->pitchBuffer + position * stream->numChannels, 0);
        newSamples = period / pitch;
        if(!enlargeOutputBufferIfNeeded(stream, newSamples)) {
            return 0;
        }
        if(pitch >= 1.0f) {
            overlapAdd(newSamples, stream->numChannels,
                stream->outputBuffer + stream->numOutputSamples * stream->numChannels,
                stream->pitchBuffer + position * stream->numChannels,
                stream->pitchBuffer + (position + period - newSamples) * stream->numChannels);
        } else {
            separation = newSamples - period;
            overlapAddWithSeparation(period, stream->numChannels, separation,
                stream->outputBuffer + stream->numOutputSamples * stream->numChannels,
                stream->pitchBuffer + position * stream->numChannels,
                stream->pitchBuffer + (position + period) * stream->numChannels);
        }
        stream->numOutputSamples += newSamples;
        position += period;
    }
    removePitchSamples(stream, position);
    return 1;
}

/* Aproximate the sinc function. */
static int sincCoefficient(int i, int sincPos, int sincTableSize, int* sincTable) {
    int sincTablePos = sincPos * sincTableSize + i;
    return sincTable[sincTablePos];
}

/* Interpolate the new output sample. */
static short interpolate(
    sonicStream stream,
    short *in,
    int oldSampleRate,
    int newSampleRate)
{
    // Simple linear interpolation for speed
    short left = *in;
    short right = *(in + stream->numChannels);
    int position = stream->newRatePosition * oldSampleRate;
    int leftPos = stream->oldRatePosition * newSampleRate;
    int rightPos = (stream->oldRatePosition + 1) * newSampleRate;
    int ratio = rightPos - position - 1;
    int width = rightPos - leftPos;
    return (ratio * left + (width - ratio) * right) / width;
}

/* Change the rate. */
static int adjustRate(
    sonicStream stream,
    float rate,
    int originalNumOutputSamples)
{
    int newSampleRate = stream->sampleRate / rate;
    int oldSampleRate = stream->sampleRate;
    int numSamples = stream->numOutputSamples - originalNumOutputSamples;
    int position;
    int i;
    short *in, *out;
    int N = 10; 

    if(numSamples == 0) {
        return 1;
    }
    /*
      Since this code is a simplified version for easy copy-pasting, we will use
      linear interpolation for rate changing. It is faster and requires less code
      than the full sinc-windowed interpolation.
    */
    if(!enlargeOutputBufferIfNeeded(stream, numSamples / rate + 16)) {
        return 0;
    }
    in = stream->outputBuffer + originalNumOutputSamples * stream->numChannels;
    out = stream->outputBuffer + originalNumOutputSamples * stream->numChannels;
    
    // Quick and dirty linear interpolation re-sampling
    for(position = 0; position < numSamples - 1; position++) {
        while((stream->oldRatePosition + 1) * newSampleRate > stream->newRatePosition * oldSampleRate) {
             // interpolate
             int leftPos = stream->oldRatePosition * newSampleRate;
             int rightPos = (stream->oldRatePosition + 1) * newSampleRate;
             int targetPos = stream->newRatePosition * oldSampleRate;
             int rightWeight = targetPos - leftPos;
             int leftWeight = rightPos - targetPos;
             int sumWeights = rightPos - leftPos;
             
             for(i = 0; i < stream->numChannels; i++) {
                 int val = (in[i] * leftWeight + in[i + stream->numChannels] * rightWeight) / sumWeights;
                 *out++ = val;
             }
             stream->newRatePosition++;
        }
        stream->oldRatePosition++;
        in += stream->numChannels;
    }
    
    stream->numOutputSamples = (out - stream->outputBuffer) / stream->numChannels;
    return 1;
}

/* Skip over a pitch period. */
static int skipPitchPeriod(
    sonicStream stream,
    short *samples,
    float speed,
    int period)
{
    long newSamples;
    int numChannels = stream->numChannels;

    if(speed >= 2.0f) {
        newSamples = period / (speed - 1.0f);
    } else {
        newSamples = period;
        stream->remainingInputToCopy = period * (2.0f - speed) / (speed - 1.0f);
    }
    if(!enlargeOutputBufferIfNeeded(stream, newSamples)) {
        return 0;
    }
    overlapAdd(newSamples, numChannels, stream->outputBuffer + stream->numOutputSamples * numChannels,
        samples, samples + period * numChannels);
    stream->numOutputSamples += newSamples;
    return newSamples;
}

/* Insert a pitch period, and determine how much input to copy directly. */
static int insertPitchPeriod(
    sonicStream stream,
    short *samples,
    float speed,
    int period)
{
    long newSamples;
    short *out = stream->outputBuffer + stream->numOutputSamples * stream->numChannels;
    int numChannels = stream->numChannels;

    if(speed < 0.5f) {
        newSamples = period * speed / (1.0f - speed);
    } else {
        newSamples = period;
        stream->remainingInputToCopy = period * (2.0f * speed - 1.0f) / (1.0f - speed);
    }
    if(!enlargeOutputBufferIfNeeded(stream, period + newSamples)) {
        return 0;
    }
    memcpy(out, samples, period * sizeof(short) * numChannels);
    out = stream->outputBuffer + (stream->numOutputSamples + period) * numChannels;
    overlapAdd(newSamples, numChannels, out, samples + period * numChannels, samples);
    stream->numOutputSamples += period + newSamples;
    return newSamples;
}

/* Resample as many pitch periods as we have buffered on the input. */
static int changeSpeed(
    sonicStream stream,
    float speed)
{
    short *samples = stream->inputBuffer;
    int numSamples = stream->numInputSamples;
    int position = 0, period, newSamples;
    int maxRequired = stream->maxRequired;

    if(stream->numInputSamples < maxRequired) {
        return 1;
    }
    do {
        if(stream->remainingInputToCopy > 0) {
            newSamples = stream->remainingInputToCopy;
            if(newSamples > numSamples - position) {
                newSamples = numSamples - position;
            }
            if(!enlargeOutputBufferIfNeeded(stream, newSamples)) {
                return 0;
            }
            memcpy(stream->outputBuffer + stream->numOutputSamples * stream->numChannels,
                samples + position * stream->numChannels,
                newSamples * sizeof(short) * stream->numChannels);
            stream->numOutputSamples += newSamples;
            position += newSamples;
            stream->remainingInputToCopy -= newSamples;
        }
        if(stream->remainingInputToCopy == 0 && numSamples - position >= maxRequired) {
            period = findPitchPeriod(stream, samples + position * stream->numChannels, 0);
            if(speed > 1.0) {
                skipPitchPeriod(stream, samples + position * stream->numChannels, speed, period);
                position += period;
            } else {
                insertPitchPeriod(stream, samples + position * stream->numChannels, speed, period);
                position += period;
            }
        }
    } while(position + maxRequired <= numSamples);
    removeInputSamples(stream, position);
    return 1;
}

/* Process the data. */
static int processStreamInput(sonicStream stream)
{
    int originalNumOutputSamples = stream->numOutputSamples;
    float speed = stream->speed / stream->pitch;
    float rate = stream->rate;

    if(!stream->useChordPitch) {
        rate *= stream->pitch;
    }
    if(speed > 1.00001 || speed < 0.99999) {
        changeSpeed(stream, speed);
    } else {
        if(!enlargeOutputBufferIfNeeded(stream, stream->numInputSamples)) {
            return 0;
        }
        memcpy(stream->outputBuffer + stream->numOutputSamples * stream->numChannels, stream->inputBuffer,
            stream->numInputSamples * sizeof(short) * stream->numChannels);
        stream->numOutputSamples += stream->numInputSamples;
        removeInputSamples(stream, stream->numInputSamples);
    }
    if(stream->useChordPitch) {
        if(stream->pitch != 1.0f) {
            if(!adjustPitch(stream, originalNumOutputSamples)) {
                return 0;
            }
        }
    } else if(!adjustRate(stream, rate, originalNumOutputSamples)) {
        return 0;
    }
    if(stream->volume != 1.0f) {
        scaleSamples(stream->outputBuffer + originalNumOutputSamples * stream->numChannels,
            (stream->numOutputSamples - originalNumOutputSamples) * stream->numChannels, stream->volume);
    }
    return 1;
}

/* Write floating point data to the input buffer and process it. */
void sonicWriteFloatToStream(sonicStream stream, float *samples, int numSamples)
{
    addFloatSamplesToInputBuffer(stream, samples, numSamples);
    processStreamInput(stream);
}

/* Write 16-bit integer data to the input buffer and process it. */
void sonicWriteShortToStream(sonicStream stream, short *samples, int numSamples)
{
    addShortSamplesToInputBuffer(stream, samples, numSamples);
    processStreamInput(stream);
}

/* Write 8-bit unsigned integer data to the input buffer and process it. */
void sonicWriteUnsignedCharToStream(sonicStream stream, unsigned char *samples, int numSamples)
{
    addUnsignedCharSamplesToInputBuffer(stream, samples, numSamples);
    processStreamInput(stream);
}

/* Read floating point data from the output buffer. */
int sonicReadFloatFromStream(sonicStream stream, float *samples, int maxSamples)
{
    int numSamples = stream->numOutputSamples;
    int remainingSamples = 0;

    if(numSamples > maxSamples) {
        remainingSamples = numSamples - maxSamples;
        numSamples = maxSamples;
    }
    copyToFloat(stream, samples, numSamples);
    if(remainingSamples > 0) {
        stream->numOutputSamples = remainingSamples;
        memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
            remainingSamples * sizeof(short) * stream->numChannels);
    } else {
        stream->numOutputSamples = 0;
    }
    return numSamples;
}

/* Read 16-bit integer data from the output buffer. */
int sonicReadShortFromStream(sonicStream stream, short *samples, int maxSamples)
{
    int numSamples = stream->numOutputSamples;
    int remainingSamples = 0;

    if(numSamples > maxSamples) {
        remainingSamples = numSamples - maxSamples;
        numSamples = maxSamples;
    }
    copyToShort(stream, samples, numSamples);
    if(remainingSamples > 0) {
        stream->numOutputSamples = remainingSamples;
        memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
            remainingSamples * sizeof(short) * stream->numChannels);
    } else {
        stream->numOutputSamples = 0;
    }
    return numSamples;
}

/* Read 8-bit unsigned integer data from the output buffer. */
int sonicReadUnsignedCharFromStream(sonicStream stream, unsigned char *samples, int maxSamples)
{
    int numSamples = stream->numOutputSamples;
    int remainingSamples = 0;

    if(numSamples > maxSamples) {
        remainingSamples = numSamples - maxSamples;
        numSamples = maxSamples;
    }
    copyToUnsignedChar(stream, samples, numSamples);
    if(remainingSamples > 0) {
        stream->numOutputSamples = remainingSamples;
        memmove(stream->outputBuffer, stream->outputBuffer + numSamples * stream->numChannels,
            remainingSamples * sizeof(short) * stream->numChannels);
    } else {
        stream->numOutputSamples = 0;
    }
    return numSamples;
}

/* Force the sonic stream to generate output using whatever data it currently
   has.  No extra delay will be added to the output, but flushing the stream
   can cause discontinuities in the output at the pitch period boundaries. */
int sonicFlushStream(sonicStream stream)
{
    int remainingSamples = stream->numInputSamples;
    int maxRequired = stream->maxRequired;
    int speed = stream->speed / stream->pitch;
    int rate = stream->rate * stream->pitch;
    int expectedOutputSamples = stream->numOutputSamples + (int)((remainingSamples / speed + stream->numPitchSamples) / rate + 0.5f);

    /* Add enough silence to flush both input and pitch buffers. */
    int minPeriod = stream->minPeriod;
    int silenceSamples = maxRequired + 2 * minPeriod; 
    
    // Add silence
    if(!enlargeInputBufferIfNeeded(stream, silenceSamples)) return 0;
    memset(stream->inputBuffer + stream->numInputSamples * stream->numChannels, 0, silenceSamples * sizeof(short) * stream->numChannels);
    stream->numInputSamples += silenceSamples;

    // Process
    processStreamInput(stream);
    
    return 1;
}

/* Return the number of samples in the output buffer */
int sonicSamplesAvailable(sonicStream stream)
{
    return stream->numOutputSamples;
}
