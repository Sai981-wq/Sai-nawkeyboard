#ifndef SONIC_H
#define SONIC_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sonicStreamStruct *sonicStream;

sonicStream sonicCreateStream(int sampleRate, int numChannels);
void sonicDestroyStream(sonicStream stream);
void sonicWriteShortToStream(sonicStream stream, short *samples, int numSamples);
int sonicReadShortFromStream(sonicStream stream, short *samples, int maxSamples);
void sonicFlushStream(sonicStream stream);
int sonicSamplesAvailable(sonicStream stream);
void sonicSetSpeed(sonicStream stream, float speed);
void sonicSetPitch(sonicStream stream, float pitch);
void sonicSetRate(sonicStream stream, float rate);
void sonicSetVolume(sonicStream stream, float volume);
void sonicSetQuality(sonicStream stream, int quality);

#ifdef __cplusplus
}
#endif
#endif
