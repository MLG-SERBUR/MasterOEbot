package com.masteroebot.markov;

import java.util.List;

public record GenerativeAiRequest(
        List<String> recentMessages,
        String systemPromptOverride
) {
    public GenerativeAiRequest(List<String> recentMessages) {
        this(recentMessages, null);
    }
}
