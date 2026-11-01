import java.util.Map;

public class GameEngine {
    // 0 = Empty floor, 1 = Brick Wall (Breakable), 2 = Steel Block (Unbreakable), 3 = Bomb
    public static int[][] arenaGrid = new int[15][15];

    public static void initializeMap() {
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (i % 2 != 0 && j % 2 != 0) {
                    arenaGrid[i][j] = 2; // Fixed Steel Pillars
                } else {
                    arenaGrid[i][j] = (Math.random() > 0.4) ? 1 : 0; // Brick Walls
                }
            }
        }

        // --- SECURE SAFE SPAWN ZONES FOR ALL 4 CORNERS ---
        // Top-Left Corner (Spawn 1)
        arenaGrid[0][0] = 0; arenaGrid[0][1] = 0; arenaGrid[1][0] = 0;

        // Bottom-Right Corner (Spawn 2)
        arenaGrid[14][14] = 0; arenaGrid[14][13] = 0; arenaGrid[13][14] = 0;

        // Top-Right Corner (Spawn 3)
        arenaGrid[14][0] = 0; arenaGrid[14][1] = 0; arenaGrid[13][0] = 0;

        // Bottom-Left Corner (Spawn 4)
        arenaGrid[0][14] = 0; arenaGrid[0][13] = 0; arenaGrid[1][14] = 0;

        System.out.println("Game Engine: 15x15 Arena Grid Initialized with 4 safe spawn corners.");
    }

    /**
     * Authoritatively assigns a unique starting corner position to a brand new player
     * based on their connection order sequence.
     */
    public static synchronized void assignInitialSpawnCorner(UserSession newPlayer) {
        // Count how many players are already registered in the session mapping database
        int playerPositionIndex = GameServer.activePlayers.size() - 1;

        if (playerPositionIndex <= 0) {       // First Player: Top-Left
            newPlayer.x = 0;
            newPlayer.y = 0;
        } else if (playerPositionIndex == 1) { // Second Player: Bottom-Right
            newPlayer.x = 14;
            newPlayer.y = 14;
        } else if (playerPositionIndex == 2) { // Third Player: Top-Right
            newPlayer.x = 14;
            newPlayer.y = 0;
        } else {                               // Fourth Player (and onward): Bottom-Left
            newPlayer.x = 0;
            newPlayer.y = 14;
        }

        System.out.println("Spawn System: Assigned [" + newPlayer.username + "] to corner (" + newPlayer.x + "," + newPlayer.y + ")");
    }

    public static void processBomb(String playerToken, int bombX, int bombY) {
        arenaGrid[bombX][bombY] = 3;
        System.out.println("Bomb placed at (" + bombX + ", " + bombY + "). Ticking...");

        new Thread(() -> {
            try {
                Thread.sleep(3000); // Wait for fuse

                // Authoritatively swap the bomb tile (3) into a live Explosion fire tile (9)
                arenaGrid[bombX][bombY] = 9;
                checkKill(bombX, bombY);

                // Blow up adjacent directions up to 1 tile away
                int[][] blastDirections = {{0,1}, {0,-1}, {1,0}, {-1,0}};
                for (int[] dir : blastDirections) {
                    int targetX = bombX + dir[0];
                    int targetY = bombY + dir[1];

                    if (targetX >= 0 && targetX < 15 && targetY >= 0 && targetY < 15) {
                        int objectType = arenaGrid[targetX][targetY];
                        if (objectType == 2) continue; // Indestructible steel pillars block fire!

                        // Mark this tile as live explosion fire
                        arenaGrid[targetX][targetY] = 9;
                        checkKill(targetX, targetY);
                    }
                }

                // Broadcast the new state to all clients
                GameServer.broadcastGameState();

                // Keep the fire on screen for half a second
                Thread.sleep(500);


                // Clear out the fire tiles back to empty ground floor (0)
                arenaGrid[bombX][bombY] = 0;
                for (int[] dir : blastDirections) {
                    int targetX = bombX + dir[0];
                    int targetY = bombY + dir[1];
                    if (targetX >= 0 && targetX < 15 && targetY >= 0 && targetY < 15) {
                        if (arenaGrid[targetX][targetY] == 9) {

                            // --- ITEM DROP RATIO LOGIC ---
                            // 20% chance to drop an Invisibility item when fire clears a brick space
                            if (Math.random() < 0.05) {
                                arenaGrid[targetX][targetY] = 5; // Drop Invisibility Item
                                System.out.println("Spawn System: Invisibility item spawned at (" + targetX + "," + targetY + ")");
                            } else {
                                arenaGrid[targetX][targetY] = 0; // standard clear to floor
                            }

                        }
                    }
                }

                // Broadcast final clean map state layout with items included
                GameServer.broadcastGameState();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static synchronized void explode(int bombX, int bombY) {
        arenaGrid[bombX][bombY] = 0;
        checkKill(bombX, bombY);

        int[][] directions = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

        for (int[] dir : directions) {
            int targetX = bombX + dir[0];
            int targetY = bombY + dir[1];

            if (targetX >= 0 && targetX < 15 && targetY >= 0 && targetY < 15) {
                int tileType = arenaGrid[targetX][targetY];

                if (tileType == 2) {
                    continue;
                }

                if (tileType == 1) {
                    arenaGrid[targetX][targetY] = 0;
                    System.out.println("Wall destroyed at (" + targetX + ", " + targetY + ")!");
                    GameServer.broadcastGameState();
                }

                checkKill(targetX, targetY);
            }
        }
        System.out.println("Explosion at (" + bombX + ", " + bombY + ") finished.");
    }

    private static void checkKill(int x, int y) {
        if (GameServer.activePlayers != null) {
            try {
                for (UserSession player : GameServer.activePlayers.values()) {
                    if (player.x == x && player.y == y && player.isAlive) {

                        if (player.isInvisible) {
                            // Skip elimination completely! They are a phantom.
                            System.out.println("Combat Log: " + player.username + " safely phased through the explosion at (" + x + ", " + y + ").");
                        } else {
                            // No power-up active? Execute normal kill logic
                            player.isAlive = false;
                            System.out.println("Player [" + player.username + "] was caught in the blast at (" + x + ", " + y + ") and died!");

                            PlayerHandler handler = GameServer.activeHandlers.get(player.token);
                            if (handler != null) {
                                handler.sendDirectMessage("GAME_OVER");
                            }
                        }

                    }
                }

                // Check if the match has concluded after evaluating deaths
                evaluateGameStatus();

            } catch (Exception e) {
                System.out.println("Error handling blast collision check: " + e.getMessage());
            }
        }
    }

    private static synchronized void evaluateGameStatus() {
        int aliveCount = 0;
        UserSession lastSurvivor = null;
        int totalPlayers = GameServer.activePlayers.size();

        // We only evaluate win conditions if there are actually players in the game
        if (totalPlayers < 2) {
            return;
        }

        for (UserSession player : GameServer.activePlayers.values()) {
            if (player.isAlive) {
                aliveCount++;
                lastSurvivor = player;
            }
        }

        // Case A: Exactly 1 survivor remains -> VICTORY!
        if (aliveCount == 1 && lastSurvivor != null) {
            System.out.println("Match Over! Winner: " + lastSurvivor.username);
            lastSurvivor.score += 10; // Reward the winner with some points
            broadcastMatchResult("VICTORY " + lastSurvivor.username);
            resetMatch();
        }
        // Case B: 0 survivors remain -> DRAW!
        else if (aliveCount == 0) {
            System.out.println("Match Over! It's a mutual Draw!");
            broadcastMatchResult("DRAW");
            resetMatch();
        }
    }

    private static void broadcastMatchResult(String message) {
        for (PlayerHandler handler : GameServer.activeHandlers.values()) {
            handler.sendDirectMessage(message);
        }
    }

    private static synchronized void resetMatch() {
        System.out.println("Match lifecycle completed. Evaluating room requirements for next round...");

        // 1. CLEANUP PHASE: Remove players who disconnected whose grace periods expired
        // We use an iterator to safely delete elements from a map while looping through it
        java.util.Iterator<Map.Entry<String, UserSession>> iterator = GameServer.activePlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, UserSession> entry = iterator.next();
            UserSession player = entry.getValue();

            // If they are disconnected and dead, they have officially left the game permanently
            if (!player.isConnected && !player.isAlive) {
                System.out.println("Spawning System: Purging offline ghost player [" + player.username + "] from session records.");
                iterator.remove();
            }
        }

        // 2. CHECK ROOM VALIDITY: Count how many active, connected human beings remain in the lobby
        long connectedCount = GameServer.activePlayers.values().stream()
                .filter(p -> p.isConnected)
                .count();

        if (connectedCount < 2) {
            System.out.println("Lobby Status: Postponing match reset. Not enough connected players.");
            // Send a custom alert broadcast to whoever is left standing alone
            for (PlayerHandler handler : GameServer.activeHandlers.values()) {
                handler.sendDirectMessage("SERVER_LOBBY_ALERT Waiting for more players to join the arena...");
            }
            return; // HALT THE RESET. Do not create a new map layout yet!
        }

        // 3. INTERMISSION PHASE: If there are enough players, wait 5 seconds before starting
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 4. GENERATION PHASE: Build a fresh arena map
        initializeMap();

        // 5. RESPAWN PHASE: Revive and distribute ONLY the players who are actually connected
        int playerIndex = 0;
        for (UserSession player : GameServer.activePlayers.values()) {
            if (player.isConnected) {
                player.isAlive = true;

                if (playerIndex == 0) {       // Corner 1: Top-Left
                    player.x = 0; player.y = 0;
                } else if (playerIndex == 1) { // Corner 2: Bottom-Right
                    player.x = 14; player.y = 14;
                } else if (playerIndex == 2) { // Corner 3: Top-Right
                    player.x = 14; player.y = 0;
                } else {                       // Corner 4: Bottom-Left
                    player.x = 0; player.y = 14;
                }
                playerIndex++;
            }
        }

        // 6. SYNC PHASE: Force broadcast the clean state update
        GameServer.broadcastGameState();
    }
}
