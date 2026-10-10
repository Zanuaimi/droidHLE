/*
 * OpenSL ES for arm32 guests: engines, output mixes and PCM buffer-queue players.
 *
 * The guest cannot reach the host's audio stack, but its syscalls are the host's. All players
 * of the process are mixed here, by one mixer thread, into a single 44.1 kHz stereo stream that
 * goes over a Unix socket to GuestAudio in the launcher process, which plays it on one
 * AudioTrack. Games commonly create a player per sound channel (dozens of them); mixing them
 * here, as AudioFlinger would, keeps that to one thread, one socket and one track.
 *
 * The mixer consumes queued buffers at the output rate, applies volume and playback rate, and
 * fires a player's buffer-queue callback the moment its head buffer is used up, then goes on
 * mixing what the callback queued: games keep only a buffer or two queued per player, so
 * waiting for the end of the mix period to call back leaves gaps. The socket buffer is
 * small and AudioTrack writes block, so the mixer, and with it the guest's callbacks, run at
 * the rate the device plays. Without a GuestAudio server (or once its socket fails) the mixer
 * paces itself on the clock and the output is dropped, so the guest keeps running either way.
 *
 * Wire format (host byte order): a hello of {'ZBA1', sample rate in Hz, channels, bits per
 * sample}, then messages {type, length, payload}; only PCM messages are sent.
 */
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define container_of(ptr, type, field) ((type *)((char *)(ptr) - offsetof(type, field)))

/* ---- interface IDs ---------------------------------------------------------------------- */

#define ZB_IID(name, a)                                                                          \
    static const struct SLInterfaceID_ zb_iid_##name = {a, 0x5a42, 0x4f53, 0x4c45, {0, 0, 0, 0, 0, a}}; \
    SL_API const SLInterfaceID SL_IID_##name = &zb_iid_##name;

ZB_IID(NULL, 0x01)
ZB_IID(OBJECT, 0x02)
ZB_IID(ENGINE, 0x03)
ZB_IID(PLAY, 0x04)
ZB_IID(BUFFERQUEUE, 0x05)
ZB_IID(VOLUME, 0x06)
ZB_IID(PLAYBACKRATE, 0x07)
ZB_IID(ANDROIDSIMPLEBUFFERQUEUE, 0x08)

/* ---- objects ---------------------------------------------------------------------------- */

enum kind { KIND_ENGINE, KIND_OUTPUT_MIX, KIND_PLAYER };

#define MAX_QUEUE 64

struct object {
    const struct SLObjectItf_ *object_itf;
    const struct SLEngineItf_ *engine_itf;
    const struct SLPlayItf_ *play_itf;
    const struct SLBufferQueueItf_ *queue_itf;
    const struct SLVolumeItf_ *volume_itf;
    const struct SLPlaybackRateItf_ *rate_itf;

    enum kind kind;
    SLuint32 state;

    /* Player only. Everything below is guarded by the mixer lock. */
    struct object *next; /* in the mixer's player list */
    SLuint32 play_state;
    SLuint32 sample_rate; /* Hz */
    SLuint32 channels;    /* 1 or 2 */
    SLuint32 bits;        /* 8 or 16 */
    SLuint32 queue_size;
    struct {
        const void *data;
        SLuint32 size;
    } queued[MAX_QUEUE]; /* oldest first */
    SLuint32 count;
    SLuint32 play_index;
    uint64_t position; /* in the head buffer, in frames, 32.32 fixed point */
    slBufferQueueCallback callback;
    void *callback_context;
    SLmillibel level;
    SLboolean mute;
    SLpermille rate;
};

static SLresult unsupported(void) { return SL_RESULT_FEATURE_UNSUPPORTED; }

/* ---- mixer ------------------------------------------------------------------------------ */

#define OUT_RATE 44100
#define OUT_FRAMES 512 /* per mix period: 11.6 ms */

enum { MSG_PCM = 1 };

static pthread_mutex_t mixer_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t mixer_wake = PTHREAD_COND_INITIALIZER;
static pthread_cond_t callbacks_done = PTHREAD_COND_INITIALIZER;
static struct object *players;
static int mixer_started;
static pthread_t mixer_thread;
static int mixing; /* a mix period is in progress; it releases the lock around guest callbacks */
static int out_sock = -1;

