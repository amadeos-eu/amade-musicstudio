package eu.amadeos.apps.musicstudio.ui.icons;

import eu.amadeos.commons.swing.StandardGlyph;
import eu.amadeos.commons.swing.ToolIcon;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The toolbar glyphs only Music Studio needs; open, save and export come from {@link StandardGlyph}. Highlights
 * are in the theme's selection colour.
 */
public enum MusicGlyph implements ToolIcon.Glyph {
    NEW_PIECE, BACK, FORWARD, PLAY, STOP, LOOP, VARIATIONS, INSTRUMENT;

    @Override
    public void paint(ToolIcon.Pen pen) {
        switch (this) {
            case NEW_PIECE -> dice(pen);
            case BACK -> arrow(pen);
            case FORWARD -> {
                pen.mirror();
                arrow(pen);
            }
            case PLAY -> play(pen);
            case STOP -> pen.fillAndDraw(new Rectangle2D.Double(3.5, 3.5, 9, 9), pen.paper());
            case LOOP -> loop(pen);
            case VARIATIONS -> variations(pen);
            case INSTRUMENT -> keys(pen);
        }
    }

    /** A die showing five: a new piece by chance. */
    private static void dice(ToolIcon.Pen pen) {
        pen.fillAndDraw(new RoundRectangle2D.Double(2, 2, 12, 12, 4, 4), pen.paper());
        double[][] dots = {{5, 5}, {11, 5}, {5, 11}, {11, 11}};
        for (double[] d : dots) pen.fill(new Ellipse2D.Double(d[0] - 1.3, d[1] - 1.3, 2.6, 2.6), pen.ink());
        pen.fill(new Ellipse2D.Double(8 - 1.3, 8 - 1.3, 2.6, 2.6), pen.light());
    }

    /** Back points left; forward is its mirror image. */
    private static void arrow(ToolIcon.Pen pen) {
        Path2D shape = new Path2D.Double();
        shape.moveTo(1.5, 8);
        shape.lineTo(7.5, 2);
        shape.lineTo(7.5, 5.5);
        shape.lineTo(14.5, 5.5);
        shape.lineTo(14.5, 10.5);
        shape.lineTo(7.5, 10.5);
        shape.lineTo(7.5, 14);
        shape.closePath();
        pen.fillAndDraw(shape, pen.paper());
    }

    /** A triangle pointing right. */
    private static void play(ToolIcon.Pen pen) {
        Path2D shape = new Path2D.Double();
        shape.moveTo(4, 2.5);
        shape.lineTo(13.5, 8);
        shape.lineTo(4, 13.5);
        shape.closePath();
        pen.fillAndDraw(shape, pen.light());
    }

    /** Two arrows going round. */
    private static void loop(ToolIcon.Pen pen) {
        pen.bold(1.6f);
        Path2D top = new Path2D.Double();
        top.moveTo(3, 9);
        top.lineTo(3, 6);
        top.quadTo(3, 4, 5, 4);
        top.lineTo(11, 4);
        pen.draw(top);
        Path2D bottom = new Path2D.Double();
        bottom.moveTo(13, 7);
        bottom.lineTo(13, 10);
        bottom.quadTo(13, 12, 11, 12);
        bottom.lineTo(5, 12);
        pen.draw(bottom);
        pen.thin();
        pen.triangle(10, 1.5, 10, 6.5, 13.5, 4);
        pen.triangle(6, 9.5, 6, 14.5, 2.5, 12);
    }

    /** Four small bars of notes, one highlighted. */
    private static void variations(ToolIcon.Pen pen) {
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                double x = 1.5 + column * 7;
                double y = 1.5 + row * 7;
                pen.fillAndDraw(new Rectangle2D.Double(x, y, 6, 6), row == 0 && column == 1 ? pen.light() : pen.paper());
                pen.line(x + 1.5, y + 4, x + 3, y + 4);
                pen.line(x + 3.5, y + 2.5, x + 5, y + 2.5);
            }
        }
    }

    /** Piano keys. */
    private static void keys(ToolIcon.Pen pen) {
        pen.fillAndDraw(new Rectangle2D.Double(1.5, 3, 13, 10), pen.paper());
        for (double x : new double[]{4.75, 8, 11.25}) pen.line(x, 8, x, 13);
        for (double x : new double[]{3.75, 7, 10.25}) pen.fill(new Rectangle2D.Double(x, 3, 2, 5), pen.ink());
    }
}
