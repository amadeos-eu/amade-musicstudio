package eu.amadeos.apps.musicstudio.domain;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Eight close relatives of a piece to listen to: one part new each, faster, slower, harder and softer. The
 * same piece always gets the same relatives.
 */
public final class Variations {
    public static final int COUNT = 8;

    /** One relative and the message key that says what changed. */
    public record Variation(String key, Recipe recipe) {
    }

    private Variations() {
    }

    public static List<Variation> around(Recipe r) {
        SplittableRandom random = new SplittableRandom(r.seed() * 31 + r.melodySeed() * 17 + r.chordSeed() * 7
                + r.bassSeed() * 3 + r.drumSeed() + r.style().ordinal());
        long[] seeds = new long[4];
        for (int i = 0; i < seeds.length; i++) seeds[i] = random.nextLong(Recipe.MAX_SEED + 1);
        int step = Math.max(6, r.bpm() / 7);
        return List.of(
                new Variation("variation.melody", r.withPartSeed(Part.MELODY, seeds[0])),
                new Variation("variation.chords", r.withPartSeed(Part.CHORDS, seeds[1])),
                new Variation("variation.bass", r.withPartSeed(Part.BASS, seeds[2])),
                new Variation("variation.drums", r.kit() == Kit.NONE
                        ? r.with(b -> b.kit = Kit.SOFT) : r.withPartSeed(Part.DRUMS, seeds[3])),
                new Variation("variation.faster", r.with(b -> b.bpm = r.bpm() + step)),
                new Variation("variation.slower", r.with(b -> b.bpm = r.bpm() - step)),
                new Variation("variation.harder", r.with(b -> {
                    b.hardness = r.hardness() + 30;
                    b.energy = r.energy() + 20;
                    b.brightness = r.brightness() + 15;
                })),
                new Variation("variation.softer", r.with(b -> {
                    b.hardness = r.hardness() - 30;
                    b.energy = r.energy() - 20;
                    b.reverb = r.reverb() + 15;
                })));
    }
}
