package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.service.compose.Section.Kind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes the notes of a piece by rules. The form is intro, A, B, A, ... and outro. The chords follow a
 * chain of likely steps in the key; the melody is a motif of one bar, varied, repeated and closed on the key
 * note, with chord notes on the strong beats; accompaniment, bass and drums follow the patterns of the style.
 * Melody, bass and drums may rest for some bars ({@link Arrangement}), and the drums answer to that.
 *
 * <p>Every part draws its random numbers from its own seed, so a part stays the same when another part gets
 * a new seed. The random numbers come from {@link Random}, whose sequence Java fixes for every version.
 */
public final class Composer {
    /** Bars of the A and B sections. */
    static final int SECTION_BARS = 8;

    /**
     * Composes the recipe.
     *
     * @param loop without intro and outro, so that the end leads back into the start
     */
    public Song compose(Recipe r, boolean loop) {
        return new Work(r, loop).song();
    }

    /** The state of one composition. */
    private static final class Work {
        final Recipe r;
        final Style st;
        final boolean loop;
        final List<Note> notes = new ArrayList<>();
        final List<Section> sections = new ArrayList<>();
        final int keyNote;
        final boolean minor;
        int bars;
        int[][] chordsPerBar;
        final double energy;

        Work(Recipe r, boolean loop) {
            this.r = r;
            this.st = r.style();
            this.loop = loop;
            this.energy = r.energy() / 100.0;
            this.minor = st.chordScale().minor();
            this.keyNote = r.key() >= 0 ? r.key() : random(Part.CHORDS, "key").nextInt(12);
        }

        Random random(Part part, String what) {
            return new Random(r.seed(part) * 1_000_003L + what.hashCode());
        }

        Song song() {
            form();
            harmony();
            accompaniment();
            bass();
            if (r.kit() != Kit.NONE) drums();
            melody();
            arrange();
            List<Note> shifted = new ArrayList<>(notes.size());
            for (Note n : notes) {
                Part p = Song.part(n.channel());
                shifted.add(p.tonal() ? n.transposed(12 * r.voice(p).octave()) : n);
            }
            shifted.sort(Comparator.comparingDouble(Note::step).thenComparingInt(Note::channel));
            return new Song(r, loop, shifted, sections, bars, keyNote, minor, chordsPerBar);
        }

        // ---------------------------------------------------------------- who plays when

        /**
         * Takes the notes out of the bars in which their part rests. The notes are written first and taken out
         * afterwards, so that a part plays the same notes whether or not it rested before.
         */
        void arrange() {
            Arrangement plan = Arrangement.of(r, sections, bars, loop);
            List<Note> played = new ArrayList<>(notes.size());
            for (Note n : notes) {
                Part part = Song.part(n.channel());
                int bar = (int) (n.step() / Song.STEPS_PER_BAR);
                if (!plan.plays(part, bar)) continue;
                int rest = bar + 1;
                while (rest < bars && plan.plays(part, rest)) rest++;
                double room = rest * Song.STEPS_PER_BAR - n.step();
                played.add(rest < bars && n.length() > room ? n.withLength(room) : n);
            }
            notes.clear();
            notes.addAll(played);
            if (r.kit() != Kit.NONE) reactions(plan);
        }

