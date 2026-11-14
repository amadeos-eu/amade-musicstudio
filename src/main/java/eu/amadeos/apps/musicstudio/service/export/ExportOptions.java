package eu.amadeos.apps.musicstudio.service.export;

/**
 * What an export writes.
 *
 * @param loop          without intro and outro, the end flowing into the start
 * @param stems         one file per part besides the whole piece (sound formats only)
 * @param includeRecipe the recipe goes into the file, so that Music Studio can open it again
 */
public record ExportOptions(ExportFormat format, boolean loop, boolean stems, boolean includeRecipe) {
    public static final ExportOptions DEFAULT = new ExportOptions(ExportFormat.WAV, false, false, true);
}
