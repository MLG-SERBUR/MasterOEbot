package com.masteroebot.markov;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class PromptTokenizer {
    private static final String TOKENIZER_RESOURCE = "/tokenizers/arliai-tokenizer.json";
    private static final double TOKEN_SAFETY_MARGIN = 1.0; // calibration manager handles margin
    private static final HuggingFaceTokenizer TOKENIZER = load();

    private PromptTokenizer() {
    }

    /**
     * Estimates prompt tokens using heuristic + self-calibration.
     * Keeps vendored tokenizer as floor but primary is aggressive heuristic (4 chars/token prose,
     * 3.5 code, 1.5 CJK, URL correction) wrapped by EMA calibration factor.
     */
    static long estimateTokens(String text) {
        return estimateTokens(text, (String) null);
    }

    /** Per-model estimate using that model's tokenizer-family calibration factor. */
    static long estimateTokens(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int heuristic = TokenCalibrationManager.getInstance().estimateTokens(text, model);
        try {
            int tokenCount = TOKENIZER.encode(text, false, false).getIds().length;
            long tokenizerEst = (long) Math.ceil(tokenCount * TOKEN_SAFETY_MARGIN);
            return Math.max(heuristic, tokenizerEst);
        } catch (Exception e) {
            return heuristic;
        }
    }

    /**
     * Conservative estimate across candidate models (max calibrated estimate),
     * so gathered history fits the most restrictive tokenizer family.
     */
    static long estimateTokensConservative(String text, java.util.Collection<String> models) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long best = estimateTokens(text);
        if (models != null) {
            for (String m : models) {
                best = Math.max(best, estimateTokens(text, m));
            }
        }
        return best;
    }

    static String formatTokenCount(long est) {
        return est >= 1000 ? String.format("%.1fk", est / 1000.0) : String.valueOf(est);
    }

    static String formatDualTokenEstimates(String text) {
        long gptEst = estimateTokens(text, TokenCalibrationManager.FAMILY_GPT);
        long qwenEst = estimateTokens(text, TokenCalibrationManager.FAMILY_QWEN);
        return "~" + formatTokenCount(gptEst) + " gpt / ~" + formatTokenCount(qwenEst) + " qwen tokens";
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
