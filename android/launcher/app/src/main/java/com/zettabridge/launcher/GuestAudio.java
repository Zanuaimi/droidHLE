package com.zettabridge.launcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Audio output for guest OpenSL ES players. The guest's libOpenSLES (guest/opensles) cannot
 * reach the host's audio stack, so each of its buffer-queue players connects to the abstract
 * socket "zb-audio-<pid>" of this process and streams PCM here, where it plays on an
 * AudioTrack. AudioTrack.write blocks while the track is full; that back-pressure is what paces
 * the guest's buffer-queue callbacks.
 *
 * Stream format (host byte order): a hello {'ZBA1', sample rate, channels, bits}, then messages
 * {type, length, payload}: PCM data, a play state (SL_PLAYSTATE_*), or a volume
 * {millibels, mute}.
 */
final class GuestAudio {
    private static final String TAG = "zb-audio";
    private static final int HELLO = 0x31414253;
    private static final int MSG_PCM = 1;
    private static final int MSG_STATE = 2;
    private static final int MSG_VOLUME = 3;
    private static final int SL_PLAYSTATE_STOPPED = 1;
    private static final int SL_PLAYSTATE_PAUSED = 2;
    private static final int SL_PLAYSTATE_PLAYING = 3;
    private static final int MAX_MESSAGE = 1 << 20;

    private static boolean started;

    private GuestAudio() {}

    /** Starts the server once per process; players that find no server stay silent. */
    static synchronized void start() {
        if (started) return;
        started = true;
        String name = "zb-audio-" + Process.myPid();
        final LocalServerSocket server;
        try {
            server = new LocalServerSocket(name);
        } catch (IOException e) {
            Log.w(TAG, "cannot listen on " + name + "; guest audio stays silent", e);
            return;
        }
        Thread accept = new Thread(() -> {
            while (true) {
                try {
                    LocalSocket socket = server.accept();
                    Thread player = new Thread(() -> serve(socket), "zb-audio-player");
                    player.setDaemon(true);
                    player.start();
                } catch (IOException e) {
                    Log.w(TAG, "accept failed; guest audio stops", e);
                    return;
                }
            }
        }, "zb-audio-accept");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "guest audio server on " + name);
    }

    private static void serve(LocalSocket socket) {
        AudioTrack track = null;
        try (LocalSocket s = socket) {
            InputStream in = s.getInputStream();
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
            readFully(in, header.array(), 16);
            if (header.getInt(0) != HELLO) throw new IOException("bad hello");
            int rate = header.getInt(4);
            int channels = header.getInt(8);
            int bits = header.getInt(12);
            track = open(rate, channels, bits);
            Log.i(TAG, "player " + rate + " Hz, " + channels + " ch, " + bits + " bit");

            byte[] data = new byte[8192];
            ByteBuffer message = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            while (true) {
                readFully(in, message.array(), 8);
                int type = message.getInt(0);
                int length = message.getInt(4);
                if (length < 0 || length > MAX_MESSAGE) throw new IOException("bad length " + length);
                if (data.length < length) data = new byte[length];
                readFully(in, data, length);
                ByteBuffer payload = ByteBuffer.wrap(data, 0, length).order(ByteOrder.nativeOrder());
                switch (type) {
                case MSG_PCM:
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    for (int off = 0; off < length; ) {
                        int n = track.write(data, off, length - off, AudioTrack.WRITE_BLOCKING);
                        if (n <= 0) throw new IOException("AudioTrack.write " + n);
                        off += n;
                    }
                    break;
                case MSG_STATE:
                    int state = payload.getInt(0);
                    if (state == SL_PLAYSTATE_PLAYING) {
                        track.play();
                    } else if (state == SL_PLAYSTATE_PAUSED) {
                        track.pause();
                    } else if (state == SL_PLAYSTATE_STOPPED) {
                        track.pause();
                        track.flush();
                    }
                    break;
                case MSG_VOLUME:
                    int millibels = payload.getInt(0);
                    boolean mute = payload.getInt(4) != 0;
                    float gain = mute ? 0f : (float) Math.min(1.0, Math.pow(10.0, millibels / 2000.0));
                    track.setVolume(gain);
                    break;
                default:
                    break;
                }
            }
        } catch (EOFException e) {
            // The guest destroyed its player.
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "guest audio stream ended", e);
        } finally {
            if (track != null) track.release();
        }
    }

    private static AudioTrack open(int rate, int channels, int bits) throws IOException {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
        int min = AudioTrack.getMinBufferSize(rate, mask, encoding);
        if (min <= 0) throw new IOException("unsupported format " + rate + "/" + channels + "/" + bits);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        for (int off = 0; off < length; ) {
            int n = in.read(buffer, off, length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }
}
