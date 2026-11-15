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
 * chain of likely steps in the key, coloured more or less by the recipe's harmony; the melody is a motif of one bar, varied, repeated and closed on the key
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

    /**
     * A Euclidean rhythm: {@code k} hits spread as evenly as possible over {@code n} steps, then turned so
     * that it starts {@code turn} steps later. Three over eight is the tresillo, five over eight the
     * cinquillo.
     */
    static int[] euclid(int k, int n, int turn) {
        int[] pattern = new int[n];
        for (int i = 0; i < n; i++) pattern[Math.floorMod(i + turn, n)] = (i * k) % n < k ? 1 : 0;
        return pattern;
    }

    /** The rhythm turned so that its hit number {@code which} falls on the first step. */
    static int[] startingWithANote(int[] rhythm, int which) {
        int n = rhythm.length;
        int at = 0;
        for (int i = 0, seen = 0; i < n; i++) {
            if (rhythm[i] == 1 && seen++ == which) at = i;
        }
        int[] turned = new int[n];
        for (int i = 0; i < n; i++) turned[i] = rhythm[(i + at) % n];
        return turned;
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
        /** The pitch class the bass plays in each bar: the chord's root, or its third when the chord is turned. */
        int[] bassPerBar;
        /** The semitones from the bass note to the second note bass lines use: a fifth, unless the chord says otherwise. */
        int[] fifthPerBar;
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
            if (r.plays(Part.PAD)) pad();
            if (r.plays(Part.ARPEGGIO)) arpeggio();
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
            Colour[] colourA = colours(progA, "colourA");
            Colour[] colourB = colours(progB, "colourB");
            chordsPerBar = new int[bars][];
            bassPerBar = new int[bars];
            fifthPerBar = new int[bars];
            for (Section sec : sections) {
                int[] prog = sec.kind() == Kind.B ? progB : progA;
                Colour[] colour = sec.kind() == Kind.B ? colourB : colourA;
                for (int b = 0; b < sec.bars(); b++) {
                    int idx = (b / st.barsPerChord()) % prog.length;
                    Colour c = colour[idx];
                    // A suspended chord that is held for two bars comes to rest in the second.
                    boolean resolves = c != null && c.suspended() && st.barsPerChord() == 2 && b % 2 == 1;
                    setChord(sec.startBar() + b, c == null || resolves ? plain(prog[idx]) : c);
                }
            }
            // The piece ends on the key's own chord.
            if (!loop) setChord(bars - 1, plain(0));
        }

        /**
         * A chord as it is played.
         *
         * @param pcs       its pitch classes, the root first
         * @param bass      the pitch class of the bass note
         * @param fifth     semitones from the bass note to the second note of bass lines
         * @param suspended whether the fourth stands in for the third
         */
        record Colour(int[] pcs, int bass, int fifth, boolean suspended) {
        }

        Colour plain(int degree) {
            int[] pcs = chord(degree);
            return new Colour(pcs, pcs[0], 7, false);
        }

        void setChord(int bar, Colour c) {
            chordsPerBar[bar] = c.pcs();
            bassPerBar[bar] = c.bass();
            fifthPerBar[bar] = c.fifth();
        }

        /**
         * The colours of the four chords of a progression; {@code null} leaves a chord as it is. The more
         * {@link Recipe#harmony()}, the more chords are coloured: the third gives way to the fourth or the
         * second (suspended), a seventh, a sixth or a ninth is added, the chord before the return home becomes
         * a dominant seventh, an augmented or a diminished chord, and the bass takes the third instead of the
         * root where that lets it move by a step. Only notes of the piece's scales are used, so the melody
         * still fits. Three numbers are drawn for every chord whatever comes of them, so that more harmony
         * keeps the colours it had and adds to them.
         */
        Colour[] colours(int[] progression, String what) {
            Random random = random(Part.CHORDS, what);
            double harmony = r.harmony() / 100.0;
            boolean[] allowed = new boolean[12];
            for (int s : st.chordScale().steps()) allowed[(keyNote + s) % 12] = true;
            for (int s : st.melodyScale().steps()) allowed[(keyNote + s) % 12] = true;
            Colour[] out = new Colour[progression.length];
            int previousBass = -1;
            for (int i = 0; i < progression.length; i++) {
                double wanted = random.nextDouble();
                double which = random.nextDouble();
                double turned = random.nextDouble();
                int degree = progression[i];
                int[] p = chord(degree);
                int root = p[0];
                boolean diminished = Math.floorMod(p[2] - root, 12) == 6;
                boolean major = Math.floorMod(p[1] - root, 12) == 4;
                boolean fourth = allowed[(root + 5) % 12];
                boolean second = allowed[(root + 2) % 12];
                List<Colour> choices = new ArrayList<>();
                List<Integer> weights = new ArrayList<>();
                if (!diminished && fourth) {
                    int[] pcs = p.clone();
                    pcs[1] = (root + 5) % 12;
                    choices.add(new Colour(pcs, root, 7, true));
                    weights.add(2);
                }
                if (!diminished && second) {
                    int[] pcs = p.clone();
                    pcs[1] = (root + 2) % 12;
                    choices.add(new Colour(pcs, root, 7, false));
                    weights.add(2);
                    choices.add(new Colour(new int[]{root, p[1], p.length == 4 ? p[3] : p[2], (root + 2) % 12}, root, 7, false));
                    weights.add(2);
                }
                if (p.length == 3) {
                    choices.add(new Colour(chord(degree, 4), root, diminished ? 6 : 7, false));
                    weights.add(2);
                    if (major && allowed[(root + 9) % 12]) {
                        choices.add(new Colour(new int[]{root, p[1], p[2], (root + 9) % 12}, root, 7, false));
                        weights.add(1);
                    }
                }
                if (degree == 4 && allowed[(root + 4) % 12]) {
                    if (allowed[(root + 10) % 12]) {
                        choices.add(new Colour(new int[]{root, (root + 4) % 12, (root + 7) % 12, (root + 10) % 12}, root, 7, false));
                        weights.add(3);
                    }
                    if (allowed[(root + 8) % 12]) {
                        choices.add(new Colour(new int[]{root, (root + 4) % 12, (root + 8) % 12}, root, 8, false));
                        weights.add(1);
                    }
                }
                int leading = (keyNote + 11) % 12;
                if (degree == 4 && i == progression.length - 1 && allowed[leading] && allowed[(leading + 3) % 12]
                        && allowed[(leading + 6) % 12]) {
                    choices.add(new Colour(new int[]{leading, (leading + 3) % 12, (leading + 6) % 12}, leading, 6, false));
                    weights.add(1);
                }
                Colour c = null;
                if (wanted < harmony * 0.75 && !choices.isEmpty()) {
                    int total = 0;
                    for (int w : weights) total += w;
                    double at = which * total;
                    for (int k = 0; k < choices.size() && c == null; k++) {
                        at -= weights.get(k);
                        if (at < 0) c = choices.get(k);
                    }
                    if (c == null) c = choices.getLast();
                }
                int[] pcs = c == null ? p : c.pcs();
                int third = Math.floorMod(pcs[1] - pcs[0], 12);
                int step = previousBass < 0 ? 0 : Math.min(Math.floorMod(pcs[1] - previousBass, 12),
                        Math.floorMod(previousBass - pcs[1], 12));
                if (turned < harmony * 0.5 && (third == 3 || third == 4) && (step == 1 || step == 2)) {
                    c = new Colour(pcs, pcs[1], Math.floorMod(pcs[0] - pcs[1], 12), false);
                }
                out[i] = c;
                previousBass = c == null ? root : c.bass();
            }
            return out;
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
            return chord(degree, st.sevenths() ? 4 : 3);
        }

        /** The first {@code n} notes of the chord on a step of the scale, in thirds. */
        int[] chord(int degree, int n) {
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
            // The riff of the muted guitar: a gallop, straight eighths, or a broken figure.
            int[] riff = switch (random(Part.CHORDS, "riff").nextInt(3)) {
                case 0 -> new int[]{0, 2, 3, 4, 6, 7, 8, 10, 11, 12, 14, 15};
                case 1 -> new int[]{0, 2, 4, 6, 8, 10, 12, 14};
                default -> new int[]{0, 2, 3, 6, 8, 10, 11, 14};
            };
            int[][] comps = {{0, 6}, {6, 14}, {0, 10}, {4, 6, 14}, {2, 10}};
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
                    case POWER -> {
                        // Root, fifth and octave, low on the neck; the third is left to the melody.
                        int low = 40 + Math.floorMod(pcs[0] - 4, 12);
                        int[] power = {low, low + 7, low + 12};
                        boolean heavy = st.groove() == Style.Groove.DOUBLE_BASS;
                        if (edge(sec) || sec.kind() == Kind.B && !heavy) {
                            // Open chords that ring.
                            int[] hits = edge(sec) ? new int[]{0} : new int[]{0, 6, 10};
                            for (int k = 0; k < hits.length; k++) {
                                int len = (k + 1 < hits.length ? hits[k + 1] : 16) - hits[k];
                                for (int i = 0; i < 3; i++) add(base + hits[k], len, Song.CHORD + i, power[i], vel, in);
                            }
                        } else if (heavy) {
                            int[] hits = sec.kind() == Kind.B && energy > 0.75
                                    ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15} : riff;
                            for (int k = 0; k < hits.length; k++) {
                                int t = hits[k];
                                boolean accent = t == 0 || t == 8 && energy > 0.5;
                                if (accent) {
                                    int len = (k + 1 < hits.length ? hits[k + 1] : 16) - t;
                                    for (int i = 0; i < 3; i++) add(base + t, len, Song.CHORD + i, power[i], vel, in);
                                } else {
                                    add(base + t, 0.7, Song.CHORD, power[0], vel * 0.8, in);
                                    add(base + t, 0.7, Song.CHORD + 1, power[1], vel * 0.7, in);
                                }
                            }
                        } else {
                            int step = energy < 0.4 ? 4 : 2;
                            for (int t = 0; t < 16; t += step) {
                                boolean accent = t % 8 == 0;
                                for (int i = 0; i < (accent ? 3 : 2); i++) {
                                    add(base + t, step * 0.9, Song.CHORD + i, power[i], vel * (accent ? 1 : 0.8), in);
                                }
                            }
                        }
                    }
                    case BOSSA -> {
                        int[] hits = {0, 3, 6, 10, 13};
                        for (int k = 0; k < hits.length; k++) {
                            if (k > 0 && random.nextDouble() > 0.55 + 0.45 * energy) continue;
                            for (int i = 0; i < 3; i++) {
                                add(base + hits[k], k == 2 ? 3.5 : 2.5, Song.CHORD + i, v[i], vel * (k == 0 ? 0.9 : 0.75), in);
                            }
                        }
                    }
                    case COMP -> {
                        // The pianist answers the soloist with a few short chords, in other places every bar.
                        int[] hits = comps[random.nextInt(comps.length)];
                        for (int t : hits) {
                            if (t > 0 && random.nextDouble() > 0.5 + 0.5 * energy) continue;
                            for (int i = 0; i < 3; i++) add(base + t, t % 4 == 0 ? 3 : 1.5, Song.CHORD + i, v[i], vel * 0.8, in);
                        }
                    }
                    case SKANK -> {
                        // Short chords off the beat.
                        for (int t = 2; t < 16; t += 4) {
                            for (int i = 0; i < 3; i++) add(base + t, 1.2, Song.CHORD + i, v[i], vel * 0.9, in);
                            if (energy > 0.65 && t % 8 == 6) {
                                for (int i = 0; i < 3; i++) add(base + t + 1, 0.8, Song.CHORD + i, v[i], vel * 0.6, in);
                            }
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
            int root = 36 + bassPerBar[Math.min(bars - 1, bar)];
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
                int fifth = root + fifthPerBar[bar];
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
                    case EIGHTHS -> {
                        boolean heavy = st.groove() == Style.Groove.DOUBLE_BASS;
                        int step = energy < 0.4 ? 4 : heavy && sec.kind() == Kind.B && energy > 0.75 ? 1 : 2;
                        for (int t = 0; t < 16; t += step) {
                            int pitch = !heavy && t == 14 && random.nextDouble() < 0.4 ? fifth : root;
                            add(base + t, step * 0.85, Song.BASS, pitch, vel * (t % 4 == 0 ? 1 : 0.85), in);
                        }
                    }
                    case BOSSA -> {
                        add(base, 5, Song.BASS, root, vel, in);
                        add(base + 6, 2, Song.BASS, fifth, vel * 0.8, in);
                        add(base + 8, 5, Song.BASS, fifth, vel * 0.9, in);
                        add(base + 14, 2, Song.BASS, root, vel * 0.8, in);
                    }
                    case REGGAE -> {
                        // A heavy, singing line that leaves room: it rests where the guitar plays.
                        boolean high = random.nextBoolean();
                        add(base, 2, Song.BASS, root, vel, in);
                        if (energy > 0.35) add(base + 3, 1, Song.BASS, root, vel * 0.8, in);
                        add(base + 4, 2, Song.BASS, fifth, vel * 0.9, in);
                        add(base + 8, 1.5, Song.BASS, high ? root + 12 : fifth, vel * 0.9, in);
                        if (energy > 0.5) add(base + 11, 1, Song.BASS, fifth, vel * 0.75, in);
                        add(base + 12, 2.5, Song.BASS, root, vel, in);
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
            // A busy piece gets one more drum whose hits are spread evenly over the bar, turned by the seed.
            Random layer = random(Part.DRUMS, "layer");
            int layerHits = new int[]{3, 5, 7}[layer.nextInt(3)];
            int layerTurn = layer.nextInt(Song.STEPS_PER_BAR);
            Drum layerDrum = switch (groove) {
                case BOOM_BAP, EPIC, BOSSA, SWING, ONE_DROP -> Drum.SHAKER;
                default -> Drum.TOM;
            };
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
                    case DOUBLE_BASS -> {
                        // Two bass drums: eighths, a gallop, or every sixteenth.
                        for (int t = 0; t < 16; t++) {
                            boolean on = e > 0.75 || (e > 0.45 ? t % 4 != 1 : t % 2 == 0);
                            if (on) hit(hits, t, Drum.KICK, t % 4 == 0 ? 1 : 0.7);
                        }
                        hit(hits, 4, Drum.SNARE, 1);
                        hit(hits, 12, Drum.SNARE, 1);
                        for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, t % 4 == 0 ? 0.6 : 0.4);
                        if (sec.kind() == Kind.B) hit(hits, 8, Drum.OPEN_HAT, 0.6);
                    }
                    case ROCK -> {
                        hit(hits, 0, Drum.KICK, 1);
                        hit(hits, 8, Drum.KICK, 0.9);
                        if (e > 0.4) hit(hits, 10, Drum.KICK, 0.85);
                        if (e > 0.7 && random.nextBoolean()) hit(hits, 7, Drum.KICK, 0.7);
                        hit(hits, 4, Drum.SNARE, 1);
                        hit(hits, 12, Drum.SNARE, 1);
                        // The chorus rides on the open cymbal.
                        if (sec.kind() == Kind.B) for (int t = 0; t < 16; t += 4) hit(hits, t, Drum.OPEN_HAT, 0.55);
                        else for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, t % 4 == 0 ? 0.65 : 0.45);
                    }
                    case BACKBEAT -> {
                        hit(hits, 0, Drum.KICK, 1);
                        hit(hits, 10, Drum.KICK, 0.85);
                        if (e > 0.5) hit(hits, 6, Drum.KICK, 0.75);
                        if (e > 0.75) hit(hits, 8, Drum.KICK, 0.8);
                        hit(hits, 4, Drum.SNARE, 0.9);
                        hit(hits, 12, Drum.SNARE, 0.95);
                        for (int t = 0; t < 16; t += e > 0.7 ? 1 : 2) {
                            hit(hits, t, Drum.HAT, t % 4 == 0 ? 0.55 : t % 2 == 0 ? 0.42 : 0.28);
                        }
                        if (random.nextDouble() < e * 0.5) hit(hits, 14, Drum.OPEN_HAT, 0.4);
                    }
                    case BOSSA -> {
                        for (int t : new int[]{0, 3, 6, 10, 13}) hit(hits, t, Drum.CLAP, t == 0 ? 0.7 : 0.6);
                        hit(hits, 0, Drum.KICK, 0.6);
                        hit(hits, 6, Drum.KICK, 0.45);
                        hit(hits, 8, Drum.KICK, 0.6);
                        hit(hits, 14, Drum.KICK, 0.45);
                        for (int t = 0; t < 16; t += e > 0.5 ? 1 : 2) {
                            hit(hits, t, Drum.SHAKER, t % 4 == 0 ? 0.5 : t % 2 == 0 ? 0.38 : 0.25);
                        }
                    }
                    case SWING -> {
                        // The ride cymbal: ding, ding-a ding, ding-a; the hi-hat closes on two and four.
                        for (int t : new int[]{0, 4, 6, 8, 12, 14}) hit(hits, t, Drum.HAT, t % 4 == 0 ? 0.6 : 0.4);
                        hit(hits, 4, Drum.SHAKER, 0.5);
                        hit(hits, 12, Drum.SHAKER, 0.5);
                        if (e > 0.4) {
                            hit(hits, 0, Drum.KICK, 0.4);
                            hit(hits, 8, Drum.KICK, 0.35);
                        }
                        for (int t : new int[]{6, 10, 14}) {
                            if (random.nextDouble() < e * 0.4) hit(hits, t, Drum.SNARE, 0.4);
                        }
                    }
                    case ONE_DROP -> {
                        // Bass drum and rim click fall together on two and four; one stays empty.
                        for (int t = 4; t < 16; t += 8) {
                            hit(hits, t, Drum.KICK, 0.95);
                            hit(hits, t, Drum.CLAP, 0.85);
                        }
                        if (e > 0.8) {
                            hit(hits, 0, Drum.KICK, 0.8);
                            hit(hits, 8, Drum.KICK, 0.8);
                        }
                        for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, t % 4 == 2 ? 0.6 : 0.4);
                        if (e > 0.6 && random.nextBoolean()) hit(hits, 15, Drum.HAT, 0.3);
                    }
                    case EPIC -> {
                        int[] pattern = euclid(5, 16, 0);
                        for (int t = 0; t < 16; t++) if (pattern[t] == 1) hit(hits, t, Drum.TOM, t == 0 ? 1 : 0.7);
                        hit(hits, 0, Drum.KICK, 0.9);
                        hit(hits, 12, Drum.SNARE, 0.9);
                        if (e > 0.5) for (int t = 0; t < 16; t += 2) hit(hits, t, Drum.HAT, 0.35);
                    }
                    default -> {
                    }
                }
                if (e > 0.5 && !intro && !outro) {
                    int[] pattern = euclid(layerHits + (sec.kind() == Kind.B ? 2 : 0), Song.STEPS_PER_BAR, layerTurn);
                    for (int t = 0; t < Song.STEPS_PER_BAR; t++) {
                        int step = t;
                        boolean taken = hits.stream().anyMatch(h -> h[0] == step
                                && channel(Drum.values()[(int) h[1]]) == channel(layerDrum));
                        if (pattern[t] == 1 && !taken) hit(hits, t, layerDrum, t % 4 == 0 ? 0.42 : 0.32);
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
            int channel = channel(d);
            double v = Math.min(1, velocity * (0.93 + 0.1 * random.nextDouble()));
            notes.add(new Note(step, 1, channel, 60, Math.max(0.05, v), null, d));
        }

        static void hit(List<double[]> hits, double step, Drum d, double velocity) {
            hits.add(new double[]{step, d.ordinal(), velocity});
        }

        /** The channel a drum is played on: the kick, the drums with a skin, the cymbals and shakers. */
        static int channel(Drum d) {
            return switch (d) {
                case KICK -> Song.KICK;
                case SNARE, CLAP, TOM -> Song.SNARE;
                default -> Song.HATS;
            };
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

        // ---------------------------------------------------------------- pad and arpeggio

        /** Two long notes of every chord, held until the chord changes or two bars have passed. */
        void pad() {
            Random random = random(Part.PAD, "pad");
            Instrument in = r.pad().instrument();
            int voicing = random.nextInt(3);
            int[] previous = null;
            for (int bar = 0; bar < bars; ) {
                Section sec = section(bar);
                int[] pcs = chordsPerBar[bar];
                int run = 1;
                if (bar + 1 < sec.endBar() && Arrays.equals(pcs, chordsPerBar[bar + 1])) run = 2;
                int[] v = voice(pcs, previous, 60);
                previous = v;
                int low = switch (voicing) {
                    case 0 -> v[0];
                    case 1 -> v[1];
                    default -> 48 + pcs[0];
                };
                int high = voicing == 2 ? v[1] : v[2];
                double vel = edge(sec) ? 0.5 : sec.kind() == Kind.B ? 0.68 : 0.6;
                if (sec.kind() == Kind.OUTRO) vel *= 1 - 0.5 * (bar - sec.startBar()) / (double) sec.bars();
                int base = bar * Song.STEPS_PER_BAR;
                add(base, run * Song.STEPS_PER_BAR, Song.PAD, low, vel, in);
                add(base, run * Song.STEPS_PER_BAR, Song.PAD + 1, high, vel, in);
                bar += run;
            }
        }

        /**
         * The notes of the chord one after another, up, down, up and down or in leaps; on sixteenths, or on
         * eighths in slow and sparse pieces. The rhythm is Euclidean: the busier the piece, the more of the
         * steps are played, spread evenly and turned by the seed so that the bar still starts with a note. The
         * figure starts anew with every bar and differs between A and B.
         */
        void arpeggio() {
            Random random = random(Part.ARPEGGIO, "arpeggio");
            Instrument in = r.arpeggio().instrument();
            int stepLength = st.barsPerChord() == 2 || energy < 0.4 ? 2 : 1;
            int shapeA = random.nextInt(4);
            int shapeB = (shapeA + 1 + random.nextInt(3)) % 4;
            int span = 5 + random.nextInt(3);
            int steps = Song.STEPS_PER_BAR / stepLength;
            int played = Math.max(3, Math.min(steps, (int) Math.round(steps * (0.55 + 0.45 * energy))));
            int[] rhythmA = startingWithANote(euclid(played, steps, 0), random.nextInt(played));
            int[] rhythmB = startingWithANote(euclid(played, steps, 0), random.nextInt(played));
            for (int bar = 0; bar < bars; bar++) {
                Section sec = section(bar);
                if (sec.kind() == Kind.INTRO && bar < sec.startBar() + sec.bars() / 2) continue;
                if (sec.kind() == Kind.OUTRO && bar > sec.startBar()) continue;
                int[] pcs = chordsPerBar[bar];
                int[] ladder = new int[span];
                for (int pitch = 60, i = 0; i < span; pitch++) {
                    for (int c = 0; c < pcs.length; c++) {
                        if (pitch % 12 == pcs[c]) {
                            ladder[i++] = pitch;
                            break;
                        }
                    }
                }
                int shape = sec.kind() == Kind.B ? shapeB : shapeA;
                int[] rhythm = sec.kind() == Kind.B ? rhythmB : rhythmA;
                double vel = edge(sec) ? 0.5 : 0.62;
                int base = bar * Song.STEPS_PER_BAR;
                for (int s = 0, i = 0; s < steps; s++) {
                    if (rhythm[s] == 0) continue;
                    int next = s + 1;
                    while (next < steps && rhythm[next] == 0) next++;
                    int t = s * stepLength;
                    add(base + t, Math.min(4, (next - s) * stepLength), Song.ARPEGGIO, ladder[rung(shape, i++, span)],
                            vel + (t % 4 == 0 ? 0.15 : 0), in);
                }
            }
        }


        /** The rung of the ladder for the {@code i}-th note of a figure. */
        static int rung(int shape, int i, int span) {
            int around = 2 * span - 2;
            return switch (shape) {
                case 0 -> i % span;
                case 1 -> span - 1 - i % span;
                case 2 -> i % around < span ? i % around : around - i % around;
                default -> Math.min(span - 1, (i / 2) % (span - 1) + (i % 2 == 0 ? 0 : 2));
            };
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
