package com.masteroebot.markov;

import java.util.List;

public record GenerativeAiConfig(
        String systemPrompt,
        String cerebrasApiKey,
        String groqApiKey,
        String openrouterApiKey,
        String geminiApiKey,
        String mistralApiKey,
        String zaiApiKey,
        String cloudflareApiKey,
        String cloudflareAccountId,
        String ollamaApiKey,
        String sambaNovaApiKey,
        List<String> cerebrasModels,
        List<String> groqModels,
        List<String> openrouterModels,
        List<String> geminiModels,
        List<String> mistralModels,
        List<String> zaiModels,
        List<String> cloudflareModels,
        List<String> ollamaModels,
        List<String> sambaNovaModels
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
                System.getenv("GEMINI_API_KEY"),
                System.getenv("MISTRAL_API_KEY"),
                System.getenv("ZAI_API_KEY"),
                System.getenv("CLOUDFLARE_API_KEY"),
                System.getenv("CLOUDFLARE_ACCOUNT_ID"),
                System.getenv("OLLAMA_API_KEY"),
                System.getenv("SAMBANOVA_API_KEY"),
                List.of("qwen-3-235b-a22b-instruct-2507"),
                List.of("meta-llama/llama-4-scout-17b-16e-instruct"),
                List.of("openrouter/free"),
                List.of("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash",
                        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemma-4-31b-it"),
                List.of("mistral-large-latest", "mistral-medium-latest", "magistral-small-latest",
                        "mistral-small-latest", "codestral-latest", "open-mistral-nemo", "ministral-8b-latest"),
                List.of("glm-4.7-flash", "glm-4.5-flash"),
                List.of("@cf/openai/gpt-oss-120b"),
                List.of("minimax-m3", "nemotron-3-ultra", "gpt-oss:120b", "gemma4:31b"),
                List.of("DeepSeek-V3.1", "gpt-oss-120b", "Meta-Llama-3.3-70B-Instruct"));
    }
}
