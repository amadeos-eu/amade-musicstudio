package eu.amadeos.apps.musicstudio.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The styles and their rules: tempo, scales, how chords, bass and drums are played, the instruments a new
 * piece starts with and where the sliders start. Never reorder or remove a style: recipes store the id, and
 * pieces depend on these numbers, so a change here changes every saved piece of the style.
 */
public enum Style {
    AMBIENT(72, Scale.LYDIAN, Scale.LYDIAN, 2, false, Accompaniment.SUSTAIN, BassLine.LONG, Groove.HALFTIME,
            Instrument.BELLS, Instrument.WARM_PAD, Instrument.SUB_BASS, Kit.NONE,
            30, 0, 25, 20, 85, 45, 0, 74, 86),
    LOFI(82, Scale.DORIAN, Scale.DORIAN, 1, true, Accompaniment.STABS, BassLine.BOOM, Groove.BOOM_BAP,
            Instrument.FLUTE, Instrument.ELECTRIC_PIANO, Instrument.ELECTRIC_BASS, Kit.LOFI,
            35, 55, 45, 55, 35, 35, 60, 67, 81),
    CHIPTUNE(140, Scale.MAJOR, Scale.MAJOR, 1, false, Accompaniment.ARPEGGIO, BassLine.PULSE, Groove.CHIP,
            Instrument.CHIP_SQUARE, Instrument.CHIP_SQUARE, Instrument.CHIP_TRIANGLE, Kit.CHIP,
            65, 0, 60, 60, 15, 80, 0, 69, 84),
    PIANO(92, Scale.MAJOR, Scale.MAJOR, 1, false, Accompaniment.ALBERTI, BassLine.PIANO, Groove.NONE,
            Instrument.PIANO, Instrument.PIANO, Instrument.PIANO, Kit.NONE,
            50, 0, 40, 30, 45, 60, 0, 67, 84),
    ELECTRO(124, Scale.MINOR, Scale.MINOR, 1, false, Accompaniment.STABS, BassLine.OFFBEAT, Groove.FOUR_ON_THE_FLOOR,
            Instrument.SYNTH_LEAD, Instrument.WARM_PAD, Instrument.SYNTH_BASS, Kit.ELECTRONIC,
            75, 0, 65, 70, 30, 75, 0, 67, 81),
    MEDITATION(60, Scale.PENTATONIC, Scale.MAJOR, 2, false, Accompaniment.SUSTAIN, BassLine.LONG, Groove.NONE,
            Instrument.FLUTE, Instrument.STRINGS, Instrument.SUB_BASS, Kit.NONE,
            20, 0, 20, 10, 90, 40, 0, 67, 81),
    ACOUSTIC(104, Scale.MAJOR, Scale.MAJOR, 1, false, Accompaniment.STRUM, BassLine.WALK, Groove.FOLK,
            Instrument.MARIMBA, Instrument.GUITAR, Instrument.ELECTRIC_BASS, Kit.SOFT,
            50, 25, 30, 40, 30, 60, 0, 69, 84),
    ADVENTURE(100, Scale.HARMONIC_MINOR, Scale.MINOR, 1, false, Accompaniment.BROKEN, BassLine.WALK, Groove.EPIC,
            Instrument.FLUTE, Instrument.HARP, Instrument.STRINGS, Kit.SOFT,
            55, 0, 55, 65, 60, 60, 0, 69, 86);

    /** Scales as semitones above the key note. */
    public enum Scale {
        MAJOR(0, 2, 4, 5, 7, 9, 11), MINOR(0, 2, 3, 5, 7, 8, 10), DORIAN(0, 2, 3, 5, 7, 9, 10),
        LYDIAN(0, 2, 4, 6, 7, 9, 11), PENTATONIC(0, 2, 4, 7, 9), HARMONIC_MINOR(0, 2, 3, 5, 7, 8, 11);

        private final int[] steps;

        Scale(int... steps) {
            this.steps = steps;
        }

        public int[] steps() {
            return steps.clone();
        }

        public int size() {
            return steps.length;
        }

        public int step(int index) {
            return steps[index];
        }

