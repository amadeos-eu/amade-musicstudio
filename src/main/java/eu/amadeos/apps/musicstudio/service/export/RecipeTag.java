package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Recipe;

import java.util.Optional;

/** How the recipe is written into exported files: as a comment that starts with {@value #PREFIX}. */
public final class RecipeTag {
    public static final String PREFIX = "amadeOS Music Studio recipe: ";

    private RecipeTag() {
    }

    public static String of(Recipe recipe) {
        return PREFIX + recipe.toLine();
    }

    /** The recipe in a comment, if the comment holds one. */
    public static Optional<Recipe> parse(String comment) {
        if (comment == null) return Optional.empty();
        int at = comment.indexOf(PREFIX);
        if (at < 0) return Optional.empty();
        try {
            return Optional.of(Recipe.parse(comment.substring(at + PREFIX.length()).strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