        /**
         * What the drums play because of the others: a roll before they come back, a last hit when they stop,
         * a short fill and a crash when melody or bass come in, two quiet hits before one of them rests.
         */
        void reactions(Arrangement plan) {
            Random random = random(Part.DRUMS, "reactions");
            boolean soft = st.groove() == Style.Groove.HALFTIME;
            Drum fillDrum = st.groove() == Style.Groove.EPIC ? Drum.TOM : Drum.SNARE;
            for (int bar = 1; bar < bars; bar++) {
                int base = bar * Song.STEPS_PER_BAR;
                int before = base - Song.STEPS_PER_BAR;
                if (plan.enters(Part.DRUMS, bar)) {
                    int[] roll = soft ? new int[]{8, 10, 12, 14} : energy > 0.5 ? new int[]{8, 10, 12, 13, 14, 15}
                            : new int[]{10, 12, 14, 15};
                    for (int i = 0; i < roll.length; i++) {
                        double rise = i / (double) (roll.length - 1);
                        react(before + roll[i], soft ? Drum.SHAKER : fillDrum, soft ? 0.3 + 0.25 * rise : 0.4 + 0.5 * rise,
                                random);
                    }
                    if (!soft) react(base, Drum.OPEN_HAT, 0.8, random);
                } else if (plan.leaves(Part.DRUMS, bar)) {
                    if (!drumsIn(before, 0, Song.STEPS_PER_BAR)) continue;
                    react(base, Drum.KICK, soft ? 0.7 : 0.9, random);
                    if (!soft) react(base, Drum.OPEN_HAT, 0.6, random);
                } else if (plan.plays(Part.DRUMS, bar) && !soft) {
                    boolean enters = plan.enters(Part.MELODY, bar) || plan.enters(Part.BASS, bar);
                    boolean leaves = plan.leaves(Part.MELODY, bar) || plan.leaves(Part.BASS, bar);
                    if (!enters && !leaves || !drumsIn(before, 0, Song.STEPS_PER_BAR)) continue;
                    boolean filled = drumsIn(before, 13, Song.STEPS_PER_BAR - 13, Song.SNARE);
                    if (enters) {
                        if (!filled) {
                            react(before + 14, fillDrum, 0.5, random);
                            react(before + 15, fillDrum, 0.7, random);
                        }
                        react(base, Drum.OPEN_HAT, 0.8, random);
                    } else if (!filled) {
                        react(before + 13, fillDrum, 0.35, random);
                        react(before + 15, fillDrum, 0.5, random);
                    }
                }
            }
        }

        /** A drum hit, unless the same drum is already hit there. */
        void react(double step, Drum d, double velocity, Random random) {
            for (Note n : notes) {
                if (n.drum() == d && Math.abs(n.step() - step) < 0.01) return;
            }
            drum(step, d, velocity, random);
        }

        boolean drumsIn(int from, int offset, int steps, int... channels) {
            for (Note n : notes) {
                if (!n.isDrum() || n.step() < from + offset || n.step() >= from + offset + steps) continue;
                if (channels.length == 0) return true;
                for (int c : channels) if (n.channel() == c) return true;
            }
            return false;
        }

        // ---------------------------------------------------------------- form and chords

        void form() {
            double barSeconds = 240.0 / r.bpm();
            int total = Math.max(loop ? 8 : 12, (int) Math.round(r.lengthSeconds() / barSeconds));
            int bar = 0;
            int edge = st.barsPerChord() == 2 || total >= 20 ? 4 : 2;
            if (!loop) {
                sections.add(new Section(Kind.INTRO, 0, edge));
                bar = edge;
            }
            int middle = loop ? Math.max(8, (total + 3) / 4 * 4) : Math.max(4, total - 2 * edge);
            boolean a = true;
            while (middle > 0) {
                int len = Math.min(SECTION_BARS, middle);
                if (middle - len > 0 && middle - len < 4) len = middle;
                sections.add(new Section(a ? Kind.A : Kind.B, bar, len));
                bar += len;
                middle -= len;
                a = !a;
            }
            if (!loop) {
                sections.add(new Section(Kind.OUTRO, bar, edge));
                bar += edge;
            }
            bars = bar;
        }

        void harmony() {
            int[] progA = progression(random(Part.CHORDS, "progA"), 0);
            int[] progB = progression(random(Part.CHORDS, "progB"), minor ? 5 : 3);
            chordsPerBar = new int[bars][];
            for (Section sec : sections) {
                int[] prog = sec.kind() == Kind.B ? progB : progA;
                for (int b = 0; b < sec.bars(); b++) {
                    int idx = (b / st.barsPerChord()) % prog.length;
                    chordsPerBar[sec.startBar() + b] = chord(prog[idx]);
                }
            }
            // The piece ends on the key's own chord.
            if (!loop) chordsPerBar[bars - 1] = chord(0);
        }

