package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.ui.view.SongSketch;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** The eight styles with a sketch of their notes, name and a few words each; choosing one gives that style. */
public final class StyleList extends JList<Style> {
    static final int SKETCH_WIDTH = 40;
    static final int SKETCH_HEIGHT = 32;
    private final Map<Style, Icon> sketches = new EnumMap<>(Style.class);
    private boolean updating;

    public StyleList(Messages msg, Consumer<Style> onSelect) {
        super(Style.values());
        EdtExecutor.requireEdt();
        setName("styles");
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setVisibleRowCount(Style.values().length);
        JPanel cell = new JPanel(new BorderLayout(8, 0));
        cell.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 10));
        JLabel icon = new JLabel();
        JPanel texts = new JPanel(new GridLayout(2, 1));
        texts.setOpaque(false);
        JLabel name = new JLabel();
        JLabel subtitle = new JLabel();
        texts.add(name);
        texts.add(subtitle);
        cell.add(icon, BorderLayout.WEST);
        cell.add(texts, BorderLayout.CENTER);
        setCellRenderer((ListCellRenderer<Style>) (list, style, index, selected, focus) -> {
            cell.setOpaque(true);
            cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            java.awt.Color text = selected ? list.getSelectionForeground() : list.getForeground();
            name.setText(msg.get(style.key()));
            name.setFont(list.getFont().deriveFont(Font.BOLD));
            name.setForeground(text);
            subtitle.setText(msg.get(style.subtitleKey()));
            subtitle.setFont(list.getFont().deriveFont(Font.PLAIN));
            subtitle.setForeground(text);
            icon.setIcon(sketches.get(style));
            icon.setPreferredSize(new java.awt.Dimension(SKETCH_WIDTH, SKETCH_HEIGHT));
            return cell;
        });
        addListSelectionListener(e -> {
            if (!updating && !e.getValueIsAdjusting() && getSelectedValue() != null) onSelect.accept(getSelectedValue());
        });
    }

    /** Selects the style without telling anyone. */
    public void show(Style style) {
        updating = true;
        try {
            setSelectedValue(style, true);
        } finally {
            updating = false;
        }
    }

    public void setSketches(Map<Style, Song> songs) {
        songs.forEach((style, song) -> sketches.put(style, new SongSketch(song, SKETCH_WIDTH, SKETCH_HEIGHT)));
        repaint();
    }
}
