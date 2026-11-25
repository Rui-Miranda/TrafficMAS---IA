package agents;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.EmergencyMission;
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
import metrics.TrafficMetricsCollector;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Agente Veículo de Emergência / Prioritário com Negociação FIPA e Autonomia Operacional (Fases 1, 6.1 e 6.3).
 *
 * Características essenciais da Fase 6.3:
 * 1. Integração no Modelo Multiagente Autónomo: PERCEÇÃO -> RACIOCÍNIO -> AÇÃO -> GATEWAY.
 * 2. Missão Explícita e Urgência Dinâmica (1..5): Urgência baseada no tipo de ocorrência
 *    em vez de hierarquia rígida pré-definida.
 * 3. Cooperação e Cedência Física FIPA-ACL (ontologia "emergency-yield"):
 *    Emite YIELD_REQUEST a veículos à frente que obstruem a via e notifica EMERGENCY_PASSED após passagem.
 * 4. Preempção Semafórica Segura: Solicita prioridade às autoridades semafóricas com tempos
 *    seguros de transição YELLOW e ALL_RED.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.3)
 */
public class EmergencyVehicleAgent extends Agent {

    private static final long serialVersionUID = 3L;

    public enum VehicleCrossingState {
        APROXIMACAO,
        PEDIDO_ENVIADO,
        AGUARDA_VERDE,
        PASSAGEM_SEGURA,
        ATRAVESSANDO,
        DESIMPEDIDO
    }

    private String road; // "NS" ou "EW"
    private int axis; // 0=NS, 1=EW
    private int visualDirection = 0;
    private String category = "AMBULANCE";
    private int urgencyLevel = 3;
    private boolean activeMission = true;
    private EmergencyMission mission;

    private int currentTargetIntersection = -1;
    private int currentTargetAxis = -1;
    private int lookaheadTargetIntersection = -1;
    private int lookaheadTargetAxis = -1;
    private VehicleCrossingState crossingState = VehicleCrossingState.APROXIMACAO;
    private long lastRequestSentMillis = 0;
    private long lastYieldRequestSentMillis = 0;
    private long decisionSequence = 0L;
    private long lastLivePerceptionTimestamp = -1L;
    private int livePerceptionTicks = 0;