        /** Four chords as steps of the scale (0 is the key's own chord), by a chain of likely next steps. */
        int[] progression(Random random, int start) {
            int[][] next = minor
                    ? new int[][]{{5, 3, 6, 2, 4}, {4, 6}, {5, 3, 6}, {4, 6, 0, 5}, {0, 5, 3}, {6, 2, 3, 4}, {0, 2, 5, 3}}
                    : new int[][]{{3, 4, 5, 1, 5}, {4, 3}, {5, 3}, {4, 0, 1, 4}, {0, 5, 3}, {3, 1, 4, 2}, {0}};
            int[] p = new int[4];
            for (int tries = 0; ; tries++) {
                p[0] = start;
                for (int i = 1; i < 4; i++) {
                    int[] n = next[p[i - 1]];
                    p[i] = n[random.nextInt(n.length)];
                }
                boolean varied = new HashSet<>(List.of(p[0], p[1], p[2], p[3])).size() >= 3;
                if (p[3] != 0 && p[3] != p[0] && varied || tries > 50) return p;
            }
        }

        /** The pitch classes of the chord on a step of the scale; the root first. */
        int[] chord(int degree) {
            int n = st.sevenths() ? 4 : 3;
            int[] pcs = new int[n];
            Style.Scale scale = st.chordScale();
            for (int i = 0; i < n; i++) {
                int d = degree + 2 * i;
                pcs[i] = (keyNote + scale.step(d % scale.size()) + 12 * (d / scale.size())) % 12;
            }
            return pcs;
        }

        /** Places the chord near the previous one, so that the voices move as little as possible. */
        static int[] voice(int[] pcs, int[] previous, int centre) {
            int[] use = pcs.length == 4 ? new int[]{pcs[1], pcs[2], pcs[3]} : pcs; // the bass plays the root
            int[] v = new int[3];
            for (int i = 0; i < 3; i++) {
                int target = previous == null ? centre - 5 + i * 5 : previous[i];
                int best = 0;
                int bestDistance = Integer.MAX_VALUE;
                for (int o = 36; o < 96; o++) {
                    if (o % 12 != use[i]) continue;
                    int d = Math.abs(o - target) + Math.abs(o - centre) / 3;
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = o;
                    }
                }
                v[i] = best;
            }
            Arrays.sort(v);
            return v;
        }

        Section section(int bar) {
            for (Section s : sections) if (s.contains(bar)) return s;
            return sections.getLast();
        }

        static boolean edge(Section s) {
            return s.kind() == Kind.INTRO || s.kind() == Kind.OUTRO;
        }

        void add(double step, double length, int channel, int pitch, double velocity, Instrument instrument) {
            notes.add(new Note(step, length, channel, pitch, Math.max(0.05, Math.min(1, velocity)), instrument, null));
        }

        // ---------------------------------------------------------------- accompaniment

