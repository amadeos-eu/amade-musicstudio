package eu.amadeos.apps.musicstudio.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The instruments of the built-in synthesizer. Each is calculated, none is a recording, and each can play
 * every part. Never reorder, new ones go at the end: recipes store the id, MIDI export the General MIDI
 * program.
 */
public enum Instrument {
    PIANO(Group.KEYS, false, 0),
    ELECTRIC_PIANO(Group.KEYS, false, 4),
    ORGAN(Group.KEYS, true, 16),
    BELLS(Group.MALLETS, false, 9),
    MARIMBA(Group.MALLETS, false, 12),
    STRINGS(Group.STRINGS, true, 48),
    HARP(Group.STRINGS, false, 46),
    GUITAR(Group.STRINGS, false, 24),
    FLUTE(Group.WIND, true, 73),
    WARM_PAD(Group.SYNTH, true, 89),
    SYNTH_LEAD(Group.SYNTH, true, 81),
    CHIP_SQUARE(Group.CHIP, true, 80),
    CHIP_TRIANGLE(Group.CHIP, true, 38),
    ELECTRIC_BASS(Group.BASS, false, 33),
    SYNTH_BASS(Group.BASS, true, 38),
    SUB_BASS(Group.BASS, true, 39),
    MALE_OOH(Group.VOICE, true, 53),
    MALE_AAH(Group.VOICE, true, 52),
    FEMALE_OOH(Group.VOICE, true, 53),
    FEMALE_AAH(Group.VOICE, true, 52),
    CHILD_OOH(Group.VOICE, true, 53),
    CHILD_AAH(Group.VOICE, true, 52),
    CHOIR_OOH(Group.VOICE, true, 53),
    CHOIR_AAH(Group.VOICE, true, 52),
    CHOIR_MMM(Group.VOICE, true, 53),
    DISTORTED_GUITAR(Group.STRINGS, false, 30),
    ELECTRIC_GUITAR(Group.STRINGS, false, 27),
    VIBRAPHONE(Group.MALLETS, false, 11),
    SAXOPHONE(Group.WIND, true, 66),
    UPRIGHT_BASS(Group.BASS, false, 32);

    /** The groups of the instrument chooser. */
    public enum Group {
        KEYS, MALLETS, STRINGS, WIND, SYNTH, CHIP, BASS, VOICE;

        public String key() {
            return "instrument.group." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final Group group;
    private final boolean sustained;
    private final int midiProgram;

    Instrument(Group group, boolean sustained, int midiProgram) {
        this.group = group;
        this.sustained = sustained;
        this.midiProgram = midiProgram;
    }

    public Group group() {
        return group;
    }

    /** Whether the sound lasts as long as the key is held (organ, flute); otherwise it dies away (piano). */
    public boolean sustained() {
        return sustained;
    }

    /** The General MIDI program (0 to 127) for MIDI export. */
    public int midiProgram() {
        return midiProgram;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String key() {
        return "instrument." + id();
    }

    /** The message key of the short description in the instrument chooser. */
    public String descriptionKey() {
        return key() + ".about";
    }

    public static Optional<Instrument> ofId(String id) {
        for (Instrument i : values()) if (i.id().equals(id)) return Optional.of(i);
        return Optional.empty();
    }

    public static List<Instrument> inGroup(Group group) {
        List<Instrument> list = new ArrayList<>();
        for (Instrument i : values()) if (i.group == group) list.add(i);
        return list;
    }
}
