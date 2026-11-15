package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.service.synth.Dsp.Filter;

/**
 * An overdriven guitar amplifier with its speaker cabinet. The bass is cut and the middle raised before the
 * valves, so that low chords stay tight; two stages limit the signal, the second a little unevenly; the
 * cabinet lets little through above four kilohertz and has a peak just below. All strings of a part go
 * through one amplifier: notes that sound together rub against each other there, which is the growl of a
 * power chord and what a keyboard with distortion on every key lacks.
 */
final class Amp {
    private Amp() {
    }

    /** Plays the strings in {@code x} through the amplifier, in place. */
    static void play(float[] x, double sr) {
        Filter tight = new Filter().set(110, 0.7, sr);
        Filter middle = new Filter().set(600, 0.7, sr);
        Filter smooth = new Filter().set(9000, 0.6, sr * 4);
        Filter fine = new Filter().set(9000, 0.6, sr * 4);
        Filter rumble = new Filter().set(85, 0.7, sr);
        Filter cabinet = new Filter().set(3800, 1.6, sr);
        Filter air = new Filter().set(4600, 0.7, sr);
        double before = 0;
        for (int i = 0; i < x.length; i++) {
            tight.process(x[i]);
            middle.process(tight.high);
            double in = tight.high + 0.8 * middle.band;
            // The limit adds overtones far above what the sample rate can hold; calculated four times as
            // finely and filtered, they do not fold back as false notes.
            double v = 0;
            for (int k = 1; k <= 4; k++) {
                double at = before + (in - before) * k / 4.0;
                double d = Math.tanh(9 * at);
                d = Math.tanh(3 * d + 0.15 * d * d);
                v = fine.process(smooth.process(d));
            }
            before = in;
            rumble.process(v);
            x[i] = (float) (0.4 * air.process(cabinet.process(rumble.high)));
        }
    }
}
