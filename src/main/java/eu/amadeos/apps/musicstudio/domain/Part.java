package eu.amadeos.apps.musicstudio.domain;

import java.util.Locale;

/**
 * The parts of a piece, each on its own track with its own seed, instrument and volume. Pad and arpeggio are
 * extras a piece can do without. Never reorder, new ones go at the end: the order is that of the lanes.
 */
public enum Part {
    MELODY, CHORDS, BASS, DRUMS, PAD, ARPEGGIO;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The message key of the name, e.g. {@code part.melody}. */
    public String key() {
        return "part." + id();
    }

    /** The parts played by an instrument: all but the drums. */
    public boolean tonal() {
        return this != DRUMS;
    }

    /** The parts that can be switched off: pad and arpeggio. */
    public boolean extra() {
        return this == PAD || this == ARPEGGIO;
    }
}
