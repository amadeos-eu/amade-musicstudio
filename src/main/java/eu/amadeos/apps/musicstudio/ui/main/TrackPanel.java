package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.ui.icons.MusicGlyph;
import eu.amadeos.apps.musicstudio.ui.view.LaneView;
import eu.amadeos.apps.musicstudio.ui.view.PartColors;
import eu.amadeos.apps.musicstudio.ui.view.SectionBar;
import eu.amadeos.apps.musicstudio.ui.view.WaveformView;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.swing.ToolIcon;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The middle of the window: one lane per part with its head (instrument, mute, solo, volume, keep) and its
 * notes, the form of the piece above, the waveform and the time below.
 */
public final class TrackPanel extends JPanel {
    static final int HEAD_WIDTH = 190;

    private final Messages msg;
    private final SectionBar sections;
    private final Map<Part, LaneView> lanes = new EnumMap<>(Part.class);
    private final Map<Part, Head> heads = new EnumMap<>(Part.class);
    private final WaveformView waveform;
    private final JLabel time = new JLabel("0:00 / 0:00");
    private double seconds;
    private boolean updating;

    /** The controls at the left of a lane. */
    final class Head extends JPanel {
        final JComboBox<Object> sound;
        final JToggleButton mute = new JToggleButton("M");
        final JToggleButton solo = new JToggleButton("S");
        final JSlider volume = new JSlider(0, 100, 75);
        final JCheckBox keep;

