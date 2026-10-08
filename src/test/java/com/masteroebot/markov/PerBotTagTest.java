package com.masteroebot.markov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerBotTagTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void perBotResponderPrompts() {
        GenerativeAiConfig config = GenerativeAiConfig.defaults();
        var main = new RoundRobinGenerativeAiResponder(config, "main prompt");
        var para = new RoundRobinGenerativeAiResponder(config, "para prompt");
        assertEquals("main prompt", main.getSystemPrompt());
        assertEquals("para prompt", para.getSystemPrompt());

        ArliAiCoordinator coordinator = new ArliAiCoordinator();
        var sc = new ArliAiSecondChanceResponder(config, coordinator, "para follow-up");
        assertEquals("para follow-up", sc.getSystemPrompt());
        var reaction = new ArliAiReactionResponder(config, coordinator, "para reactions");
        assertEquals("para reactions", reaction.getSystemPrompt());
    }

    @Test
    void appendsTagPerBotAndScrubRemovesBoth() {
        MarkovConfig config = new MarkovConfig();
        MarkovManager manager = new MarkovManager(config, tempDir);
        long channelId = 123L;

        manager.appendToAiLog(channelId, "human", "hello");
        manager.appendBotMessageToAiLog(channelId, "master reply", "<MasterOEBot> ");
        manager.appendBotMessageToAiLog(channelId, "para reply", "<paraokabot> ");

        List<String> lines = manager.getRecentMessagesForAi(channelId, 10);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("<MasterOEBot> "));
        assertTrue(lines.get(2).startsWith("<paraokabot> "));

        manager.scrubAiLog(channelId);
        List<String> after = manager.getRecentMessagesForAi(channelId, 10);
        assertEquals(List.of("<human> hello"), after);
    }

    @Test
    void humanLookalikeTagNeverScrubbed() {
        assertFalse(MarkovManager.isBotPrefix("<MasterOE> hello"));
        assertTrue(MarkovManager.isBotPrefix("<MasterOEBot> hello"));
        assertTrue(MarkovManager.isBotPrefix("<paraokabot> hello"));
    }
}