static int write_all(int fd, struct iovec *iov, int n) {
    while (n > 0) {
        ssize_t w = writev(fd, iov, n);
        if (w < 0) {
            if (errno == EINTR) continue;
            return 0;
        }
        while (n > 0 && (size_t)w >= iov->iov_len) {
            w -= (ssize_t)iov->iov_len;
            iov++;
            n--;
        }
        if (n > 0) {
            iov->iov_base = (char *)iov->iov_base + w;
            iov->iov_len -= (size_t)w;
        }
    }
    return 1;
}

static int connect_guest_audio(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    /* Abstract name, the one LocalServerSocket("zb-audio-<pid>") binds in the same process. */
    int n = snprintf(addr.sun_path + 1, sizeof addr.sun_path - 1, "zb-audio-%d", (int)getpid());
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    /* A small send buffer keeps the latency between the mixer and the speaker low. */
    int sndbuf = 4096;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof sndbuf);
    uint32_t hello[4] = {0x31414253 /* 'ZBA1' */, OUT_RATE, 2, 16};
    struct iovec iov = {hello, sizeof hello};
    if (connect(fd, (struct sockaddr *)&addr, len) != 0 || !write_all(fd, &iov, 1)) {
        close(fd);
        return -1;
    }
    return fd;
}

static int player_active(const struct object *p) {
    return p->play_state == SL_PLAYSTATE_PLAYING && p->count > 0;
}

static int16_t sample_at(const struct object *p, const void *data, SLuint32 frame, SLuint32 channel) {
    SLuint32 index = frame * p->channels + channel;
    if (p->bits == 8) return (int16_t)(((int)((const uint8_t *)data)[index] - 128) << 8);
    return ((const int16_t *)data)[index];
}

/*
 * Mixes p into acc for up to `frames` output frames, consuming its queue. A buffer it uses up
 * is popped and its callback run at once, with the lock released, so the guest can queue the
 * next one before this period needs it. Called with the mixer lock held.
 */
static void mix_player(struct object *p, int32_t *acc, int frames) {
    float gain = p->mute ? 0.0f : powf(10.0f, (float)p->level / 2000.0f);
    if (gain > 1.0f) gain = 1.0f;
    int32_t g = (int32_t)(gain * 256.0f);
    uint64_t step = ((uint64_t)p->sample_rate * (p->rate ? (uint64_t)p->rate : 1000) << 32) /
                    ((uint64_t)OUT_RATE * 1000);
    if (step == 0) step = 1;
    SLuint32 frame_bytes = p->channels * p->bits / 8;
    for (int i = 0; i < frames && p->count > 0 && p->play_state == SL_PLAYSTATE_PLAYING;) {
        SLuint32 total = p->queued[0].size / frame_bytes;
        SLuint32 frame = (SLuint32)(p->position >> 32);
        if (frame >= total) {
            /* Head buffer played out: hand it back to the guest. */
            p->position -= (uint64_t)total << 32;
            memmove(&p->queued[0], &p->queued[1], (p->count - 1) * sizeof(p->queued[0]));
            p->count--;
            p->play_index++;
            slBufferQueueCallback callback = p->callback;
            void *context = p->callback_context;
            if (callback) {
                /* p stays allocated: Destroy waits for the period to end. */
                pthread_mutex_unlock(&mixer_lock);
                callback(&p->queue_itf, context);
                pthread_mutex_lock(&mixer_lock);
            }
            continue;
        }
        const void *data = p->queued[0].data;
        int16_t left = sample_at(p, data, frame, 0);
        int16_t right = p->channels == 2 ? sample_at(p, data, frame, 1) : left;
        acc[2 * i] += left * g / 256;
        acc[2 * i + 1] += right * g / 256;
        p->position += step;
        i++;
    }
}

