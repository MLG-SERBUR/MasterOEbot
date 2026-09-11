package com.masteroebot.markov;

final class PromptTokenizer {
    private PromptTokenizer() {
    }

    /**
     * Estimates prompt tokens using heuristic + self-calibration
     * (aggressive heuristic: 4 chars/token prose, 3.5 code, 1.5 CJK,
     * URL correction, wrapped by EMA calibration factor).
     */
    static long estimateTokens(String text) {
        return estimateTokens(text, (String) null);
    }

    /** Per-model estimate using that model's tokenizer-family calibration factor. */
    static long estimateTokens(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return TokenCalibrationManager.getInstance().estimateTokens(text, model);
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
}
