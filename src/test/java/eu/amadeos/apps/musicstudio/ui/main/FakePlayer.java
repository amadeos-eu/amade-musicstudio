package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.service.play.Player;
import eu.amadeos.apps.musicstudio.service.synth.Audio;

/** Plays nothing; remembers what it was asked to do. */
final class FakePlayer implements Player {
    Audio audio;
    int position;
    boolean playing;
    boolean loop;
    boolean closed;
    String failure;
    Runnable onEnd = () -> { };
    int plays;

    @Override
    public void play(Audio newAudio, int from) {
        audio = newAudio;
        position = from;
        playing = true;
        plays++;
    }

    @Override
    public void replace(Audio newAudio) {
        audio = newAudio;
    }

    @Override
    public void stop() {
        playing = false;
    }

    @Override
    public void cue(Audio newAudio, int frame) {
        audio = newAudio;
        position = frame;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public int position() {
        return position;
    }

    @Override
    public void setLoop(boolean newLoop) {
        loop = newLoop;
    }

    @Override
    public void setOnEnd(Runnable newOnEnd) {
        onEnd = newOnEnd;
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public void close() {
        closed = true;
        playing = false;
    }

    /** The piece ran out, or the sound output failed. */
    void end() {
        playing = false;
        onEnd.run();
    }
}
