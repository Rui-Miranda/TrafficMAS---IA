package environment;

import java.io.Serializable;

/**
 * Snapshot Sensorial disponibilizado pelo ambiente físico ao VehicleAgent (Fase 6.2-D / Fase 6.3 / Fase 6.4).
 *
 * Encapsula de forma imutável todas as perceções locais do veículo no paradigma:
 * PERCEÇÃO -> RACIOCÍNIO -> AÇÃO.
 *
 * Fase 6.3: Estendido com perceções de viaturas prioritárias em aproximação pela retaguarda,
 * estado da missão, urgência e viabilidade física de cedência de passagem (mudança de faixa segura).
 *
 * Fase 6.4: Estendido com condições meteorológicas, estado físico da superfície da via,
 * atrito disponível, visibilidade atmosférica, distância de segurança ajustada e parâmetros de travagem.
 */
public class VehiclePerception implements Serializable {

    private static final long serialVersionUID = 3L;

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
    private final double speedMultiplier; // Multiplicador ambiental de velocidade
    private final long timestampMillis;
    private final long tick;

    // Campos de Perceção de Emergências (Fase 6.3 — Cedência Física Cooperativa)
    private final boolean hasApproachingEmergency;
    private final String emergencyVehicleId;
    private final String emergencyCategory; // AMBULANCE, FIRE_TRUCK, POLICE
    private final String emergencyMissionId;
    private final int emergencyUrgency; // 1 a 5
    private final double emergencyDistanceBehind; // Distância longitudinal atrás da viatura (px)
    private final double emergencyRelativeSpeed; // Velocidade relativa de aproximação
    private final int emergencyLane; // 0: direita, 1: esquerda
    private final boolean isRightLaneClearForYield; // Se a faixa direita está desimpedida
    private final boolean isYieldSafe; // Se a manobra de cedência é fisicamente segura
    private final boolean approachingFromBehind;
    private final double estimatedTimeToArrivalSeconds;

    // Campos de Meteorologia e Condições da Estrada (Fase 6.4)
    private final WeatherType weatherType;
    private final RoadCondition roadCondition;
    private final double frictionCoefficient;
    private final double visibilityKm;
    private final double safeFollowingDistance;
    private final double safeStoppingDistance;
    private final double maxSafeDeceleration;
    private final long weatherStateVersion;

    /**
     * Construtor Completo da Fase 6.4 (Meteorologia Dinâmica e Condições da Estrada).
     */
    public VehiclePerception(String vehicleId, double x, double y, double currentSpeed, double maxSpeed,
                             int direction, int laneIndex, boolean inIntersection,
                             int approachingIntersection, double distanceToStopLine,
                             CitySimulationGUI.LightState trafficLightState,
                             double distanceToLeadingVehicle, double leadingVehicleSpeed,
                             long graphVersion, double speedMultiplier,
                             long timestampMillis, long tick,
                             boolean hasApproachingEmergency, String emergencyVehicleId,
                             String emergencyCategory, String emergencyMissionId,
                             int emergencyUrgency, double emergencyDistanceBehind,
                             double emergencyRelativeSpeed, int emergencyLane,
                             boolean isRightLaneClearForYield, boolean isYieldSafe,
                             boolean approachingFromBehind, double estimatedTimeToArrivalSeconds,
                             WeatherType weatherType, RoadCondition roadCondition,
                             double frictionCoefficient, double visibilityKm,
                             double safeFollowingDistance, double safeStoppingDistance,
                             double maxSafeDeceleration, long weatherStateVersion) {
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

        this.hasApproachingEmergency = hasApproachingEmergency;
        this.emergencyVehicleId = emergencyVehicleId;
        this.emergencyCategory = (emergencyCategory != null) ? emergencyCategory : "AMBULANCE";
        this.emergencyMissionId = emergencyMissionId;
        this.emergencyUrgency = emergencyUrgency;
        this.emergencyDistanceBehind = emergencyDistanceBehind;
        this.emergencyRelativeSpeed = emergencyRelativeSpeed;
        this.emergencyLane = emergencyLane;
        this.isRightLaneClearForYield = isRightLaneClearForYield;
        this.isYieldSafe = isYieldSafe;
        this.approachingFromBehind = approachingFromBehind;
        this.estimatedTimeToArrivalSeconds = estimatedTimeToArrivalSeconds;

        this.weatherType = (weatherType != null) ? weatherType : WeatherType.CLEAR;
        this.roadCondition = (roadCondition != null) ? roadCondition : RoadCondition.DRY;
        this.frictionCoefficient = (frictionCoefficient > 0.0) ? frictionCoefficient : this.roadCondition.getFrictionCoefficient();
        this.visibilityKm = (visibilityKm > 0.0) ? visibilityKm : this.weatherType.getVisibilityKm();
        this.safeFollowingDistance = safeFollowingDistance;
        this.safeStoppingDistance = safeStoppingDistance;
        this.maxSafeDeceleration = maxSafeDeceleration;
        this.weatherStateVersion = weatherStateVersion;
    }

