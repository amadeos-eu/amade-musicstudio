package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.compose.Section.Kind;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Which part plays in which bar. Melody, bass, drums, pad and arpeggio may come in four or eight bars after the intro and
 * rest for four or eight bars in the middle of the piece; the chords always play and hold it together. Intro,
 * outro, the first and the last four bars between them are never touched.
 *
 * <p>Every part draws from its own seed, one number per block of four bars. A block rests when its number is
 * below a limit that grows with {@link Recipe#variety()}, so a higher variety mostly adds rests to those of a
 * lower one, and variety 0 lets every part play throughout.
 */
final class Arrangement {
    /** Bars that enter or rest together. */
    static final int BLOCK = 4;

    private final boolean[][] plays;

    private Arrangement(boolean[][] plays) {
        this.plays = plays;
    }

    static Arrangement of(Recipe r, List<Section> sections, int bars, boolean loop) {
        boolean[][] plays = new boolean[Part.values().length][bars];
        for (boolean[] p : plays) Arrays.fill(p, true);
        int from = bars;
        int to = 0;
        for (Section s : sections) {
            if (s.kind() == Kind.INTRO || s.kind() == Kind.OUTRO) continue;
            from = Math.min(from, s.startBar());
            to = Math.max(to, s.endBar());
        }
        double variety = r.variety() / 100.0;
        int blocks = (to - from) / BLOCK;
        if (variety > 0 && blocks >= 2) {
            for (Part part : new Part[]{Part.MELODY, Part.BASS, Part.DRUMS, Part.PAD, Part.ARPEGGIO}) {
                Random random = new Random(r.seed(part) * 1_000_003L + ("arrangement." + part.id()).hashCode());
                boolean[] rest = new boolean[blocks];
                double late = random.nextDouble();
                double longer = random.nextDouble();
                if (!loop && late < variety * 0.7) {
                    rest[0] = true;
                    if (part == Part.DRUMS && blocks >= 6 && longer < variety * 0.4) rest[1] = true;
                }
                double limit = variety * 0.45;
                for (int k = 1; k < blocks - 1; k++) {
                    boolean wanted = random.nextDouble() < limit;
                    boolean tooLong = rest[k - 1] && (part == Part.MELODY || k >= 2 && rest[k - 2]);
                    rest[k] = rest[k] || wanted && !tooLong;
                }
                for (int k = 0; k < blocks; k++) {
                    if (rest[k]) Arrays.fill(plays[part.ordinal()], from + k * BLOCK, from + (k + 1) * BLOCK, false);
                }
            }
        }
        return new Arrangement(plays);
    }

    /** Whether the part plays in the bar. */
    boolean plays(Part part, int bar) {
        boolean[] p = plays[part.ordinal()];
        return bar < 0 || bar >= p.length || p[bar];
    }

    /** Whether the part starts to play with this bar after a rest. */
    boolean enters(Part part, int bar) {
        return bar > 0 && plays(part, bar) && !plays(part, bar - 1);
    }

    /** Whether the part starts to rest with this bar. */
    boolean leaves(Part part, int bar) {
        return bar > 0 && !plays(part, bar) && plays(part, bar - 1);
    }
}
