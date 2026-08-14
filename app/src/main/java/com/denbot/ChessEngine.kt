package com.denbot

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Motor de evaluacion con minimax + poda alfa-beta.
 * No requiere Stockfish nativo (evita NDK/JNI complejo).
 * Profundidad y tiempo escalan segun ELO y modo bala.
 */
class ChessEngine {

    private val pieceValues = mapOf(
        PieceType.PAWN to 100, PieceType.KNIGHT to 320, PieceType.BISHOP to 330,
        PieceType.ROOK to 500, PieceType.QUEEN to 900, PieceType.KING to 20000
    )

    private val pawnTable = intArrayOf(
        0,0,0,0,0,0,0,0,
        50,50,50,50,50,50,50,50,
        10,10,20,30,30,20,10,10,
        5,5,10,25,25,10,5,5,
        0,0,0,20,20,0,0,0,
        5,-5,-10,0,0,-10,-5,5,
        5,10,10,-20,-20,10,10,5,
        0,0,0,0,0,0,0,0
    )
    private val knightTable = intArrayOf(
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,0,0,0,0,-20,-40,
        -30,0,10,15,15,10,0,-30,
        -30,5,15,20,20,15,5,-30,
        -30,0,15,20,20,15,0,-30,
        -30,5,10,15,15,10,5,-30,
        -40,-20,0,5,5,0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    )

    private var deadline = 0L

    fun getBestMove(fen: String, elo: Int, bulletMode: Boolean): String? {
        val board = Board()
        board.loadFromFen(fen)
        val moves = board.legalMoves()
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moveToUci(moves[0])

        val depth = when {
            bulletMode -> if (elo < 1800) 1 else 2
            elo < 1000 -> 1
            elo < 1600 -> 2
            elo < 2200 -> 3
            else -> 4
        }
        val timeBudgetMs = when {
            bulletMode -> 80L
            elo < 1000 -> 200L
            elo < 1800 -> 600L
            elo < 2500 -> 1500L
            else -> 3000L
        }

        deadline = System.currentTimeMillis() + timeBudgetMs

        val maximizing = board.sideToMove == Side.WHITE
        var bestMove: Move? = null
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE

        val candidateMoves = orderMoves(board, moves)

        for (move in candidateMoves) {
            if (System.currentTimeMillis() > deadline) break
            board.doMove(move)
            val score = minimax(board, depth - 1, Int.MIN_VALUE, Int.MAX_VALUE, !maximizing)
            board.undoMove()

            val adjustedScore = if (elo < 1400) score + (-30..30).random() else score

            if (maximizing && adjustedScore > bestScore) { bestScore = adjustedScore; bestMove = move }
            else if (!maximizing && adjustedScore < bestScore) { bestScore = adjustedScore; bestMove = move }
        }

        return (bestMove ?: candidateMoves.firstOrNull())?.let { moveToUci(it) }
    }

    private fun minimax(board: Board, depth: Int, alphaIn: Int, betaIn: Int, maximizing: Boolean): Int {
        if (depth == 0 || System.currentTimeMillis() > deadline) return evaluate(board)

        val moves = board.legalMoves()
        if (moves.isEmpty()) {
            return if (board.isMated) (if (maximizing) -100000 - depth else 100000 + depth) else 0
        }

        var alpha = alphaIn; var beta = betaIn
        if (maximizing) {
            var maxEval = Int.MIN_VALUE
            for (move in moves) {
                board.doMove(move)
                val eval = minimax(board, depth - 1, alpha, beta, false)
                board.undoMove()
                maxEval = maxOf(maxEval, eval)
                alpha = maxOf(alpha, eval)
                if (beta <= alpha) break
            }
            return maxEval
        } else {
            var minEval = Int.MAX_VALUE
            for (move in moves) {
                board.doMove(move)
                val eval = minimax(board, depth - 1, alpha, beta, true)
                board.undoMove()
                minEval = minOf(minEval, eval)
                beta = minOf(beta, eval)
                if (beta <= alpha) break
            }
            return minEval
        }
    }

    private fun orderMoves(board: Board, moves: List<Move>): List<Move> {
        return moves.sortedByDescending { move ->
            val captured = board.getPiece(move.to)
            if (captured != Piece.NONE) pieceValues[captured.pieceType] ?: 0 else 0
        }
    }

    private fun evaluate(board: Board): Int {
        var score = 0
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val piece = board.getPiece(sq)
            if (piece == Piece.NONE) continue
            val value = pieceValues[piece.pieceType] ?: 0
            val posBonus = positionalBonus(piece, sq)
            val total = value + posBonus
            score += if (piece.pieceSide == Side.WHITE) total else -total
        }
        val mobility = board.legalMoves().size
        score += if (board.sideToMove == Side.WHITE) mobility else -mobility
        return score
    }

    private fun positionalBonus(piece: Piece, sq: Square): Int {
        val idx = sq.ordinal
        val whiteIdx = if (piece.pieceSide == Side.WHITE) 63 - idx else idx
        return when (piece.pieceType) {
            PieceType.PAWN -> pawnTable.getOrElse(whiteIdx) { 0 }
            PieceType.KNIGHT -> knightTable.getOrElse(whiteIdx) { 0 }
            else -> 0
        }
    }

    private fun moveToUci(move: Move): String {
        val promo = if (move.promotion != Piece.NONE) {
            move.promotion.fenSymbol.lowercase()
        } else ""
        return "${move.from.value().lowercase()}${move.to.value().lowercase()}$promo"
    }
}
