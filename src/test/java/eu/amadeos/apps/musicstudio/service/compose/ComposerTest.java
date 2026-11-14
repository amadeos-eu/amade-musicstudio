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
        Recipe r = Recipe.of(Style.LOFI, 4711).with(x -> x.variety = 0);
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

    @Test
    void withoutVarietyEveryPartPlaysThroughout() {
        for (Style style : Style.values()) {
            Song song = composer.compose(long_(style, 4711, 0), false);
            for (Part p : Part.values()) {
                if (p == Part.DRUMS) continue;
                assertTrue(longestRest(song, p) <= 2, style + " " + p + " rests " + longestRest(song, p) + " bars");
            }
        }
    }

    @Test
    void withVarietyPartsRestAndComeBack() {
        int rests = 0;
        for (Style style : Style.values()) {
            Song steady = composer.compose(long_(style, 4711, 0), false);
            Song changing = composer.compose(long_(style, 4711, 100), false);
            assertEquals(notes(steady, Part.CHORDS), notes(changing, Part.CHORDS), "the chords hold the piece together");
            for (Part p : List.of(Part.MELODY, Part.BASS)) {
                List<String> all = notes(steady, p).stream().map(ComposerTest::where).toList();
                List<Note> left = notes(changing, p);
                assertTrue(left.size() < all.size(), style + " " + p);
                for (Note n : left) assertTrue(all.contains(where(n)), "no new notes: " + n);
                if (longestRest(changing, p) >= 4) rests++;
            }
            Section first = changing.sections().get(1);
            Section last = changing.sections().get(changing.sections().size() - 2);
            for (Part p : Part.values()) {
                for (int bar = last.endBar() - 4; bar < last.endBar(); bar++) {
                    assertEquals(inBar(steady, p, bar).size(), inBar(changing, p, bar).size(),
                            style + ": the last four bars before the outro are whole, " + p + " bar " + bar);
                }
            }
            assertEquals(Section.Kind.A, first.kind());
        }
        assertTrue(rests >= 12, "rests of four bars or more: " + rests);
    }

    @Test
    void notesEndWhereTheirPartStartsToRest() {
        for (Style style : Style.values()) {
            Song song = composer.compose(long_(style, 4711, 100), false);
            Arrangement plan = Arrangement.of(song.recipe(), song.sections(), song.bars(), false);
            for (Note n : song.notes()) {
                if (n.isDrum()) continue;
                Part p = Song.part(n.channel());
                int last = (int) Math.ceil((n.step() + n.length()) / Song.STEPS_PER_BAR) - 1;
                for (int bar = (int) (n.step() / Song.STEPS_PER_BAR); bar <= last; bar++) {
                    assertTrue(plan.plays(p, bar), style + ": sounds into a rest in bar " + bar + ": " + n);
                }
            }
        }
    }

    @Test
    void theDrumsRollBeforeTheyComeBackAndCrashWhenOthersComeIn() {
        Song song = composer.compose(long_(Style.LOFI, 4711, 100), false);
        Arrangement plan = Arrangement.of(song.recipe(), song.sections(), song.bars(), false);
        int rolls = 0;
        int crashes = 0;
        int stops = 0;
        for (int bar = 1; bar < song.bars(); bar++) {
            int start = bar * Song.STEPS_PER_BAR;
            List<Note> before = inBar(song, Part.DRUMS, bar - 1);
            List<Note> here = inBar(song, Part.DRUMS, bar);
            if (plan.enters(Part.DRUMS, bar)) {
                rolls++;
                assertTrue(before.size() >= 4, "a roll: " + before);
                for (Note n : before) {
                    assertEquals(Drum.SNARE, n.drum());
                    assertTrue(n.step() >= start - 8, "in the second half of the bar");
                }
                assertTrue(before.getLast().velocity() > before.getFirst().velocity() + 0.2, "louder and louder");
            } else if (plan.leaves(Part.DRUMS, bar)) {
                stops++;
                assertEquals(List.of(Drum.KICK, Drum.OPEN_HAT), here.stream().map(Note::drum).toList());
                for (Note n : here) assertEquals(start, n.step());
            } else if (!plan.plays(Part.DRUMS, bar)) {
                assertTrue(here.isEmpty() || plan.enters(Part.DRUMS, bar + 1), "silent in bar " + bar);
            }
            if (plan.plays(Part.DRUMS, bar) && (plan.enters(Part.MELODY, bar) || plan.enters(Part.BASS, bar)
                    || plan.enters(Part.DRUMS, bar))) {
                crashes++;
                assertTrue(here.stream().anyMatch(n -> n.drum() == Drum.OPEN_HAT && n.step() == start),
                        "a crash in bar " + bar);
            }
        }
        assertTrue(rolls >= 2, "rolls: " + rolls);
        assertTrue(stops >= 2, "stops: " + stops);
        assertTrue(crashes > rolls, "crashes: " + crashes);
        List<String> hits = song.notes().stream().filter(Note::isDrum).map(n -> n.drum() + "@" + n.step()).toList();
        assertEquals(hits.size(), hits.stream().distinct().count(), "no drum is hit twice at once");
    }

    @Test
    void theDrumsDoNotAnswerWithoutAKit() {
        Song song = composer.compose(long_(Style.LOFI, 4711, 100).with(b -> b.kit = Kit.NONE), false);
        assertTrue(notes(song, Part.DRUMS).isEmpty());
    }

    @Test
    void aKeptPartRestsInTheSameBars() {
        Recipe r = long_(Style.ELECTRO, 4711, 100);
        Song a = composer.compose(r, false);
        Song b = composer.compose(r.withPartSeed(Part.MELODY, 99).withPartSeed(Part.DRUMS, 98), false);
        assertEquals(notes(a, Part.BASS), notes(b, Part.BASS));
        assertEquals(notes(a, Part.CHORDS), notes(b, Part.CHORDS));
        assertNotEquals(notes(a, Part.MELODY), notes(b, Part.MELODY));
    }

    @Test
    void aLoopStartsWithAllParts() {
        for (long seed = 1; seed <= 20; seed++) {
            Song loop = composer.compose(long_(Style.ELECTRO, seed, 100), true);
            for (Part p : Part.values()) {
                for (int bar = 0; bar < 4; bar++) assertFalse(inBar(loop, p, bar).isEmpty(), p + " in bar " + bar);
            }
        }
    }

    private static Recipe long_(Style style, long seed, int variety) {
        return Recipe.of(style, seed).with(b -> {
            b.lengthSeconds = 180;
            b.variety = variety;
        });
    }

    private static String where(Note n) {
        return n.step() + "/" + n.channel() + "/" + n.pitch();
    }

    private static List<Note> inBar(Song song, Part part, int bar) {
        return song.notes().stream().filter(n -> Song.part(n.channel()) == part
                && (int) (n.step() / Song.STEPS_PER_BAR) == bar).toList();
    }

    /** The most bars in a row between intro and outro in which no note of the part starts. */
    private static int longestRest(Song song, Part part) {
        int longest = 0;
        int run = 0;
        for (int bar = song.sections().get(1).startBar(); bar < song.sections().getLast().startBar(); bar++) {
            run = inBar(song, part, bar).isEmpty() ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static List<Note> notes(Song song, Part part) {
        return song.notes().stream().filter(n -> Song.part(n.channel()) == part).toList();
    }
}
