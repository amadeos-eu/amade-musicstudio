package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.compose.Note;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a piece as an eight-channel module ("8CHN"), which tracker programs and many players open. The
 * synthesizer renders every instrument into one sample: each drum of the kit with its hardness, and each tonal part
 * one note of its instrument, looped with a crossfade when the instrument holds its sound. One row is one
 * sixteenth; {@code Fxx} sets the tempo, {@code Cxx} the loudness of a note, {@code EDx} delays the swung notes and
 * {@code 8xx} places the channels left or right. The recipe goes into the names of the unused samples.
 */
public final class ModWriter {
    /** The periods of the module format for the three octaves C-1 to B-3. */
    static final int[] PERIODS = {856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453, 428, 404, 381, 360, 339,
            320, 302, 285, 269, 254, 240, 226, 214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113};
    /** The playback rate of a sample at period 428 (C-2) on a PAL machine. */
    static final double C2_RATE = 7093789.2 / (428 * 2);
    static final int SAMPLES = 31;
    static final int NAME = 22;
    static final int MAX_SAMPLE_BYTES = 131_070;
    static final int ROWS = 64;
    static final int MAX_PATTERNS = 128;
    static final int TICKS = 6;

    private final AudioRenderer renderer;

    public ModWriter(AudioRenderer renderer) {
        this.renderer = renderer;
    }

    private static final class Sample {
        String name;
        byte[] data;
        int loopStart;
        int loopLength;
        int basePitch;
        double peak;
        boolean drum;
        Part part;
    }

