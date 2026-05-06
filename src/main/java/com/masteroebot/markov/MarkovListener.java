package com.masteroebot.markov;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReference;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkovListener extends ListenerAdapter {
    private final MarkovManager manager;
    private final MarkovConfig config;
    private JDA jda;
    private final Random rand = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, Deque<Long>> recentMessagesByChannel = new HashMap<>();
    private final AtomicInteger messageCount = new AtomicInteger(100);
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?\\d+>|<@&\\d+>|<#\\d+>");
    private static final Pattern CUSTOM_EMOJI_NAME_PATTERN = Pattern.compile(":([A-Za-z0-9_]{2,32}):");
    private static final long RESPONSE_DAMPENING_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final int RESPONSE_DAMPENING_FREE_MESSAGES = 2;
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

    private String resolveGuildEmoji(MessageReceivedEvent event, String text) {
        if (text == null || text.isEmpty()) return "";

        Matcher matcher = CUSTOM_EMOJI_NAME_PATTERN.matcher(text);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            List<RichCustomEmoji> emojis = event.getGuild().getEmojisByName(matcher.group(1), false);
            if (emojis.isEmpty()) {
                continue;
            }

            String replacement = emojis.stream()
                    .filter(RichCustomEmoji::isAvailable)
                    .findFirst()
                    .orElse(emojis.get(0))
                    .getAsMention();
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
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
            
            if (messageCount.incrementAndGet() >= 100) {
                messageCount.set(0);
                String statusMsg = escapeMassMentions(sanitizeOutput(manager.generateReply(channelId)));
                if (statusMsg != null && !statusMsg.trim().isEmpty()) {
                    if (statusMsg.length() > 128) {
                        statusMsg = statusMsg.substring(0, 128);
                    }
                    if (jda != null) {
                        jda.getPresence().setActivity(Activity.customStatus(statusMsg));
                    }
                }
            }
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
        int dampenedMessages = Math.max(0, recentMessages.size() - RESPONSE_DAMPENING_FREE_MESSAGES);
        double responseChance = Math.max(MIN_RESPONSE_CHANCE, 1.0 - dampenedMessages * RESPONSE_DAMPENING_STEP);
        return rand.nextDouble() < responseChance;
    }

    private int calculateDelay(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return Math.min(10, text.split("\\s+").length / 4);
    }

    private void sendMarkovReplies(MessageReceivedEvent event, long channelId, String content) {
        String reply = escapeMassMentions(resolveGuildEmoji(event, sanitizeOutput(generateReplyWithSeed(channelId, content))));
        if (!reply.isEmpty()) {
            int delaySeconds = calculateDelay(reply);
            Runnable sendReply = () -> {
                event.getChannel().sendMessage(reply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue();
                scheduleSecondReply(event, channelId, content);
            };

            if (delaySeconds == 0) {
                sendReply.run();
            } else {
                startTyping(event, delaySeconds);
                scheduler.schedule(sendReply, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void scheduleSecondReply(MessageReceivedEvent event, long channelId, String content) {
        if (rand.nextDouble() < 0.1) {
            String secondReply = escapeMassMentions(resolveGuildEmoji(event, sanitizeOutput(manager.generateReply(channelId))));
            if (!secondReply.isEmpty()) {
                int delaySeconds = calculateDelay(secondReply) + 2 + rand.nextInt(5);
                startTyping(event, delaySeconds);
                scheduler.schedule(() -> {
                    event.getChannel().sendMessage(secondReply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue();
                }, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void startTyping(MessageReceivedEvent event, int delaySeconds) {
        event.getChannel().sendTyping().queue();
        for (int d = 5; d < delaySeconds; d += 5) {
            scheduler.schedule(() -> event.getChannel().sendTyping().queue(), d, TimeUnit.SECONDS);
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
        if (countWords(originalMessage) <= 2) {
            return manager.generateReply(channelId);
        }

        if (rand.nextDouble() < 0.2) {
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

    static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
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
