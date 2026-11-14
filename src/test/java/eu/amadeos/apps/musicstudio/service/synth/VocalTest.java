package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocalTest {
    private static final double SR = 44_100;
    private final Synth synth = new Synth();

    private float[] sing(Instrument in, int pitch) {
        return synth.note(in, pitch, 0.85, 1.0, Tone.NEUTRAL, SR, 7);
    }

    /** Root mean square between two times in seconds. */
    private static double level(float[] x, double from, double to) {
        double sum = 0;
        int a = (int) (from * SR);
        int b = Math.min(x.length, (int) (to * SR));
        for (int i = a; i < b; i++) sum += x[i] * x[i];
        return Math.sqrt(sum / (b - a));
    }

    /** How strong one frequency is in the held part of a note (Goertzel). */
    private static double strength(float[] x, double hz) {
        int a = (int) (0.4 * SR);
        int n = (int) (0.5 * SR);
        double w = 2 * Math.PI * hz / SR;
        double re = 0;
        double im = 0;
        for (int i = 0; i < n; i++) {
            double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
            re += x[a + i] * window * Math.cos(w * i);
            im += x[a + i] * window * Math.sin(w * i);
        }
        return Math.hypot(re, im) / n;
    }

    /** The energy in a band, summed over the overtones of the note that fall into it. */
    private static double band(float[] x, double f, double low, double high) {
        double sum = 0;
        for (double h = f; h < high; h += f) {
            if (h >= low) sum += Math.pow(strength(x, h), 2);
        }
        return sum;
    }

    @ParameterizedTest
    @EnumSource(value = Instrument.class, names = {"MALE_OOH", "MALE_AAH", "FEMALE_OOH", "FEMALE_AAH", "CHILD_OOH",
            "CHILD_AAH", "CHOIR_OOH", "CHOIR_AAH", "CHOIR_MMM"})
    void everyVoiceSingsEveryNoteAtTheSameLevel(Instrument in) {
        assertTrue(Vocal.sings(in));
        assertTrue(in.sustained());
        double lowest = Double.MAX_VALUE;
        double highest = 0;
        for (int pitch = 36; pitch <= 84; pitch += 4) {
            float[] note = sing(in, pitch);
            for (float v : note) assertTrue(Float.isFinite(v) && Math.abs(v) < 2.5, in + " " + pitch + ": " + v);
            double held = level(note, 0.3, 1.0);
            lowest = Math.min(lowest, held);
            highest = Math.max(highest, held);
            assertEquals(0, note[note.length - 1], 1e-3, "fades out");
            assertTrue(level(note, 0, 0.01) < held, "sets in softly");
        }
        assertTrue(lowest > 0.15, in + " is heard: " + lowest);
        assertTrue(highest / lowest < 2, in + " from " + lowest + " to " + highest);
    }

    @Test
    void theSameNoteIsTheSameSound() {
        assertArrayEquals(sing(Instrument.CHOIR_AAH, 60), sing(Instrument.CHOIR_AAH, 60));
        assertFalse(Arrays.equals(sing(Instrument.CHOIR_AAH, 60), synth.note(Instrument.CHOIR_AAH, 60, 0.85, 1.0,
                Tone.NEUTRAL, SR, 8)), "another seed, other singers");
    }

    @Test
    void theVowelIsInTheFormants() {
        double f = Dsp.hz(45);
        float[] aah = sing(Instrument.MALE_AAH, 45);
        float[] ooh = sing(Instrument.MALE_OOH, 45);
        float[] mmm = sing(Instrument.CHOIR_MMM, 45);
        // "aah" has its strength between 600 and 1200 Hz, "ooh" below 500 Hz, a hum hardly anything above.
        double aahOpen = band(aah, f, 550, 1250) / band(aah, f, 80, 450);
        double oohOpen = band(ooh, f, 550, 1250) / band(ooh, f, 80, 450);
        assertTrue(aahOpen > 4 * oohOpen, aahOpen + " against " + oohOpen);
        assertTrue(band(mmm, f, 1500, 4000) / band(mmm, f, 80, 450) < band(aah, f, 1500, 4000) / band(aah, f, 80, 450) / 10);
    }

    @Test
    void womenAndChildrenSoundBrighterThanMen() {
        double f = Dsp.hz(57);
        double man = band(sing(Instrument.MALE_AAH, 57), f, 1300, 4500) / band(sing(Instrument.MALE_AAH, 57), f, 80, 1300);
        double child = band(sing(Instrument.CHILD_AAH, 57), f, 1300, 4500)
                / band(sing(Instrument.CHILD_AAH, 57), f, 80, 1300);
        assertTrue(child > 1.5 * man, child + " against " + man);
    }

    @Test
    void aHeldNoteWavers() {
        // Vibrato: the pitch of a held solo voice moves, so the note is not periodic like an organ's.
        float[] voice = sing(Instrument.FEMALE_AAH, 69);
        float[] organ = synth.note(Instrument.SUB_BASS, 69, 0.85, 1.0, Tone.NEUTRAL, SR, 7);
        assertTrue(drift(voice) > 5 * drift(organ) + 0.02, drift(voice) + " against " + drift(organ));
    }

    /** How much the phase at 440 Hz moves between two moments of the held note, as a part of a turn. */
    private static double drift(float[] x) {
        double[] phase = new double[2];
        for (int k = 0; k < 2; k++) {
            int a = (int) ((0.55 + 0.1 * k) * SR);
            int n = 2205; // 22 cycles of 440 Hz
            double re = 0;
            double im = 0;
            for (int i = 0; i < n; i++) {
                re += x[a + i] * Math.cos(2 * Math.PI * 440 * (a + i) / SR);
                im += x[a + i] * Math.sin(2 * Math.PI * 440 * (a + i) / SR);
            }
            phase[k] = Math.atan2(im, re) / (2 * Math.PI);
        }
        double d = Math.abs(phase[1] - phase[0]);
        return Math.min(d, 1 - d);
    }

    @Test
    void aChoirSetsInSofterThanOneSinger() {
        float[] choir = sing(Instrument.CHOIR_OOH, 60);
        float[] solo = sing(Instrument.FEMALE_OOH, 60);
        assertTrue(level(choir, 0, 0.05) / level(choir, 0.4, 0.9) < level(solo, 0, 0.05) / level(solo, 0.4, 0.9));
        assertTrue(Synth.tail(Instrument.CHOIR_OOH) > Synth.tail(Instrument.FEMALE_OOH));
    }
}
