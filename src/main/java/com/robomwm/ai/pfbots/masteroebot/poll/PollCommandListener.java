package com.robomwm.ai.pfbots.masteroebot.poll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robomwm.ai.pfbots.markov.GenerativeAiRequest;
import com.robomwm.ai.pfbots.markov.GenerativeAiResponder;
import com.robomwm.ai.pfbots.markov.MarkovListener;
import com.robomwm.ai.pfbots.markov.MarkovManager;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.utils.messages.MessagePollBuilder;
import net.dv8tion.jda.api.utils.messages.MessagePollData;

/**
 * {@code /poll} for MasterOEBot. Takes one {@code prompt} arg describing what
 * the poll should be about, pulls prior channel messages for context, and asks
 * the shared AI backend once for the question, options, and duration.
 */
public class PollCommandListener extends ListenerAdapter {
    /** System prompt in code, like the other bot prompts. Single AI call decides everything. */
    static final String SYSTEM_PROMPT = """
            You create a Discord poll from a user's poll request and recent channel chat.
            Recent chat lines are ordered oldest to newest, each on its own line in the format <DisplayName> message. The last line is the poll request in the format Poll request: "...".
            Use the request as the main topic. Use chat context only to sharpen wording and options (inside jokes, current topics, names). Never expose chat contents the request did not ask about.
            Pick a short poll question (max 140 characters) and 2 to 5 distinct answer options (each max 55 characters). Options must be mutually distinct and directly answer the question.
            You always pick durationHours as an integer from 1 to 24 that fits the request. Honor any duration named in the request.
            Return ONLY raw JSON, no markdown fences, no commentary, with exactly these keys:
            {"question": "...", "options": ["...", "..."], "durationHours": 4}
            """;

    static final int MAX_QUESTION_LENGTH = 300;
    static final int MAX_OPTION_LENGTH = 55;
    static final int MAX_OPTIONS = 5;
    static final int MIN_OPTIONS = 2;
    static final int MIN_DURATION_HOURS = 1;
    static final int MAX_DURATION_HOURS = 24;
    static final int AI_TIMEOUT_SECONDS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MarkovManager markovManager;
    private final GenerativeAiResponder generativeAiResponder;

    public PollCommandListener() {
        this(null, null);
    }

