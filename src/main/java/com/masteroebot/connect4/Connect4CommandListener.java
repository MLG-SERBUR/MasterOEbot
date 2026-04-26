package com.masteroebot.connect4;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.masteroebot.markov.MarkovConfig;
import com.masteroebot.markov.MarkovManager;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class Connect4CommandListener extends ListenerAdapter {
    private static final String PREFIX_COMMAND = "!connect4";

    private final boolean prefixFallbackEnabled;
    private final Map<Long, Connect4Game> gamesByChannel = new ConcurrentHashMap<>();
    private final MarkovManager markovManager;
    private final MarkovConfig markovConfig;
    private boolean markovAvailable = false;

    public Connect4CommandListener(boolean prefixFallbackEnabled) {
        this.prefixFallbackEnabled = prefixFallbackEnabled;
        this.markovManager = null;
        this.markovConfig = null;
    }

    public Connect4CommandListener(boolean prefixFallbackEnabled, MarkovManager markovManager, MarkovConfig markovConfig) {
        this.prefixFallbackEnabled = prefixFallbackEnabled;
        this.markovManager = markovManager;
        this.markovConfig = markovConfig;
    }

    public void setMarkovAvailable(boolean available) {
        this.markovAvailable = available;
    }

    public void registerCommands(CommandListUpdateAction updater) {
        updater.addCommands(
                Commands.slash("connect4", "Start or play Connect 4")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player1", "First player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player2", "Second player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "move", "Move like F7 (used after game starts)"),
                Commands.slash("markov", "Toggle Markov chain feature")
                        .addSubcommands(
                                new SubcommandData("toggle", "Toggle Markov on/off for this server"),
                                new SubcommandData("status", "Check Markov status for this server")
                        )
        ).queue(
                success -> System.out.println("Registered slash commands."),
                error -> System.err.println("Slash command registration failed. " + error.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"connect4".equals(event.getName()) && !"markov".equals(event.getName())) {
            return;
        }

        if ("markov".equals(event.getName())) {
            handleMarkovCommand(event);
            return;
        }

        long channelId = event.getChannel().getIdLong();
        OptionMapping moveOption = event.getOption("move");

        if (moveOption != null) {
            reply(event, processMove(event.getUser().getIdLong(), channelId, moveOption.getAsString(), false));
            return;
        }

        User p1 = optionUser(event.getOption("player1"));
        User p2 = optionUser(event.getOption("player2"));

        if (p1 == null || p2 == null) {
            reply(event, helpResponse(false));
            return;
        }

        reply(event, startGame(channelId, p1, p2, false));
    }

    private void handleMarkovCommand(SlashCommandInteractionEvent event) {
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
            boolean current = markovConfig.isEnabled(channelId);
            boolean newState = !current;
            markovConfig.setEnabled(channelId, newState);

            if (newState && markovManager.isEmpty(channelId)) {
                seedFromHistory(event, channelId);
            }

            event.reply("Markov feature " + (newState ? "enabled" : "disabled") + " for this channel.").queue();
        } else if ("status".equals(subcommand)) {
            boolean enabled = markovConfig.isEnabled(channelId);
            event.reply("Markov feature is currently " + (enabled ? "enabled" : "disabled") + " for this channel.").setEphemeral(true).queue();
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void seedFromHistory(SlashCommandInteractionEvent event, long channelId) {
        event.getChannel().getHistory().retrievePast(100).queue(messages -> {
            java.util.List<String> history = new java.util.ArrayList<>();
            for (net.dv8tion.jda.api.entities.Message msg : messages) {
                if (!msg.getAuthor().isBot() && !msg.getContentRaw().trim().isEmpty()) {
                    history.add(msg.getContentRaw().trim());
                }
            }
            if (!history.isEmpty()) {
                markovManager.seedFromHistory(channelId, history);
                event.getChannel().sendMessage("Brain seeded with " + history.size() + " messages from channel history.").queue();
            }
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        Message message = event.getMessage();
        String raw = message.getContentRaw();
        if (!raw.regionMatches(true, 0, PREFIX_COMMAND, 0, PREFIX_COMMAND.length())) {
            return;
        }

        String args = raw.substring(PREFIX_COMMAND.length()).trim();
        if (args.isEmpty()) {
            event.getChannel().sendMessage(helpResponse(true).message()).queue();
            return;
        }

        List<User> mentionedUsers = message.getMentions().getUsers();
        if (!mentionedUsers.isEmpty()) {
            if (mentionedUsers.size() != 2) {
                event.getChannel().sendMessage("Need exactly 2 mentioned users. Example: `!connect4 @User1 @User2`").queue();
                return;
            }

            event.getChannel().sendMessage(startGame(event.getChannel().getIdLong(), mentionedUsers.get(0), mentionedUsers.get(1), true).message()).queue();
            return;
        }

        String moveText = args.regionMatches(true, 0, "move", 0, 4)
                ? args.substring(4).trim()
                : args;
        event.getChannel().sendMessage(processMove(event.getAuthor().getIdLong(), event.getChannel().getIdLong(), moveText, true).message()).queue();
    }

    private CommandResponse startGame(long channelId, User p1, User p2, boolean prefixMode) {
        if (p1.isBot() || p2.isBot()) {
            return CommandResponse.ephemeral("Bots can't play. Please select two human users.");
        }

        Connect4Game game;
        try {
            game = new Connect4Game(p1.getIdLong(), p2.getIdLong());
        } catch (IllegalArgumentException ex) {
            return CommandResponse.ephemeral(ex.getMessage());
        }

        gamesByChannel.put(channelId, game);
        String message = String.format("Connect 4 started: <@%d> vs <@%d>%n%s%nTurn: <@%d> (use `%s`)",
                game.getPlayerOneId(),
                game.getPlayerTwoId(),
                codeBlock(game.renderBoard()),
                game.getCurrentTurn(),
                moveUsage(prefixMode));

        return CommandResponse.publicMessage(message);
    }

    private CommandResponse processMove(long userId, long channelId, String moveText, boolean prefixMode) {
        Connect4Game game = gamesByChannel.get(channelId);
        if (game == null) {
            return CommandResponse.ephemeral("No active game in this channel. Start one with " + startUsage(prefixMode) + ".");
        }

        Connect4Game.MoveResult result = game.makeMove(userId, moveText);

        if (result.status() == Connect4Game.Status.ERROR) {
            return CommandResponse.ephemeral(result.message());
        }

        StringBuilder reply = new StringBuilder();
        reply.append(String.format("Move `%s` accepted for <@%d>.%n", moveText.toUpperCase(), userId));
        reply.append(codeBlock(game.renderBoard())).append('\n');

        if (result.status() == Connect4Game.Status.WIN) {
            reply.append(String.format("🏆 Winner: <@%d>", game.getWinnerId()));
            gamesByChannel.remove(channelId);
        } else if (result.status() == Connect4Game.Status.DRAW) {
            reply.append("🤝 Draw.");
            gamesByChannel.remove(channelId);
        } else {
            reply.append(String.format("Turn: <@%d>", game.getCurrentTurn()));
        }

        return CommandResponse.publicMessage(reply.toString());
    }

    private CommandResponse helpResponse(boolean prefixMode) {
        StringBuilder message = new StringBuilder();
        message.append(String.format("Start game: %s%nPlay move: %s%n",
                startUsage(prefixMode),
                moveUsage(prefixMode)));

        if (prefixFallbackEnabled) {
            message.append("Slash also supported: `/connect4 player1:@User1 player2:@User2` and `/connect4 move:F7`.");
        } else {
            message.append("Slash only. Prefix fallback disabled because `MESSAGE_CONTENT` intent was unavailable.");
        }

        return CommandResponse.ephemeral(message.toString());
    }

    private void reply(SlashCommandInteractionEvent event, CommandResponse response) {
        event.reply(response.message()).setEphemeral(response.ephemeral()).queue();
    }

    private User optionUser(OptionMapping option) {
        return option == null ? null : option.getAsUser();
    }

    private String codeBlock(String text) {
        return "```\n" + text + "\n```";
    }

    private String startUsage(boolean prefixMode) {
        return prefixMode ? "`!connect4 @User1 @User2`" : "`/connect4 player1:@User1 player2:@User2`";
    }

    private String moveUsage(boolean prefixMode) {
        return prefixMode ? "`!connect4 F7` or `!connect4 move F7`" : "`/connect4 move:F7`";
    }

    private record CommandResponse(String message, boolean ephemeral) {
        static CommandResponse publicMessage(String message) {
            return new CommandResponse(message, false);
        }

        static CommandResponse ephemeral(String message) {
            return new CommandResponse(message, true);
        }
    }
}
