package com.masteroebot.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MarkovConfig {
    private static final String CONFIG_FILE = "data/markov/config.yml";
    /** MasterOEBot per-channel enable. */
    private final Map<Long, Boolean> channelToggles = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> shortMessageToggles = new ConcurrentHashMap<>();
    /** paraokabot per-channel enable, independent from MasterOEBot. */
    private final Map<Long, Boolean> paraokaToggles = new ConcurrentHashMap<>();
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
            applyProperties(props);
        } catch (IOException e) {
            System.err.println("Failed to load Markov config: " + e.getMessage());
        }
    }

    /**
     * Parses stored keys into the toggle maps. Channels enabled under the
     * legacy shared switch (no explicit paraoka key) keep paraokabot on, so
     * the split changes nothing until someone toggles per bot.
     */
    void applyProperties(Properties props) {
        for (String key : props.stringPropertyNames()) {
            try {
                if (key.endsWith(".enabled")) {
                    long channelId = Long.parseLong(key.substring(0, key.length() - ".enabled".length()));
                    boolean enabled = Boolean.parseBoolean(props.getProperty(key));
                    channelToggles.put(channelId, enabled);
                } else if (key.endsWith(".allowShortMessages")) {
                    long channelId = Long.parseLong(key.substring(0, key.length() - ".allowShortMessages".length()));
                    boolean enabled = Boolean.parseBoolean(props.getProperty(key));
                    shortMessageToggles.put(channelId, enabled);
                } else if (key.endsWith(".paraokaEnabled")) {
                    long channelId = Long.parseLong(key.substring(0, key.length() - ".paraokaEnabled".length()));
                    boolean enabled = Boolean.parseBoolean(props.getProperty(key));
                    paraokaToggles.put(channelId, enabled);
                } else {
                    long channelId = Long.parseLong(key);
                    boolean enabled = Boolean.parseBoolean(props.getProperty(key));
                    channelToggles.put(channelId, enabled);
                }
            } catch (NumberFormatException ignored) {}
        }
        for (Map.Entry<Long, Boolean> entry : channelToggles.entrySet()) {
            if (entry.getValue() && !paraokaToggles.containsKey(entry.getKey())) {
                paraokaToggles.put(entry.getKey(), true);
            }
        }
    }

    /** MasterOEBot enable for the channel. Defaults off. */
    public boolean isEnabled(long channelId) {
        return channelToggles.getOrDefault(channelId, false);
    }

    public Set<Long> getEnabledChannelIds() {
        Set<Long> enabled = new HashSet<>();
        for (Map.Entry<Long, Boolean> entry : channelToggles.entrySet()) {
            if (entry.getValue()) {
                enabled.add(entry.getKey());
            }
        }
        return enabled;
    }

    public void setEnabled(long channelId, boolean enabled) {
        channelToggles.put(channelId, enabled);
        save();
    }

    public boolean allowShortMessages(long channelId) {
        return shortMessageToggles.getOrDefault(channelId, true);
    }

    public void setAllowShortMessages(long channelId, boolean enabled) {
        shortMessageToggles.put(channelId, enabled);
        save();
    }

    /** paraokabot enable for the channel. Defaults off; legacy enabled channels migrate on. */
    public boolean isParaokaEnabled(long channelId) {
        return paraokaToggles.getOrDefault(channelId, false);
    }

    public Set<Long> getParaokaEnabledChannelIds() {
        Set<Long> enabled = new HashSet<>();
        for (Map.Entry<Long, Boolean> entry : paraokaToggles.entrySet()) {
            if (entry.getValue()) {
                enabled.add(entry.getKey());
            }
        }
        return enabled;
    }

    public void setParaokaEnabled(long channelId, boolean enabled) {
        paraokaToggles.put(channelId, enabled);
        save();
    }

    private void save() {
        Path path = Paths.get(CONFIG_FILE);
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            for (Map.Entry<Long, Boolean> entry : channelToggles.entrySet()) {
                props.setProperty(entry.getKey() + ".enabled", String.valueOf(entry.getValue()));
            }
            for (Map.Entry<Long, Boolean> entry : shortMessageToggles.entrySet()) {
                props.setProperty(entry.getKey() + ".allowShortMessages", String.valueOf(entry.getValue()));
            }
            for (Map.Entry<Long, Boolean> entry : paraokaToggles.entrySet()) {
                props.setProperty(entry.getKey() + ".paraokaEnabled", String.valueOf(entry.getValue()));
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
