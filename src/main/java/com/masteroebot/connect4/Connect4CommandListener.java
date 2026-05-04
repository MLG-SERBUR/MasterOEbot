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
    private final com.masteroebot.markov.MarkovPollHandler pollHandler;
    private boolean markovAvailable = false;

    public Connect4CommandListener(boolean prefixFallbackEnabled) {
        this.prefixFallbackEnabled = prefixFallbackEnabled;
        this.markovManager = null;
        this.markovConfig = null;
        this.pollHandler = null;
    }

    public Connect4CommandListener(boolean prefixFallbackEnabled, MarkovManager markovManager, MarkovConfig markovConfig) {
        this.prefixFallbackEnabled = prefixFallbackEnabled;
        this.markovManager = markovManager;
        this.markovConfig = markovConfig;
        this.pollHandler = new com.masteroebot.markov.MarkovPollHandler(markovManager);
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
                                new SubcommandData("status", "Check Markov status for this server"),
                                new SubcommandData("short", "Toggle 1-3 token Markov training and output"),
                                new SubcommandData("poll", "Create a markov generated poll")
                                        .addOption(OptionType.STRING, "word", "Optional seed word", false)
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
        long selfBotId = event.getJDA().getSelfUser().getIdLong();

        if (moveOption != null) {
            reply(event, processMove(event.getUser().getIdLong(), channelId, moveOption.getAsString(), false, selfBotId));
            return;
        }

        User p1 = optionUser(event.getOption("player1"));
        User p2 = optionUser(event.getOption("player2"));

        if (p1 == null || p2 == null) {
            reply(event, helpResponse(false));
            return;
        }

        reply(event, startGame(channelId, p1, p2, false, selfBotId));
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
            boolean shortMessages = markovConfig.allowShortMessages(channelId);
            event.reply("Markov feature is currently " + (enabled ? "enabled" : "disabled")
                    + " for this channel. Short messages are "
                    + (shortMessages ? "enabled" : "disabled (4-token requirement)") + ".").setEphemeral(true).queue();
        } else if ("short".equals(subcommand)) {
            boolean current = markovConfig.allowShortMessages(channelId);
            boolean newState = !current;
            markovConfig.setAllowShortMessages(channelId, newState);
            markovManager.reloadBrain(channelId);

            event.reply("Short Markov messages " + (newState ? "enabled" : "disabled; 4-token requirement restored")
                    + " for this channel.").queue();
        } else if ("poll".equals(subcommand)) {
            if (pollHandler != null) {
                pollHandler.handle(event);
            }
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void seedFromHistory(SlashCommandInteractionEvent event, long channelId) {
        event.getChannel().getHistory().retrievePast(100).queue(messages -> {
            java.util.List<String> history = new java.util.ArrayList<>();
            for (net.dv8tion.jda.api.entities.Message msg : messages) {
                if (!msg.getAuthor().isBot() && !msg.getContentDisplay().trim().isEmpty()) {
                    history.add(msg.getContentDisplay().trim());
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

            long selfBotId = event.getJDA().getSelfUser().getIdLong();
            event.getChannel().sendMessage(startGame(event.getChannel().getIdLong(), mentionedUsers.get(0), mentionedUsers.get(1), true, selfBotId).message()).queue();
            return;
        }

        String moveText = args.regionMatches(true, 0, "move", 0, 4)
                ? args.substring(4).trim()
                : args;
        long selfBotId = event.getJDA().getSelfUser().getIdLong();
        event.getChannel().sendMessage(processMove(event.getAuthor().getIdLong(), event.getChannel().getIdLong(), moveText, true, selfBotId).message()).queue();
    }

    private CommandResponse startGame(long channelId, User p1, User p2, boolean prefixMode, long selfBotId) {
        if (isUnsupportedBot(p1, selfBotId) || isUnsupportedBot(p2, selfBotId)) {
            return CommandResponse.ephemeral("Only this bot can be selected as a bot player.");
        }

        Connect4Game game;
        try {
            game = new Connect4Game(p1.getIdLong(), p2.getIdLong());
        } catch (IllegalArgumentException ex) {
            return CommandResponse.ephemeral(ex.getMessage());
        }

        gamesByChannel.put(channelId, game);
        StringBuilder message = new StringBuilder(String.format("Connect 4 started: <@%d> vs <@%d>%n%s%n",
                game.getPlayerOneId(),
                game.getPlayerTwoId(),
                codeBlock(game.renderBoard())));

        if (game.getCurrentTurn() == selfBotId) {
            appendBotMove(message, game, channelId, prefixMode, selfBotId);
        } else {
            message.append(String.format("Turn: <@%d> (use %s)", game.getCurrentTurn(), moveUsage(prefixMode)));
        }

        return CommandResponse.publicMessage(message.toString());
    }

    private CommandResponse processMove(long userId, long channelId, String moveText, boolean prefixMode, long selfBotId) {
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

        if (appendFinishedOrDraw(reply, game, result, channelId)) {
            return CommandResponse.publicMessage(reply.toString());
        }

        if (game.getCurrentTurn() == selfBotId) {
            reply.append('\n');
            appendBotMove(reply, game, channelId, prefixMode, selfBotId);
        } else {
            reply.append(String.format("Turn: <@%d>", game.getCurrentTurn()));
        }

        return CommandResponse.publicMessage(reply.toString());
    }

    private void appendBotMove(StringBuilder reply, Connect4Game game, long channelId, boolean prefixMode, long selfBotId) {
        String botMove = game.chooseBotMove();
        if (botMove == null) {
            reply.append("Bot has no legal move.");
            gamesByChannel.remove(channelId);
            return;
        }

        Connect4Game.MoveResult botResult = game.makeMove(selfBotId, botMove);
        reply.append(String.format("Bot move `%s`.%n", botMove));
        reply.append(codeBlock(game.renderBoard())).append('\n');

        if (!appendFinishedOrDraw(reply, game, botResult, channelId)) {
            reply.append(String.format("Turn: <@%d> (use %s)", game.getCurrentTurn(), moveUsage(prefixMode)));
        }
    }

    private boolean appendFinishedOrDraw(StringBuilder reply, Connect4Game game, Connect4Game.MoveResult result, long channelId) {
        if (result.status() == Connect4Game.Status.WIN) {
            reply.append(String.format("🏆 Winner: <@%d>", game.getWinnerId()));
            gamesByChannel.remove(channelId);
            return true;
        } else if (result.status() == Connect4Game.Status.DRAW) {
            reply.append("🤝 Draw.");
            gamesByChannel.remove(channelId);
            return true;
        } else {
            return false;
        }
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

    private boolean isUnsupportedBot(User user, long selfBotId) {
        return user.isBot() && user.getIdLong() != selfBotId;
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
