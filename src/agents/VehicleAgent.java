package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

public class VehicleAgent extends Agent {
    private String road;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.road = (String) args[0];
        } else {
            this.road = "NS";
        }

        System.out.println("[VehicleAgent] Veículo " + getLocalName() + " entrou na via " + road + ".");

        // Registar o veículo visualmente na Cidade
        int visualDir = road.equalsIgnoreCase("NS") ? 0 : 2; // 0: Norte->Sul, 2: Oeste->Este
        java.awt.Color carColor = getLocalName().toLowerCase().contains("amb") ? java.awt.Color.WHITE : java.awt.Color.CYAN;
        environment.CitySimulationGUI.getInstance().addVehicle(getLocalName(), visualDir, carColor);

        // 1. Enviar periodicamente (a cada 5 segundos) um pedido REQUEST ao semáforo da respetiva via
        addBehaviour(new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() {
                // Descobrir qual é o semáforo de destino com base na via (associado ao cruzamento inicial C1_NW)
                String targetTrafficLight = road.equalsIgnoreCase("NS") ? "Semaforo_NS_C1_NW" : "Semaforo_EW_C1_NW";

                System.out.println("[VehicleAgent - " + getLocalName() + "] A enviar pedido de passagem ao " + targetTrafficLight + "...");

                ACLMessage requestMsg = new ACLMessage(ACLMessage.REQUEST);
                requestMsg.addReceiver(new AID(targetTrafficLight, AID.ISLOCALNAME));
                requestMsg.setOntology("traffic-management");
                requestMsg.setConversationId("traffic-req-" + System.currentTimeMillis());
                requestMsg.setContent("PEDIDO_PASSAGEM: Veículo " + getLocalName() + " aguarda na fila da via " + road);
                
                send(requestMsg);
            }
        });

        // 2. Escutar respostas do semáforo
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage reply = receive();
                if (reply != null) {
                    System.out.println("[VehicleAgent - " + getLocalName() + "] Resposta recebida de " 
                            + reply.getSender().getLocalName() + " (" + ACLMessage.getPerformative(reply.getPerformative()) + "): " 
                            + reply.getContent());
                } else {
                    block();
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        System.out.println("[VehicleAgent] Veículo " + getLocalName() + " saiu da simulação.");
    }
}