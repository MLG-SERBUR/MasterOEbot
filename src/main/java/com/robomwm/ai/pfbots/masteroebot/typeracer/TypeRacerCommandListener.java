package com.robomwm.ai.pfbots.masteroebot.typeracer;

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
    private final Map<Long, Map<Integer, TypeRacerGame>> gamesByChannel = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private int nextGameId = 1;

    public TypeRacerCommandListener() {
    }

    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. BotMain combines all listeners'
        // commands into a single updateCommands() action, because each
        // updateCommands() call replaces all global commands.
        updater.addCommands(
                Commands.slash("typeracer", "Start or join a typing race")
                        .addSubcommands(
                                new SubcommandData("start", "Create a new typing race lobby"),
                                new SubcommandData("join", "Join an existing race lobby"),
                                new SubcommandData("go", "Start the race countdown"),
                                new SubcommandData("abort", "Cancel the current race")
                        )
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
                        + "Use `/typeracer join` to join.\n"
                        + "Host: use `/typeracer go` to start the race.",
                gameId, event.getUser().getIdLong()
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
                // Never delete messages: wrong guesses stay visible, the
                // reminder simply stays up as feedback.
                event.getChannel().sendMessage(
                        String.format("<@%d> - that's not right, try again!", userId)
                ).queue();
            }
        }
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
}
