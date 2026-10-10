/*
 * Host check for guest/opensles/zbopensles.c: the mixer's sample math and its callback pacing.
 * Builds against the NDK's SLES/jni headers but runs on the build machine (no GuestAudio server,
 * so the mixer paces itself on the clock):
 *
 *   I=$NDK/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/include; mkdir -p build/sles-inc
 *   ln -sfn $I/SLES build/sles-inc/SLES; ln -sfn $I/jni.h build/sles-inc/jni.h
 *   clang -O2 -Ibuild/sles-inc -o build/mixer_test tests/opensles/mixer_test.c -lm -lpthread && build/mixer_test
 */
#include "../../guest/opensles/zbopensles.c"

#include <assert.h>
#include <stdio.h>

static int16_t pcm[2][441];
static int callbacks;

static void refill(SLBufferQueueItf queue, void *context) {
    (void)context;
    callbacks++;
    (*queue)->Enqueue(queue, pcm[callbacks & 1], sizeof pcm[0]);
}

static SLObjectItf make_player(SLEngineItf engine, SLuint32 rate_mhz, SLuint32 channels) {
    SLDataLocator_BufferQueue locator = {SL_DATALOCATOR_BUFFERQUEUE, 2};
    SLDataFormat_PCM format = {SL_DATAFORMAT_PCM, channels, rate_mhz, 16, 16, 0, SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource source = {&locator, &format};
    SLObjectItf player;
    assert((*engine)->CreateAudioPlayer(engine, &player, &source, NULL, 0, NULL, NULL) == SL_RESULT_SUCCESS);
    return player;
}

int main(void) {
    SLObjectItf engine_object;
    SLEngineItf engine;
    assert(slCreateEngine(&engine_object, 0, NULL, 0, NULL, NULL) == SL_RESULT_SUCCESS);
    assert((*engine_object)->GetInterface(engine_object, SL_IID_ENGINE, &engine) == SL_RESULT_SUCCESS);

    /* Sample math, driven directly: a mono 44.1 kHz ramp at 0 dB lands unchanged on both sides. */
    SLObjectItf object = make_player(engine, SL_SAMPLINGRATE_44_1, 1);
    struct object *p = from_object(object);
    for (int i = 0; i < 441; i++) pcm[0][i] = (int16_t)(i * 50 - 11000);
    pthread_mutex_lock(&mixer_lock);
    p->queued[0].data = pcm[0];
    p->queued[0].size = sizeof pcm[0];
    p->count = 1;
    p->play_state = SL_PLAYSTATE_PLAYING;
    int32_t acc[OUT_FRAMES * 2] = {0};
    mix_player(p, acc, 441);
    assert(acc[0] == pcm[0][0] && acc[1] == pcm[0][0]);
    assert(acc[2 * 440] == pcm[0][440] && acc[2 * 440 + 1] == pcm[0][440]);
    assert(p->count == 1 && (p->position >> 32) == 441); /* used up, popped on the next frame */
    p->count = 0;
    p->position = 0;
    p->play_state = SL_PLAYSTATE_STOPPED;
    pthread_mutex_unlock(&mixer_lock);

    /* Pacing: two 10 ms buffers refilled from the callback run at about real time. */
    SLPlayItf play;
    SLBufferQueueItf queue;
    assert((*object)->GetInterface(object, SL_IID_PLAY, &play) == SL_RESULT_SUCCESS);
    assert((*object)->GetInterface(object, SL_IID_BUFFERQUEUE, &queue) == SL_RESULT_SUCCESS);
    (*queue)->RegisterCallback(queue, refill, NULL);
    (*queue)->Enqueue(queue, pcm[0], sizeof pcm[0]);
    (*queue)->Enqueue(queue, pcm[1], sizeof pcm[1]);
    (*play)->SetPlayState(play, SL_PLAYSTATE_PLAYING);
    usleep(500000);
    (*object)->Destroy(object); /* must not hang or call back afterwards */
    int seen = callbacks;
    usleep(50000);
    assert(callbacks == seen);
    printf("callbacks in 500 ms: %d (expect ~50)\n", seen);
    assert(seen > 35 && seen < 65);

    (*engine_object)->Destroy(engine_object);
    puts("mixer_test PASS");
    return 0;
}
