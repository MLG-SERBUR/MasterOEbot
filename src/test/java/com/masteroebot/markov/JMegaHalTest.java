package com.masteroebot.markov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void ignoresSingleWordMessage() {
        JMegaHal brain = new JMegaHal();

        brain.add("hello");

        assertEquals("", brain.getSentence());
    }
}
