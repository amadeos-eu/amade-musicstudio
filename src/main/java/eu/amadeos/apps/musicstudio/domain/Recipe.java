package eu.amadeos.apps.musicstudio.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything a piece depends on. The same recipe gives the same piece on every computer.
 *
 * <p>Each part has a seed of its own. They all start equal to {@link #seed()}; a new piece keeps the seeds of
 * the parts marked "keep" and gives the others the new seed. The key comes from the chords' seed, unless
 * {@link #key()} fixes it.
 *
 * @param key           -1 for the key the chords' seed gives, else 0 (C) to 11 (B)
 * @param bpm           tempo in beats per minute
 * @param lengthSeconds the length of the piece, roughly; it always ends after whole bars
 * @param hardness      0 (dull) to 100 (hard): the drums' filter, click and drive
 * @param swing         0 (straight) to 100 (swung): how late every second sixteenth comes
 * @param energy        0 (sparse) to 100 (busy): more drum hits and melody notes
 * @param fills         0 (never) to 100 (often): drum fills at the end of a section
 * @param variety       0 (steady) to 100 (changing): how often melody, bass and drums rest and come back
 * @param brightness    0 (warm) to 100 (bright): the filter on melody and chords
 * @param reverb        0 (dry) to 100 (hall)
 * @param crackle       0 (clean) to 100: noise and clicks like an old record
 * @param drumVolume    0 to 100
 */
public record Recipe(Style style, long seed, long melodySeed, long chordSeed, long bassSeed, long drumSeed, int key,
                     int bpm, int lengthSeconds, int hardness, int swing, int energy, int fills, int variety,
                     int brightness, int reverb, int crackle, Voice melody, Voice chords, Voice bass, Kit kit,
                     int drumVolume) {
    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int MIN_BPM = 40;
    public static final int MAX_BPM = 200;
    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 600;
    /** The lengths offered, in seconds. */
    public static final List<Integer> LENGTHS = List.of(30, 60, 90, 120, 180, 300);
    public static final int DEFAULT_LENGTH = 60;
    public static final int DEFAULT_FILLS = 50;
    /** The first line of a recipe file. */
    public static final String HEADER = "# amadeOS Music Studio recipe";
    /** The largest seed; seeds are typed in, so they stay short. */
    public static final long MAX_SEED = 999_999_999L;

    public Recipe {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(melody, "melody");
        Objects.requireNonNull(chords, "chords");
        Objects.requireNonNull(bass, "bass");
        Objects.requireNonNull(kit, "kit");
        for (long s : new long[]{seed, melodySeed, chordSeed, bassSeed, drumSeed}) {
            if (s < 0 || s > MAX_SEED) throw new IllegalArgumentException("Seed out of range: " + s);
        }
        key = key < 0 ? -1 : Math.min(11, key);
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, bpm));
        lengthSeconds = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, lengthSeconds));
        hardness = clamp(hardness);
        swing = clamp(swing);
        energy = clamp(energy);
        fills = clamp(fills);
        variety = clamp(variety);
        brightness = clamp(brightness);
        reverb = clamp(reverb);
        crackle = clamp(crackle);
        drumVolume = clamp(drumVolume);
    }

    static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    /** A piece of the style with its usual tempo, sliders and instruments; every part uses the seed. */
    public static Recipe of(Style style, long seed) {
        return new Recipe(style, seed, seed, seed, seed, seed, -1, style.bpm(), DEFAULT_LENGTH,
                style.defaultHardness(), style.defaultSwing(), style.defaultEnergy(), DEFAULT_FILLS,
                style.defaultVariety(), style.defaultBrightness(), style.defaultReverb(), style.defaultCrackle(),
                Voice.of(style.defaultInstrument(Part.MELODY)), Voice.of(style.defaultInstrument(Part.CHORDS)),
                Voice.of(style.defaultInstrument(Part.BASS)), style.defaultKit(), Voice.DEFAULT_VOLUME);
    }

    /** The seed of a part. */
    public long seed(Part part) {
        return switch (part) {
            case MELODY -> melodySeed;
            case CHORDS -> chordSeed;
            case BASS -> bassSeed;
            case DRUMS -> drumSeed;
        };
    }

    /** The voice of a tonal part. */
    public Voice voice(Part part) {
        return switch (part) {
            case MELODY -> melody;
            case CHORDS -> chords;
            case BASS -> bass;
            case DRUMS -> throw new IllegalArgumentException("The drums have a kit, not a voice");
        };
    }

    /** The volume of a part, 0 to 100. */
    public int volume(Part part) {
        return part == Part.DRUMS ? drumVolume : voice(part).volume();
    }

    /** Whether all parts use the main seed. */
    public boolean oneSeed() {
        return melodySeed == seed && chordSeed == seed && bassSeed == seed && drumSeed == seed;
    }

    /** Another style with its tempo, sliders and instruments; the seeds and the length stay. */
    public Recipe withStyle(Style s) {
        Recipe fresh = of(s, seed);
        return fresh.with(b -> {
            b.melodySeed = melodySeed;
            b.chordSeed = chordSeed;
            b.bassSeed = bassSeed;
            b.drumSeed = drumSeed;
            b.lengthSeconds = lengthSeconds;
            b.key = key;
        });
    }

    /** All parts take the seed. */
    public Recipe withSeed(long s) {
        return with(b -> {
            b.seed = s;
            b.melodySeed = s;
            b.chordSeed = s;
            b.bassSeed = s;
            b.drumSeed = s;
        });
    }

    /** A new piece: the parts in {@code kept} keep their seed, the others take the new one. */
    public Recipe newPiece(long s, Set<Part> kept) {
        return with(b -> {
            b.seed = s;
            if (!kept.contains(Part.MELODY)) b.melodySeed = s;
            if (!kept.contains(Part.CHORDS)) b.chordSeed = s;
            if (!kept.contains(Part.BASS)) b.bassSeed = s;
            if (!kept.contains(Part.DRUMS)) b.drumSeed = s;
        });
    }

    /** One part gets another seed, the others stay. */
    public Recipe withPartSeed(Part part, long s) {
        return with(b -> {
            switch (part) {
                case MELODY -> b.melodySeed = s;
                case CHORDS -> b.chordSeed = s;
                case BASS -> b.bassSeed = s;
                case DRUMS -> b.drumSeed = s;
            }
        });
    }

    public Recipe withVoice(Part part, Voice voice) {
        return with(b -> {
            switch (part) {
                case MELODY -> b.melody = voice;
                case CHORDS -> b.chords = voice;
                case BASS -> b.bass = voice;
                case DRUMS -> throw new IllegalArgumentException("The drums have a kit, not a voice");
            }
        });
    }

    public Recipe withVolume(Part part, int volume) {
        if (part == Part.DRUMS) return with(b -> b.drumVolume = volume);
        return withVoice(part, voice(part).withVolume(volume));
    }

    /** A copy with some values changed: {@code recipe.with(b -> b.bpm = 90)}. */
    public Recipe with(Consumer<Builder> change) {
        Builder b = new Builder(this);
        change.accept(b);
        return b.build();
    }

    /** The values of a recipe to change; see {@link #with}. */
    public static final class Builder {
        public Style style;
        public long seed;
        public long melodySeed;
        public long chordSeed;
        public long bassSeed;
        public long drumSeed;
        public int key;
        public int bpm;
        public int lengthSeconds;
        public int hardness;
        public int swing;
        public int energy;
        public int fills;
        public int variety;
        public int brightness;
        public int reverb;
        public int crackle;
        public Voice melody;
        public Voice chords;
        public Voice bass;
        public Kit kit;
        public int drumVolume;

        Builder(Recipe r) {
            style = r.style;
            seed = r.seed;
            melodySeed = r.melodySeed;
            chordSeed = r.chordSeed;
            bassSeed = r.bassSeed;
            drumSeed = r.drumSeed;
            key = r.key;
            bpm = r.bpm;
            lengthSeconds = r.lengthSeconds;
            hardness = r.hardness;
            swing = r.swing;
            energy = r.energy;
            fills = r.fills;
            variety = r.variety;
            brightness = r.brightness;
            reverb = r.reverb;
            crackle = r.crackle;
            melody = r.melody;
            chords = r.chords;
            bass = r.bass;
            kit = r.kit;
            drumVolume = r.drumVolume;
        }

        Recipe build() {
            return new Recipe(style, seed, melodySeed, chordSeed, bassSeed, drumSeed, key, bpm, lengthSeconds,
                    hardness, swing, energy, fills, variety, brightness, reverb, crackle, melody, chords, bass,
                    kit, drumVolume);
        }
    }

    // ---------------------------------------------------------------- text

    /** The recipe as text, one {@code key=value} per line; {@link #parse} reads it back. */
    public String toText() {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, String> e : values().entrySet()) {
            text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return text.toString();
    }

    /** The recipe on one line, for files and the clipboard: {@code style=lofi; seed=4711; ...} */
    public String toLine() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : values().entrySet()) {
            if (!text.isEmpty()) text.append("; ");
            text.append(e.getKey()).append('=').append(e.getValue());
        }
        return text.toString();
    }

    private Map<String, String> values() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("style", style.id());
        v.put("seed", Long.toString(seed));
        for (Part p : Part.values()) {
            if (seed(p) != seed) v.put("seed." + p.id(), Long.toString(seed(p)));
        }
        if (key >= 0) v.put("key", Integer.toString(key));
        v.put("bpm", Integer.toString(bpm));
        v.put("length", Integer.toString(lengthSeconds));
        v.put("hardness", Integer.toString(hardness));
        v.put("swing", Integer.toString(swing));
        v.put("energy", Integer.toString(energy));
        v.put("fills", Integer.toString(fills));
        v.put("variety", Integer.toString(variety));
        v.put("brightness", Integer.toString(brightness));
        v.put("reverb", Integer.toString(reverb));
        v.put("crackle", Integer.toString(crackle));
        v.put("melody", melody.toText());
        v.put("chords", chords.toText());
        v.put("bass", bass.toText());
        v.put("drums", kit.id() + "," + drumVolume);
        return v;
    }

    /**
     * Reads a recipe written by {@link #toText()} or {@link #toLine()}. Style and seed must be there; every
     * other value missing or wrong takes the style's default.
     *
     * @throws IllegalArgumentException if the text is not a recipe
     */
    public static Recipe parse(String text) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String part : text.split("[\\n;]")) {
            String p = part.strip();
            if (p.isEmpty() || p.startsWith("#")) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            v.put(p.substring(0, eq).strip(), p.substring(eq + 1).strip());
        }
        Style style = Style.ofId(v.get("style"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown or missing style: " + v.get("style")));
        long seed = seedValue(v.get("seed"));
        if (seed < 0) throw new IllegalArgumentException("Missing or wrong seed: " + v.get("seed"));
        Recipe d = of(style, seed);
        String[] drums = v.getOrDefault("drums", "").split(",");
        Kit kit = Kit.ofId(drums[0].strip()).orElse(d.kit);
        int drumVolume = drums.length > 1 ? number(drums[1], d.drumVolume) : d.drumVolume;
        return new Recipe(style, seed, partSeed(v, Part.MELODY, seed), partSeed(v, Part.CHORDS, seed),
                partSeed(v, Part.BASS, seed), partSeed(v, Part.DRUMS, seed), number(v.get("key"), -1),
                number(v.get("bpm"), d.bpm), number(v.get("length"), d.lengthSeconds),
                number(v.get("hardness"), d.hardness), number(v.get("swing"), d.swing),
                number(v.get("energy"), d.energy), number(v.get("fills"), d.fills), number(v.get("variety"), d.variety),
                number(v.get("brightness"), d.brightness), number(v.get("reverb"), d.reverb),
                number(v.get("crackle"), d.crackle), Voice.parse(v.get("melody"), d.melody),
                Voice.parse(v.get("chords"), d.chords), Voice.parse(v.get("bass"), d.bass), kit, drumVolume);
    }

    private static long partSeed(Map<String, String> v, Part part, long fallback) {
        long s = seedValue(v.get("seed." + part.id()));
        return s < 0 ? fallback : s;
    }

    private static long seedValue(String text) {
        if (text == null) return -1;
        try {
            long s = Long.parseLong(text.strip());
            return s >= 0 && s <= MAX_SEED ? s : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int number(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "lofi-4711", for file names. */
    public String fileName() {
        return style.id() + "-" + seed;
    }
}
