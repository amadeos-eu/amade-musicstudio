package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.apps.musicstudio.service.synth.Audio;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What {@link MainPresenter} needs from the window; tests replace it. */
public interface MainView {
    /** Which commands can run now; menus and toolbar follow. */
    record Commands(boolean rendering, boolean hasPiece, boolean canGoBack, boolean canGoForward, boolean playing,
                    boolean loop, boolean exporting) {
        public static final Commands NONE = new Commands(false, false, false, false, false, false, false);
    }

    /** Where to export and how. */
    record ExportRequest(ExportOptions options, Path target) {
    }

    /** Lets the instrument chooser try sounds. */
    interface InstrumentPreview {
        /** One note of the voice. */
        Audio sound(Voice voice, int pitch);

        void play(Audio sound);
    }

    /** Lets the variations dialog play the variations. */
    interface VariationPlayer {
        /** Renders and plays variation {@code index}; {@code onReady} runs when it plays. */
        void listen(int index, Runnable onReady);

        void stop();
    }

    void showTitle(String title);

    /** Fills the style list, the settings, the lane heads and the menus with the recipe. */
    void showRecipe(Recipe recipe, Set<Part> kept);

    /** The small sketch of each style in the list. */
    void showStyleSketches(Map<Style, Song> sketches);

    /**
     * Shows the notes of the piece in the lanes.
     *
     * @param seconds the length of the time line, the hall's tail included
     */
    void showSong(Song song, double seconds);

    /** The waveform below the lanes, {@code null} while there is no sound yet. */
    void showWaveform(Audio audio);

    /** The play head and the time, in seconds. */
    void showPosition(double seconds);

    /** Which lanes are muted and which play alone. */
    void showMix(Set<Part> muted, Set<Part> solo);

    void showStatus(String text);

    /** The progress bar runs while something is rendered or saved. */
    void showBusy(boolean busy);

    void updateCommands(Commands commands);

    /** Asks for a recipe or a file from Music Studio to open; empty if cancelled. */
    Optional<Path> chooseOpenPath();

    /** Asks where to save the recipe; replacing a file is confirmed. Empty if cancelled. */
    Optional<Path> chooseRecipeSavePath(String suggestedName);

    /** Asks for the format and the file of an export; empty if cancelled. */
    Optional<ExportRequest> chooseExport(ExportOptions suggested, String suggestedName, double seconds);

    /** The instrument chooser for a part; empty if cancelled. */
    Optional<Voice> chooseInstrument(Part part, Voice current, InstrumentPreview preview);

    /**
     * The variations to listen to; the index of the one chosen, empty if cancelled.
     *
     * @param labels what each variation changes
     */
    Optional<Integer> chooseVariation(List<String> labels, VariationPlayer player);

    void copyToClipboard(String text);

    /** The text on the clipboard, if there is any. */
    Optional<String> clipboardText();

    /** Shows or hides a part of the window. */
    void showBar(Bar bar, boolean visible);

    /** A yes/no question; true for yes. */
    boolean confirm(String message, String title);

    void showError(String message);

    void showInfo(String message);

    void showAbout();

    void dispose();
}
