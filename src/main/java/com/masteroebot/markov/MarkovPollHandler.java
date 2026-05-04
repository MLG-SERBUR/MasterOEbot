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
    private final Random random = new Random();

    public MarkovPollHandler(MarkovManager manager) {
        this.manager = manager;
    }

    public void handle(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        OptionMapping wordOption = event.getOption("word");
        String seed = wordOption != null ? wordOption.getAsString() : null;

        String question = generate(channelId, seed);
        if (question == null || question.trim().isEmpty()) {
            question = "Unknown poll question?";
        }
        if (question.length() > 300) {
            question = question.substring(0, 300);
        }

        MessagePollBuilder builder = new MessagePollBuilder(question);
        
        int numAnswers = 2 + random.nextInt(4); // 2 to 5 answers
        Set<String> addedAnswers = new HashSet<>();
        
        for (int i = 0; i < numAnswers; i++) {
            String answer = generate(channelId, seed);
            if (answer == null || answer.trim().isEmpty() || addedAnswers.contains(answer)) {
                answer = "Option " + (i + 1);
            }
            if (answer.length() > 55) {
                answer = answer.substring(0, 52) + "...";
            }
            builder.addAnswer(answer);
            addedAnswers.add(answer);
        }

        int durationHours = 1 + random.nextInt(24);
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
