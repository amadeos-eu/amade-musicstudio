package eu.amadeos.apps.musicstudio.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.export.ExportService;
import eu.amadeos.apps.musicstudio.service.export.OggEncoder;
import eu.amadeos.apps.musicstudio.service.export.WavWriter;
import eu.amadeos.apps.musicstudio.service.play.Player;
import eu.amadeos.apps.musicstudio.service.play.SoundPlayer;
import eu.amadeos.apps.musicstudio.service.render.MusicService;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.ui.main.MainView;
import eu.amadeos.apps.musicstudio.ui.main.MainWindow;
import eu.amadeos.commons.app.Ui;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * amade-musicstudio bindings. The background executor, theme, language and the other shared services come
 * from {@link eu.amadeos.commons.app.CommonsModule}.
 */
public final class MusicStudioModule extends AbstractModule {
    /** The texts, {@code messages.properties} and its translations. */
    public static final String MESSAGES = "eu.amadeos.apps.musicstudio.messages";

    @Override
    protected void configure() {
        bind(MusicStudioPreferences.class).in(Scopes.SINGLETON);
        bind(MusicFiles.class).in(Scopes.SINGLETON);
        bind(MainWindow.class).in(Scopes.SINGLETON);
        bind(MainView.class).to(MainWindow.class);
        bind(Player.class).to(SoundPlayer.class).in(Scopes.SINGLETON);
    }

    /** The eight channels of a piece are rendered side by side, one platform thread per core. */
    @Provides
    @Singleton
    @Workers
    ExecutorService workers() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(cores, workerThreads());
    }

    @Provides
    @Singleton
    AudioRenderer renderer() {
        return new AudioRenderer();
    }

    @Provides
    @Singleton
    MusicService musicService(AudioRenderer renderer, ExecutorService background, @Workers ExecutorService workers,
                              @Ui Executor ui) {
        return new MusicService(new Composer(), renderer, background, workers, ui);
    }

    @Provides
    @Singleton
    ExportService exportService(AudioRenderer renderer, @Workers ExecutorService workers) {
        return new ExportService(new Composer(), renderer, workers, new OggEncoder(CommandRunner.system(),
                new WavWriter()));
    }

    static ThreadFactory workerThreads() {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "music-worker-" + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    /** The texts in the language {@code AmadeApplication} chose. */
    @Provides
    @Singleton
    Messages messages(Locale locale) {
        return Messages.load(MusicStudioModule.class, MESSAGES, locale);
    }
}
