package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritersTest {
    static final Recipe RECIPE = Recipe.of(Style.LOFI, 4711);

    /** A second of noise and tones: easy to check, hard to compress. */
    static Audio testSound(int frames) {
        Random random = new Random(1);
        float[] l = new float[frames];
        float[] r = new float[frames];
        for (int i = 0; i < frames; i++) {
            l[i] = (float) (0.5 * Math.sin(i * 0.05) + 0.1 * (random.nextDouble() - 0.5));
            r[i] = (float) (0.4 * Math.sin(i * 0.031) + (i % 5000 == 0 ? 0.9 : 0));
        }
        return new Audio(l, r, AudioRenderer.SAMPLE_RATE);
    }

    @Test
    void wavHoldsTheSamplesAndTheRecipe() throws Exception {
        Audio audio = testSound(10_001);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WavWriter().write(audio, RecipeTag.of(RECIPE), out);
        byte[] bytes = out.toByteArray();
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(2, in.getFormat().getChannels());
            assertEquals(44_100f, in.getFormat().getSampleRate());
            byte[] pcm = in.readAllBytes();
            assertEquals(audio.frames() * 4, pcm.length);
            short[] expected = audio.toPcm16();
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8)), "sample " + i);
            }
        }
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("ICMT" ), "comment chunk");
        assertEquals(RECIPE, RecipeTag.parse(text.substring(text.indexOf(RecipeTag.PREFIX),
                text.indexOf('\0', text.indexOf(RecipeTag.PREFIX)))).orElseThrow());
    }

    @Test
    void flacDecodesToTheSameSamples() throws IOException {
        Audio audio = testSound(20_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FlacWriter().write(audio, RecipeTag.of(RECIPE), out);
        byte[] flac = out.toByteArray();
        assertEquals("fLaC", new String(flac, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(new String(flac, StandardCharsets.ISO_8859_1).contains("COMMENT=" + RecipeTag.PREFIX));
        assertTrue(flac.length < audio.frames() * 4, "smaller than WAV: " + flac.length);
        assertArrayEquals(audio.toPcm16(), FlacReader.decode(flac));
    }

    @Test
    void flacOfSilenceAndOfALongPiece() throws IOException {
        for (Audio audio : new Audio[]{new Audio(new float[5000], new float[5000], 44_100), testSound(1)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new FlacWriter().write(audio, null, out);
            assertArrayEquals(audio.toPcm16(), FlacReader.decode(out.toByteArray()));
        }
    }

    @Test
    void midiHoldsTheNotesTheInstrumentsAndTheRecipe() throws Exception {
        Song song = new Composer().compose(RECIPE, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MidiWriter().write(song, RecipeTag.of(RECIPE), out);
        Sequence seq = MidiSystem.getSequence(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1 + 8 + 2, seq.getTracks().length, "the two voices of the pad have tracks, the silent arpeggio has none");
        assertEquals(RECIPE, RecipeTag.parse(MidiWriter.readComment(seq).orElseThrow()).orElseThrow());
        int notes = 0;
        boolean drums = false;
        boolean flute = false;
        for (Track t : seq.getTracks()) {
            for (int i = 0; i < t.size(); i++) {
                if (t.get(i).getMessage() instanceof ShortMessage m) {
                    if (m.getCommand() == ShortMessage.NOTE_ON && m.getData2() > 0) {
                        notes++;
                        if (m.getChannel() == 9) drums = true;
                    }
                    if (m.getCommand() == ShortMessage.PROGRAM_CHANGE && m.getData1() == 73) flute = true;
                }
            }
        }
        assertEquals(song.notes().size(), notes);
        assertTrue(drums, "drums on channel 10");
        assertTrue(flute, "the Lo-Fi melody is a flute (program 74)");
    }

    @Test
    void modHasEightChannelsSamplesAndTheRecipe() throws IOException {
        Recipe classic = RECIPE.withPlaying(Part.PAD, false);
        Song song = new Composer().compose(classic, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ModWriter(new AudioRenderer()).write(song, RecipeTag.of(classic), out);
        byte[] mod = out.toByteArray();
        assertEquals("8CHN", new String(mod, 1080, 4, StandardCharsets.US_ASCII));
        assertEquals(classic, RecipeTag.parse(ModWriter.readComment(mod)).orElseThrow());
        int songLength = mod[950] & 0xFF;
        assertEquals((song.steps() + 63) / 64, songLength);
        int patterns = 0;
        for (int i = 0; i < 128; i++) patterns = Math.max(patterns, (mod[952 + i] & 0xFF) + 1);
        long sampleBytes = 0;
        int samples = 0;
        for (int i = 0; i < 31; i++) {
            int words = ((mod[20 + i * 30 + 22] & 0xFF) << 8) | (mod[20 + i * 30 + 23] & 0xFF);
            if (words > 0) samples++;
            sampleBytes += 2L * words;
        }
        assertTrue(samples >= 5, "drums and instruments as samples: " + samples);
        assertEquals(1084 + patterns * 64 * 8 * 4 + sampleBytes, mod.length);
        assertTrue(mod.length < 400_000, "a few hundred KB: " + mod.length);
    }

    @Test
    void modWithPadAndArpeggioHasTwelveChannels() throws IOException {
        Recipe wide = RECIPE.withPlaying(Part.PAD, true).withPlaying(Part.ARPEGGIO, true);
        Song song = new Composer().compose(wide, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ModWriter(new AudioRenderer()).write(song, RecipeTag.of(wide), out);
        byte[] mod = out.toByteArray();
        assertEquals("12CH", new String(mod, 1080, 4, StandardCharsets.US_ASCII));
        assertEquals(wide, RecipeTag.parse(ModWriter.readComment(mod)).orElseThrow());
        int patterns = 0;
        for (int i = 0; i < 128; i++) patterns = Math.max(patterns, (mod[952 + i] & 0xFF) + 1);
        long sampleBytes = 0;
        int samples = 0;
        for (int i = 0; i < 31; i++) {
            int words = ((mod[20 + i * 30 + 22] & 0xFF) << 8) | (mod[20 + i * 30 + 23] & 0xFF);
            if (words > 0) samples++;
            sampleBytes += 2L * words;
        }
        assertEquals(1084 + patterns * 64 * 12 * 4 + sampleBytes, mod.length);
        assertTrue(samples >= 7, "pad and arpeggio have samples of their own: " + samples);
        // Every row of the first pattern: the pad's two notes start in channels 9 and 10 (counted from 1).
        int first = 1084 + (mod[952] & 0xFF) * 64 * 12 * 4;
        boolean pad = false;
        for (int row = 0; row < 64; row++) {
            int o = first + (row * 12 + Song.PAD) * 4;
            if (((mod[o] & 0x0F) << 8 | (mod[o + 1] & 0xFF)) != 0) pad = true;
            int empty = first + (row * 12 + 11) * 4;
            assertEquals(0, mod[empty] | mod[empty + 1] | mod[empty + 2] | mod[empty + 3], "the twelfth channel is empty");
        }
        assertTrue(pad, "the pad plays in the first pattern");
    }

    @Test
    void aLongCommentIsLeftOutOfTheModule() {
        assertTrue(ModWriter.commentNames("x".repeat(1000), 10).isEmpty());
        assertEquals(2, ModWriter.commentNames("y".repeat(30), 10).size());
    }

    @Test
    void recipeTags() {
        assertEquals(RECIPE, RecipeTag.parse("before " + RecipeTag.of(RECIPE)).orElseThrow());
        assertTrue(RecipeTag.parse("just a comment").isEmpty());
        assertTrue(RecipeTag.parse(null).isEmpty());
        assertTrue(RecipeTag.parse(RecipeTag.PREFIX + "style=none").isEmpty());
    }
}
