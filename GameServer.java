import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.Naming;
import java.util.concurrent.ConcurrentHashMap;

public class GameServer {
    public static int rmiPort = 5000;
    public static int tcpPort = 30000;

    public static ConcurrentHashMap<String, UserSession> activePlayers = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> activeRooms = new ConcurrentHashMap<>();

    // Added to let the engine map a player token back to its open TCP socket
    public static ConcurrentHashMap<String, PlayerHandler> activeHandlers = new ConcurrentHashMap<>();


    public static void broadcastGameState() {
        StringBuilder packet = new StringBuilder("GAME_STATE ");

        // 1. Append the 15x15 map grid layout
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                packet.append(GameEngine.arenaGrid[i][j]).append(",");
            }
        }

        // Delimiter separating the map data from player data
        packet.append("|");

        // 2. Append all active player profiles
        for (UserSession player : activePlayers.values()) {
            packet.append(player.username).append(",")
                    .append(player.x).append(",")
                    .append(player.y).append(",")
                    .append(player.isAlive).append(",")
                    .append(player.isInvisible).append(";");
        }

        String finalPacket = packet.toString();

        // Broadcast the unified state to all connected socket handlers
        for (PlayerHandler handler : activeHandlers.values()) {
            handler.sendDirectMessage(finalPacket);
        }
    }


    public static void main(String[] args) {
        try {
            IBomberLobby lobbyEngine = new BomberLobbyImpl();

            // Change "127.0.0.1" to "0.0.0.0" to open up your RMI registry listener globally
            Naming.rebind("rmi://0.0.0.0:" + rmiPort + "/bomberlobby", lobbyEngine);
            System.out.println("Game Server RMI Engine configured globally.");

            ServerSocket tcpServerSocket = new ServerSocket(tcpPort);
            System.out.println("TCP Event Dispatcher active on data port " + tcpPort);

            GameEngine.initializeMap();

            while (true) {
                Socket clientCommunicationSocket = tcpServerSocket.accept();
                System.out.println("New TCP link established from: " + clientCommunicationSocket.getInetAddress());

                PlayerHandler workerConnectionThread = new PlayerHandler(clientCommunicationSocket);
                workerConnectionThread.start();
            }
        } catch (IOException e) {
            System.err.println("Fatal Sockets Error: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}