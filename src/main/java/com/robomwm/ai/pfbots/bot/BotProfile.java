package com.robomwm.ai.pfbots.bot;

/**
 * Per-bot identity and voice. Prompts live in code (not yaml) so each bot
 * keeps its own personality while sharing the same provider backend.
 * Index 0 is always the primary bot (MasterOEBot).
 */
public record BotProfile(
        String key,
        String displayName,
        String botTag,
        String systemPrompt,
        String secondChancePrompt,
        String reactionPrompt,
        boolean registersCommands) {
    public BotProfile {
        if (!botTag.endsWith(" ")) {
            botTag = botTag + " ";
        }
    }
}
