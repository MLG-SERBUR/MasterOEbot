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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkovListener extends ListenerAdapter {
    private final MarkovManager manager;
    private final MarkovConfig config;
    private final GenerativeAiResponder generativeAiResponder;
    private JDA jda;
    private final Random rand = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, Deque<Long>> recentMessagesByChannel = new HashMap<>();
    private final AtomicInteger messageCount = new AtomicInteger(100);
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?\\d+>|<@&\\d+>|<#\\d+>");
    private static final Pattern CUSTOM_EMOJI_NAME_PATTERN = Pattern.compile(":([A-Za-z0-9_]{2,32}):");
    private static final long RESPONSE_DAMPENING_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final int RESPONSE_DAMPENING_FREE_MESSAGES = 2;
    private static final double RESPONSE_DAMPENING_STEP = 0.2;
    private static final double MIN_RESPONSE_CHANCE = 0.0;
    public static final int GENERATIVE_AI_HISTORY_LIMIT = 700;
    private static final long GENERATIVE_AI_TIMEOUT_SECONDS = 20;

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda) {
        this(manager, config, jda, new PlaceholderGenerativeAiResponder());
    }

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder) {
        this.manager = manager;
        this.config = config;
        this.jda = jda;
        this.generativeAiResponder = generativeAiResponder;
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

        String content = MarkovUtils.getDisplayNameContent(message);

        if (content == null || content.trim().isEmpty() || content.trim().startsWith("!")) return;

        boolean responseAllowed = shouldRespondAfterDampening(channelId);

        boolean isBot = message.getAuthor().isBot();

        if (!isBot) {
            manager.train(channelId, content);
            if (!manager.aiLogExists(channelId)) {
                seedAiLogFromHistory(event, channelId);
            }
            manager.appendToBrain(channelId, content);
            manager.appendToAiLog(channelId, event.getMember().getEffectiveName(), content);
            
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
            sendTriggeredReply(event, channelId, content, message.getReferencedMessage());
            return;
        }

        MessageReference reference = message.getMessageReference();
        if (reference != null && message.getReferencedMessage() == null) {
            reference.resolve().queue(referenced -> {
                if (responseAllowed && isMessageFromSelf(referenced)) {
                    sendTriggeredReply(event, channelId, content, referenced);
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
        sendMarkovReply(event, channelId, content, true, true);
    }

    private void sendTriggeredReply(MessageReceivedEvent event, long channelId, String content, Message referencedMessage) {
        if (config.isQuestionAiEnabled(channelId)) {
            sendGenerativeAiReplyWithFallback(event, channelId, content, referencedMessage);
            return;
        }
        sendMarkovReplies(event, channelId, content);
    }

    private void sendImmediateSeededMarkovReply(MessageReceivedEvent event, long channelId, String content) {
        sendMarkovReply(event, channelId, content, false, false);
    }

    private void sendMarkovReply(MessageReceivedEvent event, long channelId, String content,
                                 boolean useDelay, boolean allowSecondReply) {
        String reply = escapeMassMentions(resolveGuildEmoji(event, sanitizeOutput(generateReplyWithSeed(channelId, content, 0.2))));
        if (!reply.isEmpty()) {
            int delaySeconds = useDelay ? calculateDelay(reply) : 0;
            final List<ScheduledFuture<?>> typingTasks = (delaySeconds > 0) ? startTyping(event, delaySeconds) : null;
            
            Runnable sendReply = () -> {
                event.getChannel().sendMessage(reply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue(success -> {
                            stopTyping(typingTasks);
                            if (allowSecondReply) {
                                scheduleSecondReply(event, channelId, content);
                            }
                        }, error -> stopTyping(typingTasks));
            };

            if (delaySeconds == 0) {
                sendReply.run();
            } else {
                scheduler.schedule(sendReply, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void sendGenerativeAiReplyWithFallback(MessageReceivedEvent event, long channelId, String content, Message referencedMessage) {
        List<ScheduledFuture<?>> typingTasks = startTyping(event, (int) GENERATIVE_AI_TIMEOUT_SECONDS);

        List<String> recentMessages = manager.getRecentMessagesForAi(channelId, GENERATIVE_AI_HISTORY_LIMIT);

        if (referencedMessage != null && !recentMessages.isEmpty()) {
            String referencedContent = MarkovUtils.getDisplayNameContent(referencedMessage);
            String formattedReferenced = "<MasterOEBot> " + referencedContent;

            boolean isReferencingLastMessage = recentMessages.size() >= 2
                    && recentMessages.get(recentMessages.size() - 2).equals(formattedReferenced);

            if (!isReferencingLastMessage) {
                String currentMessageLine = recentMessages.get(recentMessages.size() - 1);
                String context = "(replying to MasterOEBot: \"" + referencedContent + "\") ";

                int tagEnd = currentMessageLine.indexOf("> ");
                if (tagEnd != -1) {
                    String updated = currentMessageLine.substring(0, tagEnd + 2) + context + currentMessageLine.substring(tagEnd + 2);
                    recentMessages.set(recentMessages.size() - 1, updated);
                } else {
                    recentMessages.set(recentMessages.size() - 1, context + currentMessageLine);
                }
            }
        }

        GenerativeAiRequest request = new GenerativeAiRequest(recentMessages);

        CompletableFuture<String> replyFuture;
        try {
            replyFuture = generativeAiResponder.generateReply(request);
        } catch (Exception e) {
            stopTyping(typingTasks);
            logGenerativeAiFailure(channelId, e);
            sendImmediateSeededMarkovReply(event, channelId, content);
            return;
        }

        if (replyFuture == null) {
            stopTyping(typingTasks);
            logGenerativeAiFailure(channelId, new IllegalStateException("Generative AI responder returned null future"));
            sendImmediateSeededMarkovReply(event, channelId, content);
            return;
        }

        replyFuture.orTimeout(GENERATIVE_AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    stopTyping(typingTasks);
                    if (error != null) {
                        logGenerativeAiFailure(channelId, error);
                        sendImmediateSeededMarkovReply(event, channelId, content);
                        return;
                    }

                    if (!isLikelyChatLikeAiReply(reply)) {
                        logRejectedGenerativeAiReply(channelId, reply);
                        sendImmediateSeededMarkovReply(event, channelId, content);
                        return;
                    }

                    String safeReply = escapeMassMentions(resolveGuildEmoji(event, sanitizeOutput(reply.trim())));
                    if (safeReply.trim().isEmpty()) {
                        logGenerativeAiFailure(channelId, new IllegalStateException("Generative AI responder returned empty reply"));
                        sendImmediateSeededMarkovReply(event, channelId, content);
                        return;
                    }

                    event.getChannel().sendMessage(safeReply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue(success -> {
                                trackAiMessage(channelId, safeReply);
                                scheduleSecondReply(event, channelId, content);
                            });
                });
    }

    private void logGenerativeAiFailure(long channelId, Throwable error) {
        Throwable unwrapped = unwrapCompletionException(error);
        System.err.println("Generative AI reply failed for channel " + channelId + ": " + unwrapped);
        unwrapped.printStackTrace(System.err);
    }

    private void logRejectedGenerativeAiReply(long channelId, String reply) {
        System.err.println("Generative AI reply rejected for channel " + channelId + ": " + reply);
    }

    private Throwable unwrapCompletionException(Throwable error) {
        if ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private void scheduleSecondReply(MessageReceivedEvent event, long channelId, String content) {
        if (rand.nextDouble() < 0.1) {
            String replyText = generateReplyWithSeed(channelId, content, 0.5);
            String secondReply = escapeMassMentions(resolveGuildEmoji(event, sanitizeOutput(replyText)));
            if (!secondReply.isEmpty()) {
                int delaySeconds = calculateDelay(secondReply) + 2 + rand.nextInt(5);
                final List<ScheduledFuture<?>> typingTasks = startTyping(event, delaySeconds);
                scheduler.schedule(() -> {
                    event.getChannel().sendMessage(secondReply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue(success -> stopTyping(typingTasks), error -> stopTyping(typingTasks));
                }, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void trackAiMessage(long channelId, String message) {
        manager.appendBotMessageToAiLog(channelId, message);
    }

    private void seedAiLogFromHistory(MessageReceivedEvent event, long channelId) {
        System.out.println("Initializing AI log for channel " + channelId + " from history...");
        manager.ensureAiLogInitialized(channelId);
        event.getChannel().getHistory().retrievePast(100).queue(messages -> {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                String content = MarkovUtils.getDisplayNameContent(msg).trim();
                if (!content.isEmpty() && !ProfanityFilter.containsProfanity(content)) {
                    if (msg.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) {
                        manager.appendBotMessageToAiLog(channelId, content);
                    } else {
                        String authorName = msg.getMember() != null ? msg.getMember().getEffectiveName() : msg.getAuthor().getEffectiveName();
                        manager.appendToAiLog(channelId, authorName, content);
                    }
                }
            }
            System.out.println("AI log for channel " + channelId + " seeded with " + messages.size() + " messages.");
        }, error -> {
            System.err.println("Failed to seed AI log from history for channel " + channelId + ": " + error.getMessage());
        });
    }

    private List<ScheduledFuture<?>> startTyping(MessageReceivedEvent event, int delaySeconds) {
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        event.getChannel().sendTyping().queue();
        for (int d = 8; d < delaySeconds; d += 8) {
            futures.add(scheduler.schedule(() -> event.getChannel().sendTyping().queue(), d, TimeUnit.SECONDS));
        }
        return futures;
    }

    private void stopTyping(List<ScheduledFuture<?>> futures) {
        if (futures != null) {
            for (ScheduledFuture<?> future : futures) {
                future.cancel(false);
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

    private String generateReplyWithSeed(long channelId, String originalMessage, double seedChance) {
        if (countWords(originalMessage) <= 2) {
            return manager.generateReply(channelId);
        }

        if (rand.nextDouble() < seedChance) {
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


    public static boolean isLikelyChatLikeAiReply(String text) {
        if (text == null) {
            return false;
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1200) {
            return false;
        }
        if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return false;
        }
        if (trimmed.contains("```")) {
            return false;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        return !lower.matches("^(assistant|user|system|bot)\\s*:.*")
                && !lower.startsWith("as an ai")
                && !lower.startsWith("as a language model");
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
