package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.service.compose.Drum;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Filter;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Osc;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Wave;

import java.util.Random;

/**
 * The instruments and drums, calculated sample by sample: additive synthesis for the piano and the organ,
 * frequency modulation for electric piano, bells and marimba, filtered band-limited waves for pads, strings,
 * leads and basses, a plucked string (Karplus-Strong) for the guitars, harp and the plucked basses, the same string driven into
 * distortion for the overdriven guitar, and noise, sweeps
 * and filters for the drums; the sung voices are in {@link Vocal}. Thread-safe; the noise of a note comes from the seed it is given.
 */
public final class Synth {
    /** The longest a decaying note rings, in seconds. */
    static final double MAX_RING = 4.5;

    /** How long the instrument sounds on after the key is let go, in seconds. */
    static double tail(Instrument in) {
        return switch (in) {
            case WARM_PAD -> 1.4;
            case STRINGS -> 0.8;
            case PIANO -> 1.2;
            case ELECTRIC_PIANO, BELLS, HARP, GUITAR, MARIMBA -> 1.0;
            case FLUTE, SYNTH_LEAD -> 0.25;
            case ORGAN, CHIP_SQUARE, CHIP_TRIANGLE -> 0.05;
            case SYNTH_BASS, SUB_BASS -> 0.08;
            case ELECTRIC_BASS -> 0.15;
            case MALE_OOH, MALE_AAH, FEMALE_OOH, FEMALE_AAH, CHILD_OOH, CHILD_AAH -> 0.3;
            case CHOIR_OOH, CHOIR_AAH, CHOIR_MMM -> 0.7;
            case DISTORTED_GUITAR -> 0.12;
            case ELECTRIC_GUITAR, VIBRAPHONE -> 1.0;
            case SAXOPHONE -> 0.12;
            case UPRIGHT_BASS -> 0.2;
        };
    }

