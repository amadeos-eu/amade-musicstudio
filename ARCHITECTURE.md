# amade-musicstudio – Architecture

Music generator of amadeOS. It uses Java 25, Swing, Guice and [amade-commons](https://github.com/amadeos-eu/amade-commons), is built with Gradle and licensed under the EUPL-1.2. The structure follows amade-artstudio.

Packaging:
- a Debian package
- an MSI and a portable app image for Windows, built with jpackage

Languages: English, German, French, Spanish and Italian. The language follows the amadeOS desktop.

## Key decisions

- **A piece is its recipe.** `Recipe` holds the style, a main seed and one seed per part, key, tempo, length, the beat and sound sliders, the voice of each tonal part (`Voice`: instrument, octave, attack, release, volume), the drum kit and its volume. Composer and synthesizer are pure functions of the recipe, so the history, the variations, files and the clipboard only keep recipes. A recipe is a few lines of text (`toText`, `.musicrecipe`) or one line (`toLine`, clipboard).
- **The recipe travels with every export.** It is the `ICMT` comment of a WAV file, a Vorbis comment in FLAC (and in OGG, carried over by GStreamer), a text event in MIDI and the names of the unused samples of a module. `MusicFiles.readRecipe` finds it again in all of them, so any exported piece can be opened and changed.
- **Rules, not recordings.** `Composer` writes the notes: a form of intro, A and B sections and outro; chords as a chain of likely steps in the key; a one-bar melody motif that is varied, repeated and closed on the key note; accompaniment, bass and drum patterns of the style. `Synth` calculates every sound sample by sample (additive, FM, filtered band-limited waves, plucked strings, noise for drums). There are no sample files and no network.
- **Chance that repeats.** Each part draws from `java.util.Random` seeded with its own seed. Java fixes that sequence for every version, so a recipe gives the same notes everywhere. A new piece keeps the seeds of the parts marked *Keep*; that is why the melody can stay while the drums change.
- **Never reorder styles or instruments.** Recipes store ids and pieces depend on the order of the random numbers. New styles and instruments go at the end.
- **Stems, then the mix.** `AudioRenderer` renders the eight channels of a song in parallel on the worker pool, filters them (drums by hardness, melody and chords by brightness), and keeps the four parts as `Stems`. `mix` then adds them at their volumes with hall and crackle. Volume, reverb, crackle, mute and solo only mix again (`MainPresenter.sameParts`), which takes a fraction of a second.
- **Playback through Java Sound.** `SoundPlayer` feeds a `SourceDataLine` in chunks of 2048 frames from one daemon thread, about a tenth of a second ahead, so stop, seek and a new mix are heard at once. A new mix replaces the sound while playing at the same position. If no sound output can be opened, the status bar says so; everything else still works.
- **Lossless formats written in Java.** WAV and FLAC (`FlacWriter`: fixed predictors, Rice coding, mid/side) need no outside program. Only OGG uses `gst-launch-1.0`, which the Debian package recommends; a missing program is reported with the package names.
- **Modules from the synthesizer.** `ModWriter` renders each drum and one note per tonal part into 8-bit samples, loops sustained instruments with a crossfade, and writes one row per sixteenth with tempo, loudness, swing delay and panning effects. Identical patterns are shared.
- **Testable without a display.** The presenter, the recipe, the composer and the synthesizer have no Swing code. Executors and the player are injected, so tests run rendering and continuations in a deterministic queue (`QueueExecutor`) with a player that plays nothing.

## Packages

| Package | Contents |
|---|---|
| `bootstrap` | `AmadeMusicStudioApplication` (start through `AmadeApplication`), `MusicStudioModule` (Guice; worker pool, renderer, `MusicService`, `ExportService`, player, texts) |
| `domain` | `Style` (the eight styles and their rules), `Part` (melody, chords, bass, drums), `Instrument` (sixteen instruments in groups, with their General MIDI program), `Kit`, `Voice`, `Recipe` (text form and parsing), `Variations` (eight relatives of a recipe) |
| `service.compose` | `Composer`, `Song` (notes on eight channels, sections, chords), `Note`, `Section`, `Drum` |
| `service.synth` | `Synth` (instruments and drums), `Tone` (attack and release factors), `Dsp` (filters, noise), `Reverb`, `AudioRenderer` (channels in parallel, stems, mix, previews and module samples), `Stems`, `Audio` |
| `service.render` | `MusicService` (compose, render and remix in the background, results on the EDT, cancelled tasks never report) |
| `service.play` | `Player`, `SoundPlayer` |
| `service.export` | `ExportService` (one file or one per part, written to a temporary file and moved into place), `WavWriter`, `FlacWriter`, `OggEncoder`, `ModWriter`, `MidiWriter`, `RecipeTag`, `ExportFormat`, `ExportOptions` |
| `repository` | `MusicFiles` (`.musicrecipe`, the recipe inside exported files, atomic writes), `MusicStudioPreferences` (`~/.config/amadeos/music-studio/music-studio.properties`: bars, kept parts, loop, export choices, last recipe) |
| `ui.main` | `MainPresenter` (all commands and the history, no Swing), `MainView`, `MainWindow`, `MainMenuBar`, `MainToolBar`, `StyleList`, `SettingsPanel`, `TrackPanel` (lane heads and lanes), `InstrumentDialog`, `ExportDialog`, `VariationsDialog`, `MusicActions` (one action per command for menus and toolbar) |
| `ui.view` | `LaneView` (piano roll of one part), `SectionBar` (the form), `WaveformView`, `SongSketch` (small picture of a style), `PartColors` |
| `ui.icons` | `MusicGlyph` (the toolbar glyphs only Music Studio needs; `ToolIcon` and `StandardGlyph` come from amade-commons) |

## Flows

- **Start**
  1. The window opens; the styles get their small pictures, composed from seed 7.
  2. The presenter takes the file named on the command line, else the last recipe, else a new *Lo-Fi* piece with a random seed.
- **Change**
  1. Every change of the recipe adds an entry to the history (50 entries; the entries after the current one are dropped). Sliders take effect when they are let go, so dragging does not fill the history.
  2. The recipe is shown in the panels and stored as the last recipe.
  3. The notes are composed at once (milliseconds) and shown in the lanes; the sound is rendered in the background. If only the mix changed, the stems are mixed again instead.
- **Playing**
  1. Play while rendering waits for the sound and then starts.
  2. A 40 ms timer moves the play head in the lanes, on the waveform and in the time display.
  3. A new sound while playing replaces the old one at the same position.
- **Variations**: the dialog renders a relative when its listen button is pressed and plays it; *Use* takes it into the history.
- **Export**
  1. Format and options dialog, then the file chooser in the music folder of the desktop (`XDG_MUSIC_DIR`).
  2. Composing and rendering in the background, then an atomic write. Escape or *Stop Export* cancels; nothing half-written stays behind. A running export is confirmed before quitting.
- **Theme and language**
  - Colours come from `AmadeUI` and follow Settings; the part colours are fixed for light and dark.
  - Texts are in `messages*.properties`, checked by `TranslationCheck`, including a test against clashing mnemonics.

## Dependencies

- Guice, amade-commons, SLF4J/Logback.
- Everything else comes from the JDK: Java Sound, `javax.sound.midi`, Java2D, Swing.
- Optional at run time: `gst-launch-1.0` for OGG.
