package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Lightweight user preferences persisted to
 * {@code ~/.runelite/leagues-ai/preferences.json}.
 *
 * <p>Stored fields:
 * <ul>
 *   <li>{@code model} — LLM model name used for both the Codex OAuth and API-key paths</li>
 *   <li>{@code selectedPersonas} — list of persona IDs active in the coaching doctrine</li>
 * </ul>
 *
 * <p>Defaults: model = "gpt-5.4", all personas selected.
 */
public class UserPreferences {

    /** All known persona IDs — keep in sync with {@link com.leaguesai.agent.PromptBuilder}. */
    public static final List<String> ALL_PERSONA_IDS = Arrays.asList(
            "points_chaser",
            "build_architect",
            "explorer",
            "sprinter",
            "theorist",
            "settled",
            "sirpugger",
            "pragmatist",
            "skill_spec"
    );

    public static final String DEFAULT_MODEL = "gpt-5.4";

    private String model = DEFAULT_MODEL;
    private List<String> selectedPersonas = ALL_PERSONA_IDS;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getModel() {
        return model != null && !model.isEmpty() ? model : DEFAULT_MODEL;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getSelectedPersonas() {
        return selectedPersonas != null ? selectedPersonas : ALL_PERSONA_IDS;
    }

    public void setSelectedPersonas(List<String> selectedPersonas) {
        this.selectedPersonas = selectedPersonas;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Loads preferences from {@code file}. Returns defaults if the file does
     * not exist or is malformed.
     */
    public static UserPreferences load(File file) {
        if (file == null || !file.exists()) {
            return new UserPreferences();
        }
        try (FileReader reader = new FileReader(file)) {
            UserPreferences prefs = GSON.fromJson(reader, UserPreferences.class);
            return prefs != null ? prefs : new UserPreferences();
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            return new UserPreferences();
        }
    }

    /**
     * Saves preferences to {@code file}. Silently swallows IO errors — a
     * failed save means the next session loads defaults, which is acceptable.
     */
    public void save(File file) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            // Non-fatal
        }
    }
}
