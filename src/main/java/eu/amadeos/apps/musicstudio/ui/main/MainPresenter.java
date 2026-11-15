package eu.amadeos.apps.musicstudio.ui.main;

import com.google.inject.Inject;
import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Variations;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.apps.musicstudio.service.export.ExportService;
import eu.amadeos.apps.musicstudio.service.play.Player;
import eu.amadeos.apps.musicstudio.service.render.MusicService;
import eu.amadeos.apps.musicstudio.service.render.MusicService.Piece;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.commons.app.Ui;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.ProgramMissingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Everything the window does: the current recipe and its sound, the history, playback, mute and solo, the
 * variations, files and export. Runs on the event dispatch thread; rendering and writing files happen in the
 * background.
 */
public final class MainPresenter {
    private static final Logger LOG = LoggerFactory.getLogger(MainPresenter.class);
    /** Pieces kept in the history. */
    static final int HISTORY_SIZE = 50;
    /** The seed of the sketches in the style list. */
    static final long SKETCH_SEED = 7;
    static final String[] KEY_NAMES = {"C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};

    private final MainView view;
    private final Messages msg;
    private final LocaleSettings locales;
    private final MusicStudioPreferences preferences;
    private final MusicService music;
    private final ExportService exports;
    private final MusicFiles files;
    private final Player player;
    private final Executor background;
    private final Executor ui;
    private final LongSupplier seeds;

    private Recipe recipe;
    private final List<Recipe> history = new ArrayList<>();
    private int current = -1;
    private Piece piece;
    private Recipe rendered;
    private MusicService.Task renderTask;
    private MusicService.Task remixTask;
    private AtomicBoolean exportCancel;
    private boolean playWhenReady;
    private Set<Part> kept;
    private final Set<Part> muted = EnumSet.noneOf(Part.class);
    private final Set<Part> solo = EnumSet.noneOf(Part.class);
    private boolean loop;
    private int savesRunning;
    /** A variation playing instead of the piece, while the variations dialog is open. */
    private MusicService.Task variationTask;

    @Inject
    public MainPresenter(MainView view, Messages msg, LocaleSettings locales, MusicStudioPreferences preferences,
                         MusicService music, ExportService exports, MusicFiles files, Player player,
                         ExecutorService background, @Ui Executor ui) {
        this(view, msg, locales, preferences, music, exports, files, player, (Executor) background, ui,
                randomSeeds());
    }

    MainPresenter(MainView view, Messages msg, LocaleSettings locales, MusicStudioPreferences preferences,
                  MusicService music, ExportService exports, MusicFiles files, Player player, Executor background,
                  Executor ui, LongSupplier seeds) {
        this.view = view;
        this.msg = msg;
        this.locales = locales;
        this.preferences = preferences;
        this.music = music;
        this.exports = exports;
        this.files = files;
        this.player = player;
        this.background = background;
        this.ui = ui;
        this.seeds = seeds;
    }

    private static LongSupplier randomSeeds() {
        SplittableRandom random = new SplittableRandom();
        return () -> 1 + random.nextLong(Recipe.MAX_SEED);
    }

    /** Opens the file named as first argument, else the last piece, else a new one. */
    public void start(List<String> arguments) {
        kept = preferences.keptParts();
        loop = preferences.loop();
        player.setLoop(loop);
        player.setOnEnd(() -> ui.execute(this::playbackEnded));
        Map<Style, Song> sketches = new EnumMap<>(Style.class);
        for (Style s : Style.values()) sketches.put(s, music.compose(Recipe.of(s, SKETCH_SEED), false));
        view.showStyleSketches(sketches);
        Recipe first = preferences.lastRecipe().orElseGet(() -> Recipe.of(Style.LOFI, seeds.getAsLong()));
        push(first);
        if (!arguments.isEmpty()) open(Path.of(arguments.getFirst()));
    }

    public Recipe recipe() {
        return recipe;
    }

    // ---------------------------------------------------------------- composing and rendering

    private void render() {
        if (renderTask != null) renderTask.cancel();
        if (remixTask != null) remixTask.cancel();
        remixTask = null;
        Recipe wanted = recipe;
        Song song = music.compose(wanted, false);
        view.showSong(song, song.seconds() + 3);
        view.showBusy(true);
        renderTask = music.render(wanted, false, audible(), new MusicService.Callback<>() {
            @Override
            public void done(Piece result, Duration time) {
                renderTask = null;
                piece = result;
                rendered = wanted;
                view.showSong(result.song(), result.audio().seconds());
                view.showWaveform(result.audio());
                if (player.isPlaying() && variationTask == null) {
                    player.replace(result.audio());
                } else if (playWhenReady) {
                    playWhenReady = false;
                    player.play(result.audio(), 0);
                }
                view.showStatus(describe(result.song(), result.audio(), time));
                busyIfNeeded();
                updateCommands();
            }

            @Override
            public void failed(Throwable error) {
                renderTask = null;
                playWhenReady = false;
                LOG.warn("Could not render {}", wanted.toLine(), error);
                view.showStatus(msg.format("error.render", describe(error)));
                busyIfNeeded();
                updateCommands();
            }
        });
        updateCommands();
    }

    /** Only the mix changes: other volumes, hall or crackle, or other parts muted. */
    private void remix() {
        if (piece == null || renderTask != null) {
            if (renderTask == null) render();
            return;
        }
        if (remixTask != null) remixTask.cancel();
        Piece base = piece;
        Recipe wanted = recipe;
        view.showBusy(true);
        remixTask = music.remix(base.stems(), wanted, audible(), new MusicService.Callback<>() {
            @Override
            public void done(Audio result, Duration time) {
                remixTask = null;
                piece = new Piece(base.song(), base.stems(), result);
                rendered = wanted;
                view.showWaveform(result);
                if (player.isPlaying() && variationTask == null) player.replace(result);
                busyIfNeeded();
                updateCommands();
            }

            @Override
            public void failed(Throwable error) {
                remixTask = null;
                LOG.warn("Could not mix {}", wanted.toLine(), error);
                view.showStatus(msg.format("error.render", describe(error)));
                busyIfNeeded();
                updateCommands();
            }
        });
    }

    /** The parts that can be heard: the solo parts if any, else all that are not muted. */
    Set<Part> audible() {
        Set<Part> parts = EnumSet.noneOf(Part.class);
        for (Part p : Part.values()) {
            if (solo.isEmpty() ? !muted.contains(p) : solo.contains(p)) parts.add(p);
        }
        return parts;
    }

    /** Whether two recipes have the same rendered parts and differ in the mix at most. */
    static boolean sameParts(Recipe a, Recipe b) {
        return mixFree(a).equals(mixFree(b));
    }

    private static Recipe mixFree(Recipe r) {
        return r.with(x -> {
            x.reverb = 0;
            x.crackle = 0;
            x.melody = x.melody.withVolume(Voice.DEFAULT_VOLUME);
            x.chords = x.chords.withVolume(Voice.DEFAULT_VOLUME);
            x.bass = x.bass.withVolume(Voice.DEFAULT_VOLUME);
            x.pad = x.pad.withVolume(Voice.DEFAULT_VOLUME);
            x.arpeggio = x.arpeggio.withVolume(Voice.DEFAULT_VOLUME);
            x.drumVolume = Voice.DEFAULT_VOLUME;
        });
    }

    // ---------------------------------------------------------------- the recipe

    /** A new piece: new seeds for all parts but the kept ones; style, sliders and instruments stay. */
    public void newPiece() {
        change(recipe.newPiece(seeds.getAsLong(), kept));
    }

    /** A new main seed for all parts, even the kept ones. */
    public void randomSeed() {
        change(recipe.withSeed(seeds.getAsLong()));
    }

    /** A seed typed in; false if it is not a number from 0 to {@link Recipe#MAX_SEED}. */
    public boolean setSeed(String text) {
        try {
            long seed = Long.parseLong(text.strip());
            if (seed < 0 || seed > Recipe.MAX_SEED) return false;
            if (seed != recipe.seed() || !recipe.oneSeed()) change(recipe.withSeed(seed));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void selectStyle(Style style) {
        if (style != recipe.style()) change(recipe.withStyle(style));
    }

    /** -1 for the key the seed gives. */
    public void setKey(int key) {
        change(recipe.with(b -> b.key = key));
    }

    public void setLength(int seconds) {
        change(recipe.with(b -> b.lengthSeconds = seconds));
    }

    /** The sliders of the settings. */
    public enum Slider {
        TEMPO, HARDNESS, SWING, ENERGY, FILLS, BRIGHTNESS, REVERB, CRACKLE, VARIETY;

        public String key() {
            return "slider." + name().toLowerCase(java.util.Locale.ROOT);
        }

        public int value(Recipe r) {
            return switch (this) {
                case TEMPO -> r.bpm();
                case HARDNESS -> r.hardness();
                case SWING -> r.swing();
                case ENERGY -> r.energy();
                case FILLS -> r.fills();
                case BRIGHTNESS -> r.brightness();
                case REVERB -> r.reverb();
                case CRACKLE -> r.crackle();
                case VARIETY -> r.variety();
            };
        }

        public int min() {
            return this == TEMPO ? Recipe.MIN_BPM : Recipe.MIN;
        }

        public int max() {
            return this == TEMPO ? Recipe.MAX_BPM : Recipe.MAX;
        }
    }

    public void setSlider(Slider slider, int value) {
        if (slider.value(recipe) == value) return;
        change(recipe.with(b -> {
            switch (slider) {
                case TEMPO -> b.bpm = value;
                case HARDNESS -> b.hardness = value;
                case SWING -> b.swing = value;
                case ENERGY -> b.energy = value;
                case FILLS -> b.fills = value;
                case BRIGHTNESS -> b.brightness = value;
                case REVERB -> b.reverb = value;
                case CRACKLE -> b.crackle = value;
                case VARIETY -> b.variety = value;
            }
        }));
    }

    /** Another instrument for the part; a pad or an arpeggio that was off plays again. */
    public void setInstrument(Part part, Instrument instrument) {
        Voice voice = recipe.voice(part);
        Recipe next = recipe.withVoice(part, voice.withInstrument(instrument));
        if (part.extra()) next = next.withPlaying(part, true);
        if (!next.equals(recipe)) change(next);
    }

    /** Switches the pad or the arpeggio on or off. */
    public void setPlaying(Part part, boolean on) {
        if (part.extra() && recipe.plays(part) != on) change(recipe.withPlaying(part, on));
    }

    public void setKit(Kit kit) {
        if (kit != recipe.kit()) change(recipe.with(b -> b.kit = kit));
    }

    public void setVolume(Part part, int volume) {
        if (recipe.volume(part) != volume) change(recipe.withVolume(part, volume));
    }

    /** The instrument chooser with attack, release and octave. */
    public void chooseInstrument(Part part) {
        if (!part.tonal()) return;
        Optional<Voice> chosen = view.chooseInstrument(part, recipe.voice(part), new MainView.InstrumentPreview() {
            @Override
            public Audio sound(Voice voice, int pitch) {
                return music.preview(voice, pitch);
            }

            @Override
            public void play(Audio sound) {
                playWhenReady = false;
                player.play(sound, 0);
                updateCommands();
            }
        });
        if (player.isPlaying()) stop();
        chosen.ifPresent(v -> {
            Recipe next = recipe.withVoice(part, v);
            if (part.extra()) next = next.withPlaying(part, true);
            if (!next.equals(recipe)) change(next);
        });
    }

    public void setKept(Part part, boolean keep) {
        Set<Part> next = EnumSet.noneOf(Part.class);
        next.addAll(kept);
        if (keep) next.add(part);
        else next.remove(part);
        kept = next;
        remember(() -> preferences.setKeptParts(next));
    }

    public Set<Part> kept() {
        return kept;
    }

    public void setMuted(Part part, boolean on) {
        if (on) muted.add(part);
        else muted.remove(part);
        showMix();
        remix();
    }

    public void setSolo(Part part, boolean on) {
        if (on) solo.add(part);
        else solo.remove(part);
        showMix();
        remix();
    }

    private void showMix() {
        Set<Part> m = EnumSet.noneOf(Part.class);
        m.addAll(muted);
        Set<Part> s = EnumSet.noneOf(Part.class);
        s.addAll(solo);
        view.showMix(m, s);
    }

    /** A new recipe as the next step of the history; what came after the current step is dropped. */
    private void push(Recipe next) {
        while (history.size() > current + 1) history.removeLast();
        history.add(next);
        if (history.size() > HISTORY_SIZE) history.removeFirst();
        current = history.size() - 1;
        apply(next);
    }

    private void change(Recipe next) {
        if (next.equals(recipe)) return;
        push(next);
    }

    private void apply(Recipe next) {
        Recipe before = recipe;
        recipe = next;
        view.showRecipe(next, kept);
        updateTitle();
        remember(() -> preferences.setLastRecipe(next));
        if (before != null && piece != null && renderTask == null && sameParts(before, next)) remix();
        else render();
        updateCommands();
    }

    public void goBack() {
        if (current > 0) apply(history.get(--current));
    }

    public void goForward() {
        if (current < history.size() - 1) apply(history.get(++current));
    }

    // ---------------------------------------------------------------- playback

    /** Play, or pause where it is. */
    public void playPause() {
        if (player.isPlaying()) {
            player.stop();
        } else if (piece != null && rendered != null && renderTask == null) {
            int from = player.position();
            if (from >= piece.audio().frames() - 1) from = 0;
            player.play(piece.audio(), from);
        } else {
            playWhenReady = true;
        }
        updateCommands();
    }

    /** Stops and goes back to the start. */
    public void stop() {
        playWhenReady = false;
        player.stop();
        if (piece != null) player.cue(piece.audio(), 0);
        view.showPosition(0);
        updateCommands();
    }

    public void toggleLoop() {
        loop = !loop;
        player.setLoop(loop);
        remember(() -> preferences.setLoop(loop));
        updateCommands();
    }

    /** Jumps to a time, playing or not. */
    public void seek(double seconds) {
        if (piece == null) return;
        int frame = (int) Math.max(0, Math.min(piece.audio().frames() - 1, seconds * piece.audio().sampleRate()));
        player.cue(piece.audio(), frame);
        view.showPosition(frame / (double) piece.audio().sampleRate());
        updateCommands();
    }

    /** Called often by the window's timer: moves the play head. */
    public void tick() {
        if (piece != null && variationTask == null) {
            view.showPosition(player.position() / (double) piece.audio().sampleRate());
        }
    }

    private void playbackEnded() {
        String failure = player.failure();
        if (failure != null) view.showStatus(msg.format("error.sound", failure));
        updateCommands();
    }

    // ---------------------------------------------------------------- variations

    public void showVariations() {
        if (player.isPlaying()) player.stop();
        List<Variations.Variation> all = Variations.around(recipe);
        List<String> labels = all.stream().map(v -> msg.get(v.key())).toList();
        Optional<Integer> chosen = view.chooseVariation(labels, new MainView.VariationPlayer() {
            @Override
            public void listen(int index, Runnable onReady) {
                if (variationTask != null) variationTask.cancel();
                player.stop();
                Recipe r = all.get(index).recipe();
                variationTask = music.render(r, false, EnumSet.allOf(Part.class), new MusicService.Callback<>() {
                    @Override
                    public void done(Piece result, Duration time) {
                        player.play(result.audio(), 0);
                        onReady.run();
                    }

                    @Override
                    public void failed(Throwable error) {
                        view.showStatus(msg.format("error.render", describe(error)));
                        onReady.run();
                    }
                });
            }

            @Override
            public void stop() {
                if (variationTask != null) variationTask.cancel();
                player.stop();
            }
        });
        if (variationTask != null) variationTask.cancel();
        variationTask = null;
        player.stop();
        chosen.ifPresent(i -> {
            change(all.get(i).recipe());
            view.showStatus(msg.format("status.variation", msg.get(all.get(i).key())));
        });
        updateCommands();
    }

    // ---------------------------------------------------------------- files

    public void openFile() {
        view.chooseOpenPath().ifPresent(this::open);
    }

    public void open(Path path) {
        savesRunning++;
        busyIfNeeded();
        background.execute(() -> {
            try {
                Recipe read = files.readRecipe(path);
                ui.execute(() -> {
                    savesRunning--;
                    push(read);
                    view.showStatus(msg.format("status.opened", path.getFileName()));
                });
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not open {}", path, e);
                ui.execute(() -> {
                    savesRunning--;
                    busyIfNeeded();
                    view.showError(msg.format("error.open", path.getFileName(), describe(e)));
                });
            }
        });
    }

    public void saveRecipe() {
        Recipe saved = recipe;
        view.chooseRecipeSavePath(saved.fileName() + "." + MusicFiles.RECIPE_EXTENSION).ifPresent(path -> {
            try {
                files.writeRecipe(path, saved);
                view.showStatus(msg.format("status.saved", path.getFileName()));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not save {}", path, e);
                view.showError(msg.format("error.save", path.getFileName(), describe(e)));
            }
        });
    }

    public void export() {
        if (exportCancel != null) return;
        double seconds = piece != null ? piece.audio().seconds() : recipe.lengthSeconds();
        Optional<MainView.ExportRequest> request = view.chooseExport(preferences.exportOptions(), recipe.fileName(),
                seconds);
        if (request.isEmpty()) return;
        ExportOptions options = request.get().options();
        Path target = ExportService.withExtension(request.get().target(), options.format());
        remember(() -> preferences.setExportOptions(options));
        Recipe exported = recipe;
        AtomicBoolean cancel = new AtomicBoolean();
        exportCancel = cancel;
        view.showStatus(msg.format("status.exporting", target.getFileName()));
        busyIfNeeded();
        updateCommands();
        background.execute(() -> {
            try {
                List<Path> written = exports.export(exported, options, target, cancel::get);
                ui.execute(() -> {
                    exportCancel = null;
                    view.showStatus(written.size() == 1 ? msg.format("status.exported", target.getFileName())
                            : msg.format("status.exported-stems", target.getFileName(), written.size() - 1));
                    busyIfNeeded();
                    updateCommands();
                });
            } catch (CancellationException e) {
                ui.execute(() -> {
                    exportCancel = null;
                    view.showStatus(msg.get("status.export-stopped"));
                    busyIfNeeded();
                    updateCommands();
                });
            } catch (ProgramMissingException e) {
                ui.execute(() -> exportFailed(target, msg.get("error.ogg-missing")));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                LOG.warn("Could not export {}", target, e);
                ui.execute(() -> exportFailed(target, describe(e)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void exportFailed(Path target, String reason) {
        exportCancel = null;
        busyIfNeeded();
        updateCommands();
        view.showStatus(" ");
        view.showError(msg.format("error.export", target.getFileName(), reason));
    }

    /** Stops a running export. */
    public void stopExport() {
        if (exportCancel != null) exportCancel.set(true);
    }

    public void copyRecipe() {
        view.copyToClipboard(recipe.toLine());
        view.showStatus(msg.get("status.copied-recipe"));
    }

    /** A recipe from the clipboard, e.g. copied from another Music Studio or from a mail. */
    public void pasteRecipe() {
        Optional<String> text = view.clipboardText();
        try {
            push(Recipe.parse(text.orElse("")));
            view.showStatus(msg.get("status.pasted"));
        } catch (IllegalArgumentException e) {
            view.showStatus(msg.get("error.paste"));
        }
    }

    // ---------------------------------------------------------------- window

    /** Escape stops playback, else a running export. */
    public void escape() {
        if (player.isPlaying()) player.stop();
        else stopExport();
        updateCommands();
    }

    public boolean isBarVisible(Bar bar) {
        return preferences.isVisible(bar);
    }

    public void setBarVisible(Bar bar, boolean visible) {
        view.showBar(bar, visible);
        remember(() -> preferences.setVisible(bar, visible));
    }

    public void setLanguage(Optional<Language> language) {
        try {
            locales.setApplicationLanguage(language);
        } catch (IOException e) {
            LOG.warn("Could not save the language", e);
            view.showError(msg.format("error.save-language", e.getMessage()));
            return;
        }
        if (locales.restartRequired()) {
            view.showInfo(Messages.commons(msg.locale()).format("language.restart", "amade-musicstudio"));
        }
    }

    public void about() {
        view.showAbout();
    }

    /** Closes the window; a running export is confirmed first. */
    public void exit() {
        if (exportCancel != null && !view.confirm(msg.get("dialog.exit-export.message"),
                msg.get("dialog.exit-export.title"))) {
            return;
        }
        stopExport();
        for (MusicService.Task t : new MusicService.Task[]{renderTask, remixTask, variationTask}) {
            if (t != null) t.cancel();
        }
        player.close();
        view.dispose();
    }

    // ---------------------------------------------------------------- helpers

    /** "Ab minor". */
    public String keyName(int keyNote, boolean minor) {
        return msg.format(minor ? "key.minor" : "key.major", KEY_NAMES[Math.floorMod(keyNote, 12)]);
    }

    private String describe(Song s, Audio audio, Duration time) {
        return msg.format("status.rendered", msg.get(s.recipe().style().key()), Long.toString(s.recipe().seed()),
                keyName(s.keyNote(), s.minor()), s.recipe().bpm(), s.bars(), clock(audio.seconds()),
                msg.format("time.seconds", time.toMillis() / 1000.0));
    }

    /** "1:05". */
    public static String clock(double seconds) {
        int s = (int) Math.round(Math.max(0, seconds));
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private void updateTitle() {
        view.showTitle(msg.format("window.title.piece", msg.get(recipe.style().key()), Long.toString(recipe.seed())));
    }

    private void busyIfNeeded() {
        view.showBusy(renderTask != null || remixTask != null || exportCancel != null || savesRunning > 0);
    }

    private void updateCommands() {
        view.updateCommands(new MainView.Commands(renderTask != null, piece != null, current > 0,
                current < history.size() - 1, player.isPlaying() || playWhenReady, loop, exportCancel != null));
    }

    /** Stores a choice; a full disk is only logged, the choice still applies until the program ends. */
    private void remember(Runnable store) {
        try {
            store.run();
        } catch (UncheckedIOException e) {
            LOG.warn("Could not save the preferences", e);
        }
    }

    private String describe(Throwable e) {
        Throwable cause = e instanceof UncheckedIOException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof OutOfMemoryError) return msg.get("error.memory");
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
