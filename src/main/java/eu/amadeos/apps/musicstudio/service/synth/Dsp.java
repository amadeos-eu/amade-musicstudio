package eu.amadeos.apps.musicstudio.service.synth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small building blocks of the synthesizer: band-limited waves, an oscillator, a filter and an envelope. */
final class Dsp {
    static final int TABLE_SIZE = 2048;
    private static final Map<Long, float[]> TABLES = new ConcurrentHashMap<>();

    enum Wave { SAW, PULSE, TRIANGLE }

    private Dsp() {
    }

    /** The frequency of a MIDI note; 69 is A at 440 Hz. */
    static double hz(double pitch) {
        return 440 * Math.pow(2, (pitch - 69) / 12.0);
    }

    /**
     * One cycle of a wave with only the overtones below the Nyquist frequency, so that high notes do not
     * alias. The tables are shared; there are few, since the number of overtones is all that differs.
     */
    static float[] table(Wave wave, double frequency, double sampleRate, double duty) {
        int harmonics = Math.max(1, Math.min(160, (int) (sampleRate * 0.45 / frequency)));
        long key = wave.ordinal() * 1_000_000L + harmonics * 1000L + Math.round(duty * 100);
        return TABLES.computeIfAbsent(key, k -> build(wave, harmonics, duty));
    }

    private static float[] build(Wave wave, int harmonics, double duty) {
        double[] acc = new double[TABLE_SIZE];
        for (int h = 1; h <= harmonics; h++) {
            double amp;
            double phase = 0;
            switch (wave) {
                case SAW -> amp = 1.0 / h;
                case PULSE -> {
                    amp = Math.sin(Math.PI * h * duty) / h;
                    phase = Math.PI / 2 - Math.PI * h * duty;
                }
                default -> {
                    if (h % 2 == 0) continue;
                    amp = ((h / 2) % 2 == 0 ? 1 : -1) / (double) (h * h);
                }
            }
            double soft = Math.cos(Math.PI / 2 * (h - 1) / (double) harmonics); // softens the top overtones
            soft *= soft;
            for (int i = 0; i < TABLE_SIZE; i++) acc[i] += amp * soft * Math.sin(2 * Math.PI * h * i / TABLE_SIZE + phase);
        }
        double max = 1e-12;
        for (double v : acc) max = Math.max(max, Math.abs(v));
        float[] t = new float[TABLE_SIZE + 1];
        for (int i = 0; i < TABLE_SIZE; i++) t[i] = (float) (acc[i] / max);
        t[TABLE_SIZE] = t[0];
        return t;
    }

    /** Reads a wave table at a frequency. */
    static final class Osc {
        private final float[] table;
        private final double increment;
        private double phase;

        Osc(float[] table, double frequency, double sampleRate, double phase) {
            this.table = table;
            this.increment = frequency / sampleRate;
            this.phase = phase;
        }

        double next(double pitchFactor) {
            double x = phase * TABLE_SIZE;
            int i = (int) x;
            double v = table[i] + (table[i + 1] - table[i]) * (x - i);
            phase += increment * pitchFactor;
            phase -= Math.floor(phase);
            return v;
        }
    }

    /** A state-variable filter (zero-delay feedback): low, band and high pass at once. */
    static final class Filter {
        private double ic1;
        private double ic2;
        private double a1;
        private double a2;
        private double a3;
        private double k;
        double low;
        double band;
        double high;

        Filter set(double cutoff, double q, double sampleRate) {
            double fc = Math.max(20, Math.min(sampleRate * 0.45, cutoff));
            double g = Math.tan(Math.PI * fc / sampleRate);
            k = 1 / q;
            a1 = 1 / (1 + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;
            return this;
        }

        double process(double x) {
            double v3 = x - ic2;
            double v1 = a1 * ic1 + a2 * v3;
            double v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1;
            ic2 = 2 * v2 - ic2;
            low = v2;
            band = v1;
            high = x - k * v1 - v2;
            return v2;
        }
    }

    /** Attack, decay to the sustain level, held for {@code held} seconds, then a straight release. */
    static double envelope(double t, double attack, double decay, double sustain, double release, double held) {
        double tt = Math.min(t, held);
        double level = tt < attack ? tt / attack : sustain + (1 - sustain) * Math.exp(-(tt - attack) / Math.max(1e-4, decay));
        if (t > held) level *= Math.max(0, 1 - (t - held) / release);
        return level;
    }

    /** Low-pass over a whole buffer. */
    static void lowPass(float[] x, double cutoff, double q, double sampleRate) {
        if (cutoff >= sampleRate * 0.44) return;
        Filter f = new Filter().set(cutoff, q, sampleRate);
        for (int i = 0; i < x.length; i++) x[i] = (float) f.process(x[i]);
    }
}
