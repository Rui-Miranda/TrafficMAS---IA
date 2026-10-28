package environment;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor Centralizado do Ciclo de Vida da Frota e de Agentes JADE (Fase 6.1 — R2, R3, R4).
 *
 * Responsabilidades:
 * 1. Garantir o limite estrito de {@link #MAX_NORMAL_VEHICLES} (50) veículos normais ativos (intervalo [0, 50]).
 * 2. Excluir os veículos prioritários/emergência ({@code EmergencyVehicleAgent}) do limite dos 50 normais.
 * 3. Manter a correspondência estrita 1:1 entre cada veículo gráfico ({@link CitySimulationGUI.VisualVehicle})
 *    e o respetivo agente JADE real ({@link agents.VehicleAgent} ou {@link agents.EmergencyVehicleAgent}).
 * 4. Disponibilizar APIs thread-safe para adicionar e remover veículos normais em execução sem recursos órfãos
 *    e sem eliminar veículos prioritários como efeito colateral.
 * 5. Impedir sobreposição física de veículos nas posições de spawn.
 * 6. Auditar a contagem real de agentes JADE ativos na configuração de referência (73 agentes).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.1)
 */
public final class FleetLifecycleManager {

    /** Limite máximo obrigatório de veículos normais ativos em simultâneo (R2 / R4) */
    public static final int MAX_NORMAL_VEHICLES = 50;

    /** Configuração inicial padrão para a rede 3×3 (R2) */
    public static final int DEFAULT_INITIAL_NORMAL_VEHICLES_3X3 = 50;

    /** Configuração inicial recomendada de veículos prioritários fora do limite de 50 (R3) */
    public static final int DEFAULT_INITIAL_PRIORITY_VEHICLES_3X3 = 3;

    /** Paleta de cores distintas para viaturas regulares autónomas */
    private static final Color[] NORMAL_VEHICLE_PALETTE = {
        new Color(30, 155, 225),  // Azul urbano
        new Color(45, 185, 135),  // Verde esmeralda
        new Color(235, 145, 35),  // Âmbar dourado
        new Color(140, 95, 215),  // Violeta suave
        new Color(65, 115, 205),  // Azul cobalto
        new Color(40, 175, 175),  // Turquesa
        new Color(215, 95, 130),  // Carmim urbano
        new Color(100, 135, 165)  // Cinza aço
    };

    private static final FleetLifecycleManager INSTANCE = new FleetLifecycleManager();

    private volatile AgentContainer activeContainer = null;
    private final Map<String, AgentController> normalControllers = new ConcurrentHashMap<>();
    private final Map<String, AgentController> priorityControllers = new ConcurrentHashMap<>();
    private final Map<String, AgentController> infrastructureControllers = new ConcurrentHashMap<>();

    /** Registo em tempo real alimentado diretamente por setup() / takeDown() dos agentes JADE */
    private final Map<String, String> liveJadeAgentsByType = new ConcurrentHashMap<>();

    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    private FleetLifecycleManager() {}

    public static FleetLifecycleManager getInstance() {
        return INSTANCE;
    }

    public synchronized void bindContainer(AgentContainer container) {
        this.activeContainer = container;
    }

    public AgentContainer getBoundContainer() {
        return activeContainer;
    }

    /**
     * Reinicia os registos do gestor de frota (usado entre testes ou reinicializações).
     */
    public synchronized void reset() {
        normalControllers.clear();
        priorityControllers.clear();
        infrastructureControllers.clear();
        liveJadeAgentsByType.clear();
        sequenceCounter.set(0);
    }

    /* =========================================================================
     * REGISTO DE CICLO DE VIDA DIRETO DOS AGENTES JADE (setup / takeDown)
     * ========================================================================= */

    public void registerLiveJadeAgent(String localName, String agentType) {
        if (localName != null && agentType != null) {
            liveJadeAgentsByType.put(localName, agentType);
        }
    }

    public void unregisterLiveJadeAgent(String localName) {
        if (localName != null) {
            liveJadeAgentsByType.remove(localName);
        }
    }

    public Map<String, String> getLiveJadeAgentsSnapshot() {
        return new LinkedHashMap<>(liveJadeAgentsByType);
    }

