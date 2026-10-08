package com.masteroebot.bot;

import com.masteroebot.connect4.Connect4CommandListener;
import com.masteroebot.feedback.FeedbackCommandListener;
import com.masteroebot.typeracer.TypeRacerCommandListener;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

/**
 * Owns which slash commands each bot registers. MasterOEBot registers the
 * full set; commandless bots clear their global commands so no stale entries
 * linger from earlier runs. Each call targets one bot's own JDA/application,
 * so bots never wipe each other.
 */
public final class BotCommandRegistrar {
    private BotCommandRegistrar() {
    }

    public static void registerForBot(BotProfile profile, JDA jda,
            Connect4CommandListener connect4Listener,
            TypeRacerCommandListener typeracerListener,
            FeedbackCommandListener feedbackListener) {
        if (profile.registersCommands()) {
            CommandListUpdateAction updater = jda.updateCommands();
            connect4Listener.registerCommands(updater);
            typeracerListener.registerCommands(updater);
            feedbackListener.registerCommands(updater);
            updater.queue(
                    success -> System.out.println("[" + profile.displayName() + "] Registered slash commands."),
                    error -> System.err.println("[" + profile.displayName() + "] Slash command registration failed. " + error.getMessage()));
        } else {
            jda.updateCommands().queue(
                    success -> System.out.println("[" + profile.displayName() + "] No commands to register; cleared."),
                    error -> System.err.println("[" + profile.displayName() + "] Command clear failed. " + error.getMessage()));
        }
    }
}