    // Planeamento autónomo de rotas A* com saída do mapa ao concluir missão e reentrada em nova ocorrência
    private int originNode = 0;
    private int destinationNode = -1;
    private AStarPathfinder.RouteResult activeRoute = null;
    private CitySimulationGUI.ManeuverIntent plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
    private long routeGraphVersion = -1L;
    private int completedRoutesCount = 0;
    private boolean exitingMap = false;
    private int exitDirection = -1;
    private boolean wasOffMapStandby = false;
    private final ManhattanHeuristic heuristic = new ManhattanHeuristic();

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            this.road = args[0].toString();
        } else {
            this.road = "NS";
        }
        this.axis = road.equalsIgnoreCase("NS") ? 0 : 1;
        this.visualDirection = (axis == 0) ? 0 : 2;

        if (args != null && args.length > 1 && args[1] instanceof Integer) {
            this.visualDirection = (Integer) args[1];
            this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;
        }
        if (args != null && args.length > 2 && args[2] != null) {
            this.category = args[2].toString().toUpperCase(Locale.ROOT);
        } else {
            String lower = getLocalName().toLowerCase(Locale.ROOT);
            if (lower.contains("bombeir") || lower.contains("fire")) this.category = "FIRE_TRUCK";
            else if (lower.contains("polici")) this.category = "POLICE";
            else this.category = "AMBULANCE";
        }
        if (args != null && args.length > 3 && args[3] instanceof Integer) {
            this.urgencyLevel = (Integer) args[3];
        }
        if (args != null && args.length > 4 && args[4] instanceof Boolean) {
            this.activeMission = (Boolean) args[4];
        }

        // Inicialização da Missão Explícita de Emergência (Fase 6.3)
        this.mission = new EmergencyMission(
                "MIS_" + getLocalName() + "_" + System.currentTimeMillis(),
                getLocalName(), category, urgencyLevel,
                "Missao Urgente de Socorro (" + category + ")"
        );
        if (!activeMission) {
            this.mission.setState(EmergencyMission.MissionState.IDLE);
        }

        // 1. Registo no Gestor de Frota e no Gateway de Controlo Operacional
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "EmergencyVehicleAgent");
        VehicleControlGateway.getInstance().registerAgent(getLocalName(), this);

        // 2. Registo no Directory Facilitator
        registarNoDF();

        // 3. Registo visual no simulador
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            if (gui.getVisualVehicle(getLocalName()) == null) {
                Color bodyColor = Color.WHITE;
                if ("FIRE_TRUCK".equalsIgnoreCase(category) || "BOMBEIROS".equalsIgnoreCase(category)) {
                    bodyColor = new Color(220, 45, 45);
                } else if ("POLICE".equalsIgnoreCase(category) || "POLICIA".equalsIgnoreCase(category)) {
                    bodyColor = new Color(40, 75, 175);
                }
                gui.addVehicle(getLocalName(), visualDirection, bodyColor);
            }
        }

        System.out.println("[EmergencyVehicleAgent] Viatura prioritária " + getLocalName() 
                + " (Categoria=" + category + ", Urgência=" + urgencyLevel + ", MissãoAtiva=" + activeMission
                + ", MissaoId=" + mission.getMissionId() + ") pronta no corredor " + road + ".");

        // 4. Comportamento Periódico Deliberativo (Perceção -> Decisão -> FIPA)
        addBehaviour(new TickerBehaviour(this, 60) {
            private static final long serialVersionUID = 1L;
            @Override
            protected void onTick() {
                gerirNavegacaoECruzamento();
            }
        });

        // 5. Comportamento Cíclico: Receção de confirmações FIPA (semáforos e cedência de viaturas)
        addBehaviour(new CyclicBehaviour(this) {
            private static final long serialVersionUID = 1L;
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                    processarRespostaFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    public String getCategory() {
        return category;
    }

    public int getUrgencyLevel() {
        return urgencyLevel;
    }

    public void setUrgencyLevel(int urgencyLevel) {
        this.urgencyLevel = Math.max(1, Math.min(5, urgencyLevel));
        if (this.mission != null) {
            this.mission.setUrgencyLevel(this.urgencyLevel);
        }
    }

    public boolean isActiveMission() {
        return activeMission;
    }

    public void setActiveMission(boolean active) {
        this.activeMission = active;
        if (this.mission != null) {
            this.mission.setState(active ? EmergencyMission.MissionState.EN_ROUTE : EmergencyMission.MissionState.IDLE);
        }
    }

    public EmergencyMission getMission() {
        return mission;
    }

    public void setMission(EmergencyMission mission) {
        if (mission != null) {
            this.mission = mission;
            this.urgencyLevel = mission.getUrgencyLevel();
            this.activeMission = mission.isActive();
        }
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("emergency-vehicle-service");
        sd.setName("Emergencia-" + getLocalName());
        sd.addProperties(new Property("category", category));
        sd.addProperties(new Property("urgencyLevel", urgencyLevel));
        sd.addProperties(new Property("activeMission", activeMission));
        sd.addProperties(new Property("missionId", (mission != null ? mission.getMissionId() : "")));

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[EmergencyVehicleAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    private void gerirNavegacaoECruzamento() {
        if (environment.SimulationClock.isSimulatedMode()) {
            return;
        }
        VehiclePerception p = VehicleControlGateway.getInstance().getLatestPerception(getLocalName());
        if (p != null && p.getTimestampMillis() != lastLivePerceptionTimestamp) {
            lastLivePerceptionTimestamp = p.getTimestampMillis();
            if (livePerceptionTicks < 10) livePerceptionTicks++;
        }
        boolean hasLivePerception = (livePerceptionTicks >= 3);
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        NetworkTopologyConfig topo = (gui != null) ? gui.getTopology() : TrafficEnvironment.getInstance().getTopology();
        CitySimulationGUI.VisualVehicle vvLive = (gui != null) ? gui.getVisualVehicle(getLocalName()) : null;

        // Gestão do ciclo de vida fora do mapa (standby após conclusão de missão e reentrada em nova ocorrência)
        if (vvLive != null && vvLive.offMapStandby) {
            this.wasOffMapStandby = true;
            if (currentTargetIntersection >= 0) {
                notificarDesimpedimento(currentTargetIntersection, currentTargetAxis >= 0 ? currentTargetAxis : this.axis);
                currentTargetIntersection = -1;
                currentTargetAxis = -1;
            }
            if (lookaheadTargetIntersection >= 0) {
                notificarDesimpedimento(lookaheadTargetIntersection, lookaheadTargetAxis >= 0 ? lookaheadTargetAxis : this.axis);
                lookaheadTargetIntersection = -1;
                lookaheadTargetAxis = -1;
            }
            this.crossingState = VehicleCrossingState.APROXIMACAO;
            this.exitingMap = false;
            this.exitDirection = -1;
            this.activeRoute = null;
            this.destinationNode = -1;
            this.activeMission = false;
            if (this.mission != null && this.mission.isActive()) {
                this.mission.setState(EmergencyMission.MissionState.COMPLETED);
            }
            return;
        }
        if (vvLive != null && !vvLive.offMapStandby && this.wasOffMapStandby) {
            this.wasOffMapStandby = false;
            this.activeMission = vvLive.activeMission;
            if (vvLive.mission != null) {
                this.mission = vvLive.mission;
            }
            this.completedRoutesCount = vvLive.completedRoutesCount;
            this.exitingMap = false;
            this.exitDirection = -1;
            this.activeRoute = null;
            this.destinationNode = -1;
            this.crossingState = VehicleCrossingState.APROXIMACAO;
            this.currentTargetIntersection = -1;
            this.currentTargetAxis = -1;
            this.lookaheadTargetIntersection = -1;
            this.lookaheadTargetAxis = -1;
        }

        if (p == null && vvLive != null) {
            int nextInter = topo.getTargetIntersection(vvLive.x, vvLive.y, vvLive.direction);
            double distStop = topo.getBumperDistanceToTargetStopLine(vvLive.x, vvLive.y, vvLive.direction);
            p = new VehiclePerception(
                    getLocalName(), vvLive.x, vvLive.y, vvLive.currentSpeed, vvLive.maxSpeed,
                    vvLive.direction, vvLive.laneIndex, vvLive.inIntersection, nextInter, distStop,
                    CitySimulationGUI.LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                    System.currentTimeMillis(), 0L
            );
        }

        if (p == null) return;

        this.visualDirection = p.getDirection();
        this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;

        // 0. Atualização autónoma da rota A* (permite viragens à esquerda, direita e em frente na malha urbana)
        if (!p.isInIntersection()) {
            atualizarRotaAStarEmergencia(p, topo, gui);
        }

        int interIdx = p.getApproachingIntersection();
        double distToStop = p.getDistanceToStopLine();
        long now = System.currentTimeMillis();

        // Se a missão não está ativa, não solicita prioridades
        if (!activeMission || (mission != null && !mission.isActive())) {
            publicarDecisaoEmergencia(p, VehicleAction.CRUISE, p.getMaxSpeed() * 0.75, 1, "CONDUCAO_NORMAL_SEM_MISSAO", false);
            return;
        }

        // 1. Deteção e Comunicação FIPA de Cedência com Veículos à Frente (Ontologia emergency-yield)
        if (p.hasLeadingObstacle(200.0) && (now - lastYieldRequestSentMillis > 900L)) {
            lastYieldRequestSentMillis = now;
            emitirYieldRequestParaLideres(p, topo);
        }

        // 2. Preempção Semafórica Segura com Onda Verde Antecipada (Lookahead ao longo da rota A*)
        if (!p.isInIntersection() && interIdx >= 0 && interIdx < topo.getIntersectionCount()) {
            // Se saiu completamente do último cruzamento do corredor (rumo ao wrap-around), liberta-o de imediato
            if (distToStop < -96.0 && currentTargetIntersection == interIdx) {
                notificarDesimpedimento(currentTargetIntersection, currentTargetAxis >= 0 ? currentTargetAxis : this.axis);
                currentTargetIntersection = -1;
                currentTargetAxis = -1;
                crossingState = VehicleCrossingState.APROXIMACAO;
            }

            if (distToStop >= 0.0 && distToStop <= 360.0
                    && (currentTargetIntersection != interIdx || currentTargetAxis != this.axis || crossingState == VehicleCrossingState.APROXIMACAO)) {
                if (currentTargetIntersection >= 0 && (currentTargetIntersection != interIdx || currentTargetAxis != this.axis)) {
                    notificarDesimpedimento(currentTargetIntersection, currentTargetAxis >= 0 ? currentTargetAxis : this.axis);
                }
                currentTargetIntersection = interIdx;
                currentTargetAxis = this.axis;
                if (lookaheadTargetIntersection == interIdx && lookaheadTargetAxis == this.axis) {
                    // O pedido antecipado de onda verde já foi enviado para este cruzamento e eixo;
                    // se ainda não estiver verde, reforça o pedido (idempotente) para garantir preempção ativa
                    lookaheadTargetIntersection = -1;
                    lookaheadTargetAxis = -1;
                    lastRequestSentMillis = now;
                    if (p.isLightGreen()) {
                        crossingState = VehicleCrossingState.PASSAGEM_SEGURA;
                    } else {
                        enviarPedidoPreempcao(topo, interIdx, this.axis, distToStop, false);
                        crossingState = VehicleCrossingState.AGUARDA_VERDE;
                    }
                } else {
                    if (lookaheadTargetIntersection == interIdx) {
                        // O lookahead tinha sido enviado para outro eixo: limpa-o antes de pedir o eixo real
                        notificarDesimpedimento(lookaheadTargetIntersection, lookaheadTargetAxis >= 0 ? lookaheadTargetAxis : this.axis);
                        lookaheadTargetIntersection = -1;
                        lookaheadTargetAxis = -1;
                    }
                    crossingState = VehicleCrossingState.PEDIDO_ENVIADO;
                    lastRequestSentMillis = now;
                    enviarPedidoPreempcao(topo, interIdx, this.axis, distToStop, false);
                    crossingState = p.isLightGreen() ? VehicleCrossingState.PASSAGEM_SEGURA : VehicleCrossingState.AGUARDA_VERDE;
                }
            } else if (!p.isLightGreen() && distToStop >= 0.0 && distToStop <= 360.0) {
                // Nunca fica preso em PASSAGEM_SEGURA se o sinal mudou para vermelho/amarelo: reenvia preempção
                crossingState = VehicleCrossingState.AGUARDA_VERDE;
                if (now - lastRequestSentMillis > 1000L) {
                    lastRequestSentMillis = now;
                    enviarPedidoPreempcao(topo, interIdx, this.axis, distToStop, true);
                }
            }

            // Onda verde antecipada sensível à rota A* (funciona em frente, à esquerda e à direita!):
            // dispara apenas quando o cruzamento atual já está verde (evitando bloquear prematuramente o nó seguinte)
            int[] nextHop = resolveNextRouteHop(topo, interIdx, visualDirection);
            int nextInterIdx = nextHop[0];
            int nextInterAxis = nextHop[1];

            if (hasLivePerception && !this.exitingMap && nextInterIdx >= 0 && nextInterIdx != interIdx
                    && p.isLightGreen() && p.getCurrentSpeed() >= 0.5
                    && distToStop >= -20.0 && distToStop <= 125.0) {
                if (nextInterIdx != lookaheadTargetIntersection || nextInterAxis != lookaheadTargetAxis) {
                    if (lookaheadTargetIntersection >= 0 && lookaheadTargetIntersection != currentTargetIntersection) {
                        notificarDesimpedimento(lookaheadTargetIntersection, lookaheadTargetAxis >= 0 ? lookaheadTargetAxis : this.axis);
                    }
                    lookaheadTargetIntersection = nextInterIdx;
                    lookaheadTargetAxis = nextInterAxis;
                    enviarPedidoPreempcao(topo, nextInterIdx, nextInterAxis, Math.max(0.0, distToStop) + 215.0, false);
                }
            } else if (!this.exitingMap && nextInterIdx < 0 && distToStop < -96.0 && isNearWrapBoundary(topo, p.getX(), p.getY(), visualDirection, 110.0)) {
                int wrapEntryIdx = getWrapEntryIntersection(topo, interIdx, visualDirection);
                if (wrapEntryIdx >= 0 && wrapEntryIdx != interIdx
                        && (wrapEntryIdx != lookaheadTargetIntersection || this.axis != lookaheadTargetAxis)) {
                    if (lookaheadTargetIntersection >= 0 && lookaheadTargetIntersection != currentTargetIntersection) {
                        notificarDesimpedimento(lookaheadTargetIntersection, lookaheadTargetAxis >= 0 ? lookaheadTargetAxis : this.axis);
                    }
                    lookaheadTargetIntersection = wrapEntryIdx;
                    lookaheadTargetAxis = this.axis;
                    enviarPedidoPreempcao(topo, wrapEntryIdx, this.axis, 260.0, false);
                }
            } else if (this.exitingMap && lookaheadTargetIntersection >= 0 && lookaheadTargetIntersection != currentTargetIntersection) {
                notificarDesimpedimento(lookaheadTargetIntersection, lookaheadTargetAxis >= 0 ? lookaheadTargetAxis : this.axis);
                lookaheadTargetIntersection = -1;
                lookaheadTargetAxis = -1;
            }

            if ((crossingState == VehicleCrossingState.PASSAGEM_SEGURA || crossingState == VehicleCrossingState.ATRAVESSANDO)
                    && (distToStop > 380.0 || currentTargetIntersection != interIdx)) {
                notificarDesimpedimento(currentTargetIntersection >= 0 ? currentTargetIntersection : interIdx,
                        currentTargetAxis >= 0 ? currentTargetAxis : this.axis);
                crossingState = VehicleCrossingState.APROXIMACAO;
                currentTargetIntersection = -1;
                currentTargetAxis = -1;
            }
        }

        // 3. Deliberação e Controlo Cinemático Autónomo + Corredor de Emergência Central + Prioridade em Semáforo Vermelho (Opção A)
        double safeSpeedFactor = (p.getRoadCondition() != null) ? p.getRoadCondition().getSafeSpeedFactor() : 1.0;
        double safeDistanceFactor = (p.getRoadCondition() != null) ? p.getRoadCondition().getSafeDistanceFactor() : 1.0;
        double effectiveMaxSpeed = p.getMaxSpeed() * safeSpeedFactor;

        // Ativa o alinhamento pelo eixo central entre as 2 vias sempre que há tráfego à frente/lado no corredor
        boolean useCentralCorridor = !p.isInIntersection()
                && (gui != null && gui.hasTrafficAheadOnCorridor(getLocalName(), 280.0));

        double baseObstacleThreshold = 34.0 * safeDistanceFactor;
        double stopLineThreshold = Math.max(55.0, 55.0 * safeDistanceFactor);

        boolean obstacleAhead = p.hasLeadingObstacle(baseObstacleThreshold);
        boolean intersectionSafeForRedPriority = (gui != null && interIdx >= 0
                && gui.isIntersectionSafeForEmergencyCross(interIdx, this.axis));
        // Opção A: prioridade máxima sobre semáforos vermelhos com verificação básica de segurança no cruzamento
        boolean mustStopForRedLight = p.isApproachingStopLine(stopLineThreshold)
                && !p.isLightGreen()
                && !intersectionSafeForRedPriority;

        VehicleAction act;
        double targetSpd;

        if (obstacleAhead || mustStopForRedLight) {
            double safetyMarginPx = 31.0 * safeDistanceFactor;
            double limitDist = obstacleAhead
                    ? (mustStopForRedLight ? Math.min(p.getDistanceToLeadingVehicle() - safetyMarginPx, p.getDistanceToStopLine()) : (p.getDistanceToLeadingVehicle() - safetyMarginPx))
                    : p.getDistanceToStopLine();

            if (limitDist <= (3.0 * safeDistanceFactor)) {
                act = VehicleAction.STOP;
                targetSpd = 0.0;
            } else {
                act = VehicleAction.BRAKE;
                double decel = 0.11 / safeDistanceFactor;
                targetSpd = Math.max(0.35, Math.min(effectiveMaxSpeed, Math.sqrt(2.0 * decel * limitDist)));
            }
        } else if (p.isApproachingStopLine(stopLineThreshold) && !p.isLightGreen() && intersectionSafeForRedPriority) {
            // Cruzamento verificado livre de tráfego perpendicular: avança com prioridade cautelosa
            act = VehicleAction.CRUISE;
            targetSpd = Math.min(effectiveMaxSpeed * 0.78, 2.2 * safeSpeedFactor);
        } else {
            act = VehicleAction.ACCELERATE;
            targetSpd = effectiveMaxSpeed;
        }

        // Em via livre: faixa direita (0) quando vai virar à direita; faixa esquerda (1) em frente ou à esquerda
        int desiredLane = (!useCentralCorridor && this.plannedIntent == CitySimulationGUI.ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        publicarDecisaoEmergencia(p, act, targetSpd, desiredLane, "MISSAO_ATIVA_" + category, useCentralCorridor);
    }

    private void atualizarRotaAStarEmergencia(VehiclePerception p, NetworkTopologyConfig topo, CitySimulationGUI gui) {
        int approachingIdx = p.getApproachingIntersection();
        if (approachingIdx < 0 || approachingIdx >= topo.getIntersectionCount()) {
            this.plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
            return;
        }
        RoadNetworkGraph graph = TrafficEnvironment.getInstance().getRoadGraph();
        if (graph == null) return;

        // Se já está em fase de saída do mapa pelo cruzamento de periferia de destino, mantém a saída até cruzar a fronteira
        if (this.exitingMap) {
            if (p.getDirection() == this.exitDirection || p.getDistanceToStopLine() < -40.0) {
                this.plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
                if (gui != null) gui.setEmergencyExitingMap(getLocalName(), true);
                return;
            }
            if (approachingIdx == this.destinationNode && this.exitDirection >= 0) {
                this.plannedIntent = resolveTurnIntent(p.getDirection(), this.exitDirection);
                if (gui != null) gui.setEmergencyExitingMap(getLocalName(), true);
                return;
            }
            this.exitingMap = false;
            this.exitDirection = -1;
            if (gui != null) gui.setEmergencyExitingMap(getLocalName(), false);
        }

        boolean routeInvalid = (this.activeRoute == null)
                || !this.activeRoute.reachable
                || (this.routeGraphVersion != graph.getGraphVersion())
                || !this.activeRoute.nodePath.contains(approachingIdx);

        if (!routeInvalid && approachingIdx == this.destinationNode) {
            // Chegou ao cruzamento de periferia de destino: inicia saída física do mapa (Opção 1)
            int resolvedExit = resolveOffMapExitDirection(topo, approachingIdx, p.getDirection());
            if (resolvedExit >= 0) {
                this.exitingMap = true;
                this.exitDirection = resolvedExit;
                this.plannedIntent = resolveTurnIntent(p.getDirection(), resolvedExit);
                if (gui != null) {
                    gui.setEmergencyExitingMap(getLocalName(), true);
                }
                return;
            }
            // Fallback se o destino não fosse periférico: escolhe destino de periferia para sair do mapa
            this.originNode = approachingIdx;
            this.destinationNode = selectNextEmergencyDestination(graph, topo, approachingIdx, p.getDirection());
            this.activeRoute = AStarPathfinder.findPathAStar(graph, this.originNode, this.destinationNode, p.getDirection(), heuristic);
            this.routeGraphVersion = graph.getGraphVersion();
            if (gui != null) {
                gui.syncEmergencyVisualRoute(getLocalName(), this.originNode, this.destinationNode, this.activeRoute);
            }
        } else if (routeInvalid) {
            this.originNode = approachingIdx;
            int targetDest = this.destinationNode;
            if (targetDest < 0 || targetDest >= topo.getIntersectionCount()
                    || targetDest == approachingIdx || !isPerimeterNode(topo, targetDest)) {
                targetDest = selectNextEmergencyDestination(graph, topo, approachingIdx, p.getDirection());
            }
            AStarPathfinder.RouteResult candidate = AStarPathfinder.findPathAStar(graph, approachingIdx, targetDest, p.getDirection(), heuristic);
            if (!candidate.reachable) {
                targetDest = selectNextEmergencyDestination(graph, topo, approachingIdx, p.getDirection());
                candidate = AStarPathfinder.findPathAStar(graph, approachingIdx, targetDest, p.getDirection(), heuristic);
            }
            this.destinationNode = targetDest;
            this.activeRoute = candidate;
            this.routeGraphVersion = graph.getGraphVersion();
            if (gui != null) {
                gui.syncEmergencyVisualRoute(getLocalName(), this.originNode, this.destinationNode, this.activeRoute);
            }
        }

        if (this.activeRoute != null && this.activeRoute.reachable) {
            this.plannedIntent = this.activeRoute.getManeuverForIntersection(approachingIdx);
        } else {
            this.plannedIntent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
        }
    }

    private boolean isPerimeterNode(NetworkTopologyConfig topo, int nodeIdx) {
        if (!topo.isValidIntersection(nodeIdx)) return false;
        int r = topo.getRow(nodeIdx);
        int c = topo.getCol(nodeIdx);
        return (r == 0 || r == topo.getRows() - 1 || c == 0 || c == topo.getCols() - 1);
    }

    private int resolveOffMapExitDirection(NetworkTopologyConfig topo, int nodeIdx, int currentDir) {
        if (!isPerimeterNode(topo, nodeIdx)) return -1;
        int r = topo.getRow(nodeIdx);
        int c = topo.getCol(nodeIdx);
        int oppositeDir;
        switch (currentDir) {
            case 0: oppositeDir = 1; break;
            case 1: oppositeDir = 0; break;
            case 2: oppositeDir = 3; break;
            default: oppositeDir = 2; break;
        }
        int[] candidateDirs = new int[4];
        int n = 0;
        if (r == 0) candidateDirs[n++] = 1;                    // Sai para Norte (margem superior)
        if (r == topo.getRows() - 1) candidateDirs[n++] = 0;   // Sai para Sul (margem inferior)
        if (c == 0) candidateDirs[n++] = 3;                    // Sai para Oeste (margem esquerda)
        if (c == topo.getCols() - 1) candidateDirs[n++] = 2;   // Sai para Este (margem direita)

        // Prefere seguir em frente se a direção atual já sai do mapa; caso contrário vira para a saída válida sem inversão
        for (int i = 0; i < n; i++) {
            if (candidateDirs[i] == currentDir) {
                return candidateDirs[i];
            }
        }
        for (int i = 0; i < n; i++) {
            if (candidateDirs[i] != oppositeDir) {
                return candidateDirs[i];
            }
        }
        return -1;
    }

    private CitySimulationGUI.ManeuverIntent resolveTurnIntent(int fromDir, int toDir) {
        if (fromDir == toDir) return CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
        boolean isRight = (fromDir == 0 && toDir == 3)
                || (fromDir == 1 && toDir == 2)
                || (fromDir == 2 && toDir == 0)
                || (fromDir == 3 && toDir == 1);
        return isRight ? CitySimulationGUI.ManeuverIntent.TURN_RIGHT : CitySimulationGUI.ManeuverIntent.TURN_LEFT;
    }

    private int selectNextEmergencyDestination(RoadNetworkGraph graph, NetworkTopologyConfig topo, int fromNode, int currentDir) {
        int count = topo.getIntersectionCount();
        int fromRow = topo.getRow(fromNode);
        int fromCol = topo.getCol(fromNode);
        int seed = Math.abs(getLocalName().hashCode() + completedRoutesCount * 7 + currentDir * 3);

        int bestFallback = (fromNode + 1) % count;
        int bestPerimeterShort = -1;
        int bestPerimeterTurn = -1;

        for (int attempt = 0; attempt < count; attempt++) {
            int cand = (fromNode + 1 + ((seed + attempt * 5) % (count - 1))) % count;
            if (cand == fromNode) continue;
            AStarPathfinder.RouteResult res = AStarPathfinder.findPathAStar(graph, fromNode, cand, currentDir, heuristic);
            if (!res.reachable || res.nodePath.size() < 2) continue;
            bestFallback = cand;
            if (!isPerimeterNode(topo, cand)) continue;
            // Prefere destinos de periferia noutra linha e coluna (>= 3 nós) para atravessar a cidade com viragens antes de sair do mapa
            boolean differentCorridor = (topo.getRow(cand) != fromRow) && (topo.getCol(cand) != fromCol);
            if (differentCorridor && res.nodePath.size() >= 3) {
                return cand;
            }
            if (bestPerimeterTurn < 0 && (topo.getRow(cand) != fromRow || topo.getCol(cand) != fromCol)) {
                bestPerimeterTurn = cand;
            }
            if (bestPerimeterShort < 0) {
                bestPerimeterShort = cand;
            }
        }
        if (bestPerimeterTurn >= 0) return bestPerimeterTurn;
        return (bestPerimeterShort >= 0) ? bestPerimeterShort : bestFallback;
    }

    private int[] resolveNextRouteHop(NetworkTopologyConfig topo, int currentInterIdx, int currentDir) {
        if (this.activeRoute != null && this.activeRoute.reachable && this.activeRoute.nodePath != null) {
            List<Integer> nodes = this.activeRoute.nodePath;
            List<RoadNetworkGraph.RoadSegment> segs = this.activeRoute.segmentPath;
            for (int i = 0; i < nodes.size() - 1; i++) {
                if (nodes.get(i) == currentInterIdx && i < segs.size()) {
                    int nextNode = nodes.get(i + 1);
                    int segDir = segs.get(i).direction;
                    int nextAxis = (segDir == 0 || segDir == 1) ? 0 : 1;
                    return new int[] { nextNode, nextAxis };
                }
            }
        }
        int adj = getAdjacentCorridorIntersection(topo, currentInterIdx, currentDir);
        int curAxis = (currentDir == 0 || currentDir == 1) ? 0 : 1;
        return new int[] { adj, curAxis };
    }

    private void enviarPedidoPreempcao(NetworkTopologyConfig topo, int targetInterIdx, int targetAxis, double approxDist, boolean isRetry) {
        String crossingName = topo.getIntersectionName(targetInterIdx);
        String targetSemName = "Semaforo_" + (targetAxis == 0 ? "NS" : "EW") + "_" + crossingName;

        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        msg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        msg.setOntology("emergency-preemption");
        if (isRetry) {
            msg.setConversationId("emerg-retry-" + getLocalName() + "-" + targetInterIdx);
            msg.setContent("RETRY_PREEMPCAO: Viatura " + getLocalName()
                    + " (Cat=" + category + ", Urg=" + urgencyLevel + ") aguarda concessao de verde.");
        } else {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName()
                    + "] A enviar REQUEST de preempção a " + targetSemName
                    + " (Distância: " + (int) approxDist + "px | Urgência: " + urgencyLevel + ")");
            msg.setConversationId("emerg-req-" + getLocalName() + "-" + targetInterIdx);
            msg.setContent("PREEMPCAO_SOCORRO: Viatura " + getLocalName()
                    + " (Cat=" + category + ", Urg=" + urgencyLevel + ", Mis=" + mission.getMissionId()
                    + ") em missao urgente no Cruzamento " + crossingName
                    + " (Eixo " + (targetAxis == 0 ? "NS" : "EW") + ").");
        }
        send(msg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();
    }

    private int getAdjacentCorridorIntersection(NetworkTopologyConfig topo, int currentInterIdx, int dir) {
        if (!topo.isValidIntersection(currentInterIdx)) return -1;
        int r = topo.getRow(currentInterIdx);
        int c = topo.getCol(currentInterIdx);
        switch (dir) {
            case 0: return (r + 1 < topo.getRows()) ? topo.getIntersectionIndex(r + 1, c) : -1;
            case 1: return (r - 1 >= 0) ? topo.getIntersectionIndex(r - 1, c) : -1;
            case 2: return (c + 1 < topo.getCols()) ? topo.getIntersectionIndex(r, c + 1) : -1;
            case 3: return (c - 1 >= 0) ? topo.getIntersectionIndex(r, c - 1) : -1;
            default: return -1;
        }
    }

    private int getWrapEntryIntersection(NetworkTopologyConfig topo, int currentInterIdx, int dir) {
        if (!topo.isValidIntersection(currentInterIdx)) return -1;
        int r = topo.getRow(currentInterIdx);
        int c = topo.getCol(currentInterIdx);
        switch (dir) {
            case 0: return topo.getIntersectionIndex(0, c);
            case 1: return topo.getIntersectionIndex(topo.getRows() - 1, c);
            case 2: return topo.getIntersectionIndex(r, 0);
            case 3: return topo.getIntersectionIndex(r, topo.getCols() - 1);
            default: return -1;
        }
    }

    private boolean isNearWrapBoundary(NetworkTopologyConfig topo, double x, double y, int dir, double thresholdPx) {
        switch (dir) {
            case 0: return (topo.getWorldHeight() + 25.0 - y) <= thresholdPx;
            case 1: return (y + 35.0) <= thresholdPx;
            case 2: return (topo.getWorldWidth() + 25.0 - x) <= thresholdPx;
            case 3: return (x + 35.0) <= thresholdPx;
            default: return false;
        }
    }

    private void emitirYieldRequestParaLideres(VehiclePerception p, NetworkTopologyConfig topo) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) return;

        CitySimulationGUI.VisualVehicle vv = gui.getVisualVehicle(getLocalName());
        if (vv == null) return;

        int emTargetInter = topo.getTargetIntersection(p.getX(), p.getY(), p.getDirection());
        boolean emOnExitRamp = (topo.getDistanceToTargetStopLine(p.getX(), p.getY(), p.getDirection()) < -10.0);
        int nextStraight = (!emOnExitRamp && this.plannedIntent == CitySimulationGUI.ManeuverIntent.GO_STRAIGHT)
                ? getAdjacentCorridorIntersection(topo, emTargetInter, p.getDirection()) : -1;

        java.util.List<CitySimulationGUI.VisualVehicle> snapshot = gui.getVisualVehiclesSnapshot();
        for (CitySimulationGUI.VisualVehicle other : snapshot) {
            if (other.offMapStandby || other.id.equals(getLocalName()) || other.isEmergency) continue;
            if (other.direction != p.getDirection()) continue;

            // Mesmo corredor e mesma rua/segmento (ou segmento seguinte apenas se a emergência vai em frente)
            boolean sameCorridor = (p.getDirection() <= 1)
                    ? (topo.getClosestCol(other.x) == topo.getClosestCol(p.getX()))
                    : (topo.getClosestRow(other.y) == topo.getClosestRow(p.getY()));

            if (!sameCorridor) continue;

            int otherTarget = topo.getTargetIntersection(other.x, other.y, other.direction);
            boolean otherOnExitRamp = (topo.getDistanceToTargetStopLine(other.x, other.y, other.direction) < -10.0);
            boolean sameSeg = (otherTarget == emTargetInter && otherOnExitRamp == emOnExitRamp);
            boolean nextSeg = (!emOnExitRamp && this.plannedIntent == CitySimulationGUI.ManeuverIntent.GO_STRAIGHT)
                    && ((nextStraight >= 0) ? (otherTarget == nextStraight && !otherOnExitRamp)
                    : (otherTarget == emTargetInter && otherOnExitRamp));
            if (!sameSeg && !nextSeg) continue;

            // À frente da emergência dentro de 140px
            double distAhead = 9999.0;
            switch (p.getDirection()) {
                case 0: if (other.y > p.getY()) distAhead = other.y - p.getY(); break;
                case 1: if (other.y < p.getY()) distAhead = p.getY() - other.y; break;
                case 2: if (other.x > p.getX()) distAhead = other.x - p.getX(); break;
                case 3: if (other.x < p.getX()) distAhead = p.getX() - other.x; break;
            }

            if (distAhead > 5.0 && distAhead < 260.0) {
                ACLMessage yieldReq = new ACLMessage(ACLMessage.REQUEST);
                yieldReq.addReceiver(new AID(other.id, AID.ISLOCALNAME));
                yieldReq.setOntology("emergency-yield");
                yieldReq.setConversationId("yield-req-" + getLocalName() + "-" + other.id);
                yieldReq.setContent(String.format(Locale.ROOT,
                        "YIELD_REQUEST: missionId=%s; category=%s; urgency=%d; dir=%d; dist=%.1f",
                        mission.getMissionId(), category, urgencyLevel, p.getDirection(), distAhead));

                send(yieldReq);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }
        }
    }

    private void publicarDecisaoEmergencia(VehiclePerception p, VehicleAction action, double targetSpeed,
                                           int desiredLane, String state, boolean useCentralCorridor) {
        this.decisionSequence++;
        long now = System.currentTimeMillis();
        long validUntil = now + VehicleControlGateway.DEFAULT_MAX_STALENESS_MS;

        List<Integer> routeNodes = (this.activeRoute != null && this.activeRoute.reachable)
                ? this.activeRoute.nodePath : Collections.emptyList();

        VehicleDecision dec = new VehicleDecision(
                getLocalName(),
                this.decisionSequence,
                now,
                validUntil,
                action,
                targetSpeed,
                this.plannedIntent,
                desiredLane,
                this.originNode,
                this.destinationNode,
                routeNodes,
                state,
                "Emergencia em curso: " + state + " (" + this.plannedIntent + (useCentralCorridor ? " | CORREDOR_CENTRAL" : "") + ")",
                true,
                environment.VehicleYieldState.NORMAL_DRIVING,
                null,
                0.0,
                useCentralCorridor
        );

        VehicleControlGateway.getInstance().publishDecision(dec);
    }

    private void processarRespostaFIPA(ACLMessage msg) {
        String content = msg.getContent();
        String ontology = msg.getOntology();

        if ("emergency-yield".equalsIgnoreCase(ontology)) {
            if (msg.getPerformative() == ACLMessage.AGREE || (content != null && content.contains("YIELD_IN_PROGRESS"))) {
                System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Viatura "
                        + msg.getSender().getLocalName() + " iniciou manobra de cedência à direita.");
            } else if (content != null && content.contains("YIELD_COMPLETED")) {
                System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] >>> Viatura "
                        + msg.getSender().getLocalName() + " CONCLUIU a cedência lateral! Via prioritária livre.");
            } else if (content != null && content.contains("YIELD_UNSAFE")) {
                System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Viatura "
                        + msg.getSender().getLocalName() + " reportou cedência insegura no momento; velocidade ajustada.");
            }
            return;
        }

        if (msg.getPerformative() == ACLMessage.CONFIRM || (content != null && content.startsWith("RECEBIDO"))) {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Confirmação 1/3 (RECEBIDO) de " 
                    + msg.getSender().getLocalName());
            return;
        }

        if (msg.getPerformative() == ACLMessage.AGREE || (content != null && content.startsWith("ACEITE"))) {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Confirmação 2/3 (ACEITE) de " 
                    + msg.getSender().getLocalName() + ". Transição de segurança em curso.");
            return;
        }

        if (content != null && content.contains("VERDE_CONCEDIDO")) {
            String senderName = msg.getSender().getLocalName();
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] >>> Confirmação 3/3 (VERDE CONCEDIDO)! "
                    + "Passagem segura garantida pelo semáforo " + senderName);
            NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
            if (currentTargetIntersection >= 0 && currentTargetIntersection < topo.getIntersectionCount()
                    && senderName.endsWith(topo.getIntersectionName(currentTargetIntersection))) {
                this.crossingState = VehicleCrossingState.PASSAGEM_SEGURA;
            }
        }
    }

    private void notificarDesimpedimento(int interIdx, int targetAxis) {
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        if (interIdx < 0 || interIdx >= topo.getIntersectionCount()) return;

        int resolvedAxis = (targetAxis == 0 || targetAxis == 1) ? targetAxis : this.axis;
        String crossingName = topo.getIntersectionName(interIdx);
        String targetSemName = "Semaforo_" + (resolvedAxis == 0 ? "NS" : "EW") + "_" + crossingName;
        System.out.println("[EmergencyVehicleAgent - " + getLocalName()
                + "] Cruzamento " + crossingName + " desimpedido. A emitir FIPA INFORM...");

        ACLMessage clearedMsg = new ACLMessage(ACLMessage.INFORM);
        clearedMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        clearedMsg.setOntology("emergency-cleared");
        clearedMsg.setConversationId("emerg-cleared-" + getLocalName() + "-" + interIdx);
        clearedMsg.setContent("DESIMPEDIDO: Viatura " + getLocalName() + " concluiu a travessia de " + crossingName);

        send(clearedMsg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();

        // Notifica também os veículos normais no eixo atravessado com EMERGENCY_PASSED
        ACLMessage passedMsg = new ACLMessage(ACLMessage.INFORM);
        passedMsg.setOntology("emergency-yield");
        passedMsg.setConversationId("emerg-passed-" + getLocalName() + "-" + interIdx);
        passedMsg.setContent("EMERGENCY_PASSED: missionId=" + (mission != null ? mission.getMissionId() : "N/A"));

        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            for (CitySimulationGUI.VisualVehicle vv : gui.getVisualVehiclesSnapshot()) {
                int vvAxis = (vv.direction == 0 || vv.direction == 1) ? 0 : 1;
                if (!vv.isEmergency && (vv.direction == this.visualDirection || vvAxis == resolvedAxis)) {
                    passedMsg.addReceiver(new AID(vv.id, AID.ISLOCALNAME));
                }
            }
            if (passedMsg.getAllReceiver().hasNext()) {
                send(passedMsg);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }
        }
    }

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        VehicleControlGateway.getInstance().unregisterAgent(getLocalName());
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.removeVehicle(getLocalName());
        }
        try {
            DFService.deregister(this);
            System.out.println("[EmergencyVehicleAgent] Viatura " + getLocalName() + " desregistada do DF.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
