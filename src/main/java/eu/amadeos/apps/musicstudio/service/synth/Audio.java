package eu.amadeos.apps.musicstudio.service.synth;

/** Stereo sound as floats from -1 to 1. */
public record Audio(float[] left, float[] right, int sampleRate) {
    public Audio {
        if (left.length != right.length) throw new IllegalArgumentException("Both sides need the same length");
    }

    public int frames() {
        return left.length;
    }

    public double seconds() {
        return frames() / (double) sampleRate;
    }

    /** 16-bit samples, left and right in turn. */
    public short[] toPcm16() {
        short[] pcm = new short[2 * frames()];
        for (int i = 0; i < frames(); i++) {
            pcm[2 * i] = sample(left[i]);
            pcm[2 * i + 1] = sample(right[i]);
        }
        return pcm;
    }

    static short sample(float v) {
        return (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
    }

    /** The loudest sample of each of {@code count} equal pieces, 0 to 1, for a waveform. */
    public float[] peaks(int count) {
        float[] peaks = new float[Math.max(0, count)];
        int frames = frames();
        if (frames == 0) return peaks;
        for (int p = 0; p < count; p++) {
            int from = (int) ((long) p * frames / count);
            int to = Math.max(from + 1, (int) ((long) (p + 1) * frames / count));
            float max = 0;
            for (int i = from; i < Math.min(to, frames); i++) max = Math.max(max, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            peaks[p] = Math.min(1, max);
        }
        return peaks;
    }
}
