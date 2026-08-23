package com.masteroebot.typeracer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TypeRacerGame {

    private static final String[] TEXTS = {
            "The quick brown fox jumps over the lazy dog",
            "Pack my box with five dozen liquor jugs",
            "How vexingly quick daft zebras jump",
            "The five boxing wizards jump quickly",
            "Sphinx of black quartz judge my vow",
            "Two driven jocks help fax my big quiz",
            "The jay, pig, fox, zebra, and my wolves quack",
            "Sympathizing would fix Quaker objectives",
            "A wizard's job is to vex chumps quickly in fog",
            "Watch Jeopardy Alex Trebek's fun TV quiz game",
            "We promptly judged antique ivory buckles for the next prize",
            "Crazy Frederick bought many very exquisite opal jewels",
            "A mad boxer shot a quick gloved jab to the jaw of his dizzy opponent",
            "Sixty zippers were quickly picked from the woven jute bag",
            "All questions asked by five watched experts amaze the judge"
    };

    public enum State {
        WAITING,
        COUNTDOWN,
        RACING,
        FINISHED
    }

    public enum PlayerResult {
        ACCEPTED,
        WRONG_TEXT,
        ALREADY_FINISHED,
        NOT_RACING
    }

    private final long hostId;
    private final long channelId;
    private final List<Long> players = new CopyOnWriteArrayList<>();
    private final List<RacerResult> results = new CopyOnWriteArrayList<>();
    private final String targetText;
    private State state;
    private long startTimeMillis;
    private final int gameId;

    public TypeRacerGame(long hostId, long channelId, int gameId) {
        this.hostId = hostId;
        this.channelId = channelId;
        this.gameId = gameId;
        this.targetText = pickRandomText();
        this.state = State.WAITING;
        this.players.add(hostId);
    }

    private static String pickRandomText() {
        return TEXTS[(int) (Math.random() * TEXTS.length)];
    }

    public void addPlayer(long userId) {
        if (!players.contains(userId)) {
            players.add(userId);
        }
    }

    public void startRace() {
        this.state = State.RACING;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public PlayerResult submit(long userId, String typedText) {
        if (state != State.RACING) {
            return PlayerResult.NOT_RACING;
        }
        if (!players.contains(userId)) {
            return PlayerResult.NOT_RACING;
        }
        for (RacerResult r : results) {
            if (r.userId() == userId) {
                return PlayerResult.ALREADY_FINISHED;
            }
        }

        if (!normalizeText(typedText).equals(normalizeText(targetText))) {
            return PlayerResult.WRONG_TEXT;
        }

        long elapsed = System.currentTimeMillis() - startTimeMillis;
        double seconds = elapsed / 1000.0;
        double minutes = seconds / 60.0;
        int wordCount = targetText.split("\\s+").length;
        double wpm = minutes > 0 ? Math.round((wordCount / minutes) * 10.0) / 10.0 : 0;

        results.add(new RacerResult(userId, elapsed, wpm));
        return PlayerResult.ACCEPTED;
    }

    private static String normalizeText(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public boolean allFinished() {
        if (state != State.RACING) return false;
        return results.size() >= players.size();
    }

    public long getHostId() {
        return hostId;
    }

    public long getChannelId() {
        return channelId;
    }

    public int getGameId() {
        return gameId;
    }

    public String getTargetText() {
        return targetText;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public List<Long> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public List<RacerResult> getResults() {
        List<RacerResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> Long.compare(a.elapsedMillis(), b.elapsedMillis()));
        return Collections.unmodifiableList(sorted);
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void finish() {
        this.state = State.FINISHED;
    }

    public boolean isPlayer(long userId) {
        return players.contains(userId);
    }

    public boolean hasFinished(long userId) {
        return results.stream().anyMatch(r -> r.userId() == userId);
    }

    public record RacerResult(long userId, long elapsedMillis, double wpm) {
    }
}
