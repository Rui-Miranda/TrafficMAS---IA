package agents;

import environment.CitySimulationGUI;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.EmergencyRequest;
import environment.TrafficEnvironment;
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

/**
 * Agente Semáforo Inteligente e Adaptativo com Segurança de Fases (Fase 1 / Fase 6.1).
 *
 * Características arquiteturais:
 * 1. Suporta 8 agentes semáforo em modo 2×2 (4 cruzamentos) e 18 agentes semáforo em modo 3×3 (9 cruzamentos).
 * 2. Delega a transição física das fases na {@link IntersectionSafetyAuthority},
 *    assegurando UMA ÚNICA AUTORIDADE de decisão por cruzamento.
 * 3. Garante o invariante: NUNCA existem verdes simultâneos em eixos incompatíveis.
 * 4. Transições obrigatórias: Verde -> Amarelo (1500ms) -> All-Red (1000ms) -> Novo Verde.
 * 5. Comunicação FIPA-ACL em três etapas distintas:
 *    - RECEBIDO (CONFIRM)
 *    - ACEITE (AGREE)
 *    - VERDE_CONCEDIDO (INFORM)
 * 6. Suporte a desimpedimento de emergência ("emergency-cleared") e restauro adaptativo.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficLightAgent extends Agent {

    private String direction;
    private int intersectionIndex = 0; // 0..3 em 2x2, 0..8 em 3x3
    private int axis = 0; // 0=Norte-Sul, 1=Este-Oeste

    private IntersectionSafetyAuthority authority;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.direction = (String) args[0];
            if (args.length > 1 && args[1] instanceof Integer) {
                this.intersectionIndex = (Integer) args[1];
            }
            this.axis = direction.toLowerCase().contains("este") || direction.toLowerCase().contains("ew") ? 1 : 0;
        } else {
            this.direction = "Desconhecida";
        }

        // Regista no gestor de ciclo de vida da frota/infraestrutura
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "TrafficLightAgent");

        // 1. Obter a autoridade única do cruzamento
        this.authority = IntersectionSafetyAuthority.getAuthority(intersectionIndex);

        // 2. Registo no Directory Facilitator (DFService - Páginas Amarelas)
        registarNoDF();

        // 3. Registo de listener na autoridade para envio de confirmações FIPA quando o verde for efetivamente concedido
        configurarListenerAutoridade();

        System.out.println("[TrafficLightAgent] Semáforo " + direction + " (Cruzamento C" + (intersectionIndex + 1) 
                + " | Eixo " + (axis == 0 ? "NS" : "EW") + ") operacional sob controlo da Autoridade de Segurança.");

        // 4. Condução do relógio da máquina de estados: o agente do eixo NS (axis == 0) gere o tick periódico do cruzamento
        if (axis == 0) {
            addBehaviour(new TickerBehaviour(this, 100) {
                @Override
                protected void onTick() {
                    if (environment.SimulationClock.isSimulatedMode()) {
                        return;
                    }
                    TrafficEnvironment env = TrafficEnvironment.getInstance();
                    int waitingNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
                    int waitingEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
                    authority.updateTick(waitingNS, waitingEW);
                }
            });

            // Reporte Periódico de Telemetria ao Agente Coordenador Central (Fase 3 - Protocolo FIPA)
            addBehaviour(new TickerBehaviour(this, 2500) {
                @Override
                protected void onTick() {
                    enviarEstadoAoCoordenador();
                }
            });
        }

        // 5. Comportamento Cíclico: Receção e tratamento de mensagens FIPA-ACL
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                    processarMensagemFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    private void enviarEstadoAoCoordenador() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        int waitingNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
        int waitingEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
        boolean hasEmerg = env.hasEmergencyApproaching(intersectionIndex, 0) || env.hasEmergencyApproaching(intersectionIndex, 1);

        ACLMessage statusMsg = new ACLMessage(ACLMessage.INFORM);
        statusMsg.addReceiver(new AID("AgenteCoordenadorCentral", AID.ISLOCALNAME));
        statusMsg.setOntology("traffic-status");
        statusMsg.setConversationId("status-c" + (intersectionIndex + 1));
        statusMsg.setContent("STATUS;intersection=" + intersectionIndex 
                + ";waitingNS=" + waitingNS 
                + ";waitingEW=" + waitingEW 
                + ";phase=" + authority.getCurrentPhase().name() 
                + ";greenDuration=" + authority.getCurrentGreenDuration() 
                + ";emergency=" + hasEmerg 
                + ";timestamp=" + System.currentTimeMillis());

        send(statusMsg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();
    }

    private void configurarListenerAutoridade() {
        authority.addPhaseListener(new IntersectionSafetyAuthority.EmergencyPhaseListener() {
            @Override
            public void onRequestReceived(EmergencyRequest req) {
            }

            @Override
            public void onRequestAccepted(EmergencyRequest req) {
            }

            @Override
            public void onGreenGranted(EmergencyRequest req) {
                if (req.axis == axis && req.senderAID != null) {
                    System.out.println("[TrafficLightAgent " + direction + "] >>> VERDE CONCEDIDO EFETIVADO no Cruzamento C" 
                            + (intersectionIndex + 1) + ". A notificar viatura " + req.vehicleId + "...");

                    ACLMessage grantedMsg = new ACLMessage(ACLMessage.INFORM);
                    grantedMsg.addReceiver(new AID(req.senderAID, AID.ISLOCALNAME));
                    grantedMsg.setOntology("emergency-preemption");
                    grantedMsg.setConversationId("emerg-granted-" + req.vehicleId);
                    grantedMsg.setContent("VERDE_CONCEDIDO: Cruzamento C" + (intersectionIndex + 1) 
                            + " com passagem segura garantida no Eixo " + (axis == 0 ? "NS" : "EW") 
                            + ". Eixo transversal bloqueado em VERMELHO.");

                    send(grantedMsg);
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                }
            }

            @Override
            public void onEmergencyCleared(EmergencyRequest req) {
                System.out.println("[TrafficLightAgent " + direction + "] Notificação de desimpedimento recebida para " 
                        + req.vehicleId + " no Cruzamento C" + (intersectionIndex + 1));
            }

            @Override
            public void onCoordinationApplied(IntersectionSafetyAuthority.CoordinationPolicy policy) {
                if (policy.targetAxis != axis) {
                    return;
                }

                System.out.println("[TrafficLightAgent " + direction + "] >>> Diretiva " + policy.cmdId 
                        + " APLICADA no Cruzamento C" + (intersectionIndex + 1) + ". A notificar Coordenador Central...");

                ACLMessage appliedMsg = new ACLMessage(ACLMessage.INFORM);
                appliedMsg.addReceiver(new AID("AgenteCoordenadorCentral", AID.ISLOCALNAME));
                appliedMsg.setOntology("coordination-policy");
                appliedMsg.setConversationId("coord-applied-" + policy.cmdId);
                appliedMsg.setContent("APPLIED;cmdId=" + policy.cmdId 
                        + ";intersection=" + intersectionIndex 
                        + ";axis=" + policy.targetAxis 
                        + ";duration=" + policy.plannedDurationMs 
                        + ";policyType=" + policy.policyType 
                        + ";corridorId=" + policy.getCorridorId()
                        + ";schedStart=" + policy.getScheduledStartMillis()
                        + ";appliedTime=" + policy.getAppliedTimeMillis()
                        + ";temporalDeviation=" + policy.getTemporalDeviationMs()
                        + ";timestamp=" + System.currentTimeMillis());

                send(appliedMsg);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }

            @Override
            public void onCoordinationRejected(IntersectionSafetyAuthority.CoordinationPolicy policy, String reason) {
                if (policy.targetAxis != axis) {
                    return;
                }

                System.out.println("[TrafficLightAgent " + direction + "] Diretiva " + policy.cmdId 
                        + " REJEITADA/ABORTADA no Cruzamento C" + (intersectionIndex + 1) + ". Motivo: " + reason);

                ACLMessage rejMsg = new ACLMessage(ACLMessage.REFUSE);
                rejMsg.addReceiver(new AID("AgenteCoordenadorCentral", AID.ISLOCALNAME));
                rejMsg.setOntology("coordination-policy");
                rejMsg.setConversationId("coord-rejected-" + policy.cmdId);
                rejMsg.setContent("REJECTED;cmdId=" + policy.cmdId 
                        + ";intersection=" + intersectionIndex 
                        + ";reason=" + reason 
                        + ";timestamp=" + System.currentTimeMillis());

                send(rejMsg);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }

            @Override
            public void onManualControlStateChanged(IntersectionSafetyAuthority.ManualOverrideRequest req) {
                if (req == null || req.targetAxis != axis) {
                    return;
                }
                System.out.println("[TrafficLightAgent " + direction + " C" + (intersectionIndex + 1)
                        + "] Controlo Manual " + req.requestId + " -> Estado=" + req.getState()
                        + " (" + req.getStatusReason() + ")");
            }

            @Override
            public void onManualControlReturnedToAuto(int interIdx, String reason) {
                if (axis == 0) {
                    System.out.println("[TrafficLightAgent " + direction + " C" + (interIdx + 1)
                            + "] Regresso ao controlo automático sincronizado (" + reason + ").");
                }
            }
        });
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("traffic-light-service");
        sd.setName("Semaforo-" + direction + "-C" + (intersectionIndex + 1));
        sd.addProperties(new Property("intersection", intersectionIndex));
        sd.addProperties(new Property("axis", axis));

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[TrafficLightAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    private void processarMensagemFIPA(ACLMessage msg) {
        String ontology = msg.getOntology();

        if ("emergency-preemption".equalsIgnoreCase(ontology)) {
            String senderName = msg.getSender().getLocalName();
            System.out.println("[TrafficLightAgent " + direction + "] Mensagem de SOCORRO recebida de " 
                    + senderName + ": " + msg.getContent());

            String cat = "AMBULANCE";
            int urg = 3;
            String missionId = null;
            if (msg.getContent() != null) {
                String c = msg.getContent();
                if (c.contains("Cat=")) {
                    try {
                        int idx = c.indexOf("Cat=") + 4;
                        int end = c.indexOf(",", idx);
                        if (end < 0) end = c.indexOf(")", idx);
                        if (end > idx) cat = c.substring(idx, end).trim();
                    } catch (Throwable ignored) {}
                }
                if (c.contains("Urg=")) {
                    try {
                        int idx = c.indexOf("Urg=") + 4;
                        int end = c.indexOf(")", idx);
                        if (end < 0) end = c.indexOf(",", idx);
                        if (end > idx) urg = Integer.parseInt(c.substring(idx, end).trim());
                    } catch (Throwable ignored) {}
                }
                if (c.contains("Mis=")) {
                    try {
                        int idx = c.indexOf("Mis=") + 4;
                        int end = c.indexOf(";", idx);
                        if (end < 0) end = c.indexOf(")", idx);
                        if (end > idx) missionId = c.substring(idx, end).trim();
                    } catch (Throwable ignored) {}
                }
            }

            authority.requestEmergency(senderName, axis, senderName, cat, urg, missionId, null);

            ACLMessage confirm = msg.createReply();
            confirm.setPerformative(ACLMessage.CONFIRM);
            confirm.setOntology("emergency-preemption");
            confirm.setContent("RECEBIDO: Pedido de socorro de " + senderName + " registado na fila do Cruzamento C" + (intersectionIndex + 1));
            send(confirm);
            TrafficMetricsCollector.getInstance().recordFipaMessage();

            ACLMessage agree = msg.createReply();
            agree.setPerformative(ACLMessage.AGREE);
            agree.setOntology("emergency-preemption");
            agree.setContent("ACEITE: Pedido em processamento prioritario. Transicao segura em curso.");
            send(agree);
            TrafficMetricsCollector.getInstance().recordFipaMessage();

            if (authority.getCurrentPhase().activeGreenAxis == axis) {
                ACLMessage granted = msg.createReply();
                granted.setPerformative(ACLMessage.INFORM);
                granted.setOntology("emergency-preemption");
                granted.setContent("VERDE_CONCEDIDO: Fase VERDE ja ativa no Eixo " + (axis == 0 ? "NS" : "EW") + ". Passagem autorizada.");
                send(granted);
                TrafficMetricsCollector.getInstance().recordFipaMessage();
            }
            return;
        }

        if ("emergency-cleared".equalsIgnoreCase(ontology)) {
            String senderName = msg.getSender().getLocalName();
            System.out.println("[TrafficLightAgent " + direction + "] Viatura " + senderName + " notificou desimpedimento do Cruzamento C" + (intersectionIndex + 1));
            authority.notifyEmergencyCleared(senderName);
            return;
        }

        if ("traffic-management".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Pedido de passagem regular recebido de " 
                    + msg.getSender().getLocalName());

            if (authority.getCurrentPhase().activeGreenAxis != axis && authority.getCurrentGreenDuration() > 2000) {
                authority.setCurrentGreenDuration(Math.max(1500, authority.getCurrentGreenDuration() - 1500));
            }

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology("traffic-management");
            reply.setContent("PEDIDO_REGISTADO: Semáforo " + direction + " considerou o atraso na otimização de fase.");
            send(reply);
            TrafficMetricsCollector.getInstance().recordFipaMessage();
            return;
        }

        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Alerta de perturbação recebido: " + msg.getContent());
            authority.setYellowDuration(2000);
            authority.setAllRedDuration(1500);
            return;
        }

        if ("coordination-policy".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Diretiva de coordenação recebida de " 
                    + msg.getSender().getLocalName() + ": " + msg.getContent());

            if (msg.getPerformative() == ACLMessage.REQUEST) {
                String content = msg.getContent();
                java.util.Map<String, String> params = parseParameters(content);

                String cmdId = params.getOrDefault("cmdId", "CMD_UNKNOWN");
                String policyType = params.getOrDefault("policyType", "BALANCED");
                int targetAxis = Integer.parseInt(params.getOrDefault("targetAxis", String.valueOf(axis)));
                long plannedDuration = Long.parseLong(params.getOrDefault("plannedDuration", "5000"));
                long offsetMs = Long.parseLong(params.getOrDefault("offsetMs", "0"));
                long refTime = Long.parseLong(params.getOrDefault("refTime", String.valueOf(environment.SimulationClock.currentTimeMillis())));
                String corridorId = params.getOrDefault("corridorId", "CORRIDOR_GENERAL");
                long validUntil = Long.parseLong(params.getOrDefault("validUntil", String.valueOf(System.currentTimeMillis() + 10000)));

                IntersectionSafetyAuthority.CoordinationPolicy policy =
                        new IntersectionSafetyAuthority.CoordinationPolicy(cmdId, policyType, targetAxis, plannedDuration, offsetMs, refTime, corridorId, validUntil);

                String evalResult = authority.evaluateAndRegisterCoordinationPolicy(policy);

                if ("ACCEPTED".equals(evalResult)) {
                    ACLMessage agree = msg.createReply();
                    agree.setPerformative(ACLMessage.AGREE);
                    agree.setOntology("coordination-policy");
                    agree.setConversationId("coord-agree-" + cmdId);
                    agree.setContent("AGREE;cmdId=" + cmdId + ";intersection=" + intersectionIndex + ";status=SCHEDULED;schedStart=" + policy.getScheduledStartMillis());
                    send(agree);
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                } else {
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setOntology("coordination-policy");
                    refuse.setConversationId("coord-refuse-" + cmdId);
                    refuse.setContent("REFUSE;cmdId=" + cmdId + ";intersection=" + intersectionIndex + ";reason=" + evalResult);
                    send(refuse);
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                }
            }
        }
    }

    private java.util.Map<String, String> parseParameters(String content) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
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

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        try {
            DFService.deregister(this);
            System.out.println("[TrafficLightAgent] Semáforo " + direction + " desregistado do DF.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
