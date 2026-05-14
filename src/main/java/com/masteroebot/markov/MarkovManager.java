package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MarkovManager {
    private static final Path DEFAULT_BRAIN_DIR = Paths.get("data/markov");
    public static final String BOT_MESSAGE_PREFIX = "<MasterOEBot> ";
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
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, BOT_MESSAGE_PREFIX + trimmed, getAiLogPath(channelId), "AI log");
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
                    inBotMessage = trimmed.startsWith(BOT_MESSAGE_PREFIX.trim());
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
