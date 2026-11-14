package eu.amadeos.apps.musicstudio.ui.view;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.service.compose.Note;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;

/** A tiny picture of the first bars of a piece, for the style list: melody on top, drums at the bottom. */
public final class SongSketch implements Icon {
    /** How many bars the sketch shows. */
    static final int BARS = 4;
    private final Song song;
    private final int width;
    private final int height;

    public SongSketch(Song song, int width, int height) {
        this.song = song;
        this.width = width;
        this.height = height;
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            DesktopTheme theme = AmadeUI.theme();
            g.translate(x, y);
            g.setColor(theme.fieldBackground());
            g.fillRoundRect(0, 0, width, height, 4, 4);
            g.setColor(theme.shadow());
            g.drawRoundRect(0, 0, width - 1, height - 1, 4, 4);
            // The first bars after the intro, where every part plays.
            int firstBar = song.sections().size() > 1 ? song.sections().get(1).startBar() : 0;
            double from = firstBar * Song.STEPS_PER_BAR;
            double steps = BARS * Song.STEPS_PER_BAR;
            double band = (height - 4) / 4.0;
            Part[] order = {Part.MELODY, Part.CHORDS, Part.BASS, Part.DRUMS};
            for (int lane = 0; lane < order.length; lane++) {
                Part part = order[lane];
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                for (Note n : song.notes()) {
                    if (Song.part(n.channel()) != part || n.step() < from || n.step() >= from + steps) continue;
                    int p = n.isDrum() ? n.drum().ordinal() : n.pitch();
                    low = Math.min(low, p);
                    high = Math.max(high, p);
                }
                if (low > high) continue;
                g.setColor(PartColors.of(part, theme.isDark()));
                double top = 2 + lane * band;
                for (Note n : song.notes()) {
                    if (Song.part(n.channel()) != part || n.step() < from || n.step() >= from + steps) continue;
                    int p = n.isDrum() ? n.drum().ordinal() : n.pitch();
                    double fy = high == low ? 0.5 : 1 - (p - low) / (double) (high - low);
                    double nx = 2 + (n.step() - from) / steps * (width - 4);
                    double nw = n.isDrum() ? 1 : Math.max(1, Math.min(n.length(), from + steps - n.step()) / steps
                            * (width - 4) - 0.5);
                    g.fill(new Rectangle2D.Double(nx, top + fy * (band - 2), nw, 1.5));
                }
            }
        } finally {
            g.dispose();
        }
    }
}
