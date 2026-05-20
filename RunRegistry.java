import java.rmi.registry.LocateRegistry;
import java.util.Scanner;

public class RunRegistry {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter RMI registry hosting port number: ");
        int registryPort = Integer.parseInt(scanner.nextLine());

        try {
            LocateRegistry.createRegistry(registryPort);
            System.out.println("Master Distributed RMI Naming Portal established on port " + registryPort);
            System.out.println("Press enter manually to terminate registry service infrastructure...");
            scanner.nextLine();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}