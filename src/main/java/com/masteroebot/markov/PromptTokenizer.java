package com.masteroebot.markov;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class PromptTokenizer {
    private static final String TOKENIZER_RESOURCE = "/tokenizers/arliai-tokenizer.json";
    private static final double TOKEN_SAFETY_MARGIN = 1.50;
    private static final HuggingFaceTokenizer TOKENIZER = load();

    private PromptTokenizer() {
    }

    static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokenCount = TOKENIZER.encode(text, false, false).getIds().length;
        return (long) Math.ceil(tokenCount * TOKEN_SAFETY_MARGIN);
    }

    private static HuggingFaceTokenizer load() {
        try (var stream = PromptTokenizer.class.getResourceAsStream(TOKENIZER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing tokenizer resource: " + TOKENIZER_RESOURCE);
            }
            Map<String, String> options = new HashMap<>();
            options.put("addSpecialTokens", "false");
            return HuggingFaceTokenizer.newInstance(stream, options);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
