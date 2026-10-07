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
            // Inicializa a interface da Cidade com 4 cruzamentos
            environment.CitySimulationGUI.getInstance();

            // Configurar 4 Cruzamentos centrais (NW=0, NE=1, SW=2, SE=3)
            String[] crossings = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
            for (int i = 0; i < 4; i++) {
                AgentController tlNS = container.createNewAgent(
                        "Semaforo_NS_" + crossings[i],
                        "agents.TrafficLightAgent",
                        new Object[] { "Norte-Sul", i });
                AgentController tlEW = container.createNewAgent(
                        "Semaforo_EW_" + crossings[i],
                        "agents.TrafficLightAgent",
                        new Object[] { "Este-Oeste", i });
                tlNS.start();
                tlEW.start();
            }

            // Lança viaturas a percorrer a cidade em diferentes direções
            String[][] cars = {
                {"Carro_01", "NS"},
                {"Carro_02", "EW"},
                {"Carro_03", "NS"},
                {"Carro_04", "EW"}
            };

            for (String[] carInfo : cars) {
                AgentController car = container.createNewAgent(
                        carInfo[0],
                        "agents.VehicleAgent",
                        new Object[] { carInfo[1] });
                car.start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}