    /**
     * Writes the module.
     *
     * @param comment text for the free sample names, for example the recipe; {@code null} for none
     * @throws IOException also when the piece is too long for a module (more than 128 patterns)
     */
    public void write(Song song, String comment, OutputStream out) throws IOException {
        Recipe r = song.recipe();
        int rows = ((song.steps() + ROWS - 1) / ROWS) * ROWS;
        if (rows / ROWS > MAX_PATTERNS) throw new IOException("The piece is too long for a module");

        Map<String, int[]> range = new HashMap<>();
        for (Note n : song.notes()) {
            if (!n.isDrum()) {
                range.merge(key(n), new int[]{n.pitch(), n.pitch()},
                        (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            }
        }
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (Note n : song.notes()) {
            String k = key(n);
            if (!samples.containsKey(k)) samples.put(k, sample(r, n, range.get(k)));
        }
        List<Sample> list = new ArrayList<>(samples.values());
        if (list.size() > SAMPLES) throw new IOException("Too many instruments for a module");

        // The loudest note gets volume 64; the others follow the mix.
        double maxLevel = 1e-9;
        for (Note n : song.notes()) maxLevel = Math.max(maxLevel, level(r, n, samples.get(key(n))));

        Set<Long> noteStarts = new HashSet<>();
        for (Note n : song.notes()) noteStarts.add(cellId((int) Math.round(n.step()), n.channel()));

        int[][][] cell = new int[rows][Song.CHANNELS][]; // sample (1-based), note index, effect, parameter
        for (Note n : song.notes()) {
            int row = (int) Math.round(n.step());
            if (row >= rows) continue;
            Sample s = samples.get(key(n));
            int index = s.drum ? 35 : n.pitch() - s.basePitch + 24;
            while (index < 0) index += 12;
            while (index > 35) index -= 12;
            int volume = (int) Math.round(64 * level(r, n, s) / maxLevel);
            if (volume <= 0) continue;
            int effect = 0xC;
            int parameter = Math.min(64, volume);
            double swing = song.heardAt(n) - n.step();
            if (swing > 0.04) {
                effect = 0xE;
                parameter = 0xD0 | Math.min(TICKS - 1, (int) Math.round(swing * TICKS));
            }
            cell[row][n.channel()] = new int[]{list.indexOf(s) + 1, index, effect, parameter};
            if (!s.drum && s.loopLength > 0) {
                // A held sound fades with a volume slide after the note ends.
                int end = (int) Math.round(n.step() + n.length());
                for (int k = 0; k < 2; k++) {
                    int rr = end + k;
                    if (rr < rows && cell[rr][n.channel()] == null && !noteStarts.contains(cellId(rr, n.channel()))) {
                        cell[rr][n.channel()] = new int[]{0, -1, 0xA, 0x0F};
                    }
                }
            }
        }
        placeControls(r, cell);

        List<byte[]> patterns = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int p = 0; p < rows / ROWS; p++) {
            byte[] pattern = pattern(cell, p);
            int found = -1;
            for (int q = 0; q < patterns.size() && found < 0; q++) if (Arrays.equals(patterns.get(q), pattern)) found = q;
            if (found < 0) {
                patterns.add(pattern);
                found = patterns.size() - 1;
            }
            order.add(found);
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(fixed(r.style().id() + " " + r.seed(), 20));
        List<String> notes = commentNames(comment, SAMPLES - list.size());
        for (int i = 0; i < SAMPLES; i++) {
            if (i < list.size()) {
                Sample s = list.get(i);
                o.write(fixed(s.name, NAME));
                word(o, s.data.length / 2);
                o.write(0);
                o.write(64);
                word(o, s.loopLength > 0 ? s.loopStart / 2 : 0);
                word(o, s.loopLength > 0 ? s.loopLength / 2 : 1);
            } else {
                int free = i - list.size();
                o.write(fixed(free < notes.size() ? notes.get(free) : "", NAME));
                word(o, 0);
                o.write(0);
                o.write(0);
                word(o, 0);
                word(o, 1);
            }
        }
        o.write(order.size());
        o.write(127);
        for (int i = 0; i < MAX_PATTERNS; i++) o.write(i < order.size() ? order.get(i) : 0);
        o.write("8CHN".getBytes(StandardCharsets.US_ASCII));
        for (byte[] p : patterns) o.write(p);
        for (Sample s : list) o.write(s.data);
        o.writeTo(out);
    }

    private Sample sample(Recipe r, Note n, int[] range) {
        Sample s = new Sample();
        s.drum = n.isDrum();
        s.part = Song.part(n.channel());
        float[] pcm;
        if (s.drum) {
            s.name = n.drum().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            // Played at B-3, the highest note, for the best sound.
            pcm = renderer.drumSample(r, n.drum(), C2_RATE * Math.pow(2, 23 / 12.0));
        } else {
            s.name = r.voice(s.part).instrument().id() + " (" + s.part.id() + ")";
            // Rendered at twice the C-2 rate: the base pitch sounds at index 24 (C-3). The octave is chosen so that
            // every note of the part fits into the three octaves of a module.
            int base = 12 * (int) Math.ceil((range[1] - 11) / 12.0);
            if (base - 24 > range[0]) base = range[0] + 24 - ((range[0] + 24) % 12);
            s.basePitch = base;
            double rate = C2_RATE * 2;
            boolean sustained = r.voice(s.part).instrument().sustained();
            pcm = renderer.noteSample(r, s.part, base, sustained ? 1.6 : 2.4, rate);
            if (sustained && pcm.length > (int) (1.5 * rate)) {
                int loopStart = (int) (0.7 * rate) & ~1;
                int loopEnd = (int) (1.5 * rate) & ~1;
                int fade = (int) (0.15 * rate);
                for (int i = 0; i < fade; i++) {
                    double a = i / (double) fade;
                    pcm[loopEnd - fade + i] = (float) (pcm[loopEnd - fade + i] * (1 - a) + pcm[loopStart - fade + i] * a);
                }
                pcm = Arrays.copyOf(pcm, loopEnd);
                s.loopStart = loopStart;
                s.loopLength = loopEnd - loopStart;
            }
        }
        double peak = 1e-9;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        s.peak = peak;
        int length = Math.max(2, Math.min(pcm.length, MAX_SAMPLE_BYTES) & ~1);
        s.data = new byte[length];
        // The first two bytes stay silent: some players repeat them when a sample does not loop.
        for (int i = 2; i < Math.min(length, pcm.length); i++) {
            s.data[i] = (byte) Math.round(Math.max(-127, Math.min(127, pcm[i] / peak * 127)));
        }
        return s;
    }

    /** How loud a note is in the mix, before the scaling to the module's 0 to 64. */
    private static double level(Recipe r, Note n, Sample s) {
        Part part = Song.part(n.channel());
        return AudioRenderer.channelGain(n.channel())
                * AudioRenderer.instrumentGain(r, n.channel(), n.instrument(), n.drum())
                * (0.25 + 0.75 * n.velocity()) * s.peak * r.volume(part) / 75.0;
    }

    /** Tempo ({@code Fxx}, 32 and up is BPM) and stereo positions ({@code 8xx}) in free cells of the first rows. */
    private static void placeControls(Recipe r, int[][][] cell) {
        List<int[]> controls = new ArrayList<>();
        controls.add(new int[]{0xF, Math.max(32, Math.min(255, r.bpm())), -1});
        for (int ch = 0; ch < Song.CHANNELS; ch++) {
            int pan = (int) Math.round(128 + AudioRenderer.pan(ch) * 160);
            controls.add(new int[]{0x8, Math.max(0, Math.min(255, pan)), ch});
        }
        for (int[] control : controls) {
            boolean placed = false;
            int from = control[2] < 0 ? 0 : control[2];
            int to = control[2] < 0 ? Song.CHANNELS - 1 : control[2];
            for (int row = 0; row < Math.min(8, cell.length) && !placed; row++) {
                for (int ch = from; ch <= to && !placed; ch++) {
                    int[] c = cell[row][ch];
                    if (c == null) {
                        cell[row][ch] = new int[]{0, -1, control[0], control[1]};
                        placed = true;
                    } else if (c[2] == 0 || c[2] == 0xA) {
                        c[2] = control[0];
                        c[3] = control[1];
                        placed = true;
                    }
                }
            }
        }
    }

    private static byte[] pattern(int[][][] cell, int p) {
        byte[] pattern = new byte[ROWS * Song.CHANNELS * 4];
        for (int row = 0; row < ROWS; row++) {
            for (int ch = 0; ch < Song.CHANNELS; ch++) {
                int[] c = cell[p * ROWS + row][ch];
                if (c == null) continue;
                int o = (row * Song.CHANNELS + ch) * 4;
                int period = c[1] >= 0 ? PERIODS[c[1]] : 0;
                pattern[o] = (byte) ((c[0] & 0xF0) | ((period >> 8) & 0x0F));
                pattern[o + 1] = (byte) (period & 0xFF);
                pattern[o + 2] = (byte) (((c[0] & 0x0F) << 4) | (c[2] & 0x0F));
                pattern[o + 3] = (byte) c[3];
            }
        }
        return pattern;
    }

    private static long cellId(int row, int channel) {
        return (long) row * Song.CHANNELS + channel;
    }

    private static String key(Note n) {
        return n.isDrum() ? "drum/" + n.drum().name() : Song.part(n.channel()).id();
    }

    /** The comment cut into sample names; nothing when it does not fit, since half a recipe is of no use. */
    static List<String> commentNames(String comment, int free) {
        List<String> names = new ArrayList<>();
        if (comment == null || comment.isEmpty()) return names;
        String ascii = ascii(comment);
        if (ascii.length() > free * NAME) return names;
        for (int i = 0; i < ascii.length(); i += NAME) names.add(ascii.substring(i, Math.min(ascii.length(), i + NAME)));
        return names;
    }

    /** The text in the names of the empty samples of a module, joined; empty when there is none. */
    public static String readComment(byte[] module) {
        if (module.length < 1084) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < SAMPLES; i++) {
            int o = 20 + i * 30;
            int length = ((module[o + 22] & 0xFF) << 8) | (module[o + 23] & 0xFF);
            if (length != 0) continue;
            int end = 0;
            while (end < NAME && module[o + end] != 0) end++;
            text.append(new String(module, o, end, StandardCharsets.US_ASCII));
        }
        return text.toString();
    }

    private static String ascii(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 32 && c < 127 ? c : '?');
        return b.toString();
    }

    private static byte[] fixed(String s, int length) {
        byte[] b = new byte[length];
        byte[] src = ascii(s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, 0, Math.min(length, src.length));
        return b;
    }

    private static void word(ByteArrayOutputStream o, int w) {
        o.write((w >> 8) & 0xFF);
        o.write(w & 0xFF);
    }
}
