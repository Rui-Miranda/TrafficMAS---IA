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
        addBehaviour(new TickerBehaviour(this, 100) {
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
                double distStop = topo.getDistanceToTargetStopLine(vv.x, vv.y, vv.direction);
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
        if (p.hasLeadingObstacle(110.0) && (now - lastYieldRequestSentMillis > 1500L)) {
            lastYieldRequestSentMillis = now;
            emitirYieldRequestParaLideres(p, topo);
        }

        // 2. Preempção Semafórica Segura
        if (interIdx >= 0 && interIdx < topo.getIntersectionCount()) {
            String crossingName = topo.getIntersectionName(interIdx);
            if (distToStop >= 0 && distToStop <= 240.0 && (currentTargetIntersection != interIdx || crossingState == VehicleCrossingState.APROXIMACAO)) {
                if (currentTargetIntersection >= 0 && currentTargetIntersection != interIdx) {
                    notificarDesimpedimento(currentTargetIntersection);
                }
                currentTargetIntersection = interIdx;
                crossingState = VehicleCrossingState.PEDIDO_ENVIADO;
                lastRequestSentMillis = now;

                String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;

                System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                        + "] A enviar REQUEST de preempção a " + targetSemName 
                        + " (Distância: " + (int)distToStop + "px | Urgência: " + urgencyLevel + ")");

                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
                request.setOntology("emergency-preemption");
                request.setConversationId("emerg-req-" + getLocalName() + "-" + interIdx);
                request.setContent("PREEMPCAO_SOCORRO: Viatura " + getLocalName() 
                        + " (Cat=" + category + ", Urg=" + urgencyLevel + ", Mis=" + mission.getMissionId()
                        + ") em missao urgente no Cruzamento " + crossingName 
                        + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");

                send(request);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
                crossingState = VehicleCrossingState.AGUARDA_VERDE;
            } else if (crossingState == VehicleCrossingState.AGUARDA_VERDE && (now - lastRequestSentMillis > 3000L) && distToStop >= 0 && distToStop <= 240.0) {
                lastRequestSentMillis = now;
                String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;
                ACLMessage retry = new ACLMessage(ACLMessage.REQUEST);
                retry.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
                retry.setOntology("emergency-preemption");
                retry.setConversationId("emerg-retry-" + getLocalName() + "-" + interIdx);
                retry.setContent("RETRY_PREEMPCAO: Viatura " + getLocalName() + " (Cat=" + category + ", Urg=" + urgencyLevel + ") aguarda concessao de verde.");
                send(retry);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }

            if ((crossingState == VehicleCrossingState.PASSAGEM_SEGURA || crossingState == VehicleCrossingState.ATRAVESSANDO)
                    && (distToStop > 260.0 || currentTargetIntersection != interIdx)) {
                notificarDesimpedimento(currentTargetIntersection >= 0 ? currentTargetIntersection : interIdx);
                crossingState = VehicleCrossingState.APROXIMACAO;
                currentTargetIntersection = -1;
            }
        }

        // 3. Deliberação e Controlo Cinemático Autónomo
        boolean obstacleAhead = p.hasLeadingObstacle(36.0);
        boolean redLightAhead = p.isApproachingStopLine(65.0) && !p.isLightGreen() && crossingState != VehicleCrossingState.PASSAGEM_SEGURA;
        VehicleAction act;
        double targetSpd;

        if (obstacleAhead || redLightAhead) {
            double limitDist = obstacleAhead
                    ? (redLightAhead ? Math.min(p.getDistanceToLeadingVehicle() - 32.0, p.getDistanceToStopLine()) : (p.getDistanceToLeadingVehicle() - 32.0))
                    : p.getDistanceToStopLine();

            if (limitDist <= 3.5) {
                act = VehicleAction.STOP;
                targetSpd = 0.0;
            } else {
                act = VehicleAction.BRAKE;
                targetSpd = Math.max(0.0, (limitDist / 45.0) * p.getMaxSpeed());
            }
        } else {
            act = VehicleAction.ACCELERATE;
            targetSpd = p.getMaxSpeed();
        }

        publicarDecisaoEmergencia(p, act, targetSpd, 1, "MISSAO_ATIVA_" + category);
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

            if (distAhead > 5.0 && distAhead < 140.0) {
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
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] >>> Confirmação 3/3 (VERDE CONCEDIDO)! " 
                    + "Passagem segura garantida pelo semáforo " + msg.getSender().getLocalName());
            this.crossingState = VehicleCrossingState.PASSAGEM_SEGURA;
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
