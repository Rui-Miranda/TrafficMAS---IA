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
        public String status; // "SENT", "AGREED", "REFUSED", "APPLIED"
        public String refusalReason = null;
        public long appliedTimestamp = 0;

        public CoordinatedCommandRecord(String cmdId, String policyType, int intersectionIndex, 
                                      int targetAxis, long plannedDurationMs, long offsetMs) {
            this.cmdId = cmdId;
            this.policyType = policyType;
            this.intersectionIndex = intersectionIndex;
            this.targetAxis = targetAxis;
            this.plannedDurationMs = plannedDurationMs;
            this.offsetMs = offsetMs;
            this.issuedTimestamp = System.currentTimeMillis();
            this.status = "SENT";
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
            tel.lastUpdatedMs = System.currentTimeMillis();
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

        String crossingName = CROSSINGS[intersectionIdx];
        String axisPrefix = (targetAxis == 0) ? "NS" : "EW";
        String targetAgentName = "Semaforo_" + axisPrefix + "_" + crossingName;

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID(targetAgentName, AID.ISLOCALNAME));
        // Envia também ao agente do outro eixo do mesmo cruzamento para sincronização da autoridade
        String otherAgentName = "Semaforo_" + ((targetAxis == 0) ? "EW" : "NS") + "_" + crossingName;
        request.addReceiver(new AID(otherAgentName, AID.ISLOCALNAME));

        request.setOntology("coordination-policy");
        request.setConversationId("coord-req-" + cmdId);

        long validUntil = System.currentTimeMillis() + 10000; // Validade de 10 segundos
        request.setContent("POLICY;cmdId=" + cmdId 
                + ";policyType=" + policyType 
                + ";targetAxis=" + targetAxis 
                + ";plannedDuration=" + plannedDurationMs 
                + ";offsetMs=" + offsetMs 
                + ";validUntil=" + validUntil);

        System.out.println("[CentralCoordinationAgent] >>> A enviar REQUEST FIPA a " + targetAgentName 
                + ": " + request.getContent());

        send(request);
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

        // 3. Alertas de perturbações externas
        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[CentralCoordinationAgent] Alerta de perturbação externa recebido: " + msg.getContent());
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
                tel.lastUpdatedMs = System.currentTimeMillis();
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
                record.status = "AGREED";
                System.out.println("[CentralCoordinationAgent] Confirmação AGREE recebida para " + cmdId 
                        + " de " + msg.getSender().getLocalName());
            }
        } else if (msg.getPerformative() == ACLMessage.REFUSE) {
            if (record != null) {
                record.status = "REFUSED";
                record.refusalReason = params.getOrDefault("reason", "UNKNOWN");
                System.out.println("[CentralCoordinationAgent] Rejeição REFUSE recebida para " + cmdId 
                        + " de " + msg.getSender().getLocalName() + " (Motivo: " + record.refusalReason + ")");
            }
        } else if (msg.getPerformative() == ACLMessage.INFORM && content.startsWith("APPLIED")) {
            if (record != null) {
                record.status = "APPLIED";
                record.appliedTimestamp = System.currentTimeMillis();
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

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[CentralCoordinationAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
