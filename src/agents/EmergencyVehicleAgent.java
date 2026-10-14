package agents;

import environment.CitySimulationGUI;
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

import java.awt.Color;

/**
 * Agente Veículo de Emergência / Prioritário (Feature 6 - Emergency Vehicle Priority).
 *
 * Modela viaturas de socorro médico (Ambulâncias INEM), bombeiros ou forças policiais em marcha de urgência:
 * - Possui prioridade máxima de circulação sobre o tráfego regular.
 * - Monitoriza a distância física até à linha de paragem do próximo cruzamento.
 * - Interage ativamente com os agentes semáforo via mensagens FIPA-ACL (REQUEST com ontologia "emergency-preemption").
 * - Força a preempção imediata da fase verde no seu corredor e o encerramento das vias transversais.
 * - Apresenta sinalização ótica de emergência (luzes estroboscópicas azuis e vermelhas no ecrã).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class EmergencyVehicleAgent extends Agent {

    private String road; // "NS" ou "EW"
    private int axis; // 0=NS, 1=EW
    private int lastPreemptedIntersection = -1;

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

        // 1. Registo nas Páginas Amarelas (DF)
        registarNoDF();

        // 2. Registo visual no ambiente (Viatura de emergência branca/estroboscópica)
        int visualDir = (axis == 0) ? 0 : 2; // 0: Norte->Sul, 2: Oeste->Este
        CitySimulationGUI.getInstance().addVehicle(getLocalName(), visualDir, Color.WHITE);

        System.out.println("[EmergencyVehicleAgent] Viatura prioritária " + getLocalName() 
                + " entrou em marcha de urgência no corredor " + road + ".");

        // 3. Comportamento Periódico: Deteção de aproximação a cruzamentos e envio de preempção FIPA
        addBehaviour(new TickerBehaviour(this, 300) {
            @Override
            protected void onTick() {
                verificarAproximacaoECruzar();
            }
        });

        // 4. Comportamento Cíclico: Receção de confirmações FIPA AGREE / INFORM
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage reply = receive();
                if (reply != null) {
                    System.out.println("[EmergencyVehicleAgent - " + getLocalName() + "] Confirmação de preempção de " 
                            + reply.getSender().getLocalName() + ": " + reply.getContent());
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista a viatura de emergência no Directory Facilitator do JADE.
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
            System.err.println("[EmergencyVehicleAgent] Erro ao registar viatura no DF: " + fe.getMessage());
        }
    }

    /**
     * Inspeciona a cinemática da viatura no simulador. Ao aproximar-se da Stop Line,
     * emite um pedido FIPA de preempção ao semáforo competente.
     */
    private void verificarAproximacaoECruzar() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
        if (v == null) return;

        int interIdx = gui.getTargetIntersection(v);
        double dist = gui.getDistanceToTargetStopLine(v);

        // Se estiver a menos de 240px do cruzamento e ainda não tiver solicitado preempção para este cruzamento
        if (dist <= 240.0 && interIdx != lastPreemptedIntersection) {
            lastPreemptedIntersection = interIdx;

            String targetSemName = "Semaforo_" + (axis == 0 ? "NS" : "EW") + "_" + CROSSING_NAMES[interIdx];

            System.out.println("[EmergencyVehicleAgent - " + getLocalName() 
                    + "] !!! ALERTA DE PREEMPÇÃO !!! A requerer corredor verde imediato a " 
                    + targetSemName + " (Distância: " + (int)dist + "px)");

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
            request.setOntology("emergency-preemption");
            request.setConversationId("emerg-preempt-" + System.currentTimeMillis());
            request.setContent("PREEMPCAO_SOCORRO: Viatura " + getLocalName() 
                    + " a aproximar-se do Cruzamento " + CROSSING_NAMES[interIdx] 
                    + ". Exige sinal VERDE imediato!");

            send(request);

            // Garante aplicação direta na fachada do ambiente para redundância e segurança
            TrafficEnvironment.getInstance().triggerEmergencyPreemption(interIdx, axis);
        } else if (dist > 300.0) {
            // Já transitou pelo cruzamento; rearma para o próximo cruzamento do circuito
            lastPreemptedIntersection = -1;
        }
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
