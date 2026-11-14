package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.domain.Voice;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.Audio;

import eu.amadeos.apps.musicstudio.service.export.ExportOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Remembers what the presenter showed and answers its questions with what the test prepared. */
final class FakeView implements MainView {
    String title;
    Recipe recipe;
    Set<Part> kept;
    Map<Style, Song> sketches;
    Song song;
    double songSeconds;
    Audio waveform;
    double position;
    Set<Part> muted = Set.of();
    Set<Part> solo = Set.of();
    String status;
    final List<String> errors = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    boolean busy;
    Commands commands = Commands.NONE;
    final Map<Bar, Boolean> bars = new EnumMap<>(Bar.class);
    String clipboard;
    boolean disposed;
    boolean aboutShown;

    Optional<Path> openPath = Optional.empty();
    Optional<Path> savePath = Optional.empty();
    String suggestedName;
    Optional<ExportRequest> exportRequest = Optional.empty();
    ExportOptions suggestedExport;
    Function<InstrumentPreview, Optional<Voice>> instrumentAnswer = p -> Optional.empty();
    Function<VariationPlayer, Optional<Integer>> variationAnswer = p -> Optional.empty();
    List<String> variationLabels;
    boolean confirmAnswer;
    int confirmations;

    @Override
    public void showTitle(String newTitle) {
        title = newTitle;
    }

    @Override
    public void showRecipe(Recipe newRecipe, Set<Part> newKept) {
        recipe = newRecipe;
        kept = newKept;
    }

    @Override
    public void showStyleSketches(Map<Style, Song> newSketches) {
        sketches = newSketches;
    }

    @Override
    public void showSong(Song newSong, double seconds) {
        song = newSong;
        songSeconds = seconds;
    }

    @Override
    public void showWaveform(Audio audio) {
        waveform = audio;
    }

    @Override
    public void showPosition(double seconds) {
        position = seconds;
    }

    @Override
    public void showMix(Set<Part> newMuted, Set<Part> newSolo) {
        muted = newMuted;
        solo = newSolo;
    }

    @Override
    public void showStatus(String text) {
        status = text;
    }

    @Override
    public void showBusy(boolean newBusy) {
        busy = newBusy;
    }

    @Override
    public void updateCommands(Commands newCommands) {
        commands = newCommands;
    }

    @Override
    public Optional<Path> chooseOpenPath() {
        return openPath;
    }

    @Override
    public Optional<Path> chooseRecipeSavePath(String name) {
        suggestedName = name;
        return savePath;
    }

    @Override
    public Optional<ExportRequest> chooseExport(ExportOptions suggested, String name, double seconds) {
        suggestedExport = suggested;
        suggestedName = name;
        return exportRequest;
    }

    @Override
    public Optional<Voice> chooseInstrument(Part part, Voice current, InstrumentPreview preview) {
        return instrumentAnswer.apply(preview);
    }

    @Override
    public Optional<Integer> chooseVariation(List<String> labels, VariationPlayer player) {
        variationLabels = labels;
        return variationAnswer.apply(player);
    }

    @Override
    public void copyToClipboard(String text) {
        clipboard = text;
    }

    @Override
    public Optional<String> clipboardText() {
        return Optional.ofNullable(clipboard);
    }

    @Override
    public void showBar(Bar bar, boolean visible) {
        bars.put(bar, visible);
    }

    @Override
    public boolean confirm(String message, String dialogTitle) {
        confirmations++;
        return confirmAnswer;
    }

    @Override
    public void showError(String message) {
        errors.add(message);
    }

    @Override
    public void showInfo(String message) {
        infos.add(message);
    }

    @Override
    public void showAbout() {
        aboutShown = true;
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
