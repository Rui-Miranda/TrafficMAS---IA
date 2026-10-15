package agents;

import environment.CitySimulationGUI;
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

/**
 * Agente Semáforo Inteligente e Adaptativo com Segurança de Fases (Fase 1).
 *
 * Características arquiteturais:
 * 1. Preserva os 8 agentes semáforo (2 por cruzamento: NS e EW).
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
    private int intersectionIndex = 0; // 0=NW, 1=NE, 2=SW, 3=SE
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
                    processarMensagemFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Envia o estado operacional e métricas de tráfego do cruzamento ao Coordenador Central.
     */
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
    }

    /**
     * Configura os callbacks da autoridade de segurança para despachar mensagens FIPA-ACL.
     */
    private void configurarListenerAutoridade() {
        // Apenas o agente cujo eixo corresponde à emergência (ou o NS) responde
        authority.setPhaseListener(new IntersectionSafetyAuthority.EmergencyPhaseListener() {
            @Override
            public void onRequestReceived(EmergencyRequest req) {
                // A receção é confirmada diretamente no processamento da mensagem
            }

            @Override
            public void onRequestAccepted(EmergencyRequest req) {
                // A aceitação é confirmada diretamente no processamento da mensagem
            }

            @Override
            public void onGreenGranted(EmergencyRequest req) {
                // Quando o semáforo atinge EFETIVAMENTE o estado VERDE seguro para este eixo
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
                }
            }

            @Override
            public void onEmergencyCleared(EmergencyRequest req) {
                System.out.println("[TrafficLightAgent " + direction + "] Notificação de desimpedimento recebida para " 
                        + req.vehicleId + " no Cruzamento C" + (intersectionIndex + 1));
            }

            @Override
            public void onCoordinationApplied(IntersectionSafetyAuthority.CoordinationPolicy policy) {
                // Notifica o coordenador central de que a diretiva foi efetivamente executada
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
                        + ";timestamp=" + System.currentTimeMillis());

                send(appliedMsg);
            }

            @Override
            public void onCoordinationRejected(IntersectionSafetyAuthority.CoordinationPolicy policy, String reason) {
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
            }
        });
    }

    /**
     * Regista o semáforo nas Páginas Amarelas do JADE.
     */
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

    /**
     * Processa mensagens FIPA-ACL com distinção rigorosa de confirmações.
     */
    private void processarMensagemFIPA(ACLMessage msg) {
        String ontology = msg.getOntology();

        // 1. Pedido de Preempção de Emergência (Feature 6)
        if ("emergency-preemption".equalsIgnoreCase(ontology)) {
            String senderName = msg.getSender().getLocalName();
            System.out.println("[TrafficLightAgent " + direction + "] Mensagem de SOCORRO recebida de " 
                    + senderName + ": " + msg.getContent());

            // Submete o pedido à Autoridade Única do Cruzamento
            EmergencyRequest req = authority.requestEmergency(senderName, axis, senderName);

            // Confirmação 1: PEDIDO RECEBIDO (CONFIRM)
            ACLMessage confirm = msg.createReply();
            confirm.setPerformative(ACLMessage.CONFIRM);
            confirm.setOntology("emergency-preemption");
            confirm.setContent("RECEBIDO: Pedido de socorro de " + senderName + " registado na fila do Cruzamento C" + (intersectionIndex + 1));
            send(confirm);

            // Confirmação 2: PEDIDO ACEITE (AGREE)
            ACLMessage agree = msg.createReply();
            agree.setPerformative(ACLMessage.AGREE);
            agree.setOntology("emergency-preemption");
            agree.setContent("ACEITE: Pedido em processamento prioritario. Transicao segura em curso.");
            send(agree);

            // Se a autoridade já estiver na fase VERDE deste eixo, a confirmação 3 é emitida pelo callback
            if (authority.getCurrentPhase().activeGreenAxis == axis) {
                ACLMessage granted = msg.createReply();
                granted.setPerformative(ACLMessage.INFORM);
                granted.setOntology("emergency-preemption");
                granted.setContent("VERDE_CONCEDIDO: Fase VERDE ja ativa no Eixo " + (axis == 0 ? "NS" : "EW") + ". Passagem autorizada.");
                send(granted);
            }
            return;
        }

        // 2. Notificação de Desimpedimento do Cruzamento pela Viatura de Emergência
        if ("emergency-cleared".equalsIgnoreCase(ontology)) {
            String senderName = msg.getSender().getLocalName();
            System.out.println("[TrafficLightAgent " + direction + "] Viatura " + senderName + " notificou desimpedimento do Cruzamento C" + (intersectionIndex + 1));
            authority.notifyEmergencyCleared(senderName);
            return;
        }

        // 3. Pedido de Viatura Regular com Espera Excessiva (Feature 3)
        if ("traffic-management".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Pedido de passagem regular recebido de " 
                    + msg.getSender().getLocalName());

            // Se o eixo oposto estiver verde, acelera a sua comutação para amarelo
            if (authority.getCurrentPhase().activeGreenAxis != axis && authority.getCurrentGreenDuration() > 2000) {
                authority.setCurrentGreenDuration(Math.max(1500, authority.getCurrentGreenDuration() - 1500));
            }

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology("traffic-management");
            reply.setContent("PEDIDO_REGISTADO: Semáforo " + direction + " considerou o atraso na otimização de fase.");
            send(reply);
            return;
        }

        // 4. Alerta Preditivo de Perturbação / Meteorologia (Feature 5)
        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Alerta de perturbação recebido: " + msg.getContent());
            // Aumenta tempos de segurança (amarelo e all-red) durante condições adversas
            authority.setYellowDuration(2000);
            authority.setAllRedDuration(1500);
            return;
        }

        // 5. Diretiva de Coordenação Global / Onda Verde (Fase 3 - Feature 4)
        if ("coordination-policy".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Diretiva de coordenação recebida de " 
                    + msg.getSender().getLocalName() + ": " + msg.getContent());

            if (msg.getPerformative() == ACLMessage.REQUEST) {
                // Parser do comando: POLICY;cmdId=...;policyType=...;targetAxis=...;plannedDuration=...;offsetMs=...;validUntil=...
                String content = msg.getContent();
                java.util.Map<String, String> params = parseParameters(content);

                String cmdId = params.getOrDefault("cmdId", "CMD_UNKNOWN");
                String policyType = params.getOrDefault("policyType", "BALANCED");
                int targetAxis = Integer.parseInt(params.getOrDefault("targetAxis", String.valueOf(axis)));
                long plannedDuration = Long.parseLong(params.getOrDefault("plannedDuration", "5000"));
                long offsetMs = Long.parseLong(params.getOrDefault("offsetMs", "0"));
                long validUntil = Long.parseLong(params.getOrDefault("validUntil", String.valueOf(System.currentTimeMillis() + 10000)));

                IntersectionSafetyAuthority.CoordinationPolicy policy =
                        new IntersectionSafetyAuthority.CoordinationPolicy(cmdId, policyType, targetAxis, plannedDuration, offsetMs, validUntil);

                String evalResult = authority.evaluateAndRegisterCoordinationPolicy(policy);

                if ("ACCEPTED".equals(evalResult)) {
                    // Resposta FIPA: AGREE
                    ACLMessage agree = msg.createReply();
                    agree.setPerformative(ACLMessage.AGREE);
                    agree.setOntology("coordination-policy");
                    agree.setConversationId("coord-agree-" + cmdId);
                    agree.setContent("AGREE;cmdId=" + cmdId + ";intersection=" + intersectionIndex + ";status=ACCEPTED");
                    send(agree);
                } else {
                    // Resposta FIPA: REFUSE
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setOntology("coordination-policy");
                    refuse.setConversationId("coord-refuse-" + cmdId);
                    refuse.setContent("REFUSE;cmdId=" + cmdId + ";intersection=" + intersectionIndex + ";reason=" + evalResult);
                    send(refuse);
                }
            }
        }
    }

    /**
     * Utilitário para parsing de parâmetros chave-valor em formato FIPA textual (ex: "cmdId=123;axis=0").
     */
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
        try {
            DFService.deregister(this);
            System.out.println("[TrafficLightAgent] Semáforo " + direction + " desregistado do DF.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}