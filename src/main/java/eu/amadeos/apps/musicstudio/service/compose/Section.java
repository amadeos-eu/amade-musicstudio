package eu.amadeos.apps.musicstudio.service.compose;

/** A part of the form: intro, A, B or outro, from a bar on for some bars. */
public record Section(Kind kind, int startBar, int bars) {
    public enum Kind {
        INTRO, A, B, OUTRO;

        public String key() {
            return "section." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public int endBar() {
        return startBar + bars;
    }

    public boolean contains(int bar) {
        return bar >= startBar && bar < endBar();
    }
}