    /**
     * Construtor Retrocompatível da Fase 6.3 (com perceção de emergências e valores padrão da meteorologia).
     */
    public VehiclePerception(String vehicleId, double x, double y, double currentSpeed, double maxSpeed,
                             int direction, int laneIndex, boolean inIntersection,
                             int approachingIntersection, double distanceToStopLine,
                             CitySimulationGUI.LightState trafficLightState,
                             double distanceToLeadingVehicle, double leadingVehicleSpeed,
                             long graphVersion, double speedMultiplier,
                             long timestampMillis, long tick,
                             boolean hasApproachingEmergency, String emergencyVehicleId,
                             String emergencyCategory, String emergencyMissionId,
                             int emergencyUrgency, double emergencyDistanceBehind,
                             double emergencyRelativeSpeed, int emergencyLane,
                             boolean isRightLaneClearForYield, boolean isYieldSafe,
                             boolean approachingFromBehind, double estimatedTimeToArrivalSeconds) {
        this(vehicleId, x, y, currentSpeed, maxSpeed, direction, laneIndex, inIntersection,
             approachingIntersection, distanceToStopLine, trafficLightState,
             distanceToLeadingVehicle, leadingVehicleSpeed, graphVersion, speedMultiplier,
             timestampMillis, tick,
             hasApproachingEmergency, emergencyVehicleId, emergencyCategory, emergencyMissionId,
             emergencyUrgency, emergencyDistanceBehind, emergencyRelativeSpeed, emergencyLane,
             isRightLaneClearForYield, isYieldSafe, approachingFromBehind, estimatedTimeToArrivalSeconds,
             WeatherType.CLEAR, RoadCondition.DRY, 1.0, 10.0, 38.0, 15.0, 0.12, 1L);
    }

    /**
     * Construtor Retrocompatível da Fase 6.2-D (sem perceção de emergência em aproximação).
     */
    public VehiclePerception(String vehicleId, double x, double y, double currentSpeed, double maxSpeed,
                             int direction, int laneIndex, boolean inIntersection,
                             int approachingIntersection, double distanceToStopLine,
                             CitySimulationGUI.LightState trafficLightState,
                             double distanceToLeadingVehicle, double leadingVehicleSpeed,
                             long graphVersion, double speedMultiplier,
                             long timestampMillis, long tick) {
        this(vehicleId, x, y, currentSpeed, maxSpeed, direction, laneIndex, inIntersection,
             approachingIntersection, distanceToStopLine, trafficLightState,
             distanceToLeadingVehicle, leadingVehicleSpeed, graphVersion, speedMultiplier,
             timestampMillis, tick,
             false, null, null, null, 0, 9999.0, 0.0, -1, false, false, false, 9999.0,
             WeatherType.CLEAR, RoadCondition.DRY, 1.0, 10.0, 38.0, 15.0, 0.12, 1L);
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

    // Métodos de Acesso à Perceção de Emergências (Fase 6.3)
    public boolean hasApproachingEmergency() {
        return hasApproachingEmergency;
    }

    public String getEmergencyVehicleId() {
        return emergencyVehicleId;
    }

    public String getEmergencyCategory() {
        return emergencyCategory;
    }

    public String getEmergencyMissionId() {
        return emergencyMissionId;
    }

    public int getEmergencyUrgency() {
        return emergencyUrgency;
    }

    public double getEmergencyDistanceBehind() {
        return emergencyDistanceBehind;
    }

    public double getEmergencyRelativeSpeed() {
        return emergencyRelativeSpeed;
    }

    public int getEmergencyLane() {
        return emergencyLane;
    }

    public boolean isRightLaneClearForYield() {
        return isRightLaneClearForYield;
    }

    public boolean isYieldSafe() {
        return isYieldSafe;
    }

    public boolean isApproachingFromBehind() {
        return approachingFromBehind;
    }

    public double getEstimatedTimeToArrivalSeconds() {
        return estimatedTimeToArrivalSeconds;
    }

    // Métodos de Acesso à Perceção Meteorológica e de Estrada (Fase 6.4)
    public WeatherType getWeatherType() {
        return weatherType;
    }

    public RoadCondition getRoadCondition() {
        return roadCondition;
    }

    public double getFrictionCoefficient() {
        return frictionCoefficient;
    }

    public double getVisibilityKm() {
        return visibilityKm;
    }

    public double getSafeFollowingDistance() {
        return safeFollowingDistance;
    }

    public double getSafeStoppingDistance() {
        return safeStoppingDistance;
    }

    public double getMaxSafeDeceleration() {
        return maxSafeDeceleration;
    }

    public long getWeatherStateVersion() {
        return weatherStateVersion;
    }

    @Override
    public String toString() {
        return String.format("VehiclePerception[%s @ (%.1f, %.1f), dir=%d, lane=%d, v=%.2f, stopDist=%.1f, light=%s, weather=%s/%s, friction=%.2f]",
                vehicleId, x, y, direction, laneIndex, currentSpeed, distanceToStopLine, trafficLightState,
                weatherType.getCode(), roadCondition.getCode(), frictionCoefficient);
    }
}
