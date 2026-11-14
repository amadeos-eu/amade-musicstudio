package eu.amadeos.apps.musicstudio.service.render;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.service.synth.Stems;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composes and renders pieces in the background and hands them to the user interface thread. A task that was
 * cancelled reports nothing, so a piece that is no longer wanted never replaces a newer one.
 */
public final class MusicService {
    private final Composer composer;
    private final AudioRenderer renderer;
    private final Executor background;
    private final Executor workers;
    private final Executor ui;

    /**
     * @param background runs the tasks
     * @param workers    renders the channels of one piece side by side; not the same pool as {@code background}
     */
    public MusicService(Composer composer, AudioRenderer renderer, Executor background, Executor workers, Executor ui) {
        this.composer = composer;
        this.renderer = renderer;
        this.background = background;
        this.workers = workers;
        this.ui = ui;
    }

    /** A composed and rendered piece. */
    public record Piece(Song song, Stems stems, Audio audio) {
    }

    /** What a task reports, on the user interface thread. */
    public interface Callback<T> {
        void done(T result, Duration time);

        void failed(Throwable error);
    }

    /** A running task. */
    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** Nothing more is reported; the work stops at its next check. */
        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** The notes of a piece without its sound, at once; composing takes a few milliseconds. */
    public Song compose(Recipe recipe, boolean loop) {
        return composer.compose(recipe, loop);
    }

    /** Composes, renders and mixes the piece with the parts that can be heard. */
    public Task render(Recipe recipe, boolean loop, Set<Part> audible, Callback<Piece> callback) {
        return run(task -> {
            Song song = composer.compose(recipe, loop);
            Stems stems = renderer.render(song, workers, task::isCancelled);
            return new Piece(song, stems, renderer.mix(stems, recipe, audible));
        }, callback);
    }

    /** Mixes rendered parts anew: other volumes or other parts muted. */
    public Task remix(Stems stems, Recipe recipe, Set<Part> audible, Callback<Audio> callback) {
        return run(task -> renderer.mix(stems, recipe, audible), callback);
    }

    /** One note of a voice, for trying an instrument; quick enough for the user interface thread. */
    public Audio preview(Voice voice, int pitch) {
        return renderer.preview(voice, pitch);
    }

    private interface Work<T> {
        T run(Task task);
    }

    private <T> Task run(Work<T> work, Callback<T> callback) {
        Task task = new Task();
        background.execute(() -> {
            if (task.isCancelled()) return;
            long start = System.nanoTime();
            try {
                T result = work.run(task);
                Duration time = Duration.ofNanos(System.nanoTime() - start);
                ui.execute(() -> {
                    if (!task.isCancelled()) callback.done(result, time);
                });
            } catch (CancellationException e) {
                // nobody waits for it any more
            } catch (RuntimeException | OutOfMemoryError e) {
                ui.execute(() -> {
                    if (!task.isCancelled()) callback.failed(e);
                });
            }
        });
        return task;
    }
}