static void *mixer_main(void *arg) {
    (void)arg;
    static int32_t acc[OUT_FRAMES * 2];
    static int16_t out[OUT_FRAMES * 2];
    struct timespec next;
    /* As AudioFlinger's own threads run (THREAD_PRIORITY_AUDIO); best effort. */
    setpriority(PRIO_PROCESS, 0, -16);
    clock_gettime(CLOCK_MONOTONIC, &next);

    pthread_mutex_lock(&mixer_lock);
    for (;;) {
        int any = 0;
        for (struct object *p = players; p; p = p->next) any |= player_active(p);
        if (!any) {
            pthread_cond_wait(&mixer_wake, &mixer_lock);
            clock_gettime(CLOCK_MONOTONIC, &next);
            continue;
        }

        memset(acc, 0, sizeof acc);
        mixing = 1;
        for (struct object *p = players; p; p = p->next) {
            if (p->play_state == SL_PLAYSTATE_PLAYING) mix_player(p, acc, OUT_FRAMES);
        }
        for (int i = 0; i < OUT_FRAMES * 2; i++) {
            int32_t v = acc[i];
            out[i] = (int16_t)(v > 32767 ? 32767 : v < -32768 ? -32768 : v);
        }

        int sock = out_sock;
        pthread_mutex_unlock(&mixer_lock);

        int sent = 0;
        if (sock >= 0) {
            uint32_t header[2] = {MSG_PCM, sizeof out};
            struct iovec iov[2] = {{header, sizeof header}, {out, sizeof out}};
            sent = write_all(sock, iov, 2); /* blocks while the AudioTrack is full */
        }
        if (!sent) {
            /* No host audio: keep the guest's pace on the clock. */
            next.tv_nsec += (long)((long long)OUT_FRAMES * 1000000000LL / OUT_RATE);
            if (next.tv_nsec >= 1000000000L) {
                next.tv_sec++;
                next.tv_nsec -= 1000000000L;
            }
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        }

        pthread_mutex_lock(&mixer_lock);
        mixing = 0;
        pthread_cond_broadcast(&callbacks_done);
        if (sock >= 0 && !sent && out_sock == sock) {
            close(out_sock);
            out_sock = -1;
        }
    }
    return NULL;
}

/* Adds a player to the mix, starting the mixer (and its host stream) with the first one. */
static void mixer_add(struct object *p) {
    pthread_mutex_lock(&mixer_lock);
    if (!mixer_started) {
        out_sock = connect_guest_audio();
        mixer_started = pthread_create(&mixer_thread, NULL, mixer_main, NULL) == 0;
    }
    p->next = players;
    players = p;
    pthread_mutex_unlock(&mixer_lock);
}

/* Takes a player out of the mix; on return the mixer no longer uses it or calls it back. */
static void mixer_remove(struct object *p) {
    pthread_mutex_lock(&mixer_lock);
    for (struct object **link = &players; *link; link = &(*link)->next) {
        if (*link == p) {
            *link = p->next;
            break;
        }
    }
    /* The mix period in progress may still use it; from inside a callback there is nothing to wait for. */
    if (!(mixer_started && pthread_equal(mixer_thread, pthread_self())))
        while (mixing) pthread_cond_wait(&callbacks_done, &mixer_lock);
    pthread_mutex_unlock(&mixer_lock);
}

/* ---- SLObjectItf ------------------------------------------------------------------------ */

static struct object *from_object(SLObjectItf self) { return container_of(self, struct object, object_itf); }

static SLresult object_realize(SLObjectItf self, SLboolean async) {
    (void)async;
    from_object(self)->state = SL_OBJECT_STATE_REALIZED;
    return SL_RESULT_SUCCESS;
}

static SLresult object_resume(SLObjectItf self, SLboolean async) {
    (void)self;
    (void)async;
    return SL_RESULT_SUCCESS;
}

static SLresult object_get_state(SLObjectItf self, SLuint32 *state) {
    if (!state) return SL_RESULT_PARAMETER_INVALID;
    *state = from_object(self)->state;
    return SL_RESULT_SUCCESS;
}

