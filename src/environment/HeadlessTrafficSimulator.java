package environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Motor de Simulação de Tráfego em Modo Headless (Sem Interface Gráfica).
 *
 * Reproduz com total fidelidade a cinemática, regras de trânsito, detecções sensoriais
 * e interações semafóricas da {@link CitySimulationGUI}, mas concebido para:
 * 1. Execução ultra-rápida (fast-forward) para recolha massiva de dados temporais.
 * 2. Determinação rigorosa de cenários com sementes pseudoaleatórias controladas (Random).
 * 3. Integração direta com a {@link IntersectionSafetyAuthority} de cada cruzamento.
 *
 * Unidades físicas:
 * - Posição: píxeis (px).
 * - Velocidade: px/tick (em 30 ticks/s, 1.0 px/tick = 30 px/s ≈ 10.8 km/h; velocidade nominal de 2.4 px/tick ≈ 26 km/h).
 * - Tempo: segundos simulados.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class HeadlessTrafficSimulator {

    public static final int ROAD_WIDTH = 96;
    public static final int LANE_WIDTH = ROAD_WIDTH / 4; // 24px
    public static final int ROAD_X1 = 280;
    public static final int ROAD_X2 = 640;
    public static final int ROAD_Y1 = 280;
    public static final int ROAD_Y2 = 640;

    public static class SimVehicle {
        public String id;
        public double x, y;
        public double currentSpeed;
        public double maxSpeed = 2.4;
        public int direction; // 0: N->S, 1: S->N, 2: W->E, 3: E->W
        public int laneIndex; // 0: direita, 1: esquerda
        public double totalWaitSeconds = 0;
        public double currentStopStartSeconds = -1;
        public boolean wasStopped = false;
        public int startDelayTicks = 0;

        public SimVehicle(String id, double x, double y, double maxSpeed, int direction, int laneIndex) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.maxSpeed = maxSpeed;
            this.currentSpeed = maxSpeed * 0.75;
            this.direction = direction;
            this.laneIndex = laneIndex;
        }
    }

    public static class IntersectionMetricsSnapshot {
        public final int intersectionId;
        public final double simTimeSeconds;
        public final int trafficVolume;
        public final int queueLength;
        public final double averageSpeed;
        public final double averageWaitingTime;
        public final int roadCapacity;
        public final int weatherCondition; // 0=SECO, 1=CHUVA, 2=NEVOEIRO
        public final int incidentPresent;  // 0=NAO, 1=SIM

        public IntersectionMetricsSnapshot(int intersectionId, double simTimeSeconds, int trafficVolume, 
                                           int queueLength, double averageSpeed, double averageWaitingTime, 
                                           int roadCapacity, int weatherCondition, int incidentPresent) {
            this.intersectionId = intersectionId;
            this.simTimeSeconds = simTimeSeconds;
            this.trafficVolume = trafficVolume;
            this.queueLength = queueLength;
            this.averageSpeed = averageSpeed;
            this.averageWaitingTime = averageWaitingTime;
            this.roadCapacity = roadCapacity;
            this.weatherCondition = weatherCondition;
            this.incidentPresent = incidentPresent;
        }

        /**
         * Critério formal e quantitativo de Congestionamento Severo:
         * 1. Fila de viaturas paradas no cruzamento >= 6 viaturas (transbordo da zona de aproximação), OU
         * 2. Fila >= 5 viaturas com tempo médio de imobilização >= 14.0 segundos (mais de 2 ciclos completos de retenção).
         */
        public boolean isSevereCongestion() {
            return (queueLength >= 6) || (queueLength >= 5 && averageWaitingTime >= 14.0);
        }
    }

    private final List<SimVehicle> vehicles = new ArrayList<>();
    private final Random rng;
    private final String scenarioName;
    private final int weatherCondition; // 0, 1, 2
    private final boolean incidentPresent;
    private final double speedMultiplier;
    private final double spawnRatePerSecond;
    private final double asymmetricBias; // Para tráfego desequilibrado

    private double currentSimTime = 0.0;
    private int vehicleCounter = 0;
    private final IntersectionSafetyAuthority[] authorities = new IntersectionSafetyAuthority[4];

    public HeadlessTrafficSimulator(String scenarioName, long randomSeed) {
        this.scenarioName = scenarioName;
        this.rng = new Random(randomSeed);

        // Inicializa as 4 autoridades semafóricas
        for (int i = 0; i < 4; i++) {
            authorities[i] = new IntersectionSafetyAuthority(i);
        }

        // Configuração dos parâmetros segundo o cenário
        switch (scenarioName.toUpperCase()) {
            case "INTENSE":
                this.weatherCondition = 0;
                this.incidentPresent = false;
                this.speedMultiplier = 1.0;
                this.spawnRatePerSecond = 0.60; // Alta injeção
                this.asymmetricBias = 0.5;
                break;
            case "ASYMMETRIC":
                this.weatherCondition = 0;
                this.incidentPresent = false;
                this.speedMultiplier = 1.0;
                this.spawnRatePerSecond = 0.40;
                this.asymmetricBias = 0.85; // 85% de fluxo no eixo NS
                break;
            case "RAIN":
                this.weatherCondition = 1;
                this.incidentPresent = false;
                this.speedMultiplier = 0.72; // Atrito reduzido
                this.spawnRatePerSecond = 0.25;
                this.asymmetricBias = 0.5;
                break;
            case "FOG":
                this.weatherCondition = 2;
                this.incidentPresent = false;
                this.speedMultiplier = 0.78; // Visibilidade baixa
                this.spawnRatePerSecond = 0.25;
                this.asymmetricBias = 0.5;
                break;
            case "ACCIDENT":
                this.weatherCondition = 0;
                this.incidentPresent = true;
                this.speedMultiplier = 0.60; // Bloqueio de faixa
                this.spawnRatePerSecond = 0.40;
                this.asymmetricBias = 0.5;
                break;
            case "ROADWORK":
                this.weatherCondition = 0;
                this.incidentPresent = true;
                this.speedMultiplier = 0.65; // Obras com estreitamento
                this.spawnRatePerSecond = 0.35;
                this.asymmetricBias = 0.5;
                break;
            case "SPECIAL_EVENT":
                this.weatherCondition = 0;
                this.incidentPresent = false;
                this.speedMultiplier = 0.90;
                this.spawnRatePerSecond = 0.75; // Grande volume
                this.asymmetricBias = 0.75;
                break;
            case "NORMAL":
            default:
                this.weatherCondition = 0;
                this.incidentPresent = false;
                this.speedMultiplier = 1.0;
                this.spawnRatePerSecond = 0.18; // Fluxo moderado e fluido
                this.asymmetricBias = 0.5;
                break;
        }
    }

    /**
     * Avança a simulação um determinado número de segundos (em passos discretos de 1/30 s = 33.3ms).
     */
    public void advanceSeconds(double durationSeconds) {
        double dt = 1.0 / 30.0;
        int steps = (int) Math.round(durationSeconds / dt);
        for (int i = 0; i < steps; i++) {
            tickStep(dt);
        }
    }

    private void tickStep(double dt) {
        currentSimTime += dt;

        // 1. Injeção estocástica de viaturas com taxa controlada
        if (rng.nextDouble() < (spawnRatePerSecond * dt)) {
            spawnRandomVehicle();
        }

        // 2. Atualização dos Semáforos via IntersectionSafetyAuthority
        for (int i = 0; i < 4; i++) {
            int waitingNS = getWaitingCount(i, 0);
            int waitingEW = getWaitingCount(i, 1);
            authorities[i].updateTick(waitingNS, waitingEW);
        }

        // 3. Cinemática das Viaturas (Aceleração, Travagem, Stop Lines e Colisão)
        updateVehicles(dt);
    }

    private void spawnRandomVehicle() {
        if (vehicles.size() >= 45) return; // Limite de saturação máxima física

        // Escolhe direção (0=NS, 1=SN, 2=WE, 3=EW) com base na assimetria
        int dir;
        if (rng.nextDouble() < asymmetricBias) {
            dir = rng.nextBoolean() ? 0 : 1; // Eixo NS
        } else {
            dir = rng.nextBoolean() ? 2 : 3; // Eixo EW
        }

        int laneIndex = rng.nextInt(2);
        double startX = 0, startY = 0;

        switch (dir) {
            case 0:
                startX = (laneIndex == 0) ? (ROAD_X1 + 0.5 * LANE_WIDTH) : (ROAD_X1 + 1.5 * LANE_WIDTH);
                startY = -20;
                break;
            case 1:
                startX = (laneIndex == 0) ? (ROAD_X2 + 3.5 * LANE_WIDTH) : (ROAD_X2 + 2.5 * LANE_WIDTH);
                startY = 950;
                break;
            case 2:
                startX = -20;
                startY = (laneIndex == 0) ? (ROAD_Y1 + 3.5 * LANE_WIDTH) : (ROAD_Y1 + 2.5 * LANE_WIDTH);
                break;
            case 3:
                startX = 950;
                startY = (laneIndex == 0) ? (ROAD_Y2 + 0.5 * LANE_WIDTH) : (ROAD_Y2 + 1.5 * LANE_WIDTH);
                break;
        }

        // Evita sobreposição imediata no ponto de spawn
        for (SimVehicle v : vehicles) {
            if (Math.abs(v.x - startX) < 20 && Math.abs(v.y - startY) < 20) {
                return;
            }
        }

        vehicleCounter++;
        vehicles.add(new SimVehicle("Car_" + vehicleCounter, startX, startY, 2.4, dir, laneIndex));
    }

    private void updateVehicles(double dt) {
        List<SimVehicle> toRemove = new ArrayList<>();

        for (SimVehicle v : vehicles) {
            CitySimulationGUI.LightState light = getSignalForVehicle(v);
            double distToStop = getDistanceToTargetStopLine(v);

            // Deteção do líder na mesma via
            double distToLeader = getDistanceToLeadingVehicle(v);
            boolean blockedByLeader = (distToLeader < 38.0);

            boolean brakeForYellow = (light == CitySimulationGUI.LightState.YELLOW && distToStop <= 55 && distToStop > 15);
            boolean brakeForRed = (light == CitySimulationGUI.LightState.RED && distToStop <= 65);
            boolean shouldBrake = blockedByLeader || brakeForYellow || brakeForRed;

            // Se acidente/obras na faixa 1 no cruzamento C2, bloqueia
            if (incidentPresent && v.laneIndex == 1 && distToStop < 45 && getTargetIntersection(v) == 1) {
                shouldBrake = true;
            }

            if (shouldBrake) {
                double limitDist = blockedByLeader ? (distToLeader - 32.0) : distToStop;
                if (limitDist <= 2.0) {
                    v.currentSpeed = 0.0;
                    v.wasStopped = true;
                    if (v.currentStopStartSeconds < 0) {
                        v.currentStopStartSeconds = currentSimTime;
                    }
                } else {
                    double targetSpeed = Math.max(0.0, (limitDist / 50.0) * (v.maxSpeed * speedMultiplier));
                    v.currentSpeed = Math.max(0.0, v.currentSpeed - 0.14);
                    if (v.currentSpeed > targetSpeed) v.currentSpeed = targetSpeed;
                }
            } else {
                if (v.currentStopStartSeconds >= 0) {
                    v.totalWaitSeconds += (currentSimTime - v.currentStopStartSeconds);
                    v.currentStopStartSeconds = -1;
                }
                double targetSpeed = v.maxSpeed * speedMultiplier;
                if (v.wasStopped) {
                    if (v.startDelayTicks < 14) {
                        v.startDelayTicks++;
                        v.currentSpeed = 0.0;
                    } else {
                        v.currentSpeed = Math.min(targetSpeed, v.currentSpeed + 0.08);
                        if (v.currentSpeed >= targetSpeed * 0.8) v.wasStopped = false;
                    }
                } else {
                    v.currentSpeed = Math.min(targetSpeed, v.currentSpeed + 0.06);
                }
            }

            // Move veículo
            switch (v.direction) {
                case 0: v.y += v.currentSpeed; break;
                case 1: v.y -= v.currentSpeed; break;
                case 2: v.x += v.currentSpeed; break;
                case 3: v.x -= v.currentSpeed; break;
            }

            // Remoção de viaturas que saíram dos limites
            if (v.x < -60 || v.x > 1050 || v.y < -60 || v.y > 1050) {
                toRemove.add(v);
            }
        }

        vehicles.removeAll(toRemove);
    }

    private CitySimulationGUI.LightState getSignalForVehicle(SimVehicle v) {
        int interIdx = getTargetIntersection(v);
        int axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
        return authorities[interIdx].getLightState(axis);
    }

    private int getTargetIntersection(SimVehicle v) {
        boolean isWest = (v.x < 460);
        boolean isNorth = (v.y < 460);
        switch (v.direction) {
            case 0: return (v.y < ROAD_Y1 - 2) ? (isWest ? 0 : 1) : (isWest ? 2 : 3);
            case 1: return (v.y > ROAD_Y2 + ROAD_WIDTH + 2) ? (isWest ? 2 : 3) : (isWest ? 0 : 1);
            case 2: return (v.x < ROAD_X1 - 2) ? (isNorth ? 0 : 2) : (isNorth ? 1 : 3);
            case 3: return (v.x > ROAD_X2 + ROAD_WIDTH + 2) ? (isNorth ? 1 : 3) : (isNorth ? 0 : 2);
            default: return 0;
        }
    }

    private double getDistanceToTargetStopLine(SimVehicle v) {
        switch (v.direction) {
            case 0: return (v.y < ROAD_Y1 - 2) ? ((ROAD_Y1 - 2) - v.y) : ((ROAD_Y2 - 2) - v.y);
            case 1: return (v.y > ROAD_Y2 + ROAD_WIDTH + 2) ? (v.y - (ROAD_Y2 + ROAD_WIDTH + 2)) : (v.y - (ROAD_Y1 + ROAD_WIDTH + 2));
            case 2: return (v.x < ROAD_X1 - 2) ? ((ROAD_X1 - 2) - v.x) : ((ROAD_X2 - 2) - v.x);
            case 3: return (v.x > ROAD_X2 + ROAD_WIDTH + 2) ? (v.x - (ROAD_X2 + ROAD_WIDTH + 2)) : (v.x - (ROAD_X1 + ROAD_WIDTH + 2));
            default: return 9999.0;
        }
    }

    private double getDistanceToLeadingVehicle(SimVehicle v) {
        double minDist = 9999.0;
        for (SimVehicle other : vehicles) {
            if (other == v || other.direction != v.direction || other.laneIndex != v.laneIndex) continue;
            double dist = 9999.0;
            switch (v.direction) {
                case 0: if (other.y > v.y && Math.abs(other.x - v.x) < 14) dist = other.y - v.y; break;
                case 1: if (other.y < v.y && Math.abs(other.x - v.x) < 14) dist = v.y - other.y; break;
                case 2: if (other.x > v.x && Math.abs(other.y - v.y) < 14) dist = other.x - v.x; break;
                case 3: if (other.x < v.x && Math.abs(other.y - v.y) < 14) dist = v.x - other.x; break;
            }
            if (dist > 0 && dist < minDist) minDist = dist;
        }
        return minDist;
    }

    public int getWaitingCount(int interIdx, int axis) {
        int count = 0;
        for (SimVehicle v : vehicles) {
            int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            if (targetAxis != axis) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 220.0 && v.currentSpeed < 0.2) {
                count++;
            }
        }
        return count;
    }

    /**
     * Extrai um snapshot de métricas no cruzamento especificado no instante temporal atual.
     */
    public IntersectionMetricsSnapshot captureSnapshot(int intersectionId) {
        int volume = 0;
        int queue = 0;
        double sumSpeed = 0.0;
        double sumCurrentWait = 0.0;

        for (SimVehicle v : vehicles) {
            if (getTargetIntersection(v) == intersectionId) {
                double dist = getDistanceToTargetStopLine(v);
                if (dist >= 0 && dist <= 220.0) {
                    volume++;
                    sumSpeed += v.currentSpeed;
                    if (v.currentSpeed < 0.2) {
                        queue++;
                        double currentWait = (v.currentStopStartSeconds >= 0) ? (currentSimTime - v.currentStopStartSeconds) : 0.0;
                        sumCurrentWait += currentWait;
                    }
                }
            }
        }

        double avgSpeed = (volume > 0) ? Math.round((sumSpeed / volume) * 100.0) / 100.0 : (2.4 * speedMultiplier);
        double avgWait = (queue > 0) ? Math.round((sumCurrentWait / queue) * 10.0) / 10.0 : 0.0;
        int roadCapacity = incidentPresent ? 2 : 4;

        return new IntersectionMetricsSnapshot(intersectionId, currentSimTime, volume, queue, 
                                              avgSpeed, avgWait, roadCapacity, weatherCondition, 
                                              incidentPresent ? 1 : 0);
    }

    public double getCurrentSimTime() {
        return currentSimTime;
    }

    public String getScenarioName() {
        return scenarioName;
    }
}
