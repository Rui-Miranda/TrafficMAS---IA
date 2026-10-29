package agents;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.FleetLifecycleManager;
import environment.ManhattanHeuristic;
import environment.NetworkTopologyConfig;
import environment.RoadNetworkGraph;
import environment.TrafficEnvironment;
import environment.VehicleAction;
import environment.VehicleControlGateway;
import environment.VehicleDecision;
import environment.VehiclePerception;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import metrics.TrafficMetricsCollector;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agente Veículo Autónomo Operacional (Fase 6.2-D).
 *
 * Implementa o paradigma clássico de sistemas multiagente:
 * PERCEÇÃO -> RACIOCÍNIO -> AÇÃO.
 *
 * Responsabilidades Autónomas:
 * 1. Estado Individual: Mantém origem, destino, rota A* ativa, manobra planeada e telemetria.
 * 2. Planeamento de Rota: Calcula e recalcula autonomamente a rota A* com ManhattanHeuristic.
 * 3. Deliberação de Condução: Decide aceleração, velocidade de cruzeiro, travagem e paragem segura.
 * 4. Comunicação FIPA-ACL: Emite pedidos de prioridade normal (REQUEST) ao semáforo competente
 *    (com mapeamento rigoroso do eixo NS/EW) e processa respostas (AGREE, REFUSE, INFORM).
 * 5. Publicação de Decisões: Publica decisões válidas no {@link VehicleControlGateway}.
 *    Se o agente for suspenso ou terminar, o gateway impõe paragem segura imediata.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.2-D)
 */
public class VehicleAgent extends Agent {

    private static final long serialVersionUID = 1L;

    /** Limiar de espera acima do qual o veículo emite pedido de prioridade FIPA (segundos) */
    public static final double LIMIAR_ESPERA_SEGUNDOS = 6.0;

    /** Intervalo mínimo entre envios de REQUEST ao mesmo cruzamento (ms) */
    public static final long INTERVALO_MINIMO_REQUEST_MS = 6000L;

    /** Período do ciclo deliberativo autónomo do agente (ms) */
    public static final int TICK_BEHAVIOUR_PERIOD_MS = 40;

    // Estado Topológico e de Navegação A*
    private int originNode = 0;
    private int destinationNode = 0;
    private AStarPathfinder.RouteResult activeRoute = null;
    private long routeGraphVersion = -1L;
    private int completedRoutesCount = 0;
    private int routeRecalculationsCount = 0;

    // Estado Cinemático e Manobra Planeada
    private int visualDirection = 0; // 0: N->S, 1: S->N, 2: W->E, 3: E->W
    private int axis = 0; // 0: Norte-Sul (NS), 1: Este-Oeste (EW)
    private CitySimulationGUI.ManeuverIntent plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
    private int desiredLane = 1; // 0: exterior/direita, 1: interior/esquerda
    private double targetSpeed = 2.4;
    private String drivingState = "INICIALIZANDO";

    // Ciclo Deliberativo e Telemetria
    private long decisionSequence = 0L;
    private VehiclePerception lastPerception = null;
    private VehicleDecision lastDecision = null;
    private long stoppedSinceMillis = 0L;
    private double currentWaitTimeSeconds = 0.0;

    // Comunicação FIPA-ACL
    private final long[] lastRequestPerIntersection = new long[16];
    private int lastRequestedIntersection = -1;
    private String lastFipaResponse = "NENHUMA";
    private int fipaRequestsSent = 0;
    private int fipaRepliesReceived = 0;
    private boolean lastCycleFipaRequested = false;

    // Heurística de Manhattan reutilizável
    private final ManhattanHeuristic heuristic = new ManhattanHeuristic(ManhattanHeuristic.HeuristicMode.ADAPTIVE_ADMISSIBLE);

    // Cache local de semáforos no DF para otimização
    private static final Map<String, String> dfSemaphoreCache = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        parseArguments();

