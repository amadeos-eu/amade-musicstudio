package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.OnEdt;
import eu.amadeos.apps.musicstudio.QueueExecutor;
import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.export.ExportService;
import eu.amadeos.apps.musicstudio.service.render.MusicService;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.ui.main.MainPresenter.Slider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The real window with the real composer and synthesizer, without sound. Needs a display (Xvfb in CI). */
class MainWindowTest {
    @TempDir
    Path dir;

    @Test
    void composeThroughTheWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        OnEdt.run(() -> {
            QueueExecutor executor = new QueueExecutor();
            MusicStudioPreferences preferences = new MusicStudioPreferences(dir.resolve("music-studio.properties"));
            preferences.setLastRecipe(Recipe.of(Style.CHIPTUNE, 500).with(b -> b.lengthSeconds = Recipe.MIN_LENGTH));
            MainWindow window = new MainWindow(MainPresenterTest.MSG, null);
            AudioRenderer renderer = new AudioRenderer();
            FakePlayer player = new FakePlayer();
            MainPresenter presenter = new MainPresenter(window, MainPresenterTest.MSG, null, preferences,
                    new MusicService(new Composer(), renderer, executor, Runnable::run, executor),
                    new ExportService(new Composer(), renderer, Runnable::run, null), new MusicFiles(), player,
                    executor, executor, new AtomicLong(900)::getAndIncrement);
            window.bind(presenter);
            try {
                MusicActions actions = window.actions();
                presenter.start(List.of());
                assertTrue(window.progressBar().isVisible(), "busy while rendering");
                executor.runAll();
                assertTrue(actions.stop.isEnabled());
                assertFalse(actions.back.isEnabled());
                assertEquals("Chiptune #500 - Music Studio", window.getTitle());
                assertTrue(window.statusText().startsWith("Chiptune · Seed 500 · "), window.statusText());
                assertEquals(Style.CHIPTUNE, window.styleList().getSelectedValue());

                SettingsPanel settings = window.settingsPanel();
                assertEquals("500", settings.seedField().getText());
                settings.seedField().setText("42");
                settings.seedField().postActionEvent();
                assertEquals(42, presenter.recipe().seed());
                settings.seedField().setText("forty-two");
                settings.seedField().postActionEvent();
                assertEquals(42, presenter.recipe().seed(), "a wrong seed changes nothing");
                assertEquals(0xC0, settings.seedField().getForeground().getRed(), "and shows red");
                settings.slider(Slider.TEMPO).setValue(150);
                assertEquals(150, presenter.recipe().bpm());
                settings.keyBox().setSelectedIndex(1);
                assertEquals(0, presenter.recipe().key(), "C");
                window.styleList().setSelectedValue(Style.ACOUSTIC, false);
                assertEquals(Style.ACOUSTIC, presenter.recipe().style());
                executor.runAll();
                assertTrue(actions.back.isEnabled());

                // The lane heads.
                TrackPanel tracks = window.tracks();
                tracks.head(Part.MELODY).sound.setSelectedItem(Instrument.BELLS);
                assertEquals(Instrument.BELLS, presenter.recipe().voice(Part.MELODY).instrument());
                tracks.head(Part.DRUMS).mute.doClick();
                assertEquals(java.util.EnumSet.of(Part.MELODY, Part.CHORDS, Part.BASS), presenter.audible());
                tracks.head(Part.BASS).keep.doClick();
                assertEquals(java.util.Set.of(Part.BASS), presenter.kept());
                executor.runAll();

                // Playing.
                actions.play.actionPerformed(null);
                assertTrue(player.playing);
                assertEquals("Pause", actions.play.getValue(javax.swing.Action.NAME));
                assertEquals(Boolean.TRUE, actions.play.getValue(javax.swing.Action.SELECTED_KEY));
                player.position = 3 * 44100;
                presenter.tick();
                assertTrue(tracks.timeText().startsWith("0:03 / "), tracks.timeText());
                actions.stop.actionPerformed(null);
                assertFalse(player.playing);
                assertEquals("Play", actions.play.getValue(javax.swing.Action.NAME));
                actions.loop.actionPerformed(null);
                assertTrue(preferences.loop());

                // History and bars.
                actions.back.actionPerformed(null);
                actions.forward.actionPerformed(null);
                actions.newPiece.actionPerformed(null);
                executor.runAll();
                assertEquals(500 + 400, presenter.recipe().seed());
                presenter.setBarVisible(Bar.STYLES, false);
                assertFalse(preferences.isVisible(Bar.STYLES));
                presenter.setBarVisible(Bar.STYLES, true);
            } finally {
                window.dispose();
            }
        });
    }
}
