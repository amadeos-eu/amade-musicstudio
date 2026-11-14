package eu.amadeos.apps.musicstudio.service.compose;

/** The drums of a kit, with their General MIDI note for MIDI export. */
public enum Drum {
    KICK(36), SNARE(38), CLAP(39), HAT(42), OPEN_HAT(46), SHAKER(70), TOM(45);

    private final int midiNote;

    Drum(int midiNote) {
        this.midiNote = midiNote;
    }

    public int midiNote() {
        return midiNote;
    }
}
