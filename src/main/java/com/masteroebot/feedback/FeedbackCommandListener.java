package com.masteroebot.feedback;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class FeedbackCommandListener extends ListenerAdapter {
    // Confirmation lines in early main-channel voice: earnest, "man"
    // everywhere, short fragments. Sampled from the first ~140 lines of
    // data/markov/1349083001275027559.chat.log ("proud of you man",
    // "we're here for you man", "Good one?", "you really are the top 1 pengi").
    static final List<String> REPLIES = List.of(
            "noted man, proud of you ngl",
            "good one? logged it man",
            "heard you man, writing it down",
            "say less, got it man",
            "we're here for you man, saved it",
            "bet man, feedback locked in",
            "ok? logged. appreciate you man",
            "top 1 feedback, saved it ngl",
            "got it man, keep em coming",
            "^^ noted, thanks man"
    );

    private final FeedbackStore store;

    public FeedbackCommandListener() {
        this(new FeedbackStore());
    }

    FeedbackCommandListener(FeedbackStore store) {
        this.store = store;
    }

    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. BotMain combines all listeners'
        // commands into a single updateCommands() action.
        updater.addCommands(
                Commands.slash("feedback", "Send feedback on the bot")
                        .addOption(OptionType.STRING, "message", "What should change, be added, or be fixed?", true)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"feedback".equals(event.getName())) {
            return;
        }

        OptionMapping messageOption = event.getOption("message");
        String message = messageOption == null ? "" : messageOption.getAsString();
        if (message.isBlank()) {
            event.reply("give me something to work with lol, message can't be empty.")
                    .setEphemeral(true).queue();
            return;
        }

        Guild guild = event.getGuild();
        try {
            store.save(
                    event.getUser().getId(),
                    event.getUser().getEffectiveName(),
                    guild == null ? null : guild.getId(),
                    guild == null ? null : guild.getName(),
                    event.getChannel().getId(),
                    message);
        } catch (Exception e) {
            System.err.println("Failed to save feedback: " + e.getMessage());
            event.reply("yo that didn't save, try again in a bit.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply(pickReply()).setEphemeral(true).queue();
    }

    static String pickReply() {
        return REPLIES.get(ThreadLocalRandom.current().nextInt(REPLIES.size()));
    }
}
