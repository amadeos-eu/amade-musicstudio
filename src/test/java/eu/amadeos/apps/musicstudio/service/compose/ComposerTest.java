package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposerTest {
    private final Composer composer = new Composer();

    @ParameterizedTest
    @EnumSource(Style.class)
    void everyStyleGivesAPieceWithAllItsParts(Style style) {
        Song song = composer.compose(Recipe.of(style, 4711), false);
        assertFalse(song.notes().isEmpty());
        assertEquals(Section.Kind.INTRO, song.sections().getFirst().kind());
        assertEquals(Section.Kind.OUTRO, song.sections().getLast().kind());
        assertEquals(song.bars(), song.sections().getLast().endBar());
        for (Part p : Part.values()) {
            if (p == Part.DRUMS && style.defaultKit() == Kit.NONE) continue;
            assertTrue(song.notes().stream().anyMatch(n -> Song.part(n.channel()) == p), style + " " + p);
        }
        for (Note n : song.notes()) {
            assertTrue(n.step() >= 0 && n.step() < song.steps(), "note within the piece: " + n);
            assertTrue(n.velocity() > 0 && n.velocity() <= 1, n.toString());
            assertTrue(n.pitch() >= 0 && n.pitch() <= 127, n.toString());
            assertEquals(n.isDrum(), Song.part(n.channel()) == Part.DRUMS);
        }
        double seconds = song.seconds();
        assertTrue(seconds > 40 && seconds < 85, style + " lasts " + seconds + " s");
    }

    @Test
    void theSameRecipeGivesTheSameNotes() {
        Recipe r = Recipe.of(Style.LOFI, 4711);
        Song a = composer.compose(r, false);
        Song b = composer.compose(r, false);
        assertEquals(a.notes(), b.notes());
        assertEquals(a.sections(), b.sections());
        assertTrue(java.util.Arrays.deepEquals(a.chordsPerBar(), b.chordsPerBar()));
        assertNotEquals(composer.compose(r, false).notes(), composer.compose(r.withSeed(4712), false).notes());
    }

    @Test
    void anotherMelodySeedChangesOnlyTheMelody() {
        Recipe r = Recipe.of(Style.LOFI, 4711);
        Song a = composer.compose(r, false);
        Song b = composer.compose(r.withPartSeed(Part.MELODY, 99), false);
        assertEquals(notes(a, Part.DRUMS), notes(b, Part.DRUMS));
        assertEquals(notes(a, Part.BASS), notes(b, Part.BASS));
        assertEquals(notes(a, Part.CHORDS), notes(b, Part.CHORDS));
        assertNotEquals(notes(a, Part.MELODY), notes(b, Part.MELODY));
    }

    @Test
    void aFixedKeyIsUsed() {
        Recipe r = Recipe.of(Style.PIANO, 4711);
        for (int key = 0; key < 12; key++) {
            int k = key;
            assertEquals(key, composer.compose(r.with(b -> b.key = k), false).keyNote());
        }
    }

    @Test
    void aLoopHasNoIntroAndOutro() {
        Song loop = composer.compose(Recipe.of(Style.ELECTRO, 3), true);
        assertTrue(loop.loop());
        assertTrue(loop.sections().stream().noneMatch(s -> s.kind() == Section.Kind.INTRO
                || s.kind() == Section.Kind.OUTRO), loop.sections().toString());
    }

    @Test
    void theOctaveSliderMovesTheMelody() {
        Recipe r = Recipe.of(Style.PIANO, 4711);
        Voice up = r.melody().withOctave(1);
        List<Note> low = notes(composer.compose(r, false), Part.MELODY);
        List<Note> high = notes(composer.compose(r.withVoice(Part.MELODY, up), false), Part.MELODY);
        assertEquals(low.size(), high.size());
        for (int i = 0; i < low.size(); i++) assertEquals(low.get(i).pitch() + 12, high.get(i).pitch());
    }

    @Test
    void moreEnergyGivesMoreNotes() {
        Recipe r = Recipe.of(Style.LOFI, 4711);
        int sparse = composer.compose(r.with(b -> b.energy = 0), false).notes().size();
        int busy = composer.compose(r.with(b -> b.energy = 100), false).notes().size();
        assertTrue(busy > sparse, sparse + " vs " + busy);
    }

    @Test
    void noDrumsWithoutAKit() {
        Song song = composer.compose(Recipe.of(Style.LOFI, 1).with(b -> b.kit = Kit.NONE), false);
        assertTrue(notes(song, Part.DRUMS).isEmpty());
    }

    private static List<Note> notes(Song song, Part part) {
        return song.notes().stream().filter(n -> Song.part(n.channel()) == part).toList();
    }
}
