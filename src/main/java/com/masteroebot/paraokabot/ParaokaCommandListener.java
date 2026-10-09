package com.masteroebot.paraokabot;

import com.masteroebot.bot.BotSlashCommands;
import com.masteroebot.markov.MarkovConfig;
import com.masteroebot.markov.MarkovManager;
import com.masteroebot.markov.MarkovSeeder;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

/**
 * paraokabot slash commands. Only its own toggle on its own application.
 * Connect4 and all other games belong to MasterOEBot.
 */
public class ParaokaCommandListener extends ListenerAdapter implements BotSlashCommands {
    private final MarkovManager markovManager;
    private final MarkovConfig markovConfig;
    private boolean markovAvailable = false;

    public ParaokaCommandListener(MarkovManager markovManager, MarkovConfig markovConfig) {
        this.markovManager = markovManager;
        this.markovConfig = markovConfig;
    }

    @Override
    public void setMarkovAvailable(boolean available) {
        this.markovAvailable = available;
    }

    @Override
    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. The bot combines its listeners'
        // commands into a single updateCommands() action, because each
        // updateCommands() call replaces all global commands.
        updater.addCommands(
                Commands.slash("paraokabot", "paraokabot chat feature")
                        .addSubcommands(
                                new SubcommandData("toggle", "Toggle paraokabot on/off for this channel"),
                                new SubcommandData("status", "Check paraokabot status for this channel")
                        )
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if ("paraokabot".equals(event.getName())) {
            handleParaokaCommand(event);
        }
    }

    private void handleParaokaCommand(SlashCommandInteractionEvent event) {
        if (!markovAvailable || markovManager == null || markovConfig == null) {
            event.reply("Markov feature is not available (MESSAGE_CONTENT intent not granted).").setEphemeral(true).queue();
            return;
        }

        if (!event.isFromGuild()) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        long channelId = event.getChannel().getIdLong();
        String subcommand = event.getSubcommandName();

        if ("toggle".equals(subcommand)) {
            boolean current = markovConfig.isParaokaEnabled(channelId);
            boolean newState = !current;
            markovConfig.setParaokaEnabled(channelId, newState);

            if (newState && markovManager.isEmpty(channelId)) {
                MarkovSeeder.seedFromHistory(event, channelId, markovManager);
            }

            event.reply("paraokabot " + (newState ? "enabled" : "disabled") + " for this channel.").setEphemeral(true).queue();
        } else if ("status".equals(subcommand)) {
            boolean enabled = markovConfig.isParaokaEnabled(channelId);
            event.reply("paraokabot is currently " + (enabled ? "enabled" : "disabled")
                    + " for this channel.").setEphemeral(true).queue();
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }
}
