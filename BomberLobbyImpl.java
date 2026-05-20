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
        // Enforce smart validation logic to support mid-match reconnection routines
        for (UserSession active : GameServer.activePlayers.values()) {
            if (active.username.equalsIgnoreCase(username)) {
                // If the player is currently online, block someone else from taking their name
                if (active.isConnected) {
                    throw new RemoteException("Username already claimed by an active player.");
                } else {
                    // FIX: If they are offline, ALLOW them to reconnect!
                    // Return their original token session key so they can snap back into their character!
                    System.out.println("RMI Login: Recognizing reconnection request for player [" + username + "]");
                    return active.token;
                }
            }
        }

        // Initial brand new registration login sequence
        String uniqueToken = UUID.randomUUID().toString();
        UserSession newSession = new UserSession(username, uniqueToken);

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
