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

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toJson()));

        for (Map.Entry<String, String> header : provider.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
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

    private static List<Provider> buildProviders(GenerativeAiConfig config) {
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
            providers.add(new Provider(
                    "Groq",
                    "https://api.groq.com/openai/v1/chat/completions",
                    config.groqApiKey(),
                    config.groqModel(),
                    Map.of(),
                    false));
        }
        if (hasText(config.openrouterApiKey())) {
            for (String model : config.openrouterModels()) {
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
        return providers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
