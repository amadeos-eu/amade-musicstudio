package eu.amadeos.apps.musicstudio.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAndVariationsTest {
    @Test
    void aVoiceReadsBackItsText() {
        Voice v = Voice.of(Instrument.WARM_PAD).withOctave(-1).withAttack(80).withRelease(20).withVolume(60);
        assertEquals("warm-pad,-1,80,20,60", v.toText());
        assertEquals(v, Voice.parse(v.toText(), Voice.of(Instrument.PIANO)));
        assertEquals(Voice.of(Instrument.PIANO), Voice.parse("nonsense", Voice.of(Instrument.PIANO)));
        assertEquals(Voice.MAX_OCTAVE, Voice.of(Instrument.PIANO).withOctave(9).octave());
    }

    @Test
    void everyInstrumentAndKitHasAnIdThatReadsBack() {
        for (Instrument i : Instrument.values()) assertEquals(i, Instrument.ofId(i.id()).orElseThrow());
        for (Kit k : Kit.values()) assertEquals(k, Kit.ofId(k.id()).orElseThrow());
        for (Style s : Style.values()) assertEquals(s, Style.ofId(s.id()).orElseThrow());
    }

    @Test
    void eightDifferentVariationsThatAreAlwaysTheSame() {
        Recipe r = Recipe.of(Style.ACOUSTIC, 4711);
        List<Variations.Variation> all = Variations.around(r);
        assertEquals(Variations.COUNT, all.size());
        assertEquals(all, Variations.around(r));
        Set<Recipe> distinct = new HashSet<>();
        for (Variations.Variation v : all) {
            assertNotEquals(r, v.recipe(), v.key());
            distinct.add(v.recipe());
        }
        assertEquals(Variations.COUNT, distinct.size());
        assertEquals(r.melodySeed(), all.get(1).recipe().melodySeed(), "other chords keep the melody");
        assertTrue(all.get(4).recipe().bpm() > r.bpm());
        assertTrue(all.get(5).recipe().bpm() < r.bpm());
    }

    @Test
    void piecesWithoutDrumsGetDrumsAsAVariation() {
        Recipe r = Recipe.of(Style.LOFI, 1).with(b -> b.kit = Kit.NONE);
        assertEquals(Kit.SOFT, Variations.around(r).get(3).recipe().kit());
    }
}
