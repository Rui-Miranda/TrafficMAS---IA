import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

/**
 * Ponto de Entrada Principal do Sistema Multiagente TrafficMAS (Main Launcher).
 *
 * Inicializa a infraestrutura JADE com interface gráfica RMA (Remote Management Agent),
 * constrói o contentor principal (Main Container) e instancia o ecossistema completo de agentes:
 * - {@link agents.CentralCoordinationAgent}: Agente de Coordenação Central da rede viária (Feature 4).
 * - {@link agents.DisruptionManagementAgent}: Agente preditivo de perturbações com Machine Learning (Feature 5).
 * - {@link agents.TrafficLightAgent}: 8 Agentes semáforo adaptativos para os 4 cruzamentos (Features 2 e 7).
 * - {@link agents.VehicleAgent}: Viaturas regulares com reporte de tempos de espera (Feature 3).
 * - {@link agents.EmergencyVehicleAgent}: Viaturas prioritárias com preempção semafórica (Feature 6).
 *
 * Cumpre integralmente os requisitos da Aula_01 e as recomendações do guia Java_Jade.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — INTELLIGENT MULTI-AGENT TRAFFIC CONTROL SYSTEM (ISPGAYA)");
        System.out.println("   Licenciatura em Engenharia Informática — Unidade Curricular de IA");
        System.out.println("================================================================================");

        // 1. Configura e inicializa o ambiente de execução JADE
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, "true"); // Ativa a consola gráfica de gestão de agentes RMA

        AgentContainer container = rt.createMainContainer(p);

        try {
            // 2. Inicializa o ambiente físico e interface de simulação urbana com 4 cruzamentos
            environment.TrafficEnvironment.getInstance();

            // 3. Lançamento do Agente de Coordenação Central (Feature 4)
            AgentController centralCoord = container.createNewAgent(
                    "AgenteCoordenadorCentral",
                    "agents.CentralCoordinationAgent",
                    new Object[] {});
            centralCoord.start();

            // 4. Lançamento do Agente de Gestão de Perturbações com IA/ML (Feature 5)
            AgentController disruptionAgent = container.createNewAgent(
                    "AgenteGestaoPerturbacoes",
                    "agents.DisruptionManagementAgent",
                    new Object[] {});
            disruptionAgent.start();

            // 5. Configurar e instanciar os Semáforos Inteligentes nos 4 Cruzamentos (NW=0, NE=1, SW=2, SE=3)
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

            // 6. Lançamento de Viaturas Regulares (Feature 3)
            // Distribuição equilibrada pelos eixos NS e EW para cobertura dos 4 cruzamentos
            String[][] regularCars = {
                {"Carro_01", "NS"},   // Norte -> Sul (Cruzamentos NW/SW ou NE/SE)
                {"Carro_02", "EW"},   // Oeste -> Este (Cruzamentos NW/NE ou SW/SE)
                {"Carro_03", "NS"},   // Norte -> Sul
                {"Carro_04", "EW"},   // Oeste -> Este
                {"Carro_05", "NS"},   // Norte -> Sul
                {"Carro_06", "EW"}    // Oeste -> Este (garante equilíbrio entre eixos)
            };

            for (String[] carInfo : regularCars) {
                AgentController car = container.createNewAgent(
                        carInfo[0],
                        "agents.VehicleAgent",
                        new Object[] { carInfo[1] });
                car.start();
            }

            // 7. Lançamento de Viaturas de Emergência / Prioritárias (Feature 6)
            AgentController amb = container.createNewAgent(
                    "Ambulancia_INEM_01",
                    "agents.EmergencyVehicleAgent",
                    new Object[] { "NS" });
            amb.start();

            AgentController police = container.createNewAgent(
                    "Viatura_Policia_01",
                    "agents.EmergencyVehicleAgent",
                    new Object[] { "EW" });
            police.start();

            System.out.println("[Main] Ecossistema multiagente inicializado com sucesso.");

        } catch (Exception e) {
            System.err.println("[Main] Erro fatal durante a instanciação dos agentes JADE: " + e.getMessage());
            e.printStackTrace();
        }
    }
}