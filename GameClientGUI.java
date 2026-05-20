import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.PrintStream;
import java.net.Socket;
import java.rmi.Naming;
import java.util.Scanner;

public class GameClientGUI extends JFrame {
    private String mySessionToken;
    private Socket tcpSocket;
    private PrintStream tcpOut;
    private JTextArea displayArea; // Now serves as our sidebar log console
    private GamePanel gamePanel;   // Our brand new graphical board renderer
    private String chosenName;


    // Client's local tracking parameters
    private int playerX = 0;
    private int playerY = 0;
    private int[][] localArenaGrid = new int[15][15];
    private boolean isLocalPlayerAlive = true; // Tracks local round status inputs

    // Dedicated inner structure to track opposing players locally
    private static class RemotePlayer {
        String username;
        int x, y;
        boolean isAlive;

        RemotePlayer(String username, int x, int y, boolean isAlive) {
            this.username = username;
            this.x = x;
            this.y = y;
            this.isAlive = isAlive;
        }
    }

    // Store opponent data maps dynamically
    private java.util.Map<String, RemotePlayer> oppositionPlayers = new java.util.concurrent.ConcurrentHashMap<>();

    public GameClientGUI() {
        setTitle("Multiplayer Bomberman Arena");
        setSize(750, 490); // Expanded window layout width to hold both board and sidebar
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 1. Initialize the graphical board panel
        gamePanel = new GamePanel();
        add(gamePanel, BorderLayout.CENTER);

        // 2. Initialize the text log sidebar on the right
        displayArea = new JTextArea();
        displayArea.setEditable(false);
        displayArea.setBackground(new Color(240, 240, 240));
        JScrollPane logScrollPane = new JScrollPane(displayArea);
        logScrollPane.setPreferredSize(new Dimension(270, 450));
        add(logScrollPane, BorderLayout.EAST);

        // 3. Bind Keyboard Inputs directly onto the focusable graphical canvas panel
        gamePanel.setFocusable(true);
        gamePanel.requestFocusInWindow();
        gamePanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // 1. CRASH-PROOF SECURITY CHECK: If you haven't even logged in yet, ignore keys
                if (chosenName == null || mySessionToken == null || !isLocalPlayerAlive) {
                    return;
                }

                // 2. CHECK ALIVE STATUS AUTHORITATIVELY: Locate your profile in the player map
                UserSession myServerSession = GameServer.activePlayers.get(mySessionToken);
                if (myServerSession != null && !myServerSession.isAlive) {
                    displayArea.append(">> Action Denied: You are dead/spectating!\n");
                    return;
                }

                int targetX = playerX;
                int targetY = playerY;
                boolean tryingToMove = false;

                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (tcpOut != null) {
                        tcpOut.println("PLANTED_BOMB " + mySessionToken + " " + playerX + " " + playerY);
                        displayArea.append(">> Placed bomb at (" + playerX + "," + playerY + ")\n");
                    }
                    return;
                }
                else if (e.getKeyCode() == KeyEvent.VK_RIGHT) { targetX++; tryingToMove = true; }
                else if (e.getKeyCode() == KeyEvent.VK_LEFT)  { targetX--; tryingToMove = true; }
                else if (e.getKeyCode() == KeyEvent.VK_DOWN)  { targetY++; tryingToMove = true; }
                else if (e.getKeyCode() == KeyEvent.VK_UP)    { targetY--; tryingToMove = true; }

