package eu.amadeos.apps.musicstudio.ui.main;

import eu.amadeos.apps.musicstudio.bootstrap.MusicStudioModule;
import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.domain.Kit;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences;
import eu.amadeos.apps.musicstudio.service.compose.Section;
import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.i18n.TranslationCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTest {
    @Test
    void everyLanguageHasEveryText() {
        assertEquals(Map.of(), TranslationCheck.problems(MusicStudioModule.class, MusicStudioModule.MESSAGES));
    }

    @Test
    void everyNameHasAText() {
        List<String> keys = new ArrayList<>();
        for (Style s : Style.values()) {
            keys.add(s.key());
            keys.add(s.subtitleKey());
        }
        for (Instrument i : Instrument.values()) {
            keys.add(i.key());
            keys.add(i.descriptionKey());
        }
        for (Instrument.Group g : Instrument.Group.values()) keys.add(g.key());
        for (Kit k : Kit.values()) keys.add(k.key());
        for (Part p : Part.values()) keys.add(p.key());
        for (Section.Kind k : Section.Kind.values()) keys.add(k.key());
        for (ExportFormat f : ExportFormat.values()) keys.add(f.key());
        for (MainPresenter.Slider s : MainPresenter.Slider.values()) keys.add(s.key());
        for (MusicStudioPreferences.Bar b : MusicStudioPreferences.Bar.values()) keys.add(MainMenuBar.barKey(b));
        for (int seconds : Recipe.LENGTHS) keys.add("length." + seconds);
        List<String> missing = new ArrayList<>();
        for (Language language : Language.values()) {
            Messages messages = Messages.load(MusicStudioModule.class, MusicStudioModule.MESSAGES, language.locale());
            for (String key : keys) {
                if (messages.get(key).startsWith("!")) missing.add(language.code() + " " + key);
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void textsAreFormattedInTheLanguage() {
        Messages german = Messages.load(MusicStudioModule.class, MusicStudioModule.MESSAGES, Locale.GERMANY);
        assertEquals("1,5 s", german.format("time.seconds", 1.5));
        assertEquals("120 BPM", german.format("settings.bpm", 120));
        String rendered = german.format("status.rendered", "Lo-Fi", "123456789", "a-Moll", 82, 24, "1:05", "0,8 s");
        assertTrue(rendered.contains("123456789") && rendered.contains("82 BPM"), rendered);
        Messages french = Messages.load(MusicStudioModule.class, MusicStudioModule.MESSAGES, Locale.FRANCE);
        assertFalse(french.format("error.open", "a.musicrecipe", "b").contains("{"));
    }

    /** Within the menu bar and within each menu, no two entries share a mnemonic letter. */
    @Test
    void mnemonicsAreUniquePerMenu() {
        Map<String, List<String>> menus = Map.of(
                "bar", List.of("menu.file", "menu.edit", "menu.piece", "menu.play", "menu.view", "menu.options",
                        "menu.help"),
                "file", List.of("menu.file.open", "menu.file.save-recipe", "menu.file.export",
                        "menu.file.stop-export", "menu.file.exit"),
                "edit", List.of("menu.edit.back", "menu.edit.forward", "menu.edit.copy-recipe",
                        "menu.edit.paste-recipe"),
                "piece", List.of("menu.piece.new", "menu.piece.random-seed", "menu.piece.variations",
                        "menu.piece.style", "menu.piece.instrument"),
                "play", List.of("menu.play.play", "menu.play.stop", "menu.play.loop"),
                "pause", List.of("menu.play.pause", "menu.play.stop", "menu.play.loop"),
                "view", List.of("menu.view.toolbar", "menu.view.styles", "menu.view.settings",
                        "menu.view.status-bar"));
        Map<String, String> clashes = new HashMap<>();
        for (Language language : Language.values()) {
            Messages messages = Messages.load(MusicStudioModule.class, MusicStudioModule.MESSAGES, language.locale());
            for (Map.Entry<String, List<String>> menu : menus.entrySet()) {
                Map<Character, String> seen = new HashMap<>();
                for (String key : menu.getValue()) {
                    Mnemonics.Text t = Mnemonics.parse(messages.get(key));
                    if (t.index() < 0) {
                        clashes.put(language.code() + " " + key, "no mnemonic");
                        continue;
                    }
                    char letter = Character.toLowerCase(t.text().charAt(t.index()));
                    String other = seen.put(letter, key);
                    if (other != null) clashes.put(language.code() + " " + key, other);
                }
            }
        }
        assertEquals(Map.of(), clashes);
    }
}
