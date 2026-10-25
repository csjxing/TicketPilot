package cn.local.tickethelper.ai;

/** The exact locally reviewed request. Rebuilding context never mutates this snapshot. */
public final class AiRequestReview {
    public final AiSettings settings;
    public final String systemPrompt, userPrompt;
    public final long generation;
    public final boolean test;

    public AiRequestReview(AiSettings settings, String systemPrompt, String userPrompt,
                           long generation, boolean test) {
        if (settings == null || systemPrompt == null || userPrompt == null)
            throw new IllegalArgumentException("A reviewed request must contain its settings and complete text");
        this.settings = settings;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.generation = generation;
        this.test = test;
    }

    public boolean matches(AiSettings currentSettings, long currentGeneration, boolean forTest) {
        return generation == currentGeneration && test == forTest
                && settings.matchesRequestSettings(currentSettings);
    }
}