    public PollCommandListener(MarkovManager markovManager, GenerativeAiResponder generativeAiResponder) {
        this.markovManager = markovManager;
        this.generativeAiResponder = generativeAiResponder;
    }

    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. BotMain combines all listeners'
        // commands into a single updateCommands() action, because each
        // updateCommands() call replaces all global commands.
        updater.addCommands(
                Commands.slash("poll", "Create an AI-generated poll from a prompt")
                        .addOption(OptionType.STRING, "prompt", "What should the poll be about?", true));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"poll".equals(event.getName())) {
            return;
        }

        OptionMapping promptOption = event.getOption("prompt");
        String prompt = promptOption == null ? "" : promptOption.getAsString();
        if (prompt == null || prompt.isBlank()) {
            event.reply("Give me something to work with, prompt can't be empty.")
                    .setEphemeral(true).queue();
            return;
        }
        prompt = prompt.strip();

        if (generativeAiResponder == null) {
            event.reply("Poll generation is not available right now.").setEphemeral(true).queue();
            return;
        }

        String finalPrompt = prompt;
        long channelId = event.getChannel().getIdLong();
        // Ephemeral ack: the command must be answered within 3s, but the AI
        // call takes longer. The poll itself goes up later as its own public
        // channel message. Context comes from the same AI-log history a
        // regular reply uses.
        event.deferReply(true).queue(
                ignored -> {
                    event.getHook().editOriginal("Making your poll...").queue(
                            null,
                            editError -> System.err.println("Poll ack edit failed: " + editError.getMessage()));
                    generateFromHistory(event, finalPrompt, gatherRegularContext(channelId));
                },
                error -> System.err.println("Poll defer failed: " + error.getMessage()));
    }

    /** Same history source a regular reply uses: AI log up to the responder token budget. */
    private List<String> gatherRegularContext(long channelId) {
        if (markovManager == null) {
            return List.of();
        }
        try {
            return new ArrayList<>(markovManager.getRecentMessagesForAiUntilTokenBudget(
                    channelId, MarkovListener.gatherBudgetFor(generativeAiResponder), SYSTEM_PROMPT));
        } catch (Exception e) {
            System.err.println("Poll context gather failed: " + e.getMessage());
            return List.of();
        }
    }

    private void generateFromHistory(SlashCommandInteractionEvent event,
            String prompt, List<String> history) {
        List<String> aiMessages = new ArrayList<>(history);
        aiMessages.add("Poll request: \"" + prompt + "\"");
        GenerativeAiRequest request = new GenerativeAiRequest(aiMessages, SYSTEM_PROMPT);

        try {
            generativeAiResponder.generateReply(request)
                    .orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((reply, error) -> {
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            System.err.println("Poll generation failed: " + cause);
                            event.getHook().editOriginal("Couldn't generate that poll, try again in a bit.")
                                    .queue(null, editError -> System.err.println("Poll error edit failed: " + editError.getMessage()));
                            return;
                        }
                        PollSpec spec;
                        try {
                            spec = parsePollSpec(reply);
                        } catch (IllegalArgumentException e) {
                            System.err.println("Poll parse failed: " + e.getMessage());
                            event.getHook().editOriginal("The AI returned an invalid poll, try again with a clearer prompt.")
                                    .queue(null, editError -> System.err.println("Poll error edit failed: " + editError.getMessage()));
                            return;
                        }
                        postPoll(event, spec);
                    });
        } catch (Exception e) {
            System.err.println("Poll generateReply threw: " + e);
            event.getHook().editOriginal("Couldn't generate that poll, try again in a bit.").queue();
        }
    }

    /** Posts only the poll as its own public message: no user mention, no prompt echo. */
    private void postPoll(SlashCommandInteractionEvent event, PollSpec spec) {
        MessagePollBuilder builder = new MessagePollBuilder(spec.question());
        for (String option : spec.options()) {
            builder.addAnswer(option);
        }
        builder.setDuration(spec.durationHours(), TimeUnit.HOURS);
        MessagePollData poll;
        try {
            poll = builder.build();
        } catch (Exception e) {
            System.err.println("Poll build failed: " + e.getMessage());
            event.getHook().editOriginal("Couldn't post that poll, try again in a bit.").queue();
            return;
        }

        try {
            event.getChannel().sendMessagePoll(poll).queue(
                    sent -> {
                        System.out.println("Poll posted in channel " + event.getChannel().getId()
                                + " message " + sent.getId() + " (" + spec.options().size()
                                + " options, " + spec.durationHours() + "h).");
                        event.getHook().editOriginal("Poll posted!").queue(
                                null,
                                editError -> System.err.println("Poll confirmation edit failed: " + editError.getMessage()));
                    },
                    sendError -> {
                        System.err.println("Poll send failed: " + sendError.getMessage());
                        event.getHook().editOriginal("Couldn't post that poll here, try again in a bit.").queue();
                    });
        } catch (Exception e) {
            System.err.println("Poll send threw: " + e.getMessage());
            event.getHook().editOriginal("Couldn't post that poll here, try again in a bit.").queue();
        }
    }

    /** Parse and validate the single AI response into a postable poll spec. */
    static PollSpec parsePollSpec(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty poll response");
        }
        String json = stripCodeFences(raw).strip();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            throw new IllegalArgumentException("No JSON object in poll response");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid poll JSON: " + e.getMessage(), e);
        }

        String question = root.has("question") ? root.get("question").asText("").strip() : "";
        if (question.isEmpty()) {
            throw new IllegalArgumentException("Poll JSON missing question");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            question = question.substring(0, MAX_QUESTION_LENGTH);
        }

        JsonNode optionsNode = root.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() < MIN_OPTIONS) {
            throw new IllegalArgumentException("Poll JSON needs at least " + MIN_OPTIONS + " options");
        }
        Map<String, String> deduped = new LinkedHashMap<>();
        for (JsonNode optionNode : optionsNode) {
            String option = optionNode.asText("").strip();
            if (option.isEmpty()) {
                continue;
            }
            if (option.length() > MAX_OPTION_LENGTH) {
                option = option.substring(0, MAX_OPTION_LENGTH);
            }
            deduped.putIfAbsent(option.toLowerCase(), option);
            if (deduped.size() >= MAX_OPTIONS) {
                break;
            }
        }
        List<String> options = new ArrayList<>(deduped.values());
        if (options.size() < MIN_OPTIONS) {
            throw new IllegalArgumentException("Poll JSON needs at least " + MIN_OPTIONS + " distinct options");
        }

        JsonNode durationNode = firstPresent(root, "durationHours", "duration_hours", "duration");
        if (durationNode == null) {
            throw new IllegalArgumentException("Poll JSON missing durationHours");
        }
        int durationHours;
        if (durationNode.canConvertToInt()) {
            durationHours = durationNode.asInt();
        } else if (durationNode.isTextual()) {
            try {
                durationHours = Integer.parseInt(durationNode.asText("").strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Poll JSON has non-numeric durationHours");
            }
        } else {
            throw new IllegalArgumentException("Poll JSON has non-numeric durationHours");
        }
        if (durationHours < MIN_DURATION_HOURS) {
            durationHours = MIN_DURATION_HOURS;
        } else if (durationHours > MAX_DURATION_HOURS) {
            durationHours = MAX_DURATION_HOURS;
        }

        return new PollSpec(question, List.copyOf(options), durationHours);
    }

    private static JsonNode firstPresent(JsonNode root, String... names) {
        for (String name : names) {
            if (root.has(name)) {
                return root.get(name);
            }
        }
        return null;
    }

    private static String stripCodeFences(String raw) {
        String stripped = raw.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return stripped.substring(firstNewline + 1, lastFence);
            }
            if (lastFence > 3) {
                return stripped.substring(3, lastFence);
            }
        }
        return stripped;
    }

    record PollSpec(String question, List<String> options, int durationHours) {
    }
}
