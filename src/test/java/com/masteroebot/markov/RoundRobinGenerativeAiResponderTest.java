package com.masteroebot.markov;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinGenerativeAiResponderTest {
    @Test
    void cyclesProvidersOnEachInvocation() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Cerebras", "https://cerebras.example/chat", "ck", "cm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?", "reply"));

        responder.generateReply(request).join();
        responder.generateReply(request).join();
        responder.generateReply(request).join();
        responder.generateReply(request).join();

        assertEquals(List.of(
                URI.create("https://cerebras.example/chat"),
                URI.create("https://groq.example/chat"),
                URI.create("https://openrouter.example/chat"),
                URI.create("https://cerebras.example/chat")
        ), client.uris);
    }

    @Test
    void disablesReasoningOnlyForOpenRouter() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        responder.generateReply(request).join();
        responder.generateReply(request).join();

        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(0).contains("\"reasoning\""));
        DataObject reasoning = DataObject.fromJson(client.bodies.get(1)).getObject("reasoning");
        assertEquals("none", reasoning.getString("effort"));
        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(1).contains("\"exclude\""));
    }

    @Test
    void hidesGroqReasoningOutputForReasoningModels() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "qwen/qwen3-32b", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "openai/gpt-oss-20b", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "llama-3.3-70b-versatile", Map.of(), false)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        responder.generateReply(request).join();
        responder.generateReply(request).join();
        responder.generateReply(request).join();

        DataObject qwenPayload = DataObject.fromJson(client.bodies.get(0));
        assertEquals("hidden", qwenPayload.getString("reasoning_format"));
        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(0).contains("\"include_reasoning\""));

        DataObject gptOssPayload = DataObject.fromJson(client.bodies.get(1));
        org.junit.jupiter.api.Assertions.assertFalse(gptOssPayload.getBoolean("include_reasoning"));
        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(1).contains("\"reasoning_format\""));

        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(2).contains("\"include_reasoning\""));
        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(2).contains("\"reasoning_format\""));
    }

    @Test
    void retriesWithMinimalEffortWhenReasoningIsMandatory() {
        RecordingHttpClient client = new RecordingHttpClient();
        // First request fails with mandatory reasoning error, second succeeds
        client.responseSequence.add(new StringResponse(null, 400, "{\"error\":{\"message\":\"Reasoning is mandatory\"}}"));
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok after retry\"}}]}"));

        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        String reply = responder.generateReply(request).join();

        assertEquals("ok after retry", reply);
        assertEquals(2, client.bodies.size());
        assertEquals("none", DataObject.fromJson(client.bodies.get(0)).getObject("reasoning").getString("effort"));
        assertEquals("minimal", DataObject.fromJson(client.bodies.get(1)).getObject("reasoning").getString("effort"));
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final List<URI> uris = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<HttpResponse<String>> responseSequence = new ArrayList<>();
        private int responseIndex = 0;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            uris.add(request.uri());
            bodies.add(readBody(request));
            HttpResponse<String> response = responseIndex < responseSequence.size()
                    ? responseSequence.get(responseIndex++)
                    : new StringResponse(request, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        private String readBody(HttpRequest request) {
            StringBodySubscriber subscriber = new StringBodySubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.bodyFuture.join();
        }
    }

    private static final class StringBodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        private final StringBuilder body = new StringBuilder();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            bodyFuture.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            bodyFuture.complete(body.toString());
        }
    }

    private record StringResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request != null ? request.uri() : URI.create("http://localhost");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
