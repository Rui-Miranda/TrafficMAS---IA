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

/**
 * Agente Veículo Regular Autónomo (Feature 3 - Vehicle Agents).
 *
 * Simula uma viatura convencional em circulação na malha urbana:
 * - Perceciona a sinalização semafórica e reage à dinâmica de tráfego.
 * - Monitoriza o tempo de espera acumulado em filas de retenção.
 * - Envia mensagens FIPA-ACL (REQUEST) quando o tempo de imobilização ultrapassa o limiar de tolerância.
 * - Regista a sua presença no Directory Facilitator (DF) do JADE.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class VehicleAgent extends Agent {

    private String road; // "NS" ou "EW"
    private long lastRequestSentMillis = 0;
    private static final double LIMIAR_ESPERA_SEGUNDOS = 6.0;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.road = (String) args[0];
        } else {
            this.road = "NS";
        }

        // 1. Registo no Directory Facilitator
        registarNoDF();

        // 2. Integração visual na cidade
        int visualDir = road.equalsIgnoreCase("NS") ? 0 : 2; // 0: Norte->Sul, 2: Oeste->Este
        java.awt.Color carColor = new java.awt.Color(30, 160, 230);
        CitySimulationGUI.getInstance().addVehicle(getLocalName(), visualDir, carColor);

        System.out.println("[VehicleAgent] Viatura regular " + getLocalName() + " inserida na via " + road + ".");

        // 3. Comportamento Periódico: Monitorização de tempo de espera e emissão de REQUEST condicional
        addBehaviour(new TickerBehaviour(this, 1500) {
            @Override
            protected void onTick() {
                verificarTempoDeEspera();
            }
        });

        // 4. Comportamento Cíclico: Escuta e tratamento de respostas FIPA dos semáforos
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage reply = receive();
                if (reply != null) {
                    System.out.println("[VehicleAgent - " + getLocalName() + "] Resposta FIPA recebida de " 
                            + reply.getSender().getLocalName() + " (" + ACLMessage.getPerformative(reply.getPerformative()) + "): " 
                            + reply.getContent());
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista a viatura no serviço de páginas amarelas do JADE.
     */
    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("vehicle-service");
        sd.setName("Viatura-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[VehicleAgent] Erro ao registar veículo " + getLocalName() + " no DF: " + fe.getMessage());
        }
    }

    /**
     * Inspeciona os sensores do ambiente; se o tempo de imobilização exceder o limiar estipulado,
     * emite um pedido FIPA REQUEST ao semáforo competente.
     */
    private void verificarTempoDeEspera() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        double waitSeconds = env.getVehicleWaitSeconds(getLocalName());
        long now = System.currentTimeMillis();

        if (waitSeconds >= LIMIAR_ESPERA_SEGUNDOS && (now - lastRequestSentMillis > 6000)) {
            lastRequestSentMillis = now;

            // Determina o semáforo de destino com base no corredor viário da viatura
            String targetTrafficLight = road.equalsIgnoreCase("NS") ? "Semaforo_NS_C1_NW" : "Semaforo_EW_C1_NW";

            System.out.println("[VehicleAgent - " + getLocalName() + "] Tempo de espera crítico atingido (" 
                    + String.format("%.1f", waitSeconds) + "s). A emitir REQUEST a " + targetTrafficLight + "...");

            ACLMessage requestMsg = new ACLMessage(ACLMessage.REQUEST);
            requestMsg.addReceiver(new AID(targetTrafficLight, AID.ISLOCALNAME));
            requestMsg.setOntology("traffic-management");
            requestMsg.setConversationId("req-wait-" + System.currentTimeMillis());
            requestMsg.setContent("PEDIDO_PASSAGEM: Viatura " + getLocalName() + " retida ha " 
                    + String.format("%.1f", waitSeconds) + "s na via " + road);

            send(requestMsg);
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[VehicleAgent] Viatura " + getLocalName() + " desregistada do DF e terminada.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}