package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MarkovManager {
    private static final String BRAIN_DIR = "data/markov";
    private final Map<Long, JMegaHal> brains = new HashMap<>();
    private final Map<Long, Boolean> brainLoaded = new HashMap<>();

    public synchronized void loadBrain(long channelId) {
        if (brainLoaded.getOrDefault(channelId, false)) return;
        brainLoaded.put(channelId, true);

        JMegaHal brain = new JMegaHal();
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

    public synchronized boolean isEmpty(long channelId) {
        JMegaHal brain = brains.get(channelId);
        return brain == null || brain.getSentence().isEmpty();
    }

    public synchronized void seedFromHistory(long channelId, List<String> messages) {
        JMegaHal brain = brains.computeIfAbsent(channelId, k -> new JMegaHal());
        for (String msg : messages) {
            if (msg != null && !msg.trim().isEmpty()) {
                brain.add(msg.trim());
                appendToBrain(channelId, msg.trim());
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
        JMegaHal brain = brains.computeIfAbsent(channelId, k -> new JMegaHal());
        brain.add(message.trim());
    }

    public synchronized void appendToBrain(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        Path path = getBrainPath(channelId);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(message.trim());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to append to brain for channel " + channelId + ": " + e.getMessage());
        }
    }

    private Path getBrainPath(long channelId) {
        return Paths.get(BRAIN_DIR, channelId + ".brain");
    }
}
