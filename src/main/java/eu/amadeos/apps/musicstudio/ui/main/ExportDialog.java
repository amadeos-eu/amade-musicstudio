package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** The format of an export and its choices; the file is asked for afterwards. */
final class ExportDialog extends JDialog {
    private final Map<ExportFormat, JRadioButton> formats = new EnumMap<>(ExportFormat.class);
    private final JCheckBox loop;
    private final JCheckBox stems;
    private final JCheckBox recipe;
    private ExportOptions chosen;

    ExportDialog(Window owner, Messages msg, ExportOptions suggested, double seconds) {
        super(owner, msg.get("dialog.export.title"), ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        setName("dialog.export");
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        panel.add(heading(msg.get("dialog.export.format")), c);
        ButtonGroup group = new ButtonGroup();
        for (ExportFormat f : ExportFormat.values()) {
            JRadioButton button = new JRadioButton(msg.get(f.key()));
            button.setName("export." + f.id());
            group.add(button);
            formats.put(f, button);
            c.insets = new Insets(4, 0, 0, 0);
            panel.add(button, c);
            c.insets = new Insets(0, 24, 0, 0);
            panel.add(note(msg.get(f.key() + ".about")), c);
            button.addActionListener(e -> update());
        }
        c.insets = new Insets(12, 0, 2, 0);
        panel.add(heading(msg.get("dialog.export.options")), c);
        c.insets = new Insets(2, 0, 0, 0);
        loop = new JCheckBox(msg.get("dialog.export.loop"), suggested.loop());
        loop.setName("export.loop");
        panel.add(loop, c);
        stems = new JCheckBox(msg.get("dialog.export.stems"), suggested.stems());
        stems.setName("export.stems");
        panel.add(stems, c);
        recipe = new JCheckBox(msg.get("dialog.export.recipe"), suggested.includeRecipe());
        recipe.setName("export.recipe");
        panel.add(recipe, c);
        c.insets = new Insets(0, 24, 0, 0);
        panel.add(note(msg.get("dialog.export.recipe.about")), c);
        c.insets = new Insets(10, 0, 0, 0);
        panel.add(note(msg.format("dialog.export.length", MainPresenter.clock(seconds))), c);

        JButton cancel = new JButton(msg.get("button.cancel"));
        cancel.setName("export.cancel");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton(msg.get("dialog.export.ok"));
        ok.setName("export.ok");
        ok.addActionListener(e -> {
            chosen = options();
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        add(panel, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        formats.get(suggested.format()).setSelected(true);
        update();
        pack();
        setLocationRelativeTo(owner);
    }

    Optional<ExportOptions> choose() {
        setVisible(true);
        return Optional.ofNullable(chosen);
    }

    ExportOptions options() {
        ExportFormat format = ExportFormat.WAV;
        for (Map.Entry<ExportFormat, JRadioButton> e : formats.entrySet()) {
            if (e.getValue().isSelected()) format = e.getKey();
        }
        return new ExportOptions(format, loop.isSelected(), stems.isSelected() && format.audio(), recipe.isSelected());
    }

    JRadioButton format(ExportFormat f) {
        return formats.get(f);
    }

    /** Stems are sound files: not for MOD and MIDI. */
    private void update() {
        stems.setEnabled(options().format().audio());
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel note(String text) {
        JLabel label = new JLabel("<html><div style='width:380px'>" + text + "</div></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize2D() - 1));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }
}
