package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.ui.main.MainPresenter.Slider;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * The recipe on the right: seed, key and length; the beat (tempo, hardness, swing, energy, fills) and the sound
 * (brightness, hall, crackle). A slider takes effect when it is let go.
 */
public final class SettingsPanel extends JPanel {
    static final int WIDTH = 270;

    private final MainPresenter presenter;
    private final Messages msg;
    private final JTextField seed = new JTextField(10);
    private final JComboBox<String> key = new JComboBox<>();
    private final JComboBox<String> length = new JComboBox<>();
    private final Map<Slider, JSlider> sliders = new EnumMap<>(Slider.class);
    private final Map<Slider, JLabel> values = new EnumMap<>(Slider.class);
    private boolean updating;
    private int row;
    private String seedKey = "";

    public SettingsPanel(MainPresenter presenter, MusicActions actions, Messages msg) {
        super(new GridBagLayout());
        EdtExecutor.requireEdt();
        this.presenter = presenter;
        this.msg = msg;
        setName("settings");
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        heading("settings.piece");
        seed.setName("settings.seed");
        seed.addActionListener(e -> applySeed());
        seed.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) applySeed();
            }
        });
        JButton random = new JButton(msg.get("settings.random"));
        random.setName("settings.random");
        random.setToolTipText(String.valueOf(actions.randomSeed.getValue(javax.swing.Action.SHORT_DESCRIPTION)));
        random.addActionListener(e -> presenter.randomSeed());
        JPanel seedRow = new JPanel(new BorderLayout(6, 0));
        seedRow.setOpaque(false);
        seedRow.add(seed, BorderLayout.CENTER);
        seedRow.add(random, BorderLayout.EAST);
        field("settings.seed", seedRow);

        key.setName("settings.key");
        key.addItem("");
        for (String name : MainPresenter.KEY_NAMES) key.addItem(name);
        key.addActionListener(e -> {
            if (!updating && key.getSelectedIndex() >= 0) presenter.setKey(key.getSelectedIndex() - 1);
        });
        field("settings.key", key);

        length.setName("settings.length");
        for (int seconds : Recipe.LENGTHS) length.addItem(msg.get("length." + seconds));
        length.addActionListener(e -> {
            int i = length.getSelectedIndex();
            if (!updating && i >= 0) presenter.setLength(Recipe.LENGTHS.get(i));
        });
        field("settings.length", length);

        heading("settings.beat");
        slider(Slider.TEMPO, false);
        slider(Slider.HARDNESS, true);
        slider(Slider.SWING, true);
        slider(Slider.ENERGY, true);
        slider(Slider.FILLS, true);
        heading("settings.sound");
        slider(Slider.BRIGHTNESS, true);
        slider(Slider.REVERB, true);
        slider(Slider.CRACKLE, true);

        JLabel note = new JLabel("<html><div style='width:" + (WIDTH - 30) + "px'>" + msg.get("settings.note")
                + "</div></html>");
        note.setFont(note.getFont().deriveFont(Font.PLAIN, note.getFont().getSize2D() - 1));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        GridBagConstraints c = constraints(0);
        c.gridwidth = 3;
        c.insets = new Insets(12, 0, 0, 0);
        add(note, c);
        GridBagConstraints filler = constraints(0);
        filler.weighty = 1;
        filler.gridy = ++row;
        add(new JPanel() {
            {
                setOpaque(false);
            }
        }, filler);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(WIDTH, d.width), d.height);
    }

    /** Shows the recipe without telling the presenter. */
    public void show(Recipe recipe) {
        EdtExecutor.requireEdt();
        updating = true;
        try {
            seed.setText(Long.toString(recipe.seed()));
            seed.setForeground(UIManager.getColor("TextField.foreground"));
            key.removeItemAt(0);
            key.insertItemAt(seedKey.isEmpty() ? msg.get("settings.key.auto")
                    : msg.format("settings.key.auto-named", seedKey), 0);
            key.setSelectedIndex(recipe.key() + 1);
            int index = Recipe.LENGTHS.indexOf(recipe.lengthSeconds());
            if (index < 0) {
                index = 0;
                for (int i = 0; i < Recipe.LENGTHS.size(); i++) {
                    if (Recipe.LENGTHS.get(i) <= recipe.lengthSeconds()) index = i;
                }
            }
            length.setSelectedIndex(index);
            for (Slider s : Slider.values()) {
                sliders.get(s).setValue(s.value(recipe));
                values.get(s).setText(valueText(s, s.value(recipe)));
            }
        } finally {
            updating = false;
        }
    }

    /** The key the seed gives, once the piece is composed. */
    public void showSeedKey(String newSeedKey) {
        seedKey = newSeedKey;
        updating = true;
        try {
            int selected = key.getSelectedIndex();
            key.removeItemAt(0);
            key.insertItemAt(msg.format("settings.key.auto-named", seedKey), 0);
            key.setSelectedIndex(selected);
        } finally {
            updating = false;
        }
    }

    JSlider slider(Slider s) {
        return sliders.get(s);
    }

    JTextField seedField() {
        return seed;
    }

    JComboBox<String> keyBox() {
        return key;
    }

    JComboBox<String> lengthBox() {
        return length;
    }

    private void applySeed() {
        if (updating) return;
        if (presenter.setSeed(seed.getText())) {
            seed.setForeground(UIManager.getColor("TextField.foreground"));
        } else {
            seed.setForeground(new Color(0xC0, 0x30, 0x30));
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private String valueText(Slider s, int value) {
        return s == Slider.TEMPO ? msg.format("settings.bpm", value) : msg.format("settings.percent", value);
    }

    private void heading(String key) {
        JLabel label = new JLabel(msg.get(key));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(row == 0 ? 0 : 10, 0, 4, 0),
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground"))));
        GridBagConstraints c = constraints(0);
        c.gridwidth = 3;
        add(label, c);
    }

    private void field(String key, java.awt.Component component) {
        JLabel label = new JLabel(msg.get(key));
        label.setLabelFor(component);
        GridBagConstraints c = constraints(0);
        add(label, c);
        c = constraints(1);
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        add(component, c);
    }

    private void slider(Slider s, boolean scaleLabels) {
        JSlider slider = new JSlider(s.min(), s.max(), s.min());
        slider.setName(s.key());
        slider.setPreferredSize(new Dimension(110, slider.getPreferredSize().height));
        JLabel value = new JLabel(valueText(s, s.max()));
        value.setHorizontalAlignment(JLabel.RIGHT);
        value.setPreferredSize(value.getPreferredSize());
        slider.addChangeListener(e -> {
            value.setText(valueText(s, slider.getValue()));
            if (!updating && !slider.getValueIsAdjusting()) presenter.setSlider(s, slider.getValue());
        });
        JLabel label = new JLabel(msg.get(s.key()));
        label.setLabelFor(slider);
        add(label, constraints(0));
        GridBagConstraints c = constraints(1);
        c.gridy = row;
        c.weightx = 1;
        add(slider, c);
        c = constraints(2);
        c.gridy = row;
        add(value, c);
        if (scaleLabels) {
            JPanel scale = new JPanel(new BorderLayout());
            scale.setOpaque(false);
            JLabel low = small(msg.get(s.key() + ".low"));
            JLabel high = small(msg.get(s.key() + ".high"));
            scale.add(low, BorderLayout.WEST);
            scale.add(high, BorderLayout.EAST);
            c = constraints(1);
            c.gridy = ++row;
            c.insets = new Insets(0, 4, 2, 4);
            add(scale, c);
        }
        sliders.put(s, slider);
        values.put(s, value);
    }

    private static JLabel small(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize2D() - 2));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    private GridBagConstraints constraints(int column) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = column == 0 ? ++row : row;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 6);
        return c;
    }
}
