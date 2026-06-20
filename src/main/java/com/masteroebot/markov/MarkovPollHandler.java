package com.masteroebot.markov;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.messages.MessagePollBuilder;
import net.dv8tion.jda.api.utils.messages.MessagePollData;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MarkovPollHandler {
    private final MarkovManager manager;
    private final MarkovConfig config;
    private final Random random = new Random();

    public MarkovPollHandler(MarkovManager manager, MarkovConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void handle(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        
        if (!config.isEnabled(channelId)) {
            event.reply("Markov is not enabled for this channel. Use /markov toggle first.").setEphemeral(true).queue();
            return;
        }
        
        manager.loadBrain(channelId);
        
        OptionMapping wordOption = event.getOption("word");
        String seed = wordOption != null ? wordOption.getAsString() : null;

        String question = generate(channelId, seed);
        if (question == null || question.trim().isEmpty()) {
            event.reply("Could not generate a poll question. The Markov brain may not have enough data for this channel.").setEphemeral(true).queue();
            return;
        }
        if (question.length() > 300) {
            question = question.substring(0, 300);
        }

        int numAnswers = 2 + random.nextInt(4); // 2 to 5 answers
        Set<String> addedAnswers = new HashSet<>();
        int retryCount = 0;
        
        for (int i = 0; i < numAnswers; i++) {
            String answer = generate(channelId, seed);
            if (answer == null || answer.trim().isEmpty()) {
                event.reply("Could not generate a poll answer. The Markov brain may not have enough data for this channel.").setEphemeral(true).queue();
                return;
            }
            if (addedAnswers.contains(answer)) {
                // Duplicate answer, try again but limit retries
                retryCount++;
                if (retryCount >= numAnswers * 3) {
                    event.reply("Could not generate enough unique answers for the poll.").setEphemeral(true).queue();
                    return;
                }
                i--;
                continue;
            }
            retryCount = 0;
            if (answer.length() > 55) {
                answer = answer.substring(0, 52) + "...";
            }
            addedAnswers.add(answer);
        }

        MessagePollBuilder builder = new MessagePollBuilder(question);
        
        for (String answer : addedAnswers) {
            builder.addAnswer(answer);
        }

        int durationHours = 1 + random.nextInt(168);
        builder.setDuration(durationHours, TimeUnit.HOURS);
        
        MessagePollData poll = builder.build();
        event.reply("Generated Poll").setPoll(poll).queue();
    }

    private String generate(long channelId, String seed) {
        if (seed != null && !seed.trim().isEmpty()) {
            return manager.generateReply(channelId, seed);
        }
        return manager.generateReply(channelId);
    }
}
