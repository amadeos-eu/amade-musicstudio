package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Drum;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRendererTest {
    private static final Executor DIRECT = Runnable::run;
    private final AudioRenderer renderer = new AudioRenderer();

    static Recipe shortPiece(Style style) {
        return Recipe.of(style, 4711).with(b -> b.lengthSeconds = Recipe.MIN_LENGTH);
    }

    @Test
    void aPieceIsLoudButNeverClips() {
        Recipe r = shortPiece(Style.LOFI);
        Song song = new Composer().compose(r, false);
        Audio audio = renderer.mix(renderer.render(song, DIRECT, () -> false), r);
        assertEquals(AudioRenderer.SAMPLE_RATE, audio.sampleRate());
        assertEquals((int) ((song.seconds() + AudioRenderer.TAIL) * AudioRenderer.SAMPLE_RATE), audio.frames());
        float peak = 0;
        for (int i = 0; i < audio.frames(); i++) peak = Math.max(peak, Math.max(Math.abs(audio.left()[i]),
                Math.abs(audio.right()[i])));
        assertTrue(peak > 0.5 && peak <= 1, "peak " + peak);
        assertEquals(0, audio.left()[audio.frames() - 1], 1e-3, "the end fades out");
    }

    @Test
    void tremoloLetsTheVolumeSinkAndRiseInTimeWithThePiece() {
        int sr = 8000;
        double stepSeconds = 0.125; // one bar is two seconds
        float[] x = new float[4 * sr];
        java.util.Arrays.fill(x, 1f);
        AudioRenderer.move(x, Voice.of(Instrument.STRINGS).withTremolo(100, 3), stepSeconds, 4, 8000, sr);
        assertEquals(1, x[100], 0.02, "full at the start of the bar");
        assertEquals(0.1, x[sr], 0.02, "lowest in the middle of the bar");
        assertEquals(1, x[2 * sr - 1], 0.02, "full again at its end");
        assertEquals(0.1, x[3 * sr], 0.02, "and so on in the next bar");
        float[] still = new float[sr];
        java.util.Arrays.fill(still, 0.5f);
        AudioRenderer.move(still, Voice.of(Instrument.STRINGS), stepSeconds, 4, 8000, sr);
        for (float v : still) assertEquals(0.5f, v, "a voice that does not move is left alone");
    }

    @Test
    void theSweepClosesAndOpensTheFilter() {
        int sr = 44_100;
        double stepSeconds = 0.125;
        float[] x = new float[4 * sr];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(2 * Math.PI * 3000 * i / sr);
        AudioRenderer.move(x, Voice.of(Instrument.WARM_PAD).withSweep(100, 3), stepSeconds, 4, 12_000, sr);
        double open = rms(x, 2 * sr - 2000, 1500);
        double closed = rms(x, sr - 750, 1500);
        assertTrue(open > 0.5, "3 kHz passes when the filter is open: " + open);
        assertTrue(closed < open / 10, "and hardly when it is closed: " + closed + " against " + open);
        assertEquals(closed, rms(x, 3 * sr - 750, 1500), closed / 2, "the same in the next bar");
    }

    @Test
    void aCourseOverTheWholePieceRunsOnceInItsDirection() {
        int sr = 8000;
        float[] x = new float[10 * sr];
        java.util.Arrays.fill(x, 1f);
        AudioRenderer.move(x, Voice.of(Instrument.STRINGS).withTremolo(100, Voice.RISING), 0.125, 10, 8000, sr);
        assertEquals(0.1, x[0], 0.02, "quiet from the first sample");
        assertEquals(0.55, x[5 * sr], 0.02, "half way in the middle");
        assertEquals(1, x[x.length - 1], 0.02, "full at the end");
        for (int i = 1; i < x.length; i++) assertTrue(x[i] >= x[i - 1] - 1e-6, "it only grows");
        float[] y = new float[10 * sr];
        java.util.Arrays.fill(y, 1f);
        AudioRenderer.move(y, Voice.of(Instrument.STRINGS).withTremolo(50, Voice.FALLING), 0.125, 10, 8000, sr);
        assertEquals(1, y[100], 0.02, "full at the start");
        assertEquals(0.55, y[y.length - 1], 0.02, "down by the depth at the end");
    }

    @Test
    void aSweepOverTheWholePieceOpensOrClosesTheFilter() {
        int sr = 44_100;
        float[] x = new float[4 * sr];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(2 * Math.PI * 3000 * i / sr);
        float[] y = x.clone();
        AudioRenderer.move(x, Voice.of(Instrument.WARM_PAD).withSweep(100, Voice.RISING), 0.125, 4, 12_000, sr);
        double start = rms(x, 2000, 1500);
        double end = rms(x, x.length - 2000, 1500);
        assertTrue(end > 0.5, "open at the end: " + end);
        assertTrue(start < end / 10, "closed at the start: " + start);
        AudioRenderer.move(y, Voice.of(Instrument.WARM_PAD).withSweep(100, Voice.FALLING), 0.125, 4, 12_000, sr);
        assertTrue(rms(y, 2000, 1500) > 0.5, "falling, it starts open");
        assertTrue(rms(y, y.length - 2000, 1500) < 0.05, "and ends closed");
    }

    @Test
    void padAndArpeggioAreStemsOfTheirOwn() {
        Recipe r = shortPiece(Style.ELECTRO);
        Stems stems = renderer.render(new Composer().compose(r, false), DIRECT, () -> false);
        for (Part p : Part.values()) {
            assertTrue(rms(stems.left().get(p), 0, stems.frames()) > 1e-4, p + " is heard");
        }
        Recipe plain = r.withPlaying(Part.PAD, false).withPlaying(Part.ARPEGGIO, false);
        Stems without = renderer.render(new Composer().compose(plain, false), DIRECT, () -> false);
        assertEquals(0, rms(without.left().get(Part.PAD), 0, without.frames()));
        assertEquals(0, rms(without.right().get(Part.ARPEGGIO), 0, without.frames()));
        assertArrayEquals(stems.left().get(Part.MELODY), without.left().get(Part.MELODY));
    }

    private static double rms(float[] x, int from, int length) {
        double sum = 0;
        for (int i = from; i < from + length; i++) sum += x[i] * x[i];
        return Math.sqrt(sum / length);
    }

    @Test
    void theSameRecipeSoundsTheSame() {
        Recipe r = shortPiece(Style.CHIPTUNE);
        Song song = new Composer().compose(r, false);
        Audio a = renderer.mix(renderer.render(song, DIRECT, () -> false), r);
        Audio b = renderer.mix(renderer.render(song, DIRECT, () -> false), r);
        assertArrayEquals(a.left(), b.left());
    }

    @Test
    void aMutedPartIsSilent() {
        Recipe r = shortPiece(Style.LOFI).with(b -> b.crackle = 0);
        Stems stems = renderer.render(new Composer().compose(r, false), DIRECT, () -> false);
        Audio none = renderer.mix(stems, r, EnumSet.noneOf(Part.class));
        for (float v : none.left()) assertEquals(0, v, 1e-6);
        Audio drums = renderer.mix(stems, r, EnumSet.of(Part.DRUMS));
        float peak = 0;
        for (float v : drums.left()) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak > 0.05);
    }

    @Test
    void aLoopIsAsLongAsItsBars() {
        Recipe r = shortPiece(Style.ELECTRO);
        Song loop = new Composer().compose(r, true);
        Audio audio = renderer.mix(renderer.render(loop, DIRECT, () -> false), r);
        assertEquals(Math.round(loop.seconds() * AudioRenderer.SAMPLE_RATE), audio.frames(), 2);
    }

    @Test
    void renderingStopsWhenCancelled() {
        Song song = new Composer().compose(shortPiece(Style.AMBIENT), false);
        assertThrows(CancellationException.class, () -> renderer.render(song, DIRECT, () -> true));
    }

    @Test
    void everyInstrumentAndDrumSounds() {
        for (Instrument in : Instrument.values()) {
            Audio a = renderer.preview(Voice.of(in), 60);
            assertTrue(a.frames() > 1000, in.toString());
            float peak = 0;
            for (float v : a.left()) peak = Math.max(peak, Math.abs(v));
            assertTrue(peak > 0.05 && peak <= 1, in + " peak " + peak);
        }
        Recipe r = Recipe.of(Style.LOFI, 1);
        for (Kit kit : Kit.values()) {
            if (kit == Kit.NONE) continue;
            for (Drum d : Drum.values()) {
                float[] s = renderer.drumSample(r.with(b -> b.kit = kit), d, AudioRenderer.SAMPLE_RATE);
                float peak = 0;
                for (float v : s) peak = Math.max(peak, Math.abs(v));
                assertTrue(peak > 0.01, kit + " " + d);
            }
        }
    }

    @Test
    void theDistortedGuitarStaysLoudWhileTheCleanOneDiesAway() {
        Synth synth = new Synth();
        float[] clean = synth.note(Instrument.ELECTRIC_GUITAR, 52, 0.9, 2.0, Tone.NEUTRAL, 44_100, 1);
        float[] driven = synth.note(Instrument.DISTORTED_GUITAR, 52, 0.9, 2.0, Tone.NEUTRAL, 44_100, 1);
        double cleanLeft = rms(clean, 66_000, 8000) / rms(clean, 2000, 8000);
        double drivenLeft = rms(driven, 66_000, 8000) / rms(driven, 2000, 8000);
        assertTrue(cleanLeft < 0.5, "the clean string has faded after a second and a half: " + cleanLeft);
        assertTrue(drivenLeft > 0.5 && drivenLeft > 2 * cleanLeft,
                "the amplifier holds the note: " + drivenLeft + " against " + cleanLeft);
        float[] muted = synth.note(Instrument.DISTORTED_GUITAR, 40, 0.9, 0.08, Tone.NEUTRAL, 44_100, 1);
        assertTrue(muted.length < 44_100 / 2, "a short note is cut off: " + muted.length);
    }

    @Test
    void thePluckedStringsAreInTune() {
        Synth synth = new Synth();
        int sr = 44_100;
        for (Instrument in : List.of(Instrument.GUITAR, Instrument.HARP, Instrument.ELECTRIC_GUITAR,
                Instrument.DISTORTED_GUITAR, Instrument.ELECTRIC_BASS, Instrument.UPRIGHT_BASS)) {
            for (int pitch : new int[]{40, 52, 64, 69, 76, 81, 84, 88}) {
                float[] x = synth.note(in, pitch, 0.8, 1.5, Tone.NEUTRAL, sr, 1);
                double cents = centsOff(x, Dsp.hz(pitch), sr);
                assertEquals(0, cents, 5, in + " at " + pitch + " is off by " + cents + " cents");
            }
        }
    }

    /** How far a note is from its pitch, from the lag at which eight periods match themselves best. */
    private static double centsOff(float[] x, double f, double sr) {
        int from = (int) (0.15 * sr);
        int n = (int) (0.4 * sr);
        double period = sr / f;
        double best = Double.NEGATIVE_INFINITY;
        double bestLag = period;
        for (double lag = period * 0.95; lag <= period * 1.05; lag += 0.02) {
            double far = lag * 8;
            int whole = (int) far;
            double frac = far - whole;
            double sum = 0;
            for (int i = from; i < from + n; i++) sum += x[i] * (x[i + whole] * (1 - frac) + x[i + whole + 1] * frac);
            if (sum > best) {
                best = sum;
                bestLag = lag;
            }
        }
        return 1200 * Math.log(period / bestLag) / Math.log(2);
    }

    @Test
    void aSoftAttackStartsQuieter() {
        float[] hard = renderer.noteSample(Recipe.of(Style.LOFI, 1).withVoice(Part.MELODY,
                Voice.of(Instrument.STRINGS).withAttack(0)), Part.MELODY, 60, 1, AudioRenderer.SAMPLE_RATE);
        float[] soft = renderer.noteSample(Recipe.of(Style.LOFI, 1).withVoice(Part.MELODY,
                Voice.of(Instrument.STRINGS).withAttack(100)), Part.MELODY, 60, 1, AudioRenderer.SAMPLE_RATE);
        assertTrue(energy(soft, 2000) < energy(hard, 2000));
    }

    @Test
    void peaksForTheWaveform() {
        Audio a = new Audio(new float[]{0, 0.5f, -1, 0.25f}, new float[]{0, 0, 0, 0.75f}, 4);
        assertArrayEquals(new float[]{0.5f, 1}, a.peaks(2));
        assertEquals(1.0, a.seconds());
        assertArrayEquals(new short[]{0, 0, 16384, 0, -32767, 0, 8192, 24575}, a.toPcm16());
        assertFalse(a.peaks(0).length > 0);
    }

    private static double energy(float[] x, int frames) {
        double sum = 0;
        for (int i = 0; i < Math.min(frames, x.length); i++) sum += x[i] * x[i];
        return sum;
    }
}
