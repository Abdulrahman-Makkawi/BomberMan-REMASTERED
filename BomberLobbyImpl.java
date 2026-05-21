import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BomberLobbyImpl extends UnicastRemoteObject implements IBomberLobby {

    public BomberLobbyImpl() throws RemoteException{
    }

    @Override
    public String login(String username) throws RemoteException {
        if (username == null || username.trim().isEmpty()) {
            return "DUPLICATE_NAME";
        }

        // 1. Validate incoming string details against the current session database
        for (UserSession activeUser : GameServer.activePlayers.values()) {
            if (activeUser.username.equalsIgnoreCase(username.trim())) {
                System.out.println("Lobby Security: Rejected duplicate registration attempt for: " + username);
                return "DUPLICATE_NAME"; // Hand back an explicit error code flag
            }
        }

        // 2. If the name is completely unique, proceed with standard registration
        String uniqueToken = java.util.UUID.randomUUID().toString();
        UserSession newSession = new UserSession(username.trim(), uniqueToken);

        GameServer.activePlayers.put(uniqueToken, newSession);
        System.out.println("RMI Login Success: Assigned fresh token " + uniqueToken + " to " + username);

        return uniqueToken;
    }

    @Override
    public boolean createRoom(String roomName, String sessionToken) throws RemoteException {
        //Check if the given token exist in our database (hashmap)
        if (!GameServer.activePlayers.containsKey(sessionToken)) {
            throw new RemoteException("Invalid session authorization credentials.");
        }
        //Check if this room is already in use
        if (GameServer.activeRooms.containsKey(roomName)) {
            return false;
        }
        //Successful try
        GameServer.activeRooms.put(roomName, sessionToken);
        System.out.println("RMI Room Registered: '" + roomName + "' created by host token " + sessionToken);
        return true;
    }

    @Override
    public List<String> listActiveRooms() throws RemoteException {
        return new ArrayList<>(GameServer.activeRooms.keySet());
    }
}
