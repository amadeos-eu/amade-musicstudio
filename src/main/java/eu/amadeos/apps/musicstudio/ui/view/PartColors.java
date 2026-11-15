package eu.amadeos.apps.musicstudio.ui.view;

import eu.amadeos.apps.musicstudio.domain.Part;

import java.awt.Color;

/** The colour of each part, the same in the lanes, the lane heads and the style sketches. */
public final class PartColors {
    private static final Color MELODY = new Color(0xE8, 0x89, 0x2B);
    private static final Color CHORDS = new Color(0x3A, 0x9D, 0xB5);
    private static final Color BASS = new Color(0x8A, 0x5C, 0xC9);
    private static final Color PAD = new Color(0x4F, 0xA8, 0x6B);
    private static final Color ARPEGGIO = new Color(0xC9, 0x5C, 0x8E);
    private static final Color DRUMS_LIGHT = new Color(0x80, 0x80, 0x88);
    private static final Color DRUMS_DARK = new Color(0xA8, 0xA8, 0xB0);

    private PartColors() {
    }

    public static Color of(Part part, boolean dark) {
        return switch (part) {
            case MELODY -> MELODY;
            case CHORDS -> CHORDS;
            case BASS -> BASS;
            case PAD -> PAD;
            case ARPEGGIO -> ARPEGGIO;
            case DRUMS -> dark ? DRUMS_DARK : DRUMS_LIGHT;
        };
    }

    /** The colour faded towards the background, for muted lanes. */
    public static Color faded(Color c, Color background) {
        return new Color((c.getRed() + 2 * background.getRed()) / 3, (c.getGreen() + 2 * background.getGreen()) / 3,
                (c.getBlue() + 2 * background.getBlue()) / 3);
    }
}
