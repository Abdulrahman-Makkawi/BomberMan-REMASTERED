import java.io.Serializable;

public class GameState implements Serializable {
    public int[][] arenaGrid; // The 15x15 structural matrix layer representing tile types
    public int remainingTime;

    public GameState(int[][] arenaGrid, int remainingTime) {
        this.arenaGrid = arenaGrid;
        this.remainingTime = remainingTime;
    }
}
