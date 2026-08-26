package com.masteroebot.connect4;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.masteroebot.markov.GenerativeAiConfig;
import org.yaml.snakeyaml.Yaml;

public record BotConfig(String token, GenerativeAiConfig generativeAiConfig) {

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

            GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
            GenerativeAiConfig generativeAiConfig = new GenerativeAiConfig(
                    readString(data, "ai.systemPrompt", defaults.systemPrompt()),
                    readString(data, "ai.cerebrasApiKey", defaults.cerebrasApiKey()),
                    readString(data, "ai.groqApiKey", defaults.groqApiKey()),
                    readString(data, "ai.openrouterApiKey", defaults.openrouterApiKey()),
                    readString(data, "ai.geminiApiKey", defaults.geminiApiKey()),
                    readString(data, "ai.mistralApiKey", defaults.mistralApiKey()),
                    readString(data, "ai.zaiApiKey", defaults.zaiApiKey()),
                    readString(data, "ai.cloudflareApiKey", defaults.cloudflareApiKey()),
                    readString(data, "ai.cloudflareAccountId", defaults.cloudflareAccountId()),
                    readString(data, "ai.ollamaApiKey", defaults.ollamaApiKey()),
                    readString(data, "ai.sambaNovaApiKey", defaults.sambaNovaApiKey()),
                    readStringListWithLegacy(data, "ai.cerebrasModels", "ai.cerebrasModel", defaults.cerebrasModels()),
                    readStringListWithLegacy(data, "ai.groqModels", "ai.groqModel", defaults.groqModels()),
                    readStringList(data, "ai.openrouterModels", defaults.openrouterModels()),
                    readStringList(data, "ai.geminiModels", defaults.geminiModels()),
                    readStringList(data, "ai.mistralModels", defaults.mistralModels()),
                    readStringList(data, "ai.zaiModels", defaults.zaiModels()),
                    readStringList(data, "ai.cloudflareModels", defaults.cloudflareModels()),
                    readStringList(data, "ai.ollamaModels", defaults.ollamaModels()),
                    readStringList(data, "ai.sambaNovaModels", defaults.sambaNovaModels()));

            return new BotConfig(token, generativeAiConfig);
        }
    }

    private static String readString(Map<String, Object> map, String dottedPath, String defaultValue) {
        String value = readString(map, dottedPath);
        return value == null || value.isBlank() ? defaultValue : value;
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

    private static List<String> readStringList(Map<String, Object> map, String dottedPath, List<String> defaultValue) {
        List<String> value = readStringList(map, dottedPath);
        return value == null ? defaultValue : value;
    }

    private static List<String> readStringListWithLegacy(Map<String, Object> map, String dottedPath,
                                                         String legacyDottedPath, List<String> defaultValue) {
        List<String> value = readStringList(map, dottedPath);
        if (value != null) {
            return value;
        }
        value = readStringList(map, legacyDottedPath);
        return value == null ? defaultValue : value;
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
