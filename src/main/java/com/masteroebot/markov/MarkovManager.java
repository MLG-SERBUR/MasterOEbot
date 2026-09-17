package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MarkovManager {
    private static final Path DEFAULT_BRAIN_DIR = Paths.get("data/markov");
    public static final String BOT_MESSAGE_PREFIX = "<MasterOEBot> ";
    private static final String BOT_MESSAGE_TAG = BOT_MESSAGE_PREFIX.trim();
    private static final String BRAIN_EXTENSION = ".brain";
    private static final String AI_LOG_EXTENSION = ".ai.log";
    private final MarkovConfig config;
    private final Path brainDir;
    private final Map<Long, JMegaHal> brains = new HashMap<>();
    private final Map<Long, Boolean> brainLoaded = new HashMap<>();

    public MarkovManager() {
        this(null);
    }

    public MarkovManager(MarkovConfig config) {
        this(config, DEFAULT_BRAIN_DIR);
    }

    MarkovManager(MarkovConfig config, Path brainDir) {
        this.config = config;
        this.brainDir = brainDir;
    }

    public synchronized void loadBrain(long channelId) {
        if (brainLoaded.getOrDefault(channelId, false)) return;
        brainLoaded.put(channelId, true);

        JMegaHal brain = newBrain(channelId);
        Path path = getBrainPath(channelId);

        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        brain.add(line);
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to load brain for channel " + channelId + ": " + e.getMessage());
            }
        }

        brains.put(channelId, brain);
    }

    public synchronized void reloadBrain(long channelId) {
        brainLoaded.remove(channelId);
        brains.remove(channelId);
        loadBrain(channelId);
    }

    public synchronized boolean isEmpty(long channelId) {
        JMegaHal brain = brains.get(channelId);
        return brain == null || brain.getSentence().isEmpty();
    }

    public synchronized void seedFromHistory(long channelId, List<String> messages) {
        JMegaHal brain = brains.computeIfAbsent(channelId, this::newBrain);
        for (String msg : messages) {
            if (msg != null && !msg.trim().isEmpty()) {
                String trimmed = msg.trim();
                if (!ProfanityFilter.containsProfanity(trimmed)) {
                    brain.add(trimmed);
                    appendToBrain(channelId, trimmed);
                }
            }
        }
    }

    public synchronized String generateReply(long channelId) {
        JMegaHal brain = brains.get(channelId);
        return brain != null ? brain.getSentence() : "";
    }

    public synchronized String generateReply(long channelId, String seedWord) {
        JMegaHal brain = brains.get(channelId);
        return brain != null ? brain.getSentence(seedWord) : "";
    }

    public synchronized void train(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        JMegaHal brain = brains.computeIfAbsent(channelId, this::newBrain);
        brain.add(trimmed);
    }

    public synchronized void appendToBrain(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, trimmed, getBrainPath(channelId), "brain");
    }

    public synchronized void appendToAiLog(long channelId, String authorName, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, "<" + authorName + "> " + trimmed, getAiLogPath(channelId), "AI log");
    }

    public synchronized void appendToAiLog(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, trimmed, getAiLogPath(channelId), "AI log");
    }

    public synchronized void appendBotMessageToAiLog(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = stripBotPrefix(message.trim());
        if (trimmed.isEmpty()) return;
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, BOT_MESSAGE_PREFIX + trimmed, getAiLogPath(channelId), "AI log");
    }

    /**
     * Canonical bot log prefix only ({@code <MasterOEBot>}). Used for scrub
     * detection. {@code <MasterOE>} is a human username — never scrub.
     */
    public static boolean isBotPrefix(String text) {
        return startsWithTag(text, BOT_MESSAGE_TAG);
    }

    private static boolean startsWithTag(String text, String tag) {
        if (text == null) return false;
        return text.length() >= tag.length()
                && text.regionMatches(true, 0, tag, 0, tag.length());
    }

    /**
     * Strips any leading {@code <...>} tag AI sometimes prepends to its
     * reply (e.g. {@code <MasterOEBot>}, {@code <MasterOE>}, any username).
     * Output-only: loops to handle repeated prefixes and tolerates leading
     * whitespace. A tag is a leading {@code <}, a non-empty name without
     * brackets/newlines, then {@code >}.
     */
    public static String stripBotPrefix(String text) {
        if (text == null) return "";
        String stripped = text.stripLeading();
        while (stripped.startsWith("<")) {
            int end = stripped.indexOf('>');
            if (end <= 1) {
                break;
            }
            String inside = stripped.substring(1, end);
            if (inside.contains("<") || inside.contains(">")
                    || inside.contains("\n") || inside.contains("\r")
                    || inside.trim().isEmpty()) {
                break;
            }
            stripped = stripped.substring(end + 1).stripLeading();
            if (stripped.isEmpty()) {
                break;
            }
        }
        return stripped;
    }

    public synchronized void ensureAiLogInitialized(long channelId) {
        Path aiLogPath = getAiLogPath(channelId);
        if (Files.exists(aiLogPath)) {
            return;
        }

        try {
            Files.createDirectories(aiLogPath.getParent());
            Files.createFile(aiLogPath);
        } catch (IOException e) {
            System.err.println("Failed to initialize AI log for channel " + channelId + ": " + e.getMessage());
        }
    }

    public synchronized boolean aiLogExists(long channelId) {
        return Files.exists(getAiLogPath(channelId));
    }

    private void appendLine(long channelId, String line, Path path, String logName) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to append to " + logName + " for channel " + channelId + ": " + e.getMessage());
        }
    }

    public synchronized List<String> getRecentMessages(long channelId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        Path path = getBrainPath(channelId);
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        Deque<String> recentMessages = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (recentMessages.size() == limit) {
                    recentMessages.removeFirst();
                }
                recentMessages.addLast(trimmed);
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent brain messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        return new ArrayList<>(recentMessages);
    }

    public synchronized List<String> getRecentMessagesForAi(long channelId, int limit) {
        ensureAiLogInitialized(channelId);
        Path path = getAiLogPath(channelId);
        if (Files.exists(path)) {
            return getRecentLines(path, limit, "AI log", channelId);
        }
        return getRecentMessages(channelId, limit);
    }

    /**
     * Reads history backwards from the newest message until the token budget is
     * reached. Always includes the newest message.
     * Accounts for system prompt tokens in budget.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, null);
    }

    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, systemPrompt, (String) null);
    }

    /**
     * Variant that estimates with a representative model (per-family calibration), so the
     * gathered history fits a specific tokenizer family. Null model = default estimate.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt, String estimateModel) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, systemPrompt, estimateModel, null);
    }

    /**
     * Conservative variant across candidate models (max calibrated estimate),
     * so gathered history fits the most restrictive tokenizer family.
     * When {@code estimateModels} is null/empty, falls back to {@code estimateModel}.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt, String estimateModel, java.util.Collection<String> estimateModels) {
        if (tokenBudget <= 0) {
            return Collections.emptyList();
        }
        ensureAiLogInitialized(channelId);
        Path path = Files.exists(getAiLogPath(channelId)) ? getAiLogPath(channelId) : getBrainPath(channelId);
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent AI log messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        if (lines.isEmpty()) {
            return lines;
        }

        long systemTokens = estimateForGather(systemPrompt != null ? systemPrompt + "\n" : null, estimateModel, estimateModels);
        long overheadTokens = estimateForGather("system\nuser\n", estimateModel, estimateModels); // role overhead
        long effectiveBudget = tokenBudget - systemTokens - overheadTokens;
        if (effectiveBudget < 500) effectiveBudget = tokenBudget - systemTokens; // fallback if overhead too large
        if (effectiveBudget <= 0) effectiveBudget = tokenBudget;

        int start = lines.size() - 1;
        long usedTokens = estimateForGather(lines.get(start) + "\n", estimateModel, estimateModels);
        while (start > 0
                && estimateForGather(lines.get(start - 1) + "\n", estimateModel, estimateModels) <= effectiveBudget - usedTokens) {
            start--;
            usedTokens += estimateForGather(lines.get(start) + "\n", estimateModel, estimateModels);
        }
        if (start > 0 || !lines.isEmpty()) {
            List<String> gathered = new ArrayList<>(lines.subList(start, lines.size()));
            String dualEst = PromptTokenizer.formatDualTokenEstimates(String.join("\n", gathered));
            System.out.println("AI history pulled up to " + gathered.size()
                    + " messages (~" + (usedTokens + systemTokens) + " tokens of " + tokenBudget + " budget, system=" + systemTokens + " [" + dualEst + "]).");
            return gathered;
        }
        return new ArrayList<>(lines.subList(start, lines.size()));
    }

    private static long estimateForGather(String text, String estimateModel, java.util.Collection<String> estimateModels) {
        if (text == null || text.isEmpty()) return 0;
        if (estimateModels != null && !estimateModels.isEmpty()) {
            return PromptTokenizer.estimateTokensConservative(text, estimateModels);
        }
        return PromptTokenizer.estimateTokens(text, estimateModel);
    }

    public synchronized void scrubAiLog(long channelId) {
        Path path = getAiLogPath(channelId);
        if (!Files.exists(path)) return;

        try {
            List<String> lines = Files.readAllLines(path);
            List<String> cleanLines = new ArrayList<>();
            boolean modified = false;

            boolean inBotMessage = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<") && trimmed.contains("> ")) {
                    inBotMessage = isBotPrefix(trimmed);
                }

                if (inBotMessage) {
                    modified = true;
                } else {
                    cleanLines.add(line);
                }
            }

            if (modified) {
                Files.write(path, cleanLines);
                System.out.println("Scrubbed AI log for channel " + channelId);
            }
        } catch (IOException e) {
            System.err.println("Failed to scrub AI log for channel " + channelId + ": " + e.getMessage());
        }
    }

    private Path getBrainPath(long channelId) {
        return brainDir.resolve(channelId + BRAIN_EXTENSION);
    }

    private Path getAiLogPath(long channelId) {
        return brainDir.resolve(channelId + AI_LOG_EXTENSION);
    }

    private JMegaHal newBrain(long channelId) {
        return new JMegaHal(config == null || config.allowShortMessages(channelId));
    }

    private List<String> getRecentLines(Path path, int limit, String logName, long channelId) {
        if (limit <= 0 || !Files.exists(path)) {
            return Collections.emptyList();
        }

        Deque<String> recentMessages = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (recentMessages.size() == limit) {
                    recentMessages.removeFirst();
                }
                recentMessages.addLast(trimmed);
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent " + logName + " messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        return new ArrayList<>(recentMessages);
    }
}
