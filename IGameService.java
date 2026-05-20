import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IGameService extends Remote {
    // Synchronizes data structures and fetches the complete state profile of an active player
    public UserSession getPlayerStatus(String sessionToken) throws RemoteException;
}
