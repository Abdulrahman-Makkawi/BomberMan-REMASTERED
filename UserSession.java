import java.io.Serializable;

public class UserSession implements Serializable {
    public String username;
    public String token;
    public int score;
    public boolean isAlive;
    public int x; // Added for position tracking
    public int y; // Added for position tracking
    public long lastHeartbeat;
    public boolean isConnected = true; // Tracks if the TCP socket thread is actively bound

    public UserSession(String username, String token) {
        this.username = username;
        this.token = token;
        this.score = 0;
        this.isAlive = true;
        this.x = 0; // Top-left starting corner
        this.y = 0;
        this.lastHeartbeat = System.currentTimeMillis();
    }
}
