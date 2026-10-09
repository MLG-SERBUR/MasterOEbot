package com.robomwm.ai.pfbots.masteroebot.poll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PollCommandListenerTest {
    @Test
    void parsesValidJson() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "{\"question\": \"Best game?\", \"options\": [\"Chess\", \"Go\"], \"durationHours\": 48}");
        assertEquals("Best game?", spec.question());
        assertEquals(2, spec.options().size());
        assertEquals(48, spec.durationHours());
    }

    @Test
    void stripsCodeFencesAndClamps() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "```json\n{\"question\": \"Q?\", \"options\": [\"A\", \"A\", \"B\"], \"durationHours\": 999}\n```");
        assertEquals(2, spec.options().size());
        assertEquals(168, spec.durationHours());
    }

    @Test
    void defaultsDurationWhenMissing() {
        PollCommandListener.PollSpec spec = PollCommandListener.parsePollSpec(
                "{\"question\": \"Q?\", \"options\": [\"A\", \"B\"]}");
        assertEquals(24, spec.durationHours());
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
    void systemPromptCoversAllOutputs() {
        String prompt = PollCommandListener.SYSTEM_PROMPT.toLowerCase();
        assertTrue(prompt.contains("question"));
        assertTrue(prompt.contains("options"));
        assertTrue(prompt.contains("durationhours"));
    }
}
