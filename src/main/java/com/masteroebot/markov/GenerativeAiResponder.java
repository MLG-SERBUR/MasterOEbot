package com.masteroebot.markov;

import java.util.concurrent.CompletableFuture;

public interface GenerativeAiResponder {
    CompletableFuture<String> generateReply(GenerativeAiRequest request);
}
