package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.swing.ToolButtons;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JToolBar;
import javax.swing.border.BevelBorder;

/** New piece; back, forward; play, stop, loop; variations; open, save, export. */
public final class MainToolBar extends JToolBar {
    public MainToolBar(MusicActions actions) {
        EdtExecutor.requireEdt();
        setFloatable(false);
        setRollover(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED),
                BorderFactory.createEmptyBorder(2, 3, 2, 3)));
        JButton newPiece = ToolButtons.button(actions.newPiece);
        newPiece.setName("tool.new-piece");
        add(newPiece);
        addSeparator();
        add(ToolButtons.button(actions.back));
        add(ToolButtons.button(actions.forward));
        addSeparator();
        AbstractButton play = ToolButtons.toggle(actions.play);
        play.setName("tool.play");
        add(play);
        add(ToolButtons.button(actions.stop));
        AbstractButton loop = ToolButtons.toggle(actions.loop);
        loop.setName("tool.loop");
        add(loop);
        addSeparator();
        add(ToolButtons.button(actions.variations));
        addSeparator();
        add(ToolButtons.button(actions.open));
        add(ToolButtons.button(actions.saveRecipe));
        add(ToolButtons.button(actions.export));
    }
}
