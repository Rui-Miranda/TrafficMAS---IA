package agents;

import environment.IntersectionSafetyAuthority;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agente Coordenador Central da Rede Semafórica (Fase 3 - Coordenação Multiagente Real).
 *
 * Responsabilidades:
 * 1. Recolha periódica de telemetria dos 4 cruzamentos (via FIPA-ACL e sensores).
 * 2. Manutenção do estado global da malha urbana (filas, tempos, emergências).
 * 3. Identificação de estrangulamentos e corredores congestionados.
 * 4. Determinação de políticas de coordenação:
 *    - ONDA VERDE: sincronismo e desfasamento (offset temporal) entre nós consecutivos.
 *    - EQUILÍBRIO ADAPTATIVO: redistribuição de tempos de verde em nós assimétricos.
 * 5. Emissão de diretivas FIPA-ACL (REQUEST) aos agentes semáforo.
 * 6. Controlo de ciclo de vida FIPA: confirmação (AGREE/REFUSE) e confirmação de aplicação (INFORM: APPLIED).
 * 7. Histórico e auditoria de comandos emitidos e aplicados.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class CentralCoordinationAgent extends Agent {

    public static final String[] CROSSINGS = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    /** Telemetria em tempo real de um cruzamento */
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

    /** Registo de comando emitido pelo coordenador */
    public static class CoordinatedCommandRecord {
        public final String cmdId;
        public final String policyType;
        public final int intersectionIndex;
        public final int targetAxis;
        public final long plannedDurationMs;
        public final long offsetMs;
        public final long issuedTimestamp;
        public String status; // "SENT", "AGREED", "REFUSED", "APPLIED", "APPLIED_THEN_PREEMPTED"
        public String refusalReason = null;
        public long appliedTimestamp = 0;
        public boolean wasApplied = false;

        public CoordinatedCommandRecord(String cmdId, String policyType, int intersectionIndex, 
                                      int targetAxis, long plannedDurationMs, long offsetMs) {
            this.cmdId = cmdId;
            this.policyType = policyType;
            this.intersectionIndex = intersectionIndex;
            this.targetAxis = targetAxis;
            this.plannedDurationMs = plannedDurationMs;
            this.offsetMs = offsetMs;
            this.issuedTimestamp = environment.SimulationClock.currentTimeMillis();
            this.status = "SENT";
            this.wasApplied = false;
        }
    }

    // Telemetria agregada dos 4 cruzamentos (0 a 3)
    private final Map<Integer, IntersectionTelemetry> telemetryMap = new ConcurrentHashMap<>();

    // Registo de comandos e auditoria
    private final Map<String, CoordinatedCommandRecord> commandRecords = new ConcurrentHashMap<>();
    private final List<String> commandHistory = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong commandSeq = new AtomicLong(1000);

    // Cruzamentos que já aderiram com sucesso a políticas
    private final Map<Integer, Long> lastAdherenceByIntersection = new ConcurrentHashMap<>();

    // ======================== ESTADO DE PERTURBAÇÕES ML (Fase 4 - Etapa 4) ========================
    /** Último alerta DISTURBANCE_ALERT recebido do DisruptionManagementAgent (ML) */
    private volatile String lastMLAlertId = null;
    private volatile int lastMLAlertIntersection = -1;
    private volatile double lastMLAlertProbability = -1.0;
    private volatile double lastMLAlertSpeedMultiplier = 1.0;
    private volatile long lastMLAlertReceivedMs = 0;
    private volatile int mlAlertCount = 0;
    private volatile String lastAdaptiveCommandId = null;

    // Instância única partilhada para facilitar consulta em suites de testes
    private static volatile CentralCoordinationAgent activeInstance = null;

    public static CentralCoordinationAgent getActiveInstance() {
        return activeInstance;
    }

    @Override
    protected void setup() {
        activeInstance = this;

        // Inicializa estrutura de telemetria para os 4 cruzamentos
        for (int i = 0; i < 4; i++) {
            telemetryMap.put(i, new IntersectionTelemetry(i));
        }

        // 1. Registo no Directory Facilitator (DFService)
        registarNoDF();

        System.out.println("[CentralCoordinationAgent] Agente Coordenador Central online na malha urbana (4 cruzamentos).");

        // 2. Comportamento Periódico: Auditoria e Tomada de Decisão Macro (a cada 3.5 segundos)
        addBehaviour(new TickerBehaviour(this, 3500) {
            @Override
            protected void onTick() {
                auditarRedeETomarDecisoes();
            }
        });

        // 3. Comportamento Cíclico: Receção e tratamento de comunicações FIPA-ACL
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    processarMensagemFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista o coordenador nas Páginas Amarelas do JADE.
     */
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

    /**
     * Inspeciona a rede viária e decide políticas macro de tráfego.
     */
    public synchronized void auditarRedeETomarDecisoes() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();

        // Atualiza telemetria sensorial caso algum semáforo ainda não tenha reportado via FIPA
        for (int i = 0; i < 4; i++) {
            IntersectionTelemetry tel = telemetryMap.get(i);
            int waitingNS = env.getWaitingVehiclesCount(i, 0);
            int waitingEW = env.getWaitingVehiclesCount(i, 1);
            boolean hasEmerg = env.hasEmergencyApproaching(i, 0) || env.hasEmergencyApproaching(i, 1);

            tel.waitingNS = waitingNS;
            tel.waitingEW = waitingEW;
            tel.hasEmergency = hasEmerg;
            tel.lastUpdatedMs = environment.SimulationClock.currentTimeMillis();
        }

        // 1. Deteção de Congestionamento em Corredores (Onda Verde)
        // Corredor Horizontal Norte: C1_NW (0) <--> C2_NE (1) no eixo Este-Oeste (axis 1)
        int corridorNorthEW = telemetryMap.get(0).waitingEW + telemetryMap.get(1).waitingEW;
        if (corridorNorthEW >= 3 && !telemetryMap.get(0).hasEmergency && !telemetryMap.get(1).hasEmergency) {
            System.out.println("[CentralCoordinationAgent] >>> CONGESTIONAMENTO DETETADO no Corredor Norte EW (" 
                    + corridorNorthEW + " viaturas). A programar ONDA VERDE...");
            coordenarOndaVerdeHorizontal(0, 1, 1);
            return;
        }

        // Corredor Horizontal Sul: C3_SW (2) <--> C4_SE (3) no eixo Este-Oeste (axis 1)
        int corridorSouthEW = telemetryMap.get(2).waitingEW + telemetryMap.get(3).waitingEW;
        if (corridorSouthEW >= 3 && !telemetryMap.get(2).hasEmergency && !telemetryMap.get(3).hasEmergency) {
            System.out.println("[CentralCoordinationAgent] >>> CONGESTIONAMENTO DETETADO no Corredor Sul EW (" 
                    + corridorSouthEW + " viaturas). A programar ONDA VERDE...");
            coordenarOndaVerdeHorizontal(2, 3, 1);
            return;
        }

        // Corredor Vertical Oeste: C1_NW (0) <--> C3_SW (2) no eixo Norte-Sul (axis 0)
        int corridorWestNS = telemetryMap.get(0).waitingNS + telemetryMap.get(2).waitingNS;
        if (corridorWestNS >= 3 && !telemetryMap.get(0).hasEmergency && !telemetryMap.get(2).hasEmergency) {
            System.out.println("[CentralCoordinationAgent] >>> CONGESTIONAMENTO DETETADO no Corredor Oeste NS (" 
                    + corridorWestNS + " viaturas). A programar ONDA VERDE...");
            coordenarOndaVerdeVertical(0, 2, 0);
            return;
        }

        // 2. Deteção de Assimetria Local (Equilíbrio Adaptativo por Cruzamento)
        for (int i = 0; i < 4; i++) {
            IntersectionTelemetry tel = telemetryMap.get(i);
            if (tel.hasEmergency) continue; // Emergência tem prioridade estrita, não interfere

            if (tel.waitingNS >= 3 && tel.waitingEW <= 1) {
                emitirPoliticaEquilibrio(i, 0, 6000); // Prioriza NS com 6s de verde
            } else if (tel.waitingEW >= 3 && tel.waitingNS <= 1) {
                emitirPoliticaEquilibrio(i, 1, 6000); // Prioriza EW com 6s de verde
            }
        }
    }

    /**
     * Coordena uma Onda Verde entre dois cruzamentos horizontais adjacentes.
     *
     * Distância física: 360 px (ROAD_X2 - ROAD_X1 = 640 - 280).
     * Velocidade média de tráfego: ~75 px/s -> Tempo de viagem estimado: ~4800 ms.
     */
    public synchronized void coordenarOndaVerdeHorizontal(int upstreamIdx, int downstreamIdx, int axis) {
        long seq = commandSeq.incrementAndGet();
        String cmdUp = "CMD_GW_H_UP_" + seq;
        String cmdDown = "CMD_GW_H_DN_" + seq;
        long duration = 6500;
        long travelTimeOffsetMs = 4500; // Desfasamento para permitir a chegada do pelotão

        // 1. Comando a montante (offset = 0)
        enviarComandoCoordenação(upstreamIdx, axis, cmdUp, "GREEN_WAVE", duration, 0);

        // 2. Comando a jusante (offset = 4500ms)
        enviarComandoCoordenação(downstreamIdx, axis, cmdDown, "GREEN_WAVE", duration, travelTimeOffsetMs);
    }

    /**
     * Coordena uma Onda Verde entre dois cruzamentos verticais adjacentes.
     */
    public synchronized void coordenarOndaVerdeVertical(int upstreamIdx, int downstreamIdx, int axis) {
        long seq = commandSeq.incrementAndGet();
        String cmdUp = "CMD_GW_V_UP_" + seq;
        String cmdDown = "CMD_GW_V_DN_" + seq;
        long duration = 6500;
        long travelTimeOffsetMs = 4500;

        enviarComandoCoordenação(upstreamIdx, axis, cmdUp, "GREEN_WAVE", duration, 0);
        enviarComandoCoordenação(downstreamIdx, axis, cmdDown, "GREEN_WAVE", duration, travelTimeOffsetMs);
    }

    /**
     * Emite diretiva de equilíbrio adaptativo para um cruzamento específico.
     */
    public synchronized void emitirPoliticaEquilibrio(int intersectionIdx, int targetAxis, long durationMs) {
        long seq = commandSeq.incrementAndGet();
        String cmdId = "CMD_BAL_" + seq;
        enviarComandoCoordenação(intersectionIdx, targetAxis, cmdId, "BALANCED", durationMs, 0);
    }

    /**
     * Despacha uma mensagem FIPA-ACL REQUEST com a diretiva de coordenação para os semáforos competentes.
     */
    public synchronized void enviarComandoCoordenação(int intersectionIdx, int targetAxis, 
                                                      String cmdId, String policyType, 
                                                      long plannedDurationMs, long offsetMs) {
        // Regista o comando na auditoria
        CoordinatedCommandRecord record = new CoordinatedCommandRecord(cmdId, policyType, intersectionIdx, 
                                                                       targetAxis, plannedDurationMs, offsetMs);
        commandRecords.put(cmdId, record);
        commandHistory.add(cmdId);
        metrics.TrafficMetricsCollector.getInstance().recordCoordinationIssued(cmdId, intersectionIdx, policyType, plannedDurationMs);

        String crossingName = CROSSINGS[intersectionIdx];
        String axisPrefix = (targetAxis == 0) ? "NS" : "EW";
        String targetAgentName = "Semaforo_" + axisPrefix + "_" + crossingName;

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(createAID(targetAgentName));

        request.setOntology("coordination-policy");
        request.setConversationId("coord-req-" + cmdId);

        long validUntil = environment.SimulationClock.currentTimeMillis() + 10000; // Validade de 10 segundos
        request.setContent("POLICY;cmdId=" + cmdId 
                + ";policyType=" + policyType 
                + ";targetAxis=" + targetAxis 
                + ";plannedDuration=" + plannedDurationMs 
                + ";offsetMs=" + offsetMs 
                + ";validUntil=" + validUntil);

        System.out.println("[CentralCoordinationAgent] >>> A enviar REQUEST FIPA a " + targetAgentName 
                + ": " + request.getContent());

        safeSend(request);
    }

    /**
     * Envia mensagem de forma segura tolerando testes unitários sem container JADE ativo.
     */
    private void safeSend(ACLMessage msg) {
        try {
            send(msg);
        } catch (Throwable ignored) {
            // Em testes unitários isolados onde o agente não está anexado a um container JADE ativo
        }
    }

    /**
     * Cria um AID com tolerância a execuções fora de container JADE ativo.
     */
    private AID createAID(String name) {
        try {
            return new AID(name, AID.ISLOCALNAME);
        } catch (Throwable t) {
            return new AID(name, AID.ISGUID);
        }
    }

    /**
     * Processa mensagens FIPA-ACL recebidas dos agentes semáforo.
     */
    private void processarMensagemFIPA(ACLMessage msg) {
        String ontology = msg.getOntology();

        // 1. Telemetria periódica dos semáforos (INFORM)
        if ("traffic-status".equalsIgnoreCase(ontology)) {
            tratarMensagemStatus(msg);
            return;
        }

        // 2. Confirmações e Aplicações de Diretivas de Coordenação
        if ("coordination-policy".equalsIgnoreCase(ontology)) {
            tratarMensagemCoordenacao(msg);
            return;
        }

        // 3. Alertas de perturbações externas (legado)
        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[CentralCoordinationAgent] Alerta de perturbação externa recebido: " + msg.getContent());
        }

        // 4. Alertas de perturbações baseadas em ML (Fase 4 - Etapa 4: DISTURBANCE_ALERT)
        if ("DISTURBANCE_ALERT".equalsIgnoreCase(ontology)) {
            tratarAlertaMLDisturbance(msg);
        }
    }

    /**
     * Trata o reporte de estado recebido de um cruzamento.
     */
    private void tratarMensagemStatus(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);

        try {
            int idx = Integer.parseInt(params.getOrDefault("intersection", "-1"));
            if (idx >= 0 && idx < 4) {
                IntersectionTelemetry tel = telemetryMap.get(idx);
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

    /**
     * Trata confirmações (AGREE / REFUSE) e notificações de aplicação (INFORM: APPLIED).
     */
    private void tratarMensagemCoordenacao(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);
        String cmdId = params.getOrDefault("cmdId", "UNKNOWN");
        CoordinatedCommandRecord record = commandRecords.get(cmdId);

        if (msg.getPerformative() == ACLMessage.AGREE) {
            if (record != null) {
                if (!"APPLIED".equals(record.status) && !record.wasApplied) {
                    record.status = "AGREED";
                }
                System.out.println("[CentralCoordinationAgent] Confirmação AGREE recebida para " + cmdId 
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
                lastAdherenceByIntersection.put(record.intersectionIndex, record.appliedTimestamp);
                System.out.println("[CentralCoordinationAgent] >>> SUCESSO: Diretiva " + cmdId 
                        + " EFETIVAMENTE APLICADA no Cruzamento C" + (record.intersectionIndex + 1) + "!");
            }
        }
    }

    /**
     * Parser utilitário de parâmetros no formato chave=valor separados por ponto-e-vírgula.
     */
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

    /**
     * Trata alertas DISTURBANCE_ALERT emitidos pelo DisruptionManagementAgent com modelo ML.
     *
     * Conteúdo esperado (chave=valor; separado por ponto-e-vírgula):
     *   type=ML_PREDICTION;intersection=<idx>;probability=<p>;predicted=1;
     *   speedMultiplier=<mult>;horizon=<horizon_s>;model=<name>;threshold=<tau>
     *
     * Ações:
     * 1. Parsing e registo do alerta.
     * 2. Log estruturado com cruzamento, probabilidade e ações recomendadas.
     * 3. Resposta AGREE confirmando receção.
     */
    /**
     * Trata alertas DISTURBANCE_ALERT emitidos pelo DisruptionManagementAgent com modelo ML.
     *
     * Validações obrigatórias (Secção 5 e 7):
     * 1. Cruzamento válido no intervalo [0, 3].
     * 2. Probabilidade válida no intervalo [0.0, 1.0].
     * 3. Alerta não expirado (validUntil >= agora).
     * 4. Precedência de emergências sobre coordenação preditiva (Secção 8).
     * 5. Resposta AGREE com confirmação ou REFUSE com motivo em caso de rejeição.
     * 6. Despacho de diretiva de coordenação adaptativa quando P >= threshold.
     */
    private void tratarAlertaMLDisturbance(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> params = parseParameters(content);

        try {
            int intersection = Integer.parseInt(params.getOrDefault("intersection", "-1"));
            // 1. Validação do Cruzamento
            if (intersection < 0 || intersection >= 4) {
                System.err.println("[CentralCoordinationAgent] Alerta ML rejeitado: cruzamento inválido (" + intersection + ")");
                ACLMessage refReply = msg.createReply();
                refReply.setPerformative(ACLMessage.REFUSE);
                refReply.setOntology("DISTURBANCE_ALERT");
                refReply.setContent("REFUSE;reason=INVALID_INTERSECTION;intersection=" + intersection);
                safeSend(refReply);
                return;
            }

            // 2. Validação da Probabilidade
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

            // 3. Validação de Expiração
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

            // Atualiza estado interno de telemetria ML
            lastMLAlertId = alertId;
            lastMLAlertIntersection = intersection;
            lastMLAlertProbability = probability;
            lastMLAlertSpeedMultiplier = speedMult;
            lastMLAlertReceivedMs = now;
            mlAlertCount++;
            metrics.TrafficMetricsCollector.getInstance().recordMLAlert(alertId, intersection, probability, riskType);

            int probPercent = (int) Math.round(probability * 100);
            String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");
            String crossingName = CROSSINGS[intersection];

            System.out.println("\n[CentralCoordinationAgent] >>> DISTURBANCE_ALERT ML RECEBIDO <<<");
            System.out.println("  • Alerta ID: " + alertId);
            System.out.println("  • Cruzamento: C" + (intersection + 1) + " (" + crossingName + ")");
            System.out.println("  • Probabilidade Cong.: " + probPercent + "% (" + nivel + ")");
            System.out.println("  • Tipo de Risco: " + riskType);
            System.out.println("  • Modelo: " + model + " (τ=" + threshold + ")");
            System.out.println("  • Horizonte: " + (int) horizon + "s");
            System.out.println("  • Multiplicador Vel.: " + (int)(speedMult * 100) + "%");
            System.out.println("  • Total de alertas ML recebidos: " + mlAlertCount + "\n");

            // Confirmação de receção com AGREE
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.AGREE);
            reply.setOntology("DISTURBANCE_ALERT");
            reply.setContent("AGREE;alertId=" + alertId + ";intersection=" + intersection + ";probability=" + probability);
            safeSend(reply);

            // Resposta de coordenação adaptativa preditiva (Secção 7 e 8)
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

    /* =========================================================================
     * MÉTODOS DE CONSULTA E AUDITORIA PARA VALIDAÇÃO E SUITES DE TESTES
     * ========================================================================= */

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

    // ======================== CONSULTAS ML (Fase 4 - Etapa 4) ========================

    /** Devolve o ID do último alerta ML recebido. */
    public String getLastMLAlertId() {
        return lastMLAlertId;
    }

    /** Devolve o ID da última diretiva adaptativa preditiva emitida pelo coordenador. */
    public String getLastAdaptiveCommandId() {
        return lastAdaptiveCommandId;
    }

    /** Devolve o índice do cruzamento do último alerta ML recebido. */
    public int getLastMLAlertIntersection() {
        return lastMLAlertIntersection;
    }

    /** Devolve a probabilidade do último alerta ML recebido. */
    public double getLastMLAlertProbability() {
        return lastMLAlertProbability;
    }

    /** Devolve o multiplicador de velocidade do último alerta ML recebido. */
    public double getLastMLAlertSpeedMultiplier() {
        return lastMLAlertSpeedMultiplier;
    }

    /** Devolve o timestamp de receção do último alerta ML. */
    public long getLastMLAlertReceivedMs() {
        return lastMLAlertReceivedMs;
    }

    /** Devolve o total de alertas ML recebidos. */
    public int getMLAlertCount() {
        return mlAlertCount;
    }

    /** Devolve o estado de execução de um comando coordenado pelo seu ID. */
    public String getCommandStatus(String cmdId) {
        if (cmdId == null) return "UNKNOWN";
        CoordinatedCommandRecord r = commandRecords.get(cmdId);
        return (r != null) ? r.status : "NOT_FOUND";
    }

    /** Devolve se o comando coordenado foi efetivamente aplicado no cruzamento. */
    public boolean isCommandApplied(String cmdId) {
        if (cmdId == null) return false;
        CoordinatedCommandRecord r = commandRecords.get(cmdId);
        return (r != null) && (r.wasApplied || "APPLIED".equals(r.status) || "APPLIED_THEN_PREEMPTED".equals(r.status));
    }

    /** Devolve o registo completo de auditoria de um comando pelo seu ID. */
    public CoordinatedCommandRecord getCommandRecord(String cmdId) {
        return (cmdId != null) ? commandRecords.get(cmdId) : null;
    }

    /**
     * Dispara um ciclo determinístico de coordenação macro sincronizado com o relógio simulado (Fase 5 - Etapa 2).
     */
    public synchronized void triggerScheduledCoordination() {
        auditarRedeETomarDecisoes();
    }

    @Override
    protected void takeDown() {
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
