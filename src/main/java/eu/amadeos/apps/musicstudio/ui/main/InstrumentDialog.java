package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.ui.view.PartColors;
import eu.amadeos.apps.musicstudio.ui.view.WaveformView;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Optional;

/**
 * Chooses the instrument of a part: the instruments in groups on the left; on the right what it is, how one
 * note looks, a keyboard to try it (also with the keys A S D F ... of the computer), attack, release and octave.
 */
final class InstrumentDialog extends JDialog {
    /** The note of the leftmost white key: C below middle C... */
    static final int LOW_C = 48;
    /** ...over two octaves. */
    static final int WHITE_KEYS = 14;
    /** Computer keys for the white and the black keys, as on a tracker. */
    static final String WHITE = "ASDFGHJKL";
    static final String BLACK = "WE TYU OP";

    private final Messages msg;
    private final MainView.InstrumentPreview preview;
    private final Part part;
    private final JList<Instrument> list = new JList<>(Instrument.values());
    private final JLabel title = new JLabel();
    private final JLabel about = new JLabel();
    private final WaveformView wave = new WaveformView(null);
    private final Keyboard keyboard = new Keyboard();
    private final JSlider attack = new JSlider(0, 100, 50);
    private final JSlider release = new JSlider(0, 100, 50);
    private final JSlider octave = new JSlider(-2, 2, 0);
    private final Voice start;
    private Voice chosen;
    private int row = 100;

