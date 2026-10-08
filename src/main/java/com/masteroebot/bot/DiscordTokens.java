package com.masteroebot.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Legacy loader for discord.yaml extra tokens, used only by the BotMain
 * fallback path when dumcord.yml is absent.
 *
 * <p>Accepted shapes (all optional, first hit wins):
 * <pre>
 * tokens: ["SECOND_TOKEN"]
 * discord:
 *   tokens: ["SECOND_TOKEN"]
 * </pre>
 */
public final class DiscordTokens {
    private DiscordTokens() {
    }

    public static List<String> loadExtraTokens(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        Map<String, Object> data = loadYamlMap(path);
        List<String> tokens = readStringList(data, "tokens");
        if (tokens == null) {
            tokens = readStringList(data, "discord.tokens");
        }
        if (tokens == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String token : tokens) {
            if (token != null && !token.isBlank() && !token.contains("PUT_")) {
                cleaned.add(token.strip());
            }
        }
        return List.copyOf(cleaned);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlMap(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> map = yaml.loadAs(in, Map.class);
            return map == null ? Map.of() : map;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Map<String, Object> map, String dottedPath) {
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
        if (current instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        } else if (current instanceof String str) {
            return List.of(str);
        }
        return null;
    }
}
