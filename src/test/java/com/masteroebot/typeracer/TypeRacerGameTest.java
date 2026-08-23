package com.masteroebot.typeracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TypeRacerGameTest {

    @Test
    void newGameStartsInWaitingState() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        assertEquals(TypeRacerGame.State.WAITING, game.getState());
    }

    @Test
    void hostIsAutoAddedAsPlayer() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        assertEquals(1, game.getPlayers().size());
        assertTrue(game.isPlayer(1L));
    }

    @Test
    void addPlayerAddsNewPlayer() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.addPlayer(2L);
        assertEquals(2, game.getPlayers().size());
        assertTrue(game.isPlayer(2L));
    }

    @Test
    void addPlayerDoesNotDuplicate() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.addPlayer(1L);
        assertEquals(1, game.getPlayers().size());
    }

    @Test
    void targetTextIsNotBlank() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        assertNotNull(game.getTargetText());
        assertFalse(game.getTargetText().isBlank());
    }

    @Test
    void submitFailsBeforeRaceStarts() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        TypeRacerGame.PlayerResult result = game.submit(1L, game.getTargetText());
        assertEquals(TypeRacerGame.PlayerResult.NOT_RACING, result);
    }

    @Test
    void submitAcceptedWithCorrectText() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.startRace();
        TypeRacerGame.PlayerResult result = game.submit(1L, game.getTargetText());
        assertEquals(TypeRacerGame.PlayerResult.ACCEPTED, result);
    }

    @Test
    void submitRejectsWrongText() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.startRace();
        TypeRacerGame.PlayerResult result = game.submit(1L, "completely wrong text");
        assertEquals(TypeRacerGame.PlayerResult.WRONG_TEXT, result);
    }

    @Test
    void submitIgnoresExtraWhitespace() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.startRace();
        String padded = "  " + game.getTargetText() + "  ";
        TypeRacerGame.PlayerResult result = game.submit(1L, padded);
        assertEquals(TypeRacerGame.PlayerResult.ACCEPTED, result);
    }

    @Test
    void submitRejectsDuplicateFinish() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.startRace();
        game.submit(1L, game.getTargetText());
        TypeRacerGame.PlayerResult result = game.submit(1L, game.getTargetText());
        assertEquals(TypeRacerGame.PlayerResult.ALREADY_FINISHED, result);
    }

    @Test
    void allFinishedTrueWhenAllPlayersSubmit() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.addPlayer(2L);
        game.startRace();
        game.submit(1L, game.getTargetText());
        assertFalse(game.allFinished());
        game.submit(2L, game.getTargetText());
        assertTrue(game.allFinished());
    }

    @Test
    void resultsAreSortedByTime() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.addPlayer(2L);
        game.startRace();
        game.submit(1L, game.getTargetText());
        game.submit(2L, game.getTargetText());
        var results = game.getResults();
        assertEquals(2, results.size());
        assertTrue(results.get(0).elapsedMillis() <= results.get(1).elapsedMillis());
    }

    @Test
    void finishSetsStateToFinished() {
        TypeRacerGame game = new TypeRacerGame(1L, 100L, 1);
        game.finish();
        assertEquals(TypeRacerGame.State.FINISHED, game.getState());
    }
}
