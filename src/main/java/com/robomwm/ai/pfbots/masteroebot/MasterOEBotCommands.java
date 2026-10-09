package com.robomwm.ai.pfbots.masteroebot;

import com.robomwm.ai.pfbots.bot.BotProfile;
import com.robomwm.ai.pfbots.masteroebot.connect4.Connect4CommandListener;
import com.robomwm.ai.pfbots.masteroebot.feedback.FeedbackCommandListener;
import com.robomwm.ai.pfbots.masteroebot.poll.PollCommandListener;
import com.robomwm.ai.pfbots.masteroebot.typeracer.TypeRacerCommandListener;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

/**
 * Owns which slash commands MasterOEBot registers on its own application:
 * the full set (connect4, masteroebot, markov, remind, typeracer, feedback, poll).
 * Commandless states clear global commands so no stale entries linger.
 */
public final class MasterOEBotCommands {
    private MasterOEBotCommands() {
    }

    public static void registerForBot(BotProfile profile, JDA jda,
            Connect4CommandListener connect4Listener,
            TypeRacerCommandListener typeracerListener,
            FeedbackCommandListener feedbackListener,
            PollCommandListener pollListener) {
        if (profile.registersCommands()) {
            CommandListUpdateAction updater = jda.updateCommands();
            connect4Listener.registerCommands(updater);
            typeracerListener.registerCommands(updater);
            feedbackListener.registerCommands(updater);
            pollListener.registerCommands(updater);
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
