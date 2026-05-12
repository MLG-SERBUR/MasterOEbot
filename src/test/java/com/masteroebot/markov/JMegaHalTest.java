package com.masteroebot.markov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JMegaHalTest {
    @Test
    void trainsAndGeneratesTwoWordMessage() {
        JMegaHal brain = new JMegaHal();

        brain.add("hello there");

        assertEquals("hello there", brain.getSentence());
    }

    @Test
    void seededGenerationUsesTwoWordMessage() {
        JMegaHal brain = new JMegaHal();

        brain.add("hello there");

        assertEquals("hello there", brain.getSentence("there"));
    }

    @Test
    void twoWordMessageCanGenerateWhenLongerMessagesExist() {
        JMegaHal brain = new JMegaHal();

        brain.add("hello there");
        brain.add("this is a longer message");

        boolean generatedShortMessage = false;
        for (int i = 0; i < 50; i++) {
            if ("hello there".equals(brain.getSentence())) {
                generatedShortMessage = true;
                break;
            }
        }

        assertTrue(generatedShortMessage);
    }

    @Test
    void trainsAndGeneratesSingleWordMessage() {
        JMegaHal brain = new JMegaHal();

        brain.add("hello");

        assertEquals("hello", brain.getSentence());
    }

    @Test
    void legacyModeRequiresFourTokens() {
        JMegaHal brain = new JMegaHal(false);

        brain.add("hello there");

        assertEquals("", brain.getSentence());
    }

    @Test
    void listenerCountsWordsForShortMessageSeedBypass() {
        assertEquals(0, MarkovListener.countWords(""));
        assertEquals(1, MarkovListener.countWords("hello"));
        assertEquals(2, MarkovListener.countWords("hello there"));
        assertEquals(3, MarkovListener.countWords("hello there bot"));
    }

    @Test
    void listenerRejectsNonChatLikeAiOutput() {
        assertTrue(MarkovListener.isLikelyChatLikeAiReply("probably yea"));
        assertFalse(MarkovListener.isLikelyChatLikeAiReply("first line\nsecond line"));
        assertFalse(MarkovListener.isLikelyChatLikeAiReply("Assistant: probably yea"));
        assertFalse(MarkovListener.isLikelyChatLikeAiReply("```java\nclass Test {}\n```"));
        assertFalse(MarkovListener.isLikelyChatLikeAiReply(""));
    }
}
