package agents;

import environment.CitySimulationGUI;
import environment.EmergencyMission;
import environment.FleetLifecycleManager;
import environment.NetworkTopologyConfig;
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
    private int lookaheadTargetIntersection = -1;
    private VehicleCrossingState crossingState = VehicleCrossingState.APROXIMACAO;
    private long lastRequestSentMillis = 0;
    private long lastYieldRequestSentMillis = 0;
    private long decisionSequence = 0L;

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
        VehiclePerception p = VehicleControlGateway.getInstance().getLatestPerception(getLocalName());
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        NetworkTopologyConfig topo = (gui != null) ? gui.getTopology() : TrafficEnvironment.getInstance().getTopology();

        if (p == null && gui != null) {
            CitySimulationGUI.VisualVehicle vv = gui.getVisualVehicle(getLocalName());
            if (vv != null) {
                int nextInter = topo.getTargetIntersection(vv.x, vv.y, vv.direction);
                double distStop = topo.getBumperDistanceToTargetStopLine(vv.x, vv.y, vv.direction);
                p = new VehiclePerception(
                        getLocalName(), vv.x, vv.y, vv.currentSpeed, vv.maxSpeed,
                        vv.direction, vv.laneIndex, vv.inIntersection, nextInter, distStop,
                        CitySimulationGUI.LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                        System.currentTimeMillis(), 0L
                );
            }
        }

        if (p == null) return;

        this.visualDirection = p.getDirection();
        this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;

        int interIdx = p.getApproachingIntersection();
        double distToStop = p.getDistanceToStopLine();
        long now = System.currentTimeMillis();

        // Se a missão não está ativa, não solicita prioridades
        if (!activeMission || (mission != null && !mission.isActive())) {
            publicarDecisaoEmergencia(p, VehicleAction.CRUISE, p.getMaxSpeed() * 0.75, 1, "CONDUCAO_NORMAL_SEM_MISSAO");
            return;
        }

        // 1. Deteção e Comunicação FIPA de Cedência com Veículos à Frente (Ontologia emergency-yield)
        if (p.hasLeadingObstacle(200.0) && (now - lastYieldRequestSentMillis > 900L)) {
            lastYieldRequestSentMillis = now;
            emitirYieldRequestParaLideres(p, topo);
        }

        // 2. Preempção Semafórica Segura com Onda Verde Antecipada (Lookahead no corredor)
        if (interIdx >= 0 && interIdx < topo.getIntersectionCount()) {
            // Se saiu completamente do último cruzamento do corredor (rumo ao wrap-around), liberta-o de imediato
            if (distToStop < -96.0 && currentTargetIntersection == interIdx) {
                notificarDesimpedimento(currentTargetIntersection);
                currentTargetIntersection = -1;
                crossingState = VehicleCrossingState.APROXIMACAO;
            }

            if (distToStop >= 0.0 && distToStop <= 360.0
                    && (currentTargetIntersection != interIdx || crossingState == VehicleCrossingState.APROXIMACAO)) {
                if (currentTargetIntersection >= 0 && currentTargetIntersection != interIdx) {
                    notificarDesimpedimento(currentTargetIntersection);
                }
                currentTargetIntersection = interIdx;
                if (lookaheadTargetIntersection == interIdx) {
                    // O pedido antecipado de onda verde já foi enviado para este cruzamento;
                    // se ainda não estiver verde, reforça o pedido (idempotente) para garantir preempção ativa
                    lookaheadTargetIntersection = -1;
                    lastRequestSentMillis = now;
                    if (p.isLightGreen()) {
                        crossingState = VehicleCrossingState.PASSAGEM_SEGURA;
                    } else {
                        enviarPedidoPreempcao(topo, interIdx, distToStop, false);
                        crossingState = VehicleCrossingState.AGUARDA_VERDE;
                    }
                } else {
                    crossingState = VehicleCrossingState.PEDIDO_ENVIADO;
                    lastRequestSentMillis = now;
                    enviarPedidoPreempcao(topo, interIdx, distToStop, false);
                    crossingState = p.isLightGreen() ? VehicleCrossingState.PASSAGEM_SEGURA : VehicleCrossingState.AGUARDA_VERDE;
                }
            } else if (crossingState == VehicleCrossingState.AGUARDA_VERDE
                    && !p.isLightGreen()
                    && (now - lastRequestSentMillis > 1000L)
                    && distToStop >= 0.0 && distToStop <= 360.0) {
                lastRequestSentMillis = now;
                enviarPedidoPreempcao(topo, interIdx, distToStop, true);
            }

            // Onda verde antecipada: solicita preempção segura ao cruzamento adjacente seguinte do corredor
            // ~3-3.5s antes da chegada para que YELLOW (1.5s) + ALL_RED (1.0s) terminem antes da stop line
            int adjacentDownstream = getAdjacentCorridorIntersection(topo, interIdx, visualDirection);
            if (adjacentDownstream >= 0 && distToStop >= -20.0 && distToStop <= 155.0) {
                if (adjacentDownstream != lookaheadTargetIntersection) {
                    if (lookaheadTargetIntersection >= 0 && lookaheadTargetIntersection != currentTargetIntersection) {
                        notificarDesimpedimento(lookaheadTargetIntersection);
                    }
                    lookaheadTargetIntersection = adjacentDownstream;
                    enviarPedidoPreempcao(topo, adjacentDownstream, Math.max(0.0, distToStop) + 215.0, false);
                }
            } else if (adjacentDownstream < 0 && distToStop < -96.0 && isNearWrapBoundary(topo, p.getX(), p.getY(), visualDirection, 110.0)) {
                // Após sair do último cruzamento do corredor e já perto da fronteira de wrap-around (~3s do 1.º cruzamento),
                // antecipa a onda verde para o primeiro cruzamento de entrada do mesmo corredor
                int wrapEntryIdx = getWrapEntryIntersection(topo, interIdx, visualDirection);
                if (wrapEntryIdx >= 0 && wrapEntryIdx != interIdx && wrapEntryIdx != lookaheadTargetIntersection) {
                    if (lookaheadTargetIntersection >= 0 && lookaheadTargetIntersection != currentTargetIntersection) {
                        notificarDesimpedimento(lookaheadTargetIntersection);
                    }
                    lookaheadTargetIntersection = wrapEntryIdx;
                    enviarPedidoPreempcao(topo, wrapEntryIdx, 260.0, false);
                }
            }

            if ((crossingState == VehicleCrossingState.PASSAGEM_SEGURA || crossingState == VehicleCrossingState.ATRAVESSANDO)
                    && (distToStop > 380.0 || currentTargetIntersection != interIdx)) {
                notificarDesimpedimento(currentTargetIntersection >= 0 ? currentTargetIntersection : interIdx);
                crossingState = VehicleCrossingState.APROXIMACAO;
                currentTargetIntersection = -1;
            }
        }

        // 3. Deliberação e Controlo Cinemático Autónomo Adaptado à Meteorologia (Fase 6.4)
        double safeSpeedFactor = (p.getRoadCondition() != null) ? p.getRoadCondition().getSafeSpeedFactor() : 1.0;
        double safeDistanceFactor = (p.getRoadCondition() != null) ? p.getRoadCondition().getSafeDistanceFactor() : 1.0;
        double effectiveMaxSpeed = p.getMaxSpeed() * safeSpeedFactor;

        double baseObstacleThreshold = 34.0 * safeDistanceFactor;
        double stopLineThreshold = Math.max(55.0, 55.0 * safeDistanceFactor);

        boolean obstacleAhead = p.hasLeadingObstacle(baseObstacleThreshold);
        boolean redLightAhead = p.isApproachingStopLine(stopLineThreshold) && !p.isLightGreen();
        VehicleAction act;
        double targetSpd;

        if (obstacleAhead || redLightAhead) {
            double safetyMarginPx = 31.0 * safeDistanceFactor;
            double limitDist = obstacleAhead
                    ? (redLightAhead ? Math.min(p.getDistanceToLeadingVehicle() - safetyMarginPx, p.getDistanceToStopLine()) : (p.getDistanceToLeadingVehicle() - safetyMarginPx))
                    : p.getDistanceToStopLine();

            if (limitDist <= (3.0 * safeDistanceFactor)) {
                act = VehicleAction.STOP;
                targetSpd = 0.0;
            } else {
                act = VehicleAction.BRAKE;
                double decel = 0.11 / safeDistanceFactor;
                targetSpd = Math.max(0.35, Math.min(effectiveMaxSpeed, Math.sqrt(2.0 * decel * limitDist)));
            }
        } else {
            act = VehicleAction.ACCELERATE;
            targetSpd = effectiveMaxSpeed;
        }

        publicarDecisaoEmergencia(p, act, targetSpd, 1, "MISSAO_ATIVA_" + category);
    }

    private void enviarPedidoPreempcao(NetworkTopologyConfig topo, int targetInterIdx, double approxDist, boolean isRetry) {
        String crossingName = topo.getIntersectionName(targetInterIdx);
        String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;

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
                    + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");
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

        java.util.List<CitySimulationGUI.VisualVehicle> snapshot = gui.getVisualVehiclesSnapshot();
        for (CitySimulationGUI.VisualVehicle other : snapshot) {
            if (other.id.equals(getLocalName()) || other.isEmergency) continue;
            if (other.direction != p.getDirection()) continue;

            // Mesmo corredor
            boolean sameCorridor = (p.getDirection() <= 1)
                    ? (topo.getClosestCol(other.x) == topo.getClosestCol(p.getX()))
                    : (topo.getClosestRow(other.y) == topo.getClosestRow(p.getY()));

            if (!sameCorridor) continue;

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

    private void publicarDecisaoEmergencia(VehiclePerception p, VehicleAction action, double targetSpeed, int desiredLane, String state) {
        this.decisionSequence++;
        long now = System.currentTimeMillis();
        long validUntil = now + VehicleControlGateway.DEFAULT_MAX_STALENESS_MS;

        VehicleDecision dec = new VehicleDecision(
                getLocalName(),
                this.decisionSequence,
                now,
                validUntil,
                action,
                targetSpeed,
                CitySimulationGUI.ManeuverIntent.GO_STRAIGHT,
                desiredLane,
                p.getApproachingIntersection(),
                -1,
                Collections.emptyList(),
                state,
                "Emergencia em curso: " + state,
                true
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

    private void notificarDesimpedimento(int interIdx) {
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        if (interIdx < 0 || interIdx >= topo.getIntersectionCount()) return;

        String crossingName = topo.getIntersectionName(interIdx);
        String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;
        System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                + "] Cruzamento " + crossingName + " desimpedido. A emitir FIPA INFORM...");

        ACLMessage clearedMsg = new ACLMessage(ACLMessage.INFORM);
        clearedMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        clearedMsg.setOntology("emergency-cleared");
        clearedMsg.setConversationId("emerg-cleared-" + getLocalName() + "-" + interIdx);
        clearedMsg.setContent("DESIMPEDIDO: Viatura " + getLocalName() + " concluiu a travessia de " + crossingName);

        send(clearedMsg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();

        // Notifica também os veículos normais com EMERGENCY_PASSED
        ACLMessage passedMsg = new ACLMessage(ACLMessage.INFORM);
        passedMsg.setOntology("emergency-yield");
        passedMsg.setConversationId("emerg-passed-" + getLocalName() + "-" + interIdx);
        passedMsg.setContent("EMERGENCY_PASSED: missionId=" + (mission != null ? mission.getMissionId() : "N/A"));

        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            for (CitySimulationGUI.VisualVehicle vv : gui.getVisualVehiclesSnapshot()) {
                if (!vv.isEmergency && vv.direction == this.visualDirection) {
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
