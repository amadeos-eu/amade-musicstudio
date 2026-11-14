package eu.amadeos.apps.musicstudio.bootstrap;

import eu.amadeos.apps.musicstudio.ui.main.MainPresenter;
import eu.amadeos.apps.musicstudio.ui.main.MainWindow;
import eu.amadeos.commons.app.AmadeApplication;
import eu.amadeos.commons.app.AppInfo;

import java.util.List;

public final class AmadeMusicStudioApplication {
    public static final AppInfo APP = new AppInfo("amade-musicstudio", "Music Studio", "music-studio");

    private AmadeMusicStudioApplication() {
    }

    /** The version from the jar's manifest; "dev" when run from the IDE. */
    public static String version() {
        String version = AmadeMusicStudioApplication.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** Opens the recipe or exported file named as first argument, if any. */
    public static void main(String[] args) {
        AmadeApplication.launch(APP, injector -> {
            MainWindow window = injector.getInstance(MainWindow.class);
            MainPresenter presenter = injector.getInstance(MainPresenter.class);
            window.bind(presenter);
            window.setVisible(true);
            presenter.start(List.of(args));
        }, new MusicStudioModule());
    }
}
