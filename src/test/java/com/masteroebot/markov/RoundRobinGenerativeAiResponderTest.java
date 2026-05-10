package com.masteroebot.markov;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinGenerativeAiResponderTest {
    @Test
    void cyclesProvidersOnEachInvocation() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Cerebras", "https://cerebras.example/chat", "ck", "cm", Map.of()),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of()),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of())
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

    private static final class RecordingHttpClient extends HttpClient {
        private final List<URI> uris = new ArrayList<>();

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
            return CompletableFuture.completedFuture((HttpResponse<T>) new StringResponse(request));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StringResponse(HttpRequest request) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public String body() {
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        }

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
            return request.uri();
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
