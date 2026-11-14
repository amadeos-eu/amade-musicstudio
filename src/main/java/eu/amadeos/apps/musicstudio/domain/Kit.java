package eu.amadeos.apps.musicstudio.domain;

import java.util.Locale;
import java.util.Optional;

/** How the drums sound; {@link #NONE} leaves them out. Never reorder: recipes store the id. */
public enum Kit {
    NONE, SOFT, ELECTRONIC, LOFI, CHIP;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "kit." + id();
    }

    public static Optional<Kit> ofId(String id) {
        for (Kit k : values()) if (k.id().equals(id)) return Optional.of(k);
        return Optional.empty();
    }
}
