import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Scanner;

public class PlayerHandler extends Thread {
    private Socket clientSocket;
    private PrintStream streamWriter;
    private String associatedToken = null;

    public PlayerHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    // Helper method allowing the GameEngine to push messages directly down this socket
    public void sendDirectMessage(String message) {
        if (streamWriter != null) {
            streamWriter.println(message);
        }
    }

    @Override
    public void run() {
        try (
                Scanner streamReader = new Scanner(clientSocket.getInputStream());
                PrintStream writer = new PrintStream(clientSocket.getOutputStream())
        ) {
            this.streamWriter = writer;

            // The very first line from the client MUST be its authentication token
            if (streamReader.hasNextLine()) {
                String initialLine = streamReader.nextLine();
                String[] authSegments = initialLine.split(" ");
                if (authSegments[0].toUpperCase().equals("CONNECT")) {
                    this.associatedToken = authSegments[1];

                    UserSession session = GameServer.activePlayers.get(associatedToken);
                    if (session != null) {
                        if (!session.isConnected) {
                            // RECONNECTION ROUTINE SUCCESSFUL!
                            session.isConnected = true;
                            System.out.println("Reconnection verified! [" + session.username + "] has returned safely.");
                        } else {
                            // Initial fresh connection logic
                            GameEngine.assignInitialSpawnCorner(session);
                        }
                    }

                    GameServer.activeHandlers.put(associatedToken, this);
                    GameServer.broadcastGameState();
                }
            }


            // Main transaction command processing loop
            while (streamReader.hasNextLine()) {
                String rawIncomingRequest = streamReader.nextLine();
                String[] parsedSegments = rawIncomingRequest.split(" ");
                String actionCommand = parsedSegments[0].toUpperCase();

                if (actionCommand.equals("PLANTED_BOMB")) {
                    String playerToken = parsedSegments[1];
                    int tileX = Integer.parseInt(parsedSegments[2]);
                    int tileY = Integer.parseInt(parsedSegments[3]);

                    // FIX: Validate authoritatively that the player exists and is ALIVE before placing a bomb
                    UserSession session = GameServer.activePlayers.get(playerToken);
                    if (session != null && session.isAlive && session.isConnected) {
                        GameEngine.processBomb(playerToken, tileX, tileY);
                        //streamWriter.println("CONFIRM_BOMB_PLANTED " + tileX + " " + tileY);
                    } else {
                        streamWriter.println("SERVER_ALERT_INPUT_DENIED Action blocked: You are dead or disconnected!");
                    }
                }
                else if (actionCommand.equals("PLAYER_MOVE")) {
                    String playerToken = parsedSegments[1];
                    int targetX = Integer.parseInt(parsedSegments[2]);
                    int targetY = Integer.parseInt(parsedSegments[3]);

                    UserSession session = GameServer.activePlayers.get(playerToken);
                    if (session != null && session.isAlive) {
                        int currentX = session.x;
                        int currentY = session.y;

                        int deltaX = Math.abs(targetX - currentX);
                        int deltaY = Math.abs(targetY - currentY);

                        boolean isValidDistance = (deltaX + deltaY == 1);
                        boolean isWithinBounds = (targetX >= 0 && targetX < 15 && targetY >= 0 && targetY < 15);
                        boolean isWalkable = false;

                        if (isWithinBounds) {
                            int targetTile = GameEngine.arenaGrid[targetX][targetY];
                            isWalkable = (targetTile == 0); //if bombs transparent add: || targetTile == 3
                        }

                        if (isValidDistance && isWithinBounds && isWalkable) {
                            session.x = targetX;
                            session.y = targetY;
                            GameServer.broadcastGameState();
                        } else {
                            sendDirectMessage("GAME_STATE_RESET " + currentX + " " + currentY);
                        }
                    }
                }
                else if (actionCommand.equals("PLAYER_DEAD")) {
                    String deadToken = parsedSegments[1];
                    if (GameServer.activePlayers.containsKey(deadToken)) {
                        GameServer.activePlayers.get(deadToken).isAlive = false;
                    }
                    streamWriter.println("MATCH_OVER_STATE_UPDATED");
                }
            }
        } catch (IOException e) {
            System.err.println("Player connection lost on token: " + associatedToken);
        } finally {
            if (associatedToken != null) {
                UserSession session = GameServer.activePlayers.get(associatedToken);
                if (session != null) {
                    session.isConnected = false; // Flag that they went offline
                    GameServer.activeHandlers.remove(associatedToken);

                    // Launch a separate independent background thread to manage the 10-second grace period
                    new Thread(() -> {
                        try {
                            System.out.println(">> Grace Period started for [" + session.username + "] (10s to reconnect)...");
                            Thread.sleep(10000); // Wait 10 seconds

                            // Check if they are still offline after 10 seconds
                            if (!session.isConnected && session.isAlive) {
                                session.isAlive = false; // Authoritatively mark them dead/lost
                                System.out.println(">> Grace Period expired. [" + session.username + "] marked as dead.");

                                // Notify remaining players and evaluate victory conditions
                                GameServer.broadcastGameState();
                                // Trigger check inside GameEngine to see if someone won because of this drop
                                java.lang.reflect.Method evalMethod;
                                try {
                                    evalMethod = GameEngine.class.getDeclaredMethod("evaluateGameStatus");
                                    evalMethod.setAccessible(true);
                                    evalMethod.invoke(null);
                                } catch (Exception ignored) {}
                            }
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                }
            }
        }
    }
}