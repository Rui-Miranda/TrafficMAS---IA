package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

public class TrafficLightAgent extends Agent {
    private String direction;
    private int intersectionIndex = 0; // 0=NW, 1=NE, 2=SW, 3=SE
    private int axis = 0; // 0=NS, 1=EW
    private environment.CitySimulationGUI.LightState currentState = environment.CitySimulationGUI.LightState.RED;

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

        // Estado inicial alternado para evitar bloqueios no cruzamento
        currentState = (axis == 0) ? environment.CitySimulationGUI.LightState.GREEN : environment.CitySimulationGUI.LightState.RED;
        environment.CitySimulationGUI.getInstance().setTrafficLight(intersectionIndex, axis, currentState);

        System.out.println("[TrafficLightAgent] Semáforo " + direction + " (Cruzamento C" + (intersectionIndex + 1) + ") inicializado.");

        // Comportamento periódico: transição do ciclo semafórico a cada 4 segundos
        addBehaviour(new TickerBehaviour(this, 3500) {
            @Override
            protected void onTick() {
                // Alternar estado da luz e notificar o painel visual
                if (currentState == environment.CitySimulationGUI.LightState.GREEN) {
                    currentState = environment.CitySimulationGUI.LightState.YELLOW;
                } else if (currentState == environment.CitySimulationGUI.LightState.YELLOW) {
                    currentState = environment.CitySimulationGUI.LightState.RED;
                } else {
                    currentState = environment.CitySimulationGUI.LightState.GREEN;
                }

                environment.CitySimulationGUI.getInstance().setTrafficLight(intersectionIndex, axis, currentState);
            }
        });

        // Comportamento cíclico: Escutar mensagens dos veículos e responder
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    System.out.println("[TrafficLightAgent - " + direction + "] Mensagem recebida de " 
                            + msg.getSender().getLocalName() + ": " + msg.getContent());

                    // Cria e envia uma resposta formal FIPA (INFORM) de volta para o remetente
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("CONFIRMACAO: Pedido registado pelo semáforo " + direction + ". Aguarde abertura de fase verde.");
                    send(reply);
                } else {
                    block();
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        System.out.println("[TrafficLightAgent] Semáforo " + direction + " a desligar.");
    }
}