        void accompaniment() {
            Random random = random(Part.CHORDS, "chords");
            Instrument in = r.chords().instrument();
            int[] previous = null;
            int roundRobin = 0;
            int[] stabRhythm = switch (random.nextInt(3)) {
                case 0 -> new int[]{0, 6, 10};
                case 1 -> new int[]{0, 7, 12};
                default -> new int[]{0, 3, 10};
            };
            int[] stabLength = {5, 3, 5};
            for (int bar = 0; bar < bars; bar++) {
                int[] pcs = chordsPerBar[bar];
                boolean chordStarts = bar == 0 || st.barsPerChord() == 1 || bar % 2 == 0
                        || !Arrays.equals(pcs, chordsPerBar[bar - 1]);
                int[] v = voice(pcs, previous, 62);
                previous = v;
                Section sec = section(bar);
                double vel = edge(sec) ? 0.6 : sec.kind() == Kind.B ? 0.8 : 0.72;
                if (sec.kind() == Kind.OUTRO) vel *= 1 - 0.5 * (bar - sec.startBar()) / (double) sec.bars();
                int base = bar * Song.STEPS_PER_BAR;
                switch (st.accompaniment()) {
                    case SUSTAIN -> {
                        if (!chordStarts) break;
                        int span = 16 * st.barsPerChord();
                        for (int i = 0; i < 3; i++) add(base, span, Song.CHORD + i, v[i], vel, in);
                    }
                    case STABS -> {
                        for (int k = 0; k < stabRhythm.length; k++) {
                            if (k > 0 && random.nextDouble() > 0.4 + 0.6 * energy) continue;
                            for (int i = 0; i < 3; i++) {
                                add(base + stabRhythm[k], stabLength[k], Song.CHORD + i, v[i], vel * (k == 0 ? 1 : 0.8), in);
                            }
                        }
                    }
                    case ARPEGGIO, BROKEN, ALBERTI -> {
                        int[] seq = switch (st.accompaniment()) {
                            case ARPEGGIO -> new int[]{v[0], v[1], v[2], v[0] + 12, v[1] + 12, v[2] + 12, v[0] + 12, v[2],
                                    v[1], v[0], v[1], v[2], v[0] + 12, v[2], v[1], v[2]};
                            case ALBERTI -> new int[]{v[0], v[2], v[1], v[2], v[0], v[2], v[1], v[2]};
                            default -> new int[]{v[0] - 12, v[0], v[1], v[2], v[0] + 12, v[2], v[1], v[0]};
                        };
                        int stepLength = st.accompaniment() == Style.Accompaniment.ARPEGGIO && energy >= 0.4 ? 1 : 2;
                        for (int t = 0, i = 0; t < 16; t += stepLength, i++) {
                            double accent = t % 4 == 0 ? 1.0 : 0.82;
                            add(base + t, stepLength * 2, Song.CHORD + (roundRobin++ % 3), seq[i % seq.length],
                                    vel * accent, in);
                        }
                    }
                    case STRUM -> {
                        int[] hits = energy > 0.5 ? new int[]{0, 4, 6, 10, 12, 14} : new int[]{0, 6, 10, 12};
                        for (int k = 0; k < hits.length; k++) {
                            int len = (k + 1 < hits.length ? hits[k + 1] : 16) - hits[k];
                            boolean down = hits[k] % 4 == 0;
                            for (int i = 0; i < 3; i++) {
                                int idx = down ? i : 2 - i;
                                add(base + hits[k] + 0.12 * i, len, Song.CHORD + idx, v[idx], vel * (down ? 0.95 : 0.7), in);
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- bass

        int root(int bar) {
            int root = 36 + chordsPerBar[Math.min(bars - 1, bar)][0];
            return root > 45 ? root - 12 : root;
        }

        void bass() {
            Random random = random(Part.BASS, "bass");
            Instrument in = r.bass().instrument();
            for (int bar = 0; bar < bars; bar++) {
                Section sec = section(bar);
                if (sec.kind() == Kind.INTRO && bar < sec.startBar() + sec.bars() / 2
                        && st.bassLine() != Style.BassLine.LONG) {
                    continue;
                }
                int root = root(bar);
                int fifth = root + 7;
                int base = bar * Song.STEPS_PER_BAR;
                double vel = sec.kind() == Kind.OUTRO ? 0.7 : 0.9;
                switch (st.bassLine()) {
                    case LONG -> {
                        if (bar % st.barsPerChord() == 0) add(base, 16 * st.barsPerChord(), Song.BASS, root, 0.8, in);
                    }
                    case PULSE -> {
                        for (int t = 0; t < 16; t += 2) add(base + t, 1.6, Song.BASS, (t / 2) % 2 == 0 ? root : root + 12, vel, in);
                    }
                    case OFFBEAT -> {
                        for (int t = 2; t < 16; t += 4) add(base + t, 1.7, Song.BASS, root, vel, in);
                        if (energy > 0.6) add(base + 15, 0.9, Song.BASS, root + 12, vel * 0.7, in);
                    }
                    case BOOM -> {
                        int[] hits = boomKick(random, energy);
                        for (int k = 0; k < hits.length; k++) {
                            int len = (k + 1 < hits.length ? hits[k + 1] : 16) - hits[k];
                            int pitch = k == 0 ? root : random.nextDouble() < 0.3 ? fifth : root;
                            add(base + hits[k], Math.min(len, 6), Song.BASS, pitch, vel, in);
                        }
                    }
                    case WALK -> {
                        int approach = root(bar + 1) + (random.nextBoolean() ? -1 : 2);
                        int[] seq = {root, random.nextBoolean() ? fifth : root + 12, fifth, approach};
                        for (int k = 0; k < 4; k++) add(base + 4 * k, 3.5, Song.BASS, seq[k], vel * (k == 0 ? 1 : 0.85), in);
                    }
                    case PIANO -> {
                        add(base, 8, Song.BASS, root + 12, 0.75, in);
                        add(base + 8, 8, Song.BASS, fifth + (fifth + 12 > 60 ? 0 : 12), 0.6, in);
                    }
                }
            }
        }

        /** The kick drum of a laid-back beat; the bass of that style plays on the same hits. */
        static int[] boomKick(Random random, double energy) {
            List<Integer> hits = new ArrayList<>(List.of(0, 10));
            if (random.nextDouble() < 0.3 + 0.5 * energy) hits.add(7);
            if (random.nextDouble() < 0.3 * energy) hits.add(3);
            Collections.sort(hits);
            return hits.stream().mapToInt(Integer::intValue).toArray();
        }

        // ---------------------------------------------------------------- drums

        void drums() {
            Random random = random(Part.DRUMS, "drums");
            Random kicks = random(Part.BASS, "bass"); // a laid-back beat's kicks follow the bass line
            Style.Groove groove = st.groove();
            double fills = r.fills() / 100.0;
            for (int bar = 0; bar < bars; bar++) {
                Section sec = section(bar);
                boolean intro = sec.kind() == Kind.INTRO;
                boolean outro = sec.kind() == Kind.OUTRO;
                double e = Math.min(1, energy + (sec.kind() == Kind.B ? 0.15 : 0));
                int base = bar * Song.STEPS_PER_BAR;
                boolean last = bar == sec.endBar() - 1;
                boolean fill = last && !outro && new Random(r.drumSeed() * 1_000_003L + bar).nextDouble() < fills;
                double fade = outro ? 1 - (bar - sec.startBar()) / (double) sec.bars() : 1;
                if (intro && bar < sec.endBar() - 1 && e < 0.5) {
                    if (groove == Style.Groove.BOOM_BAP) boomKick(kicks, e); // keeps the kicks in step with the bass
                    continue;
                }
                if (outro && bar == bars - 1) {
                    drum(base, Drum.KICK, 0.8, random);
                    continue;
                }
                List<double[]> hits = new ArrayList<>();
                switch (groove) {
                    case HALFTIME -> {
                        hit(hits, 0, Drum.KICK, 0.9);
                        hit(hits, 8, Drum.SNARE, 0.7);
                        for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.SHAKER, t % 4 == 0 ? 0.5 : 0.35);
                    }
                    case BOOM_BAP -> {
                        for (int t : boomKick(kicks, e)) hit(hits, t, Drum.KICK, t == 0 ? 1 : 0.85);
                        hit(hits, 4, Drum.SNARE, 0.9);
                        hit(hits, 12, Drum.SNARE, 0.95);
                        for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, t % 4 == 0 ? 0.6 : 0.42);
                        if (random.nextDouble() < e) hit(hits, 15, Drum.SNARE, 0.25);
                        if (random.nextDouble() < e * 0.6) hit(hits, 13, Drum.HAT, 0.3);
                    }
                    case CHIP -> {
                        hit(hits, 0, Drum.KICK, 1);
                        hit(hits, 8, Drum.KICK, 0.9);
                        if (e > 0.5) hit(hits, 10, Drum.KICK, 0.8);
                        hit(hits, 4, Drum.SNARE, 0.9);
                        hit(hits, 12, Drum.SNARE, 0.9);
                        for (int t = 0; t < 16; t += e > 0.6 ? 1 : 2) hit(hits, t, Drum.HAT, t % 2 == 0 ? 0.55 : 0.35);
                    }
                    case FOUR_ON_THE_FLOOR -> {
                        for (int t = 0; t < 16; t += 4) hit(hits, t, Drum.KICK, 1);
                        hit(hits, 4, Drum.CLAP, 0.9);
                        hit(hits, 12, Drum.CLAP, 0.9);
                        for (int t = 2; t < 16; t += 4) hit(hits, t, Drum.OPEN_HAT, 0.6);
                        if (e > 0.45) {
                            for (int t = 1; t < 16; t += 2) hit(hits, t, Drum.HAT, 0.35 + 0.1 * random.nextDouble());
                        }
                        if (e > 0.8 && random.nextBoolean()) hit(hits, 14, Drum.KICK, 0.6);
                    }
                    case FOLK -> {
                        hit(hits, 0, Drum.KICK, 0.9);
                        hit(hits, 8, Drum.KICK, 0.75);
                        hit(hits, 4, Drum.SNARE, 0.7);
                        hit(hits, 12, Drum.SNARE, 0.75);
                        for (int t = 0; t < 16; t++) {
                            if (t % 2 == 0 || e > 0.5) hit(hits, t, Drum.SHAKER, t % 4 == 0 ? 0.55 : t % 2 == 0 ? 0.4 : 0.25);
                        }
                    }
                    case EPIC -> {
                        int[] pattern = euclid(5, 16);
                        for (int t = 0; t < 16; t++) if (pattern[t] == 1) hit(hits, t, Drum.TOM, t == 0 ? 1 : 0.7);
                        hit(hits, 0, Drum.KICK, 0.9);
                        hit(hits, 12, Drum.SNARE, 0.9);
                        if (e > 0.5) for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, 0.35);
                    }
                    default -> {
                    }
                }
                if (intro && e < 0.5) {
                    hits.removeIf(h -> h[1] != Drum.SHAKER.ordinal() && h[1] != Drum.HAT.ordinal());
                }
                if (fill) {
                    hits.removeIf(h -> h[0] >= 12 && h[1] != Drum.KICK.ordinal());
                    Drum fillDrum = groove == Style.Groove.EPIC ? Drum.TOM : Drum.SNARE;
                    int n = e > 0.5 ? 4 : 2;
                    for (int k = 0; k < n; k++) hit(hits, 12 + k * (4 / n), fillDrum, 0.5 + 0.12 * k);
                }
                if (bar == sec.startBar() && !edge(sec) && e > 0.45 && groove != Style.Groove.HALFTIME) {
                    hit(hits, 0, Drum.OPEN_HAT, 0.75);
                }
                for (double[] h : hits) drum(base + h[0], Drum.values()[(int) h[1]], h[2] * fade, random);
            }
        }

        void drum(double step, Drum d, double velocity, Random random) {
            int channel = switch (d) {
                case KICK -> Song.KICK;
                case SNARE, CLAP, TOM -> Song.SNARE;
                default -> Song.HATS;
            };
            double v = Math.min(1, velocity * (0.93 + 0.1 * random.nextDouble()));
            notes.add(new Note(step, 1, channel, 60, Math.max(0.05, v), null, d));
        }

        static void hit(List<double[]> hits, double step, Drum d, double velocity) {
            hits.add(new double[]{step, d.ordinal(), velocity});
        }

        /** {@code k} hits spread as evenly as possible over {@code n} steps. */
        static int[] euclid(int k, int n) {
            int[] pattern = new int[n];
            for (int i = 0; i < n; i++) pattern[i] = (i * k) % n < k ? 1 : 0;
            return pattern;
        }

        // ---------------------------------------------------------------- melody

        void melody() {
            Instrument in = r.melody().instrument();
            List<Integer> pitches = new ArrayList<>();
            Style.Scale scale = st.melodyScale();
            for (int m = st.leadLow(); m <= st.leadHigh(); m++) {
                int pc = Math.floorMod(m - keyNote, 12);
                for (int s : scale.steps()) if (s == pc) pitches.add(m);
            }
            Map<Kind, double[][]> rhythms = new HashMap<>();
            for (Section sec : sections) {
                if (sec.kind() == Kind.INTRO) continue;
                boolean outro = sec.kind() == Kind.OUTRO;
                Kind theme = outro ? Kind.A : sec.kind();
                double[][] rhythm = rhythms.computeIfAbsent(theme,
                        k -> phraseRhythm(random(Part.MELODY, "rhythm" + k), st, energy));
                Random pr = random(Part.MELODY, "melody" + theme);
                int idx = pitches.size() / 2;
                int barsToPlay = outro ? Math.min(2, sec.bars()) : sec.bars();
                List<Note> phrase = new ArrayList<>();
                for (double[] onset : rhythm) {
                    int barInPhrase = (int) (onset[0] / 16);
                    if (barInPhrase >= barsToPlay) continue;
                    int bar = sec.startBar() + barInPhrase;
                    double stepInBar = onset[0] % 16;
                    int[] chord = chordsPerBar[bar];
                    double u = pr.nextDouble();
                    if (stepInBar % 8 == 0) idx = nearestChordTone(pitches, idx + (pr.nextBoolean() ? 1 : -1), chord);
                    else if (u < 0.65) idx += pr.nextBoolean() ? 1 : -1;
                    else if (u < 0.85) idx += pr.nextBoolean() ? 2 : -2;
                    else if (u < 0.93) idx += pr.nextBoolean() ? 3 : -3;
                    idx = Math.max(0, Math.min(pitches.size() - 1, idx));
                    if (idx == 0 || idx == pitches.size() - 1) idx = pitches.size() / 2 + (pr.nextBoolean() ? 1 : -1);
                    phrase.add(new Note(bar * 16 + stepInBar, onset[1], Song.LEAD, pitches.get(idx),
                            0.62 + 0.25 * onset[2], in, null));
                }
                // The phrase again, fitted to the chords; the very last note goes to the key note.
                for (int rep = 0; rep * 4 < barsToPlay; rep++) {
                    for (int i = 0; i < phrase.size(); i++) {
                        Note n = phrase.get(i);
                        int bar = (int) (n.step() / 16) + rep * 4;
                        if (bar >= sec.startBar() + barsToPlay) continue;
                        int p = n.pitch();
                        boolean lastNote = i == phrase.size() - 1 && (rep + 1) * 4 >= barsToPlay;
                        if (rep > 0 && n.step() % 8 == 0) {
                            p = pitches.get(nearestChordTone(pitches, pitches.indexOf(p), chordsPerBar[bar]));
                        }
                        if (lastNote) p = keyNoteNear(p);
                        double len = lastNote ? Math.max(n.length(), 8) : n.length();
                        add(n.step() + rep * 64, len, Song.LEAD, p, outro ? n.velocity() * 0.7 : n.velocity(), in);
                    }
                }
            }
        }

        int keyNoteNear(int p) {
            for (int d = 0; d < 7; d++) {
                if (Math.floorMod(p - d, 12) == keyNote) return p - d;
                if (Math.floorMod(p + d, 12) == keyNote) return p + d;
            }
            return p;
        }

        static int nearestChordTone(List<Integer> pitches, int idx, int[] chord) {
            idx = Math.max(0, Math.min(pitches.size() - 1, idx));
            for (int d = 0; d < pitches.size(); d++) {
                for (int sign : new int[]{0, 1, -1}) {
                    int i = idx + sign * d;
                    if (i < 0 || i >= pitches.size()) continue;
                    int pc = pitches.get(i) % 12;
                    for (int c = 0; c < Math.min(3, chord.length); c++) if (chord[c] == pc) return i;
                }
            }
            return idx;
        }

        /** Four bars of rhythm: the motif, a variation, the motif, a close. Entries: onset, length, accent. */
        static double[][] phraseRhythm(Random random, Style st, double energy) {
            double density = st.melodyDensity() * (0.7 + 0.6 * energy);
            boolean[] motif = new boolean[16];
            for (int t = 0; t < 16; t++) {
                double w = t == 0 ? 0.95 : t == 8 ? 0.7 : t % 4 == 0 ? 0.55 : t % 2 == 0 ? 0.35 : 0.12;
                motif[t] = random.nextDouble() < w * density * 1.6;
            }
            motif[0] = true;
            boolean[] variation = motif.clone();
            for (int k = 0; k < 2; k++) {
                int t = 2 + random.nextInt(14);
                variation[t] = !variation[t];
            }
            boolean[] close = new boolean[16];
            close[0] = true;
            if (random.nextBoolean()) close[4] = true;
            if (density > 0.4) close[6] = random.nextBoolean();
            boolean[][] barsOfPhrase = {motif, variation, motif, close};
            List<Integer> onsets = new ArrayList<>();
            for (int b = 0; b < 4; b++) for (int t = 0; t < 16; t++) if (barsOfPhrase[b][t]) onsets.add(b * 16 + t);
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i < onsets.size(); i++) {
                int on = onsets.get(i);
                int next = i + 1 < onsets.size() ? onsets.get(i + 1) : 64;
                if (st.barsPerChord() == 2 && on % 16 > 10 && random.nextBoolean()) continue; // slow styles breathe
                out.add(new double[]{on, Math.min(next - on, 12), on % 8 == 0 ? 1 : 0});
            }
            return out.toArray(new double[0][]);
        }
    }
}