    public int getLiveJadeAgentsCount() {
        return liveJadeAgentsByType.size();
    }

    public int getLiveJadeAgentsCountByType(String simpleClassName) {
        int count = 0;
        for (String type : liveJadeAgentsByType.values()) {
            if (simpleClassName.equals(type)) {
                count++;
            }
        }
        return count;
    }

    public void registerInfrastructureController(String agentName, AgentController controller) {
        if (agentName != null && controller != null) {
            infrastructureControllers.put(agentName, controller);
        }
    }

    /* =========================================================================
     * GESTÃO DE FROTA DE VEÍCULOS NORMAIS (0 a 50) — R2 e R4
     * ========================================================================= */

    /**
     * Obtém o número de veículos normais atualmente ativos.
     */
    public synchronized int getActiveNormalVehicleCount() {
        return normalControllers.size();
    }

    /**
     * Obtém o limite máximo permitido de veículos normais (50).
     */
    public int getMaxNormalVehicles() {
        return MAX_NORMAL_VEHICLES;
    }

    /**
     * Indica se é permitido adicionar mais um veículo normal (true se ativos < 50).
     */
    public synchronized boolean isAddNormalVehicleAvailable() {
        return normalControllers.size() < MAX_NORMAL_VEHICLES;
    }

    /**
     * Indica se é possível remover um veículo normal (true se ativos > 0).
     */
    public synchronized boolean isRemoveNormalVehicleAvailable() {
        return !normalControllers.isEmpty();
    }

    /**
     * Devolve a lista ordenada de identificadores dos veículos normais ativos.
     */
    public synchronized List<String> getActiveNormalVehicleIds() {
        List<String> list = new ArrayList<>(normalControllers.keySet());
        Collections.sort(list);
        return list;
    }

    /**
     * Adiciona um novo veículo normal autónomo (agente JADE + objeto físico/gráfico 1:1),
     * gerando automaticamente um identificador único e posição de spawn sem sobreposição.
     *
     * @return Identificador do veículo criado ou {@code null} se o limite de 50 já tiver sido atingido.
     */
    public synchronized String addNormalVehicle() {
        if (!isAddNormalVehicleAvailable()) {
            return null;
        }
        String nextId = generateNextUniqueNormalId();
        boolean ok = addNormalVehicle(nextId);
        return ok ? nextId : null;
    }

    /**
     * Adiciona um veículo normal com identificador específico, respeitando o limite máximo de 50
     * e garantindo unicidade de ID, posição física sem sobreposição e rota A* válida.
     *
     * @param vehicleId Identificador único pretendido (ex: "Carro_01")
     * @return {@code true} se o agente JADE e o veículo visual foram criados com sucesso; {@code false} caso contrário
     */
    public synchronized boolean addNormalVehicle(String vehicleId) {
        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            return false;
        }
        if (normalControllers.size() >= MAX_NORMAL_VEHICLES) {
            return false; // Limite estrito de 50 veículos normais atingido
        }
        if (normalControllers.containsKey(vehicleId) || priorityControllers.containsKey(vehicleId)
                || infrastructureControllers.containsKey(vehicleId)) {
            return false; // Identificador duplicado proibido
        }
        if (activeContainer == null) {
            throw new IllegalStateException("Nenhum AgentContainer JADE está associado ao FleetLifecycleManager.");
        }

        int slotIndex = sequenceCounter.getAndIncrement();
        NetworkTopologyConfig topology = NetworkTopologyConfig.getActive();
        int direction = slotIndex % 4; // Distribuição equilibrada: 0=N->S, 1=S->N, 2=W->E, 3=E->W
        String road = (direction == 0 || direction == 1) ? "NS" : "EW";
        Color color = NORMAL_VEHICLE_PALETTE[slotIndex % NORMAL_VEHICLE_PALETTE.length];

        // 1. Registo físico/visual sem sobreposição e com rota A* calculada no grafo
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        CitySimulationGUI.VisualVehicle visual = gui.spawnVehicleSafely(
                vehicleId, direction, color, false, "NORMAL", slotIndex);

