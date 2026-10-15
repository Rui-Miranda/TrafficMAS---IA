package agents;

import environment.CitySimulationGUI;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.awt.Color;

/**
 * Agente Veículo de Emergência / Prioritário com Negociação FIPA Segura (Fase 1).
 *
 * Características essenciais:
 * 1. NUNCA muta diretamente os semáforos nem o ambiente; atua estritamente via FIPA-ACL.
 * 2. Máquina de estados interna de travessia:
 *    - APROXIMACAO
 *    - PEDIDO_ENVIADO (REQUEST)
 *    - AGUARDA_VERDE (RECEBIDO / ACEITE)
 *    - PASSAGEM_SEGURA (VERDE_CONCEDIDO)
 *    - DESIMPEDIDO (INFORM com ontologia "emergency-cleared")
 * 3. A viatura não avança às cegas se o sinal transversal ainda estiver a desimpedir;
 *    só transpõe o cruzamento à velocidade máxima após confirmação de VERDE_CONCEDIDO.
 * 4. Ao transpor o cruzamento, notifica o semáforo para libertação imediata e restauro normal.
 * 5. Idempotência / Anti-debouncing: evita envios desnecessários de pedidos repetidos.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class EmergencyVehicleAgent extends Agent {

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
    private int currentTargetIntersection = -1;
    private VehicleCrossingState crossingState = VehicleCrossingState.APROXIMACAO;
    private long lastRequestSentMillis = 0;

    private static final String[] CROSSING_NAMES = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.road = (String) args[0];
        } else {
            this.road = "NS";
        }
        this.axis = road.equalsIgnoreCase("NS") ? 0 : 1;

        // 1. Registo no Directory Facilitator
        registarNoDF();

        // 2. Registo visual no simulador com luzes de emergência
        int visualDir = (axis == 0) ? 0 : 2; // 0: Norte->Sul, 2: Oeste->Este
        CitySimulationGUI.getInstance().addVehicle(getLocalName(), visualDir, Color.WHITE);

        System.out.println("[EmergencyVehicleAgent] Viatura prioritária " + getLocalName() 
                + " pronta em marcha de urgência no corredor " + road + ".");

        // 3. Comportamento Periódico: Monitorização de distância e emissão FIPA
        addBehaviour(new TickerBehaviour(this, 250) {
            @Override
            protected void onTick() {
                gerirNavegacaoECruzamento();
            }
        });

        // 4. Comportamento Cíclico: Receção de confirmações FIPA (CONFIRM, AGREE, INFORM)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    processarRespostaFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista a viatura nas Páginas Amarelas do JADE.
     */
    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("emergency-vehicle-service");
        sd.setName("Emergencia-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[EmergencyVehicleAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    /**
     * Inspeciona a posição no simulador e gere os pedidos de preempção.
     */
    private void gerirNavegacaoECruzamento() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) return;

        CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
        if (v == null) return;

        int interIdx = gui.getTargetIntersection(v);
        double dist = gui.getDistanceToTargetStopLine(v);
        long now = System.currentTimeMillis();

        // A. Aproximação a um novo cruzamento (< 240px)
        if (dist <= 240.0 && (currentTargetIntersection != interIdx || crossingState == VehicleCrossingState.APROXIMACAO)) {
            currentTargetIntersection = interIdx;
            crossingState = VehicleCrossingState.PEDIDO_ENVIADO;
            lastRequestSentMillis = now;

            String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + CROSSING_NAMES[interIdx];

            System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                    + "] A enviar REQUEST de preempção a " + targetSemName 
                    + " (Distância: " + (int)dist + "px)");

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
            request.setOntology("emergency-preemption");
            request.setConversationId("emerg-req-" + getLocalName() + "-" + interIdx);
            request.setContent("PREEMPCAO_SOCORRO: Viatura " + getLocalName() 
                    + " em missao urgente no Cruzamento " + CROSSING_NAMES[interIdx] 
                    + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");

            send(request);
            crossingState = VehicleCrossingState.AGUARDA_VERDE;
            return;
        }

        // B. Reenvio preventivo em caso de timeout de comunicação (> 3s sem resposta)
        if (crossingState == VehicleCrossingState.AGUARDA_VERDE && (now - lastRequestSentMillis > 3000) && dist <= 240.0) {
            lastRequestSentMillis = now;
            String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + CROSSING_NAMES[interIdx];
            ACLMessage retry = new ACLMessage(ACLMessage.REQUEST);
            retry.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
            retry.setOntology("emergency-preemption");
            retry.setConversationId("emerg-retry-" + getLocalName() + "-" + interIdx);
            retry.setContent("RETRY_PREEMPCAO: Viatura " + getLocalName() + " aguarda concessao de verde.");
            send(retry);
        }

        // C. Deteção de desimpedimento após transpor o cruzamento
        if ((crossingState == VehicleCrossingState.PASSAGEM_SEGURA || crossingState == VehicleCrossingState.ATRAVESSANDO) && dist > 300.0) {
            notificarDesimpedimento(interIdx);
            crossingState = VehicleCrossingState.APROXIMACAO;
            currentTargetIntersection = -1;
        }
    }

    /**
     * Processa as respostas e confirmações FIPA-ACL dos semáforos.
     */
    private void processarRespostaFIPA(ACLMessage msg) {
        String content = msg.getContent();

        // 1. Confirmação de Pedido Recebido
        if (msg.getPerformative() == ACLMessage.CONFIRM || (content != null && content.startsWith("RECEBIDO"))) {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Confirmação 1/3 (RECEBIDO) de " 
                    + msg.getSender().getLocalName());
            return;
        }

        // 2. Confirmação de Pedido Aceite em Processamento
        if (msg.getPerformative() == ACLMessage.AGREE || (content != null && content.startsWith("ACEITE"))) {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Confirmação 2/3 (ACEITE) de " 
                    + msg.getSender().getLocalName() + ". Transição de segurança em curso.");
            return;
        }

        // 3. Confirmação de Verde Efetivamente Concedido (Passagem Segura)
        if (content != null && content.contains("VERDE_CONCEDIDO")) {
            System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] >>> Confirmação 3/3 (VERDE CONCEDIDO)! " 
                    + "Passagem segura garantida pelo semáforo " + msg.getSender().getLocalName());
            this.crossingState = VehicleCrossingState.PASSAGEM_SEGURA;
        }
    }

    /**
     * Emite uma mensagem formal FIPA INFORM de desimpedimento do cruzamento.
     */
    private void notificarDesimpedimento(int interIdx) {
        if (interIdx < 0 || interIdx >= CROSSING_NAMES.length) return;

        String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + CROSSING_NAMES[interIdx];
        System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                + "] Cruzamento " + CROSSING_NAMES[interIdx] + " desimpedido. A emitir FIPA INFORM...");

        ACLMessage clearedMsg = new ACLMessage(ACLMessage.INFORM);
        clearedMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        clearedMsg.setOntology("emergency-cleared");
        clearedMsg.setConversationId("emerg-cleared-" + getLocalName() + "-" + interIdx);
        clearedMsg.setContent("DESIMPEDIDO: Viatura " + getLocalName() + " concluiu a travessia de " + CROSSING_NAMES[interIdx]);

        send(clearedMsg);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[EmergencyVehicleAgent] Viatura " + getLocalName() + " desregistada do DF.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