static SLresult object_get_interface(SLObjectItf self, const SLInterfaceID iid, void *out) {
    struct object *o = from_object(self);
    const void *itf = NULL;
    if (!out) return SL_RESULT_PARAMETER_INVALID;
    if (iid == SL_IID_OBJECT) itf = &o->object_itf;
    else if (o->kind == KIND_ENGINE && iid == SL_IID_ENGINE) itf = &o->engine_itf;
    else if (o->kind == KIND_PLAYER && iid == SL_IID_PLAY) itf = &o->play_itf;
    else if (o->kind == KIND_PLAYER && (iid == SL_IID_BUFFERQUEUE || iid == SL_IID_ANDROIDSIMPLEBUFFERQUEUE))
        itf = &o->queue_itf;
    else if (o->kind == KIND_PLAYER && iid == SL_IID_VOLUME) itf = &o->volume_itf;
    else if (o->kind == KIND_PLAYER && iid == SL_IID_PLAYBACKRATE) itf = &o->rate_itf;
    *(const void **)out = itf;
    return itf ? SL_RESULT_SUCCESS : SL_RESULT_FEATURE_UNSUPPORTED;
}

static SLresult object_register_callback(SLObjectItf self, slObjectCallback callback, void *context) {
    (void)self;
    (void)callback;
    (void)context;
    return SL_RESULT_SUCCESS;
}

static void object_abort(SLObjectItf self) { (void)self; }

static void object_destroy(SLObjectItf self) {
    struct object *o = from_object(self);
    if (o->kind == KIND_PLAYER) mixer_remove(o);
    free(o);
}

static SLresult object_set_priority(SLObjectItf self, SLint32 priority, SLboolean preemptable) {
    (void)self;
    (void)priority;
    (void)preemptable;
    return SL_RESULT_SUCCESS;
}

static SLresult object_get_priority(SLObjectItf self, SLint32 *priority, SLboolean *preemptable) {
    (void)self;
    if (priority) *priority = 0;
    if (preemptable) *preemptable = SL_BOOLEAN_FALSE;
    return SL_RESULT_SUCCESS;
}

static SLresult object_set_loss_of_control(SLObjectItf self, SLint16 n, SLInterfaceID *ids, SLboolean enabled) {
    (void)self;
    (void)n;
    (void)ids;
    (void)enabled;
    return SL_RESULT_SUCCESS;
}

static const struct SLObjectItf_ object_vtable = {
    object_realize, object_resume, object_get_state, object_get_interface, object_register_callback,
    object_abort, object_destroy, object_set_priority, object_get_priority, object_set_loss_of_control,
};

/* ---- SLPlayItf -------------------------------------------------------------------------- */

static struct object *from_play(SLPlayItf self) { return container_of(self, struct object, play_itf); }

