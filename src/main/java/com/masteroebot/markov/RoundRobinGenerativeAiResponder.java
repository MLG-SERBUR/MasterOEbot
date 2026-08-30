package com.masteroebot.markov;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinGenerativeAiResponder implements GenerativeAiResponder {
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final long GROQ_TOKEN_BUDGET = 8000;
    private final HttpClient client;
    private final List<Provider> regularProviders;
    private final List<Provider> fallbackProviders;
    private final String systemPrompt;
    private final AtomicInteger nextProvider = new AtomicInteger(0);

    public RoundRobinGenerativeAiResponder(GenerativeAiConfig config) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt());
    }

    RoundRobinGenerativeAiResponder(HttpClient client, List<Provider> providers, String systemPrompt) {
        this.client = client;
        this.regularProviders = providers.stream()
                .filter(p -> !"OpenRouter".equals(p.displayName()))
                .toList();
        this.fallbackProviders = providers.stream()
                .filter(p -> "OpenRouter".equals(p.displayName()))
                .toList();
        this.systemPrompt = systemPrompt;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        if (regularProviders.isEmpty() && fallbackProviders.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No generative AI providers configured"));
        }
        long deadlineMs = System.currentTimeMillis() + (REQUEST_TIMEOUT_SECONDS * 1000L);
        return attemptGenerateReply(request, deadlineMs, 0, "none", false, false);
    }

    private CompletableFuture<String> attemptGenerateReply(GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean usingFallback) {
        return attemptGenerateReply(request, deadlineMs, attempts, reasoningEffort, usingFallback, false);
    }

    private CompletableFuture<String> attemptGenerateReply(GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean usingFallback, boolean calibrationRetryDone) {
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (attempts > 0 && timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not enough time remaining to try next provider"));
        }

        List<Provider> providersToUse = usingFallback ? fallbackProviders : regularProviders;
        if (providersToUse.isEmpty()) {
            // If we've exhausted regular providers and have fallback providers, try them
            if (!usingFallback && !fallbackProviders.isEmpty()) {
                System.out.println("All regular providers failed, trying OpenRouter fallback...");
                return attemptGenerateReply(request, deadlineMs, 0, "none", true, calibrationRetryDone);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("No more providers available"));
        }

        Provider provider = providersToUse.get(Math.floorMod(nextProvider.getAndIncrement(), providersToUse.size()));
        return attemptWithProvider(provider, request, deadlineMs, attempts, reasoningEffort, usingFallback, calibrationRetryDone);
    }

    private CompletableFuture<String> attemptWithProvider(Provider provider, GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean usingFallback, boolean calibrationRetryDone) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(provider, request, reasoningEffort);
        } catch (Exception e) {
            return fallback(request, deadlineMs, attempts + 1, e, usingFallback, calibrationRetryDone);
        }

        System.out.println("Starting " + provider.displayName() + " (" + provider.model() + ") request with reasoning effort: " + (provider.disableReasoning() ? reasoningEffort : "default") + "...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response))
                .exceptionallyCompose(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ReasoningMandatoryException && "none".equals(reasoningEffort)) {
                        System.out.println("Reasoning is mandatory for " + provider.model() + ". Retrying same provider with 'minimal' effort...");
                        return attemptWithProvider(provider, request, deadlineMs, attempts, "minimal", usingFallback, calibrationRetryDone);
                    }
                    // Self-calibration: heuristic underestimated -> update factor and retry once with truncated history
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
                                return attemptWithProvider(provider, truncatedRequest, deadlineMs, attempts, reasoningEffort, usingFallback, true);
                            }
                        }
                    }
                    return fallback(request, deadlineMs, attempts + 1, e, usingFallback, calibrationRetryDone);
                });
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int attempts, Throwable e, boolean usingFallback, boolean calibrationRetryDone) {
        System.err.println("Provider failed: " + e.toString());
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();

        List<Provider> currentProviders = usingFallback ? fallbackProviders : regularProviders;
        if (attempts >= currentProviders.size() || timeRemainingMs <= 2000) {
            // If we're using regular providers and they all failed, try fallback
            if (!usingFallback && !fallbackProviders.isEmpty()) {
                System.out.println("All regular providers failed, trying OpenRouter fallback...");
                return attemptGenerateReply(request, deadlineMs, 0, "none", true, calibrationRetryDone);
            }
            return CompletableFuture.failedFuture(e);
        }
        System.out.println("Retrying next provider...");
        return attemptGenerateReply(request, deadlineMs, attempts, "none", usingFallback, calibrationRetryDone);
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int attempts, Throwable e, boolean usingFallback) {
        return fallback(request, deadlineMs, attempts, e, usingFallback, false);
    }

    private String buildCalibrationPrompt(GenerativeAiRequest request, Provider provider) {
        String sys = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        java.util.List<String> capped = capForSmallContextProvider(provider, request.recentMessages());
        String joined = capped == null ? "" : String.join("\n", capped);
        return (sys != null ? sys : "") + "\n" + joined;
    }

    private HttpRequest buildRequest(Provider provider, GenerativeAiRequest request, String reasoningEffort) {
        DataObject payload = DataObject.empty()
                .put("stream", false)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty()
                                .put("role", "system")
                                .put("content", request.systemPromptOverride() == null
                                        ? systemPrompt
                                        : request.systemPromptOverride()))
                        .add(DataObject.empty()
                                .put("role", "user")
                                .put("content", String.join("\n", capForSmallContextProvider(provider, request.recentMessages())))));
        if ("OpenRouter".equals(provider.displayName())) {
            payload.put("models", DataArray.empty().add(provider.model()));
        } else {
            payload.put("model", provider.model());
        }
        if (provider.disableReasoning()) {
            payload.put("reasoning", DataObject.empty()
                    .put("effort", reasoningEffort));
        }
        suppressGroqReasoningOutput(provider, payload, reasoningEffort);

        byte[] payloadJson = payload.toJson();
        System.out.println("AI Request Payload: " + new String(payloadJson, StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payloadJson));

        for (Map.Entry<String, String> header : provider.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
    }

    private static boolean isFreeOpenRouterModel(String model) {
        return model != null && (model.equals("openrouter/free") || model.contains(":free"));
    }

    private static final Set<String> SMALL_CONTEXT_PROVIDERS = Set.of("Groq", "Cloudflare");

    private static List<String> capForSmallContextProvider(Provider provider, List<String> messages) {
        if (messages == null || messages.isEmpty()
                || !SMALL_CONTEXT_PROVIDERS.contains(provider.displayName())) {
            return messages;
        }
        long[] tokenCounts = new long[messages.size()];
        long total = 0;
        for (int i = 0; i < messages.size(); i++) {
            tokenCounts[i] = PromptTokenizer.estimateTokens(messages.get(i));
            total += tokenCounts[i];
        }
        if (total <= GROQ_TOKEN_BUDGET) {
            return messages;
        }

        int start = messages.size() - 1;
        long budget = GROQ_TOKEN_BUDGET - tokenCounts[start];
        while (start > 0 && tokenCounts[start - 1] <= budget) {
            start--;
            budget -= tokenCounts[start];
        }
        System.out.println("Trimmed " + start + " oldest messages to fit " + provider.displayName()
                + " token budget of " + GROQ_TOKEN_BUDGET + " tokens.");
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private void suppressGroqReasoningOutput(Provider provider, DataObject payload, String reasoningEffort) {
        if (!"Groq".equals(provider.displayName())) {
            return;
        }

        String model = provider.model().toLowerCase();
        if (model.startsWith("openai/gpt-oss-")) {
            payload.put("include_reasoning", false);
            String effort = "none".equals(reasoningEffort) ? "low" : "medium";
            payload.put("reasoning_effort", effort);
        } else if (model.startsWith("qwen/qwen3-")) {
            payload.put("reasoning_effort", reasoningEffort);
            payload.put("reasoning_format", "hidden");
            // ArliAI/vLLM style: Groq docs confirm reasoning_effort="none" disables for qwen3 (qwen/qwen3.6-27b supports none/default),
            // but underlying vLLM template also respects chat_template_kwargs.enable_thinking=false (see Qwen3, Featherless, vLLM docs).
            // Add it when we intend to disable reasoning to ensure true non-thinking mode and avoid hidden reasoning time.
            if ("none".equals(reasoningEffort)) {
                payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
            }
        }
    }


    private String parseResponse(Provider provider, HttpResponse<String> response) {
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
        if (hasText(config.cerebrasApiKey())) {
            for (String model : models(config.cerebrasModels())) {
                providers.add(new Provider(
                        "Cerebras",
                        "https://api.cerebras.ai/v1/chat/completions",
                        config.cerebrasApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.groqApiKey())) {
            for (String model : models(config.groqModels())) {
                providers.add(new Provider(
                        "Groq",
                        "https://api.groq.com/openai/v1/chat/completions",
                        config.groqApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.openrouterApiKey())) {
            for (String model : models(config.openrouterModels())) {
                if (!isFreeOpenRouterModel(model)) {
                    continue;
                }
                providers.add(new Provider(
                        "OpenRouter",
                        "https://openrouter.ai/api/v1/chat/completions",
                        config.openrouterApiKey(),
                        model,
                        Map.of(
                                "HTTP-Referer", "https://github.com/MLG-SERBUR/MasterOEbot",
                                "X-Title", "MasterOEbot"),
                        true));
            }
        }
        if (hasText(config.geminiApiKey())) {
            for (String model : models(config.geminiModels())) {
                providers.add(new Provider(
                        "Gemini",
                        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                        config.geminiApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.mistralApiKey())) {
            for (String model : models(config.mistralModels())) {
                providers.add(new Provider(
                        "Mistral",
                        "https://api.mistral.ai/v1/chat/completions",
                        config.mistralApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.zaiApiKey())) {
            for (String model : models(config.zaiModels())) {
                providers.add(new Provider(
                        "ZAI",
                        "https://api.z.ai/api/paas/v4/chat/completions",
                        config.zaiApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.cloudflareApiKey()) && hasText(config.cloudflareAccountId())) {
            for (String model : models(config.cloudflareModels())) {
                providers.add(new Provider(
                        "Cloudflare",
                        "https://api.cloudflare.com/client/v4/accounts/" + config.cloudflareAccountId()
                                + "/ai/v1/chat/completions",
                        config.cloudflareApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.ollamaApiKey())) {
            for (String model : models(config.ollamaModels())) {
                providers.add(new Provider(
                        "Ollama",
                        "https://ollama.com/v1/chat/completions",
                        config.ollamaApiKey(),
                        model,
                        Map.of(),
                        false));
            }
        }
        if (hasText(config.sambaNovaApiKey())) {
            for (String model : models(config.sambaNovaModels())) {
                providers.add(new Provider(
                        "SambaNova",
                        "https://api.sambanova.ai/v1/chat/completions",
                        config.sambaNovaApiKey(),
                        model,
                        Map.of(),
                        false));
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
                .filter(RoundRobinGenerativeAiResponder::hasText)
                .toList();
    }

    record Provider(String displayName, String url, String apiKey, String model,
                    Map<String, String> extraHeaders, boolean disableReasoning) {
    }

    private static final class ReasoningMandatoryException extends RuntimeException {
        public ReasoningMandatoryException(String message) {
            super(message);
        }
    }
}
