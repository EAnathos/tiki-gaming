package fr.tikigaming.games.tic_tac_toe;

/** Un coup joué : la case visée. Le serveur rejoue la partie coup par coup. */
public record TicTacToeMove(int row, int column) {}
