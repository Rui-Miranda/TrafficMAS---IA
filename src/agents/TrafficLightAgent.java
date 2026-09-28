package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

public class TrafficLightAgent extends Agent {
    private String direction;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.direction = (String) args[0];
        } else {
            this.direction = "Desconhecida";
        }

        System.out.println("[TrafficLightAgent] Semáforo inicializado para a via: " + direction);

        // Comportamento periódico: simula verificação a cada 4 segundos
        addBehaviour(new TickerBehaviour(this, 4000) {
            @Override
            protected void onTick() {
                System.out.println("[TrafficLightAgent - " + direction + "] A monitorizar tráfego na interseção...");
            }
        });
    }

    @Override
    protected void takeDown() {
        System.out.println("[TrafficLightAgent] Semáforo " + direction + " a desligar.");
    }
}