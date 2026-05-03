package com.masteroebot.markov;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReference;
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
    private final Map<Long, Deque<Long>> recentMessagesByChannel = new HashMap<>();
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?\\d+>|<@&\\d+>|<#\\d+>");
    private static final long RESPONSE_DAMPENING_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final double RESPONSE_DAMPENING_STEP = 0.05;
    private static final double MIN_RESPONSE_CHANCE = 0.50;

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

    private String escapeMassMentions(String text) {
        if (text == null) return "";
        return text.replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere");
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

        boolean responseAllowed = shouldRespondAfterDampening(channelId);

        boolean isBot = message.getAuthor().isBot();

        if (!isBot) {
            manager.train(channelId, content);
            manager.appendToBrain(channelId, content);
        }

        String botName = jda.getSelfUser().getName().toLowerCase();
        String lowerContent = content.toLowerCase();

        boolean directlyAddressed = lowerContent.contains(botName) || isReplyToSelf(message);

        if (responseAllowed && (directlyAddressed || rand.nextDouble() < 0.01)) {
            sendMarkovReplies(event, channelId, content);
            return;
        }

        MessageReference reference = message.getMessageReference();
        if (reference != null && message.getReferencedMessage() == null) {
            reference.resolve().queue(referenced -> {
                if (responseAllowed && isMessageFromSelf(referenced)) {
                    sendMarkovReplies(event, channelId, content);
                }
            });
        }
    }

    private synchronized boolean shouldRespondAfterDampening(long channelId) {
        long now = System.currentTimeMillis();
        Deque<Long> recentMessages = recentMessagesByChannel.computeIfAbsent(channelId, id -> new ArrayDeque<>());
        while (!recentMessages.isEmpty() && now - recentMessages.peekFirst() >= RESPONSE_DAMPENING_WINDOW_MS) {
            recentMessages.removeFirst();
        }

        recentMessages.addLast(now);
        double responseChance = Math.max(MIN_RESPONSE_CHANCE, 1.0 - recentMessages.size() * RESPONSE_DAMPENING_STEP);
        return rand.nextDouble() < responseChance;
    }

    private void sendMarkovReplies(MessageReceivedEvent event, long channelId, String content) {
        String reply = escapeMassMentions(sanitizeOutput(generateReplyWithSeed(channelId, content)));
        if (!reply.isEmpty()) {
            event.getChannel().sendMessage(reply)
                    .setAllowedMentions(Collections.emptyList())
                    .queue();

            if (rand.nextDouble() < 0.1) {
                scheduler.schedule(() -> {
                    String secondReply = escapeMassMentions(sanitizeOutput(generateReplyWithSeed(channelId, content)));
                    if (!secondReply.isEmpty()) {
                        event.getChannel().sendMessage(secondReply)
                                .setAllowedMentions(Collections.emptyList())
                                .queue();
                    }
                }, 2 + rand.nextInt(5), TimeUnit.SECONDS);
            }
        }
    }

    private boolean isReplyToSelf(Message message) {
        return isMessageFromSelf(message.getReferencedMessage());
    }

    private boolean isMessageFromSelf(Message message) {
        return message != null
                && jda != null
                && jda.getSelfUser() != null
                && message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong();
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
