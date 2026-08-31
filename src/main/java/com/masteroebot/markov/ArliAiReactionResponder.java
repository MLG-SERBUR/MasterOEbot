package com.masteroebot.markov;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dedicated responder for reaction selection using ArliAI.
 * Separate from main response flow: uses ArliAI only, with its own timeout and
 * non-thinking defaults mirroring matrix-robobot's ArliAI handling.
 * Provider/model order is fixed per config; no round-robin.
 */
public class ArliAiReactionResponder implements GenerativeAiResponder {
    private static final int REACTION_TIMEOUT_SECONDS = 600;
    private static final long GROQ_TOKEN_BUDGET = 8000; // reuse same budget for trimming if needed
    private final HttpClient client;
    private final List<Provider> providers;
    private final String systemPrompt;
    private final ArliAiCoordinator coordinator;

    public ArliAiReactionResponder(GenerativeAiConfig config) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt(), null);
    }

    public ArliAiReactionResponder(GenerativeAiConfig config, ArliAiCoordinator coordinator) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt(), coordinator);
    }

    public ArliAiReactionResponder(HttpClient client, List<Provider> providers, String systemPrompt) {
        this(client, providers, systemPrompt, null);
    }

    public ArliAiReactionResponder(HttpClient client, List<Provider> providers, String systemPrompt, ArliAiCoordinator coordinator) {
        this.client = client;
        this.providers = List.copyOf(providers);
        this.systemPrompt = systemPrompt;
        this.coordinator = coordinator;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public static int getTimeoutSeconds() {
        return REACTION_TIMEOUT_SECONDS;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No ArliAI providers configured for reactions"));
        }
        // If second chance is currently awaiting ArliAI, wait and retry after it completes
        if (coordinator != null && coordinator.isSecondChanceActive()) {
            System.out.println("ArliAI reaction waiting for second chance to complete before proceeding...");
            return coordinator.awaitSecondChance().thenCompose(v -> {
                System.out.println("ArliAI reaction retrying after second chance completed");
                return generateReply(request);
            });
        }
        if (coordinator != null) {
            // Try to mark reaction as active; if already active, proceed anyway (should not happen)
            coordinator.tryStartReaction();
        }
        long deadlineMs = System.currentTimeMillis() + (REACTION_TIMEOUT_SECONDS * 1000L);
        CompletableFuture<String> result = attemptGenerateReply(request, deadlineMs, 0, "none", false);
        if (coordinator != null) {
            result.whenComplete((r, e) -> coordinator.finishReaction());
        }
        return result;
    }

    private CompletableFuture<String> attemptGenerateReply(GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean calibrationRetryDone) {
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (attempts > 0 && timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not enough time remaining to try next ArliAI provider"));
        }
        if (attempts >= providers.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No more ArliAI providers available"));
        }
        Provider provider = providers.get(attempts);
        return attemptWithProvider(provider, request, deadlineMs, attempts, reasoningEffort, calibrationRetryDone);
    }

    private CompletableFuture<String> attemptWithProvider(Provider provider, GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean calibrationRetryDone) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(provider, request, reasoningEffort);
        } catch (Exception e) {
            return fallback(request, deadlineMs, attempts + 1, e, calibrationRetryDone);
        }

        System.out.println("Starting ArliAI reaction " + provider.displayName() + " (" + provider.model() + ") request with reasoning effort: " + reasoningEffort + "...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response))
                .exceptionallyCompose(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ReasoningMandatoryException && "none".equals(reasoningEffort)) {
                        System.out.println("Reasoning is mandatory for " + provider.model() + ". Retrying same provider with 'minimal' effort...");
                        return attemptWithProvider(provider, request, deadlineMs, attempts, "minimal", calibrationRetryDone);
                    }
                    // No calibration/truncation for reactions: payload is candidate list, not chat log. If context exceeded, just drop.
                    return fallback(request, deadlineMs, attempts + 1, e, calibrationRetryDone);
                });
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int nextAttempts, Throwable e, boolean calibrationRetryDone) {
        System.err.println("ArliAI reaction provider failed: " + e.toString());
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (nextAttempts >= providers.size() || timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(e);
        }
        System.out.println("Retrying next ArliAI provider...");
        return attemptGenerateReply(request, deadlineMs, nextAttempts, "none", calibrationRetryDone);
    }

    private HttpRequest buildRequest(Provider provider, GenerativeAiRequest request, String reasoningEffort) {
        String effectiveSystemPrompt = request.systemPromptOverride() == null ? systemPrompt : request.systemPromptOverride();
        List<String> cappedMessages = capForSmallContextProvider(provider, request.recentMessages(), effectiveSystemPrompt);
        DataObject payload = DataObject.empty()
                .put("stream", false)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty()
                                .put("role", "system")
                                .put("content", effectiveSystemPrompt))
                        .add(DataObject.empty()
                                .put("role", "user")
                                .put("content", String.join("\n", cappedMessages))))
                .put("model", provider.model());
        if (provider.disableReasoning()) {
            payload.put("reasoning", DataObject.empty()
                    .put("effort", reasoningEffort));
        }
        applyArliAiNonThinkingDefaults(payload);
        suppressGenericReasoning(provider, payload, reasoningEffort);

        byte[] payloadJson = payload.toJson();
        try {
            int previewCount = Math.min(5, cappedMessages.size());
            List<String> preview = cappedMessages.subList(Math.max(0, cappedMessages.size() - previewCount), cappedMessages.size());
            String previewStr = String.join(" | ", preview).replace("\n", " ");
            if (previewStr.length() > 1000) previewStr = previewStr.substring(0, 1000) + "...";
            String loggedEffort = payload.hasKey("reasoning_effort") ? payload.getString("reasoning_effort") : payload.hasKey("reasoning") ? payload.getObject("reasoning").getString("effort", reasoningEffort) : reasoningEffort;
            if (payload.hasKey("chat_template_kwargs")) loggedEffort += "+no_think";
            System.out.println("ArliAI Reaction Request: " + provider.displayName() + " (" + provider.model() + ") reasoning=" + loggedEffort + " history=" + cappedMessages.size() + " previewLast" + previewCount + ": " + previewStr);
        } catch (Exception logEx) {
            System.out.println("ArliAI Reaction Request: " + provider.displayName() + " (" + provider.model() + ") [preview log failed: " + logEx + "]");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .timeout(Duration.ofSeconds(REACTION_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payloadJson));

        for (Map.Entry<String, String> header : provider.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
    }

    private void applyArliAiNonThinkingDefaults(DataObject payload) {
        // Mirrors matrix-robobot AIService.applyArliAiNonThinkingDefaults + extraPayload output_kind delta
        if (!payload.hasKey("temperature")) payload.put("temperature", 0.3);
        if (!payload.hasKey("top_p")) payload.put("top_p", 0.9);
        if (!payload.hasKey("top_k")) payload.put("top_k", 20);
        if (!payload.hasKey("min_p")) payload.put("min_p", 0.0);
        if (!payload.hasKey("presence_penalty")) payload.put("presence_penalty", 1.5);
        if (!payload.hasKey("repetition_penalty")) payload.put("repetition_penalty", 1.0);
        payload.put("reasoning_effort", "none");
        payload.put("thinking_token_budget", 0);
        payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
        payload.put("output_kind", "delta");
    }

    private static final java.util.Set<String> SMALL_CONTEXT_PROVIDERS = java.util.Set.of("Groq", "Cloudflare", "ArliAI");

    private List<String> capForSmallContextProvider(Provider provider, List<String> messages) {
        String effectiveSystemPrompt = this.systemPrompt;
        return capForSmallContextProvider(provider, messages, effectiveSystemPrompt);
    }

    private static List<String> capForSmallContextProvider(Provider provider, List<String> messages, String systemPrompt) {
        if (messages == null || messages.isEmpty()
                || !SMALL_CONTEXT_PROVIDERS.contains(provider.displayName())) {
            return messages;
        }
        long systemTokens = systemPrompt != null ? PromptTokenizer.estimateTokens(systemPrompt + "\n") : 0;
        long overheadTokens = PromptTokenizer.estimateTokens("system\nuser\n");
        long effectiveBudget = GROQ_TOKEN_BUDGET - systemTokens - overheadTokens;
        if (effectiveBudget < 500) effectiveBudget = GROQ_TOKEN_BUDGET - systemTokens;
        if (effectiveBudget <= 0) effectiveBudget = GROQ_TOKEN_BUDGET;

        long[] tokenCounts = new long[messages.size()];
        long total = 0;
        for (int i = 0; i < messages.size(); i++) {
            tokenCounts[i] = PromptTokenizer.estimateTokens(messages.get(i));
            total += tokenCounts[i];
        }
        if (total <= effectiveBudget) {
            return messages;
        }

        int start = messages.size() - 1;
        long budget = effectiveBudget - tokenCounts[start];
        while (start > 0 && tokenCounts[start - 1] <= budget) {
            start--;
            budget -= tokenCounts[start];
        }
        System.out.println("Trimmed " + start + " oldest messages to fit " + provider.displayName()
                + " token budget of " + GROQ_TOKEN_BUDGET + " tokens (effective " + effectiveBudget + " after system=" + systemTokens + ").");
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private void suppressGenericReasoning(Provider provider, DataObject payload, String reasoningEffort) {
        // For ArliAI, already disabled via applyArliAiNonThinkingDefaults; keep generic disable for safety on other potential models
        // but avoid overriding ArliAI-specific values already set.
        if ("ArliAI".equals(provider.displayName())) {
            return;
        }
        String model = provider.model().toLowerCase();
        if (model.contains("gpt-oss")) {
            String effort = "none".equals(reasoningEffort) ? "low" : "medium";
            payload.put("reasoning_effort", effort);
            payload.put("include_reasoning", false);
            return;
        }
        payload.put("reasoning_effort", reasoningEffort);
        payload.put("reasoning_format", "hidden");
        if (!payload.hasKey("chat_template_kwargs")) {
            payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
        }
    }

    private String parseResponse(Provider provider, HttpResponse<String> response) {
        String bodyPreview = response.body() != null ? response.body().substring(0, Math.min(2000, response.body().length())) : "null";
        System.out.println("ArliAI Reaction Response: " + provider.displayName() + " (" + provider.model() + ") status=" + response.statusCode() + " body=" + bodyPreview.replace("\n", " "));
        if (response.statusCode() != 200) {
            String body = response.body();
            if (response.statusCode() == 400 && body.contains("Reasoning is mandatory")) {
                throw new ReasoningMandatoryException(provider.model() + " requires reasoning.");
            }
            throw new IllegalStateException(provider.displayName() + " (" + provider.model()
                    + ") failed. Status: " + response.statusCode() + ", Body: " + body);
        }

        try {
            DataObject root = DataObject.fromJson(response.body());
            DataArray choices = root.getArray("choices");
            if (choices.isEmpty()) {
                throw new IllegalStateException("Missing 'choices' array");
            }
            String text = choices.getObject(0).getObject("message").getString("content", null);
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalStateException("No response from " + provider.displayName() + " (" + provider.model() + ").");
            }
            return text;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected response from " + provider.displayName() + " ("
                    + provider.model() + "). Body: " + response.body(), e);
        }
    }

    static List<Provider> buildProviders(GenerativeAiConfig config) {
        List<Provider> providers = new ArrayList<>();
        if (hasText(config.arliApiKey())) {
            List<String> ms = models(config.arliModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "ArliAI",
                            "https://api.arliai.com/v1/chat/completions",
                            config.arliApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        return providers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<String> models(List<String> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .filter(ArliAiReactionResponder::hasText)
                .toList();
    }

    public record Provider(String displayName, String url, String apiKey, String model,
                    Map<String, String> extraHeaders, boolean disableReasoning) {
    }

    private static final class ReasoningMandatoryException extends RuntimeException {
        public ReasoningMandatoryException(String message) {
            super(message);
        }
    }
}
