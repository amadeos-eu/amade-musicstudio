package eu.amadeos.apps.musicstudio.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTest {
    @Test
    void aNewRecipeTakesTheStyleDefaults() {
        Recipe r = Recipe.of(Style.LOFI, 4711);
        assertEquals(Style.LOFI.bpm(), r.bpm());
        assertEquals(Recipe.DEFAULT_LENGTH, r.lengthSeconds());
        assertEquals(-1, r.key());
        assertEquals(Style.LOFI.defaultInstrument(Part.MELODY), r.melody().instrument());
        assertEquals(Style.LOFI.defaultKit(), r.kit());
        assertTrue(r.oneSeed());
        for (Part p : Part.values()) assertEquals(4711, r.seed(p));
        assertEquals("lofi-4711", r.fileName());
    }

    @Test
    void textAndLineReadBackTheSameRecipe() {
        Recipe r = Recipe.of(Style.CHIPTUNE, 99).withPartSeed(Part.DRUMS, 1234)
                .withVoice(Part.MELODY, Voice.of(Instrument.BELLS).withOctave(1).withAttack(20).withRelease(80))
                .withVolume(Part.BASS, 60).with(b -> {
                    b.key = 9;
                    b.bpm = 150;
                    b.crackle = 33;
                    b.variety = 77;
                    b.harmony = 88;
                    b.kit = Kit.ELECTRONIC;
                    b.drumVolume = 90;
                });
        assertEquals(r, Recipe.parse(r.toText()));
        assertEquals(r, Recipe.parse(r.toLine()));
        assertTrue(r.toLine().contains("seed.drums=1234"), r.toLine());
        assertTrue(r.toLine().contains("variety=77"), r.toLine());
        assertTrue(r.toLine().contains("harmony=88"), r.toLine());
        assertFalse(r.toLine().contains("seed.melody"), "only seeds that differ are written");
        assertTrue(r.toText().startsWith(Recipe.HEADER));
    }

    @Test
    void missingOrWrongValuesTakeTheDefaults() {
        Recipe r = Recipe.parse("style=piano; seed=5; bpm=fast; melody=kazoo,9,9,9,9; drums=nothing");
        Recipe d = Recipe.of(Style.PIANO, 5);
        assertEquals(d.bpm(), r.bpm());
        assertEquals(d.melody().instrument(), r.melody().instrument(), "unknown instrument keeps the style's");
        assertEquals(Voice.MAX_OCTAVE, r.melody().octave(), "numbers are kept in their range");
        assertEquals(d.kit(), r.kit());
    }

    @Test
    void notARecipe() {
        assertThrows(IllegalArgumentException.class, () -> Recipe.parse("hello"));
        assertThrows(IllegalArgumentException.class, () -> Recipe.parse("style=jazz; seed=1"));
        assertThrows(IllegalArgumentException.class, () -> Recipe.parse("style=lofi; seed=-3"));
        assertThrows(IllegalArgumentException.class, () -> Recipe.of(Style.LOFI, Recipe.MAX_SEED + 1));
    }

    @Test
    void valuesAreKeptInTheirRange() {
        Recipe r = Recipe.of(Style.LOFI, 1).with(b -> {
            b.bpm = 999;
            b.hardness = -5;
            b.swing = 300;
            b.lengthSeconds = 2;
            b.key = 30;
        });
        assertEquals(Recipe.MAX_BPM, r.bpm());
        assertEquals(0, r.hardness());
        assertEquals(100, r.swing());
        assertEquals(Recipe.MIN_LENGTH, r.lengthSeconds());
        assertEquals(11, r.key());
    }

    @Test
    void anotherStyleKeepsSeedsLengthAndKey() {
        Recipe r = Recipe.of(Style.LOFI, 10).withPartSeed(Part.BASS, 77).with(b -> {
            b.lengthSeconds = 120;
            b.key = 3;
            b.hardness = 99;
        });
        Recipe other = r.withStyle(Style.ELECTRO);
        assertEquals(Style.ELECTRO, other.style());
        assertEquals(77, other.bassSeed());
        assertEquals(120, other.lengthSeconds());
        assertEquals(3, other.key());
        assertEquals(Style.ELECTRO.defaultHardness(), other.hardness());
        assertEquals(Style.ELECTRO.defaultInstrument(Part.MELODY), other.melody().instrument());
    }

    @Test
    void aNewPieceKeepsTheKeptParts() {
        Recipe r = Recipe.of(Style.LOFI, 10);
        Set<Part> kept = EnumSet.of(Part.BASS, Part.DRUMS);
        Recipe next = r.newPiece(20, kept);
        assertEquals(20, next.seed());
        assertEquals(20, next.melodySeed());
        assertEquals(20, next.chordSeed());
        assertEquals(10, next.bassSeed());
        assertEquals(10, next.drumSeed());
        assertFalse(next.oneSeed());
        assertTrue(next.withSeed(30).oneSeed(), "a new main seed reaches every part");
    }

    @Test
    void padAndArpeggioAreExtras() {
        Recipe lofi = Recipe.of(Style.LOFI, 1);
        assertTrue(lofi.plays(Part.PAD));
        assertFalse(lofi.plays(Part.ARPEGGIO));
        assertTrue(lofi.plays(Part.MELODY) && lofi.plays(Part.DRUMS));
        assertFalse(Recipe.of(Style.PIANO, 1).plays(Part.DRUMS), "no kit, no drums");
        assertThrows(IllegalArgumentException.class, () -> lofi.withPlaying(Part.BASS, false));
        Recipe r = lofi.withPlaying(Part.PAD, false).withPlaying(Part.ARPEGGIO, true)
                .withVoice(Part.PAD, Voice.of(Instrument.CHOIR_AAH).withVolume(40).withSweep(80, 0))
                .withVoice(Part.ARPEGGIO, Voice.of(Instrument.HARP).withOctave(1).withTremolo(35, 6))
                .withPartSeed(Part.ARPEGGIO, 55);
        assertEquals(r, Recipe.parse(r.toText()));
        assertEquals(r, Recipe.parse(r.toLine()));
        assertTrue(r.toLine().contains("pad=off choir-aah,0,50,50,40,80,0,0,5"), r.toLine());
        assertTrue(r.toLine().contains("arpeggio=harp,1,50,50,75,0,1,35,6"), r.toLine());
        assertTrue(r.toLine().contains("seed.arpeggio=55"), r.toLine());
        assertTrue(lofi.toLine().contains("melody=flute,0,50,50,75;"), "a voice that does not move stays short");
        Recipe next = r.newPiece(9, EnumSet.of(Part.ARPEGGIO));
        assertEquals(55, next.arpeggioSeed());
        assertEquals(9, next.padSeed());
        assertEquals(55, r.withStyle(Style.ELECTRO).arpeggioSeed());
    }

    @Test
    void aRecipeFromBeforePadAndArpeggioHasThemAsItsStyle() {
        Recipe old = Recipe.parse("style=electro; seed=7; melody=flute,0,50,50,75; drums=electronic,75");
        assertEquals(Recipe.of(Style.ELECTRO, 7).pad(), old.pad());
        assertTrue(old.plays(Part.PAD) && old.plays(Part.ARPEGGIO));
        assertEquals(0, old.melody().sweep());
        assertEquals(Voice.DEFAULT_TREMOLO_RATE, old.melody().tremoloRate());
        assertFalse(Recipe.parse("style=electro; seed=7; pad=off").plays(Part.PAD));
        assertEquals(Recipe.of(Style.ELECTRO, 7).pad(), Recipe.parse("style=electro; seed=7; pad=off").pad());
    }

    @Test
    void movementsOfAVoiceStayInTheirRange() {
        Voice v = Voice.of(Instrument.STRINGS).withSweep(300, 99).withTremolo(-4, -1);
        assertEquals(100, v.sweep());
        assertEquals(Voice.RATES.size() - 1, v.sweepRate());
        assertEquals(0, v.tremolo());
        assertEquals(0, v.tremoloRate());
        assertEquals(128, v.tremoloSteps());
        assertEquals(2, v.sweepSteps());
        assertEquals("movement.rate.16", Voice.rateKey(3));
    }

    @Test
    void theDrumsHaveAKitNotAVoice() {
        Recipe r = Recipe.of(Style.LOFI, 1);
        assertThrows(IllegalArgumentException.class, () -> r.voice(Part.DRUMS));
        assertEquals(40, r.withVolume(Part.DRUMS, 40).volume(Part.DRUMS));
        assertEquals(40, r.withVolume(Part.MELODY, 40).volume(Part.MELODY));
    }
}
