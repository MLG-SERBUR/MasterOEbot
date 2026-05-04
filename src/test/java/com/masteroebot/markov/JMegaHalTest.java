package com.masteroebot.markov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
