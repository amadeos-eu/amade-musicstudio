package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Instrument;

/**
 * One note of a piece. Time is counted in sixteenth notes from the start; a note may start a little after a
 * sixteenth (strummed chords). Either {@code instrument} or {@code drum} is set.
 *
 * @param step     where the note starts, in sixteenths
 * @param length   how long it is held, in sixteenths
 * @param channel  one of the eight channels of {@link Song}
 * @param pitch    MIDI note number (60 is middle C); 60 for drums
 * @param velocity 0 to 1
 */
public record Note(double step, double length, int channel, int pitch, double velocity, Instrument instrument,
                   Drum drum) {
    public boolean isDrum() {
        return drum != null;
    }

    Note withLength(double newLength) {
        return new Note(step, newLength, channel, pitch, velocity, instrument, drum);
    }

    Note transposed(int semitones) {
        return new Note(step, length, channel, pitch + semitones, velocity, instrument, drum);
    }
}
