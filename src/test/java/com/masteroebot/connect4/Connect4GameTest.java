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
    void allowsSameUserToPlayBothSides() {
        Connect4Game game = new Connect4Game(1L, 1L);

        assertEquals(Connect4Game.Status.SUCCESS, game.makeMove(1L, "F1").status());
        assertEquals(2, game.getCurrentTurnPlayer());
        assertEquals(Connect4Game.Status.SUCCESS, game.makeMove(1L, "E1").status());
        assertEquals(1, game.getCurrentTurnPlayer());
        assertTrue(game.renderBoard().contains("E◍"));
        assertTrue(game.renderBoard().contains("F●"));
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

    @Test
    void botChoosesCenterColumnOnEmptyBoard() {
        Connect4Game game = new Connect4Game(1L, 2L);

        assertEquals("F4", game.chooseBotMove());
    }

    @Test
    void botChoosesWinningMoveBeforeBlock() {
        Connect4Game game = new Connect4Game(1L, 2L);

        game.makeMove(1L, "F1");
        game.makeMove(2L, "F7");
        game.makeMove(1L, "F2");
        game.makeMove(2L, "E7");
        game.makeMove(1L, "F3");
        game.makeMove(2L, "D7");

        assertEquals("F4", game.chooseBotMove());
    }

    @Test
    void botBlocksOpponentWinningMove() {
        Connect4Game game = new Connect4Game(1L, 2L);

        game.makeMove(1L, "F1");
        game.makeMove(2L, "F7");
        game.makeMove(1L, "F2");
        game.makeMove(2L, "E7");
        game.makeMove(1L, "E1");
        game.makeMove(2L, "D7");

        assertEquals("C7", game.chooseBotMove());
    }
}
