package com.masteroebot.markov;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MarkovListener extends ListenerAdapter {
    private final MarkovManager manager;
    private final MarkovConfig config;
    private JDA jda;
    private final Random rand = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?\\d+>|<@&\\d+>|<#\\d+>");

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda) {
        this.manager = manager;
        this.config = config;
        this.jda = jda;
    }

    public void setJDA(JDA jda) {
        this.jda = jda;
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return MENTION_PATTERN.matcher(text).replaceAll("");
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;

        long channelId = event.getChannel().getIdLong();

        if (!config.isEnabled(channelId)) return;

        Message message = event.getMessage();

        if (message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) return;

        manager.loadBrain(channelId);

        String content = message.getContentDisplay();

        if (content == null || content.trim().isEmpty()) return;

        boolean isBot = message.getAuthor().isBot();

        if (!isBot) {
            manager.train(channelId, content);
            manager.appendToBrain(channelId, content);
        }

        String botName = jda.getSelfUser().getName().toLowerCase();
        String lowerContent = content.toLowerCase();

        boolean mentioned = lowerContent.contains(botName);

        boolean shouldReply = mentioned || rand.nextDouble() < 0.05;

        if (shouldReply) {
            String reply = sanitizeOutput(generateReplyWithSeed(channelId, content));
            if (!reply.isEmpty()) {
                event.getChannel().sendMessage(reply).queue();

                if (rand.nextDouble() < 0.1) {
                    scheduler.schedule(() -> {
                        String secondReply = sanitizeOutput(generateReplyWithSeed(channelId, content));
                        if (!secondReply.isEmpty()) {
                            event.getChannel().sendMessage(secondReply).queue();
                        }
                    }, 2 + rand.nextInt(5), TimeUnit.SECONDS);
                }
            }
        }
    }

    private String generateReplyWithSeed(long channelId, String originalMessage) {
        if (rand.nextDouble() < 0.1) {
            String[] words = originalMessage.split("\\s+");
            String[] validWords = Arrays.stream(words)
                    .filter(w -> w.length() > 2)
                    .toArray(String[]::new);
            if (validWords.length > 0) {
                String seedWord = validWords[rand.nextInt(validWords.length)];
                return manager.generateReply(channelId, seedWord);
            }
        }
        return manager.generateReply(channelId);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
