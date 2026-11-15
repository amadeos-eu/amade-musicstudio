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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposerTest {
    private final Composer composer = new Composer();

    @ParameterizedTest
    @EnumSource(Style.class)
    void everyStyleGivesAPieceWithAllItsParts(Style style) {
        Recipe recipe = Recipe.of(style, 4711);
        Song song = composer.compose(recipe, false);
        assertFalse(song.notes().isEmpty());
        assertEquals(Section.Kind.INTRO, song.sections().getFirst().kind());
        assertEquals(Section.Kind.OUTRO, song.sections().getLast().kind());
        assertEquals(song.bars(), song.sections().getLast().endBar());
        for (Part p : Part.values()) {
            assertEquals(recipe.plays(p), song.notes().stream().anyMatch(n -> Song.part(n.channel()) == p), style + " " + p);
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
                if (p == Part.DRUMS || !song.recipe().plays(p)) continue;
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
    void thePadHoldsTwoNotesOfEveryChord() {
        for (Style style : Style.values()) {
            Recipe r = long_(style, 4711, 0).withPlaying(Part.PAD, true);
            Song song = composer.compose(r, false);
            List<Note> pad = notes(song, Part.PAD);
            assertEquals(0, pad.size() % 2, "two voices");
            for (Note n : pad) {
                int bar = (int) (n.step() / Song.STEPS_PER_BAR);
                assertEquals(0, n.step() % Song.STEPS_PER_BAR, "starts with a bar");
                assertTrue(n.length() == 16 || n.length() == 32, "one or two bars: " + n);
                int pc = Math.floorMod(n.pitch() - 12 * r.pad().octave(), 12);
                assertTrue(java.util.Arrays.stream(song.chordsPerBar()[bar]).anyMatch(c -> c == pc),
                        style + ": a note of the chord in bar " + bar + ": " + n);
                assertEquals(r.pad().instrument(), n.instrument());
            }
            for (int bar = 0; bar < song.bars(); bar++) {
                int b = bar;
                assertTrue(pad.stream().anyMatch(n -> n.step() <= b * 16 && n.step() + n.length() > b * 16),
                        style + ": the pad sounds in bar " + bar);
            }
        }
    }

    @Test
    void theArpeggioPlaysTheChordNoteByNote() {
        for (Style style : Style.values()) {
            Recipe r = long_(style, 4711, 0).withPlaying(Part.ARPEGGIO, true);
            Song song = composer.compose(r, false);
            List<Note> arp = notes(song, Part.ARPEGGIO);
            assertFalse(arp.isEmpty());
            double end = 0;
            java.util.Set<Integer> pitches = new java.util.HashSet<>();
            for (Note n : arp) {
                int bar = (int) (n.step() / Song.STEPS_PER_BAR);
                assertEquals(Song.ARPEGGIO, n.channel());
                assertTrue(n.step() >= end, "one note at a time: " + n);
                end = n.step() + n.length();
                int pc = Math.floorMod(n.pitch(), 12);
                assertTrue(java.util.Arrays.stream(song.chordsPerBar()[bar]).anyMatch(c -> c == pc),
                        style + ": a note of the chord in bar " + bar + ": " + n);
                assertTrue(n.pitch() >= 60 && n.pitch() <= 96, n.toString());
                pitches.add(n.pitch());
            }
            assertTrue(pitches.size() >= 8, style + ": the figure moves, " + pitches.size() + " pitches");
            assertTrue(inBar(song, Part.ARPEGGIO, 0).isEmpty(), "the intro starts without it");
            assertTrue(inBar(song, Part.ARPEGGIO, song.bars() - 1).isEmpty(), "the outro ends without it");
        }
    }

    @Test
    void euclideanRhythmsSpreadTheirHitsEvenly() {
        assertEquals("10010010", text(Composer.euclid(3, 8, 0)), "the tresillo");
        assertEquals("10101101", text(Composer.euclid(5, 8, 0)));
        assertEquals("01001001", text(Composer.euclid(3, 8, 1)), "turned by one step");
        assertEquals("1000100010001000", text(Composer.euclid(4, 16, 0)));
        assertEquals("1111", text(Composer.euclid(4, 4, 2)));
        for (int k = 1; k <= 16; k++) {
            for (int turn = 0; turn < 16; turn++) {
                int[] rhythm = Composer.euclid(k, 16, turn);
                assertEquals(k, java.util.Arrays.stream(rhythm).sum());
                for (int which = 0; which < k; which++) {
                    int[] turned = Composer.startingWithANote(rhythm, which);
                    assertEquals(1, turned[0], "starts with a note");
                    assertEquals(k, java.util.Arrays.stream(turned).sum());
                }
            }
        }
    }

    @Test
    void theArpeggioOfASparsePieceLeavesStepsOut() {
        Recipe busy = long_(Style.ELECTRO, 4711, 0).with(b -> b.energy = 100);
        Recipe sparse = busy.with(b -> b.energy = 45);
        Song full = composer.compose(busy, false);
        Song thin = composer.compose(sparse, false);
        int bar = full.sections().get(1).startBar();
        assertEquals(16, inBar(full, Part.ARPEGGIO, bar).size(), "every sixteenth");
        List<Note> notes = inBar(thin, Part.ARPEGGIO, bar);
        assertEquals(12, notes.size(), "twelve of sixteen");
        assertEquals(bar * 16, notes.getFirst().step());
        double end = notes.getFirst().step();
        for (Note n : notes) {
            assertEquals(end, n.step(), "a note lasts until the next");
            end = n.step() + n.length();
        }
        assertEquals(5, inBar(composer.compose(sparse.with(b -> b.energy = 30), false), Part.ARPEGGIO, bar).size(),
                "five of eight eighths when the piece is sparse");
    }

    @Test
    void aBusyPieceGetsOneMoreDrumSpreadOverTheBar() {
        Recipe r = long_(Style.LOFI, 4711, 0);
        Song quiet = composer.compose(r.with(b -> b.energy = 20), false);
        Song busy = composer.compose(r.with(b -> b.energy = 100), false);
        assertTrue(notes(quiet, Part.DRUMS).stream().noneMatch(n -> n.drum() == Drum.SHAKER));
        Section a = busy.sections().get(1);
        List<Note> shaker = inBar(busy, Part.DRUMS, a.startBar() + 1).stream().filter(n -> n.drum() == Drum.SHAKER).toList();
        assertFalse(shaker.isEmpty());
        assertTrue(shaker.size() <= 7, shaker.toString());
        java.util.Set<Double> places = new java.util.TreeSet<>();
        for (int bar = a.startBar(); bar < a.endBar(); bar++) {
            inBar(busy, Part.DRUMS, bar).stream().filter(n -> n.drum() == Drum.SHAKER).forEach(n -> places.add(n.step() % 16));
        }
        assertTrue(places.size() <= 7, "the same three, five or seven places in every bar of the section: " + places);
        for (Note s : shaker) {
            assertTrue(notes(busy, Part.DRUMS).stream().noneMatch(n -> n != s && n.step() == s.step()
                    && n.channel() == s.channel()), "never where its channel already has a hit: " + s);
        }
        assertTrue(inBar(busy, Part.DRUMS, 0).stream().noneMatch(n -> n.drum() == Drum.SHAKER) || a.startBar() == 0,
                "not in the intro");
    }

    @Test
    void withoutHarmonyAllChordsArePlain() {
        for (Style style : Style.values()) {
            Song song = composer.compose(long_(style, 4711, 0).with(b -> b.harmony = 0), false);
            int[] steps = style.chordScale().steps();
            for (int[] chord : song.chordsPerBar()) {
                assertEquals(style.sevenths() ? 4 : 3, chord.length);
                int degree = -1;
                for (int d = 0; d < steps.length; d++) if ((song.keyNote() + steps[d]) % 12 == chord[0]) degree = d;
                assertTrue(degree >= 0, "the root is a step of the scale");
                for (int i = 0; i < chord.length; i++) {
                    assertEquals((song.keyNote() + steps[(degree + 2 * i) % steps.length]) % 12, chord[i], "thirds of the scale");
                }
            }
            for (int bar = 0; bar < song.bars(); bar++) {
                List<Note> bass = inBar(song, Part.BASS, bar);
                if (!bass.isEmpty()) {
                    assertEquals(song.chordsPerBar()[bar][0], Math.floorMod(bass.getFirst().pitch() - 12 * song.recipe().bass().octave(), 12),
                            style + ": the bass starts bar " + bar + " on the root");
                }
            }
        }
    }

    @Test
    void harmonyColoursTheChordsWithNotesOfTheScales() {
        int suspended = 0;
        int widened = 0;
        int turned = 0;
        int dominants = 0;
        int coloured = 0;
        for (Style style : Style.values()) {
            for (long seed = 1; seed <= 12; seed++) {
                Recipe r = long_(style, seed, 0).with(b -> b.key = 0);
                Song plain = composer.compose(r.with(b -> b.harmony = 0), false);
                Song half = composer.compose(r.with(b -> b.harmony = 50), false);
                Song full = composer.compose(r.with(b -> b.harmony = 100), false);
                java.util.Set<Integer> allowed = new java.util.HashSet<>();
                for (int s : style.chordScale().steps()) allowed.add(s);
                for (int s : style.melodyScale().steps()) allowed.add(s);
                for (int bar = 0; bar < full.bars(); bar++) {
                    int[] before = plain.chordsPerBar()[bar];
                    int[] chord = full.chordsPerBar()[bar];
                    for (int pc : chord) assertTrue(allowed.contains(pc), style + " seed " + seed + " bar " + bar + ": " + pc);
                    if (!java.util.Arrays.equals(before, half.chordsPerBar()[bar])) {
                        assertTrue(java.util.Arrays.equals(half.chordsPerBar()[bar], chord),
                                "more harmony keeps the colours it had: " + style + " seed " + seed + " bar " + bar);
                    }
                    if (java.util.Arrays.equals(before, chord)) continue;
                    coloured++;
                    int third = Math.floorMod(chord[1] - chord[0], 12);
                    if (third == 5 || third == 2) suspended++;
                    if (chord.length > before.length) widened++;
                    if (chord.length == 4 && third == 4 && Math.floorMod(chord[3] - chord[0], 12) == 10) dominants++;
                }
                for (int bar = 0; bar < full.bars(); bar++) {
                    List<Note> bass = inBar(full, Part.BASS, bar);
                    int[] chord = full.chordsPerBar()[bar];
                    if (!bass.isEmpty() && Math.floorMod(bass.getFirst().pitch() - 12 * r.bass().octave(), 12) == chord[1]
                            && chord[1] != chord[0]) {
                        turned++;
                    }
                }
                assertArrayEquals(plain.chordsPerBar()[plain.bars() - 1], full.chordsPerBar()[full.bars() - 1],
                        "the piece ends on the key's own chord");
                assertEquals(notes(plain, Part.MELODY).size(), notes(full, Part.MELODY).size(), "the melody keeps its rhythm");
                assertEquals(notes(plain, Part.DRUMS), notes(full, Part.DRUMS));
            }
        }
        assertTrue(coloured > 500, "coloured bars: " + coloured);
        assertTrue(suspended > 100, "suspended: " + suspended);
        assertTrue(widened > 100, "with a seventh, sixth or ninth: " + widened);
        assertTrue(turned > 50, "with the third in the bass: " + turned);
        assertTrue(dominants > 20, "dominant sevenths: " + dominants);
    }

    @Test
    void aSuspendedChordHeldForTwoBarsComesToRest() {
        int resolved = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Song song = composer.compose(long_(Style.AMBIENT, seed, 0).with(b -> b.harmony = 100), false);
            Section a = song.sections().get(1);
            for (int bar = a.startBar(); bar + 1 < a.endBar(); bar += 2) {
                int[] first = song.chordsPerBar()[bar];
                int[] second = song.chordsPerBar()[bar + 1];
                assertEquals(first[0], second[0], "the same root for two bars");
                if (Math.floorMod(first[1] - first[0], 12) == 5) {
                    int third = Math.floorMod(second[1] - second[0], 12);
                    assertTrue(third == 3 || third == 4, "the fourth falls to the third");
                    resolved++;
                }
            }
        }
        assertTrue(resolved > 5, "resolved: " + resolved);
    }

    private static String text(int[] rhythm) {
        StringBuilder b = new StringBuilder();
        for (int v : rhythm) b.append(v);
        return b.toString();
    }

    @Test
    void padAndArpeggioLeaveTheOtherPartsAsTheyAre() {
        Recipe r = long_(Style.ELECTRO, 4711, 100);
        Song with = composer.compose(r, false);
        Song without = composer.compose(r.withPlaying(Part.PAD, false).withPlaying(Part.ARPEGGIO, false), false);
        assertFalse(notes(with, Part.PAD).isEmpty());
        assertFalse(notes(with, Part.ARPEGGIO).isEmpty());
        assertTrue(notes(without, Part.PAD).isEmpty());
        assertTrue(notes(without, Part.ARPEGGIO).isEmpty());
        for (Part p : List.of(Part.MELODY, Part.CHORDS, Part.BASS, Part.DRUMS)) {
            assertEquals(notes(with, p), notes(without, p), p.toString());
        }
        assertTrue(longestRest(with, Part.PAD) >= 4 || longestRest(with, Part.ARPEGGIO) >= 4,
                "with variety the extras rest too");
        Song other = composer.compose(r.withPartSeed(Part.ARPEGGIO, 5), false);
        assertEquals(notes(with, Part.PAD), notes(other, Part.PAD));
        assertNotEquals(notes(with, Part.ARPEGGIO), notes(other, Part.ARPEGGIO));
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
