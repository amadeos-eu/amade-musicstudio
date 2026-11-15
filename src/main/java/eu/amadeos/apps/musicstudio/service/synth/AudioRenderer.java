package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.service.compose.Drum;
import eu.amadeos.apps.musicstudio.service.compose.Note;
import eu.amadeos.apps.musicstudio.service.compose.Song;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Plays a song with the synthesizer: each of the channels is rendered on its own, filtered (the drums by the
 * hardness, the others but the bass by the brightness), moved by its voice's sweep and tremolo and placed left or right; then {@link #mix} adds the
 * parts at their volumes, the hall and the record crackle.
 */
public final class AudioRenderer {
    public static final int SAMPLE_RATE = 44_100;
    /** Seconds after the last note for the hall and long notes to fade. */
    static final double TAIL = 3.0;
    /** The highest level after mixing; the rest is headroom. */
    static final double LEVEL = 0.89;

    static final double[] CHANNEL_GAIN = {0.9, 0.6, 0.85, 0.75, 0.3, 0.3, 0.3, 0.42, 0.26, 0.26, 0.32};
    static final double[] CHANNEL_PAN = {0, 0.08, 0.3, 0, -0.4, 0, 0.4, 0.12, -0.6, 0.6, -0.22};
    static final double[] CHANNEL_REVERB = {0.03, 0.18, 0.12, 0.0, 0.5, 0.5, 0.5, 0.4, 0.6, 0.6, 0.45};

    private final Synth synth = new Synth();

    public Synth synth() {
        return synth;
    }

    /** How far a movement is down at a frame, 0 (open, full) to 1: a wave, or one course over the piece. */
    private static double low(int rate, int frame, double waveFrames, double pieceFrames) {
        if (!Voice.wholePiece(rate)) return 0.5 - 0.5 * Math.cos(2 * Math.PI * frame / waveFrames);
        double done = 0.5 - 0.5 * Math.cos(Math.PI * Math.min(1, frame / pieceFrames));
        return rate == Voice.RISING ? 1 - done : done;
    }

    /** The stereo position of a channel, -1 (left) to 1 (right), for the MOD export too. */
    public static double pan(int channel) {
        return CHANNEL_PAN[channel];
    }

    /** How loud a note's instrument is set against the others, so that every choice sits well in the mix. */
    public static double instrumentGain(Recipe r, int channel, Instrument instrument, Drum drum) {
        double g;
        if (drum != null) {
            g = switch (drum) {
                case TOM -> 0.4;
                case KICK -> switch (r.kit()) {
                    case ELECTRONIC -> 0.4;
                    case CHIP -> 0.5;
                    case LOFI -> 0.75;
                    case ROCK -> 0.45;
                    default -> 0.9;
                };
                default -> 1;
            };
            return g;
        }
        g = switch (instrument) {
            case SUB_BASS -> 0.4;
            case SYNTH_BASS -> 0.6;
            case CHIP_TRIANGLE -> 0.7;
            case GUITAR, HARP -> 1.8;
            case ELECTRIC_GUITAR -> 2.6;
            case DISTORTED_GUITAR -> 1.9;
            case BELLS, MARIMBA -> 1.1;
            default -> 1;
        };
        if (channel == Song.BASS && (instrument == Instrument.STRINGS || instrument == Instrument.WARM_PAD
                || instrument == Instrument.ORGAN)) {
            g *= 0.5;
        }
        if (channel == Song.BASS && instrument == Instrument.PIANO) g *= 0.6;
        if (instrument == Instrument.CHIP_SQUARE && channel >= Song.CHORD && channel < Song.LEAD) g *= 0.7;
        if (channel == Song.LEAD && (instrument == Instrument.CHIP_SQUARE || instrument == Instrument.FLUTE)) g *= 1.35;
        return g;
    }

    /** The gain of a channel in the mix, the same for every note on it. */
    public static double channelGain(int channel) {
        return CHANNEL_GAIN[channel];
    }

