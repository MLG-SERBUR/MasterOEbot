package com.masteroebot.connect4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Connect4GameTest {

    @Test
    void rejectsNonGravityMove() {
        Connect4Game game = new Connect4Game(1L, 2L);
        Connect4Game.MoveResult result = game.makeMove(1L, "A1");

        assertEquals(Connect4Game.Status.ERROR, result.status());
        assertTrue(result.message().contains("gravity"));
    }

    @Test
    void acceptsBottomMoveAndAlternatesTurn() {
        Connect4Game game = new Connect4Game(1L, 2L);
        Connect4Game.MoveResult result = game.makeMove(1L, "F1");

        assertEquals(Connect4Game.Status.SUCCESS, result.status());
        assertEquals(2L, game.getCurrentTurn());
    }

    @Test
    void detectsHorizontalWin() {
        Connect4Game game = new Connect4Game(1L, 2L);

        game.makeMove(1L, "F1");
        game.makeMove(2L, "F7");

        game.makeMove(1L, "F2");
        game.makeMove(2L, "E7");

        game.makeMove(1L, "F3");
        game.makeMove(2L, "D7");

        Connect4Game.MoveResult winningMove = game.makeMove(1L, "F4");

        assertEquals(Connect4Game.Status.WIN, winningMove.status());
        assertEquals(1L, game.getWinnerId());
        assertTrue(game.isFinished());
    }
}
