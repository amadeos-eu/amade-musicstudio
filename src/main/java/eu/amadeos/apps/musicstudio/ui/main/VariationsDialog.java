package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.ui.icons.MusicGlyph;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.swing.ToolIcon;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Eight relatives of the piece: listen to each, then take one. Listening renders the variation first, which
 * takes a second or two.
 */
final class VariationsDialog extends JDialog {
    private final List<JToggleButton> listen = new ArrayList<>();
    private final List<JButton> use = new ArrayList<>();
    private Integer chosen;

    VariationsDialog(Window owner, Messages msg, List<String> labels, MainView.VariationPlayer player) {
        super(owner, msg.get("dialog.variations.title"), ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        setName("dialog.variations");
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
        grid.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            JPanel card = new JPanel(new BorderLayout(8, 0));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            JLabel label = new JLabel(labels.get(i));
            JToggleButton play = new JToggleButton(new ToolIcon(MusicGlyph.PLAY, 16));
            play.setName("variation." + i + ".listen");
            play.setToolTipText(msg.get("dialog.variations.listen"));
            JButton take = new JButton(msg.get("dialog.variations.use"));
            take.setName("variation." + i + ".use");
            play.addActionListener(e -> {
                if (play.isSelected()) {
                    label.setText(labels.get(index) + " …");
                    player.listen(index, () -> label.setText(labels.get(index)));
                } else {
                    player.stop();
                    group.clearSelection();
                }
            });
            take.addActionListener(e -> {
                chosen = index;
                player.stop();
                dispose();
            });
            group.add(play);
            listen.add(play);
            use.add(take);
            card.add(play, BorderLayout.WEST);
            card.add(label, BorderLayout.CENTER);
            card.add(take, BorderLayout.EAST);
            grid.add(card);
        }
        JLabel note = new JLabel(msg.get("dialog.variations.note"));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        note.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        JButton close = new JButton(msg.get("button.cancel"));
        close.setName("variations.cancel");
        close.addActionListener(e -> {
            player.stop();
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);
        add(note, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> {
            player.stop();
            dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    Optional<Integer> choose() {
        setVisible(true);
        return Optional.ofNullable(chosen);
    }

    JButton useButton(int index) {
        return use.get(index);
    }

    JToggleButton listenButton(int index) {
        return listen.get(index);
    }
}
