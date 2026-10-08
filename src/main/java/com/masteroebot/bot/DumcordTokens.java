package com.masteroebot.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Bot tokens keyed by profile key. Sole configured source of tokens
 * (legacy config.yaml/discord.yaml fallback lives in BotMain).
 *
 * <pre>
 * bots:
 *   MasterOEBot: "MASTER_TOKEN"
 *   paraokabot: "PARAOKA_TOKEN"
 * </pre>
 *
 * An ordered {@code tokens: [...]} list is also accepted (index = profile
 * order). Missing/blank/placeholder entries mean that bot is disabled.
 */
public final class DumcordTokens {
    private final Map<String, String> byKey;

    private DumcordTokens(Map<String, String> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    public static DumcordTokens load(Path path) throws IOException {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return new DumcordTokens(byKey);
        }
        Map<String, Object> data = loadYamlMap(path);

        Object bots = data.get("bots");
        if (bots instanceof Map<?, ?> botsMap) {
            for (Map.Entry<?, ?> entry : botsMap.entrySet()) {
                String key = String.valueOf(entry.getKey()).strip();
                String token = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).strip();
                if (!key.isBlank() && usable(token)) {
                    byKey.put(key, token);
                }
            }
            for (String key : botsMap.keySet().stream().map(k -> String.valueOf(k).strip()).toList()) {
                if (BotRegistry.PROFILES.stream().noneMatch(p -> p.key().equals(key))) {
                    System.err.println("dumcord.yml: unknown bot '" + key + "', ignored.");
                }
            }
        }

        List<String> ordered = readStringList(data, "tokens");
        if (ordered == null) {
            ordered = readStringList(data, "discord.tokens");
        }
        if (ordered != null) {
            List<BotProfile> profiles = BotRegistry.PROFILES;
            for (int i = 0; i < ordered.size() && i < profiles.size(); i++) {
                String token = ordered.get(i) == null ? "" : ordered.get(i).strip();
                if (usable(token)) {
                    byKey.putIfAbsent(profiles.get(i).key(), token);
                }
            }
        }

        return new DumcordTokens(byKey);
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /** Token for a profile key, or null when that bot is disabled. */
    public String tokenFor(String key) {
        return byKey.get(key);
    }

    private static boolean usable(String token) {
        return token != null && !token.isBlank() && !token.contains("PUT_");
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
            return list.stream().map(o -> o == null ? "" : o.toString()).toList();
        } else if (current instanceof String str) {
            return List.of(str);
        }
        return null;
    }
}
