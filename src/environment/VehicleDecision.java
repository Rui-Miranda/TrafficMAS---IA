package environment;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decisão Deliberativa formulada autonomamente pelo VehicleAgent (Fase 6.2-D).
 *
 * Cada decisão possui identificador único sequencial, timestamp de emissão e
 * validade temporal (TTL). O motor de simulação apenas executa decisões válidas
 * e recentes; decisões obsoletas ou ausência de decisão por suspensão do agente
 * resultam em paragem controlada segura.
 */
public class VehicleDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String vehicleId;
    private final long decisionSequence;
    private final long timestampMillis;
    private final long validUntilMillis;
    private final VehicleAction action;
    private final double targetSpeed;
    private final CitySimulationGUI.ManeuverIntent plannedIntent;
    private final int desiredLane; // 0: exterior/direita, 1: interior/esquerda
    private final int originNode;
    private final int destinationNode;
    private final List<Integer> activeRouteNodes;
    private final String drivingState;
    private final String statusExplanation;
    private final boolean requestFipaPriority;

    public VehicleDecision(String vehicleId, long decisionSequence, long timestampMillis, long validUntilMillis,
                           VehicleAction action, double targetSpeed,
                           CitySimulationGUI.ManeuverIntent plannedIntent, int desiredLane,
                           int originNode, int destinationNode, List<Integer> activeRouteNodes,
                           String drivingState, String statusExplanation, boolean requestFipaPriority) {
        this.vehicleId = vehicleId;
        this.decisionSequence = decisionSequence;
        this.timestampMillis = timestampMillis;
        this.validUntilMillis = validUntilMillis;
        this.action = (action != null) ? action : VehicleAction.CRUISE;
        this.targetSpeed = Math.max(0.0, targetSpeed);
        this.plannedIntent = (plannedIntent != null) ? plannedIntent : CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
        this.desiredLane = (desiredLane == 0) ? 0 : 1;
        this.originNode = originNode;
        this.destinationNode = destinationNode;
        this.activeRouteNodes = (activeRouteNodes != null)
                ? Collections.unmodifiableList(new ArrayList<>(activeRouteNodes))
                : Collections.emptyList();
        this.drivingState = (drivingState != null) ? drivingState : "NORMAL";
        this.statusExplanation = (statusExplanation != null) ? statusExplanation : "";
        this.requestFipaPriority = requestFipaPriority;
    }

    /**
     * Construtor auxiliar para paragem controlada segura imediata (ex: agente suspenso ou ausente).
     */
    public static VehicleDecision createControlledStopDecision(String vehicleId, String reason, long timestampMillis) {
        return new VehicleDecision(
                vehicleId,
                -1L,
                timestampMillis,
                timestampMillis + 1500L,
                VehicleAction.STOP,
                0.0,
                CitySimulationGUI.ManeuverIntent.GO_STRAIGHT,
                0,
                -1,
                -1,
                Collections.emptyList(),
                "CONTROLLED_STOP",
                reason,
                false
        );
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public long getDecisionSequence() {
        return decisionSequence;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public long getValidUntilMillis() {
        return validUntilMillis;
    }

    public VehicleAction getAction() {
        return action;
    }

    public double getTargetSpeed() {
        return targetSpeed;
    }

    public CitySimulationGUI.ManeuverIntent getPlannedIntent() {
        return plannedIntent;
    }

    public int getDesiredLane() {
        return desiredLane;
    }

    public int getOriginNode() {
        return originNode;
    }

    public int getDestinationNode() {
        return destinationNode;
    }

    public List<Integer> getActiveRouteNodes() {
        return activeRouteNodes;
    }

    public String getDrivingState() {
        return drivingState;
    }

    public String getStatusExplanation() {
        return statusExplanation;
    }

    public boolean isRequestFipaPriority() {
        return requestFipaPriority;
    }

    public boolean isFipaRequested() {
        return requestFipaPriority;
    }

    public boolean isExpired(long currentTimeMillis) {
        return currentTimeMillis > validUntilMillis;
    }

    public boolean isSafeStop() {
        return action == VehicleAction.STOP || action == VehicleAction.EMERGENCY_STOP;
    }

    @Override
    public String toString() {
        return String.format("VehicleDecision[#%d %s: action=%s, vTarget=%.2f, intent=%s, lane=%d, state=%s, dest=%d]",
                decisionSequence, vehicleId, action, targetSpeed, plannedIntent, desiredLane, drivingState, destinationNode);
    }
}

