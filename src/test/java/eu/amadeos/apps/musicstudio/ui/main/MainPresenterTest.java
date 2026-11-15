package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.QueueExecutor;
import eu.amadeos.apps.musicstudio.bootstrap.MusicStudioModule;
import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.apps.musicstudio.service.export.ExportService;
import eu.amadeos.apps.musicstudio.service.export.OggEncoder;
import eu.amadeos.apps.musicstudio.service.export.WavWriter;
import eu.amadeos.apps.musicstudio.service.render.MusicService;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.ui.main.MainPresenter.Slider;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.ProgramMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainPresenterTest {
    static final Messages MSG = Messages.load(MusicStudioModule.class, MusicStudioModule.MESSAGES, Locale.ENGLISH);

    @TempDir
    Path dir;

    private final QueueExecutor executor = new QueueExecutor();
    private final FakeView view = new FakeView();
    private final FakePlayer player = new FakePlayer();
    private final AtomicLong nextSeed = new AtomicLong(100);
    private final AudioRenderer renderer = new AudioRenderer();
    private MusicStudioPreferences preferences;
    private MainPresenter presenter;
    private boolean oggMissing;

    /** Short pieces keep the tests quick. */
    private static Recipe shortPiece(Style style, long seed) {
        return Recipe.of(style, seed).with(b -> b.lengthSeconds = Recipe.MIN_LENGTH);
    }

    @BeforeEach
    void setUp() {
        preferences = new MusicStudioPreferences(dir.resolve("music-studio.properties"));
        preferences.setLastRecipe(shortPiece(Style.LOFI, 42));
        OggEncoder ogg = new OggEncoder((command) -> {
            if (oggMissing) throw new ProgramMissingException(command.getFirst(), new IOException("no"));
            throw new IOException("unexpected");
        }, new WavWriter());
        presenter = new MainPresenter(view, MSG, null, preferences,
                new MusicService(new Composer(), renderer, executor, Runnable::run, executor),
                new ExportService(new Composer(), renderer, Runnable::run, ogg), new MusicFiles(), player,
                executor, executor, nextSeed::getAndIncrement);
    }

    private void start() {
        presenter.start(List.of());
        executor.runAll();
    }

    @Test
    void startsWithTheLastPiece() {
        start();
        Recipe r = presenter.recipe();
        assertEquals(shortPiece(Style.LOFI, 42), r);
        assertEquals(r, view.recipe);
        assertEquals("Lo-Fi #42 - Music Studio", view.title);
        assertEquals(Style.values().length, view.sketches.size());
        assertNotNull(view.song);
        assertNotNull(view.waveform);
        assertEquals(view.waveform.seconds(), view.songSeconds, 1e-9);
        assertTrue(view.status.startsWith("Lo-Fi · Seed 42 · "), view.status);
        assertTrue(view.status.contains(" BPM · "), view.status);
        assertFalse(view.busy);
        assertTrue(view.commands.hasPiece());
        assertFalse(view.commands.rendering());
        assertFalse(view.commands.canGoBack());
        assertFalse(player.playing, "nothing plays by itself");
    }

    @Test
    void withoutALastPieceANewLoFiPiece() {
        presenter = new MainPresenter(view, MSG, null, new MusicStudioPreferences(dir.resolve("other.properties")),
                new MusicService(new Composer(), renderer, executor, Runnable::run, executor),
                new ExportService(new Composer(), renderer, Runnable::run, null), new MusicFiles(), player,
                executor, executor, nextSeed::getAndIncrement);
        presenter.start(List.of());
        assertEquals(Recipe.of(Style.LOFI, 100), presenter.recipe());
        assertTrue(view.commands.rendering());
        assertTrue(view.busy);
        assertFalse(view.commands.hasPiece());
    }

    @Test
    void anOpenedFileAsArgument() throws IOException {
        Path file = dir.resolve("p.musicrecipe");
        Recipe piano = shortPiece(Style.PIANO, 5);
        new MusicFiles().writeRecipe(file, piano);
        presenter.start(List.of(file.toString()));
        executor.runAll();
        assertEquals(piano, presenter.recipe());
        assertEquals(piano, view.recipe);
        assertTrue(view.commands.canGoBack(), "the last piece is one step back");
    }

    @Test
    void newPieceKeepsStyleSlidersAndKeptParts() {
        start();
        presenter.setSlider(Slider.TEMPO, 120);
        executor.runAll();
        presenter.setKept(Part.DRUMS, true);
        presenter.newPiece();
        executor.runAll();
        Recipe r = presenter.recipe();
        assertEquals(100, r.seed());
        assertEquals(120, r.bpm());
        assertEquals(42, r.seed(Part.DRUMS), "the drums are kept");
        assertEquals(100, r.seed(Part.MELODY));
        assertEquals(EnumSet.of(Part.DRUMS), preferences.keptParts());
        assertEquals(EnumSet.of(Part.DRUMS), view.kept);
        presenter.randomSeed();
        executor.runAll();
        assertEquals(101, presenter.recipe().seed(Part.DRUMS), "a random seed changes all parts");
    }

    @Test
    void seedsTyped() {
        start();
        assertFalse(presenter.setSeed("abc"));
        assertFalse(presenter.setSeed("-1"));
        assertFalse(presenter.setSeed(Long.toString(Recipe.MAX_SEED + 1)));
        assertTrue(presenter.setSeed(" 4711 "));
        executor.runAll();
        assertEquals(4711, presenter.recipe().seed());
        assertEquals("Lo-Fi #4711 - Music Studio", view.title);
    }

    @Test
    void historyGoesBackAndForward() {
        start();
        Recipe first = presenter.recipe();
        presenter.selectStyle(Style.CHIPTUNE);
        executor.runAll();
        presenter.setKey(3);
        executor.runAll();
        assertEquals(3, presenter.recipe().key());
        assertTrue(view.commands.canGoBack());
        presenter.goBack();
        presenter.goBack();
        executor.runAll();
        assertEquals(first, presenter.recipe());
        assertFalse(view.commands.canGoBack());
        assertTrue(view.commands.canGoForward());
        presenter.goForward();
        executor.runAll();
        assertEquals(Style.CHIPTUNE, presenter.recipe().style());
        presenter.setLength(30);
        executor.runAll();
        assertFalse(view.commands.canGoForward(), "a change drops what came after");
    }

    @Test
    void theHistoryHasALimit() {
        start();
        for (int i = 0; i < MainPresenter.HISTORY_SIZE + 5; i++) presenter.setSeed(Integer.toString(1000 + i));
        executor.runAll();
        for (int i = 0; i < MainPresenter.HISTORY_SIZE + 5; i++) presenter.goBack();
        executor.runAll();
        assertEquals(1005, presenter.recipe().seed(), "the oldest steps are dropped");
    }

    @Test
    void theMixAloneIsOnlyRemixed() {
        start();
        Song before = view.song;
        Audio audio = view.waveform;
        presenter.setSlider(Slider.REVERB, 90);
        presenter.setVolume(Part.BASS, 20);
        executor.runAll();
        assertSame(before, view.song, "no new notes for a new mix");
        assertNotSame(audio, view.waveform);
        assertTrue(MainPresenter.sameParts(presenter.recipe(), shortPiece(Style.LOFI, 42)));
        presenter.setSlider(Slider.HARDNESS, 90);
        executor.runAll();
        assertNotSame(before, view.song);
        assertFalse(MainPresenter.sameParts(presenter.recipe(), shortPiece(Style.LOFI, 42)));
    }

    @Test
    void muteAndSolo() {
        start();
        presenter.setMuted(Part.DRUMS, true);
        executor.runAll();
        assertEquals(Set.of(Part.DRUMS), view.muted);
        assertEquals(EnumSet.complementOf(EnumSet.of(Part.DRUMS)), presenter.audible());
        presenter.setSolo(Part.BASS, true);
        executor.runAll();
        assertEquals(Set.of(Part.BASS), view.solo);
        assertEquals(EnumSet.of(Part.BASS), presenter.audible());
        presenter.setSolo(Part.BASS, false);
        presenter.setMuted(Part.DRUMS, false);
        executor.runAll();
        assertEquals(EnumSet.allOf(Part.class), presenter.audible());
        assertEquals(shortPiece(Style.LOFI, 42), presenter.recipe(), "muting is not part of the recipe");
    }

    @Test
    void instrumentsAndKits() {
        start();
        presenter.setInstrument(Part.MELODY, Instrument.MARIMBA);
        presenter.setKit(Kit.values()[Kit.values().length - 1]);
        executor.runAll();
        assertEquals(Instrument.MARIMBA, presenter.recipe().voice(Part.MELODY).instrument());
        assertEquals(Kit.values()[Kit.values().length - 1], presenter.recipe().kit());
        Voice chosen = Voice.of(Instrument.HARP).withOctave(1).withAttack(10).withRelease(90).withVolume(60);
        view.instrumentAnswer = preview -> {
            Audio sound = preview.sound(chosen, 60);
            preview.play(sound);
            assertTrue(player.playing);
            assertSame(sound, player.audio);
            return Optional.of(chosen);
        };
        presenter.chooseInstrument(Part.CHORDS);
        executor.runAll();
        assertFalse(player.playing, "trying notes stops with the dialog");
        assertEquals(chosen, presenter.recipe().voice(Part.CHORDS));
        Recipe r = presenter.recipe();
        presenter.chooseInstrument(Part.DRUMS);
        view.instrumentAnswer = preview -> Optional.empty();
        presenter.chooseInstrument(Part.BASS);
        assertEquals(r, presenter.recipe());
    }

    @Test
    void padAndArpeggioCanBeSwitchedOffAndOn() {
        start();
        assertTrue(presenter.recipe().plays(Part.PAD), "a Lo-Fi piece has a pad");
        assertFalse(presenter.recipe().plays(Part.ARPEGGIO));
        presenter.setPlaying(Part.PAD, false);
        executor.runAll();
        assertFalse(presenter.recipe().plays(Part.PAD));
        assertFalse(view.recipe.plays(Part.PAD));
        presenter.setPlaying(Part.MELODY, false);
        assertTrue(presenter.recipe().plays(Part.MELODY), "only the extras can be switched off");
        presenter.setInstrument(Part.ARPEGGIO, Instrument.HARP);
        executor.runAll();
        assertTrue(presenter.recipe().plays(Part.ARPEGGIO), "choosing an instrument switches the part on");
        assertEquals(Instrument.HARP, presenter.recipe().voice(Part.ARPEGGIO).instrument());
        Voice moving = Voice.of(Instrument.STRINGS).withSweep(70, 2).withTremolo(40, 4);
        view.instrumentAnswer = preview -> Optional.of(moving);
        presenter.chooseInstrument(Part.PAD);
        executor.runAll();
        assertTrue(presenter.recipe().plays(Part.PAD));
        assertEquals(moving, presenter.recipe().voice(Part.PAD));
        presenter.goBack();
        presenter.goBack();
        assertFalse(presenter.recipe().plays(Part.ARPEGGIO), "switching is a step of the history");
    }

    @Test
    void playPauseStopAndSeek() {
        start();
        presenter.playPause();
        assertTrue(player.playing);
        assertSame(view.waveform, player.audio);
        assertTrue(view.commands.playing());
        player.position = 44100;
        presenter.tick();
        assertEquals(1.0, view.position, 1e-9);
        presenter.playPause();
        assertFalse(player.playing);
        presenter.playPause();
        assertEquals(44100, player.position, "pause goes on where it stopped");
        presenter.seek(2.5);
        assertEquals(2.5, view.position, 1e-9);
        assertEquals(110250, player.position);
        presenter.stop();
        assertFalse(player.playing);
        assertEquals(0, player.position);
        assertEquals(0, view.position);
        presenter.toggleLoop();
        assertTrue(player.loop);
        assertTrue(preferences.loop());
        assertTrue(view.commands.loop());
    }

    @Test
    void playWhileRenderingStartsWhenReady() {
        start();
        presenter.selectStyle(Style.PIANO);
        presenter.playPause();
        assertFalse(player.playing);
        assertTrue(view.commands.playing(), "the button shows it will play");
        executor.runAll();
        assertTrue(player.playing);
        assertEquals(1, player.plays);
        presenter.selectStyle(Style.AMBIENT);
        executor.runAll();
        assertEquals(1, player.plays, "a new piece while playing replaces the sound");
        assertSame(view.waveform, player.audio);
    }

    @Test
    void theEndOfPlaybackAndASoundFailure() {
        start();
        presenter.playPause();
        player.failure = "no device";
        player.end();
        executor.runAll();
        assertEquals("No sound output: no device", view.status);
        assertFalse(view.commands.playing());
    }

    @Test
    void variations() {
        start();
        Recipe before = presenter.recipe();
        view.variationAnswer = p -> {
            p.listen(2, () -> { });
            executor.runAll();
            assertTrue(player.playing);
            p.stop();
            assertFalse(player.playing);
            return Optional.of(3);
        };
        presenter.showVariations();
        assertTrue(view.status.startsWith("Variation taken: "), view.status);
        executor.runAll();
        assertEquals(8, view.variationLabels.size());
        assertNotEquals(before, presenter.recipe());
        assertTrue(view.commands.canGoBack());
        view.variationAnswer = p -> Optional.empty();
        Recipe now = presenter.recipe();
        presenter.showVariations();
        assertEquals(now, presenter.recipe());
    }

    @Test
    void saveOpenAndBrokenFiles() throws IOException {
        start();
        Path file = dir.resolve("mine.musicrecipe");
        view.savePath = Optional.of(file);
        presenter.saveRecipe();
        assertEquals("lofi-42.musicrecipe", view.suggestedName);
        assertEquals("Saved mine.musicrecipe.", view.status);
        presenter.selectStyle(Style.ADVENTURE);
        executor.runAll();
        view.openPath = Optional.of(file);
        presenter.openFile();
        executor.runAll();
        assertEquals(shortPiece(Style.LOFI, 42), presenter.recipe());
        Path broken = dir.resolve("broken.musicrecipe");
        Files.writeString(broken, "no recipe");
        presenter.open(broken);
        executor.runAll();
        assertEquals(1, view.errors.size());
        assertTrue(view.errors.getFirst().contains("broken.musicrecipe"), view.errors.getFirst());
        assertFalse(view.busy);
        view.savePath = Optional.of(dir.resolve("missing/dir/x.musicrecipe"));
        presenter.saveRecipe();
        assertEquals(2, view.errors.size());
    }

    @Test
    void exportToWav() {
        start();
        Path target = dir.resolve("out");
        view.exportRequest = Optional.of(new MainView.ExportRequest(
                new ExportOptions(ExportFormat.WAV, false, true, true), target));
        presenter.export();
        assertEquals(ExportOptions.DEFAULT, view.suggestedExport);
        assertEquals("lofi-42", view.suggestedName);
        assertEquals("Exporting out.wav...", view.status);
        assertTrue(view.commands.exporting());
        assertTrue(view.busy);
        executor.runAll();
        assertEquals("Exported out.wav and 5 files with the single parts.", view.status);
        assertTrue(Files.exists(dir.resolve("out.wav")));
        assertTrue(Files.exists(dir.resolve("out-drums.wav")));
        assertFalse(view.commands.exporting());
        assertEquals(ExportFormat.WAV, preferences.exportOptions().format());
        assertTrue(preferences.exportOptions().stems());
    }

    @Test
    void exportStoppedAndFailed() {
        start();
        view.exportRequest = Optional.of(new MainView.ExportRequest(
                new ExportOptions(ExportFormat.FLAC, false, false, true), dir.resolve("a.flac")));
        presenter.export();
        presenter.escape();
        executor.runAll();
        assertEquals("Export stopped.", view.status);
        assertFalse(Files.exists(dir.resolve("a.flac")));

        oggMissing = true;
        view.exportRequest = Optional.of(new MainView.ExportRequest(
                new ExportOptions(ExportFormat.OGG, false, false, true), dir.resolve("b.ogg")));
        presenter.export();
        executor.runAll();
        assertEquals(1, view.errors.size());
        assertTrue(view.errors.getFirst().contains("GStreamer"), view.errors.getFirst());
        assertFalse(view.busy);

        view.exportRequest = Optional.empty();
        presenter.export();
        assertFalse(view.commands.exporting());
    }

    @Test
    void copyAndPasteTheRecipe() {
        start();
        presenter.copyRecipe();
        assertEquals(presenter.recipe().toLine(), view.clipboard);
        presenter.selectStyle(Style.MEDITATION);
        executor.runAll();
        presenter.pasteRecipe();
        assertEquals("Recipe pasted.", view.status);
        executor.runAll();
        assertEquals(shortPiece(Style.LOFI, 42), presenter.recipe());
        view.clipboard = "hello";
        presenter.pasteRecipe();
        assertEquals("There is no recipe on the clipboard.", view.status);
        view.clipboard = null;
        presenter.pasteRecipe();
        assertEquals("There is no recipe on the clipboard.", view.status);
    }

    @Test
    void barsAndExit() {
        start();
        assertTrue(presenter.isBarVisible(Bar.STYLES));
        presenter.setBarVisible(Bar.STYLES, false);
        assertEquals(Boolean.FALSE, view.bars.get(Bar.STYLES));
        assertFalse(presenter.isBarVisible(Bar.STYLES));
        presenter.about();
        assertTrue(view.aboutShown);

        view.exportRequest = Optional.of(new MainView.ExportRequest(
                new ExportOptions(ExportFormat.WAV, false, false, true), dir.resolve("c.wav")));
        presenter.export();
        presenter.exit();
        assertEquals(1, view.confirmations);
        assertFalse(view.disposed, "the export goes on");
        view.confirmAnswer = true;
        presenter.exit();
        assertTrue(view.disposed);
        assertTrue(player.closed);
    }

    @Test
    void keyNamesAndClock() {
        assertEquals("Ab minor", presenter.keyName(8, true));
        assertEquals("C major", presenter.keyName(12, false));
        assertEquals("0:00", MainPresenter.clock(-1));
        assertEquals("1:05", MainPresenter.clock(64.6));
        assertEquals("10:00", MainPresenter.clock(600));
    }

    @Test
    void escapeStopsPlayback() {
        start();
        presenter.playPause();
        presenter.escape();
        assertFalse(player.playing);
        assertNull(player.failure);
    }
}