    /**
     * One note, mono.
     *
     * @param held how long the key is held in seconds; the release follows
     */
    public float[] note(Instrument in, int pitch, double velocity, double held, Tone tone, double sampleRate,
                        long noiseSeed) {
        double f = Dsp.hz(pitch);
        double rel = tail(in) * tone.release();
        double total = switch (in) {
            case PIANO, BELLS -> Math.min(held + Math.max(rel, in == Instrument.BELLS ? 1.6 : 0), MAX_RING);
            case ELECTRIC_PIANO, HARP, GUITAR, MARIMBA, ELECTRIC_BASS, ELECTRIC_GUITAR, UPRIGHT_BASS, VIBRAPHONE ->
                    Math.min(held + rel, 3.5);
            case DISTORTED_GUITAR -> Math.min(held + rel, MAX_RING);
            default -> held + rel;
        };
        int n = Math.max(1, (int) (total * sampleRate));
        float[] out = new float[n];
        Random noise = new Random(noiseSeed);
        double sr = sampleRate;
        switch (in) {
            case PIANO -> {
                piano(out, f, velocity, sr, noise);
                damp(out, held, 0.25 * tone.release(), sr);
            }
            case ELECTRIC_PIANO, BELLS, MARIMBA, VIBRAPHONE -> {
                fm(out, in, f, velocity, sr);
                if (in != Instrument.BELLS) damp(out, held, (in == Instrument.VIBRAPHONE ? 0.6 : 0.3) * tone.release(), sr);
            }
            case DISTORTED_GUITAR -> distorted(out, f, velocity, held, rel, sr, noise);
            case SAXOPHONE -> {
                Osc reed = new Osc(Dsp.table(Wave.PULSE, f, sr, 0.36), f, sr, 0);
                Filter body = new Filter();
                Filter breath = new Filter().set(Math.min(f * 3, 5000), 2, sr);
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double vibrato = 1 + 0.005 * Math.sin(2 * Math.PI * 5 * t) * Math.min(1, Math.max(0, t - 0.25) / 0.4);
                    // The reed opens with the breath: the sound starts dull and a little flat.
                    double blow = Math.min(1, t / (0.05 * tone.attack() + 0.01));
                    if (i % 32 == 0) body.set(Math.min(600 + f * (1.5 + 2.5 * blow * (0.5 + 0.5 * velocity)), 6000), 1.6, sr);
                    double v = body.process(reed.next(vibrato * (1 - 0.01 * (1 - blow))));
                    breath.process(noise.nextGaussian());
                    v += 0.05 * breath.band * (0.5 + Math.exp(-t / 0.08));
                    out[i] = (float) (0.75 * v * Dsp.envelope(t, 0.04 * tone.attack(), 0.2, 0.85, rel, held));
                }
            }
            case WARM_PAD, STRINGS, SYNTH_LEAD, SYNTH_BASS -> subtractive(out, in, f, velocity, held, rel, tone, sr, noise);
            case ORGAN -> {
                double[] mult = {0.5, 1, 2, 3, 4, 6, 8};
                double[] amp = {0.6, 1, 0.7, 0.4, 0.3, 0.15, 0.1};
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double v = 0;
                    for (int k = 0; k < mult.length; k++) {
                        if (f * mult[k] < sr * 0.45) v += amp[k] * Math.sin(2 * Math.PI * f * mult[k] * t);
                    }
                    v *= 0.4 * (1 + 0.08 * Math.sin(2 * Math.PI * 6 * t));
                    out[i] = (float) (v * Dsp.envelope(t, 0.01 * tone.attack(), 0.1, 1, rel, held));
                }
            }
            case CHIP_SQUARE -> {
                Osc o = new Osc(Dsp.table(Wave.PULSE, f, sr, pitch >= 66 ? 0.25 : 0.5), f, sr, 0);
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double vibrato = 1 + (t > 0.25 ? 0.006 * Math.sin(2 * Math.PI * 6 * t) : 0);
                    out[i] = (float) (0.6 * o.next(vibrato) * Dsp.envelope(t, 0.003 * tone.attack(), 0.15, 0.7, rel, held));
                }
            }
            case CHIP_TRIANGLE -> {
                double phase = 0;
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double tri = 1 - 4 * Math.abs(phase - 0.5);
                    tri = Math.round(tri * 7.5) / 7.5; // sixteen steps, like old sound chips
                    phase += f / sr;
                    phase -= Math.floor(phase);
                    out[i] = (float) (0.9 * tri * Dsp.envelope(t, 0.002 * tone.attack(), 1, 1, rel, held));
                }
            }
            case FLUTE -> {
                Filter breath = new Filter().set(f * 2, 3, sr);
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double vibrato = Math.sin(2 * Math.PI * 4.8 * t) * 0.004 * Math.min(1, Math.max(0, t - 0.2) / 0.3);
                    double ph = 2 * Math.PI * f * t * (1 + vibrato);
                    double v = Math.sin(ph) + 0.22 * Math.sin(2 * ph) + 0.06 * Math.sin(3 * ph);
                    breath.process(noise.nextGaussian());
                    v += 0.12 * breath.band * (0.4 + Math.exp(-t / 0.1));
                    out[i] = (float) (0.6 * v * Dsp.envelope(t, 0.06 * tone.attack(), 0.3, 0.85, rel, held));
                }
            }
            case GUITAR, HARP, ELECTRIC_BASS, ELECTRIC_GUITAR, UPRIGHT_BASS -> plucked(out, in, f, velocity, held, rel, sr, noise);
            case MALE_OOH, MALE_AAH, FEMALE_OOH, FEMALE_AAH, CHILD_OOH, CHILD_AAH, CHOIR_OOH, CHOIR_AAH, CHOIR_MMM ->
                    Vocal.note(out, in, pitch, velocity, held, rel, tone, sr, noise);
            case SUB_BASS -> {
                for (int i = 0; i < n; i++) {
                    double t = i / sr;
                    double v = Math.sin(2 * Math.PI * f * t) + 0.15 * Math.sin(4 * Math.PI * f * t);
                    out[i] = (float) (0.9 * v * Dsp.envelope(t, 0.01 * tone.attack(), 1, 1, rel, held));
                }
            }
        }
        if (!in.sustained() && tone.attack() != 1) fadeIn(out, 0.003 * Math.pow(tone.attack(), 2.5), sr);
        finish(out, velocity, sr);
        return out;
    }

    private static void piano(float[] out, double f, double velocity, double sr, Random noise) {
        int n = out.length;
        int partials = Math.max(1, Math.min(18, (int) (sr * 0.45 / f)));
        double bright = 0.5 + 0.5 * velocity;
        for (int k = 1; k <= partials; k++) {
            double fk = f * k * Math.sqrt(1 + 0.0003 * k * k); // stiff strings: overtones a little sharp
            if (fk > sr * 0.45) break;
            double a = Math.pow(k, -1.1 - (1 - bright)) * (k == 1 ? 1 : 0.8);
            double tau = 2.2 * Math.pow(220 / f, 0.4) / (1 + 0.35 * (k - 1));
            for (int detune = -1; detune <= 1; detune += 2) { // two strings per key, slightly apart
                double w = 2 * Math.PI * fk * (1 + detune * 0.0004) / sr;
                double cos = Math.cos(w);
                double sin = Math.sin(w);
                double re = 1;
                double im = 0;
                double decay = Math.exp(-1 / (tau * sr));
                double env = 0.5 * a;
                for (int i = 0; i < n; i++) {
                    out[i] += (float) (im * env);
                    double nre = re * cos - im * sin;
                    im = re * sin + im * cos;
                    re = nre;
                    env *= decay;
                }
            }
        }
        int hammer = Math.min(n, (int) (0.01 * sr));
        for (int i = 0; i < hammer; i++) out[i] += (float) ((noise.nextDouble() - 0.5) * 0.15 * velocity * (1 - i / (0.01 * sr)));
    }

    private static void fm(float[] out, Instrument in, double f, double velocity, double sr) {
        boolean vibes = in == Instrument.VIBRAPHONE;
        double ratio = in == Instrument.BELLS ? 3.5 : in == Instrument.MARIMBA || vibes ? 4.0 : 1.0;
        double index = in == Instrument.BELLS ? 2.5 : in == Instrument.MARIMBA ? 1.2 : vibes ? 0.9 * (0.5 + velocity)
                : 1.4 * (0.5 + velocity);
        double indexTau = in == Instrument.BELLS ? 1.2 : in == Instrument.MARIMBA ? 0.03 : vibes ? 0.06 : 0.35;
        double ampTau = in == Instrument.BELLS ? 1.8 : in == Instrument.MARIMBA ? 0.35
                : vibes ? 1.7 * Math.pow(261 / f, 0.25) : 1.2 * Math.pow(261 / f, 0.3);
        for (int i = 0; i < out.length; i++) {
            double t = i / sr;
            double mod = index * Math.exp(-t / indexTau) * Math.sin(2 * Math.PI * f * ratio * t);
            double v = Math.sin(2 * Math.PI * f * t + mod) * Math.exp(-t / ampTau);
            if (in == Instrument.ELECTRIC_PIANO && f * 14 < sr * 0.45) {
                v += 0.15 * velocity * Math.exp(-t / 0.04) * Math.sin(2 * Math.PI * f * 14 * t); // the tine
            }
            if (in == Instrument.BELLS) v += 0.3 * Math.exp(-t / 0.9) * Math.sin(2 * Math.PI * f * 2.76 * t);
            if (vibes) v *= 1 - 0.25 * (0.5 - 0.5 * Math.cos(2 * Math.PI * 5.5 * t)); // the turning discs in the tubes
            out[i] = (float) (v * Math.min(1, t / 0.002));
        }
    }

    private static void subtractive(float[] out, Instrument in, double f, double velocity, double held, double rel,
                                    Tone tone, double sr, Random noise) {
        int voices = switch (in) {
            case WARM_PAD -> 3;
            case STRINGS -> 4;
            case SYNTH_LEAD -> 2;
            default -> 1;
        };
        double detune = in == Instrument.SYNTH_BASS ? 0 : in == Instrument.SYNTH_LEAD ? 0.006 : 0.008;
        Osc[] osc = new Osc[voices];
        for (int v = 0; v < voices; v++) {
            double fd = f * (1 + detune * (v - (voices - 1) / 2.0));
            osc[v] = new Osc(Dsp.table(Wave.SAW, fd, sr, 0), fd, sr, noise.nextDouble());
        }
        Osc sub = in == Instrument.SYNTH_BASS ? new Osc(Dsp.table(Wave.PULSE, f / 2, sr, 0.5), f / 2, sr, 0) : null;
        Filter filter = new Filter();
        double attack = tone.attack() * switch (in) {
            case WARM_PAD -> 0.6;
            case STRINGS -> 0.25;
            case SYNTH_LEAD -> 0.01;
            default -> 0.004;
        };
        boolean vibrato = in == Instrument.STRINGS || in == Instrument.SYNTH_LEAD;
        for (int i = 0; i < out.length; i++) {
            double t = i / sr;
            double pitchFactor = vibrato ? 1 + 0.003 * Math.sin(2 * Math.PI * 5.2 * t) * Math.min(1, t / 0.4) : 1;
            double sum = 0;
            for (Osc o : osc) sum += o.next(pitchFactor);
            sum /= Math.sqrt(voices);
            if (sub != null) sum += 0.5 * sub.next(1);
            if (i % 32 == 0) {
                double cutoff = switch (in) {
                    case WARM_PAD -> f * 3 + 1200 * Math.min(1, t / 1.5);
                    case STRINGS -> 3200;
                    case SYNTH_LEAD -> f * (2.5 + 4 * Math.exp(-t / 0.25));
                    default -> 120 + (300 + 2500 * velocity) * Math.exp(-t / 0.12);
                };
                filter.set(cutoff, in == Instrument.SYNTH_BASS ? 1.4 : 0.8, sr);
            }
            double v = filter.process(sum);
            out[i] = (float) (v * Dsp.envelope(t, attack, 0.4, in == Instrument.SYNTH_BASS ? 0.75 : 0.85, rel, held));
        }
    }

    private static void plucked(float[] out, Instrument in, double f, double velocity, double held, double rel,
                                double sr, Random noise) {
        // The loop is as long as one period: the delay line, and half a sample for the averaging below.
        double delay = sr / f - 0.5;
        int whole = (int) delay;
        double frac = delay - whole;
        int length = whole + 2;
        double[] line = new double[length];
        boolean bass = in == Instrument.ELECTRIC_BASS || in == Instrument.UPRIGHT_BASS;
        Filter excite = new Filter().set(in == Instrument.ELECTRIC_BASS ? 900 : in == Instrument.UPRIGHT_BASS ? 600
                : in == Instrument.HARP ? 2500 + 2000 * velocity
                : in == Instrument.ELECTRIC_GUITAR ? 2600 + 2200 * velocity : 3500 + 3000 * velocity, 0.7, sr);
        for (int i = 0; i < length; i++) line[i] = excite.process(noise.nextDouble() * 2 - 1) * 1.6;
        double feedback = switch (in) {
            case HARP -> 0.9985;
            case ELECTRIC_BASS, ELECTRIC_GUITAR -> 0.997; // steel strings ring longer than nylon
            case UPRIGHT_BASS -> 0.9955;
            default -> 0.994;
        };
        int write = 0;
        double last = 0;
        for (int i = 0; i < out.length; i++) {
            int r1 = (write + 1) % length;
            int r2 = (write + 2) % length;
            double y = line[r2] * (1 - frac) + line[r1] * frac;
            double next = feedback * 0.5 * (y + last);
            last = y;
            line[write] = next;
            write = r1;
            double v = y;
            if (bass) {
                // The body of the note: long on the bass guitar, a short woody thump on the upright bass.
                double ring = in == Instrument.ELECTRIC_BASS ? 1.2 : 0.55;
                v = 0.6 * y + (in == Instrument.ELECTRIC_BASS ? 0.5 : 0.7) * Math.sin(2 * Math.PI * f * i / sr)
                        * Math.exp(-i / sr / ring);
            }
            out[i] = (float) v;
        }
        damp(out, held, (in == Instrument.HARP ? 0.6 : in == Instrument.ELECTRIC_GUITAR ? 0.2 : 0.12) * rel / tail(in), sr);
    }

    /**
     * An electric guitar through an overdriven amplifier: a plucked steel string that rings long, driven hard
     * into a soft limit, which adds overtones and keeps the note loud while the string dies away, and then the
     * speaker cabinet, which lets little through above four kilohertz. A short note is a muted one.
     */
    private static void distorted(float[] out, double f, double velocity, double held, double rel, double sr,
                                  Random noise) {
        double delay = sr / f - 0.5;
        int whole = (int) delay;
        double frac = delay - whole;
        int length = whole + 2;
        double[] line = new double[length];
        Filter excite = new Filter().set(1800 + 1500 * velocity, 0.7, sr);
        for (int i = 0; i < length; i++) line[i] = excite.process(noise.nextDouble() * 2 - 1) * 1.6;
        double drive = 6 + 14 * velocity;
        Filter low = new Filter().set(110, 0.7, sr);
        Filter cabinet = new Filter().set(3600, 1.2, sr);
        Filter air = new Filter().set(5200, 0.7, sr);
        Filter smooth = new Filter().set(9000, 0.6, sr * 4);
        Filter fine = new Filter().set(9000, 0.6, sr * 4);
        double before = 0;
        int write = 0;
        double last = 0;
        for (int i = 0; i < out.length; i++) {
            int r1 = (write + 1) % length;
            int r2 = (write + 2) % length;
            double y = line[r2] * (1 - frac) + line[r1] * frac;
            line[write] = 0.9988 * 0.5 * (y + last);
            last = y;
            write = r1;
            low.process(y);
            // The limit adds overtones far above what the sample rate can hold; calculated four times as
            // finely and filtered, they do not fold back as false notes.
            double in = low.high;
            double v = 0;
            for (int k = 1; k <= 4; k++) {
                double x = before + (in - before) * k / 4.0;
                double d = Math.tanh(drive * x);
                d = Math.tanh(1.5 * d + 0.3 * d * d); // a second stage, a little uneven like a valve
                v = fine.process(smooth.process(d));
            }
            before = in;
            out[i] = (float) (0.42 * air.process(cabinet.process(v)) * Math.exp(-i / sr / 4.5));
        }
        damp(out, held, 0.1 * rel / tail(Instrument.DISTORTED_GUITAR), sr);
    }

    /** After the key is let go, a decaying note dies away faster. */
    private static void damp(float[] out, double held, double release, double sr) {
        int start = (int) (held * sr);
        double tau = Math.max(0.005, release) * sr / 4;
        for (int i = Math.max(0, start); i < out.length; i++) out[i] *= (float) Math.exp(-(i - start) / tau);
    }

    private static void fadeIn(float[] out, double seconds, double sr) {
        int n = Math.min(out.length, (int) (seconds * sr));
        for (int i = 0; i < n; i++) out[i] *= (float) (i / (double) n);
    }

    /** Velocity, and a few milliseconds of fade at the end against clicks. */
    private static void finish(float[] out, double velocity, double sr) {
        float gain = (float) (0.25 + 0.75 * velocity);
        for (int i = 0; i < out.length; i++) out[i] *= gain;
        int fade = Math.min(out.length / 2, (int) (0.003 * sr));
        for (int i = 0; i < fade; i++) out[out.length - 1 - i] *= (float) (i / (double) fade);
    }

    // ---------------------------------------------------------------- drums

    /**
     * One drum hit, mono.
     *
     * @param hardness 0 (dull) to 1 (hard)
     */
    public float[] drum(Drum d, Kit kit, double hardness, double velocity, double sampleRate, long noiseSeed) {
        double seconds = switch (d) {
            case KICK -> kit == Kit.ELECTRONIC ? 0.6 : 0.4;
            case SNARE, CLAP -> 0.35;
            case HAT, SHAKER -> kit == Kit.BRUSH ? 0.4 : 0.12;
            case OPEN_HAT -> kit == Kit.ROCK ? 1.0 : 0.6;
            case TOM -> 0.7;
        };
        float[] out = new float[(int) (seconds * sampleRate)];
        Random noise = new Random(noiseSeed);
        switch (d) {
            case KICK -> kick(out, kit, hardness, sampleRate, noise);
            case SNARE, CLAP -> snare(out, d == Drum.CLAP, kit, hardness, sampleRate, noise);
            case HAT, OPEN_HAT, SHAKER -> hat(out, d, kit, hardness, sampleRate, noise);
            case TOM -> {
                double phase = 0;
                for (int i = 0; i < out.length; i++) {
                    double t = i / sampleRate;
                    phase += 2 * Math.PI * (70 + 60 * Math.exp(-t / 0.08)) / sampleRate;
                    double v = Math.sin(phase) * Math.exp(-t / 0.35)
                            + (noise.nextDouble() - 0.5) * 0.4 * Math.exp(-t / 0.03) * hardness;
                    out[i] = (float) Math.tanh(v * (1 + 2 * hardness));
                }
            }
        }
        finish(out, velocity, sampleRate);
        return out;
    }

    private static void kick(float[] out, Kit kit, double h, double sr, Random noise) {
        double f0 = switch (kit) {
            case ELECTRONIC -> 160 + 120 * h;
            case CHIP -> 300;
            case LOFI -> 110 + 60 * h;
            case ROCK -> 150 + 70 * h;
            case BRUSH -> 95 + 30 * h;
            default -> 120 + 80 * h;
        };
        double f1 = kit == Kit.ELECTRONIC ? 46 : 52;
        double pitchTau = kit == Kit.CHIP ? 0.03 : 0.035 + 0.02 * (1 - h);
        double ampTau = switch (kit) {
            case ELECTRONIC -> 0.32;
            case CHIP -> 0.09;
            case ROCK -> 0.13; // short, so that fast kicks stay apart
            default -> 0.18;
        };
        double click = kit == Kit.ROCK ? 2.2 : kit == Kit.BRUSH ? 0.3 : 1.2;
        double drive = 1 + 4 * h;
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / sr;
            phase += 2 * Math.PI * (f1 + (f0 - f1) * Math.exp(-t / pitchTau)) / sr;
            double v = kit == Kit.CHIP ? Math.signum(Math.sin(phase)) * 0.6 : Math.sin(phase);
            v *= Math.exp(-t / ampTau);
            v += (noise.nextDouble() - 0.5) * click * h * Math.exp(-t / 0.004); // the beater's click
            out[i] = (float) (Math.tanh(v * drive) / Math.tanh(drive));
        }
    }

    private static void snare(float[] out, boolean clap, Kit kit, double h, double sr, Random noise) {
        Filter band = new Filter().set(clap ? 1100 + 800 * h : 1400 + 4500 * h, clap ? 1.8 : 0.7, sr);
        int lfsr = 0x7FFF;
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / sr;
            double n;
            if (kit == Kit.CHIP) {
                if (i % 3 == 0) {
                    int bit = (lfsr ^ (lfsr >> 1)) & 1;
                    lfsr = (lfsr >> 1) | (bit << 14);
                }
                n = (lfsr & 1) * 2 - 1;
            } else {
                n = noise.nextDouble() * 2 - 1;
            }
            double v;
            band.process(n);
            if (clap && (kit == Kit.ROCK || kit == Kit.BRUSH)) {
                // The stick laid across the drum and struck on the rim: a short, woody click.
                v = (Math.sin(2 * Math.PI * 1750 * t) * 0.8 + Math.sin(2 * Math.PI * 820 * t) * 0.5) * Math.exp(-t / 0.012)
                        + band.band * 0.5 * Math.exp(-t / 0.006);
            } else if (kit == Kit.BRUSH) {
                // A wire brush: no drum tone, a soft swish that takes a moment to arrive.
                v = (band.band * 0.9 + band.high * 0.25) * Math.min(1, t / 0.008) * Math.exp(-t / (0.07 + 0.05 * h)) * 0.7;
            } else if (clap) {
                double env = 0;
                for (int b = 0; b < 3; b++) {
                    double tb = t - b * 0.011;
                    if (tb >= 0) env = Math.max(env, Math.exp(-tb / 0.006));
                }
                if (t > 0.022) env = Math.max(env, 0.6 * Math.exp(-(t - 0.022) / 0.09));
                v = band.band * 2.2 * env;
            } else {
                phase += 2 * Math.PI * (180 + 40 * Math.exp(-t / 0.02)) / sr;
                double tone = Math.sin(phase) * Math.exp(-t / (kit == Kit.ROCK ? 0.08 : 0.05)) * (0.9 - 0.4 * h);
                double rattle = kit == Kit.CHIP ? n * 0.5 : band.band * 1.4 + band.high * 0.4 * h;
                v = tone + rattle * Math.exp(-t / (0.08 + 0.1 * h + (kit == Kit.ROCK ? 0.04 : 0)));
            }
            out[i] = (float) Math.tanh(v * (1 + 1.5 * h));
        }
    }

    private static void hat(float[] out, Drum d, Kit kit, double h, double sr, Random noise) {
        double cutoff = d == Drum.SHAKER ? 5000 + 2000 * h : 6000 + 3500 * h;
        if (kit == Kit.LOFI) cutoff *= 0.6;
        Filter high = new Filter().set(cutoff, d == Drum.SHAKER ? 1.2 : 0.7, sr);
        double[] metal = {205.3, 304.4, 369.6, 522.7, 540.0, 800.0};
        double[] phases = new double[metal.length];
        double tau = d == Drum.OPEN_HAT ? 0.18 : d == Drum.SHAKER ? 0.04 : 0.025 + 0.02 * (1 - h);
        if (kit == Kit.BRUSH && d == Drum.HAT) tau = 0.11; // rings on like a ride cymbal
        if (kit == Kit.ROCK && d == Drum.OPEN_HAT) tau = 0.3; // a crash
        int lfsr = 0x7FFF;
        for (int i = 0; i < out.length; i++) {
            double t = i / sr;
            double n;
            if (kit == Kit.CHIP) {
                int bit = (lfsr ^ (lfsr >> 6)) & 1;
                lfsr = (lfsr >> 1) | (bit << 14);
                n = (lfsr & 1) * 2 - 1;
            } else {
                double m = 0;
                for (int k = 0; k < metal.length; k++) {
                    phases[k] += metal[k] * 8 / sr;
                    m += phases[k] % 1 < 0.5 ? 1 : -1;
                }
                n = (noise.nextDouble() * 2 - 1) * 0.6 + (d == Drum.SHAKER ? 0 : m / 6 * 0.6);
            }
            high.process(n);
            double env = d == Drum.SHAKER ? Math.min(1, t / 0.012) * Math.exp(-Math.max(0, t - 0.012) / tau)
                    : Math.exp(-t / tau);
            out[i] = (float) (high.high * env * (kit == Kit.BRUSH ? 0.55 : 0.8));
        }
    }
}
