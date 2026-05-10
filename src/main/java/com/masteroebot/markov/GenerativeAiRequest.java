package com.masteroebot.markov;

import java.util.List;

public record GenerativeAiRequest(
        String systemPrompt,
        String question,
        List<String> recentMessages
) {
}
