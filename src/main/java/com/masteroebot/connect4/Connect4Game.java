package com.masteroebot.connect4;

import java.util.Locale;

public class Connect4Game {
    public static final int ROWS = 6;
    public static final int COLUMNS = 7;

    private final long playerOneId;
    private final long playerTwoId;
    private final char[][] board;
    private int currentTurnPlayer;
    private long winnerId;
    private boolean finished;
    private static final int[] PREFERRED_COLUMNS = {3, 2, 4, 1, 5, 0, 6};

    public Connect4Game(long playerOneId, long playerTwoId) {
        this.playerOneId = playerOneId;
        this.playerTwoId = playerTwoId;
        this.currentTurnPlayer = 1;
        this.board = new char[ROWS][COLUMNS];

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                board[row][col] = 'o';
            }
        }
    }

    public MoveResult makeMove(long userId, String moveText) {
        if (finished) {
            return MoveResult.error("This game is already finished.");
        }
        if (userId != playerOneId && userId != playerTwoId) {
            return MoveResult.error("You're not one of the selected players.");
        }
        if (userId != getCurrentTurn()) {
            return MoveResult.error("It's not your turn.");
        }

        ParsedMove move = parseMove(moveText);
        if (!move.valid()) {
            return MoveResult.error(move.errorMessage());
        }

        int dropRow = findDropRow(move.column());
        if (dropRow < 0) {
            return MoveResult.error("That column is full.");
        }

        if (dropRow != move.row()) {
            char expectedRowLabel = toRowLabel(dropRow);
            return MoveResult.error("Invalid slot for gravity. This piece would land on " + expectedRowLabel + (move.column() + 1) + ".");
        }

        char piece = pieceForPlayer(currentTurnPlayer);
        board[dropRow][move.column()] = piece;

        if (hasConnectFour(dropRow, move.column(), piece)) {
            finished = true;
            winnerId = userId;
            return MoveResult.win("Connect 4!", dropRow, move.column());
        }

        if (isBoardFull()) {
            finished = true;
            winnerId = 0;
            return MoveResult.draw("Board is full. It's a draw.", dropRow, move.column());
        }

        currentTurnPlayer = currentTurnPlayer == 1 ? 2 : 1;
        return MoveResult.success("Move placed.", dropRow, move.column());
    }

    public String renderBoard() {
        StringBuilder builder = new StringBuilder();
        for (int row = 0; row < ROWS; row++) {
            builder.append(toRowLabel(row));
            for (int col = 0; col < COLUMNS; col++) {
                builder.append(board[row][col]);
            }
            if (row < ROWS - 1) {
                builder.append('\n');
            }
        }
        builder.append("\n 1234567");
        return builder.toString();
    }

    public String chooseBotMove() {
        if (finished) {
            return null;
        }

        char currentPiece = pieceForPlayer(currentTurnPlayer);
        String winningMove = findMoveCompleting(currentPiece);
        if (winningMove != null) {
            return winningMove;
        }

        char opponentPiece = pieceForPlayer(currentTurnPlayer == 1 ? 2 : 1);
        String blockingMove = findMoveCompleting(opponentPiece);
        if (blockingMove != null) {
            return blockingMove;
        }

        for (int col : PREFERRED_COLUMNS) {
            int row = findDropRow(col);
            if (row >= 0) {
                return formatMove(row, col);
            }
        }

        return null;
    }

    public long getCurrentTurn() {
        return currentTurnPlayer == 1 ? playerOneId : playerTwoId;
    }

    public int getCurrentTurnPlayer() {
        return currentTurnPlayer;
    }

    public long getPlayerOneId() {
        return playerOneId;
    }

    public long getPlayerTwoId() {
        return playerTwoId;
    }

    public long getWinnerId() {
        return winnerId;
    }

    public boolean isFinished() {
        return finished;
    }

    private ParsedMove parseMove(String rawMove) {
        if (rawMove == null || rawMove.isBlank()) {
            return ParsedMove.invalid("Missing move. Use format like F7.");
        }

        String normalized = rawMove.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 2) {
            return ParsedMove.invalid("Move must be exactly 2 characters, like F7.");
        }

        char rowChar = normalized.charAt(0);
        char colChar = normalized.charAt(1);

        if (rowChar < 'A' || rowChar > 'F') {
            return ParsedMove.invalid("Row must be A-F.");
        }
        if (colChar < '1' || colChar > '7') {
            return ParsedMove.invalid("Column must be 1-7.");
        }

        int row = rowChar - 'A';
        int col = colChar - '1';
        return ParsedMove.valid(row, col);
    }

    private int findDropRow(int col) {
        for (int row = ROWS - 1; row >= 0; row--) {
            if (board[row][col] == 'o') {
                return row;
            }
        }
        return -1;
    }

    private boolean isBoardFull() {
        for (int col = 0; col < COLUMNS; col++) {
            if (board[0][col] == 'o') {
                return false;
            }
        }
        return true;
    }

    private char pieceForPlayer(int playerNumber) {
        return playerNumber == 1 ? '●' : '◍';
    }

    private String findMoveCompleting(char piece) {
        for (int col : PREFERRED_COLUMNS) {
            int row = findDropRow(col);
            if (row < 0) {
                continue;
            }

            board[row][col] = piece;
            boolean wins = hasConnectFour(row, col, piece);
            board[row][col] = 'o';

            if (wins) {
                return formatMove(row, col);
            }
        }

        return null;
    }

    private String formatMove(int row, int col) {
        return String.valueOf(toRowLabel(row)) + (col + 1);
    }

    private boolean hasConnectFour(int row, int col, char piece) {
        return countLine(row, col, piece, 0, 1) >= 4
                || countLine(row, col, piece, 1, 0) >= 4
                || countLine(row, col, piece, 1, 1) >= 4
                || countLine(row, col, piece, 1, -1) >= 4;
    }

    private int countLine(int row, int col, char piece, int dRow, int dCol) {
        int count = 1;
        count += countDirection(row, col, piece, dRow, dCol);
        count += countDirection(row, col, piece, -dRow, -dCol);
        return count;
    }

    private int countDirection(int row, int col, char piece, int dRow, int dCol) {
        int count = 0;
        int currentRow = row + dRow;
        int currentCol = col + dCol;

        while (currentRow >= 0 && currentRow < ROWS && currentCol >= 0 && currentCol < COLUMNS && board[currentRow][currentCol] == piece) {
            count++;
            currentRow += dRow;
            currentCol += dCol;
        }

        return count;
    }

    private char toRowLabel(int row) {
        return (char) ('A' + row);
    }

    private record ParsedMove(boolean valid, int row, int column, String errorMessage) {
        static ParsedMove valid(int row, int column) {
            return new ParsedMove(true, row, column, null);
        }

        static ParsedMove invalid(String message) {
            return new ParsedMove(false, -1, -1, message);
        }
    }

    public record MoveResult(Status status, String message, int row, int column) {
        public static MoveResult success(String message, int row, int column) {
            return new MoveResult(Status.SUCCESS, message, row, column);
        }

        public static MoveResult win(String message, int row, int column) {
            return new MoveResult(Status.WIN, message, row, column);
        }

        public static MoveResult draw(String message, int row, int column) {
            return new MoveResult(Status.DRAW, message, row, column);
        }

        public static MoveResult error(String message) {
            return new MoveResult(Status.ERROR, message, -1, -1);
        }
    }

    public enum Status {
        SUCCESS,
        WIN,
        DRAW,
        ERROR
    }
}
