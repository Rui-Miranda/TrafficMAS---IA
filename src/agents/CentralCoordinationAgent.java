package agents;

import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.TrafficEnvironment;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import metrics.TrafficMetricsCollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agente Coordenador Central da Rede Semafórica (Fase 3 / Fase 6.1 - Coordenação Multiagente Real).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class CentralCoordinationAgent extends Agent {

    public static final String[] CROSSINGS = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    public static final String[] CROSSINGS_3X3 = {
        "C1_NW", "C2_N", "C3_NE",
        "C4_W",  "C5_C", "C6_E",
        "C7_SW", "C8_S", "C9_SE"
    };

    public static class IntersectionTelemetry {
        public final int intersectionIndex;
        public int waitingNS = 0;
        public int waitingEW = 0;
        public String currentPhase = "NS_GREEN";
        public long currentGreenDuration = 4000;
        public boolean hasEmergency = false;
        public long lastUpdatedMs = 0;

        public IntersectionTelemetry(int intersectionIndex) {
            this.intersectionIndex = intersectionIndex;
        }

        public int getTotalWaiting() {
            return waitingNS + waitingEW;
        }
    }

    public static class CoordinatedCommandRecord {
        public final String cmdId;
        public final String policyType;
        public final int intersectionIndex;
        public final int targetAxis;
        public final long plannedDurationMs;
        public final long offsetMs;
        public final long referenceTimeMillis;
        public final long scheduledStartMillis;
        public final String corridorId;
        public final long issuedTimestamp;
        public String status;
        public String refusalReason = null;
        public long appliedTimestamp = 0;
        public long temporalDeviationMs = 0;
        public boolean wasApplied = false;

        public CoordinatedCommandRecord(String cmdId, String policyType, int intersectionIndex, 
                                      int targetAxis, long plannedDurationMs, long offsetMs) {
            this(cmdId, policyType, intersectionIndex, targetAxis, plannedDurationMs, offsetMs,
                 environment.SimulationClock.currentTimeMillis(), "CORRIDOR_GENERAL");
        }

        public CoordinatedCommandRecord(String cmdId, String policyType, int intersectionIndex, 
                                      int targetAxis, long plannedDurationMs, long offsetMs,
                                      long referenceTimeMillis, String corridorId) {
            this.cmdId = cmdId;
            this.policyType = policyType;
            this.intersectionIndex = intersectionIndex;
            this.targetAxis = targetAxis;
            this.plannedDurationMs = plannedDurationMs;
            this.offsetMs = offsetMs;
            this.referenceTimeMillis = (referenceTimeMillis > 0) ? referenceTimeMillis : environment.SimulationClock.currentTimeMillis();
            this.scheduledStartMillis = this.referenceTimeMillis + offsetMs;
            this.corridorId = (corridorId != null) ? corridorId : "CORRIDOR_GENERAL";
            this.issuedTimestamp = environment.SimulationClock.currentTimeMillis();
            this.status = "SENT";
            this.wasApplied = false;
        }
    }

    private final Map<Integer, IntersectionTelemetry> telemetryMap = new ConcurrentHashMap<>();
    private final Map<String, CoordinatedCommandRecord> commandRecords = new ConcurrentHashMap<>();
    private final List<String> commandHistory = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong commandSeq = new AtomicLong(1000);
    private final Map<Integer, Long> lastAdherenceByIntersection = new ConcurrentHashMap<>();

    private volatile String lastMLAlertId = null;
    private volatile int lastMLAlertIntersection = -1;
    private volatile double lastMLAlertProbability = -1.0;
    private volatile double lastMLAlertSpeedMultiplier = 1.0;
    private volatile long lastMLAlertReceivedMs = 0;
    private volatile int mlAlertCount = 0;
    private volatile String lastAdaptiveCommandId = null;

    private static volatile CentralCoordinationAgent activeInstance = null;

    public static CentralCoordinationAgent getActiveInstance() {
        return activeInstance;
    }

    @Override
    protected void setup() {
        activeInstance = this;
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "CentralCoordinationAgent");

        for (int i = 0; i < 9; i++) {
            telemetryMap.put(i, new IntersectionTelemetry(i));
        }

        registarNoDF();

        int interCount = NetworkTopologyConfig.getActive().getIntersectionCount();
        System.out.println("[CentralCoordinationAgent] Agente Coordenador Central online na malha urbana (" 
                + interCount + " cruzamentos).");

        addBehaviour(new TickerBehaviour(this, 3500) {
            @Override
            protected void onTick() {
                if (environment.SimulationClock.isSimulatedMode()) {
                    return;
                }
                auditarRedeETomarDecisoes();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.not(
                        jade.lang.acl.MessageTemplate.MatchSender(getDefaultDF()));
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                    processarMensagemFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("central-coordination-service");
        sd.setName("CoordenadorCentral-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[CentralCoordinationAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    private String resolveCrossingName(int intersectionIndex) {
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        if (intersectionIndex >= 0 && intersectionIndex < topo.getIntersectionCount()) {
            return topo.getIntersectionName(intersectionIndex);
        }
        if (intersectionIndex >= 0 && intersectionIndex < CROSSINGS_3X3.length) {
            return CROSSINGS_3X3[intersectionIndex];
        }
        return "C" + (intersectionIndex + 1);
    }

    public synchronized void auditarRedeETomarDecisoes() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        NetworkTopologyConfig topo = env.getTopology();
        int n = topo.getIntersectionCount();
        int rows = topo.getRows();
        int cols = topo.getCols();

        for (int i = 0; i < n; i++) {
            IntersectionTelemetry tel = telemetryMap.computeIfAbsent(i, IntersectionTelemetry::new);
            int waitingNS = env.getWaitingVehiclesCount(i, 0);
            int waitingEW = env.getWaitingVehiclesCount(i, 1);
            boolean hasEmerg = env.hasEmergencyApproaching(i, 0) || env.hasEmergencyApproaching(i, 1);

            tel.waitingNS = waitingNS;
            tel.waitingEW = waitingEW;
            tel.hasEmergency = hasEmerg;
            tel.lastUpdatedMs = environment.SimulationClock.currentTimeMillis();
        }

        // 1. Deteção de Congestionamento em Corredores Horizontais (Eixo EW = 1)
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols - 1; c++) {
                int upIdx = topo.getIntersectionIndex(r, c);
                int dnIdx = topo.getIntersectionIndex(r, c + 1);
                IntersectionTelemetry tUp = telemetryMap.get(upIdx);
                IntersectionTelemetry tDn = telemetryMap.get(dnIdx);
                int corridorEW = tUp.waitingEW + tDn.waitingEW;
                if (corridorEW >= 3 && !tUp.hasEmergency && !tDn.hasEmergency) {
                    System.out.println("[CentralCoordinationAgent] >>> CONGESTIONAMENTO DETETADO no Corredor Horizontal L" 
                            + (r + 1) + " EW (" + corridorEW + " viaturas). A programar ONDA VERDE...");
                    coordenarOndaVerdeHorizontal(upIdx, dnIdx, 1);
                    return;
                }
            }
        }

        // 2. Deteção de Congestionamento em Corredores Verticais (Eixo NS = 0)
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows - 1; r++) {
                int upIdx = topo.getIntersectionIndex(r, c);
                int dnIdx = topo.getIntersectionIndex(r + 1, c);
                IntersectionTelemetry tUp = telemetryMap.get(upIdx);
                IntersectionTelemetry tDn = telemetryMap.get(dnIdx);
                int corridorNS = tUp.waitingNS + tDn.waitingNS;
                if (corridorNS >= 3 && !tUp.hasEmergency && !tDn.hasEmergency) {
                    System.out.println("[CentralCoordinationAgent] >>> CONGESTIONAMENTO DETETADO no Corredor Vertical C" 
                            + (c + 1) + " NS (" + corridorNS + " viaturas). A programar ONDA VERDE...");
                    coordenarOndaVerdeVertical(upIdx, dnIdx, 0);
                    return;
                }
            }
        }

        // 3. Deteção de Assimetria Local (Equilíbrio Adaptativo por Cruzamento)
        for (int i = 0; i < n; i++) {
            IntersectionTelemetry tel = telemetryMap.get(i);
            if (tel.hasEmergency) continue;

            if (tel.waitingNS >= 3 && tel.waitingEW <= 1) {
                emitirPoliticaEquilibrio(i, 0, 6000);
            } else if (tel.waitingEW >= 3 && tel.waitingNS <= 1) {
                emitirPoliticaEquilibrio(i, 1, 6000);
            }
        }
    }

    public synchronized List<String> coordenarOndaVerdeCorredor(environment.CorridorDefinition corridor) {
        List<String> issuedCmds = new ArrayList<>();
        if (corridor == null || corridor.getNodeCount() < 2) return issuedCmds;

        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        double speedMult = 1.0;
        try {
            speedMult = environment.WeatherEnvironmentManager.getInstance().getSnapshot().getSafeSpeedFactor();
        } catch (Throwable ignored) {}

        double progSpeedPxSec = environment.CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, speedMult);
        long refTime = environment.SimulationClock.currentTimeMillis();
        long currentOffsetMs = 0L;
        long duration = 6500L;
        long seqBase = commandSeq.incrementAndGet();

        List<Integer> nodes = corridor.getIntersectionIndices();
        for (int i = 0; i < nodes.size(); i++) {
            int nodeIdx = nodes.get(i);
            String cmdId = "CMD_GW_" + corridor.getId() + "_N" + (i + 1) + "_" + seqBase;
            enviarComandoCoordenação(nodeIdx, corridor.getAxis(), cmdId, "GREEN_WAVE",
                                     duration, currentOffsetMs, refTime, corridor.getId());
            issuedCmds.add(cmdId);

            if (i < nodes.size() - 1) {
                double segDistPx = corridor.getSegmentDistancePx(i, topo);
                long segTravelTimeMs = environment.CorridorDefinition.calculateTravelTimeOffsetMs(segDistPx, progSpeedPxSec);
                currentOffsetMs += segTravelTimeMs;
            }
        }

        return issuedCmds;
    }

    public synchronized void coordenarOndaVerdeHorizontal(int upstreamIdx, int downstreamIdx, int axis) {
        long seq = commandSeq.incrementAndGet();
        String cmdUp = "CMD_GW_H_UP_" + seq;
        String cmdDown = "CMD_GW_H_DN_" + seq;
        long duration = 6500;
        
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        double x1 = topo.getIntersectionCenterX(upstreamIdx);
        double y1 = topo.getIntersectionCenterY(upstreamIdx);
        double x2 = topo.getIntersectionCenterX(downstreamIdx);
        double y2 = topo.getIntersectionCenterY(downstreamIdx);
        double distPx = Math.hypot(x2 - x1, y2 - y1);
        double speedMult = 1.0;
        try {
            speedMult = environment.WeatherEnvironmentManager.getInstance().getSnapshot().getSafeSpeedFactor();
        } catch (Throwable ignored) {}
        double progSpeed = environment.CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, speedMult);
        long travelTimeOffsetMs = environment.CorridorDefinition.calculateTravelTimeOffsetMs(distPx, progSpeed);
        long refTime = environment.SimulationClock.currentTimeMillis();

        enviarComandoCoordenação(upstreamIdx, axis, cmdUp, "GREEN_WAVE", duration, 0, refTime, "CORRIDOR_H");
        enviarComandoCoordenação(downstreamIdx, axis, cmdDown, "GREEN_WAVE", duration, travelTimeOffsetMs, refTime, "CORRIDOR_H");
    }

    public synchronized void coordenarOndaVerdeVertical(int upstreamIdx, int downstreamIdx, int axis) {
        long seq = commandSeq.incrementAndGet();
        String cmdUp = "CMD_GW_V_UP_" + seq;
        String cmdDown = "CMD_GW_V_DN_" + seq;
        long duration = 6500;
        
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        double x1 = topo.getIntersectionCenterX(upstreamIdx);
        double y1 = topo.getIntersectionCenterY(upstreamIdx);
        double x2 = topo.getIntersectionCenterX(downstreamIdx);
        double y2 = topo.getIntersectionCenterY(downstreamIdx);
        double distPx = Math.hypot(x2 - x1, y2 - y1);
        double speedMult = 1.0;
        try {
            speedMult = environment.WeatherEnvironmentManager.getInstance().getSnapshot().getSafeSpeedFactor();
        } catch (Throwable ignored) {}
        double progSpeed = environment.CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, speedMult);
        long travelTimeOffsetMs = environment.CorridorDefinition.calculateTravelTimeOffsetMs(distPx, progSpeed);
        long refTime = environment.SimulationClock.currentTimeMillis();

        enviarComandoCoordenação(upstreamIdx, axis, cmdUp, "GREEN_WAVE", duration, 0, refTime, "CORRIDOR_V");
        enviarComandoCoordenação(downstreamIdx, axis, cmdDown, "GREEN_WAVE", duration, travelTimeOffsetMs, refTime, "CORRIDOR_V");
    }

    public synchronized void emitirPoliticaEquilibrio(int intersectionIdx, int targetAxis, long durationMs) {
        long seq = commandSeq.incrementAndGet();
        String cmdId = "CMD_BAL_" + seq;
        enviarComandoCoordenação(intersectionIdx, targetAxis, cmdId, "BALANCED", durationMs, 0);
    }

    public synchronized void enviarComandoCoordenação(int intersectionIdx, int targetAxis, 
                                                      String cmdId, String policyType, 
                                                      long plannedDurationMs, long offsetMs) {
        enviarComandoCoordenação(intersectionIdx, targetAxis, cmdId, policyType, plannedDurationMs, offsetMs,
                                 environment.SimulationClock.currentTimeMillis(), "CORRIDOR_GENERAL");
    }

    public synchronized void enviarComandoCoordenação(int intersectionIdx, int targetAxis, 
                                                      String cmdId, String policyType, 
                                                      long plannedDurationMs, long offsetMs,
                                                      long referenceTimeMillis, String corridorId) {
        CoordinatedCommandRecord record = new CoordinatedCommandRecord(cmdId, policyType, intersectionIdx, 
                                                                       targetAxis, plannedDurationMs, offsetMs,
                                                                       referenceTimeMillis, corridorId);
        commandRecords.put(cmdId, record);
        commandHistory.add(cmdId);
        TrafficMetricsCollector.getInstance().recordCoordinationIssued(cmdId, intersectionIdx, policyType, plannedDurationMs);

        String crossingName = resolveCrossingName(intersectionIdx);
        String axisPrefix = (targetAxis == 0) ? "NS" : "EW";
        String targetAgentName = "Semaforo_" + axisPrefix + "_" + crossingName;

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(createAID(targetAgentName));

        request.setOntology("coordination-policy");
        request.setConversationId("coord-req-" + cmdId);

        long validUntil = environment.SimulationClock.currentTimeMillis() + 15000;
        request.setContent("POLICY;cmdId=" + cmdId 
                + ";policyType=" + policyType 
                + ";targetAxis=" + targetAxis 
                + ";plannedDuration=" + plannedDurationMs 
                + ";offsetMs=" + offsetMs 
                + ";refTime=" + referenceTimeMillis
                + ";corridorId=" + corridorId
                + ";validUntil=" + validUntil);

        System.out.println("[CentralCoordinationAgent] >>> A enviar REQUEST FIPA a " + targetAgentName 
                + ": " + request.getContent());

        safeSend(request);
    }

    private void safeSend(ACLMessage msg) {
        try {
            send(msg);
            TrafficMetricsCollector.getInstance().recordFipaMessage();
        } catch (Throwable ignored) {
        }
    }

    private AID createAID(String name) {
        try {
            return new AID(name, AID.ISLOCALNAME);
        } catch (Throwable t) {
            return new AID(name, AID.ISGUID);
        }
    }

    private void processarMensagemFIPA(ACLMessage msg) {
        String ontology = msg.getOntology();

        if ("traffic-status".equalsIgnoreCase(ontology)) {
            tratarMensagemStatus(msg);
            return;
        }

        if ("coordination-policy".equalsIgnoreCase(ontology)) {
            tratarMensagemCoordenacao(msg);
            return;
        }

        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[CentralCoordinationAgent] Alerta de perturbação externa recebido: " + msg.getContent());
        }

        if ("DISTURBANCE_ALERT".equalsIgnoreCase(ontology)) {
            tratarAlertaMLDisturbance(msg);
        }
    }

    private void tratarMensagemStatus(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);

        try {
            int idx = Integer.parseInt(params.getOrDefault("intersection", "-1"));
            int maxInter = NetworkTopologyConfig.getActive().getIntersectionCount();
            if (idx >= 0 && idx < maxInter) {
                IntersectionTelemetry tel = telemetryMap.computeIfAbsent(idx, IntersectionTelemetry::new);
                tel.waitingNS = Integer.parseInt(params.getOrDefault("waitingNS", "0"));
                tel.waitingEW = Integer.parseInt(params.getOrDefault("waitingEW", "0"));
                tel.currentPhase = params.getOrDefault("phase", "NS_GREEN");
                tel.currentGreenDuration = Long.parseLong(params.getOrDefault("greenDuration", "4000"));
                tel.hasEmergency = Boolean.parseBoolean(params.getOrDefault("emergency", "false"));
                tel.lastUpdatedMs = environment.SimulationClock.currentTimeMillis();
            }
        } catch (Exception e) {
            System.err.println("[CentralCoordinationAgent] Erro no parsing de status: " + e.getMessage());
        }
    }

    private void tratarMensagemCoordenacao(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);
        String cmdId = params.getOrDefault("cmdId", "UNKNOWN");
        CoordinatedCommandRecord record = commandRecords.get(cmdId);

        if (msg.getPerformative() == ACLMessage.AGREE) {
            if (record != null) {
                if (!"APPLIED".equals(record.status) && !record.wasApplied) {
                    record.status = "SCHEDULED";
                }
                System.out.println("[CentralCoordinationAgent] Confirmação AGREE/SCHEDULED recebida para " + cmdId 
                        + " de " + msg.getSender().getLocalName());
            }
        } else if (msg.getPerformative() == ACLMessage.REFUSE) {
            if (record != null) {
                record.refusalReason = params.getOrDefault("reason", "UNKNOWN");
                if (record.wasApplied || "APPLIED".equals(record.status)) {
                    record.status = "APPLIED_THEN_PREEMPTED";
                } else {
                    record.status = "REFUSED";
                }
                System.out.println("[CentralCoordinationAgent] Rejeição/Revogação recebida para " + cmdId 
                        + " de " + msg.getSender().getLocalName() + " (Motivo: " + record.refusalReason + ")");
            }
        } else if (msg.getPerformative() == ACLMessage.INFORM && content.startsWith("APPLIED")) {
            if (record != null) {
                record.status = "APPLIED";
                record.wasApplied = true;
                record.appliedTimestamp = environment.SimulationClock.currentTimeMillis();
                try {
                    record.temporalDeviationMs = Long.parseLong(params.getOrDefault("temporalDeviation", "0"));
                } catch (Exception ignored) {}
                lastAdherenceByIntersection.put(record.intersectionIndex, record.appliedTimestamp);
                System.out.println("[CentralCoordinationAgent] >>> SUCESSO: Diretiva " + cmdId 
                        + " EFETIVAMENTE APLICADA no Cruzamento C" + (record.intersectionIndex + 1)
                        + " (Desvio: " + record.temporalDeviationMs + "ms)!");
            }
        }
    }

    public Map<String, CoordinatedCommandRecord> getAllCommandRecords() {
        return new ConcurrentHashMap<>(commandRecords);
    }

    private Map<String, String> parseParameters(String content) {
        Map<String, String> map = new java.util.HashMap<>();
        if (content == null) return map;
        String[] parts = content.split(";");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

    private void tratarAlertaMLDisturbance(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);

        try {
            int intersection = Integer.parseInt(params.getOrDefault("intersection", "-1"));
            int maxIntersections = NetworkTopologyConfig.getActive().getIntersectionCount();
            if (intersection < 0 || intersection >= maxIntersections) {
                System.err.println("[CentralCoordinationAgent] Alerta ML rejeitado: cruzamento inválido (" + intersection + ")");
                ACLMessage refReply = msg.createReply();
                refReply.setPerformative(ACLMessage.REFUSE);
                refReply.setOntology("DISTURBANCE_ALERT");
                refReply.setContent("REFUSE;reason=INVALID_INTERSECTION;intersection=" + intersection);
                safeSend(refReply);
                return;
            }

            double probability = Double.parseDouble(params.getOrDefault("probability", "0.0"));
            if (probability < 0.0 || probability > 1.0) {
                System.err.println("[CentralCoordinationAgent] Alerta ML rejeitado: probabilidade fora dos limites [0, 1]: " + probability);
                ACLMessage refReply = msg.createReply();
                refReply.setPerformative(ACLMessage.REFUSE);
                refReply.setOntology("DISTURBANCE_ALERT");
                refReply.setContent("REFUSE;reason=INVALID_PROBABILITY;probability=" + probability);
                safeSend(refReply);
                return;
            }

            long validUntil = Long.parseLong(params.getOrDefault("validUntil", "0"));
            long now = environment.SimulationClock.currentTimeMillis();
            if (validUntil > 0 && validUntil < now) {
                System.err.println("[CentralCoordinationAgent] Alerta ML rejeitado: alerta expirado (validUntil=" + validUntil + " < now=" + now + ")");
                ACLMessage refReply = msg.createReply();
                refReply.setPerformative(ACLMessage.REFUSE);
                refReply.setOntology("DISTURBANCE_ALERT");
                refReply.setContent("REFUSE;reason=EXPIRED_ALERT;validUntil=" + validUntil);
                safeSend(refReply);
                return;
            }

            String alertId = params.getOrDefault("alertId", "ALERT_ML_" + now);
            double speedMult = Double.parseDouble(params.getOrDefault("speedMultiplier", "1.0"));
            String model = params.getOrDefault("model", "UNKNOWN");
            double threshold = Double.parseDouble(params.getOrDefault("threshold", "0.40"));
            double horizon = Double.parseDouble(params.getOrDefault("horizon", "60"));
            String riskType = params.getOrDefault("riskType", "CONGESTION_RISK");

            lastMLAlertId = alertId;
            lastMLAlertIntersection = intersection;
            lastMLAlertProbability = probability;
            lastMLAlertSpeedMultiplier = speedMult;
            lastMLAlertReceivedMs = now;
            mlAlertCount++;
            TrafficMetricsCollector.getInstance().recordMLAlert(alertId, intersection, probability, riskType);

            int probPercent = (int) Math.round(probability * 100);
            String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");
            String crossingName = resolveCrossingName(intersection);

            System.out.println("\n[CentralCoordinationAgent] >>> DISTURBANCE_ALERT ML RECEBIDO <<<");
            System.out.println("  • Alerta ID: " + alertId);
            System.out.println("  • Cruzamento: C" + (intersection + 1) + " (" + crossingName + ")");
            System.out.println("  • Probabilidade Cong.: " + probPercent + "% (" + nivel + ")");
            System.out.println("  • Tipo de Risco: " + riskType);
            System.out.println("  • Modelo: " + model + " (τ=" + threshold + ")");
            System.out.println("  • Horizonte: " + (int) horizon + "s");
            System.out.println("  • Multiplicador Vel.: " + (int)(speedMult * 100) + "%");
            System.out.println("  • Total de alertas ML recebidos: " + mlAlertCount + "\n");

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.AGREE);
            reply.setOntology("DISTURBANCE_ALERT");
            reply.setContent("AGREE;alertId=" + alertId + ";intersection=" + intersection + ";probability=" + probability);
            safeSend(reply);

            if (probability >= threshold) {
                IntersectionTelemetry tel = telemetryMap.get(intersection);
                if (tel != null && tel.hasEmergency) {
                    System.out.println("[CentralCoordinationAgent] Precedência de Emergência: Alerta preditivo em C" + 
                            (intersection + 1) + " registado mas diretiva preditiva suspensa por emergência ativa.");
                } else {
                    int targetAxis = params.containsKey("targetAxis") 
                            ? Integer.parseInt(params.get("targetAxis")) 
                            : ((tel != null && tel.waitingEW > tel.waitingNS) ? 1 : ((tel != null && tel.currentPhase != null && tel.currentPhase.contains("EW")) ? 1 : 0));
                    long duration = (probability >= 0.70) ? 6500 : 5000;
                    long seq = commandSeq.incrementAndGet();
                    String cmdId = "CMD_ML_ADAPT_" + seq;
                    System.out.println("[CentralCoordinationAgent] >>> A despachar Diretiva Adaptativa Preditiva: " + 
                            cmdId + " (Eixo " + (targetAxis == 0 ? "NS" : "EW") + ", " + duration + "ms) para Cruzamento C" + (intersection + 1));
                    enviarComandoCoordenação(intersection, targetAxis, cmdId, "BALANCED", duration, 0);
                    lastAdaptiveCommandId = cmdId;
                }
            }

        } catch (Exception e) {
            System.err.println("[CentralCoordinationAgent] Erro ao processar DISTURBANCE_ALERT: " + e.getMessage());
        }
    }

    public Map<Integer, IntersectionTelemetry> getTelemetryMap() {
        return telemetryMap;
    }

    public Map<String, CoordinatedCommandRecord> getCommandRecords() {
        return commandRecords;
    }

    public List<String> getCommandHistory() {
        return commandHistory;
    }

    public CoordinatedCommandRecord getLastCommandRecord() {
        if (commandHistory.isEmpty()) return null;
        return commandRecords.get(commandHistory.get(commandHistory.size() - 1));
    }

    public boolean hasIntersectionAdhered(int intersectionIndex) {
        return lastAdherenceByIntersection.containsKey(intersectionIndex);
    }

    public String getLastMLAlertId() {
        return lastMLAlertId;
    }

    public String getLastAdaptiveCommandId() {
        return lastAdaptiveCommandId;
    }

    public int getLastMLAlertIntersection() {
        return lastMLAlertIntersection;
    }

    public double getLastMLAlertProbability() {
        return lastMLAlertProbability;
    }

    public double getLastMLAlertSpeedMultiplier() {
        return lastMLAlertSpeedMultiplier;
    }

    public long getLastMLAlertReceivedMs() {
        return lastMLAlertReceivedMs;
    }

    public int getMLAlertCount() {
        return mlAlertCount;
    }

    public String getCommandStatus(String cmdId) {
        if (cmdId == null) return "UNKNOWN";
        CoordinatedCommandRecord r = commandRecords.get(cmdId);
        return (r != null) ? r.status : "NOT_FOUND";
    }

    public boolean isCommandApplied(String cmdId) {
        if (cmdId == null) return false;
        CoordinatedCommandRecord r = commandRecords.get(cmdId);
        return (r != null) && (r.wasApplied || "APPLIED".equals(r.status) || "APPLIED_THEN_PREEMPTED".equals(r.status));
    }

    public CoordinatedCommandRecord getCommandRecord(String cmdId) {
        return (cmdId != null) ? commandRecords.get(cmdId) : null;
    }

    public synchronized void triggerScheduledCoordination() {
        auditarRedeETomarDecisoes();
    }

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        if (activeInstance == this) {
            activeInstance = null;
        }
        try {
            DFService.deregister(this);
            System.out.println("[CentralCoordinationAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
