package eu.amadeos.apps.musicstudio.service.play;

import eu.amadeos.apps.musicstudio.service.synth.Audio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays through the desktop's sound output (Java Sound, which reaches PipeWire through ALSA). One thread feeds
 * the line in small pieces, so that stopping, jumping and a new mix take effect within a tenth of a second.
 */
public final class SoundPlayer implements Player {
    private static final Logger LOG = LoggerFactory.getLogger(SoundPlayer.class);
    /** Frames per piece written to the line. */
    static final int CHUNK = 2048;
    /** About 0.1 s of buffer in the line. */
    static final int LINE_BUFFER_FRAMES = 4410;

    private final Object lock = new Object();
    private Audio audio;
    private int cursor;
    private boolean playing;
    private boolean loop;
    private boolean closed;
    private Runnable onEnd = () -> {
    };
    private SourceDataLine line;
    private int lineRate;
    private Thread thread;
    /** The error of the last attempt to open the sound output, if it failed. */
    private volatile String failure;

    @Override
    public void play(Audio newAudio, int from) {
        synchronized (lock) {
            audio = newAudio;
            cursor = Math.max(0, Math.min(from, newAudio.frames()));
            playing = true;
            flush();
            ensureThread();
            lock.notifyAll();
        }
    }

    @Override
    public void replace(Audio newAudio) {
        synchronized (lock) {
            int at = position();
            audio = newAudio;
            cursor = Math.min(at, newAudio.frames());
            flush();
            lock.notifyAll();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (playing) cursor = position();
            playing = false;
            flush();
            lock.notifyAll();
        }
    }

    @Override
    public void cue(Audio newAudio, int frame) {
        synchronized (lock) {
            audio = newAudio;
            cursor = Math.max(0, Math.min(frame, newAudio.frames()));
            flush();
            lock.notifyAll();
        }
    }

    @Override
    public boolean isPlaying() {
        synchronized (lock) {
            return playing;
        }
    }

    @Override
    public int position() {
        synchronized (lock) {
            if (!playing || line == null || audio == null) return cursor;
            int queued = (line.getBufferSize() - line.available()) / 4;
            int at = cursor - queued;
            if (at < 0) at = loop ? at + audio.frames() : 0;
            return Math.max(0, Math.min(at, audio.frames()));
        }
    }

    @Override
    public void setLoop(boolean newLoop) {
        synchronized (lock) {
            loop = newLoop;
        }
    }

    @Override
    public void setOnEnd(Runnable newOnEnd) {
        synchronized (lock) {
            onEnd = newOnEnd == null ? () -> {
            } : newOnEnd;
        }
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            playing = false;
            lock.notifyAll();
        }
    }

    private void flush() {
        if (line != null) line.flush();
    }

    private void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(this::run, "music-player");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        byte[] bytes = new byte[CHUNK * 4];
        while (true) {
            Runnable ended = null;
            int count;
            SourceDataLine out;
            synchronized (lock) {
                while (!closed && !playing) {
                    if (line != null) line.stop();
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed) {
                    closeLine();
                    return;
                }
                if (!openLine(audio.sampleRate())) {
                    playing = false;
                    ended = onEnd;
                } else if (cursor >= audio.frames()) {
                    if (loop && audio.frames() > 0) {
                        cursor = 0;
                    } else {
                        playing = false;
                        line.drain();
                        cursor = audio.frames();
                        ended = onEnd;
                    }
                }
                if (ended == null) {
                    count = Math.min(CHUNK, audio.frames() - cursor);
                    float[] l = audio.left();
                    float[] r = audio.right();
                    for (int i = 0; i < count; i++) {
                        short a = pcm(l[cursor + i]);
                        short b = pcm(r[cursor + i]);
                        bytes[4 * i] = (byte) a;
                        bytes[4 * i + 1] = (byte) (a >> 8);
                        bytes[4 * i + 2] = (byte) b;
                        bytes[4 * i + 3] = (byte) (b >> 8);
                    }
                    cursor += count;
                    line.start();
                } else {
                    count = 0;
                }
                out = line;
            }
            if (ended != null) {
                try {
                    ended.run();
                } catch (RuntimeException e) {
                    LOG.warn("Playback end handler failed", e);
                }
                continue;
            }
            // Blocks while the line is full; outside the lock, so that stop() and position() answer at once.
            out.write(bytes, 0, count * 4);
        }
    }

    private boolean openLine(int rate) {
        if (line != null && lineRate == rate) return true;
        closeLine();
        try {
            AudioFormat format = new AudioFormat(rate, 16, 2, true, false);
            SourceDataLine opened = AudioSystem.getSourceDataLine(format);
            opened.open(format, LINE_BUFFER_FRAMES * 4);
            line = opened;
            lineRate = rate;
            failure = null;
            return true;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            LOG.warn("No sound output", e);
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    private void closeLine() {
        if (line != null) {
            line.close();
            line = null;
        }
    }

    private static short pcm(float v) {
        return (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
    }
}
