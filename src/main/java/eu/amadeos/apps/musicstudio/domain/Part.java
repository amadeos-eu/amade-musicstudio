package eu.amadeos.apps.musicstudio.domain;

import java.util.Locale;

/** The four parts of a piece, each on its own track with its own seed, instrument and volume. */
public enum Part {
    MELODY, CHORDS, BASS, DRUMS;

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
}
