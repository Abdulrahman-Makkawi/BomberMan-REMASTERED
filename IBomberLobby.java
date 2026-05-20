import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface IBomberLobby extends Remote {

    // Registers a player and returns a unique session token (for connection recovery)
    public String login(String username) throws RemoteException;

    // Creates a new game room in memory
    public boolean createRoom(String roomName, String sessionToken) throws RemoteException;

    // Returns a list of active game rooms
    public List<String> listActiveRooms() throws RemoteException;
}