                if (tryingToMove) {
                    if (targetX >= 0 && targetX < 15 && targetY >= 0 && targetY < 15) {
                        int targetTile = localArenaGrid[targetX][targetY];

                        // CLASSIC FIX: Can only step onto empty ground (0).
                        if (targetTile == 0) {
                            playerX = targetX;
                            playerY = targetY;
                            if (tcpOut != null) {
                                tcpOut.println("PLAYER_MOVE " + mySessionToken + " " + playerX + " " + playerY);
                            }
                            gamePanel.repaint();
                        } else {
                            displayArea.append(">> Movement blocked by obstacle or bomb!\n");
                        }
                    }
                }
            }
        });

        connectToServer();
        setVisible(true);
    }

    private void connectToServer() {
        try {
            chosenName = JOptionPane.showInputDialog(this, "Enter your username:");
            if (chosenName == null || chosenName.isEmpty()) System.exit(0);

            // 1. Ask for the Server's IP address. Defaults to localhost for easy local testing!
            String serverIP = JOptionPane.showInputDialog(this, "Enter Server IP Address:", "127.0.0.1");
            if (serverIP == null || serverIP.isEmpty()) {
                serverIP = "127.0.0.1";
            }

            // 2. Use the dynamic serverIP variable instead of the old hardcoded "127.0.0.1"
            IBomberLobby lobbyProxy = (IBomberLobby) Naming.lookup("rmi://" + serverIP + ":" + GameServer.rmiPort + "/bomberlobby");
            mySessionToken = lobbyProxy.login(chosenName);
            displayArea.append("Authenticated via RMI Registry!\n");

            // 3. Connect the gameplay TCP socket to the same dynamic IP address
            tcpSocket = new Socket(serverIP, GameServer.tcpPort);
            tcpOut = new PrintStream(tcpSocket.getOutputStream());

            // Send the immediate initial authentication connection handshake
            tcpOut.println("CONNECT " + mySessionToken);

            Scanner tcpIn = new Scanner(tcpSocket.getInputStream());
            displayArea.append("TCP Game Engine Stream bound.\nControls: Arrows to Move, SPACE to Bomb.\n");

            // Background worker thread processing incoming global broadcasts
            new Thread(() -> {
                while (tcpIn.hasNextLine()) {
                    String serverMessage = tcpIn.nextLine();

                    if (serverMessage.startsWith("GAME_STATE_RESET")) {
                        String[] resetSegments = serverMessage.split(" ");
                        this.playerX = Integer.parseInt(resetSegments[1]);
                        this.playerY = Integer.parseInt(resetSegments[2]);
                        displayArea.append(">> Position verified by server to (" + playerX + "," + playerY + ")\n");
                        gamePanel.repaint();
                    }
                    else if (serverMessage.startsWith("GAME_STATE")) {
                        parseUnifiedGameState(serverMessage);
                    }
                    else if (serverMessage.equals("GAME_OVER")) {
                        this.isLocalPlayerAlive = false; // FREEZE INPUTS LOCALLY
                        displayArea.append("\n💥 BOOM! You were caught in a blast! Spectating... 💥\n");
                        gamePanel.repaint();
                    }
                    else if (serverMessage.startsWith("VICTORY")) {
                        this.isLocalPlayerAlive = false; // FREEZE INPUTS LOCALLY during the 5s transition
                        String winner = serverMessage.substring(8);
                        displayArea.append("\n👑 MATCH OVER: [" + winner + "] has won the game! Rematch starting in 5 seconds... 👑\n");
                    }
                    else if (serverMessage.equals("DRAW")) {
                        this.isLocalPlayerAlive = false; // FREEZE INPUTS LOCALLY
                        displayArea.append("\n💀 MATCH OVER: Sudden death draw! Round resetting... 💀\n");
                    }
                    else if (serverMessage.startsWith("SERVER_LOBBY_ALERT")) {
                        String alertMsg = serverMessage.substring(19);
                        displayArea.append("\n⚠️ LOBBY NOTICE: " + alertMsg + "\n");
                    }
                    else {
                        displayArea.append("SERVER: " + serverMessage + "\n");
                    }
                }
            }).start();

        } catch (Exception e) {
            displayArea.append("Connection Error: " + e.getMessage() + "\n");
        }
    }

    private void parseUnifiedGameState(String packet) {
        try {
            String rawPayload = packet.substring(11);
            String[] coreSegments = rawPayload.split("\\|");

            // 1. Rebuild Map Matrix
            String[] gridElements = coreSegments[0].split(",");
            int cellIndex = 0;
            for (int i = 0; i < 15; i++) {
                for (int j = 0; j < 15; j++) {
                    localArenaGrid[i][j] = Integer.parseInt(gridElements[cellIndex++]);
                }
            }

            // 2. Rebuild Opposition Profiles
            oppositionPlayers.clear();
            if (coreSegments.length > 1) {
                String[] playerEntries = coreSegments[1].split(";");
                for (String entry : playerEntries) {
                    if (entry.trim().isEmpty()) continue;

                    String[] attributes = entry.split(",");
                    String name = attributes[0];
                    int x = Integer.parseInt(attributes[1]);
                    int y = Integer.parseInt(attributes[2]);
                    boolean alive = Boolean.parseBoolean(attributes[3]);

                    oppositionPlayers.put(name, new RemotePlayer(name, x, y, alive));

                    // FORCE LOCAL COORDINATE SYNC DURING RESETS:
                    // If this entry matches your own username, force your local variables
                    // to perfectly synchronize with the server's authoritative position record!
                    // Inside parseUnifiedGameState() loop:
                    if (name.equalsIgnoreCase(chosenName)) { // Using equalsIgnoreCase to prevent string bugs
                        this.playerX = x;
                        this.playerY = y;

                        // Dynamic absolute synchronization:
                        // If the server says you are alive, you ARE alive. If it says you are dead, you ARE dead.
                        if (alive && !this.isLocalPlayerAlive) {
                            displayArea.append(">> You have been revived! New round started.\n");
                        }
                        this.isLocalPlayerAlive = alive;
                    }
                }
            }

            gamePanel.repaint();

        } catch (Exception e) {
            System.err.println("Error decoding global update snapshot: " + e.getMessage());
        }
    }

    /**
     * Inner Custom Panel responsible for drawing the grid environment and active entities
     */
    private class GamePanel extends JPanel {
        private final int TILE_SIZE = 30; // Every block is mapped out cleanly to 30x30 pixels

        public GamePanel() {
            setPreferredSize(new Dimension(15 * TILE_SIZE, 15 * TILE_SIZE));
            setBackground(new Color(34, 139, 34)); // Classic retro grass green base floor color
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            // --- 1. Draw Map Blocks ---
            for (int x = 0; x < 15; x++) {
                for (int y = 0; y < 15; y++) {
                    int blockType = localArenaGrid[x][y];
                    int drawX = x * TILE_SIZE;
                    int drawY = y * TILE_SIZE;

                    if (blockType == 1) { // Breakable Brick Wall
                        g2d.setColor(new Color(160, 82, 45)); // Sienna Brick Brown
                        g2d.fillRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
                        g2d.setColor(Color.BLACK);
                        g2d.drawRect(drawX, drawY, TILE_SIZE, TILE_SIZE); // Render block borders
                    }
                    else if (blockType == 2) { // Unbreakable Solid Steel Pillar
                        g2d.setColor(new Color(112, 128, 144)); // Slate Hard Steel Gray
                        g2d.fillRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
                        g2d.setColor(Color.DARK_GRAY);
                        g2d.drawRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
                    }
                    else if (blockType == 3) { // Placed Bomb Active State
                        g2d.setColor(Color.BLACK);
                        g2d.fillOval(drawX + 4, drawY + 4, TILE_SIZE - 8, TILE_SIZE - 8);
                        g2d.setColor(Color.RED); // Lit fuse color accent
                        g2d.fillRect(drawX + 13, drawY + 1, 4, 4);
                    }
                }
            }

            // --- 2. Draw Opponent Players ---
            for (RemotePlayer player : oppositionPlayers.values()) {
                if (!player.isAlive) continue;

                // FIX: If this opponent's data entry has your own username, skip drawing it!
                // Your personal blue avatar is already handled by section 3 below.
                if (player.username.equals(chosenName)) {
                    continue;
                }

                int oppX = player.x * TILE_SIZE;
                int oppY = player.y * TILE_SIZE;

                g2d.setColor(Color.RED);
                g2d.fillOval(oppX + 2, oppY + 2, TILE_SIZE - 4, TILE_SIZE - 4);

                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 10));
                g2d.drawString(player.username, oppX + 2, oppY + 12);
            }

            // --- 3. Draw Local Human Player (Your Character) ---
            // Let's check if the client is still alive locally or flagged out by server
            UserSession currentSession = GameServer.activePlayers.get(mySessionToken);
            boolean amIAlive = (currentSession == null) || currentSession.isAlive;

            if (amIAlive) {
                int myX = playerX * TILE_SIZE;
                int myY = playerY * TILE_SIZE;

                // Draw your player avatar (Blue Circle)
                g2d.setColor(Color.BLUE);
                g2d.fillOval(myX + 2, myY + 2, TILE_SIZE - 4, TILE_SIZE - 4);

                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 10));
                g2d.drawString("YOU", myX + 6, myY + 18);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameClientGUI());
    }
}