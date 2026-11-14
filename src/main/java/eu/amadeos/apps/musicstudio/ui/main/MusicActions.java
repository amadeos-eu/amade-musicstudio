package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.ui.icons.MusicGlyph;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.StandardGlyph;
import eu.amadeos.commons.swing.ToolIcon;
import eu.amadeos.commons.swing.ToolIcon.Glyph;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * One action per command, shared by the menus and the toolbar, so that both are enabled and disabled
 * together. Texts come from the messages ({@code &} marks the mnemonic), icons from {@link ToolIcon}.
 */
public final class MusicActions {
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    public final Action newPiece;
    public final Action open;
    public final Action saveRecipe;
    public final Action export;
    public final Action exit;
    public final Action back;
    public final Action forward;
    public final Action copyRecipe;
    public final Action pasteRecipe;
    public final Action randomSeed;
    public final Action variations;
    public final Action play;
    public final Action stop;
    public final Action loop;
    public final Action stopExport;
    /** Escape, without a menu entry: stops playback, else the export. */
    public final Action escape;

    private final Messages msg;

    public MusicActions(MainPresenter presenter, Messages msg) {
        this.msg = msg;
        newPiece = action("menu.piece.new", MusicGlyph.NEW_PIECE, KeyEvent.VK_N, CTRL, presenter::newPiece);
        open = action("menu.file.open", StandardGlyph.OPEN, KeyEvent.VK_O, CTRL, presenter::openFile);
        saveRecipe = action("menu.file.save-recipe", StandardGlyph.SAVE, KeyEvent.VK_S, CTRL, presenter::saveRecipe);
        export = action("menu.file.export", StandardGlyph.EXPORT, KeyEvent.VK_E, CTRL, presenter::export);
        exit = action("menu.file.exit", null, KeyEvent.VK_Q, CTRL, presenter::exit);
        back = action("menu.edit.back", MusicGlyph.BACK, KeyEvent.VK_LEFT, ALT, presenter::goBack);
        forward = action("menu.edit.forward", MusicGlyph.FORWARD, KeyEvent.VK_RIGHT, ALT, presenter::goForward);
        copyRecipe = action("menu.edit.copy-recipe", StandardGlyph.COPY, KeyEvent.VK_C, CTRL | SHIFT,
                presenter::copyRecipe);
        pasteRecipe = action("menu.edit.paste-recipe", null, KeyEvent.VK_V, CTRL | SHIFT, presenter::pasteRecipe);
        randomSeed = action("menu.piece.random-seed", null, KeyEvent.VK_R, CTRL, presenter::randomSeed);
        variations = action("menu.piece.variations", MusicGlyph.VARIATIONS, KeyEvent.VK_G, CTRL,
                presenter::showVariations);
        play = action("menu.play.play", MusicGlyph.PLAY, KeyEvent.VK_SPACE, 0, presenter::playPause);
        stop = action("menu.play.stop", MusicGlyph.STOP, KeyEvent.VK_HOME, CTRL, presenter::stop);
        loop = action("menu.play.loop", MusicGlyph.LOOP, KeyEvent.VK_L, CTRL, presenter::toggleLoop);
        stopExport = action("menu.file.stop-export", null, 0, 0, presenter::stopExport);
        escape = new AbstractAction("escape") {
            @Override
            public void actionPerformed(ActionEvent e) {
                presenter.escape();
            }
        };
        escape.putValue(Action.ACTION_COMMAND_KEY, "escape");
        update(MainView.Commands.NONE);
    }

    /** All actions with a menu entry, for tests and key bindings. */
    public List<Action> all() {
        return List.of(newPiece, open, saveRecipe, export, stopExport, exit, back, forward, copyRecipe, pasteRecipe,
                randomSeed, variations, play, stop, loop);
    }

    /** Further keys besides the accelerators: Escape, and Ctrl+Z and Ctrl+Y for back and forward. */
    public List<KeyBinding> extraKeys() {
        return List.of(new KeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), escape),
                new KeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_Z, CTRL), back),
                new KeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_Y, CTRL), forward));
    }

    public record KeyBinding(KeyStroke key, Action action) {
    }

    public void update(MainView.Commands c) {
        back.setEnabled(c.canGoBack());
        forward.setEnabled(c.canGoForward());
        export.setEnabled(!c.exporting());
        stopExport.setEnabled(c.exporting());
        stop.setEnabled(c.hasPiece());
        play.putValue(Action.SELECTED_KEY, c.playing());
        loop.putValue(Action.SELECTED_KEY, c.loop());
        String playKey = c.playing() ? "menu.play.pause" : "menu.play.play";
        Mnemonics.Text text = Mnemonics.parse(msg.get(playKey));
        play.putValue(Action.NAME, text.text());
        play.putValue(Action.SHORT_DESCRIPTION, msg.has(playKey + ".tip") ? msg.get(playKey + ".tip") : text.text());
    }

    private Action action(String key, Glyph glyph, int keyCode, int modifiers, Runnable command) {
        Mnemonics.Text text = Mnemonics.parse(msg.get(key));
        Action action = new AbstractAction(text.text()) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isEnabled()) command.run();
            }
        };
        action.putValue(Action.ACTION_COMMAND_KEY, key);
        if (text.index() >= 0) {
            action.putValue(Action.MNEMONIC_KEY, text.keyCode());
            action.putValue(Action.DISPLAYED_MNEMONIC_INDEX_KEY, text.index());
        }
        String tip = msg.has(key + ".tip") ? msg.get(key + ".tip") : text.text();
        action.putValue(Action.SHORT_DESCRIPTION, tip);
        if (keyCode != 0) action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(keyCode, modifiers));
        if (glyph != null) {
            action.putValue(Action.SMALL_ICON, new ToolIcon(glyph, 16));
            action.putValue(Action.LARGE_ICON_KEY, new ToolIcon(glyph));
        }
        return action;
    }
}
