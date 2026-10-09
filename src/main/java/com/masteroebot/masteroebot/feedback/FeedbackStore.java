package com.masteroebot.masteroebot.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FeedbackStore {
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_CONTEXT_LINE_LENGTH = 300;

    private static final Path DEFAULT_FILE = Paths.get("data/feedback/feedback.jsonl");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public FeedbackStore() {
        this(DEFAULT_FILE);
    }

    FeedbackStore(Path file) {
        this.file = file;
    }

    public synchronized void save(String userId, String username, String guildId, String guildName,
                                  String channelId, String message, List<String> context) throws IOException {
        String clean = sanitize(message);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Feedback message is empty.");
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("userId", nullToEmpty(userId));
        entry.put("username", nullToEmpty(username));
        entry.put("guildId", nullToEmpty(guildId));
        entry.put("guildName", nullToEmpty(guildName));
        entry.put("channelId", nullToEmpty(channelId));
        entry.put("message", clean);
        entry.put("context", sanitizeContext(context));

        Files.createDirectories(file.getParent());
        String line = MAPPER.writeValueAsString(entry) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String clean = message.strip().replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        if (clean.length() > MAX_MESSAGE_LENGTH) {
            clean = clean.substring(0, MAX_MESSAGE_LENGTH);
        }
        return clean;
    }

    static List<String> sanitizeContext(List<String> context) {
        List<String> clean = new ArrayList<>();
        if (context == null) {
            return clean;
        }
        for (String line : context) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String trimmed = line.strip().replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_CONTEXT_LINE_LENGTH) {
                trimmed = trimmed.substring(0, MAX_CONTEXT_LINE_LENGTH);
            }
            clean.add(trimmed);
        }
        return clean;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
