package environment;

import java.io.Serializable;

/**
 * Snapshot Sensorial disponibilizado pelo ambiente físico ao VehicleAgent (Fase 6.2-D).
 *
 * Encapsula de forma imutável todas as perceções locais do veículo no paradigma:
 * PERCEÇÃO -> RACIOCÍNIO -> AÇÃO.
 *
 * Permite ao agente tomar decisões autónomas de aceleração, travagem, paragem,
 * manobra de viragem e replaneamento A*.
 */
public class VehiclePerception implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String vehicleId;
    private final double x;
    private final double y;
    private final double currentSpeed;
    private final double maxSpeed;
    private final int direction; // 0: N->S, 1: S->N, 2: W->E, 3: E->W
    private final int laneIndex; // 0: faixa exterior/direita, 1: faixa interior/esquerda
    private final boolean inIntersection;
    private final int approachingIntersection; // Índice do cruzamento alvo mais próximo (-1 se nenhum)
    private final double distanceToStopLine; // Distância longitudinal até à stop-line (px)
    private final CitySimulationGUI.LightState trafficLightState; // GREEN, YELLOW, RED
    private final double distanceToLeadingVehicle; // Distância ao líder na mesma faixa (px, ou 9999.0 se livre)
    private final double leadingVehicleSpeed; // Velocidade do líder (-1 se nenhum)
    private final long graphVersion; // Versão de topologia do RoadNetworkGraph
    private final double speedMultiplier; // Multiplicador ambiental de velocidade (ex: atrito/chuva)
    private final long timestampMillis;
    private final long tick;

    public VehiclePerception(String vehicleId, double x, double y, double currentSpeed, double maxSpeed,
                             int direction, int laneIndex, boolean inIntersection,
                             int approachingIntersection, double distanceToStopLine,
                             CitySimulationGUI.LightState trafficLightState,
                             double distanceToLeadingVehicle, double leadingVehicleSpeed,
                             long graphVersion, double speedMultiplier,
                             long timestampMillis, long tick) {
        this.vehicleId = vehicleId;
        this.x = x;
        this.y = y;
        this.currentSpeed = currentSpeed;
        this.maxSpeed = maxSpeed;
        this.direction = direction;
        this.laneIndex = laneIndex;
        this.inIntersection = inIntersection;
        this.approachingIntersection = approachingIntersection;
        this.distanceToStopLine = distanceToStopLine;
        this.trafficLightState = (trafficLightState != null) ? trafficLightState : CitySimulationGUI.LightState.GREEN;
        this.distanceToLeadingVehicle = distanceToLeadingVehicle;
        this.leadingVehicleSpeed = leadingVehicleSpeed;
        this.graphVersion = graphVersion;
        this.speedMultiplier = speedMultiplier;
        this.timestampMillis = timestampMillis;
        this.tick = tick;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getCurrentSpeed() {
        return currentSpeed;
    }

    public double getMaxSpeed() {
        return maxSpeed;
    }

    public int getDirection() {
        return direction;
    }

    public int getLaneIndex() {
        return laneIndex;
    }

    public boolean isInIntersection() {
        return inIntersection;
    }

    public int getApproachingIntersection() {
        return approachingIntersection;
    }

    public double getDistanceToStopLine() {
        return distanceToStopLine;
    }

    public CitySimulationGUI.LightState getTrafficLightState() {
        return trafficLightState;
    }

    public double getDistanceToLeadingVehicle() {
        return distanceToLeadingVehicle;
    }

    public double getLeadingVehicleSpeed() {
        return leadingVehicleSpeed;
    }

    public long getGraphVersion() {
        return graphVersion;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public long getTick() {
        return tick;
    }

    /**
     * Eixo semafórico correspondente à direção física atual:
     * 0 (N->S) ou 1 (S->N) -> Eixo 0 (Norte-Sul / NS)
     * 2 (W->E) ou 3 (E->W) -> Eixo 1 (Este-Oeste / EW)
     */
    public int getAxis() {
        return (direction == 0 || direction == 1) ? 0 : 1;
    }

    public boolean hasLeadingObstacle(double safetyThresholdPx) {
        return distanceToLeadingVehicle < safetyThresholdPx;
    }

    public boolean isApproachingStopLine(double thresholdPx) {
        return distanceToStopLine >= 0.0 && distanceToStopLine <= thresholdPx;
    }

    public boolean isLightGreen() {
        return trafficLightState == CitySimulationGUI.LightState.GREEN;
    }

    @Override
    public String toString() {
        return String.format("VehiclePerception[%s @ (%.1f, %.1f), dir=%d, lane=%d, v=%.2f, stopDist=%.1f, light=%s, leaderDist=%.1f]",
                vehicleId, x, y, direction, laneIndex, currentSpeed, distanceToStopLine, trafficLightState, distanceToLeadingVehicle);
    }
}

