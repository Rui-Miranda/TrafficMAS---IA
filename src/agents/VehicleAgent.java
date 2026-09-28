package agents;

import jade.core.Agent;

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
    }

    @Override
    protected void takeDown() {
        System.out.println("[VehicleAgent] Veículo " + getLocalName() + " saiu da simulação.");
    }
}