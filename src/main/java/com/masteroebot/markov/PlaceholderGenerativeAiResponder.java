package com.masteroebot.markov;

import java.util.concurrent.CompletableFuture;

public class PlaceholderGenerativeAiResponder implements GenerativeAiResponder {
    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Generative AI endpoint is not implemented"));
    }
}
