package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MarkovConfig {
    private static final String CONFIG_FILE = "data/markov/config.yml";
    private final Map<Long, Boolean> channelToggles = new ConcurrentHashMap<>();
    private boolean loaded = false;

    public void load() {
        if (loaded) return;
        loaded = true;

        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) {
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                try {
                    long channelId = Long.parseLong(key);
                    boolean enabled = Boolean.parseBoolean(props.getProperty(key));
                    channelToggles.put(channelId, enabled);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("Failed to load Markov config: " + e.getMessage());
        }
    }

    public boolean isEnabled(long channelId) {
        return channelToggles.getOrDefault(channelId, false);
    }

    public void setEnabled(long channelId, boolean enabled) {
        channelToggles.put(channelId, enabled);
        save();
    }

    private void save() {
        Path path = Paths.get(CONFIG_FILE);
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            for (Map.Entry<Long, Boolean> entry : channelToggles.entrySet()) {
                props.setProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "Markov Feature Config");
            }
        } catch (IOException e) {
            System.err.println("Failed to save Markov config: " + e.getMessage());
        }
    }
}
