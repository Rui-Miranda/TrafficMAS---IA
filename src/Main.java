import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class Main {
    public static void main(String[] args) {
        // Inicializa o runtime do JADE
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, "true"); // Inicia a interface RMA automaticamente

        AgentContainer container = rt.createMainContainer(p);

        try {
            // Lança os dois agentes semáforo (Norte-Sul e Este-Oeste)
            AgentController tl1 = container.createNewAgent(
                    "Semaforo_NS",
                    "agents.TrafficLightAgent",
                    new Object[] { "Norte-Sul" });
            AgentController tl2 = container.createNewAgent(
                    "Semaforo_EW",
                    "agents.TrafficLightAgent",
                    new Object[] { "Este-Oeste" });
            tl1.start();
            tl2.start();

            // Lança um veículo de teste na via Norte-Sul
            AgentController car1 = container.createNewAgent(
                    "Carro_01",
                    "agents.VehicleAgent",
                    new Object[] { "NS" });
            car1.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}