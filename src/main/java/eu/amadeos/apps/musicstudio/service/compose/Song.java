package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;

import java.util.List;

/**
 * A composed piece: its notes on eleven channels, its form and its chords. One bar has four beats of four
 * sixteenths each.
 */
public record Song(Recipe recipe, boolean loop, List<Note> notes, List<Section> sections, int bars, int keyNote,
                   boolean minor, int[][] chordsPerBar) {
    public static final int KICK = 0;
    public static final int SNARE = 1;
    public static final int HATS = 2;
    public static final int BASS = 3;
    /** The three chord voices are channels 4, 5 and 6. */
    public static final int CHORD = 4;
    public static final int LEAD = 7;
    /** The two pad voices are channels 8 and 9. */
    public static final int PAD = 8;
    public static final int ARPEGGIO = 10;
    public static final int CHANNELS = 11;
    /** The channels of a piece without pad and arpeggio. */
    public static final int CLASSIC_CHANNELS = 8;
    public static final int STEPS_PER_BAR = 16;

    public Song {
        notes = List.copyOf(notes);
        sections = List.copyOf(sections);
    }

    /** The part a channel belongs to. */
    public static Part part(int channel) {
        if (channel <= HATS) return Part.DRUMS;
        if (channel == BASS) return Part.BASS;
        if (channel == LEAD) return Part.MELODY;
        if (channel == ARPEGGIO) return Part.ARPEGGIO;
        if (channel >= PAD) return Part.PAD;
        return Part.CHORDS;
    }

    public int steps() {
        return bars * STEPS_PER_BAR;
    }

    /** The length of a sixteenth in seconds. */
    public double stepSeconds() {
        return 60.0 / recipe.bpm() / 4;
    }

    /** The length of the notes, without the reverb's tail. */
    public double seconds() {
        return steps() * stepSeconds();
    }

    /** Where a note is heard, in sixteenths: every second sixteenth comes later when the piece swings,
     * or every second eighth in a style that {@linkplain eu.amadeos.apps.musicstudio.domain.Style#shuffle() shuffles}. */
    public double heardAt(Note note) {
        return note.step() + swingOffset(note.step());
    }

    double swingOffset(double step) {
        if (recipe.style().shuffle()) {
            // The beat is stretched before its second eighth and squeezed after it.
            double late = recipe.swing() / 100.0 * 2 / 3;
            double inBeat = step - 4 * Math.floor(step / 4);
            double heard = inBeat < 2 ? inBeat * (2 + late) / 2 : 2 + late + (inBeat - 2) * (2 - late) / 2;
            return heard - inBeat;
        }
        int whole = (int) Math.floor(step);
        return whole % 2 == 1 && step - whole < 0.05 ? recipe.swing() / 100.0 / 3 : 0;
    }

    /** The section a bar is in. */
    public Section sectionAt(int bar) {
        for (Section s : sections) if (s.contains(bar)) return s;
        return sections.getLast();
    }
}
