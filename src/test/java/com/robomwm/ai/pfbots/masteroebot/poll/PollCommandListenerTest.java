package com.robomwm.ai.pfbots.masteroebot.poll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PollCommandListenerTest {
    @Test
    void parsesValidJson() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "{\"question\": \"Best game?\", \"options\": [\"Chess\", \"Go\"]}");
        assertEquals("Best game?", spec.question());
        assertEquals(2, spec.options().size());
    }

    @Test
    void stripsCodeFencesAndDedupes() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "```json\n{\"question\": \"Q?\", \"options\": [\"A\", \"A\", \"B\"]}\n```");
        assertEquals(2, spec.options().size());
    }

    @Test
    void capsOptionsAtTen() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "{\"question\": \"Q?\", \"options\": [\"A\", \"B\", \"C\", \"D\", \"E\", \"F\", \"G\", \"H\", \"I\", \"J\", \"K\", \"L\"]}");
        assertEquals(10, spec.options().size());
    }

    @Test
    void ignoresDurationIfPresent() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "{\"question\": \"Q?\", \"options\": [\"A\", \"B\"], \"durationHours\": 4}");
        assertEquals(2, spec.options().size());
    }

    @Test
    void rollDurationStaysInRange() {
        for (int i = 0; i < 200; i++) {
            int hours = PollCommandListener.rollDurationHours();
            assertTrue(hours >= 8 && hours <= 72, "out of range: " + hours);
        }
    }

    @Test
    void rejectsTooFewOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> PollCommandListener.parsePollSpec("{\"question\": \"Q?\", \"options\": [\"Only\"]}"));
    }

    @Test
    void rejectsMissingQuestion() {
        assertThrows(IllegalArgumentException.class,
                () -> PollCommandListener.parsePollSpec("{\"options\": [\"A\", \"B\"]}"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> PollCommandListener.parsePollSpec("not json at all"));
    }

    @Test
    void systemPromptCoversQuestionAndOptionsOnly() {
        String prompt = PollCommandListener.SYSTEM_PROMPT.toLowerCase();
        assertTrue(prompt.contains("question"));
        assertTrue(prompt.contains("options"));
        assertTrue(!prompt.contains("duration"), "AI must not decide duration");
    }
}