        int originNode = (visual != null) ? visual.originIntersectionId : 0;
        int destNode = (visual != null) ? visual.destinationIntersectionId : (topology.getIntersectionCount() - 1);

        // 2. Criação e arranque do agente JADE real
        try {
            AgentController controller = activeContainer.createNewAgent(
                    vehicleId,
                    "agents.VehicleAgent",
                    new Object[] { road, direction, originNode, destNode }
            );
            normalControllers.put(vehicleId, controller);
            liveJadeAgentsByType.put(vehicleId, "VehicleAgent");
            controller.start();
            return true;
        } catch (Exception e) {
            normalControllers.remove(vehicleId);
            liveJadeAgentsByType.remove(vehicleId);
            gui.removeVehicle(vehicleId);
            System.err.println("[FleetLifecycleManager] Erro ao criar VehicleAgent " + vehicleId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove o último veículo normal adicionado, libertando o agente JADE, o registo no DF,
     * o objeto gráfico na GUI e todas as referências internas.
     * NUNCA remove veículos prioritários.
     *
     * @return Identificador do veículo normal removido ou {@code null} se não existirem veículos normais ativos.
     */
    public synchronized String removeNormalVehicle() {
        if (normalControllers.isEmpty()) {
            return null;
        }
        List<String> ids = getActiveNormalVehicleIds();
        String targetId = ids.get(ids.size() - 1);
        return removeNormalVehicle(targetId) ? targetId : null;
    }

    /**
     * Remove um veículo normal específico pelo seu identificador.
     * Recusa estritamente remover veículos prioritários/emergência.
     *
     * @param vehicleId Identificador do veículo normal a remover
     * @return {@code true} se foi removido com sucesso; {@code false} se não existir ou for prioritário
     */
    public synchronized boolean removeNormalVehicle(String vehicleId) {
        if (vehicleId == null || priorityControllers.containsKey(vehicleId)) {
            // Proteção explícita: veículos prioritários nunca são afetados pela remoção de frota normal
            return false;
        }
        AgentController controller = normalControllers.remove(vehicleId);
        if (controller == null) {
            return false;
        }

        liveJadeAgentsByType.remove(vehicleId);

        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.removeVehicle(vehicleId);
        }

        try {
            controller.kill();
        } catch (Throwable ignored) {
            // O agente pode já estar em processo de encerramento
        }
        return true;
    }

    private boolean isJadeAgentStillPresent(String localName) {
        if (activeContainer == null || localName == null) {
            return false;
        }
        try {
            AgentController existing = activeContainer.getAgent(localName);
            return existing != null;
        } catch (Throwable notFound) {
            return false;
        }
    }

    private String generateNextUniqueNormalId() {
        for (int i = 1; i <= 9999; i++) {
            String candidate = String.format(Locale.US, "Carro_%02d", i);
            if (!normalControllers.containsKey(candidate)
                    && !priorityControllers.containsKey(candidate)
                    && !liveJadeAgentsByType.containsKey(candidate)
                    && !isJadeAgentStillPresent(candidate)) {
                return candidate;
            }
        }
        return "Carro_" + System.nanoTime();
    }

    /* =========================================================================
     * GESTÃO DE VEÍCULOS PRIORITÁRIOS / EMERGÊNCIA (FORA DO LIMITE DE 50) — R3
     * ========================================================================= */

    /**
     * Obtém a quantidade de veículos prioritários ativos (excluídos do limite de 50 normais).
     */
    public synchronized int getActivePriorityVehicleCount() {
        return priorityControllers.size();
    }

    public synchronized List<String> getActivePriorityVehicleIds() {
        List<String> list = new ArrayList<>(priorityControllers.keySet());
        Collections.sort(list);
        return list;
    }

    /**
     * Cria e inicia um veículo prioritário (EmergencyVehicleAgent) fora do limite de 50 veículos normais.
     *
     * @param vehicleId Identificador único (ex: "Ambulancia_INEM_01", "Bombeiros_Sapadores_01", "Viatura_Policia_01")
     * @param direction Direção inicial (0=N->S, 1=S->N, 2=W->E, 3=E->W)
     * @param category Categoria da viatura ("AMBULANCE", "FIRE_TRUCK", "POLICE", "AUTHORIZED_PRIORITY")
     * @param urgencyLevel Nível de urgência da missão (1=Normal, 2=Elevada, 3=Crítica)
     * @param activeMission Indica se a missão prioritária está ativa
     * @return {@code true} se criado com sucesso
     */
    public synchronized boolean addPriorityVehicle(String vehicleId, int direction, String category,
                                                   int urgencyLevel, boolean activeMission) {
        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            return false;
        }
        if (normalControllers.containsKey(vehicleId) || priorityControllers.containsKey(vehicleId)
                || infrastructureControllers.containsKey(vehicleId)) {
            return false;
        }
        if (activeContainer == null) {
            throw new IllegalStateException("Nenhum AgentContainer JADE está associado ao FleetLifecycleManager.");
        }

        String road = (direction == 0 || direction == 1) ? "NS" : "EW";
        Color color = resolvePriorityColor(category);
        int slotIndex = 100 + priorityControllers.size();

        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.spawnVehicleSafely(vehicleId, direction, color, true, category, slotIndex);

        try {
            AgentController controller = activeContainer.createNewAgent(
                    vehicleId,
                    "agents.EmergencyVehicleAgent",
                    new Object[] { road, direction, category, urgencyLevel, activeMission }
            );
            priorityControllers.put(vehicleId, controller);
            liveJadeAgentsByType.put(vehicleId, "EmergencyVehicleAgent");
            controller.start();
            return true;
        } catch (Exception e) {
            priorityControllers.remove(vehicleId);
            liveJadeAgentsByType.remove(vehicleId);
            gui.removeVehicle(vehicleId);
            System.err.println("[FleetLifecycleManager] Erro ao criar EmergencyVehicleAgent " + vehicleId + ": " + e.getMessage());
            return false;
        }
    }

