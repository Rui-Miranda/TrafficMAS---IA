package environment;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Camada de Abstração e Fachada Oficial do Ambiente de Tráfego (Feature 1 - Traffic Environment).
 *
 * Esta classe representa o ambiente no qual os agentes inteligentes vivem,
 * disponibilizando sensores e atuadores para que os agentes possam:
 * - Percecionar o estado do tráfego (densidade de aproximação, filas de espera, viaturas de emergência).
 * - Atuar sobre a infraestrutura rodoviária (comutação semafórica, preempção de socorro).
 * - Consultar métricas de desempenho agregadas em tempo real (Feature 8).
 * - Propagar alertas preditivos de perturbações e condições meteorológicas (Feature 5).
 * - Gerir o ciclo de vida dinâmico da frota e topologia parametrizável 2×2 / 3×3 (Fase 6.1).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficEnvironment {

    private static TrafficEnvironment instance;

    /** Interface provedora de dados sensoriais (Fase 5 - Etapa 2) */
    public interface EnvironmentDataProvider {
        int getApproachingCount(int intersectionIndex, int axis);
        int getWaitingCount(int intersectionIndex, int axis);
        boolean hasEmergencyApproaching(int intersectionIndex, int axis);
        double getVehicleWaitSeconds(String vehicleId);
        double getAverageSpeed(int intersectionIndex);
        double getAverageWaitingTime(int intersectionIndex);
        int getWeatherCondition();
        int getIncidentPresent();
        int getRoadCapacity();
        double getGlobalSpeedMultiplier();
        String getDisruptionNotice();
    }

    private static volatile EnvironmentDataProvider customDataProvider = null;

    public static void setCustomDataProvider(EnvironmentDataProvider provider) {
        customDataProvider = provider;
    }

    public static EnvironmentDataProvider getCustomDataProvider() {
        return customDataProvider;
    }

    // Filas legadas mantidas para compatibilidade retroativa
    private final ConcurrentLinkedQueue<String> queueNS = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> queueEW = new ConcurrentLinkedQueue<>();

    /**
     * Construtor privado (Padrão Singleton).
     */
    private TrafficEnvironment() {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CitySimulationGUI.getInstance();
        }
    }

    /**
     * Obtém a instância única e global do ambiente de tráfego.
     *
     * @return Instância de {@link TrafficEnvironment}
     */
    public static synchronized TrafficEnvironment getInstance() {
        if (instance == null) {
            instance = new TrafficEnvironment();
        }
        return instance;
    }

    /* =========================================================================
     * TOPOLOGIA, GRAFO RODOVIÁRIO E GESTÃO DE FROTA (Fase 6.1)
     * ========================================================================= */

    /**
     * Obtém a configuração topológica ativa (2×2 ou 3×3).
     */
    public NetworkTopologyConfig getTopology() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            return gui.getTopology();
        }
        return NetworkTopologyConfig.getActive();
    }

    /**
     * Obtém o grafo rodoviário direcionado da simulação.
     */
    public RoadNetworkGraph getRoadGraph() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            return gui.getRoadGraph();
        }
        return new RoadNetworkGraph(NetworkTopologyConfig.getActive());
    }

    /**
     * Obtém o gestor oficial do ciclo de vida da frota de veículos (limite 0..50 normais + prioritários).
     */
    public FleetLifecycleManager getFleetManager() {
        return FleetLifecycleManager.getInstance();
    }

    /**
     * Adiciona dinamicamente um veículo autónomo normal (até ao limite máximo de 50).
     *
     * @return ID do veículo adicionado ou {@code null} se o limite de 50 já foi atingido.
     */
    public String addNormalVehicle() {
        return FleetLifecycleManager.getInstance().addNormalVehicle();
    }

    /**
     * Remove em segurança um veículo autónomo normal sem afetar veículos prioritários.
     *
     * @return ID do veículo removido ou {@code null} se não existirem veículos normais ativos.
     */
    public String removeNormalVehicle() {
        return FleetLifecycleManager.getInstance().removeNormalVehicle();
    }

    /**
     * Remove em segurança um veículo autónomo normal específico pelo seu ID.
     */
    public boolean removeNormalVehicleById(String vehicleId) {
        return FleetLifecycleManager.getInstance().removeNormalVehicle(vehicleId);
    }

    /**
     * Obtém o número atual de veículos autónomos normais ativos (0..50).
     */
    public int getActiveNormalVehicleCount() {
        return FleetLifecycleManager.getInstance().getActiveNormalVehicleCount();
    }

    /**
     * Obtém o limite máximo de veículos normais (50).
     */
    public int getMaxNormalVehicles() {
        return FleetLifecycleManager.MAX_NORMAL_VEHICLES;
    }

    /**
     * Obtém o número de veículos prioritários ativos (excluídos do limite de 50).
     */
    public int getActivePriorityVehicleCount() {
        return FleetLifecycleManager.getInstance().getActivePriorityVehicleCount();
    }

    /**
     * Verifica se a adição de novos veículos normais está disponível (< 50 ativos).
     */
    public boolean isAddNormalVehicleAvailable() {
        return FleetLifecycleManager.getInstance().isAddNormalVehicleAvailable();
    }

    /* =========================================================================
     * SENSORES: PERCEÇÃO DO AMBIENTE (Features 1, 2, 3, 6)
     * ========================================================================= */

    public int getApproachingVehiclesCount(int intersectionIndex, int axis) {
        if (customDataProvider != null) {
            return customDataProvider.getApproachingCount(intersectionIndex, axis);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getApproachingCount(intersectionIndex, axis) : 0;
    }

    public int getWaitingVehiclesCount(int intersectionIndex, int axis) {
        if (customDataProvider != null) {
            return customDataProvider.getWaitingCount(intersectionIndex, axis);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getWaitingCount(intersectionIndex, axis) : 0;
    }

    public boolean hasEmergencyApproaching(int intersectionIndex, int axis) {
        if (customDataProvider != null) {
            return customDataProvider.hasEmergencyApproaching(intersectionIndex, axis);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) && gui.hasEmergencyApproaching(intersectionIndex, axis);
    }

    public double getVehicleWaitSeconds(String vehicleId) {
        if (customDataProvider != null) {
            return customDataProvider.getVehicleWaitSeconds(vehicleId);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getVehicleWaitSeconds(vehicleId) : 0.0;
    }

    /* =========================================================================
     * ATUADORES: AÇÕES SOBRE O AMBIENTE (Features 2, 5, 6, 7)
     * ========================================================================= */

    public void setTrafficLight(int intersectionIndex, int axis, CitySimulationGUI.LightState state) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.setTrafficLight(intersectionIndex, axis, state);
        }
    }

    public void triggerEmergencyPreemption(int intersectionIndex, int axis) {
        IntersectionSafetyAuthority.getAuthority(intersectionIndex).requestEmergency("MANUAL_EMERGENCY", axis, null);
    }

    public IntersectionSafetyAuthority getIntersectionAuthority(int intersectionIndex) {
        return IntersectionSafetyAuthority.getAuthority(intersectionIndex);
    }

    /**
     * Submete um pedido de controlo manual seguro para um cruzamento e eixo (0=NS, 1=EW),
     * passando obrigatoriamente pela {@link IntersectionSafetyAuthority}.
     */
    public IntersectionSafetyAuthority.ManualOverrideRequest requestManualTrafficLightPhase(
            int intersectionIndex, int targetAxis) {
        return IntersectionSafetyAuthority.getAuthority(intersectionIndex).requestManualPhase(targetAxis);
    }

    public IntersectionSafetyAuthority.ManualOverrideRequest requestManualTrafficLightPhase(
            int intersectionIndex, int targetAxis, long holdDurationMs) {
        return IntersectionSafetyAuthority.getAuthority(intersectionIndex)
                .requestManualPhase(targetAxis, holdDurationMs);
    }

    /**
     * Restitui um cruzamento ao controlo automático normal após intervenção manual.
     */
    public IntersectionSafetyAuthority.ManualOverrideRequest returnTrafficLightToAutomatic(int intersectionIndex) {
        return IntersectionSafetyAuthority.getAuthority(intersectionIndex).returnToAutomaticMode();
    }

    public void setDisruptionNotice(String notice, double speedMultiplier) {
        CitySimulationGUI.getInstance().setDisruptionNotice(notice, speedMultiplier);
    }

    public void setMLPredictionNotice(String notice) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.setMLPredictionNotice(notice);
        }
    }

    public double getEnvironmentalSpeedMultiplier() {
        if (customDataProvider != null) {
            return customDataProvider.getGlobalSpeedMultiplier();
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getGlobalSpeedMultiplier() : 1.0;
    }

    public double getAverageSpeed(int intersectionIndex) {
        if (customDataProvider != null) {
            return customDataProvider.getAverageSpeed(intersectionIndex);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getAverageSpeed(intersectionIndex) : 2.4;
    }

    public double getAverageWaitingTime(int intersectionIndex) {
        if (customDataProvider != null) {
            return customDataProvider.getAverageWaitingTime(intersectionIndex);
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getAverageWaitingTime(intersectionIndex) : 0.0;
    }

    public int getWeatherCondition() {
        if (customDataProvider != null) {
            return customDataProvider.getWeatherCondition();
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getWeatherCondition() : 0;
    }

    public int getIncidentPresent() {
        if (customDataProvider != null) {
            return customDataProvider.getIncidentPresent();
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getIncidentPresent() : 0;
    }

    public int getRoadCapacity() {
        if (customDataProvider != null) {
            return customDataProvider.getRoadCapacity();
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getRoadCapacity() : 4;
    }

    public String getDisruptionNotice() {
        if (customDataProvider != null) {
            return customDataProvider.getDisruptionNotice();
        }
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getDisruptionNotice() : "Condições Normais";
    }

    /* =========================================================================
     * MÉTRICAS DE DESEMPENHO E TELEMETRIA (Feature 8)
     * ========================================================================= */

    public double getAverageWaitSeconds() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getAverageWaitSeconds() : 0.0;
    }

    public long getTotalCompletedPasses() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getTotalCompletedPasses() : 0L;
    }

    public int getPreemptionCount() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getPreemptionCount() : 0;
    }

    public metrics.TrafficMetricsCollector getMetricsCollector() {
        return metrics.TrafficMetricsCollector.getInstance();
    }

    public metrics.TrafficMetricsSnapshot getMetricsSnapshot(String runId, String scenario, String controlMode, long seed) {
        return metrics.TrafficMetricsCollector.getInstance().getSnapshot(runId, scenario, controlMode, seed);
    }

    /* =========================================================================
     * MÉTODOS DE COMPATIBILIDADE RETROATIVA
     * ========================================================================= */

    public synchronized void setLights(CitySimulationGUI.LightState ns, CitySimulationGUI.LightState ew) {
        setTrafficLight(0, 0, ns);
        setTrafficLight(0, 1, ew);
        System.out.println("\n[AMBIENTE] Semáforos do Cruzamento C1_NW atualizados -> NS: " + ns + " | EW: " + ew);
    }

    public void addVehicle(String road, String vehicleId) {
        if (road.equalsIgnoreCase("NS")) {
            queueNS.add(vehicleId);
        } else {
            queueEW.add(vehicleId);
        }
        System.out.println("[AMBIENTE] Viatura " + vehicleId + " registada na via " + road.toUpperCase());
    }

    public int getQueueLength(String road) {
        return road.equalsIgnoreCase("NS") ? queueNS.size() : queueEW.size();
    }
}
