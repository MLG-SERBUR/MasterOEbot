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
 * Dedicated responder for second-chance (% chance) follow-up replies using ArliAI.
 * Uses 10 minute timeout (600s) and same system prompt as main responder (for now).
 * Only starts if reaction service isn't currently awaiting ArliAI (via ArliAiCoordinator).
 */
public class ArliAiSecondChanceResponder implements GenerativeAiResponder {
    private static final int SECOND_CHANCE_TIMEOUT_SECONDS = 600;
    private static final long GROQ_TOKEN_BUDGET = 8000;
    private final HttpClient client;
    private final List<Provider> providers;
    private final String systemPrompt;
    private final ArliAiCoordinator coordinator;

    public ArliAiSecondChanceResponder(GenerativeAiConfig config) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt(), null);
    }

    public ArliAiSecondChanceResponder(GenerativeAiConfig config, ArliAiCoordinator coordinator) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt(), coordinator);
    }

    public ArliAiSecondChanceResponder(HttpClient client, List<Provider> providers, String systemPrompt) {
        this(client, providers, systemPrompt, null);
    }

    public ArliAiSecondChanceResponder(HttpClient client, List<Provider> providers, String systemPrompt, ArliAiCoordinator coordinator) {
        this.client = client;
        this.providers = List.copyOf(providers);
        this.systemPrompt = systemPrompt;
        this.coordinator = coordinator;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public static int getTimeoutSeconds() {
        return SECOND_CHANCE_TIMEOUT_SECONDS;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No ArliAI providers configured for second chance"));
        }
        // Only start if reaction service isn't currently awaiting ArliAI response
        if (coordinator != null && coordinator.isReactionActive()) {
            System.out.println("Skipping ArliAI second chance - reaction service is awaiting response");
            return CompletableFuture.failedFuture(new IllegalStateException("Reaction service is currently awaiting ArliAI response, skipping second chance"));
        }
        if (coordinator != null && !coordinator.tryStartSecondChance()) {
            System.out.println("Skipping ArliAI second chance - could not acquire coordinator (reaction or second chance active)");
            return CompletableFuture.failedFuture(new IllegalStateException("Could not acquire second chance coordinator"));
        }

        long deadlineMs = System.currentTimeMillis() + (SECOND_CHANCE_TIMEOUT_SECONDS * 1000L);
        CompletableFuture<String> result = attemptGenerateReply(request, deadlineMs, 0, "none", false);
        if (coordinator != null) {
            result.whenComplete((r, e) -> coordinator.finishSecondChance());
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

        System.out.println("Starting ArliAI second chance " + provider.displayName() + " (" + provider.model() + ") request with reasoning effort: " + reasoningEffort + "...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response))
                .exceptionallyCompose(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ReasoningMandatoryException && "none".equals(reasoningEffort)) {
                        System.out.println("Reasoning is mandatory for " + provider.model() + ". Retrying same provider with 'minimal' effort...");
                        return attemptWithProvider(provider, request, deadlineMs, attempts, "minimal", calibrationRetryDone);
                    }
                    String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    if (!calibrationRetryDone && TokenCalibrationManager.isCalibrationError(errorMsg)) {
                        String promptForCalibration = buildCalibrationPrompt(request, provider);
                        TokenCalibrationManager.getInstance().recordFromError(promptForCalibration, errorMsg);
                        Integer actual = TokenCalibrationManager.extractActualTokensForCalibration(errorMsg);
                        Integer limit = TokenCalibrationManager.extractLimitForCalibration(errorMsg);
                        if (actual != null && limit != null && request.recentMessages() != null && request.recentMessages().size() > 10) {
                            double targetRatio = limit * 0.85 / (double) actual;
                            targetRatio = Math.max(0.3, Math.min(0.85, targetRatio));
                            int newSize = Math.max(10, (int) (request.recentMessages().size() * targetRatio));
                            if (newSize < request.recentMessages().size()) {
                                java.util.List<String> truncated = new java.util.ArrayList<>(request.recentMessages().subList(request.recentMessages().size() - newSize, request.recentMessages().size()));
                                GenerativeAiRequest truncatedRequest = new GenerativeAiRequest(truncated, request.systemPromptOverride());
                                String calMsg = "Context exceeded (" + actual + "/" + limit + "). Calibrated factor to " + String.format("%.2f", TokenCalibrationManager.getInstance().getFactor()) + " and retrying with " + newSize + " messages (" + (int) (targetRatio * 100) + "%)...";
                                System.out.println(calMsg);
                                return attemptWithProvider(provider, truncatedRequest, deadlineMs, attempts, reasoningEffort, true);
                            }
                        }
                    }
                    return fallback(request, deadlineMs, attempts + 1, e, calibrationRetryDone);
                });
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int nextAttempts, Throwable e, boolean calibrationRetryDone) {
        System.err.println("ArliAI second chance provider failed: " + e.toString());
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (nextAttempts >= providers.size() || timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(e);
        }
        System.out.println("Retrying next ArliAI second chance provider...");
        return attemptGenerateReply(request, deadlineMs, nextAttempts, "none", calibrationRetryDone);
    }

    private String buildCalibrationPrompt(GenerativeAiRequest request, Provider provider) {
        String sys = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        java.util.List<String> capped = capForSmallContextProvider(provider, request.recentMessages(), sys);
        String joined = capped == null ? "" : String.join("\n", capped);
        return (sys != null ? sys : "") + "\n" + joined;
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
            System.out.println("ArliAI Second Chance Request: " + provider.displayName() + " (" + provider.model() + ") reasoning=" + loggedEffort + " history=" + cappedMessages.size() + " previewLast" + previewCount + ": " + previewStr);
        } catch (Exception logEx) {
            System.out.println("ArliAI Second Chance Request: " + provider.displayName() + " (" + provider.model() + ") [preview log failed: " + logEx + "]");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .timeout(Duration.ofSeconds(SECOND_CHANCE_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payloadJson));

        for (Map.Entry<String, String> header : provider.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
    }

    private void applyArliAiNonThinkingDefaults(DataObject payload) {
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
        System.out.println("ArliAI Second Chance Response: " + provider.displayName() + " (" + provider.model() + ") status=" + response.statusCode() + " body=" + bodyPreview.replace("\n", " "));
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
                .filter(ArliAiSecondChanceResponder::hasText)
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