static SLresult play_set_state(SLPlayItf self, SLuint32 state) {
    struct object *o = from_play(self);
    pthread_mutex_lock(&mixer_lock);
    o->play_state = state;
    if (state == SL_PLAYSTATE_STOPPED) {
        o->count = 0;
        o->position = 0;
    }
    pthread_cond_broadcast(&mixer_wake);
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult play_get_state(SLPlayItf self, SLuint32 *state) {
    if (!state) return SL_RESULT_PARAMETER_INVALID;
    pthread_mutex_lock(&mixer_lock);
    *state = from_play(self)->play_state;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult play_get_ms(SLPlayItf self, SLmillisecond *ms) {
    (void)self;
    if (!ms) return SL_RESULT_PARAMETER_INVALID;
    *ms = 0;
    return SL_RESULT_SUCCESS;
}

static SLresult play_register_callback(SLPlayItf self, slPlayCallback callback, void *context) {
    (void)self;
    (void)callback;
    (void)context;
    return SL_RESULT_SUCCESS;
}

static SLresult play_set_u32(SLPlayItf self, SLuint32 value) {
    (void)self;
    (void)value;
    return SL_RESULT_SUCCESS;
}

static SLresult play_get_u32(SLPlayItf self, SLuint32 *value) {
    (void)self;
    if (!value) return SL_RESULT_PARAMETER_INVALID;
    *value = 0;
    return SL_RESULT_SUCCESS;
}

static SLresult play_clear_marker(SLPlayItf self) {
    (void)self;
    return SL_RESULT_SUCCESS;
}

static const struct SLPlayItf_ play_vtable = {
    play_set_state, play_get_state, play_get_ms, play_get_ms, play_register_callback, play_set_u32,
    play_get_u32, play_set_u32, play_clear_marker, play_get_ms, play_set_u32, play_get_ms,
};

/* ---- SLBufferQueueItf ------------------------------------------------------------------- */

static struct object *from_queue(SLBufferQueueItf self) { return container_of(self, struct object, queue_itf); }

static SLresult queue_enqueue(SLBufferQueueItf self, const void *buffer, SLuint32 size) {
    struct object *o = from_queue(self);
    SLresult result = SL_RESULT_SUCCESS;
    if (!buffer || !size) return SL_RESULT_PARAMETER_INVALID;
    pthread_mutex_lock(&mixer_lock);
    if (o->count >= o->queue_size) {
        result = SL_RESULT_BUFFER_INSUFFICIENT;
    } else {
        o->queued[o->count].data = buffer;
        o->queued[o->count].size = size;
        o->count++;
        pthread_cond_broadcast(&mixer_wake);
    }
    pthread_mutex_unlock(&mixer_lock);
    return result;
}

static SLresult queue_clear(SLBufferQueueItf self) {
    struct object *o = from_queue(self);
    pthread_mutex_lock(&mixer_lock);
    o->count = 0;
    o->position = 0;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult queue_get_state(SLBufferQueueItf self, SLBufferQueueState *state) {
    struct object *o = from_queue(self);
    if (!state) return SL_RESULT_PARAMETER_INVALID;
    pthread_mutex_lock(&mixer_lock);
    state->count = o->count;
    state->playIndex = o->play_index;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult queue_register_callback(SLBufferQueueItf self, slBufferQueueCallback callback, void *context) {
    struct object *o = from_queue(self);
    pthread_mutex_lock(&mixer_lock);
    o->callback = callback;
    o->callback_context = context;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static const struct SLBufferQueueItf_ queue_vtable = {
    queue_enqueue, queue_clear, queue_get_state, queue_register_callback,
};

/* ---- SLVolumeItf ------------------------------------------------------------------------ */

static struct object *from_volume(SLVolumeItf self) { return container_of(self, struct object, volume_itf); }

static SLresult volume_set_level(SLVolumeItf self, SLmillibel level) {
    pthread_mutex_lock(&mixer_lock);
    from_volume(self)->level = level;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult volume_get_level(SLVolumeItf self, SLmillibel *level) {
    if (!level) return SL_RESULT_PARAMETER_INVALID;
    *level = from_volume(self)->level;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_get_max(SLVolumeItf self, SLmillibel *level) {
    (void)self;
    if (!level) return SL_RESULT_PARAMETER_INVALID;
    *level = 0;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_set_mute(SLVolumeItf self, SLboolean mute) {
    pthread_mutex_lock(&mixer_lock);
    from_volume(self)->mute = mute;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult volume_get_mute(SLVolumeItf self, SLboolean *mute) {
    if (!mute) return SL_RESULT_PARAMETER_INVALID;
    *mute = from_volume(self)->mute;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_set_bool(SLVolumeItf self, SLboolean value) {
    (void)self;
    (void)value;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_get_bool(SLVolumeItf self, SLboolean *value) {
    (void)self;
    if (!value) return SL_RESULT_PARAMETER_INVALID;
    *value = SL_BOOLEAN_FALSE;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_set_position(SLVolumeItf self, SLpermille position) {
    (void)self;
    (void)position;
    return SL_RESULT_SUCCESS;
}

static SLresult volume_get_position(SLVolumeItf self, SLpermille *position) {
    (void)self;
    if (!position) return SL_RESULT_PARAMETER_INVALID;
    *position = 0;
    return SL_RESULT_SUCCESS;
}

static const struct SLVolumeItf_ volume_vtable = {
    volume_set_level, volume_get_level, volume_get_max, volume_set_mute, volume_get_mute,
    volume_set_bool, volume_get_bool, volume_set_position, volume_get_position,
};

/* ---- SLPlaybackRateItf ------------------------------------------------------------------ */

static struct object *from_rate(SLPlaybackRateItf self) { return container_of(self, struct object, rate_itf); }

static SLresult rate_set(SLPlaybackRateItf self, SLpermille rate) {
    pthread_mutex_lock(&mixer_lock);
    from_rate(self)->rate = rate > 0 ? rate : 1000;
    pthread_mutex_unlock(&mixer_lock);
    return SL_RESULT_SUCCESS;
}

static SLresult rate_get(SLPlaybackRateItf self, SLpermille *rate) {
    if (!rate) return SL_RESULT_PARAMETER_INVALID;
    *rate = from_rate(self)->rate;
    return SL_RESULT_SUCCESS;
}

static SLresult rate_set_constraints(SLPlaybackRateItf self, SLuint32 constraints) {
    (void)self;
    (void)constraints;
    return SL_RESULT_SUCCESS;
}

static SLresult rate_get_properties(SLPlaybackRateItf self, SLuint32 *properties) {
    (void)self;
    if (!properties) return SL_RESULT_PARAMETER_INVALID;
    *properties = SL_RATEPROP_NOPITCHCORAUDIO;
    return SL_RESULT_SUCCESS;
}

static SLresult rate_get_capabilities(SLPlaybackRateItf self, SLpermille rate, SLuint32 *capabilities) {
    (void)self;
    (void)rate;
    if (!capabilities) return SL_RESULT_PARAMETER_INVALID;
    *capabilities = SL_RATEPROP_NOPITCHCORAUDIO;
    return SL_RESULT_SUCCESS;
}

static SLresult rate_get_range(SLPlaybackRateItf self, SLuint8 index, SLpermille *min, SLpermille *max,
                               SLpermille *step, SLuint32 *capabilities) {
    (void)self;
    if (index != 0) return SL_RESULT_PARAMETER_INVALID;
    if (min) *min = 500;
    if (max) *max = 2000;
    if (step) *step = 1;
    if (capabilities) *capabilities = SL_RATEPROP_NOPITCHCORAUDIO;
    return SL_RESULT_SUCCESS;
}

static const struct SLPlaybackRateItf_ rate_vtable = {
    rate_set, rate_get, rate_set_constraints, rate_get_properties, rate_get_capabilities, rate_get_range,
};

/* ---- SLEngineItf ------------------------------------------------------------------------ */

static struct object *new_object(enum kind kind) {
    struct object *o = calloc(1, sizeof(*o));
    if (!o) return NULL;
    o->object_itf = &object_vtable;
    o->kind = kind;
    o->state = SL_OBJECT_STATE_UNREALIZED;
    return o;
}

static SLresult engine_create_output_mix(SLEngineItf self, SLObjectItf *out, SLuint32 n,
                                         const SLInterfaceID *ids, const SLboolean *required) {
    (void)self;
    (void)n;
    (void)ids;
    (void)required;
    if (!out) return SL_RESULT_PARAMETER_INVALID;
    struct object *o = new_object(KIND_OUTPUT_MIX);
    if (!o) return SL_RESULT_MEMORY_FAILURE;
    *out = &o->object_itf;
    return SL_RESULT_SUCCESS;
}

static SLresult engine_create_audio_player(SLEngineItf self, SLObjectItf *out, SLDataSource *source,
                                           SLDataSink *sink, SLuint32 n, const SLInterfaceID *ids,
                                           const SLboolean *required) {
    (void)self;
    (void)sink;
    (void)n;
    (void)ids;
    (void)required;
    if (!out || !source || !source->pLocator || !source->pFormat) return SL_RESULT_PARAMETER_INVALID;
    SLuint32 locator = *(const SLuint32 *)source->pLocator;
    if (locator != SL_DATALOCATOR_BUFFERQUEUE && locator != SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE)
        return SL_RESULT_CONTENT_UNSUPPORTED;
    const SLDataFormat_PCM *pcm = source->pFormat;
    if (pcm->formatType != SL_DATAFORMAT_PCM) return SL_RESULT_CONTENT_UNSUPPORTED;
    SLuint32 hz = pcm->samplesPerSec / 1000; /* SL_SAMPLINGRATE_* is in milliHertz */
    if (hz == 0 || (pcm->numChannels != 1 && pcm->numChannels != 2) ||
        (pcm->bitsPerSample != 8 && pcm->bitsPerSample != 16))
        return SL_RESULT_CONTENT_UNSUPPORTED;

    struct object *o = new_object(KIND_PLAYER);
    if (!o) return SL_RESULT_MEMORY_FAILURE;
    o->play_itf = &play_vtable;
    o->queue_itf = &queue_vtable;
    o->volume_itf = &volume_vtable;
    o->rate_itf = &rate_vtable;
    o->play_state = SL_PLAYSTATE_STOPPED;
    o->rate = 1000;
    o->sample_rate = hz;
    o->channels = pcm->numChannels;
    o->bits = pcm->bitsPerSample;
    o->queue_size = ((const SLDataLocator_BufferQueue *)source->pLocator)->numBuffers;
    if (o->queue_size == 0 || o->queue_size > MAX_QUEUE) o->queue_size = MAX_QUEUE;
    mixer_add(o);
    *out = &o->object_itf;
    return SL_RESULT_SUCCESS;
}

static SLresult engine_query_num(SLEngineItf self, SLuint32 *n) {
    (void)self;
    if (!n) return SL_RESULT_PARAMETER_INVALID;
    *n = 0;
    return SL_RESULT_SUCCESS;
}

static SLresult engine_is_extension_supported(SLEngineItf self, const SLchar *name, SLboolean *supported) {
    (void)self;
    (void)name;
    if (!supported) return SL_RESULT_PARAMETER_INVALID;
    *supported = SL_BOOLEAN_FALSE;
    return SL_RESULT_SUCCESS;
}

typedef SLresult (*any_fn)();

static const struct SLEngineItf_ engine_vtable = {
    .CreateLEDDevice = (void *)(any_fn)unsupported,
    .CreateVibraDevice = (void *)(any_fn)unsupported,
    .CreateAudioPlayer = engine_create_audio_player,
    .CreateAudioRecorder = (void *)(any_fn)unsupported,
    .CreateMidiPlayer = (void *)(any_fn)unsupported,
    .CreateListener = (void *)(any_fn)unsupported,
    .Create3DGroup = (void *)(any_fn)unsupported,
    .CreateOutputMix = engine_create_output_mix,
    .CreateMetadataExtractor = (void *)(any_fn)unsupported,
    .CreateExtensionObject = (void *)(any_fn)unsupported,
    .QueryNumSupportedInterfaces = (void *)(any_fn)unsupported,
    .QuerySupportedInterfaces = (void *)(any_fn)unsupported,
    .QueryNumSupportedExtensions = engine_query_num,
    .QuerySupportedExtension = (void *)(any_fn)unsupported,
    .IsExtensionSupported = engine_is_extension_supported,
};

/* ---- entry points ----------------------------------------------------------------------- */

SL_API SLresult SLAPIENTRY slCreateEngine(SLObjectItf *out, SLuint32 n_options, const SLEngineOption *options,
                                          SLuint32 n_interfaces, const SLInterfaceID *ids,
                                          const SLboolean *required) {
    (void)n_options;
    (void)options;
    (void)n_interfaces;
    (void)ids;
    (void)required;
    if (!out) return SL_RESULT_PARAMETER_INVALID;
    struct object *o = new_object(KIND_ENGINE);
    if (!o) return SL_RESULT_MEMORY_FAILURE;
    o->engine_itf = &engine_vtable;
    *out = &o->object_itf;
    return SL_RESULT_SUCCESS;
}

SL_API SLresult SLAPIENTRY slQueryNumSupportedEngineInterfaces(SLuint32 *n) {
    if (!n) return SL_RESULT_PARAMETER_INVALID;
    *n = 1;
    return SL_RESULT_SUCCESS;
}

SL_API SLresult SLAPIENTRY slQuerySupportedEngineInterfaces(SLuint32 index, SLInterfaceID *id) {
    if (!id || index != 0) return SL_RESULT_PARAMETER_INVALID;
    *id = SL_IID_ENGINE;
    return SL_RESULT_SUCCESS;
}
