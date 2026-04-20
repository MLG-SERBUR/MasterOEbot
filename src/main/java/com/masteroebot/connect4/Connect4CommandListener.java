package com.masteroebot.connect4;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class Connect4CommandListener extends ListenerAdapter {
    private final Map<Long, Connect4Game> gamesByChannel = new ConcurrentHashMap<>();

    public void registerCommands(CommandListUpdateAction updater) {
        updater.addCommands(
                Commands.slash("connect4", "Start or play Connect 4")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player1", "First player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "player2", "Second player (required to start game)")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "move", "Move like F7 (used after game starts)")
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"connect4".equals(event.getName())) {
            return;
        }

        long channelId = event.getChannel().getIdLong();
        OptionMapping moveOption = event.getOption("move");

        if (moveOption != null) {
            processMove(event, channelId, moveOption.getAsString());
            return;
        }

        User p1 = optionUser(event.getOption("player1"));
        User p2 = optionUser(event.getOption("player2"));

        if (p1 == null || p2 == null) {
            event.reply("To start a game, pick two users: `/connect4 player1:@User1 player2:@User2`\nThen play with `/connect4 move:F7`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (p1.isBot() || p2.isBot()) {
            event.reply("Bots can't play. Please select two human users.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        Connect4Game game;
        try {
            game = new Connect4Game(p1.getIdLong(), p2.getIdLong());
        } catch (IllegalArgumentException ex) {
            event.reply(ex.getMessage()).setEphemeral(true).queue();
            return;
        }

        gamesByChannel.put(channelId, game);
        String message = String.format("Connect 4 started: <@%d> vs <@%d>%n%s%nTurn: <@%d> (use `/connect4 move:F7`)",
                game.getPlayerOneId(),
                game.getPlayerTwoId(),
                codeBlock(game.renderBoard()),
                game.getCurrentTurn());

        event.reply(message).queue();
    }

    private void processMove(SlashCommandInteractionEvent event, long channelId, String moveText) {
        Connect4Game game = gamesByChannel.get(channelId);
        if (game == null) {
            event.reply("No active game in this channel. Start one with `/connect4 player1:@User1 player2:@User2`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        Connect4Game.MoveResult result = game.makeMove(event.getUser().getIdLong(), moveText);

        if (result.status() == Connect4Game.Status.ERROR) {
            event.reply(result.message()).setEphemeral(true).queue();
            return;
        }

        StringBuilder reply = new StringBuilder();
        reply.append(String.format("Move `%s` accepted for <@%d>.%n", moveText.toUpperCase(), event.getUser().getIdLong()));
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

        event.reply(reply.toString()).queue();
    }

    private User optionUser(OptionMapping option) {
        return option == null ? null : option.getAsUser();
    }

    private String codeBlock(String text) {
        return "```\n" + text + "\n```";
    }
}
