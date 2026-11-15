package eu.amadeos.apps.musicstudio.ui.main;

import com.google.inject.Inject;
import eu.amadeos.apps.musicstudio.bootstrap.AmadeMusicStudioApplication;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.border.BevelBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The main window: menu bar and toolbar, the styles on the left, the lanes of the piece in the middle, the
 * recipe on the right and a status bar.
 */
public final class MainWindow extends JFrame implements MainView {
    /** How often the play head moves, in milliseconds. */
    static final int TICK = 40;

    private final Messages msg;
    private final LocaleSettings locales;
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JPanel statusBar;
    private final Timer ticker;
    private StyleList styleList;
    private JScrollPane stylePane;
    private JScrollPane settingsPane;
    private SettingsPanel settingsPanel;
    private TrackPanel tracks;
    private MainMenuBar menuBar;
    private MainToolBar toolBar;
    private MusicActions actions;
    private MainPresenter presenter;
    private File lastDirectory;

    @Inject
    public MainWindow(Messages msg, LocaleSettings locales) {
        super(msg.get("window.title"));
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.locales = locales;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        statusBar = statusBar();
        add(statusBar, BorderLayout.SOUTH);
        java.net.URL icon = getClass().getResource("/icons/amade-musicstudio.png");
        if (icon != null) setIconImage(new javax.swing.ImageIcon(icon).getImage());
        ticker = new Timer(TICK, e -> {
            if (presenter != null) presenter.tick();
        });
        setPreferredSize(new Dimension(1300, 800));
        pack();
        setLocationRelativeTo(null);
    }

