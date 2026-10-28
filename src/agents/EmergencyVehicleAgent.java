package agents;

import environment.CitySimulationGUI;
import environment.FleetLifecycleManager;
import environment.NetworkTopologyConfig;
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

import java.awt.Color;

/**
 * Agente Veículo de Emergência / Prioritário com Negociação FIPA Segura (Fase 1 / Fase 6.1).
 *
 * Características essenciais:
 * 1. NUNCA muta diretamente os semáforos nem o ambiente; atua estritamente via FIPA-ACL.
 * 2. Excluído do limite de 50 veículos normais (Requisito R3).
 * 3. Distingue categoria da viatura (ex: AMBULANCE, FIRE_TRUCK, POLICE), nível de urgência
 *    e estado ativo da missão sem impor hierarquia fixa entre categorias (Requisitos R3 e R10).
 * 4. Suporta todos os 9 cruzamentos da malha 3×3 (e 4 cruzamentos em modo 2×2).
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
    private int visualDirection = 0;
    private String category = "AMBULANCE";
    private int urgencyLevel = 3;
    private boolean activeMission = true;

    private int currentTargetIntersection = -1;
    private VehicleCrossingState crossingState = VehicleCrossingState.APROXIMACAO;
    private long lastRequestSentMillis = 0;

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
            this.category = args[2].toString();
        } else {
            String lower = getLocalName().toLowerCase();
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

        // 1. Registo no Gestor de Ciclo de Vida da Frota
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "EmergencyVehicleAgent");

        // 2. Registo no Directory Facilitator
        registarNoDF();

        // 3. Registo visual no simulador (se ainda não foi inserido pelo FleetLifecycleManager)
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
                + ") pronta no corredor " + road + ".");

        // 4. Comportamento Periódico: Monitorização de distância e emissão FIPA
        addBehaviour(new TickerBehaviour(this, 250) {
            @Override
            protected void onTick() {
                gerirNavegacaoECruzamento();
            }
        });

        // 5. Comportamento Cíclico: Receção de confirmações FIPA (CONFIRM, AGREE, INFORM)
        addBehaviour(new CyclicBehaviour(this) {
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

    public boolean isActiveMission() {
        return activeMission;
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

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[EmergencyVehicleAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    private void gerirNavegacaoECruzamento() {
        if (!activeMission) return;

        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) return;

        CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
        if (v == null) return;

        this.axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;

        NetworkTopologyConfig topo = gui.getTopology();
        int interIdx = gui.getTargetIntersection(v);
        if (interIdx < 0 || interIdx >= topo.getIntersectionCount()) return;

        String crossingName = topo.getIntersectionName(interIdx);
        double dist = gui.getDistanceToTargetStopLine(v);
        long now = System.currentTimeMillis();

        if (dist >= 0 && dist <= 240.0 && (currentTargetIntersection != interIdx || crossingState == VehicleCrossingState.APROXIMACAO)) {
            if (currentTargetIntersection >= 0 && currentTargetIntersection != interIdx) {
                notificarDesimpedimento(currentTargetIntersection);
            }
            currentTargetIntersection = interIdx;
            crossingState = VehicleCrossingState.PEDIDO_ENVIADO;
            lastRequestSentMillis = now;

            String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;

            System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                    + "] A enviar REQUEST de preempção a " + targetSemName 
                    + " (Distância: " + (int)dist + "px)");

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
            request.setOntology("emergency-preemption");
            request.setConversationId("emerg-req-" + getLocalName() + "-" + interIdx);
            request.setContent("PREEMPCAO_SOCORRO: Viatura " + getLocalName() 
                    + " (Cat=" + category + ", Urg=" + urgencyLevel + ") em missao urgente no Cruzamento " + crossingName 
                    + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");

            send(request);
            TrafficMetricsCollector.getInstance().recordFipaMessage();
            crossingState = VehicleCrossingState.AGUARDA_VERDE;
            return;
        }

        if (crossingState == VehicleCrossingState.AGUARDA_VERDE && (now - lastRequestSentMillis > 3000) && dist >= 0 && dist <= 240.0) {
            lastRequestSentMillis = now;
            String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + crossingName;
            ACLMessage retry = new ACLMessage(ACLMessage.REQUEST);
            retry.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
            retry.setOntology("emergency-preemption");
            retry.setConversationId("emerg-retry-" + getLocalName() + "-" + interIdx);
            retry.setContent("RETRY_PREEMPCAO: Viatura " + getLocalName() + " aguarda concessao de verde.");
            send(retry);
            TrafficMetricsCollector.getInstance().recordFipaMessage();
        }

        if ((crossingState == VehicleCrossingState.PASSAGEM_SEGURA || crossingState == VehicleCrossingState.ATRAVESSANDO)
                && (dist > 260.0 || currentTargetIntersection != interIdx)) {
            notificarDesimpedimento(currentTargetIntersection >= 0 ? currentTargetIntersection : interIdx);
            crossingState = VehicleCrossingState.APROXIMACAO;
            currentTargetIntersection = -1;
        }
    }

    private void processarRespostaFIPA(ACLMessage msg) {
        String content = msg.getContent();

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
    }

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
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
