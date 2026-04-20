package com.masteroebot.connect4;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public record BotConfig(String token) {

    public static BotConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing config file: " + path + " (copy config.yaml.example to config.yaml)");
        }

        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.loadAs(in, Map.class);
            if (data == null || data.isEmpty()) {
                throw new IllegalStateException("Config file is empty: " + path);
            }

            String token = readString(data, "discord.token");
            if (token == null || token.isBlank() || "PUT_YOUR_BOT_TOKEN_HERE".equals(token)) {
                throw new IllegalStateException("Please set discord.token in " + path);
            }

            return new BotConfig(token);
        }
    }

    @SuppressWarnings("unchecked")
    private static String readString(Map<String, Object> map, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = ((Map<String, Object>) currentMap).get(part);
            if (current == null) {
                return null;
            }
        }

        return current instanceof String value ? value : null;
    }
}
