# BomberMan-REMASTERED

# 💣 Distributed Bomberman: Hybrid Network Architecture

A real-time multiplayer Bomberman clone built from scratch in Java. This project serves as a comprehensive demonstration of distributed systems, utilizing a **Hybrid Network Architecture** to seamlessly integrate Remote Method Invocation (RMI), reliable TCP sockets, and high-speed UDP streams without relying on a traditional database.

## 🧠 System Architecture

This game engine avoids single-protocol bottlenecks by assigning different networking tools to the tasks they do best:

* **RMI (Remote Method Invocation) - The Lobby Layer:** Handles administrative, synchronous tasks. Players use RMI lookups to authenticate, generate secure unique session tokens, and query active game rooms.
* **TCP Sockets - The Critical State Engine:** Handles guaranteed, order-dependent game events. Actions like `PLANTED_BOMB`, `WALL_DESTROYED`, or `PLAYER_DEAD` are routed through decoupled TCP worker threads to ensure absolute state synchronization across all clients.
* **In-Memory Data Structures - The Database Replacement:** To maximize read/write speeds for real-time gaming, the server completely bypasses disk writes. Active sessions, player states, and the 15x15 `GameEngine` matrix live directly inside thread-safe `ConcurrentHashMap` and volatile array structures.
* **UDP Sockets (In Progress) - The Movement Layer:** Designed to broadcast high-frequency X/Y walking coordinates where latency matters more than occasional packet loss.

## 🗂️ Project Structure

The codebase is strictly separated into three logical domains:

1. **Remote Interfaces (`IBomberLobby.java`):** Interface definitions extending `Remote` for matchmaking.
2. **The Server Backbone (`GameServer.java`, `PlayerHandler.java`, `GameEngine.java`):** The master source of truth. It hosts the RMI registry, accepts TCP connections via a dedicated loop, and manages the 3-second threaded explosion fuse logic.
3. **The Client GUI (`GameClientGUI.java`):** A lightweight Java Swing application that captures local keystrokes, pushes commands down the socket pipelines, and asynchronously reads server broadcasts without blocking the main thread.

## 🚀 How to Run

### 1. Start the Server Network
Compile the server files and initialize the central hub.
```bash
# 1. Start the RMI Registry (Defaults to port 5000)
java bomberlab.RunRegistry

# 2. In a new terminal, launch the Master Game Server
# This boots the GameEngine matrix and opens TCP port 30000
java bomberlab.GameServer
```

### 2. Connect a Client
Launch the graphical client to join the arena.
```bash
# In a new terminal, launch the player client
java bomberlab.GameClientGUI
```
Controls: * Use SPACEBAR to drop a bomb.

Author

Bomberman Makkawi
