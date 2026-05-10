package com.masteroebot.markov;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkovManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void recentMessagesReturnsTailInOriginalOrder() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        for (int i = 1; i <= 505; i++) {
            manager.appendToBrain(channelId, "message " + i);
        }

        List<String> recentMessages = manager.getRecentMessages(channelId, 500);

        assertEquals(500, recentMessages.size());
        assertEquals("message 6", recentMessages.get(0));
        assertEquals("message 505", recentMessages.get(499));
    }

    @Test
    void recentMessagesHandlesEmptyAndInvalidLimits() {
        MarkovManager manager = new MarkovManager(null, tempDir);

        assertTrue(manager.getRecentMessages(123L, 500).isEmpty());
        assertTrue(manager.getRecentMessages(123L, 0).isEmpty());
    }
}
