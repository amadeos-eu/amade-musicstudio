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
        AudioRenderer.move(x, Voice.of(Instrument.STRINGS).withTremolo(100, 3), stepSeconds, 8000, sr);
        assertEquals(1, x[100], 0.02, "full at the start of the bar");
        assertEquals(0.1, x[sr], 0.02, "lowest in the middle of the bar");
        assertEquals(1, x[2 * sr - 1], 0.02, "full again at its end");
        assertEquals(0.1, x[3 * sr], 0.02, "and so on in the next bar");
        float[] still = new float[sr];
        java.util.Arrays.fill(still, 0.5f);
        AudioRenderer.move(still, Voice.of(Instrument.STRINGS), stepSeconds, 8000, sr);
        for (float v : still) assertEquals(0.5f, v, "a voice that does not move is left alone");
    }

    @Test
    void theSweepClosesAndOpensTheFilter() {
        int sr = 44_100;
        double stepSeconds = 0.125;
        float[] x = new float[4 * sr];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(2 * Math.PI * 3000 * i / sr);
        AudioRenderer.move(x, Voice.of(Instrument.WARM_PAD).withSweep(100, 3), stepSeconds, 12_000, sr);
        double open = rms(x, 2 * sr - 2000, 1500);
        double closed = rms(x, sr - 750, 1500);
        assertTrue(open > 0.5, "3 kHz passes when the filter is open: " + open);
        assertTrue(closed < open / 10, "and hardly when it is closed: " + closed + " against " + open);
        assertEquals(closed, rms(x, 3 * sr - 750, 1500), closed / 2, "the same in the next bar");
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
