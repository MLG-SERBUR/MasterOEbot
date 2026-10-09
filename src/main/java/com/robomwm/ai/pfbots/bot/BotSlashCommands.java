package com.robomwm.ai.pfbots.bot;

import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

/**
 * Slash commands owned by one bot. Each bot's own listener implements this
 * so the shared bootstrap can register commands and availability without
 * knowing bot-specific command sets.
 */
public interface BotSlashCommands {
    void registerCommands(CommandListUpdateAction updater);

    void setMarkovAvailable(boolean available);
}
