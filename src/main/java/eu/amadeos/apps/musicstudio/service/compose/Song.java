package eu.amadeos.apps.musicstudio.service.compose;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;

import java.util.List;

/**
 * A composed piece: its notes on eight channels, its form and its chords. One bar has four beats of four
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
    public static final int CHANNELS = 8;
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

    /** Where a note is heard, in sixteenths: every second sixteenth comes later when the piece swings. */
    public double heardAt(Note note) {
        return note.step() + swingOffset(note.step());
    }

    double swingOffset(double step) {
        int whole = (int) Math.floor(step);
        return whole % 2 == 1 && step - whole < 0.05 ? recipe.swing() / 100.0 / 3 : 0;
    }

    /** The section a bar is in. */
    public Section sectionAt(int bar) {
        for (Section s : sections) if (s.contains(bar)) return s;
        return sections.getLast();
    }
}
