package com.masteroebot.masteroebot.connect4;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.masteroebot.bot.BotSlashCommands;
import com.masteroebot.markov.MarkovConfig;
import com.masteroebot.markov.MarkovManager;
import com.masteroebot.markov.MarkovSeeder;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class Connect4CommandListener extends ListenerAdapter implements BotSlashCommands {
    private final Map<Long, Map<Integer, Connect4Game>> gamesByChannel = new ConcurrentHashMap<>();
    private final AtomicInteger nextGameId = new AtomicInteger(1);
    private final MarkovManager markovManager;
    private final MarkovConfig markovConfig;
    private final com.masteroebot.markov.MarkovPollHandler pollHandler;
    private boolean markovAvailable = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Connect4CommandListener() {
        this.markovManager = null;
        this.markovConfig = null;
        this.pollHandler = null;
    }

    public Connect4CommandListener(MarkovManager markovManager, MarkovConfig markovConfig) {
        this.markovManager = markovManager;
        this.markovConfig = markovConfig;
        this.pollHandler = new com.masteroebot.markov.MarkovPollHandler(markovManager, markovConfig);
    }

    @Override
    public void setMarkovAvailable(boolean available) {
        this.markovAvailable = available;
    }

    @Override
    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. BotMain combines all listeners'
        // commands into a single updateCommands() action per bot, because each
        // updateCommands() call replaces all global commands.
        updater.addCommands(
                Commands.slash("connect4", "Start or play Connect 4")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player1", "First player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player2", "Second player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "move", "Move like F7 (used after game starts)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.INTEGER, "game", "Game number when multiple games are active"),
                Commands.slash("masteroebot", "MasterOEBot chat feature")
                        .addSubcommands(
                                new SubcommandData("toggle", "Toggle MasterOEBot on/off for this server"),
                                new SubcommandData("status", "Check MasterOEBot status for this server"),
                                new SubcommandData("short", "Toggle 1-3 token Markov training and output")
                        ),
                Commands.slash("markov", "Markov chain extras")
                        .addSubcommands(
                                new SubcommandData("poll", "Create a markov generated poll")
                                        .addOption(OptionType.STRING, "word", "Optional seed word", false)
                        ),
                Commands.slash("remind", "Set a reminder")
                        .addOption(OptionType.STRING, "time", "Time duration (e.g. 10m, 1h, 30s)", true)
                        .addOption(OptionType.STRING, "message", "What to remind you about", true)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"connect4".equals(event.getName()) && !"markov".equals(event.getName()) && !"masteroebot".equals(event.getName()) && !"remind".equals(event.getName())) {
            return;
        }

        if ("remind".equals(event.getName())) {
            handleRemindCommand(event);
            return;
        }

        if ("markov".equals(event.getName()) || "masteroebot".equals(event.getName())) {
            handleMarkovCommand(event);
            return;
        }

        long channelId = event.getChannel().getIdLong();
        OptionMapping moveOption = event.getOption("move");
        OptionMapping gameOption = event.getOption("game");
        long selfBotId = event.getJDA().getSelfUser().getIdLong();

        if (moveOption != null) {
            reply(event, processMove(event.getUser().getIdLong(), channelId, gameIdFromOption(gameOption), moveOption.getAsString(), selfBotId));
            return;
        }

        User p1 = optionUser(event.getOption("player1"));
        User p2 = optionUser(event.getOption("player2"));

        if (p1 == null || p2 == null) {
            reply(event, helpResponse());
            return;
        }

        reply(event, startGame(channelId, p1, p2, selfBotId));
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
                MarkovSeeder.seedFromHistory(event, channelId, markovManager);
            }

            event.reply("MasterOEBot " + (newState ? "enabled" : "disabled") + " for this channel.").setEphemeral(true).queue();
        } else if ("status".equals(subcommand)) {
            boolean enabled = markovConfig.isEnabled(channelId);
            boolean shortMessages = markovConfig.allowShortMessages(channelId);
            event.reply("MasterOEBot is currently " + (enabled ? "enabled" : "disabled")
                    + " for this channel. Short messages are "
                    + (shortMessages ? "enabled" : "disabled (4-token requirement)") + ".").setEphemeral(true).queue();
        } else if ("short".equals(subcommand)) {
            boolean current = markovConfig.allowShortMessages(channelId);
            boolean newState = !current;
            markovConfig.setAllowShortMessages(channelId, newState);
            markovManager.reloadBrain(channelId);

            event.reply("Short Markov messages " + (newState ? "enabled" : "disabled; 4-token requirement restored")
                    + " for this channel.").setEphemeral(true).queue();
        } else if ("poll".equals(subcommand)) {
            if (pollHandler != null) {
                pollHandler.handle(event);
            }
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private long parseDurationSeconds(String duration) {
        if (duration == null || duration.isBlank()) return -1;
        try {
            duration = duration.trim().toLowerCase();
            if (duration.endsWith("s")) return Long.parseLong(duration.substring(0, duration.length() - 1));
            if (duration.endsWith("m")) return Long.parseLong(duration.substring(0, duration.length() - 1)) * 60;
            if (duration.endsWith("h")) return Long.parseLong(duration.substring(0, duration.length() - 1)) * 3600;
            if (duration.endsWith("d")) return Long.parseLong(duration.substring(0, duration.length() - 1)) * 86400;
            return Long.parseLong(duration);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void handleRemindCommand(SlashCommandInteractionEvent event) {
        String timeStr = event.getOption("time").getAsString();
        String message = event.getOption("message").getAsString();
        long seconds = parseDurationSeconds(timeStr);
        
        if (seconds <= 0) {
            event.reply("Invalid time format. Use something like 10m, 1h, 30s.").setEphemeral(true).queue();
            return;
        }
        
        event.reply("Reminder set for " + timeStr + ".").setEphemeral(false).queue();
        
        net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion channel = event.getChannel();
        long userId = event.getUser().getIdLong();
        
        scheduler.schedule(() -> {
            channel.sendMessage("<@" + userId + ">, reminder: " + message).queue(
                    success -> {},
                    error -> System.err.println("Failed to send reminder: " + error.getMessage())
            );
        }, seconds, TimeUnit.SECONDS);
    }

    private CommandResponse startGame(long channelId, User p1, User p2, long selfBotId) {
        Connect4Game game = new Connect4Game(p1.getIdLong(), p2.getIdLong());
        int gameId = nextGameId.getAndIncrement();
        gamesByChannel.computeIfAbsent(channelId, ignored -> new ConcurrentHashMap<>()).put(gameId, game);

        StringBuilder message = new StringBuilder(String.format("Connect 4 #%d started: <@%d> vs <@%d>%n%s%n",
                gameId,
                game.getPlayerOneId(),
                game.getPlayerTwoId(),
                codeBlock(game.renderBoard())));

        if (game.getCurrentTurn() == selfBotId) {
            appendBotMoves(message, game, channelId, gameId, selfBotId);
        } else {
            message.append(turnMessage(gameId, game));
        }

        return CommandResponse.publicMessage(message.toString());
    }

    private CommandResponse processMove(long userId, long channelId, Integer gameId, String moveText, long selfBotId) {
        GameSelection selection = selectGame(userId, channelId, gameId);
        if (!selection.valid()) {
            return CommandResponse.ephemeral(selection.errorMessage());
        }

        Connect4Game game = selection.game();
        int selectedGameId = selection.gameId();
        if (moveText == null || moveText.isBlank()) {
            return CommandResponse.ephemeral("Missing move. Use " + moveUsage() + ".");
        }

        Connect4Game.MoveResult result = game.makeMove(userId, moveText);

        if (result.status() == Connect4Game.Status.ERROR) {
            return CommandResponse.ephemeral(result.message());
        }

        StringBuilder reply = new StringBuilder();
        reply.append(String.format("Game #%d move `%s` accepted for <@%d>.%n", selectedGameId, moveText.toUpperCase(), userId));
        reply.append(codeBlock(game.renderBoard())).append('\n');

        if (appendFinishedOrDraw(reply, game, result, channelId, selectedGameId)) {
            return CommandResponse.publicMessage(reply.toString());
        }

        if (game.getCurrentTurn() == selfBotId) {
            reply.append('\n');
            appendBotMoves(reply, game, channelId, selectedGameId, selfBotId);
        } else {
            reply.append(turnMessage(selectedGameId, game));
        }

        return CommandResponse.publicMessage(reply.toString());
    }

    private GameSelection selectGame(long userId, long channelId, Integer requestedGameId) {
        Map<Integer, Connect4Game> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null || channelGames.isEmpty()) {
            return GameSelection.error("No active game in this channel. Start one with " + startUsage() + ".");
        }

        if (requestedGameId != null) {
            Connect4Game requestedGame = channelGames.get(requestedGameId);
            if (requestedGame == null) {
                return GameSelection.error("No active game #" + requestedGameId + " in this channel.");
            }
            return GameSelection.success(requestedGameId, requestedGame);
        }

        List<Map.Entry<Integer, Connect4Game>> playableGames = channelGames.entrySet().stream()
                .filter(entry -> entry.getValue().getPlayerOneId() == userId || entry.getValue().getPlayerTwoId() == userId)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (playableGames.isEmpty()) {
            return GameSelection.error("You're not in any active game in this channel.");
        }
        if (playableGames.size() > 1) {
            return GameSelection.error("Multiple active games match. Specify game number with " + gameUsage() + ".");
        }

        Map.Entry<Integer, Connect4Game> selected = playableGames.get(0);
        return GameSelection.success(selected.getKey(), selected.getValue());
    }

    private void appendBotMoves(StringBuilder reply, Connect4Game game, long channelId, int gameId, long selfBotId) {
        int moves = 0;
        while (!game.isFinished() && game.getCurrentTurn() == selfBotId) {
            String botMove = game.chooseBotMove();
            if (botMove == null) {
                reply.append("Bot has no legal move.");
                removeGame(channelId, gameId);
                return;
            }

            Connect4Game.MoveResult botResult = game.makeMove(selfBotId, botMove);
            moves++;

            if (game.getCurrentTurn() == selfBotId && !game.isFinished()) {
                reply.append(String.format("Bot move `%s`.%n", botMove));
                continue;
            }

            if (moves > 1) {
                reply.append(String.format("Bot played %d moves; last move `%s`.%n", moves, botMove));
            } else {
                reply.append(String.format("Bot move `%s`.%n", botMove));
            }
            reply.append(codeBlock(game.renderBoard())).append('\n');

            if (!appendFinishedOrDraw(reply, game, botResult, channelId, gameId)) {
                reply.append(turnMessage(gameId, game));
            }
        }
    }

    private boolean appendFinishedOrDraw(StringBuilder reply, Connect4Game game, Connect4Game.MoveResult result, long channelId, int gameId) {
        if (result.status() == Connect4Game.Status.WIN) {
            reply.append(String.format("Game #%d winner: <@%d>", gameId, game.getWinnerId()));
            removeGame(channelId, gameId);
            return true;
        } else if (result.status() == Connect4Game.Status.DRAW) {
            reply.append(String.format("Game #%d draw.", gameId));
            removeGame(channelId, gameId);
            return true;
        } else {
            return false;
        }
    }

    private void removeGame(long channelId, int gameId) {
        Map<Integer, Connect4Game> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            return;
        }

        channelGames.remove(gameId);
        if (channelGames.isEmpty()) {
            gamesByChannel.remove(channelId);
        }
    }

    private CommandResponse helpResponse() {
        return CommandResponse.ephemeral(String.format("Start game: %s%nPlay move: %s",
                startUsage(),
                moveUsage()));
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

    private String startUsage() {
        return "`/connect4 player1:@User1 player2:@User2`";
    }

    private String moveUsage() {
        return "`/connect4 move:F7 game:1`";
    }

    private String gameUsage() {
        return "`game:1`";
    }

    private String turnMessage(int gameId, Connect4Game game) {
        return String.format("Game #%d turn: <@%d> (use %s)", gameId, game.getCurrentTurn(), moveUsage());
    }

    private Integer gameIdFromOption(OptionMapping option) {
        if (option == null) {
            return null;
        }
        long value = option.getAsLong();
        if (value < 1 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private record CommandResponse(String message, boolean ephemeral) {
        static CommandResponse publicMessage(String message) {
            return new CommandResponse(message, false);
        }

        static CommandResponse ephemeral(String message) {
            return new CommandResponse(message, true);
        }
    }

    private record GameSelection(boolean valid, int gameId, Connect4Game game, String errorMessage) {
        static GameSelection success(int gameId, Connect4Game game) {
            return new GameSelection(true, gameId, game, null);
        }

        static GameSelection error(String message) {
            return new GameSelection(false, -1, null, message);
        }
    }
}