        Head(Part part, MainPresenter presenter) {
            super(new BorderLayout(0, 2));
            setName("head." + part.id());
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                    javax.swing.UIManager.getColor("Separator.foreground")), BorderFactory.createEmptyBorder(6, 6, 6, 8)));
            JLabel name = new JLabel(msg.get(part.key()), new Swatch(part), JLabel.LEFT);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setIconTextGap(6);
            Object[] items = part.tonal() ? Instrument.values() : Kit.values();
            sound = new JComboBox<>(items);
            sound.setName("head." + part.id() + ".sound");
            sound.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                              boolean focus) {
                    String text = value instanceof Instrument i ? msg.get(i.key())
                            : value instanceof Kit k ? msg.get(k.key()) : String.valueOf(value);
                    return super.getListCellRendererComponent(list, text, index, selected, focus);
                }
            });
            sound.addActionListener(e -> {
                if (updating) return;
                if (sound.getSelectedItem() instanceof Instrument i) presenter.setInstrument(part, i);
                else if (sound.getSelectedItem() instanceof Kit k) presenter.setKit(k);
            });
            JPanel soundRow = new JPanel(new BorderLayout(4, 0));
            soundRow.setOpaque(false);
            soundRow.add(sound, BorderLayout.CENTER);
            if (part.tonal()) {
                JButton more = new JButton(new ToolIcon(MusicGlyph.INSTRUMENT, 16));
                more.setName("head." + part.id() + ".instrument");
                more.setToolTipText(msg.format("menu.piece.instrument.for", msg.get(part.key())));
                more.setMargin(new Insets(1, 3, 1, 3));
                more.addActionListener(e -> presenter.chooseInstrument(part));
                soundRow.add(more, BorderLayout.EAST);
            }
            mute.setName("head." + part.id() + ".mute");
            mute.setToolTipText(msg.get("track.mute"));
            mute.setMargin(new Insets(1, 4, 1, 4));
            mute.addActionListener(e -> presenter.setMuted(part, mute.isSelected()));
            solo.setName("head." + part.id() + ".solo");
            solo.setToolTipText(msg.get("track.solo"));
            solo.setMargin(new Insets(1, 4, 1, 4));
            solo.addActionListener(e -> presenter.setSolo(part, solo.isSelected()));
            volume.setName("head." + part.id() + ".volume");
            volume.setToolTipText(msg.get("track.volume"));
            volume.setPreferredSize(new Dimension(90, volume.getPreferredSize().height));
            volume.addChangeListener(e -> {
                if (!updating && !volume.getValueIsAdjusting()) presenter.setVolume(part, volume.getValue());
            });
            keep = new JCheckBox(msg.get("track.keep"));
            keep.setName("head." + part.id() + ".keep");
            keep.setToolTipText(msg.get("track.keep.tip"));
            keep.setOpaque(false);
            keep.addActionListener(e -> presenter.setKept(part, keep.isSelected()));
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            controls.setOpaque(false);
            controls.add(mute);
            controls.add(solo);
            controls.add(volume);
            // Keep stands beside the name: in the row of the controls it does not fit in every language.
            JPanel nameRow = new JPanel(new BorderLayout(4, 0));
            nameRow.setOpaque(false);
            nameRow.add(name, BorderLayout.CENTER);
            nameRow.add(keep, BorderLayout.EAST);
            JPanel top = new JPanel(new java.awt.GridLayout(0, 1, 0, 3));
            top.setOpaque(false);
            top.add(nameRow);
            top.add(soundRow);
            top.add(controls);
            add(top, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(HEAD_WIDTH, super.getPreferredSize().height);
        }
    }

    /** The coloured square before a part's name. */
    private static final class Swatch implements Icon {
        private final Part part;

        Swatch(Part part) {
            this.part = part;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(PartColors.of(part, AmadeUI.theme().isDark()));
            g.fillRect(x, y + 1, 10, 10);
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    public TrackPanel(MainPresenter presenter, Messages msg) {
        super(new GridBagLayout());
        EdtExecutor.requireEdt();
        this.msg = msg;
        setName("tracks");
        sections = new SectionBar(kind -> msg.get(kind.key()));
        waveform = new WaveformView(presenter::seek);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 1;
        c.gridy = 0;
        c.weightx = 1;
        add(sections, c);
        int row = 1;
        for (Part part : Part.values()) {
            Head head = new Head(part, presenter);
            LaneView lane = new LaneView(part, presenter::seek);
            heads.put(part, head);
            lanes.put(part, lane);
            c = new GridBagConstraints();
            c.fill = GridBagConstraints.BOTH;
            c.gridx = 0;
            c.gridy = row;
            add(head, c);
            c.gridx = 1;
            c.weightx = 1;
            c.weighty = 1;
            add(lane, c);
            row++;
        }
        time.setName("time");
        time.setFont(new Font(Font.MONOSPACED, Font.PLAIN, time.getFont().getSize() + 1));
        time.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.gridy = row;
        c.insets = new Insets(4, 0, 0, 0);
        add(time, c);
        c.gridx = 1;
        c.weightx = 1;
        add(waveform, c);
    }

    /** Fills the lane heads with the recipe without telling the presenter. */
    public void show(Recipe recipe, Set<Part> kept) {
        EdtExecutor.requireEdt();
        updating = true;
        try {
            for (Part part : Part.values()) {
                Head head = heads.get(part);
                head.sound.setSelectedItem(part.tonal() ? recipe.voice(part).instrument() : recipe.kit());
                head.volume.setValue(recipe.volume(part));
                head.keep.setSelected(kept.contains(part));
            }
        } finally {
            updating = false;
        }
    }

    public void showSong(Song song, double newSeconds) {
        seconds = newSeconds;
        sections.setSong(song, newSeconds);
        for (LaneView lane : lanes.values()) lane.setSong(song, newSeconds);
        showPosition(0);
    }

    public void showWaveform(Audio audio) {
        waveform.setAudio(audio);
        if (audio != null) seconds = audio.seconds();
    }

    public void showPosition(double position) {
        for (LaneView lane : lanes.values()) lane.setPosition(position);
        waveform.setPosition(position);
        time.setText(MainPresenter.clock(position) + " / " + MainPresenter.clock(seconds));
    }

    public void showMix(Set<Part> muted, Set<Part> solo) {
        for (Part part : Part.values()) {
            Head head = heads.get(part);
            head.mute.setSelected(muted.contains(part));
            head.solo.setSelected(solo.contains(part));
            boolean heard = solo.isEmpty() ? !muted.contains(part) : solo.contains(part);
            lanes.get(part).setMuted(!heard);
        }
    }

    Head head(Part part) {
        return heads.get(part);
    }

    LaneView lane(Part part) {
        return lanes.get(part);
    }

    WaveformView waveform() {
        return waveform;
    }

    String timeText() {
        return time.getText();
    }

    /** All components, for tests. */
    JComponent[] parts() {
        return new JComponent[]{sections, waveform, time};
    }
}
