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

        Map<String, Object> data = loadYamlMap(path);
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("Config file is empty: " + path);
        }

        String token = readString(data, "discord.token");
        if (token == null || token.isBlank() || "PUT_YOUR_BOT_TOKEN_HERE".equals(token)) {
            throw new IllegalStateException("Please set discord.token in " + path);
        }

        Path aiPath = path.resolveSibling("ai.yaml");
        Map<String, Object> aiData = data;
        if (Files.exists(aiPath)) {
            Map<String, Object> loaded = loadYamlMap(aiPath);
            if (loaded != null && !loaded.isEmpty()) {
                aiData = normalizeAiData(loaded);
            } else {
                aiData = normalizeAiData(Map.of());
            }
        } else {
            aiData = normalizeAiData(data);
        }

        GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
        GenerativeAiConfig generativeAiConfig = new GenerativeAiConfig(
                readString(aiData, "ai.systemPrompt", defaults.systemPrompt()),
                readString(aiData, "ai.secondChanceSystemPrompt", defaults.secondChanceSystemPrompt()),
                readString(aiData, "ai.cerebrasApiKey", defaults.cerebrasApiKey()),
                readString(aiData, "ai.groqApiKey", defaults.groqApiKey()),
                readString(aiData, "ai.openrouterApiKey", defaults.openrouterApiKey()),
                readString(aiData, "ai.geminiApiKey", defaults.geminiApiKey()),
                readString(aiData, "ai.mistralApiKey", defaults.mistralApiKey()),
                readString(aiData, "ai.zaiApiKey", defaults.zaiApiKey()),
                readString(aiData, "ai.cloudflareApiKey", defaults.cloudflareApiKey()),
                readString(aiData, "ai.cloudflareAccountId", defaults.cloudflareAccountId()),
                readString(aiData, "ai.ollamaApiKey", defaults.ollamaApiKey()),
                readString(aiData, "ai.sambaNovaApiKey", defaults.sambaNovaApiKey()),
                readString(aiData, "ai.arliApiKey", defaults.arliApiKey()),
                readStringListWithLegacy(aiData, "ai.cerebrasModels", "ai.cerebrasModel", defaults.cerebrasModels()),
                readStringListWithLegacy(aiData, "ai.groqModels", "ai.groqModel", defaults.groqModels()),
                readStringList(aiData, "ai.openrouterModels", defaults.openrouterModels()),
                readStringList(aiData, "ai.geminiModels", defaults.geminiModels()),
                readStringList(aiData, "ai.mistralModels", defaults.mistralModels()),
                readStringList(aiData, "ai.zaiModels", defaults.zaiModels()),
                readStringList(aiData, "ai.cloudflareModels", defaults.cloudflareModels()),
                readStringList(aiData, "ai.ollamaModels", defaults.ollamaModels()),
                readStringList(aiData, "ai.sambaNovaModels", defaults.sambaNovaModels()),
                readStringList(aiData, "ai.arliModels", defaults.arliModels()));

        return new BotConfig(token, generativeAiConfig);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlMap(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> map = yaml.loadAs(in, Map.class);
            return map == null ? Map.of() : map;
        }
    }

    private static Map<String, Object> normalizeAiData(Map<String, Object> raw) {
        if (raw.containsKey("ai")) {
            return raw;
        }
        return Map.of("ai", raw);
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
