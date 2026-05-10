package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MarkovManager {
    private static final Path DEFAULT_BRAIN_DIR = Paths.get("data/markov");
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
        Path path = getBrainPath(channelId);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(trimmed);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to append to brain for channel " + channelId + ": " + e.getMessage());
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

    private Path getBrainPath(long channelId) {
        return brainDir.resolve(channelId + ".brain");
    }

    private JMegaHal newBrain(long channelId) {
        return new JMegaHal(config == null || config.allowShortMessages(channelId));
    }
}
