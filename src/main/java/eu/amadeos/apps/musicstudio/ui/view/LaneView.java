package eu.amadeos.apps.musicstudio.ui.view;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.service.compose.Note;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.DoubleConsumer;

/**
 * The notes of one part over time, like a piano roll: high notes at the top, drums as dots in one row per drum.
 * Bar lines, the play head, and a click moves the play head there.
 */
public final class LaneView extends JComponent {
    private final Part part;
    private Song song;
    private double seconds = 1;
    private double position;
    private boolean muted;

    public LaneView(Part part, DoubleConsumer onSeek) {
        this.part = part;
        setName("lane." + part.id());
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (getWidth() > 0) onSeek.accept(e.getX() / (double) getWidth() * seconds);
            }
        };
        addMouseListener(mouse);
    }

    public Part part() {
        return part;
    }

    /** @param newSeconds the length of the time line */
    public void setSong(Song newSong, double newSeconds) {
        song = newSong;
        seconds = Math.max(0.1, newSeconds);
        repaint();
    }

    public void setPosition(double newPosition) {
        if (newPosition == position) return;
        int before = x(position);
        position = newPosition;
        int after = x(position);
        repaint(Math.min(before, after) - 2, 0, Math.abs(after - before) + 5, getHeight());
    }

    public void setMuted(boolean newMuted) {
        muted = newMuted;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) return super.getPreferredSize();
        return new Dimension(500, 90);
    }

    private int x(double time) {
        return (int) Math.round(time / seconds * getWidth());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            DesktopTheme theme = AmadeUI.theme();
            Color background = theme.fieldBackground();
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
            Color text = theme.fieldText();
            Color line = new Color(text.getRed(), text.getGreen(), text.getBlue(), 28);
            Color phrase = new Color(text.getRed(), text.getGreen(), text.getBlue(), 70);
            int w = getWidth();
            int h = getHeight();
            if (song != null) {
                double step = song.stepSeconds();
                for (int bar = 0; bar <= song.bars(); bar++) {
                    double bx = bar * Song.STEPS_PER_BAR * step / seconds * w;
                    g.setColor(bar % 4 == 0 ? phrase : line);
                    g.draw(new Line2D.Double(bx, 0, bx, h));
                }
                paintNotes(g, theme, background, step, w, h);
            }
            g.setColor(phrase);
            g.drawLine(0, h - 1, w, h - 1);
            int px = x(position);
            g.setColor(theme.selectionBackground());
            g.setStroke(new BasicStroke(2f));
            g.drawLine(px, 0, px, h);
        } finally {
            g.dispose();
        }
    }

    private void paintNotes(Graphics2D g, DesktopTheme theme, Color background, double step, int w, int h) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (Note n : song.notes()) {
            if (Song.part(n.channel()) != part) continue;
            int p = row(n);
            low = Math.min(low, p);
            high = Math.max(high, p);
        }
        if (low > high) return;
        Color color = PartColors.of(part, theme.isDark());
        g.setColor(muted ? PartColors.faded(color, background) : color);
        double margin = 8;
        double span = Math.max(1, high - low);
        double rowHeight = Math.max(2, Math.min(5, (h - 2 * margin) / (span + 1)));
        for (Note n : song.notes()) {
            if (Song.part(n.channel()) != part) continue;
            double fy = high == low ? 0.5 : 1 - (row(n) - low) / span;
            double y = margin + fy * (h - 2 * margin - rowHeight);
            double nx = song.heardAt(n) * step / seconds * w;
            if (n.isDrum()) {
                double r = Math.max(1.5, rowHeight * 0.6);
                g.fill(new Ellipse2D.Double(nx - r, y + rowHeight / 2 - r, 2 * r, 2 * r));
            } else {
                double nw = Math.max(2, n.length() * step / seconds * w - 1);
                g.fill(new RoundRectangle2D.Double(nx, y, nw, rowHeight, 2, 2));
            }
        }
    }

    /** Pitch, or for drums a fixed row per drum: kick at the bottom, cymbals at the top. */
    private static int row(Note n) {
        if (!n.isDrum()) return n.pitch();
        return switch (n.drum()) {
            case KICK -> 0;
            case TOM -> 1;
            case SNARE, CLAP -> 2;
            case SHAKER -> 3;
            case HAT, OPEN_HAT -> 4;
        };
    }
}
