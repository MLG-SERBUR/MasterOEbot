package com.masteroebot.feedback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackStoreTest {
    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void appendsValidJsonLine() throws Exception {
        Path file = tempDir.resolve("nested/feedback.jsonl");
        FeedbackStore store = new FeedbackStore(file);

        store.save("1", "bro", "2", "guild", "3", "fix the prompt lol");

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertEquals("1", node.get("userId").asText());
        assertEquals("bro", node.get("username").asText());
        assertEquals("fix the prompt lol", node.get("message").asText());
        assertTrue(node.has("timestamp"));
    }

    @Test
    void appendsMultipleLines() throws Exception {
        Path file = tempDir.resolve("feedback.jsonl");
        FeedbackStore store = new FeedbackStore(file);

        store.save("1", "a", null, null, "3", "first");
        store.save("2", "b", null, null, "4", "second");

        assertEquals(2, Files.readAllLines(file).size());
    }

    @Test
    void rejectsBlankMessage() {
        FeedbackStore store = new FeedbackStore(tempDir.resolve("feedback.jsonl"));

        assertThrows(IllegalArgumentException.class,
                () -> store.save("1", "a", null, null, "3", "   "));
    }

    @Test
    void truncatesLongMessage() throws Exception {
        Path file = tempDir.resolve("feedback.jsonl");
        FeedbackStore store = new FeedbackStore(file);

        store.save("1", "a", null, null, "3", "x".repeat(2000));

        JsonNode node = MAPPER.readTree(Files.readAllLines(file).get(0));
        assertEquals(FeedbackStore.MAX_MESSAGE_LENGTH, node.get("message").asText().length());
    }

    @Test
    void repliesComeFromPregeneratedList() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String reply = FeedbackCommandListener.pickReply();
            assertTrue(FeedbackCommandListener.REPLIES.contains(reply));
            seen.add(reply);
        }
        assertTrue(seen.size() > 1, "replies should vary");
    }
}
