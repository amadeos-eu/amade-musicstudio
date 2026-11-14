package eu.amadeos.apps.musicstudio.ui.view;

import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.DoubleConsumer;

/**
 * The sound of the whole piece as a waveform; what has been played is in the accent colour. A click jumps
 * there. Also used, without play head, for one note in the instrument chooser.
 */
public final class WaveformView extends JComponent {
    private Audio audio;
    private float[] peaks = new float[0];
    private double seconds = 1;
    private double position = -1;
    private Color colour;

    /** @param onSeek where a click goes, in seconds; {@code null} for no clicks */
    public WaveformView(DoubleConsumer onSeek) {
        setName("waveform");
        if (onSeek != null) {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (getWidth() > 0) onSeek.accept(e.getX() / (double) getWidth() * seconds);
                }
            });
        }
    }

    /** {@code null} shows nothing. */
    public void setAudio(Audio newAudio) {
        audio = newAudio;
        seconds = newAudio == null ? 1 : Math.max(0.1, newAudio.seconds());
        peaks = new float[0];
        repaint();
    }

    /** One colour for the whole wave instead of played and not yet played. */
    public void setColour(Color newColour) {
        colour = newColour;
        repaint();
    }

    public void setPosition(double newPosition) {
        if (newPosition == position) return;
        position = newPosition;
        repaint();
    }

    public Audio audio() {
        return audio;
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) return super.getPreferredSize();
        return new Dimension(500, 40);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            DesktopTheme theme = AmadeUI.theme();
            int w = getWidth();
            int h = getHeight();
            g.setColor(theme.fieldBackground());
            g.fillRect(0, 0, w, h);
            if (audio == null || w <= 0) return;
            if (peaks.length != w) peaks = audio.peaks(w);
            int played = position < 0 ? -1 : (int) Math.round(position / seconds * w);
            Color done = colour != null ? colour : theme.selectionBackground();
            Color text = theme.fieldText();
            Color rest = colour != null ? colour : new Color(text.getRed(), text.getGreen(), text.getBlue(), 110);
            int mid = h / 2;
            for (int x = 0; x < w; x++) {
                int half = Math.max(1, Math.round(peaks[x] * (h / 2f - 2)));
                g.setColor(x <= played ? done : rest);
                g.drawLine(x, mid - half, x, mid + half);
            }
        } finally {
            g.dispose();
        }
    }
}
