package eu.amadeos.apps.musicstudio.service.synth;

/** A small hall: eight damped comb filters and four all-pass filters per side, slightly apart left and right. */
final class Reverb {
    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALL_PASS = {556, 441, 341, 225};

    private Reverb() {
    }

    /** Adds the hall of {@code in} to both sides; {@code amount} 0 to 1 sets its length and level. */
    static void apply(float[] in, float[] left, float[] right, double amount, double sampleRate) {
        if (amount <= 0.01) return;
        double scale = sampleRate / 44100.0;
        double room = 0.78 + 0.18 * amount;
        double damp = 0.3;
        for (int side = 0; side < 2; side++) {
            float[] out = new float[in.length];
            int spread = side * 23;
            for (int c : COMBS) {
                int len = Math.max(1, (int) ((c + spread) * scale));
                float[] buf = new float[len];
                double store = 0;
                int p = 0;
                for (int i = 0; i < in.length; i++) {
                    double y = buf[p];
                    store = y * (1 - damp) + store * damp;
                    buf[p] = (float) (in[i] * 0.015 + store * room);
                    out[i] += (float) y;
                    if (++p == len) p = 0;
                }
            }
            for (int a : ALL_PASS) {
                int len = Math.max(1, (int) ((a + spread) * scale));
                float[] buf = new float[len];
                int p = 0;
                for (int i = 0; i < in.length; i++) {
                    double b = buf[p];
                    double x = out[i];
                    buf[p] = (float) (x + b * 0.5);
                    out[i] = (float) (b - x);
                    if (++p == len) p = 0;
                }
            }
            float[] target = side == 0 ? left : right;
            double wet = 3.0 * amount;
            for (int i = 0; i < in.length; i++) target[i] += (float) (out[i] * wet);
        }
    }
}
