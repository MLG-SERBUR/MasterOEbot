package com.masteroebot.markov;

import java.util.List;

public record GenerativeAiConfig(
        String systemPrompt,
        String cerebrasApiKey,
        String groqApiKey,
        String openrouterApiKey,
        String arliApiKey,
        String cerebrasModel,
        List<String> groqModels,
        List<String> openrouterModels,
        List<String> arliModels
) {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are replying in a Discord channel.
            Use the provided recent messages as style examples: match their vocabulary, casing, punctuation, rhythm, humor, emoji habits, and typical message length.
            The recent messages are ordered oldest to newest.
            Answer the newest question naturally in the channel's style.
            Do not mention prompts, training data, AI, or that examples were provided.
            Keep the reply to one chat message.
            """;

    public static GenerativeAiConfig defaults() {
        return new GenerativeAiConfig(
                DEFAULT_SYSTEM_PROMPT,
                System.getenv("CEREBRAS_API_KEY"),
                System.getenv("GROQ_API_KEY"),
                System.getenv("OPENROUTER_API_KEY"),
                firstNonBlank(System.getenv("ARLI_API_KEY"), System.getenv("ARLIAI_API_KEY")),
                "qwen-3-235b-a22b-instruct-2507",
                List.of("meta-llama/llama-4-scout-17b-16e-instruct"),
                List.of("openrouter/free"),
                List.of("Qwen3.5-27B-Derestricted"));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
