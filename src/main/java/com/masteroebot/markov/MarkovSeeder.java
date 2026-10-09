package com.masteroebot.markov;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * Shared channel-history seeding for slash-command toggles. Used by each
 * bot's own command listener when its chat feature is first enabled.
 */
public final class MarkovSeeder {
    private MarkovSeeder() {
    }

    public static void seedFromHistory(SlashCommandInteractionEvent event, long channelId, MarkovManager markovManager) {
        event.getChannel().getHistory().retrievePast(100).queue(messages -> {
            java.util.List<String> brainHistory = new java.util.ArrayList<>();
            markovManager.ensureAiLogInitialized(channelId);

            // History is newest first, so reverse for AI log
            for (int i = messages.size() - 1; i >= 0; i--) {
                net.dv8tion.jda.api.entities.Message msg = messages.get(i);
                String content = MarkovUtils.getDisplayNameContent(msg).trim();
                if (content.isEmpty()) continue;

                if (!msg.getAuthor().isBot()) {
                    brainHistory.add(content);
                    String authorName = msg.getMember() != null ? msg.getMember().getEffectiveName() : msg.getAuthor().getEffectiveName();
                    markovManager.appendToAiLog(channelId, authorName, content);
                } else if (msg.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
                    markovManager.appendBotMessageToAiLog(channelId, content);
                }
            }

            if (!brainHistory.isEmpty()) {
                markovManager.seedFromHistory(channelId, brainHistory);
                System.out.println("Brain and AI log for channel " + channelId + " seeded from history via command.");
                event.getHook().sendMessage("Brain seeded with " + brainHistory.size() + " messages from channel history.")
                        .setEphemeral(true)
                        .queue();
            }
        });
    }
}