    private static Color resolvePriorityColor(String category) {
        if (category == null) return Color.WHITE;
        switch (category.toUpperCase(Locale.ROOT)) {
            case "FIRE_TRUCK":
            case "BOMBEIROS":
                return new Color(225, 45, 45);
            case "POLICE":
            case "POLICIA":
                return new Color(40, 75, 175);
            case "AMBULANCE":
            case "AMBULANCIA":
            default:
                return Color.WHITE;
        }
    }

    /**
     * Inicializa a frota padrão de referência da Fase 6.1 (50 veículos normais + 3 veículos prioritários).
     */
    public synchronized void initializeDefault3x3Fleet(AgentContainer container) {
        bindContainer(container);
        for (int i = 1; i <= DEFAULT_INITIAL_NORMAL_VEHICLES_3X3; i++) {
            String carId = String.format(Locale.US, "Carro_%02d", i);
            addNormalVehicle(carId);
        }
        addPriorityVehicle("Ambulancia_INEM_01", 0, "AMBULANCE", 3, true);
        addPriorityVehicle("Bombeiros_Sapadores_01", 2, "FIRE_TRUCK", 3, true);
        addPriorityVehicle("Viatura_Policia_01", 1, "POLICE", 2, true);
    }

    /**
     * Verifica rigorosamente a correspondência 1:1 entre os veículos gráficos presentes na GUI
     * e os agentes JADE de veículos geridos pelo sistema.
     */
    public synchronized boolean verifyOneToOneCorrespondence() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) {
            return false;
        }
        List<CitySimulationGUI.VisualVehicle> visualList = gui.getVisualVehiclesSnapshot();
        Set<String> visualNormalIds = new HashSet<>();
        Set<String> visualPriorityIds = new HashSet<>();

        for (CitySimulationGUI.VisualVehicle v : visualList) {
            if (v.isEmergency) {
                visualPriorityIds.add(v.id);
            } else {
                visualNormalIds.add(v.id);
            }
        }

        return visualNormalIds.equals(normalControllers.keySet())
                && visualPriorityIds.equals(priorityControllers.keySet());
    }

    /**
     * Devolve o total de agentes JADE de aplicação geridos (Semáforos + Coordenador + ML + Normais + Prioritários).
     */
    public synchronized int getTotalManagedApplicationAgentsCount() {
        return infrastructureControllers.size() + normalControllers.size() + priorityControllers.size();
    }
}

