package com.masteroebot.typeracer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class TypeRacerCommandListener extends ListenerAdapter {
    private static final String PREFIX_COMMAND = "!typeracer";

    private final boolean prefixFallbackEnabled;
    private final Map<Long, Map<Integer, TypeRacerGame>> gamesByChannel = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private int nextGameId = 1;

    public TypeRacerCommandListener(boolean prefixFallbackEnabled) {
        this.prefixFallbackEnabled = prefixFallbackEnabled;
    }

    public void registerCommands(CommandListUpdateAction updater) {
        updater.addCommands(
                Commands.slash("typeracer", "Start or join a typing race")
                        .addSubcommands(
                                new SubcommandData("start", "Create a new typing race lobby"),
                                new SubcommandData("join", "Join an existing race lobby"),
                                new SubcommandData("go", "Start the race countdown"),
                                new SubcommandData("abort", "Cancel the current race")
                        )
        ).queue(
                success -> System.out.println("Registered typeracer slash commands."),
                error -> System.err.println("Typeracer slash command registration failed. " + error.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"typeracer".equals(event.getName())) {
            return;
        }

        String subcommand = event.getSubcommandName();
        long channelId = event.getChannel().getIdLong();

        switch (subcommand) {
            case "start" -> startGame(event);
            case "join" -> joinGame(event, channelId);
            case "go" -> goRace(event, channelId);
            case "abort" -> abortGame(event, channelId);
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void startGame(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);

        if (channelGames != null && channelGames.values().stream()
                .anyMatch(g -> g.getState() != TypeRacerGame.State.FINISHED)) {
            event.reply("A race is already in progress in this channel.").setEphemeral(true).queue();
            return;
        }

        int gameId = nextGameId++;
        TypeRacerGame game = new TypeRacerGame(event.getUser().getIdLong(), channelId, gameId);
        gamesByChannel.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>()).put(gameId, game);

        event.reply(String.format(
                "**TypeRacer #%d** lobby created!\n"
                        + "Players: <@%d> (host)\n"
                        + "Use `%s` or `/typeracer join` to join.\n"
                        + "Host: use `%s` or `/typeracer go` to start the race.",
                gameId, event.getUser().getIdLong(),
                PREFIX_COMMAND + " join",
                PREFIX_COMMAND + " go"
        )).queue();
    }

    private void joinGame(SlashCommandInteractionEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.reply("No race lobby open. Start one with `/typeracer start`.").setEphemeral(true).queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() == TypeRacerGame.State.WAITING)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.reply("No race lobby available to join.").setEphemeral(true).queue();
            return;
        }

        if (game.isPlayer(event.getUser().getIdLong())) {
            event.reply("You're already in the lobby.").setEphemeral(true).queue();
            return;
        }

        game.addPlayer(event.getUser().getIdLong());
        StringBuilder players = new StringBuilder();
        for (long p : game.getPlayers()) {
            players.append("- <@").append(p).append(">\n");
        }
        event.reply(String.format("**TypeRacer #%d** - Player joined!\nPlayers:\n%sUse `/typeracer go` when ready.",
                game.getGameId(), players)).queue();
    }

    private void goRace(SlashCommandInteractionEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.reply("No race lobby. Start one with `/typeracer start`.").setEphemeral(true).queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() == TypeRacerGame.State.WAITING)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.reply("No lobby found to start.").setEphemeral(true).queue();
            return;
        }

        if (game.getHostId() != event.getUser().getIdLong()) {
            event.reply("Only the host can start the race.").setEphemeral(true).queue();
            return;
        }

        if (game.getPlayers().size() < 1) {
            event.reply("Need at least 1 player to start.").setEphemeral(true).queue();
            return;
        }

        game.setState(TypeRacerGame.State.COUNTDOWN);
        event.reply("Race starting in 3...").queue();

        scheduler.schedule(() -> {
            event.getHook().editOriginal("2...").queue();
        }, 1, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            event.getHook().editOriginal("1...").queue();
        }, 2, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            game.startRace();
            StringBuilder sb = new StringBuilder();
            sb.append("**GO!** Type the following text in the chat:\n\n");
            sb.append("```\n").append(game.getTargetText()).append("\n```\n");
            sb.append("First to type it correctly wins!");
            event.getHook().editOriginal(sb.toString()).queue();
        }, 3, TimeUnit.SECONDS);
    }

    private void abortGame(SlashCommandInteractionEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.reply("No race to abort.").setEphemeral(true).queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() != TypeRacerGame.State.FINISHED)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.reply("No active race to abort.").setEphemeral(true).queue();
            return;
        }

        if (game.getHostId() != event.getUser().getIdLong()) {
            event.reply("Only the host can abort the race.").setEphemeral(true).queue();
            return;
        }

        game.finish();
        removeGame(channelId, game.getGameId());
        event.reply("Race aborted.").queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        String raw = event.getMessage().getContentRaw();
        long channelId = event.getChannel().getIdLong();
        long userId = event.getAuthor().getIdLong();

        if (raw.regionMatches(true, 0, PREFIX_COMMAND, 0, PREFIX_COMMAND.length())) {
            handlePrefixCommand(event);
            return;
        }

        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) return;

        for (TypeRacerGame game : channelGames.values()) {
            if (game.getState() != TypeRacerGame.State.RACING) continue;
            if (!game.isPlayer(userId)) continue;
            if (game.hasFinished(userId)) continue;

            TypeRacerGame.PlayerResult result = game.submit(userId, raw);
            if (result == TypeRacerGame.PlayerResult.ACCEPTED) {
                long rank = game.getResults().stream()
                        .filter(r -> r.userId() == userId)
                        .count();
                event.getChannel().sendMessage(
                        String.format("<@%d> finished! (#%d) WPM: %.1f",
                                userId, rank,
                                game.getResults().stream()
                                        .filter(r -> r.userId() == userId)
                                        .findFirst()
                                        .map(TypeRacerGame.RacerResult::wpm)
                                        .orElse(0.0))
                ).queue();

                if (game.allFinished()) {
                    announceResults(event, game);
                }
            } else if (result == TypeRacerGame.PlayerResult.WRONG_TEXT) {
                event.getMessage().delete().queue();
                event.getChannel().sendMessage(
                        String.format("<@%d> - that's not right, try again!", userId)
                ).queue(q -> {
                    try {
                        Thread.sleep(3000);
                        q.delete().queue();
                    } catch (InterruptedException ignored) {
                    }
                });
            }
        }
    }

    private void handlePrefixCommand(MessageReceivedEvent event) {
        String raw = event.getMessage().getContentRaw();
        String args = raw.substring(PREFIX_COMMAND.length()).trim().toLowerCase();
        long channelId = event.getChannel().getIdLong();

        if (args.isEmpty() || "help".equals(args)) {
            event.getChannel().sendMessage(helpMessage()).queue();
            return;
        }

        if (args.startsWith("join")) {
            joinGamePrefix(event, channelId);
        } else if (args.startsWith("go")) {
            goRacePrefix(event, channelId);
        } else if (args.startsWith("abort")) {
            abortGamePrefix(event, channelId);
        } else if (args.startsWith("start")) {
            startGamePrefix(event);
        } else {
            event.getChannel().sendMessage(helpMessage()).queue();
        }
    }

    private void startGamePrefix(MessageReceivedEvent event) {
        long channelId = event.getChannel().getIdLong();
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);

        if (channelGames != null && channelGames.values().stream()
                .anyMatch(g -> g.getState() != TypeRacerGame.State.FINISHED)) {
            event.getChannel().sendMessage("A race is already in progress.").queue();
            return;
        }

        int gameId = nextGameId++;
        TypeRacerGame game = new TypeRacerGame(event.getAuthor().getIdLong(), channelId, gameId);
        gamesByChannel.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>()).put(gameId, game);

        event.getChannel().sendMessage(String.format(
                "**TypeRacer #%d** lobby created!\nPlayers: <@%d> (host)\n"
                        + "Use `%s join` to join. Host: `%s go` to start.",
                gameId, event.getAuthor().getIdLong(),
                PREFIX_COMMAND, PREFIX_COMMAND
        )).queue();
    }

    private void joinGamePrefix(MessageReceivedEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.getChannel().sendMessage("No race lobby. Start one with `" + PREFIX_COMMAND + " start`.").queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() == TypeRacerGame.State.WAITING)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.getChannel().sendMessage("No lobby available to join.").queue();
            return;
        }

        if (game.isPlayer(event.getAuthor().getIdLong())) {
            event.getChannel().sendMessage("You're already in.").queue();
            return;
        }

        game.addPlayer(event.getAuthor().getIdLong());
        StringBuilder players = new StringBuilder();
        for (long p : game.getPlayers()) {
            players.append("- <@").append(p).append(">\n");
        }
        event.getChannel().sendMessage(String.format("**TypeRacer #%d** - Player joined!\nPlayers:\n%sHost: `%s go`",
                game.getGameId(), players, PREFIX_COMMAND)).queue();
    }

    private void goRacePrefix(MessageReceivedEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.getChannel().sendMessage("No lobby. Start one with `" + PREFIX_COMMAND + " start`.").queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() == TypeRacerGame.State.WAITING)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.getChannel().sendMessage("No lobby found.").queue();
            return;
        }

        if (game.getHostId() != event.getAuthor().getIdLong()) {
            event.getChannel().sendMessage("Only the host can start.").queue();
            return;
        }

        game.setState(TypeRacerGame.State.COUNTDOWN);
        event.getChannel().sendMessage("Race starting in 3...").queue();

        scheduler.schedule(() -> {
            event.getChannel().sendMessage("2...").queue();
        }, 1, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            event.getChannel().sendMessage("1...").queue();
        }, 2, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            game.startRace();
            event.getChannel().sendMessage(String.format(
                    "**GO!** Type the following:\n```\n%s\n```\nFirst to type it correctly wins!",
                    game.getTargetText()
            )).queue();
        }, 3, TimeUnit.SECONDS);
    }

    private void abortGamePrefix(MessageReceivedEvent event, long channelId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames == null) {
            event.getChannel().sendMessage("No race to abort.").queue();
            return;
        }

        TypeRacerGame game = channelGames.values().stream()
                .filter(g -> g.getState() != TypeRacerGame.State.FINISHED)
                .findFirst()
                .orElse(null);

        if (game == null) {
            event.getChannel().sendMessage("No active race.").queue();
            return;
        }

        if (game.getHostId() != event.getAuthor().getIdLong()) {
            event.getChannel().sendMessage("Only the host can abort.").queue();
            return;
        }

        game.finish();
        removeGame(channelId, game.getGameId());
        event.getChannel().sendMessage("Race aborted.").queue();
    }

    private void announceResults(MessageReceivedEvent event, TypeRacerGame game) {
        List<TypeRacerGame.RacerResult> results = game.getResults();
        StringBuilder sb = new StringBuilder("**Race finished!**\n\n");
        String[] medals = {"1st", "2nd", "3rd"};
        for (int i = 0; i < results.size(); i++) {
            TypeRacerGame.RacerResult r = results.get(i);
            String place = i < medals.length ? medals[i] : "#" + (i + 1);
            sb.append(String.format("**%s** <@%d> - %.1f WPM (%.1fs)\n",
                    place, r.userId(), r.wpm(), r.elapsedMillis() / 1000.0));
        }
        event.getChannel().sendMessage(sb.toString()).queue();
        game.finish();
        removeGame(game.getChannelId(), game.getGameId());
    }

    private void removeGame(long channelId, int gameId) {
        Map<Integer, TypeRacerGame> channelGames = gamesByChannel.get(channelId);
        if (channelGames != null) {
            channelGames.remove(gameId);
            if (channelGames.isEmpty()) {
                gamesByChannel.remove(channelId);
            }
        }
    }

    private String helpMessage() {
        return "**TypeRacer** - Race to type text the fastest!\n\n"
                + "Slash commands:\n"
                + "`/typeracer start` - Create a lobby\n"
                + "`/typeracer join` - Join a lobby\n"
                + "`/typeracer go` - Start the race (host only)\n"
                + "`/typeracer abort` - Cancel the race (host only)\n\n"
                + "Prefix commands:\n"
                + "`" + PREFIX_COMMAND + " start` - Create a lobby\n"
                + "`" + PREFIX_COMMAND + " join` - Join a lobby\n"
                + "`" + PREFIX_COMMAND + " go` - Start the race\n"
                + "`" + PREFIX_COMMAND + " abort` - Cancel the race";
    }
}
