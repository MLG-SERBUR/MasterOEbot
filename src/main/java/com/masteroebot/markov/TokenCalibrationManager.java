package com.masteroebot.markov;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic estimator + self-calibrating factor persisted to disk.
 * Ported from matrix-robobot 744ad4e + a86be49 (8k Groq primary).
 * Aggressive initially (factor=1.0, chars/4), then retries with less on 403/413.
 * EMA update: factor = factor*(1-ALPHA) + ratio*ALPHA where ratio = actual/estimatedRaw.
 */
public class TokenCalibrationManager {
    private static final Path FILE = Paths.get("token_calibration.json");
    private static final double DEFAULT_FACTOR = 1.0;
    private static final double ALPHA = 0.3;
    private static final double MIN_FACTOR = 1.0;
    private static final double MAX_FACTOR = 3.0;

    private static final Pattern CONTEXT_PATTERN = Pattern.compile("\\((\\d+)\\s*/\\s*\\d+\\s*\\)");
    private static final Pattern GROQ_REQUESTED_PATTERN = Pattern.compile("Requested\\s+(\\d+)");
    private static final Pattern GROQ_LIMIT_PATTERN = Pattern.compile("Limit\\s+(\\d+)");
    private static final Pattern CJK_PATTERN = Pattern.compile(
            "[\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uff00-\uffef\uac00-\ud7af]");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(?:def |class |function |const |let |var |import |from |if \\(|for \\(|while \\(|=>|->|\\{\\{|\\}\\}|;$)",
            Pattern.MULTILINE);

    private static final double CHARS_PER_TOKEN = 4.0;
    private static final double CHARS_PER_TOKEN_CODE = 3.5;
    private static final double CHARS_PER_TOKEN_CJK = 1.5;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile double factor;

    private static volatile TokenCalibrationManager INSTANCE;

    public static TokenCalibrationManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TokenCalibrationManager.class) {
                if (INSTANCE == null) INSTANCE = new TokenCalibrationManager();
            }
        }
        return INSTANCE;
    }

    private TokenCalibrationManager() {
        this.factor = loadFactor();
    }

    private double loadFactor() {
        if (Files.exists(FILE)) {
            try {
                String content = Files.readString(FILE);
                var node = mapper.readTree(content);
                double f = node.path("factor").asDouble(DEFAULT_FACTOR);
                int samples = node.path("samples").asInt(0);
                System.out.println("Loaded token calibration factor=" + f + " samples=" + samples);
                return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f));
            } catch (IOException e) {
                System.err.println("Failed to load token calibration: " + e.getMessage());
            }
        }
        return DEFAULT_FACTOR;
    }

    private synchronized void saveFactor(int samples) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    java.util.Map.of("factor", factor, "samples", samples));
            Files.writeString(FILE, json);
        } catch (IOException e) {
            System.err.println("Failed to save token calibration: " + e.getMessage());
        }
    }

    public double getFactor() {
        return factor;
    }

    public static int estimateRaw(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkChars = 0;
        Matcher m = CJK_PATTERN.matcher(text);
        while (m.find()) cjkChars++;
        int otherChars = text.length() - cjkChars;

        double ratio = CHARS_PER_TOKEN;
        if (text.contains("```") || CODE_PATTERN.matcher(text).find()) {
            long codeHits = CODE_PATTERN.matcher(text).results().count();
            if (codeHits > text.length() / 500.0) ratio = CHARS_PER_TOKEN_CODE;
        }

        double tokens = cjkChars / CHARS_PER_TOKEN_CJK + otherChars / ratio;

        Matcher um = URL_PATTERN.matcher(text);
        while (um.find()) {
            String url = um.group();
            double urlTokensHeuristic = url.length() / 2.0;
            double urlTokensProse = url.length() / ratio;
            tokens += (urlTokensHeuristic - urlTokensProse);
        }

        return Math.max(1, (int) Math.ceil(tokens));
    }

    public int estimateTokens(String text) {
        int raw = estimateRaw(text);
        return (int) Math.ceil(raw * factor);
    }

    public static boolean isContextLengthError(String errorMsg) {
        return errorMsg != null && errorMsg.contains("exceeded the maximum context length");
    }

    public static boolean isGroqTpmError(String errorMsg) {
        return errorMsg != null && errorMsg.contains("tokens per minute") && GROQ_REQUESTED_PATTERN.matcher(errorMsg).find();
    }

    public static boolean isCalibrationError(String errorMsg) {
        return isContextLengthError(errorMsg) || isGroqTpmError(errorMsg);
    }

    public static Integer extractActualTokens(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher matcher = CONTEXT_PATTERN.matcher(errorMsg);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractGroqRequested(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher m = GROQ_REQUESTED_PATTERN.matcher(errorMsg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractGroqLimit(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher m = GROQ_LIMIT_PATTERN.matcher(errorMsg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractActualTokensForCalibration(String errorMsg) {
        Integer ctx = extractActualTokens(errorMsg);
        if (ctx != null) return ctx;
        return extractGroqRequested(errorMsg);
    }

    public static Integer extractLimitForCalibration(String errorMsg) {
        if (isContextLengthError(errorMsg)) {
            Matcher m = CONTEXT_PATTERN.matcher(errorMsg);
            if (m.find()) {
                Pattern denom = Pattern.compile("\\(\\d+\\s*/\\s*(\\d+)\\s*\\)");
                Matcher dm = denom.matcher(errorMsg);
                if (dm.find()) {
                    try { return Integer.parseInt(dm.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            return 12288;
        }
        if (isGroqTpmError(errorMsg)) {
            Integer lim = extractGroqLimit(errorMsg);
            return lim != null ? lim : 8000;
        }
        return null;
    }

    public synchronized double recordFromError(String prompt, String errorMsg) {
        Integer actual = extractActualTokensForCalibration(errorMsg);
        if (actual == null) {
            System.out.println("Calibration: could not parse actual tokens from: " + errorMsg);
            return factor;
        }
        int estimatedRaw = estimateRaw(prompt);
        if (estimatedRaw == 0) return factor;
        double ratio = (double) actual / estimatedRaw;
        if (ratio < 1.0) ratio = 1.0;
        if (ratio > 3.0) ratio = 3.0;
        double old = factor;
        factor = old * (1 - ALPHA) + ratio * ALPHA;
        factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        System.out.println("Calibration update: estimatedRaw=" + estimatedRaw + " actual=" + actual +
                " ratio=" + String.format("%.2f", ratio) + " factor " + String.format("%.2f", old) + " -> " + String.format("%.2f", factor));
        int samples = 0;
        if (Files.exists(FILE)) {
            try {
                var node = mapper.readTree(Files.readString(FILE));
                samples = node.path("samples").asInt(0);
            } catch (IOException ignored) {}
        }
        saveFactor(samples + 1);
        return factor;
    }

    public synchronized void setFactor(double newFactor) {
        factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, newFactor));
        saveFactor(0);
    }

    // For testing: reset singleton
    static void resetForTest() {
        INSTANCE = null;
    }
}
