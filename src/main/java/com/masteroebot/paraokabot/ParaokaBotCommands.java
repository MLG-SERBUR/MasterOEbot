package com.masteroebot.paraokabot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

/**
 * Owns which slash commands paraokabot registers on its own application:
 * only its own toggle. Commandless states clear global commands so no
 * stale entries linger from earlier runs.
 */
public final class ParaokaBotCommands {
    private ParaokaBotCommands() {
    }

    public static void registerForBot(String displayName, JDA jda, ParaokaCommandListener listener) {
        CommandListUpdateAction updater = jda.updateCommands();
        listener.registerCommands(updater);
        updater.queue(
                success -> System.out.println("[" + displayName + "] Registered slash commands."),
                error -> System.err.println("[" + displayName + "] Slash command registration failed. " + error.getMessage()));
    }
}
