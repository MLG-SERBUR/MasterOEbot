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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinGenerativeAiResponder implements GenerativeAiResponder {
    private static final int REQUEST_TIMEOUT_SECONDS = 18;
    private final HttpClient client;
    private final List<Provider> providers;
    private final String systemPrompt;
    private final AtomicInteger nextProvider = new AtomicInteger(0);

    public RoundRobinGenerativeAiResponder(GenerativeAiConfig config) {
        this(HttpClient.newHttpClient(), buildProviders(config), config.systemPrompt());
    }

    RoundRobinGenerativeAiResponder(HttpClient client, List<Provider> providers, String systemPrompt) {
        this.client = client;
        this.providers = List.copyOf(providers);
        this.systemPrompt = systemPrompt;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No generative AI providers configured"));
        }
        long deadlineMs = System.currentTimeMillis() + (REQUEST_TIMEOUT_SECONDS * 1000L);
        return attemptGenerateReply(request, deadlineMs, 0, "none");
    }

    private CompletableFuture<String> attemptGenerateReply(GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort) {
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (attempts > 0 && timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not enough time remaining to try next provider"));
        }

        Provider provider = providers.get(Math.floorMod(nextProvider.getAndIncrement(), providers.size()));
        return attemptWithProvider(provider, request, deadlineMs, attempts, reasoningEffort);
    }

    private CompletableFuture<String> attemptWithProvider(Provider provider, GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(provider, request, reasoningEffort);
        } catch (Exception e) {
            return fallback(request, deadlineMs, attempts + 1, e);
        }

        System.out.println("Starting " + provider.displayName() + " (" + provider.model() + ") request with reasoning effort: " + (provider.disableReasoning() ? reasoningEffort : "default") + "...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response))
                .exceptionallyCompose(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ReasoningMandatoryException && "none".equals(reasoningEffort)) {
                        System.out.println("Reasoning is mandatory for " + provider.model() + ". Retrying same provider with 'minimal' effort...");
                        return attemptWithProvider(provider, request, deadlineMs, attempts, "minimal");
                    }
                    return fallback(request, deadlineMs, attempts + 1, e);
                });
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int attempts, Throwable e) {
        System.err.println("Provider failed: " + e.toString());
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (attempts >= providers.size() || timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(e);
        }
        System.out.println("Retrying next provider...");
        return attemptGenerateReply(request, deadlineMs, attempts, "none");
    }

    private HttpRequest buildRequest(Provider provider, GenerativeAiRequest request, String reasoningEffort) {
        DataObject payload = DataObject.empty()
                .put("model", provider.model())
                .put("stream", false)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty()
                                .put("role", "system")
                                .put("content", request.systemPromptOverride() == null
                                        ? systemPrompt
                                        : request.systemPromptOverride()))
                        .add(DataObject.empty()
                                .put("role", "user")
                                .put("content", String.join("\n", request.recentMessages()))));
        if (provider.disableReasoning()) {
            payload.put("reasoning", DataObject.empty()
                    .put("effort", reasoningEffort));
        }
        applyArliAiNonThinkingDefaults(provider, payload);
        suppressGroqReasoningOutput(provider, payload);

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

    private void suppressGroqReasoningOutput(Provider provider, DataObject payload) {
        if (!"Groq".equals(provider.displayName())) {
            return;
        }

        String model = provider.model().toLowerCase();
        if (model.startsWith("openai/gpt-oss-")) {
            payload.put("include_reasoning", false);
        } else if (model.startsWith("qwen/qwen3-")) {
            payload.put("reasoning_format", "hidden");
        }
    }

    private void applyArliAiNonThinkingDefaults(Provider provider, DataObject payload) {
        if (!"ArliAI".equals(provider.displayName())) {
            return;
        }

        payload.put("temperature", 0.7);
        payload.put("top_p", 0.8);
        payload.put("top_k", 20);
        payload.put("min_p", 0.0);
        payload.put("presence_penalty", 1.5);
        payload.put("repetition_penalty", 1.0);
        payload.put("output_kind", "delta");
        payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
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
            providers.add(new Provider(
                    "Cerebras",
                    "https://api.cerebras.ai/v1/chat/completions",
                    config.cerebrasApiKey(),
                    config.cerebrasModel(),
                    Map.of(),
                    false));
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
        if (hasText(config.arliApiKey())) {
            for (String model : models(config.arliModels())) {
                providers.add(new Provider(
                        "ArliAI",
                        "https://api.arliai.com/v1/chat/completions",
                        config.arliApiKey(),
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