    /**
     * Renders the song, the channels side by side on {@code workers}.
     *
     * @throws CancellationException as soon as {@code cancelled} says so
     */
    public Stems render(Song song, Executor workers, BooleanSupplier cancelled) {
        Recipe r = song.recipe();
        int frames = (int) ((song.seconds() + TAIL) * SAMPLE_RATE);
        List<CompletableFuture<float[]>> jobs = new ArrayList<>();
        for (int ch = 0; ch < Song.CHANNELS; ch++) {
            int channel = ch;
            jobs.add(CompletableFuture.supplyAsync(() -> channel(song, channel, frames, cancelled), workers));
        }
        float[][] channels = new float[Song.CHANNELS][];
        try {
            for (int ch = 0; ch < Song.CHANNELS; ch++) channels[ch] = jobs.get(ch).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
        if (cancelled.getAsBoolean()) throw new CancellationException();
        Map<Part, float[]> left = new EnumMap<>(Part.class);
        Map<Part, float[]> right = new EnumMap<>(Part.class);
        Map<Part, float[]> send = new EnumMap<>(Part.class);
        for (Part p : Part.values()) {
            left.put(p, new float[frames]);
            right.put(p, new float[frames]);
            send.put(p, new float[frames]);
        }
        for (int ch = 0; ch < Song.CHANNELS; ch++) {
            Part p = Song.part(ch);
            double g = CHANNEL_GAIN[ch];
            double angle = (CHANNEL_PAN[ch] + 1) * Math.PI / 4;
            float gl = (float) (g * Math.cos(angle) * Math.sqrt(2));
            float gr = (float) (g * Math.sin(angle) * Math.sqrt(2));
            float gs = (float) (CHANNEL_REVERB[ch] * g);
            float[] in = channels[ch];
            float[] l = left.get(p);
            float[] rr = right.get(p);
            float[] s = send.get(p);
            for (int i = 0; i < frames; i++) {
                float v = in[i];
                l[i] += v * gl;
                rr[i] += v * gr;
                s[i] += v * gs;
            }
        }
        // The level: the dry mix of all parts at their usual volume, at most LEVEL.
        double peak = 1e-9;
        for (int i = 0; i < frames; i++) {
            float sl = 0;
            float sr = 0;
            for (Part p : Part.values()) {
                sl += left.get(p)[i];
                sr += right.get(p)[i];
            }
            peak = Math.max(peak, Math.max(Math.abs(sl), Math.abs(sr)));
        }
        return new Stems(song, left, right, send, frames, SAMPLE_RATE, LEVEL / peak * 0.85);
    }

    private float[] channel(Song song, int channel, int frames, BooleanSupplier cancelled) {
        Recipe r = song.recipe();
        float[] out = new float[frames];
        double stepSeconds = song.stepSeconds();
        Part part = Song.part(channel);
        Tone tone = part.tonal() ? Tone.of(r.voice(part)) : Tone.NEUTRAL;
        int index = 0;
        for (Note n : song.notes()) {
            if (n.channel() != channel) continue;
            index++; // counted per channel, so that the noise of a note does not depend on the other parts
            if (cancelled.getAsBoolean()) throw new CancellationException();
            int start = (int) Math.round(song.heardAt(n) * stepSeconds * SAMPLE_RATE);
            if (start >= frames) continue;
            long noiseSeed = r.seed(part) * 7919 + index;
            float[] sound = n.isDrum()
                    ? synth.drum(n.drum(), r.kit(), r.hardness() / 100.0, n.velocity(), SAMPLE_RATE, noiseSeed)
                    : synth.note(n.instrument(), n.pitch(), n.velocity(), Math.max(0.03, n.length() * stepSeconds - 0.01),
                    tone, SAMPLE_RATE, noiseSeed);
            float g = (float) instrumentGain(r, channel, n.instrument(), n.drum());
            int len = Math.min(sound.length, frames - start);
            for (int i = 0; i < len; i++) out[start + i] += sound[i] * g;
        }
        if (part == Part.DRUMS) {
            Dsp.lowPass(out, drumCutoff(r.hardness()), 0.707, SAMPLE_RATE);
        } else if (part != Part.BASS) {
            Dsp.lowPass(out, brightCutoff(r.brightness()), 0.6, SAMPLE_RATE);
        }
        if (part.tonal()) {
            move(out, r.voice(part), stepSeconds, song.seconds(),
                    part == Part.BASS ? 5000 : Math.min(12_000, brightCutoff(r.brightness())), SAMPLE_RATE);
        }
        return out;
    }

    /** The filter on melody, chords, pad and arpeggio: warm when low, open when high. */
    static double brightCutoff(int brightness) {
        return 900 * Math.pow(2, brightness / 100.0 * 4.2);
    }

    /**
     * The movements of a voice in time with the piece. The sweep is a low-pass filter that starts open at
     * {@code open} Hz, closes by up to four and a half octaves and opens again; the tremolo lets the volume
     * sink and rise again. Both start with the piece, so they fall on the same notes every time. Instead of
     * waves either can take one course over the {@code seconds} of the piece: rising, the filter starts closed
     * and is open at the end and the volume grows to full; falling, the other way round.
     */
    static void move(float[] x, Voice voice, double stepSeconds, double seconds, double open, int sampleRate) {
        if (voice.sweep() == 0 && voice.tremolo() == 0) return;
        double pieceFrames = Math.max(1, seconds * sampleRate);
        double sweepFrames = Voice.wholePiece(voice.sweepRate()) ? 0 : voice.sweepSteps() * stepSeconds * sampleRate;
        double tremoloFrames = Voice.wholePiece(voice.tremoloRate()) ? 0 : voice.tremoloSteps() * stepSeconds * sampleRate;
        double octaves = voice.sweep() / 100.0 * 4.5;
        double depth = voice.tremolo() / 100.0 * 0.9;
        Dsp.Filter filter = new Dsp.Filter();
        double target = 1 - depth * low(voice.tremoloRate(), 0, tremoloFrames, pieceFrames);
        double gain = target;
        for (int i = 0; i < x.length; i++) {
            if (i % 32 == 0) {
                double closed = low(voice.sweepRate(), i, sweepFrames, pieceFrames);
                filter.set(open * Math.pow(2, -octaves * closed), 1.1, sampleRate);
                target = 1 - depth * low(voice.tremoloRate(), i, tremoloFrames, pieceFrames);
            }
            gain += (target - gain) * 0.01;
            double v = voice.sweep() > 0 ? filter.process(x[i]) : x[i];
            x[i] = (float) (v * gain);
        }
    }

    /** The drums' filter: almost closed when dull, open when hard. */
    public static double drumCutoff(int hardness) {
        return 700 * Math.pow(2, hardness / 100.0 * 4.8);
    }

    /** All parts. */
    public Audio mix(Stems stems, Recipe r) {
        return mix(stems, r, EnumSet.allOf(Part.class));
    }

    /**
     * Mixes the parts that can be heard at the recipe's volumes, with hall and crackle. A loop is cut to the
     * length of its bars, and what sounds on past its end is laid over its start, so that it repeats without a
     * seam; any other piece fades out at its end.
     */
    public Audio mix(Stems stems, Recipe r, Set<Part> audible) {
        int n = stems.frames();
        float[] left = new float[n];
        float[] right = new float[n];
        float[] send = new float[n];
        for (Part p : audible) {
            float g = (float) (stems.gain() * r.volume(p) / (double) Voice.DEFAULT_VOLUME);
            float[] l = stems.left().get(p);
            float[] rr = stems.right().get(p);
            float[] s = stems.send().get(p);
            for (int i = 0; i < n; i++) {
                left[i] += l[i] * g;
                right[i] += rr[i] * g;
                send[i] += s[i] * g;
            }
        }
        Reverb.apply(send, left, right, r.reverb() / 100.0, stems.sampleRate());
        if (r.crackle() > 0) crackle(left, right, r.crackle() / 100.0, r.seed(), stems.sampleRate());
        Song song = stems.song();
        if (song.loop()) {
            int length = Math.min(n, (int) Math.round(song.seconds() * stems.sampleRate()));
            for (int i = length; i < n; i++) {
                left[(i - length) % length] += left[i];
                right[(i - length) % length] += right[i];
            }
            left = java.util.Arrays.copyOf(left, length);
            right = java.util.Arrays.copyOf(right, length);
        } else {
            int fade = Math.min(n, (int) (1.5 * stems.sampleRate()));
            for (int i = 0; i < fade; i++) {
                float m = (float) (i / (double) fade);
                left[n - 1 - i] *= m;
                right[n - 1 - i] *= m;
            }
        }
        limit(left);
        limit(right);
        return new Audio(left, right, stems.sampleRate());
    }

    /** Above 0.8 the level is bent softly towards 1, so that nothing clips. */
    static void limit(float[] x) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            float a = Math.abs(v);
            if (a > 0.8f) x[i] = Math.signum(v) * (float) (0.8 + 0.2 * Math.tanh((a - 0.8) / 0.2));
        }
    }

    private static void crackle(float[] left, float[] right, double amount, long seed, int sampleRate) {
        Random random = new Random(seed ^ 0x5eedL);
        Dsp.Filter hiss = new Dsp.Filter().set(3000, 0.5, sampleRate);
        double pop = 0;
        for (int i = 0; i < left.length; i++) {
            double v = hiss.process(random.nextGaussian()) * 0.006 * amount;
            if (random.nextDouble() < 6 * amount / sampleRate) pop = (random.nextDouble() - 0.5) * 0.25 * amount;
            v += pop;
            pop *= 0.6;
            left[i] += (float) v;
            right[i] += (float) v;
        }
    }

    /**
     * One drum of the recipe's kit as a sample, filtered by the hardness like in the mix; for the MOD export.
     */
    public float[] drumSample(Recipe r, Drum drum, double sampleRate) {
        float[] out = synth.drum(drum, r.kit(), r.hardness() / 100.0, 1.0, sampleRate, 4242 + drum.ordinal());
        Dsp.lowPass(out, drumCutoff(r.hardness()), 0.707, sampleRate);
        return out;
    }

    /**
     * One note of a part's instrument as a sample, with the part's attack and release and, for melody and
     * chords, the brightness filter of the mix; for the MOD export.
     */
    public float[] noteSample(Recipe r, Part part, int pitch, double held, double sampleRate) {
        Voice voice = r.voice(part);
        float[] out = synth.note(voice.instrument(), pitch, 0.85, held, Tone.of(voice), sampleRate,
                99 + voice.instrument().ordinal());
        if (part != Part.BASS) Dsp.lowPass(out, brightCutoff(r.brightness()), 0.6, sampleRate);
        return out;
    }

    /** A short phrase on one instrument, for trying it in the instrument chooser. */
    public Audio preview(Voice voice, int pitch) {
        double held = voice.instrument().sustained() ? 0.6 : 0.4;
        float[] note = synth.note(voice.instrument(), pitch + 12 * voice.octave(), 0.85, held, Tone.of(voice),
                SAMPLE_RATE, 1);
        float[] left = new float[note.length];
        float[] right = new float[note.length];
        for (int i = 0; i < note.length; i++) {
            left[i] = note[i] * 0.5f;
            right[i] = note[i] * 0.5f;
        }
        return new Audio(left, right, SAMPLE_RATE);
    }
}
