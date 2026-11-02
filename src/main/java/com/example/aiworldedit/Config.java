package com.example.aiworldedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings stored in .minecraft/config/aiworldedit.json.
 * The file is created with defaults on first launch. Edit it, then run /aireload.
 */
public final class Config {

    // ---- Provider ----
    /** "gemini" (cloud), "claude" (cloud) or "ollama" (local). Switch in game with /aiprovider. */
    public String provider = "gemini";

    // ---- Claude (cloud, Anthropic API) ----
    /** Claude API key from platform.claude.com. If blank, the ANTHROPIC_API_KEY env var is used. */
    public String claudeApiKey = "";
    /** e.g. claude-sonnet-5-5 (balanced), claude-opus-5-5 (best), claude-haiku-5-5 (cheapest). */
    public String claudeModel = "claude-sonnet-5-5";
    public int claudeTimeoutSeconds = 600;

    // ---- Ollama (local) ----
    public String ollamaUrl = "http://localhost:11434";
    /** Any model you've pulled. Use a vision model (e.g. gemma3:12b) if you want /aifix to work. */
    public String ollamaModel = "qwen2.5-coder:14b";
    /** Context window. The prompt plus terrain scan and last build need well over Ollama's default. */
    public int ollamaContextLength = 16384;
    public double ollamaTemperature = 0.4;
    /** Local models can be slow on big builds. */
    public int ollamaTimeoutSeconds = 600;

    // ---- Gemini (cloud) ----
    /** Gemini API key. If blank, GEMINI_API_KEY env var / -Dgemini.api.key are used. */
    public String apiKey = "";
    public String model = "gemini-3.6-flash";
    public int maxOutputTokens = 32768;

    // ---- Execution ----
    /** Ticks between queued commands. Raise to 10+ on servers where you are not op (spam kick). */
    public int ticksBetweenCommands = 2;
    /** Send //pos1 and //pos2 in the same tick as the edit after them (false = one command per step, the old pace). */
    public boolean fastSelections = true;
    /** Send //placement pos1 before a build so shapes are centered on //pos1 (WorldEdit 7.3+). */
    public boolean usePlacementPos1 = true;
    /** Show a preview outline and wait for /aiconfirm before building. */
    public boolean confirmBeforeBuild = true;
    /** Anchor builds on the block you are looking at instead of your feet. */
    public boolean anchorAtCrosshair = true;
    public int crosshairRange = 64;

    // ---- Safety limits ----
    /** Total WorldEdit commands per build (a composed medieval house is ~1500-3000; villages need more). */
    public int maxCommands = 20000;
    /** Max components the AI may use in one plan. */
    public int maxComponents = 40;
    public int maxCommandLength = 500;
    public long maxSelectionVolume = 250_000;
    public int maxShapeRadius = 48;
    public int maxShapeHeight = 128;
    /** Max distance (per axis) any coordinate may be from the anchor. */
    public int maxBuildDistance = 96;

    // ---- Reference images ----
    /** Search free image libraries for style references before designing a build. */
    public boolean referenceSearch = true;
    /** Comma-separated: wikimedia, openverse. */
    public String referenceSources = "wikimedia,openverse";
    /** How many candidate images to fetch and show the AI. */
    public int referenceCandidates = 6;
    /** How many references the AI may keep and use for the build. */
    public int maxReferenceImages = 3;
    /** Optional contact (e.g. your email) added to the User-Agent, as Wikimedia asks of API users. */
    public String referenceContact = "";
    /** Ollama only: can the model read images? "auto" guesses from the model name, or "true"/"false". */
    public String ollamaSupportsImages = "auto";

    // ---- Visual self-review ----
    /** Before building, simulate the plan, draw it as text views and let the AI fix its own mistakes. */
    public boolean previewReview = true;
    /** After a build finishes, scan it and let the AI review it automatically (like /aifix). */
    public boolean autoReviewAfterBuild = false;
    /** Largest area (blocks) that will be simulated/scanned for review. */
    public long maxReviewVolume = 1_500_000;

    // ---- Saved builds (config/aiworldedit/library) ----
    /**
     * What saved builds are for: "learn" (default) = the AI studies them and their materials become
     * palettes, but they are never pasted; "place" = the AI may also paste them whole; "both"; "off".
     */
    public String libraryMode = "learn";
    /**
     * Folder of saved builds. Empty = config/aiworldedit/library. Can point anywhere, e.g.
     * "~/Desktop/mcai_ref". Subfolders become tags: medieval/house/x.litematic is a "medieval house".
     * Folders starting with "_" are ignored.
     */
    public String libraryFolder = "";
    /** How many saved builds the AI studies per /aibuild (Ollama uses at most 1). */
    public int maxStudyExamples = 2;
    /** Also show the AI a rendered picture of each studied build (models that can see images). */
    public boolean studyImages = true;
    /** How many saved builds are offered for pasting when libraryMode allows it. */
    public int maxLibraryInPrompt = 12;

    public boolean libraryLearn() {
        String m = libraryMode == null ? "learn" : libraryMode.trim().toLowerCase(java.util.Locale.ROOT);
        return m.equals("learn") || m.equals("both");
    }

    public boolean libraryPlace() {
        String m = libraryMode == null ? "learn" : libraryMode.trim().toLowerCase(java.util.Locale.ROOT);
        return m.equals("place") || m.equals("both");
    }

    // ---- World scan ----
    public int scanRadius = 12;
    public int scanStep = 3;

    // ---- Memory ----
    public int maxHistoryTurns = 10;
    /** Max commands from the last build included as context for /aichat and /aifix. */
    public int maxRememberedCommands = 300;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Config INSTANCE = new Config();

    public static Config get() {
        return INSTANCE;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiworldedit.json");
    }

    /** Loads the config (creating it with defaults if missing). Returns an error message or null. */
    public static String load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Config.class);
                INSTANCE = c == null ? new Config() : c;
                if (INSTANCE.maxCommands == 3000 || INSTANCE.maxCommands == 6000) INSTANCE.maxCommands = 20000; // old defaults: too low
            } else {
                INSTANCE = new Config();
            }
            // Re-save so new fields from updates appear in the file.
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            INSTANCE = new Config();
            return "Could not read " + p.getFileName() + " (" + e.getMessage() + "). Using defaults.";
        }
    }

    public boolean isClaude() {
        return "claude".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    public String resolveClaudeKey() {
        if (claudeApiKey != null && !claudeApiKey.isBlank()) return claudeApiKey.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? null : env.trim();
    }

    public boolean isOllama() {
        return "ollama".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    public String activeModel() {
        if (isOllama()) return "Ollama " + ollamaModel;
        if (isClaude()) return "Claude " + claudeModel;
        return "Gemini " + model;
    }

    /**
     * Changes settings without losing edits made to the file while the game is running:
     * re-reads the file first, applies the change, then saves. Returns an error message or null.
     */
    public static String update(java.util.function.Consumer<Config> change) {
        String err = load();
        change.accept(INSTANCE);
        String saveErr = save();
        return err != null ? err : saveErr;
    }

    /** Writes the current settings back to the file. Returns an error message or null. */
    public static String save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            return "Could not save config: " + e.getMessage();
        }
    }

    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("gemini.api.key");
        return prop == null ? null : prop.trim();
    }
}
