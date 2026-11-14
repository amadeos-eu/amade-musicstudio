package eu.amadeos.apps.musicstudio.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicStudioModuleTest {
    @Test
    void appInfo() {
        assertEquals("music-studio", AmadeMusicStudioApplication.APP.configName());
        assertEquals("amade-musicstudio", AmadeMusicStudioApplication.APP.id());
        assertEquals("dev", AmadeMusicStudioApplication.version());
    }

    @Test
    void workersDoNotKeepTheProgramAlive() {
        Thread worker = MusicStudioModule.workerThreads().newThread(() -> { });
        assertTrue(worker.isDaemon());
        assertTrue(worker.getName().startsWith("music-worker-"));
        assertTrue(worker.getPriority() < Thread.NORM_PRIORITY);
    }
}
