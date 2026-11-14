package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.border.BevelBorder;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** File, Edit, Piece, Play, View, Options and Help. */
public final class MainMenuBar extends JMenuBar {
    private final Messages msg;
    private final Map<Style, JRadioButtonMenuItem> styles = new EnumMap<>(Style.class);

    public MainMenuBar(MainPresenter presenter, MusicActions actions, Messages msg, LocaleSettings locales) {
        EdtExecutor.requireEdt();
        this.msg = msg;
        setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));

        JMenu file = menu("menu.file");
        file.add(item(actions.open));
        file.add(item(actions.saveRecipe));
        file.addSeparator();
        file.add(item(actions.export));
        file.add(item(actions.stopExport));
        file.addSeparator();
        file.add(item(actions.exit));

        JMenu edit = menu("menu.edit");
        edit.add(item(actions.back));
        edit.add(item(actions.forward));
        edit.addSeparator();
        edit.add(item(actions.copyRecipe));
        edit.add(item(actions.pasteRecipe));

        JMenu piece = menu("menu.piece");
        piece.add(item(actions.newPiece));
        piece.add(item(actions.randomSeed));
        piece.add(item(actions.variations));
        piece.addSeparator();
        JMenu styleMenu = Mnemonics.apply(new JMenu(), msg.get("menu.piece.style"));
        styleMenu.setName("menu.piece.style");
        ButtonGroup styleGroup = new ButtonGroup();
        int n = 1;
        for (Style s : Style.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(msg.get(s.key()));
            item.setName(s.key());
            if (n <= 9) item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, InputEvent.ALT_DOWN_MASK));
            n++;
            item.addActionListener(e -> presenter.selectStyle(s));
            styleGroup.add(item);
            styleMenu.add(item);
            styles.put(s, item);
        }
        piece.add(styleMenu);
        JMenu instruments = Mnemonics.apply(new JMenu(), msg.get("menu.piece.instrument"));
        instruments.setName("menu.piece.instrument");
        for (Part p : Part.values()) {
            if (!p.tonal()) continue;
            JMenuItem item = new JMenuItem(msg.format("menu.piece.instrument.for", msg.get(p.key())));
            item.setName("menu.piece.instrument." + p.id());
            item.addActionListener(e -> presenter.chooseInstrument(p));
            instruments.add(item);
        }
        piece.add(instruments);

        JMenu play = menu("menu.play");
        play.add(item(actions.play));
        play.add(item(actions.stop));
        JCheckBoxMenuItem loop = new JCheckBoxMenuItem(actions.loop);
        loop.setName("menu.play.loop");
        loop.setIcon(null);
        play.add(loop);

        JMenu view = menu("menu.view");
        for (Bar bar : Bar.values()) {
            JCheckBoxMenuItem item = Mnemonics.apply(new JCheckBoxMenuItem(), msg.get(barKey(bar)));
            item.setSelected(presenter.isBarVisible(bar));
            item.setName(barKey(bar));
            item.addActionListener(e -> presenter.setBarVisible(bar, item.isSelected()));
            view.add(item);
        }

        JMenu options = menu("menu.options");
        if (locales != null) options.add(languageMenu(presenter, locales));

        JMenu help = menu("menu.help");
        JMenuItem about = new JMenuItem(msg.get("menu.help.about"));
        about.setName("menu.help.about");
        about.addActionListener(e -> presenter.about());
        help.add(about);
    }

    /** Ticks the style of the recipe. */
    public void show(Recipe recipe) {
        EdtExecutor.requireEdt();
        styles.get(recipe.style()).setSelected(true);
    }

    static String barKey(Bar bar) {
        return "menu.view." + bar.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** "Desktop setting (Deutsch)" and the five languages; the choice applies at the next start. */
    private JMenu languageMenu(MainPresenter presenter, LocaleSettings locales) {
        JMenu menu = new JMenu(msg.get("menu.options.language"));
        Messages commons = Messages.commons(msg.locale());
        List<Optional<Language>> choices = new ArrayList<>();
        choices.add(Optional.empty());
        for (Language language : Language.values()) choices.add(Optional.of(language));
        ButtonGroup group = new ButtonGroup();
        Optional<Language> current = locales.applicationLanguage();
        for (Optional<Language> choice : choices) {
            String label = choice.map(Language::nativeName).orElseGet(() -> commons.format("language.follow-desktop",
                    locales.desktopResolution().language().nativeName()));
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, choice.equals(current));
            item.setName(choice.map(Language::code).orElse(LocaleSettings.FOLLOW_DESKTOP));
            item.addActionListener(e -> presenter.setLanguage(choice));
            group.add(item);
            menu.add(item);
            if (choice.isEmpty()) menu.addSeparator();
        }
        return menu;
    }

    private JMenu menu(String key) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get(key));
        menu.setName(key);
        add(menu);
        return menu;
    }

    private static JMenuItem item(Action action) {
        JMenuItem item = new JMenuItem(action);
        item.setName(String.valueOf(action.getValue(Action.ACTION_COMMAND_KEY)));
        item.setIcon(null);
        return item;
    }
}