    InstrumentDialog(Window owner, Messages msg, Part part, Voice current, MainView.InstrumentPreview preview) {
        super(owner, msg.format("dialog.instrument.title", msg.get(part.key())), ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.preview = preview;
        this.part = part;
        this.start = current;
        setName("dialog.instrument");
        list.setName("instrument.list");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(Instrument.values().length);
        JLabel heading = new JLabel();
        JPanel headed = new JPanel(new BorderLayout());
        headed.setOpaque(false);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected,
                                                          boolean focus) {
                Instrument in = (Instrument) value;
                JLabel label = (JLabel) super.getListCellRendererComponent(l, msg.get(in.key()), index, selected, focus);
                label.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
                if (index > 0 && Instrument.values()[index - 1].group() == in.group()) return label;
                heading.setText(msg.get(in.group().key()));
                heading.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() - 1));
                heading.setForeground(UIManager.getColor("Label.disabledForeground"));
                heading.setBorder(BorderFactory.createEmptyBorder(index == 0 ? 2 : 8, 4, 2, 4));
                headed.removeAll();
                headed.add(heading, BorderLayout.NORTH);
                headed.add(label, BorderLayout.CENTER);
                return headed;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                showInstrument();
                tryNote(60);
            }
        });

        title.setFont(title.getFont().deriveFont(Font.BOLD));
        about.setForeground(UIManager.getColor("Label.disabledForeground"));
        wave.setPreferredSize(new Dimension(380, 80));
        wave.setColour(PartColors.of(part, AmadeUI.theme().isDark()));
        wave.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")));
        attack.setName("instrument.attack");
        release.setName("instrument.release");
        octave.setName("instrument.octave");
        octave.setMajorTickSpacing(1);
        octave.setSnapToTicks(true);
        octave.setPaintTicks(true);
        attack.addChangeListener(e -> {
            if (!attack.getValueIsAdjusting()) tryNote(60);
        });
        release.addChangeListener(e -> {
            if (!release.getValueIsAdjusting()) tryNote(60);
        });
        octave.addChangeListener(e -> {
            if (!octave.getValueIsAdjusting()) tryNote(60);
        });

        JPanel right = new JPanel(new GridBagLayout());
        right.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 4, 0);
        right.add(title, c);
        right.add(about, c);
        right.add(wave, c);
        right.add(keyboard, c);
        JLabel hint = new JLabel("<html><div style='width:360px'>" + msg.get("dialog.instrument.keyboard")
                + "</div></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1));
        right.add(hint, c);
        JLabel sound = new JLabel(msg.get("dialog.instrument.sound"));
        sound.setFont(sound.getFont().deriveFont(Font.BOLD));
        c.insets = new Insets(10, 0, 4, 0);
        right.add(sound, c);
        sliderRow(right, "dialog.instrument.attack", attack, "dialog.instrument.attack.low",
                "dialog.instrument.attack.high");
        sliderRow(right, "dialog.instrument.release", release, "dialog.instrument.release.low",
                "dialog.instrument.release.high");
        sliderRow(right, "dialog.instrument.octave", octave, null, null);
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = ++row;
        c.gridwidth = 3;
        c.weighty = 1;
        right.add(new JLabel(), c);

        JButton cancel = new JButton(msg.get("button.cancel"));
        cancel.setName("instrument.cancel");
        cancel.addActionListener(e -> dispose());
        JButton use = new JButton(msg.format("dialog.instrument.use", msg.get(part.key())));
        use.setName("instrument.use");
        use.addActionListener(e -> {
            chosen = voice();
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(use);
        JPanel main = new JPanel(new BorderLayout());
        main.add(right, BorderLayout.CENTER);
        main.add(buttons, BorderLayout.SOUTH);
        JScrollPane listPane = new JScrollPane(list);
        listPane.setPreferredSize(new Dimension(210, Math.max(460, list.getPreferredSize().height + 6)));
        add(listPane, BorderLayout.WEST);
        add(main, BorderLayout.CENTER);
        getRootPane().setDefaultButton(use);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        bindComputerKeys();

        attack.setValue(current.attack());
        release.setValue(current.release());
        octave.setValue(current.octave());
        list.setSelectedValue(current.instrument(), true);
        showInstrument();
        wave.setAudio(preview.sound(voice(), part == Part.BASS ? 36 : 60));
        pack();
        setLocationRelativeTo(owner);
    }

    /** The voice chosen, empty if cancelled. */
    Optional<Voice> choose() {
        setVisible(true);
        return Optional.ofNullable(chosen);
    }

    /** The voice as set now; the volume stays. */
    Voice voice() {
        Instrument in = list.getSelectedValue() != null ? list.getSelectedValue() : start.instrument();
        return new Voice(in, octave.getValue(), attack.getValue(), release.getValue(), start.volume());
    }

    JList<Instrument> instruments() {
        return list;
    }

    private void showInstrument() {
        Instrument in = list.getSelectedValue();
        if (in == null) return;
        title.setText(msg.get(in.key()));
        about.setText("<html><div style='width:360px'>" + msg.get(in.descriptionKey()) + "</div></html>");
    }

    /** Plays one note and shows its waveform. */
    void tryNote(int pitch) {
        if (!isVisible() && !isDisplayable()) return;
        Voice v = voice();
        int base = part == Part.BASS ? pitch - 24 : pitch;
        Audio sound = preview.sound(v, base);
        wave.setAudio(sound);
        if (isVisible()) preview.play(sound);
    }

    private void sliderRow(JPanel panel, String key, JSlider slider, String lowKey, String highKey) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = ++row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 0, 8);
        JLabel label = new JLabel(msg.get(key));
        label.setLabelFor(slider);
        panel.add(label, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(slider, c);
        if (lowKey != null) {
            JPanel scale = new JPanel(new BorderLayout());
            scale.setOpaque(false);
            scale.add(small(msg.get(lowKey)), BorderLayout.WEST);
            scale.add(small(msg.get(highKey)), BorderLayout.EAST);
            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = ++row;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 4, 2, 4);
            panel.add(scale, c);
        }
    }

    private static JLabel small(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize2D() - 2));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    /** A S D F ... play the white keys, W E T Y U ... the black keys, while the dialog has the focus. */
    private void bindComputerKeys() {
        for (int i = 0; i < WHITE.length(); i++) {
            int pitch = Keyboard.whitePitch(i);
            bindKey(WHITE.charAt(i), pitch);
        }
        for (int i = 0; i < BLACK.length(); i++) {
            if (BLACK.charAt(i) == ' ') continue;
            bindKey(BLACK.charAt(i), Keyboard.whitePitch(i) + 1);
        }
    }

    private void bindKey(char key, int pitch) {
        String name = "note." + key;
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                keyboard.press(pitch);
                tryNote(pitch);
            }
        });
    }

    /** Two octaves of piano keys; a click plays the note. */
    final class Keyboard extends JComponent {
        private int pressed = -1;

        Keyboard() {
            setName("instrument.keyboard");
            setPreferredSize(new Dimension(380, 56));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int pitch = pitchAt(e.getX(), e.getY());
                    if (pitch >= 0) {
                        press(pitch);
                        tryNote(pitch);
                    }
                }
            });
        }

        static int whitePitch(int index) {
            int[] steps = {0, 2, 4, 5, 7, 9, 11};
            return LOW_C + 12 * (index / 7) + steps[index % 7];
        }

        static boolean hasBlackAfter(int index) {
            int i = index % 7;
            return i != 2 && i != 6;
        }

        void press(int pitch) {
            pressed = pitch;
            repaint();
        }

        int pitchAt(int x, int y) {
            double key = getWidth() / (double) WHITE_KEYS;
            if (y < getHeight() * 0.6) {
                for (int i = 0; i < WHITE_KEYS - 1; i++) {
                    if (!hasBlackAfter(i)) continue;
                    double bx = (i + 1) * key - key * 0.3;
                    if (x >= bx && x < bx + key * 0.6) return whitePitch(i) + 1;
                }
            }
            int index = (int) (x / key);
            return index >= 0 && index < WHITE_KEYS ? whitePitch(index) : -1;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                double key = (getWidth() - 1) / (double) WHITE_KEYS;
                int h = getHeight() - 1;
                Color mark = PartColors.of(part, false);
                for (int i = 0; i < WHITE_KEYS; i++) {
                    int x = (int) Math.round(i * key);
                    int w = (int) Math.round((i + 1) * key) - x;
                    g.setColor(whitePitch(i) == pressed ? mark : Color.WHITE);
                    g.fillRect(x, 0, w, h);
                    g.setColor(Color.GRAY);
                    g.drawRect(x, 0, w, h);
                }
                for (int i = 0; i < WHITE_KEYS - 1; i++) {
                    if (!hasBlackAfter(i)) continue;
                    int x = (int) Math.round((i + 1) * key - key * 0.3);
                    g.setColor(whitePitch(i) + 1 == pressed ? mark : new Color(0x2A, 0x2A, 0x30));
                    g.fillRect(x, 0, (int) Math.round(key * 0.6), (int) (h * 0.6));
                }
            } finally {
                g.dispose();
            }
        }
    }
}