        // 1. Registo no Gestor de Frota e no Gateway de Controlo
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "VehicleAgent");
        VehicleControlGateway.getInstance().registerAgent(getLocalName(), this);

        // 2. Registo no Directory Facilitator (DF)
        registarNoDF();

        // 3. Planeamento Autónomo Inicial da Rota A*
        inicializarRotaAutonoma();

        // 4. Integração visual na GUI (se não foi já criada pelo FleetLifecycleManager)
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            if (gui.getVisualVehicle(getLocalName()) == null) {
                java.awt.Color carColor = new java.awt.Color(30, 160, 230);
                gui.addVehicle(getLocalName(), visualDirection, carColor, plannedIntent);
            }
        }

        System.out.printf(Locale.US,
                "[VehicleAgent - %s] Inicializado com autonomia operacional | Origem=%d, Destino=%d | Eixo=%s (Dir=%d) | Rota A*=%s%n",
                getLocalName(), originNode, destinationNode, (axis == 0 ? "NS" : "EW"), visualDirection,
                (activeRoute != null ? activeRoute.nodePath : "[]"));

        // 5. Comportamento Deliberativo Periódico (TickerBehaviour a 40ms)
        addBehaviour(new TickerBehaviour(this, TICK_BEHAVIOUR_PERIOD_MS) {
            private static final long serialVersionUID = 1L;
            @Override
            protected void onTick() {
                senseReasonAct();
            }
        });

        // 6. Comportamento Cíclico de Escuta FIPA-ACL (filtrado para não interceptar mensagens do DF)
        final MessageTemplate trafficTemplate = MessageTemplate.MatchOntology("traffic-management");
        addBehaviour(new CyclicBehaviour(this) {
            private static final long serialVersionUID = 1L;
            @Override
            public void action() {
                ACLMessage reply = receive(trafficTemplate);
                if (reply != null) {
                    processarRespostaFIPA(reply);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Interpretação robusta de todos os argumentos de inicialização.
     */
    private void parseArguments() {
        Object[] args = getArguments();
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        int maxNodes = topo.getIntersectionCount();

        this.originNode = 0;
        this.destinationNode = (maxNodes > 1) ? (maxNodes - 1) : 0;
        this.visualDirection = 0;

        if (args != null && args.length > 0 && args[0] != null) {
            String arg0 = args[0].toString();
            if (arg0.equalsIgnoreCase("EW") || arg0.equals("1")) {
                this.axis = 1;
                this.visualDirection = 2;
            } else if (arg0.equalsIgnoreCase("NS") || arg0.equals("0")) {
                this.axis = 0;
                this.visualDirection = 0;
            } else {
                try {
                    int d = Integer.parseInt(arg0);
                    this.visualDirection = (d >= 0 && d <= 3) ? d : 0;
                    this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;
                } catch (NumberFormatException ignored) {}
            }
        }

        if (args != null && args.length > 1 && args[1] != null) {
            if (args[1] instanceof Integer) {
                int d = (Integer) args[1];
                this.visualDirection = (d >= 0 && d <= 3) ? d : this.visualDirection;
                this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;
            }
        }

        if (args != null && args.length > 2 && args[2] instanceof Integer) {
            int orig = (Integer) args[2];
            if (orig >= 0 && orig < maxNodes) {
                this.originNode = orig;
            }
        }

        if (args != null && args.length > 3 && args[3] instanceof Integer) {
            int dst = (Integer) args[3];
            if (dst >= 0 && dst < maxNodes && dst != this.originNode) {
                this.destinationNode = dst;
            }
        }

        if (this.destinationNode == this.originNode) {
            this.destinationNode = selectAutonomousDestination(this.originNode);
        }
    }

    private void inicializarRotaAutonoma() {
        RoadNetworkGraph graph = TrafficEnvironment.getInstance().getRoadGraph();
        this.activeRoute = AStarPathfinder.findPathAStar(graph, originNode, destinationNode, visualDirection, heuristic);
        if (!this.activeRoute.reachable) {
            this.destinationNode = selectAutonomousDestination(originNode);
            this.activeRoute = AStarPathfinder.findPathAStar(graph, originNode, destinationNode, visualDirection, heuristic);
        }
        this.routeGraphVersion = graph.getGraphVersion();
        this.routeRecalculationsCount++;

        if (this.activeRoute.reachable) {
            this.plannedIntent = this.activeRoute.getManeuverForIntersection(originNode);
            this.desiredLane = (this.plannedIntent == CitySimulationGUI.ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        }
    }

    /**
     * Ciclo Central Autónomo: PERCEÇÃO -> RACIOCÍNIO -> AÇÃO.
     * Pode ser executado pelo TickerBehaviour ou invocado sincronicamente em testes.
     *
     * @return Decisão deliberada e publicada pelo agente (ou {@code null} se sem perceção)
     */
    public synchronized VehicleDecision senseReasonAct() {
        // 1. PERCEÇÃO: Consulta o snapshot sensorial do motor físico no gateway
        VehiclePerception p = VehicleControlGateway.getInstance().getLatestPerception(getLocalName());
        if (p == null) {
            return null;
        }
        this.lastPerception = p;
        this.visualDirection = p.getDirection();
        this.axis = (p.getDirection() == 0 || p.getDirection() == 1) ? 0 : 1;

        RoadNetworkGraph graph = TrafficEnvironment.getInstance().getRoadGraph();
        int approachingIdx = p.getApproachingIntersection();

        // 2. RACIOCÍNIO — GESTÃO E REPLANEAMENTO DA ROTA A*
        if (approachingIdx >= 0 && approachingIdx == this.destinationNode) {
            // Conclui missão atual e seleciona autonomamente um novo destino
            this.completedRoutesCount++;
            this.originNode = this.destinationNode;
            this.destinationNode = selectAutonomousDestination(this.originNode);
            this.activeRoute = AStarPathfinder.findPathAStar(graph, this.originNode, this.destinationNode, p.getDirection(), heuristic);
            this.routeGraphVersion = p.getGraphVersion();
            this.routeRecalculationsCount++;
        } else if (p.getGraphVersion() != this.routeGraphVersion || this.activeRoute == null || !this.activeRoute.reachable) {
            // Replaneia percurso devido a alteração topológica ou rota inválida
            this.routeGraphVersion = p.getGraphVersion();
            int startNode = (approachingIdx >= 0) ? approachingIdx : this.originNode;
            this.activeRoute = AStarPathfinder.findPathAStar(graph, startNode, this.destinationNode, p.getDirection(), heuristic);
            this.routeRecalculationsCount++;
        }

        // Determina a manobra planeada para o próximo cruzamento
        if (this.activeRoute != null && this.activeRoute.reachable && approachingIdx >= 0) {
            this.plannedIntent = this.activeRoute.getManeuverForIntersection(approachingIdx);
            this.desiredLane = (this.plannedIntent == CitySimulationGUI.ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        } else {
            this.plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
            this.desiredLane = 0;
        }

        // 3. RACIOCÍNIO — DELIBERAÇÃO CINEMÁTICA E SEGURANÇA
        boolean leaderObstacle = p.hasLeadingObstacle(38.0);
        boolean approachingStopLine = p.isApproachingStopLine(65.0);
        boolean signalNotGreen = !p.isLightGreen();
        boolean brakeForSignal = approachingStopLine && signalNotGreen;
        boolean shouldBrake = leaderObstacle || brakeForSignal;

        VehicleAction action;
        double effectiveMaxSpeed = p.getMaxSpeed() * p.getSpeedMultiplier();
        this.lastCycleFipaRequested = false;

        if (shouldBrake) {
            double limitDist = leaderObstacle
                    ? (brakeForSignal ? Math.min(p.getDistanceToLeadingVehicle() - 32.0, p.getDistanceToStopLine()) : (p.getDistanceToLeadingVehicle() - 32.0))
                    : p.getDistanceToStopLine();

            if (limitDist <= 3.0) {
                action = VehicleAction.STOP;
                this.targetSpeed = 0.0;
                this.drivingState = leaderObstacle ? "STOPPED_BEHIND_LEADER" : "STOPPED_AT_RED_LIGHT";
            } else {
                action = VehicleAction.BRAKE;
                this.targetSpeed = Math.max(0.0, (limitDist / 50.0) * effectiveMaxSpeed);
                this.drivingState = leaderObstacle ? "BRAKING_FOR_LEADER" : "BRAKING_FOR_SIGNAL";
            }

            // Monitorização do tempo de espera e emissão de pedido FIPA se retido
            if (p.getCurrentSpeed() < 0.2) {
                if (this.stoppedSinceMillis == 0L) {
                    this.stoppedSinceMillis = System.currentTimeMillis();
                }
                this.currentWaitTimeSeconds = (System.currentTimeMillis() - this.stoppedSinceMillis) / 1000.0;
                if (this.currentWaitTimeSeconds >= LIMIAR_ESPERA_SEGUNDOS) {
                    emitirPedidoPrioridadeSeNecessario(p, this.currentWaitTimeSeconds);
                }
            } else {
                this.stoppedSinceMillis = 0L;
                this.currentWaitTimeSeconds = 0.0;
            }
        } else {
            this.stoppedSinceMillis = 0L;
            this.currentWaitTimeSeconds = 0.0;
            if (p.getCurrentSpeed() < 0.2) {
                action = VehicleAction.RESUME;
                this.targetSpeed = effectiveMaxSpeed;
                this.drivingState = "RESUMING_MOTION";
            } else {
                action = VehicleAction.ACCELERATE;
                this.targetSpeed = effectiveMaxSpeed;
                this.drivingState = "CRUISING";
            }
        }

        // 4. AÇÃO: Formulação da Decisão Deliberada e Publicação no Gateway
        this.decisionSequence++;
        long now = System.currentTimeMillis();
        long validUntil = now + VehicleControlGateway.DEFAULT_MAX_STALENESS_MS;
        List<Integer> routeNodes = (this.activeRoute != null && this.activeRoute.reachable)
                ? this.activeRoute.nodePath
                : Collections.emptyList();

        String explanation = String.format(Locale.US,
                "%s -> %s (vTarget=%.2f, lane=%d, intent=%s, wait=%.1fs)",
                this.drivingState, action, this.targetSpeed, this.desiredLane, this.plannedIntent, this.currentWaitTimeSeconds);

        VehicleDecision decision = new VehicleDecision(
                getLocalName(),
                this.decisionSequence,
                now,
                validUntil,
                action,
                this.targetSpeed,
                this.plannedIntent,
                this.desiredLane,
                this.originNode,
                this.destinationNode,
                routeNodes,
                this.drivingState,
                explanation,
                this.lastCycleFipaRequested
        );

        this.lastDecision = decision;
        VehicleControlGateway.getInstance().publishDecision(decision);
        return decision;
    }

    /**
     * Seleciona autonomamente um novo nó de destino alcançável sem inversão imediata de marcha.
     */
    public int selectAutonomousDestination(int currentOrigin) {
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        int count = topo.getIntersectionCount();
        if (count <= 1) return 0;

        RoadNetworkGraph graph = TrafficEnvironment.getInstance().getRoadGraph();
        int offset = Math.abs(getLocalName().hashCode() + this.completedRoutesCount * 31);
        int bestCandidate = (currentOrigin + (count / 2) + (offset % count)) % count;
        if (bestCandidate == currentOrigin) {
            bestCandidate = (currentOrigin + 1) % count;
        }

        for (int attempt = 0; attempt < count; attempt++) {
            int candidate = (bestCandidate + attempt) % count;
            if (candidate == currentOrigin) continue;
            AStarPathfinder.RouteResult test = AStarPathfinder.findPathAStar(
                    graph, currentOrigin, candidate, this.visualDirection, heuristic);
            if (test.reachable && test.segmentPath.size() >= 1) {
                return candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * Força o replaneamento da rota do veículo para um novo destino especificado (pela aplicação ou operador).
     */
    public synchronized boolean assignNewDestination(int newDestinationNode) {
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        if (newDestinationNode < 0 || newDestinationNode >= topo.getIntersectionCount() || newDestinationNode == this.destinationNode) {
            return false;
        }
        this.destinationNode = newDestinationNode;
        RoadNetworkGraph graph = TrafficEnvironment.getInstance().getRoadGraph();
        int startNode = (this.lastPerception != null && this.lastPerception.getApproachingIntersection() >= 0)
                ? this.lastPerception.getApproachingIntersection()
                : this.originNode;
        this.activeRoute = AStarPathfinder.findPathAStar(graph, startNode, this.destinationNode, this.visualDirection, heuristic);
        this.routeGraphVersion = graph.getGraphVersion();
        this.routeRecalculationsCount++;
        senseReasonAct();
        return this.activeRoute.reachable;
    }

    /**
     * Emite um pedido de prioridade FIPA-ACL (REQUEST) ao semáforo competente.
     * Mapeamento estrito:
     * Direção 0 ou 1 -> Eixo Norte-Sul (NS / axis=0) -> Semaforo_NS_...
     * Direção 2 ou 3 -> Eixo Este-Oeste (EW / axis=1) -> Semaforo_EW_...
     */
    private void emitirPedidoPrioridadeSeNecessario(VehiclePerception p, double waitSeconds) {
        int targetIntersection = p.getApproachingIntersection();
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        if (targetIntersection < 0 || targetIntersection >= topo.getIntersectionCount()
                || targetIntersection >= this.lastRequestPerIntersection.length) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - this.lastRequestPerIntersection[targetIntersection] < INTERVALO_MINIMO_REQUEST_MS) {
            return; // Respeita cooldown anti-flooding
        }

        this.lastRequestPerIntersection[targetIntersection] = now;
        this.lastRequestedIntersection = targetIntersection;

        // Determina com rigor o eixo semafórico correto (0=NS, 1=EW)
        int currentAxis = (p.getDirection() == 0 || p.getDirection() == 1) ? 0 : 1;
        String crossingName = topo.getIntersectionName(targetIntersection);
        String targetSemName = resolverSemaforoViaDF(targetIntersection, currentAxis, crossingName);

        ACLMessage requestMsg = new ACLMessage(ACLMessage.REQUEST);
        requestMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        requestMsg.setOntology("traffic-management");
        requestMsg.setConversationId("req-wait-" + getLocalName() + "-" + crossingName + "-" + now);
        String content = String.format(Locale.US,
                "PEDIDO_PRIORIDADE_NORMAL:Viatura=%s;wait=%.1fs;intersection=%d;axis=%s",
                getLocalName(), waitSeconds, targetIntersection, (currentAxis == 0 ? "NS" : "EW"));
        requestMsg.setContent(content);

        send(requestMsg);
        this.fipaRequestsSent++;
        this.lastCycleFipaRequested = true;
        TrafficMetricsCollector.getInstance().recordFipaMessage();

        System.out.printf(Locale.US,
                "[VehicleAgent - %s] Emissor=%s -> Recetor=%s | FIPA REQUEST (traffic-management) | Decisão=PEDIDO_PRIORIDADE_NORMAL (wait=%.1fs, Eixo=%s) | Ação=Mensagem Enviada%n",
                getLocalName(), getLocalName(), targetSemName, waitSeconds, (currentAxis == 0 ? "NS" : "EW"));
    }

    private String resolverSemaforoViaDF(int intersectionIndex, int targetAxis, String crossingName) {
        String cacheKey = intersectionIndex + "_" + targetAxis + "_" + crossingName;
        String cached = dfSemaphoreCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("traffic-light-service");
            sd.addProperties(new Property("intersection", intersectionIndex));
            sd.addProperties(new Property("axis", targetAxis));
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(this, template);
            if (results != null && results.length > 0) {
                String dfName = results[0].getName().getLocalName();
                dfSemaphoreCache.put(cacheKey, dfName);
                return dfName;
            }
        } catch (FIPAException fe) {
            System.err.println("[VehicleAgent - " + getLocalName() 
                    + "] Aviso: Pesquisa no DF falhou. A usar nome canónico. Erro: " + fe.getMessage());
        }

        String axisLabel = (targetAxis == 0) ? "NS" : "EW";
        String fallback = "Semaforo_" + axisLabel + "_" + crossingName;
        dfSemaphoreCache.put(cacheKey, fallback);
        return fallback;
    }

    private void processarRespostaFIPA(ACLMessage reply) {
        TrafficMetricsCollector.getInstance().recordFipaMessage();
        this.fipaRepliesReceived++;
        String senderName = reply.getSender().getLocalName();
        int perf = reply.getPerformative();
        String perfName = ACLMessage.getPerformative(perf);
        String content = reply.getContent();

        if (perf == ACLMessage.AGREE) {
            this.lastFipaResponse = "AGREE: " + content;
        } else if (perf == ACLMessage.REFUSE) {
            this.lastFipaResponse = "REFUSE: " + content;
        } else if (perf == ACLMessage.INFORM) {
            this.lastFipaResponse = "INFORM: " + content;
        } else {
            this.lastFipaResponse = perfName + ": " + content;
        }

        System.out.printf(
                "[VehicleAgent - %s] Resposta FIPA Recebida: Emissor=%s -> Recetor=%s | Performative=%s | Conteúdo=[%s]%n",
                getLocalName(), senderName, getLocalName(), perfName, content);
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("vehicle-service");
        sd.setName("Viatura-" + getLocalName());
        sd.addProperties(new Property("axis", axis));
        sd.addProperties(new Property("road", (axis == 0 ? "NS" : "EW")));
        sd.addProperties(new Property("origin", originNode));
        sd.addProperties(new Property("destination", destinationNode));

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (Throwable fe) {
            System.err.println("[VehicleAgent] Erro ao registar veículo " + getLocalName() + " no DF: " + fe.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        VehicleControlGateway.getInstance().unregisterAgent(getLocalName());
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.removeVehicle(getLocalName());
        }
        try {
            DFService.deregister(this);
            System.out.println("[VehicleAgent] Viatura " + getLocalName() + " desregistada do DF e terminada.");
        } catch (Throwable fe) {
            // Ignorado no encerramento limpo
        }
    }

    public void setDestinationNode(int destinationNode) {
        this.destinationNode = destinationNode;
        this.activeRoute = null;
    }

    public void setStoppedSinceMillis(long millis) {
        this.stoppedSinceMillis = millis;
    }

    // Getters de Estado para Testes e Diagnóstico
    public int getOriginNode() { return originNode; }
    public int getDestinationNode() { return destinationNode; }
    public AStarPathfinder.RouteResult getActiveRoute() { return activeRoute; }
    public CitySimulationGUI.ManeuverIntent getPlannedIntent() { return plannedIntent; }
    public int getDesiredLane() { return desiredLane; }
    public double getTargetSpeed() { return targetSpeed; }
    public String getDrivingState() { return drivingState; }
    public VehiclePerception getLastPerception() { return lastPerception; }
    public VehicleDecision getLastDecision() { return lastDecision; }
    public int getFipaRequestsSent() { return fipaRequestsSent; }
    public int getFipaRepliesReceived() { return fipaRepliesReceived; }
    public String getLastFipaResponse() { return lastFipaResponse; }
    public int getCompletedRoutesCount() { return completedRoutesCount; }
    public int getRouteRecalculationsCount() { return routeRecalculationsCount; }
}
