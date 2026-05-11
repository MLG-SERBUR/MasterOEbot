package com.masteroebot.markov;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

    @Test
    void botMessagesAreStoredWithPromptPrefixInAiLog() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "human question?");
        manager.appendToAiLog(channelId, "human question?");
        manager.appendBotMessageToAiLog(channelId, "bot answer");

        assertEquals(List.of("human question?", "MasterOEBot: bot answer"),
                manager.getRecentMessagesForAi(channelId, 500));
    }

    @Test
    void botMessagesAreNotStoredInBrain() throws Exception {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendBotMessageToAiLog(channelId, "hello there");
        manager.loadBrain(channelId);

        assertEquals(List.of("MasterOEBot: hello there"), manager.getRecentMessagesForAi(channelId, 500));
        assertTrue(manager.getRecentMessages(channelId, 500).isEmpty());
        assertTrue(Files.notExists(tempDir.resolve(channelId + ".brain")));
        assertTrue(manager.generateReply(channelId).isEmpty());
    }

    @Test
    void aiRecentMessagesFallBackToBrainWhenAiLogDoesNotExist() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "old human line");

        assertEquals(List.of("old human line"), manager.getRecentMessagesForAi(channelId, 500));
        assertTrue(Files.exists(tempDir.resolve(channelId + ".ai.log")));
    }

    @Test
    void aiLogInitializationCopiesBrainBeforeCurrentAppend() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "old human line");
        manager.ensureAiLogInitialized(channelId);
        manager.appendToBrain(channelId, "new human line");
        manager.appendToAiLog(channelId, "new human line");

        assertEquals(List.of("old human line", "new human line"),
                manager.getRecentMessagesForAi(channelId, 500));
    }
}
