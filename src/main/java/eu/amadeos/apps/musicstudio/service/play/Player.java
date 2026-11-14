package eu.amadeos.apps.musicstudio.service.play;

import eu.amadeos.apps.musicstudio.service.synth.Audio;

/** Plays sound; tests replace it with a silent one. All methods may be called from any thread. */
public interface Player {
    /** Plays from frame {@code from}; what played before stops. */
    void play(Audio audio, int from);

    /** Changes the sound while it plays (a new mix or a changed piece), at the same position where possible. */
    void replace(Audio audio);

    void stop();

    /** Moves to frame {@code frame} of the sound; plays on from there if it is playing. */
    void cue(Audio audio, int frame);

    boolean isPlaying();

    /** The frame heard now; where it stopped when it is not playing. */
    int position();

    /** Whether playback starts again at the end. */
    void setLoop(boolean loop);

    /** Called once when playback reaches its end (not when stopped), on the player's own thread. */
    void setOnEnd(Runnable onEnd);

    /** Why the sound output could not be opened at the last try, or {@code null}. */
    default String failure() {
        return null;
    }

    /** Stops for good and frees the sound device. */
    void close();
}
