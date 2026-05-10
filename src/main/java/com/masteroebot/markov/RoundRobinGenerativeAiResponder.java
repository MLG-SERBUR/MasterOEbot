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
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
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

        Provider provider = providers.get(Math.floorMod(nextProvider.getAndIncrement(), providers.size()));
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(provider, request);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        System.out.println("Starting " + provider.displayName() + " (" + provider.model() + ") request...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response));
    }

    private HttpRequest buildRequest(Provider provider, GenerativeAiRequest request) {
        DataObject payload = DataObject.empty()
                .put("model", provider.model())
                .put("stream", false)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .add(DataObject.empty()
                                .put("role", "user")
                                .put("content", String.join("\n", request.recentMessages()))));

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
            throw new IllegalStateException(provider.displayName() + " (" + provider.model()
                    + ") failed. Status: " + response.statusCode() + ", Body: " + response.body());
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
                    Map.of()));
        }
        if (hasText(config.groqApiKey())) {
            providers.add(new Provider(
                    "Groq",
                    "https://api.groq.com/openai/v1/chat/completions",
                    config.groqApiKey(),
                    config.groqModel(),
                    Map.of()));
        }
        if (hasText(config.openrouterApiKey())) {
            providers.add(new Provider(
                    "OpenRouter",
                    "https://openrouter.ai/api/v1/chat/completions",
                    config.openrouterApiKey(),
                    config.openrouterModel(),
                    Map.of(
                            "HTTP-Referer", "https://github.com/RoboMWM/MasterOEbot",
                            "X-Title", "MasterOEbot")));
        }
        return providers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Provider(String displayName, String url, String apiKey, String model, Map<String, String> extraHeaders) {
    }
}