        /** Minor if the third step is three semitones up. */
        public boolean minor() {
            return steps[2] == 3;
        }
    }

    /** How the chords are played. */
    public enum Accompaniment { SUSTAIN, STABS, ARPEGGIO, ALBERTI, BROKEN, STRUM }

    /** How the bass is played. */
    public enum BassLine { LONG, PULSE, OFFBEAT, BOOM, WALK, PIANO }

    /** The drum patterns. */
    public enum Groove { NONE, HALFTIME, BOOM_BAP, CHIP, FOUR_ON_THE_FLOOR, FOLK, EPIC }

    private final int bpm;
    private final Scale melodyScale;
    private final Scale chordScale;
    private final int barsPerChord;
    private final boolean sevenths;
    private final Accompaniment accompaniment;
    private final BassLine bassLine;
    private final Groove groove;
    private final Instrument lead;
    private final Instrument chords;
    private final Instrument bass;
    private final Kit kit;
    private final double melodyDensity;
    private final int swing;
    private final int hardness;
    private final int energy;
    private final int reverb;
    private final int brightness;
    private final int crackle;
    private final int leadLow;
    private final int leadHigh;

    Style(int bpm, Scale melodyScale, Scale chordScale, int barsPerChord, boolean sevenths, Accompaniment accompaniment,
          BassLine bassLine, Groove groove, Instrument lead, Instrument chords, Instrument bass, Kit kit,
          int melodyDensity, int swing, int hardness, int energy, int reverb, int brightness, int crackle,
          int leadLow, int leadHigh) {
        this.bpm = bpm;
        this.melodyScale = melodyScale;
        this.chordScale = chordScale;
        this.barsPerChord = barsPerChord;
        this.sevenths = sevenths;
        this.accompaniment = accompaniment;
        this.bassLine = bassLine;
        this.groove = groove;
        this.lead = lead;
        this.chords = chords;
        this.bass = bass;
        this.kit = kit;
        this.melodyDensity = melodyDensity / 100.0;
        this.swing = swing;
        this.hardness = hardness;
        this.energy = energy;
        this.reverb = reverb;
        this.brightness = brightness;
        this.crackle = crackle;
        this.leadLow = leadLow;
        this.leadHigh = leadHigh;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "style." + id();
    }

    /** The message key of the line below the name, e.g. "Relaxed beats". */
    public String subtitleKey() {
        return key() + ".sub";
    }

    public static Optional<Style> ofId(String id) {
        for (Style s : values()) if (s.id().equals(id)) return Optional.of(s);
        return Optional.empty();
    }

    public int bpm() {
        return bpm;
    }

    public Scale melodyScale() {
        return melodyScale;
    }

    public Scale chordScale() {
        return chordScale;
    }

    /** One chord lasts one or two bars. */
    public int barsPerChord() {
        return barsPerChord;
    }

    /** Whether chords get their seventh. */
    public boolean sevenths() {
        return sevenths;
    }

    public Accompaniment accompaniment() {
        return accompaniment;
    }

    public BassLine bassLine() {
        return bassLine;
    }

    /** The drum pattern; styles without drums play {@link Groove#HALFTIME} when a kit is chosen. */
    public Groove groove() {
        return groove == Groove.NONE ? Groove.HALFTIME : groove;
    }

    public Instrument defaultInstrument(Part part) {
        return switch (part) {
            case MELODY -> lead;
            case CHORDS -> chords;
            default -> bass;
        };
    }

    public Kit defaultKit() {
        return kit;
    }

    /** How many melody notes a bar gets, 0 to 1. */
    public double melodyDensity() {
        return melodyDensity;
    }

    public int defaultSwing() {
        return swing;
    }

    public int defaultHardness() {
        return hardness;
    }

    public int defaultEnergy() {
        return energy;
    }

    public int defaultReverb() {
        return reverb;
    }

    public int defaultBrightness() {
        return brightness;
    }

    public int defaultCrackle() {
        return crackle;
    }

    /** The range of the melody as MIDI notes. */
    public int leadLow() {
        return leadLow;
    }

    public int leadHigh() {
        return leadHigh;
    }
}
