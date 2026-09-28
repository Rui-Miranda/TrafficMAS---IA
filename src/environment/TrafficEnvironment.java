package environment;

import java.util.concurrent.ConcurrentLinkedQueue;

public class TrafficEnvironment {
    public enum LightColor {
        RED, YELLOW, GREEN
    }

    // Estados iniciais: Norte-Sul a Vermelho, Este-Oeste a Verde
    private LightColor northSouthLight = LightColor.RED;
    private LightColor eastWestLight = LightColor.GREEN;

    // Filas para registar veículos à espera em cada via
    private ConcurrentLinkedQueue<String> queueNS = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> queueEW = new ConcurrentLinkedQueue<>();

    // Atualiza as cores dos semáforos
    public synchronized void setLights(LightColor ns, LightColor ew) {
        this.northSouthLight = ns;
        this.eastWestLight = ew;
        System.out.println("\n[AMBIENTE] Semáforos atualizados -> Norte-Sul: " + ns + " | Este-Oeste: " + ew);
    }

    // Regista a chegada de um veículo
    public void addVehicle(String road, String vehicleId) {
        if (road.equalsIgnoreCase("NS")) {
            queueNS.add(vehicleId);
        } else {
            queueEW.add(vehicleId);
        }
        System.out.println("[AMBIENTE] Veículo " + vehicleId + " entrou na fila " + road.toUpperCase());
    }

    public LightColor getNorthSouthLight() {
        return northSouthLight;
    }

    public LightColor getEastWestLight() {
        return eastWestLight;
    }

    public int getQueueLength(String road) {
        return road.equalsIgnoreCase("NS") ? queueNS.size() : queueEW.size();
    }
}