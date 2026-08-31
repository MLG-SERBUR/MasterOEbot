package com.masteroebot.markov;

import java.util.concurrent.CompletableFuture;

/**
 * Shared coordination between ArliAI reaction service and second-chance reply service.
 * Ensures:
 * - Second chance only starts if reaction isn't currently awaiting ArliAI.
 * - Reaction waits and retries after second chance completes if attempted while second chance active.
 */
public class ArliAiCoordinator {
    private final Object lock = new Object();
    private boolean secondChanceActive = false;
    private CompletableFuture<Void> secondChanceFuture = CompletableFuture.completedFuture(null);
    private boolean reactionActive = false;

    public boolean isSecondChanceActive() {
        synchronized (lock) {
            return secondChanceActive;
        }
    }

    public boolean isReactionActive() {
        synchronized (lock) {
            return reactionActive;
        }
    }

    /**
     * Attempt to start second chance. Fails if reaction is active or second chance already active.
     * On success, creates a new pending future that callers can await.
     */
    public boolean tryStartSecondChance() {
        synchronized (lock) {
            if (reactionActive || secondChanceActive) {
                return false;
            }
            secondChanceActive = true;
            secondChanceFuture = new CompletableFuture<>();
            return true;
        }
    }

    public void finishSecondChance() {
        CompletableFuture<Void> toComplete;
        synchronized (lock) {
            if (!secondChanceActive) {
                return;
            }
            secondChanceActive = false;
            toComplete = secondChanceFuture;
            secondChanceFuture = CompletableFuture.completedFuture(null);
        }
        toComplete.complete(null);
    }

    /**
     * Returns a future that completes when current second chance finishes.
     * If no second chance active, returns already-completed future.
     */
    public CompletableFuture<Void> awaitSecondChance() {
        synchronized (lock) {
            if (!secondChanceActive) {
                return CompletableFuture.completedFuture(null);
            }
            return secondChanceFuture;
        }
    }

    public boolean tryStartReaction() {
        synchronized (lock) {
            if (reactionActive) {
                return false;
            }
            reactionActive = true;
            return true;
        }
    }

    public void finishReaction() {
        synchronized (lock) {
            reactionActive = false;
        }
    }
}
