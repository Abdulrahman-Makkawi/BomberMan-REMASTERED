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
    private JTextPane displayArea; // Now serves as our sidebar log console
    private JTextField chatInputField;
    private GamePanel gamePanel;   // Our brand new graphical board renderer
    private String chosenName;


    // Client's local tracking parameters
    private int playerX = 0;
    private int playerY = 0;
    private int[][] localArenaGrid = new int[15][15];
    private boolean isLocalPlayerAlive = true; // Tracks local round status inputs
    private boolean isLocalPlayerInvisible = false;

    // Dedicated inner structure to track opposing players locally
    private static class RemotePlayer {
        String username;
        int x, y;
        boolean isAlive;
        boolean isInvisible;

        RemotePlayer(String username, int x, int y, boolean isAlive, boolean isInvisible) {
            this.username = username;
            this.x = x;
            this.y = y;
            this.isAlive = isAlive;
            this.isInvisible = isInvisible;
        }
    }

    // Store opponent data maps dynamically
    private java.util.Map<String, RemotePlayer> oppositionPlayers = new java.util.concurrent.ConcurrentHashMap<>();

    private void connectToServer() {
        try {
            // 1. Prompt for Server IP first so we can bind the RMI proxy connection
            String serverIP = JOptionPane.showInputDialog(this, "Enter Server IP Address:", "127.0.0.1");
            if (serverIP == null || serverIP.isEmpty()) {
                serverIP = "127.0.0.1";
            }

            // Bind the RMI interface stub proxy from the registry lookup
            IBomberLobby lobbyProxy = (IBomberLobby) Naming.lookup("rmi://" + serverIP + ":" + GameServer.rmiPort + "/bomberlobby");

            // LOOPING UNIQUE LOGIN AUTHENTICATION GATE
            while (true) {
                chosenName = JOptionPane.showInputDialog(this, "Enter your username:");

                // Handshake Cancellation Protection: Exit cleanly if they press cancel or exit
                if (chosenName == null || chosenName.trim().isEmpty()) {
                    System.exit(0);
                }

                chosenName = chosenName.trim();

                // Fire the validation checking request over RMI
                mySessionToken = lobbyProxy.login(chosenName);

                // Check if the server caught a duplicate identity conflict
                if (mySessionToken.equals("DUPLICATE_NAME")) {
                    JOptionPane.showMessageDialog(this,
                            "The username '" + chosenName + "' is already taken in this arena!\nPlease choose a different name.",
                            "Login Conflict",
                            JOptionPane.WARNING_MESSAGE);
                    // The loop continues, throwing the username input box right back on screen!
                } else {
                    // Safe name verified and token generated! Break out of the trap safely.
                    break;
                }
            }

            appendColoredText("Authenticated via RMI Registry as [" + chosenName + "]!\n", Color.BLACK, false);

            // 2. ONLY NOW do we establish the persistent real-time TCP socket stream
            tcpSocket = new Socket(serverIP, GameServer.tcpPort);
            tcpOut = new PrintStream(tcpSocket.getOutputStream());

            // Send immediate connection authentication handshake payload
            tcpOut.println("CONNECT " + mySessionToken);

            Scanner tcpIn = new Scanner(tcpSocket.getInputStream());
            appendColoredText("TCP Game Engine Stream bound.\nControls: Arrows to Move, SPACE to Bomb.\n", Color.BLACK, false);

            // Background worker thread processing incoming global broadcasts
            new Thread(() -> {
                while (tcpIn.hasNextLine()) {
                    String serverMessage = tcpIn.nextLine();

                    if (serverMessage.startsWith("GAME_STATE_RESET")) {
                        String[] resetSegments = serverMessage.split(" ");
                        this.playerX = Integer.parseInt(resetSegments[1]);
                        this.playerY = Integer.parseInt(resetSegments[2]);
                        appendColoredText(">> Position verified by server to (" + playerX + "," + playerY + ")\n", Color.GRAY, false);
                        gamePanel.repaint();
                    }
                    else if (serverMessage.startsWith("GAME_STATE")) {
                        parseUnifiedGameState(serverMessage);
                    }
                    else if (serverMessage.startsWith("BROADCAST_CHAT")) {
                        String chatLine = serverMessage.substring(15);
                        appendColoredText(chatLine + "\n", new Color(0, 102, 204), false); // Clean Royal Blue

                        // Auto-scroll the text window to the bottom for new messages
                        displayArea.setCaretPosition(displayArea.getDocument().getLength());
                    }
                    else if (serverMessage.equals("GAME_OVER")) {
                        this.isLocalPlayerAlive = false;
                        appendColoredText("\n💥 BOOM! You were caught in a blast! Spectating... 💥\n", Color.RED, true);
                        gamePanel.repaint();
                    }
                    else if (serverMessage.startsWith("VICTORY")) {
                        this.isLocalPlayerAlive = false;
                        String winner = serverMessage.substring(8);
                        appendColoredText("\n👑 MATCH OVER: [" + winner + "] has won the game! 👑\n", new Color(204, 153, 0), true); // Gold/Amber
                    }
                    else if (serverMessage.equals("DRAW")) {
                        this.isLocalPlayerAlive = false;
                        appendColoredText("\n💀 MATCH OVER: Sudden death draw! Round resetting... 💀\n", Color.DARK_GRAY, true);
                    }
                    else if (serverMessage.startsWith("SERVER_LOBBY_ALERT")) {
                        String alertMsg = serverMessage.substring(19);
                        appendColoredText("\n⚠️ LOBBY NOTICE: " + alertMsg + "\n", Color.ORANGE.darker(), true);
                    }
                    else {
                        appendColoredText("SYSTEM: " + serverMessage + "\n", Color.BLACK, false);
                    }
                }
            }).start();

        } catch (Exception e) {
            appendColoredText("Connection Error: " + e.getMessage() + "\n", Color.magenta, true);
        }
    }

    public GameClientGUI() {
        setTitle("Multiplayer Bomberman Arena");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Recommended to handle window closing cleanly
        setLayout(new BorderLayout());

        // 1. Initialize and size the graphical board panel
        gamePanel = new GamePanel();
        gamePanel.setPreferredSize(new java.awt.Dimension(600, 600)); // Explicit size for the 15x15 grid (15 * 40px)
        add(gamePanel, BorderLayout.CENTER);

        // 2. Initialize the text log sidebar on the right
        displayArea = new JTextPane();
        displayArea.setEditable(false);
        displayArea.setBackground(new Color(245, 245, 245)); // Slightly tinted off-white background
        JScrollPane logScrollPane = new JScrollPane(displayArea);

        // Create the chat input box field
        chatInputField = new JTextField();
        chatInputField.setToolTipText("Press ENTER to send a message...");

        // Combine the log area and the input field into a neat vertical panel
        JPanel sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.add(logScrollPane, BorderLayout.CENTER);
        sidebarPanel.add(chatInputField, BorderLayout.SOUTH);
        sidebarPanel.setPreferredSize(new java.awt.Dimension(250, 600)); // Explicit width constraint for the chat area

        // Add components to the frame window
        add(sidebarPanel, BorderLayout.EAST);

        // CHAT TRANSMISSION TRIGGER listener
        chatInputField.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String textToSend = chatInputField.getText().trim();
                if (!textToSend.isEmpty() && tcpOut != null) {
                    // Push the text message out over the socket pipeline!
                    tcpOut.println("PLAYER_CHAT " + textToSend);
                    chatInputField.setText(""); // Reset the text field to empty
                }

                // CRITICAL FOCUS RESTORATION: Return focus back to the game panel
                // so arrow keys continue moving the avatar immediately after chatting!
                gamePanel.requestFocusInWindow();
            }
        });

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
                    appendColoredText(">> Action Denied: You are dead/spectating!\n", Color.BLACK, false);
                    return;
                }

                int targetX = playerX;
                int targetY = playerY;
                boolean tryingToMove = false;

                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (tcpOut != null) {
                        tcpOut.println("PLANTED_BOMB " + mySessionToken + " " + playerX + " " + playerY);
                        appendColoredText(">> Placed bomb at (" + playerX + "," + playerY + ")\n", Color.BLACK, false);
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

                        // Fetch local phantom status to decide if we can walk through bombs
                        myServerSession = GameServer.activePlayers.get(mySessionToken);
                        boolean amIPhantom = (myServerSession != null && myServerSession.isInvisible);

                        // Walkable if floor (0) OR Invisibility Item (5)
                        // OR if it's a bomb (3) and we are currently a phantom
                        boolean clientIsWalkable = (targetTile == 0 || targetTile == 5 || (targetTile == 3 && amIPhantom));

                        if (clientIsWalkable) {
                            playerX = targetX;
                            playerY = targetY;
                            // If we just stepped onto an invisibility item, clear it locally immediately
                            if (localArenaGrid[targetX][targetY] == 5) {
                                localArenaGrid[targetX][targetY] = 0;
                            }
                            if (tcpOut != null) {
                                tcpOut.println("PLAYER_MOVE " + mySessionToken + " " + playerX + " " + playerY);
                            }
                            gamePanel.repaint();
                        } else {
                            appendColoredText(">> Movement blocked by obstacle or bomb!\n", Color.BLACK, false);
                        }
                        // ---------------------------------------
                    }
                }
            }
        });

        connectToServer();

        // === CHOSEN PLACEMENT FOR PACKING AND POSITIONING ===
        this.pack();                       // Safely wraps around your 600px canvas and 250px sidebar panel now!
        this.setLocationRelativeTo(null);   // Beautifully centers it on your screen monitor boundaries
        setVisible(true);
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
                    // READ THE NEW INVISIBILITY NETWORK ATTRIBUTE
                    boolean invisible = false;
                    if (attributes.length > 4) {
                        invisible = Boolean.parseBoolean(attributes[4]);
                    }

                    oppositionPlayers.put(name, new RemotePlayer(name, x, y, alive, invisible));

                    // FORCE LOCAL COORDINATE SYNC DURING RESETS:
                    // If this entry matches your own username, force your local variables
                    // to perfectly synchronize with the server's authoritative position record!
                    // Inside parseUnifiedGameState() loop:
                    if (name.equalsIgnoreCase(chosenName)) { // Using equalsIgnoreCase to prevent string bugs
                        this.playerX = x;
                        this.playerY = y;

                        if (alive && !this.isLocalPlayerAlive) {
                            appendColoredText(">> You have been revived! New round started.\n", Color.BLACK, false);
                        }
                        this.isLocalPlayerAlive = alive;
                        this.isLocalPlayerInvisible = invisible;
                        // Grab your exact active state straight from the packet's server instance lookup
                        //UserSession myServerSession = GameServer.activePlayers.get(mySessionToken);
                        //if (myServerSession != null) {
                         //   this.isLocalPlayerInvisible = myServerSession.isInvisible;
                        //}
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
    class GamePanel extends JPanel {

        // Image object reference holders
        private Image floorImg;
        private Image brickImg;
        private Image steelImg;
        private Image bombImg;
        private Image playerImg;
        private Image ghostImg;

        public GamePanel() {
            setFocusable(true);

            // Pre-load graphics assets into memory when the panel initializes
            try {
                floorImg  = new ImageIcon(getClass().getResource("/resources/floor.png")).getImage();
                brickImg  = new ImageIcon(getClass().getResource("/resources/brick.png")).getImage();
                steelImg  = new ImageIcon(getClass().getResource("/resources/steel.png")).getImage();
                bombImg   = new ImageIcon(getClass().getResource("/resources/bomb.png")).getImage();
                playerImg = new ImageIcon(getClass().getResource("/resources/player.png")).getImage();
                ghostImg = new ImageIcon(getClass().getResource("/resources/player_ghost.png")).getImage();
                System.out.println("Graphics Engine: All retro asset textures loaded successfully!");
            } catch (Exception e) {
                System.err.println("Graphics Engine Error loading images: " + e.getMessage());
                System.err.println("Fallback: Engine will use default colored blocks if files are missing.");
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            int tileSize = 40; // Tracks block scaling layout dimensions

            // 1. RENDER MATRIX ARENA ENVIRONMENT
            for (int i = 0; i < 15; i++) {
                for (int j = 0; j < 15; j++) {
                    int tileType = localArenaGrid[i][j];
                    int drawX = i * tileSize;
                    int drawY = j * tileSize;

                    // Draw floor underneath everything first for a seamless look
                    if (floorImg != null) {
                        g2d.drawImage(floorImg, drawX, drawY, tileSize, tileSize, this);
                    } else {
                        g2d.setColor(Color.LIGHT_GRAY);
                        g2d.fillRect(drawX, drawY, tileSize, tileSize);
                    }

                    // Draw structural overlays
                    if (tileType == 1) { // Breakable Brick Wall
                        if (brickImg != null) g2d.drawImage(brickImg, drawX, drawY, tileSize, tileSize, this);
                        else { g2d.setColor(Color.ORANGE); g2d.fillRect(drawX, drawY, tileSize, tileSize); }
                    }
                    else if (tileType == 2) { // Solid Steel Pillar
                        if (steelImg != null) g2d.drawImage(steelImg, drawX, drawY, tileSize, tileSize, this);
                        else { g2d.setColor(Color.DARK_GRAY); g2d.fillRect(drawX, drawY, tileSize, tileSize); }
                    }
                    else if (tileType == 3) { // Active Ticking Bomb
                        if (bombImg != null) g2d.drawImage(bombImg, drawX, drawY, tileSize, tileSize, this);
                        else { g2d.setColor(Color.BLACK); g2d.fillOval(drawX + 5, drawY + 5, tileSize - 10, tileSize - 10); }
                    }
                    // --- INVISIBILITY ITEM DROP ---
                    else if (tileType == 5) { // Invisibility Drop Item
                        try {
                            Image ghostItemImg = new ImageIcon(getClass().getResource("/resources/phantom.png")).getImage();
                            g2d.drawImage(ghostItemImg, drawX, drawY, tileSize, tileSize, this);
                        } catch (Exception fallback) {
                            // Sleek neon purple block fallback design
                            g2d.setColor(new Color(153, 51, 255));
                            g2d.fillRect(drawX + 10, drawY + 10, tileSize - 20, tileSize - 20);
                        }
                    }
                    // --- EXPLOSION DRAW LAYER ---
                    else if (tileType == 9) { // Live Explosion Fire!
                        // Optional image pre-loading if you add explosion.png asset:
                        Image explosionImg = new ImageIcon(getClass().getResource("/resources/explosion.png")).getImage();
                        g2d.drawImage(explosionImg, drawX, drawY, tileSize, tileSize, this);
                    }
                }
            }

            // 2. RENDER ENEMY OPPOSITION CHARACTERS
            for (RemotePlayer opponent : oppositionPlayers.values()) {
                if (opponent.isAlive && !opponent.username.equalsIgnoreCase(chosenName)) {

                    int oppDrawX = opponent.x * tileSize;
                    int oppDrawY = opponent.y * tileSize;

                    // --- READ STRIP DIRECTLY FROM LOCAL STRUCTURE ---
                    Image characterSpriteToUse = opponent.isInvisible ? ghostImg : playerImg;
                    // ------------------------------------------------

                    if (characterSpriteToUse != null) {
                        g2d.drawImage(characterSpriteToUse, oppDrawX, oppDrawY, tileSize, tileSize, this);
                    } else {
                        g2d.setColor(opponent.isInvisible ? new Color(0, 255, 255) : Color.RED);
                        g2d.fillOval(oppDrawX + 4, oppDrawY + 4, tileSize - 8, tileSize - 8);
                    }

                    g2d.setColor(Color.BLACK);
                    g2d.drawString(opponent.username + (opponent.isInvisible ? " (G)" : ""), oppDrawX + 2, oppDrawY - 2);
                }
            }

            // 3. RENDER YOUR OWN CHARACTER PLAYER MODEL
            if (isLocalPlayerAlive) {
                int localDrawX = playerX * tileSize;
                int localDrawY = playerY * tileSize;

                // --- SPRITE SELECTION (Explicitly reference outer class variable) ---
                boolean amIInvisible = GameClientGUI.this.isLocalPlayerInvisible;
                Image localSpriteToUse = amIInvisible ? ghostImg : playerImg;
                // -------------------------------------------------------------------

                if (localSpriteToUse != null) {
                    g2d.drawImage(localSpriteToUse, localDrawX, localDrawY, tileSize, tileSize, this);
                } else {
                    // FALLBACK coloring for local player
                    if (amIInvisible) {
                        g2d.setColor(new Color(0, 255, 255, 120)); // Translucent Cyan
                    } else {
                        g2d.setColor(Color.BLUE); // Normal Blue
                    }
                    g2d.fillOval(localDrawX + 4, localDrawY + 4, tileSize - 8, tileSize - 8);
                }

                // Render your indicator tag
                g2d.setColor(amIInvisible ? new Color(0, 153, 153) : Color.BLUE.darker());
                g2d.drawString(amIInvisible ? "You (STEALTH)" : "You (" + chosenName + ")", localDrawX - 5, localDrawY - 2);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameClientGUI());
    }

    /**
     * Helper method to append stylized colored text into the JTextPane chat log.
     */
    private void appendColoredText(String text, Color color, boolean isBold) {
        javax.swing.text.StyledDocument doc = displayArea.getStyledDocument();
        javax.swing.text.SimpleAttributeSet attributes = new javax.swing.text.SimpleAttributeSet();

        javax.swing.text.StyleConstants.setForeground(attributes, color);
        javax.swing.text.StyleConstants.setBold(attributes, isBold);

        try {
            doc.insertString(doc.getLength(), text, attributes);
            // Auto-scroll to the bottom line
            displayArea.setCaretPosition(doc.getLength());
        } catch (javax.swing.text.BadLocationException e) {
            System.err.println("Error formatting chat colors: " + e.getMessage());
        }
    }
}