package eu.amadeos.apps.musicstudio.ui.view;

import eu.amadeos.apps.musicstudio.service.compose.Section;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Function;

/** The form of the piece above the lanes: intro, A, B, outro, each as a labelled block. */
public final class SectionBar extends JComponent {
    private final Function<Section.Kind, String> names;
    private Song song;
    private double seconds = 1;

    public SectionBar(Function<Section.Kind, String> names) {
        this.names = names;
        setName("sections");
    }

    public void setSong(Song newSong, double newSeconds) {
        song = newSong;
        seconds = Math.max(0.1, newSeconds);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        return new Dimension(500, fm.getHeight() + 6);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (song == null) return;
            DesktopTheme theme = AmadeUI.theme();
            Font font = getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            double barSeconds = Song.STEPS_PER_BAR * song.stepSeconds();
            for (Section s : song.sections()) {
                int x0 = (int) Math.round(s.startBar() * barSeconds / seconds * getWidth());
                int x1 = (int) Math.round(s.endBar() * barSeconds / seconds * getWidth());
                Color fill = switch (s.kind()) {
                    case A -> blend(PartColors.of(eu.amadeos.apps.musicstudio.domain.Part.MELODY, false),
                            theme.fieldBackground(), 0.35);
                    case B -> blend(PartColors.of(eu.amadeos.apps.musicstudio.domain.Part.CHORDS, false),
                            theme.fieldBackground(), 0.35);
                    default -> blend(theme.shadow(), theme.fieldBackground(), 0.35);
                };
                g.setColor(fill);
                g.fillRoundRect(x0 + 1, 1, Math.max(2, x1 - x0 - 2), getHeight() - 2, 4, 4);
                g.setColor(theme.fieldText());
                String label = names.apply(s.kind());
                if (fm.stringWidth(label) < x1 - x0 - 8) {
                    g.drawString(label, x0 + 5, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static Color blend(Color a, Color b, double share) {
        return new Color((int) (a.getRed() * share + b.getRed() * (1 - share)),
                (int) (a.getGreen() * share + b.getGreen() * (1 - share)),
                (int) (a.getBlue() * share + b.getBlue() * (1 - share)));
    }
}
