package eu.amadeos.apps.musicstudio.service.render;

import eu.amadeos.apps.musicstudio.QueueExecutor;
import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicServiceTest {
    private final QueueExecutor background = new QueueExecutor();
    private final QueueExecutor ui = new QueueExecutor();
    private final MusicService service = new MusicService(new Composer(), new AudioRenderer(), background,
            Runnable::run, ui);
    private final Recipe recipe = Recipe.of(Style.PIANO, 3).with(b -> b.lengthSeconds = Recipe.MIN_LENGTH);

    private static final class Collect<T> implements MusicService.Callback<T> {
        final List<T> results = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public void done(T result, Duration time) {
            results.add(result);
        }

        @Override
        public void failed(Throwable error) {
            errors.add(error);
        }
    }

    @Test
    void renderReportsOnTheUserInterfaceThread() {
        Collect<MusicService.Piece> callback = new Collect<>();
        service.render(recipe, false, EnumSet.allOf(Part.class), callback);
        background.runAll();
        assertTrue(callback.results.isEmpty(), "not before the user interface thread runs");
        ui.runAll();
        MusicService.Piece piece = callback.results.getFirst();
        assertEquals(service.compose(recipe, false).notes(), piece.song().notes());
        assertTrue(piece.audio().frames() > 0);
    }

    @Test
    void aCancelledTaskReportsNothing() {
        Collect<MusicService.Piece> before = new Collect<>();
        service.render(recipe, false, EnumSet.allOf(Part.class), before).cancel();
        Collect<MusicService.Piece> after = new Collect<>();
        MusicService.Task late = service.render(recipe, false, EnumSet.allOf(Part.class), after);
        background.runAll();
        late.cancel();
        ui.runAll();
        assertTrue(before.results.isEmpty());
        assertTrue(after.results.isEmpty());
        assertTrue(late.isCancelled());
    }

    @Test
    void remixWithoutAPartIsQuieter() {
        Collect<MusicService.Piece> piece = new Collect<>();
        service.render(recipe, false, EnumSet.allOf(Part.class), piece);
        background.runAll();
        ui.runAll();
        Collect<Audio> remix = new Collect<>();
        service.remix(piece.results.getFirst().stems(), recipe, EnumSet.noneOf(Part.class), remix);
        background.runAll();
        ui.runAll();
        Audio silent = remix.results.getFirst();
        float peak = 0;
        for (float v : silent.left()) peak = Math.max(peak, Math.abs(v));
        assertEquals(0, peak, 1e-6);
    }

    @Test
    void failuresAreReported() {
        MusicService broken = new MusicService(new Composer(), new AudioRenderer(), background, Runnable::run, ui);
        Collect<Audio> callback = new Collect<>();
        broken.remix(null, recipe, EnumSet.allOf(Part.class), callback);
        background.runAll();
        ui.runAll();
        assertEquals(1, callback.errors.size());
        assertTrue(callback.results.isEmpty());
    }

    @Test
    void previewIsOneShortNote() {
        Audio note = service.preview(Voice.of(Instrument.PIANO), 60);
        assertTrue(note.seconds() > 0.2 && note.seconds() < 5, note.seconds() + " s");
        assertFalse(note.frames() == 0);
    }
}