    public void bind(MainPresenter newPresenter) {
        EdtExecutor.requireEdt();
        presenter = newPresenter;
        actions = new MusicActions(presenter, msg);
        menuBar = new MainMenuBar(presenter, actions, msg, locales);
        setJMenuBar(menuBar);
        toolBar = new MainToolBar(actions);
        add(toolBar, BorderLayout.NORTH);
        styleList = new StyleList(msg, presenter::selectStyle);
        JPanel styles = new JPanel(new BorderLayout());
        JLabel heading = new JLabel(msg.get("styles"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        styles.add(heading, BorderLayout.NORTH);
        styles.add(styleList, BorderLayout.CENTER);
        JLabel note = new JLabel("<html><div style='width:160px'>" + msg.get("styles.note") + "</div></html>");
        note.setFont(note.getFont().deriveFont(Font.PLAIN, note.getFont().getSize2D() - 1));
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        styles.add(note, BorderLayout.SOUTH);
        stylePane = new JScrollPane(styles, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(stylePane, BorderLayout.WEST);
        settingsPanel = new SettingsPanel(presenter, actions, msg);
        settingsPane = new JScrollPane(settingsPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        settingsPane.setBorder(BorderFactory.createEmptyBorder());
        settingsPane.getVerticalScrollBar().setUnitIncrement(16);
        add(settingsPane, BorderLayout.EAST);
        tracks = new TrackPanel(presenter, msg);
        JPanel centre = new JPanel(new BorderLayout());
        centre.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4),
                BorderFactory.createBevelBorder(BevelBorder.LOWERED)));
        centre.add(tracks, BorderLayout.CENTER);
        add(centre, BorderLayout.CENTER);
        for (Action a : actions.all()) {
            KeyStroke key = (KeyStroke) a.getValue(Action.ACCELERATOR_KEY);
            if (key != null) bindKey(key, a);
        }
        for (MusicActions.KeyBinding binding : actions.extraKeys()) bindKey(binding.key(), binding.action());
        for (Bar bar : Bar.values()) showBar(bar, presenter.isBarVisible(bar));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                presenter.exit();
            }
        });
        updateCommands(Commands.NONE);
        ticker.start();
        pack();
        setLocationRelativeTo(null);
    }

    private void bindKey(KeyStroke key, Action action) {
        String name = action.getValue(Action.ACTION_COMMAND_KEY) + "@" + key;
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getRootPane().getActionMap().put(name, action);
    }

    // ---- parts, for tests

    public MusicActions actions() {
        return actions;
    }

    SettingsPanel settingsPanel() {
        return settingsPanel;
    }

    StyleList styleList() {
        return styleList;
    }

    TrackPanel tracks() {
        return tracks;
    }

    String statusText() {
        return status.getText();
    }

    JProgressBar progressBar() {
        return progress;
    }

    // ---------------------------------------------------------------- MainView

    @Override
    public void showTitle(String title) {
        EdtExecutor.requireEdt();
        setTitle(title);
    }

    @Override
    public void showRecipe(Recipe recipe, Set<Part> kept) {
        EdtExecutor.requireEdt();
        styleList.show(recipe.style());
        settingsPanel.show(recipe);
        tracks.show(recipe, kept);
        menuBar.show(recipe);
    }

    @Override
    public void showStyleSketches(Map<Style, Song> sketches) {
        EdtExecutor.requireEdt();
        styleList.setSketches(sketches);
    }

    @Override
    public void showSong(Song song, double seconds) {
        EdtExecutor.requireEdt();
        tracks.showSong(song, seconds);
        settingsPanel.showSeedKey(presenter.keyName(song.keyNote(), song.minor()));
    }

    @Override
    public void showWaveform(Audio audio) {
        EdtExecutor.requireEdt();
        tracks.showWaveform(audio);
    }

    @Override
    public void showPosition(double seconds) {
        EdtExecutor.requireEdt();
        tracks.showPosition(seconds);
    }

    @Override
    public void showMix(Set<Part> muted, Set<Part> solo) {
        EdtExecutor.requireEdt();
        tracks.showMix(muted, solo);
    }

    @Override
    public void showStatus(String text) {
        EdtExecutor.requireEdt();
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    @Override
    public void showBusy(boolean busy) {
        EdtExecutor.requireEdt();
        progress.setIndeterminate(busy);
        progress.setVisible(busy);
    }

    @Override
    public void updateCommands(Commands commands) {
        EdtExecutor.requireEdt();
        if (actions != null) actions.update(commands);
    }

    @Override
    public Optional<Path> chooseOpenPath() {
        EdtExecutor.requireEdt();
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory : musicDirectory());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        String patterns = String.join(", ", MusicFiles.OPEN_EXTENSIONS.stream().map(e -> "*." + e).toList());
        FileNameExtensionFilter filter = new FileNameExtensionFilter(msg.format("filter.open", patterns),
                MusicFiles.OPEN_EXTENSIONS.toArray(String[]::new));
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return Optional.empty();
        lastDirectory = chooser.getCurrentDirectory();
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    @Override
    public Optional<Path> chooseRecipeSavePath(String suggestedName) {
        return chooseSavePath(suggestedName, MusicFiles.RECIPE_EXTENSION,
                msg.format("filter.recipe", "*." + MusicFiles.RECIPE_EXTENSION));
    }

    @Override
    public Optional<ExportRequest> chooseExport(ExportOptions suggested, String suggestedName, double seconds) {
        EdtExecutor.requireEdt();
        Optional<ExportOptions> options = new ExportDialog(this, msg, suggested, seconds).choose();
        if (options.isEmpty()) return Optional.empty();
        ExportFormat format = options.get().format();
        return chooseSavePath(suggestedName + "." + format.extension(), format.extension(),
                msg.format("filter.export", msg.get(format.key()), "*." + format.extension()))
                .map(path -> new ExportRequest(options.get(), path));
    }

    private Optional<Path> chooseSavePath(String suggestedName, String extension, String description) {
        EdtExecutor.requireEdt();
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory : musicDirectory());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        FileNameExtensionFilter filter = new FileNameExtensionFilter(description, extension);
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), suggestedName));
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path path = chooser.getSelectedFile().toPath();
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
                path = path.resolveSibling(path.getFileName() + "." + extension);
            }
            lastDirectory = chooser.getCurrentDirectory();
            if (!Files.exists(path) || confirm(msg.format("dialog.overwrite.message", path.getFileName()),
                    msg.get("dialog.overwrite.title"))) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /** The music folder from the XDG user directories if it exists, else the home folder. */
    static File musicDirectory() {
        XdgDirectories xdg = XdgDirectories.system();
        Path music = musicDirectory(xdg);
        return (Files.isDirectory(music) ? music : xdg.home()).toFile();
    }

    /**
     * {@code XDG_MUSIC_DIR} from {@code user-dirs.dirs} ({@code ~/Musik}, {@code ~/Musique}), else {@code ~/Music}.
     */
    static Path musicDirectory(XdgDirectories xdg) {
        Path home = xdg.home();
        try {
            Path dirs = xdg.configHome().resolve("user-dirs.dirs");
            if (Files.isRegularFile(dirs)) {
                for (String line : Files.readAllLines(dirs)) {
                    line = line.strip();
                    if (!line.startsWith("XDG_MUSIC_DIR=")) continue;
                    String value = line.substring(line.indexOf('=') + 1).replace("\"", "");
                    if (value.startsWith("$HOME")) return home.resolve(value.substring(5).replaceFirst("^/+", ""));
                    if (value.startsWith("/")) return Path.of(value);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // an unreadable file counts as no file
        }
        return home.resolve("Music");
    }

    @Override
    public Optional<Voice> chooseInstrument(Part part, Voice current, InstrumentPreview preview) {
        EdtExecutor.requireEdt();
        return new InstrumentDialog(this, msg, part, current, preview).choose();
    }

    @Override
    public Optional<Integer> chooseVariation(List<String> labels, VariationPlayer player) {
        EdtExecutor.requireEdt();
        return new VariationsDialog(this, msg, labels, player).choose();
    }

    @Override
    public void copyToClipboard(String text) {
        EdtExecutor.requireEdt();
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    @Override
    public Optional<String> clipboardText() {
        EdtExecutor.requireEdt();
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data instanceof String s ? Optional.of(s) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void showBar(Bar bar, boolean visible) {
        EdtExecutor.requireEdt();
        switch (bar) {
            case TOOLBAR -> toolBar.setVisible(visible);
            case STYLES -> stylePane.setVisible(visible);
            case SETTINGS -> settingsPane.setVisible(visible);
            case STATUS_BAR -> statusBar.setVisible(visible);
        }
        revalidate();
    }

    @Override
    public boolean confirm(String message, String title) {
        EdtExecutor.requireEdt();
        return JOptionPane.showConfirmDialog(this, plain(message), title, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    @Override
    public void showError(String message) {
        EdtExecutor.requireEdt();
        JOptionPane.showMessageDialog(this, plain(message), msg.get("dialog.error.title"), JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void showInfo(String message) {
        EdtExecutor.requireEdt();
        JOptionPane.showMessageDialog(this, plain(message), getTitle(), JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void showAbout() {
        EdtExecutor.requireEdt();
        JOptionPane.showMessageDialog(this,
                "<html><center><h2>amade-musicstudio " + AmadeMusicStudioApplication.version() + "</h2>"
                        + "<p>" + msg.get("dialog.about.text") + "</p><br>"
                        + "<small>&copy; 2026 datazuul.com &bull; EUPL-1.2</small></center></html>",
                msg.get("dialog.about.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void dispose() {
        ticker.stop();
        super.dispose();
    }

    /** A text component, so that messages (with file names and errors) are not read as Swing HTML. */
    private static JTextArea plain(String message) {
        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setOpaque(false);
        text.setLineWrap(message.length() > 200);
        text.setWrapStyleWord(true);
        if (message.length() > 200) text.setColumns(50);
        return text;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        status.setName("status");
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(status);
        progress.setName("progress");
        progress.setPreferredSize(new Dimension(120, 14));
        progress.setVisible(false);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        right.add(progress);
        // Keeps the height while the progress bar is hidden.
        right.add(Box.createVerticalStrut(progress.getPreferredSize().height));
        bar.add(left, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }
}
