package environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Motor de Simulação de Tráfego em Modo Headless (Sem Interface Gráfica).
 *
 * Reproduz com total fidelidade a cinemática, regras de trânsito, detecções sensoriais
 * e interações semafóricas da {@link CitySimulationGUI}, mas concebido para:
 * 1. Execução ultra-rápida (fast-forward) com temporização determinística (SimulationClock).
 * 2. Determinação rigorosa de cenários com sementes pseudoaleatórias controladas (Random).
 * 3. Integração direta com a {@link IntersectionSafetyAuthority} de cada cruzamento.
 * 4. Fornecimento de telemetria sensorial em tempo real (EnvironmentDataProvider).
 * 5. Suporte aos 4 modos de controlo experimental (FIXED_TIME, LOCAL_ADAPTIVE, MULTIAGENT_COORDINATED, MULTIAGENT_ML).
 *
 * Unidades físicas:
 * - Posição: píxeis (px).
 * - Velocidade: px/tick (em 30 ticks/s, 1.0 px/tick = 30 px/s ≈ 10.8 km/h; velocidade nominal de 2.4 px/tick ≈ 26 km/h).
 * - Tempo: segundos simulados.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class HeadlessTrafficSimulator implements TrafficEnvironment.EnvironmentDataProvider {

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
    private IntersectionSafetyAuthority.ControlMode controlMode = IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE;

    private double nextCoordinationTime = 3.5;
    private double nextMLInferenceTime = 10.0;

    public HeadlessTrafficSimulator(String scenarioName, long randomSeed) {
        this(scenarioName, randomSeed, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE);
    }

    public HeadlessTrafficSimulator(String scenarioName, long randomSeed, IntersectionSafetyAuthority.ControlMode mode) {
        this.scenarioName = scenarioName;
        this.rng = new Random(randomSeed);
        this.controlMode = (mode != null) ? mode : IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE;

        // Inicializa ou reaproveita as 4 autoridades semafóricas oficiais do cruzamento
        for (int i = 0; i < 4; i++) {
            authorities[i] = IntersectionSafetyAuthority.getAuthority(i);
            authorities[i].setControlMode(this.controlMode);
        }

        // Ativa o modo simulado e conecta o provedor de dados de ambiente
        SimulationClock.enableSimulatedMode(0L);
        SimulationClock.setSimulatedTimeSeconds(0.0);
        TrafficEnvironment.setCustomDataProvider(this);

        // Configuração dos parâmetros segundo o cenário
        switch (scenarioName.toUpperCase()) {
            case "INTENSE":
            case "HEAVY_TRAFFIC":
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
            case "DISRUPTION_EMERGENCY":
                this.weatherCondition = 1;
                this.incidentPresent = false;
                this.speedMultiplier = 0.72; // Atrito reduzido
                this.spawnRatePerSecond = 0.35;
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

    public void setControlMode(IntersectionSafetyAuthority.ControlMode mode) {
        this.controlMode = (mode != null) ? mode : IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE;
        for (int i = 0; i < 4; i++) {
            authorities[i].setControlMode(this.controlMode);
        }
    }

    public IntersectionSafetyAuthority.ControlMode getControlMode() {
        return controlMode;
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
        SimulationClock.setSimulatedTimeSeconds(currentSimTime);
        metrics.TrafficMetricsCollector.getInstance().setSimulatedTime(currentSimTime);

        // 1. Disparo periódico de coordenação multiagente (Modo C e D) nos instantes exatos
        if (controlMode == IntersectionSafetyAuthority.ControlMode.MULTIAGENT_COORDINATED ||
            controlMode == IntersectionSafetyAuthority.ControlMode.MULTIAGENT_ML) {
            if (currentSimTime >= nextCoordinationTime) {
                agents.CentralCoordinationAgent coord = agents.CentralCoordinationAgent.getActiveInstance();
                if (coord != null) {
                    coord.triggerScheduledCoordination();
                    pumpJadeMessages(15);
                }
                nextCoordinationTime += 3.5;
            }
        }

        // 2. Disparo periódico de inferência Machine Learning (Modo D) nos instantes exatos
        if (controlMode == IntersectionSafetyAuthority.ControlMode.MULTIAGENT_ML) {
            if (currentSimTime >= nextMLInferenceTime) {
                agents.DisruptionManagementAgent mlAgent = agents.DisruptionManagementAgent.getActiveInstance();
                if (mlAgent != null) {
                    mlAgent.triggerScheduledInference();
                    pumpJadeMessages(25);
                }
                nextMLInferenceTime += 10.0;
            }
        }

        // 3. Injeção estocástica de viaturas com taxa controlada
        if (rng.nextDouble() < (spawnRatePerSecond * dt)) {
            spawnRandomVehicle();
        }

        // 4. Atualização dos Semáforos via IntersectionSafetyAuthority
        for (int i = 0; i < 4; i++) {
            int waitingNS = getWaitingCount(i, 0);
            int waitingEW = getWaitingCount(i, 1);
            authorities[i].updateTick(waitingNS, waitingEW);
        }

        // 5. Cinemática das Viaturas (Aceleração, Travagem, Stop Lines e Colisão)
        updateVehicles(dt);

        // 6. Amostragem periódica de filas (a cada 1 segundo simulado)
        if (Math.abs(currentSimTime - Math.round(currentSimTime)) < (dt / 2.0)) {
            for (int i = 0; i < 4; i++) {
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 0, getWaitingCount(i, 0));
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 1, getWaitingCount(i, 1));
            }
        }
    }

    private void pumpJadeMessages(long timeoutMs) {
        try {
            Thread.sleep(Math.min(timeoutMs, 25));
        } catch (InterruptedException ignored) {}
    }

    private void spawnRandomVehicle() {
        if (vehicles.size() >= 45) return; // Limite de saturação máxima física

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

        for (SimVehicle v : vehicles) {
            if (Math.abs(v.x - startX) < 20 && Math.abs(v.y - startY) < 20) {
                return;
            }
        }

        vehicleCounter++;
        vehicles.add(new SimVehicle("Car_" + vehicleCounter, startX, startY, 2.4, dir, laneIndex));
    }

    /**
     * Injeta deliberadamente um veículo de emergência na simulação.
     */
    public SimVehicle injectEmergencyVehicle(String id, int direction, int laneIndex) {
        double startX = 0, startY = 0;
        switch (direction) {
            case 0: startX = ROAD_X1 + 0.5 * LANE_WIDTH; startY = -20; break;
            case 1: startX = ROAD_X2 + 3.5 * LANE_WIDTH; startY = 950; break;
            case 2: startX = -20; startY = ROAD_Y1 + 3.5 * LANE_WIDTH; break;
            case 3: startX = 950; startY = ROAD_Y2 + 0.5 * LANE_WIDTH; break;
        }
        SimVehicle em = new SimVehicle(id, startX, startY, 3.2, direction, laneIndex);
        vehicles.add(em);
        return em;
    }

    private void updateVehicles(double dt) {
        List<SimVehicle> toRemove = new ArrayList<>();

        for (SimVehicle v : vehicles) {
            CitySimulationGUI.LightState light = getSignalForVehicle(v);
            double distToStop = getDistanceToTargetStopLine(v);

            double distToLeader = getDistanceToLeadingVehicle(v);
            boolean blockedByLeader = (distToLeader < 38.0);

            boolean brakeForYellow = (light == CitySimulationGUI.LightState.YELLOW && distToStop <= 55 && distToStop > 15);
            boolean brakeForRed = (light == CitySimulationGUI.LightState.RED && distToStop <= 65);
            boolean shouldBrake = blockedByLeader || brakeForYellow || brakeForRed;

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

            double oldX = v.x;
            double oldY = v.y;

            // Move veículo
            switch (v.direction) {
                case 0: v.y += v.currentSpeed; break;
                case 1: v.y -= v.currentSpeed; break;
                case 2: v.x += v.currentSpeed; break;
                case 3: v.x -= v.currentSpeed; break;
            }

            // Deteção física de travessia na linha de saída do cruzamento
            checkCrossingExitLine(v, oldX, oldY);

            // Remoção de viaturas que saíram dos limites
            if (v.x < -60 || v.x > 1050 || v.y < -60 || v.y > 1050) {
                toRemove.add(v);
            }
        }

        vehicles.removeAll(toRemove);
    }

    private void checkCrossingExitLine(SimVehicle v, double oldX, double oldY) {
        int axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
        boolean isWest = (v.x < 460);
        boolean isNorth = (v.y < 460);

        int interIdx = -1;
        boolean crossed = false;

        switch (v.direction) {
            case 0: // N->S
                if (oldY < (ROAD_Y1 + ROAD_WIDTH) && v.y >= (ROAD_Y1 + ROAD_WIDTH)) {
                    interIdx = isWest ? 0 : 1;
                    crossed = true;
                } else if (oldY < (ROAD_Y2 + ROAD_WIDTH) && v.y >= (ROAD_Y2 + ROAD_WIDTH)) {
                    interIdx = isWest ? 2 : 3;
                    crossed = true;
                }
                break;
            case 1: // S->N
                if (oldY > ROAD_Y2 && v.y <= ROAD_Y2) {
                    interIdx = isWest ? 2 : 3;
                    crossed = true;
                } else if (oldY > ROAD_Y1 && v.y <= ROAD_Y1) {
                    interIdx = isWest ? 0 : 1;
                    crossed = true;
                }
                break;
            case 2: // W->E
                if (oldX < (ROAD_X1 + ROAD_WIDTH) && v.x >= (ROAD_X1 + ROAD_WIDTH)) {
                    interIdx = isNorth ? 0 : 2;
                    crossed = true;
                } else if (oldX < (ROAD_X2 + ROAD_WIDTH) && v.x >= (ROAD_X2 + ROAD_WIDTH)) {
                    interIdx = isNorth ? 1 : 3;
                    crossed = true;
                }
                break;
            case 3: // E->W
                if (oldX > ROAD_X2 && v.x <= ROAD_X2) {
                    interIdx = isNorth ? 1 : 3;
                    crossed = true;
                } else if (oldX > ROAD_X1 && v.x <= ROAD_X1) {
                    interIdx = isNorth ? 0 : 2;
                    crossed = true;
                }
                break;
        }

        if (crossed && interIdx >= 0) {
            double wait = v.totalWaitSeconds;
            if (v.currentStopStartSeconds >= 0) {
                wait += (currentSimTime - v.currentStopStartSeconds);
            }
            metrics.TrafficMetricsCollector.getInstance().recordCrossing(v.id, interIdx, axis, wait);
            v.totalWaitSeconds = 0.0;
        }
    }

    private CitySimulationGUI.LightState getSignalForVehicle(SimVehicle v) {
        int interIdx = getTargetIntersection(v);
        int axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
        return authorities[interIdx].getLightState(axis);
    }

    public int getTargetIntersection(SimVehicle v) {
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
                case 2: if (other.x > v.x && Math.abs(other.x - v.x) < 14) dist = other.x - v.x; break;
                case 3: if (other.x < v.x && Math.abs(other.y - v.y) < 14) dist = v.x - other.x; break;
            }
            if (dist > 0 && dist < minDist) minDist = dist;
        }
        return minDist;
    }

    @Override
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

    @Override
    public int getApproachingCount(int intersectionIndex, int axis) {
        int count = 0;
        for (SimVehicle v : vehicles) {
            int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            if (targetAxis != axis) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == intersectionIndex && dist >= 0 && dist <= 220.0) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean hasEmergencyApproaching(int intersectionIndex, int axis) {
        for (SimVehicle v : vehicles) {
            if (v.id.startsWith("EM_") || v.id.startsWith("Ambulancia") || v.id.startsWith("Emergencia")) {
                int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
                if (targetAxis == axis && getTargetIntersection(v) == intersectionIndex) {
                    double dist = getDistanceToTargetStopLine(v);
                    if (dist >= 0 && dist <= 300.0) return true;
                }
            }
        }
        return false;
    }

    @Override
    public double getVehicleWaitSeconds(String vehicleId) {
        for (SimVehicle v : vehicles) {
            if (v.id.equals(vehicleId)) {
                return v.totalWaitSeconds + ((v.currentStopStartSeconds >= 0) ? (currentSimTime - v.currentStopStartSeconds) : 0);
            }
        }
        return 0.0;
    }

    @Override
    public double getAverageSpeed(int intersectionIndex) {
        int volume = 0;
        double sumSpeed = 0.0;
        for (SimVehicle v : vehicles) {
            if (getTargetIntersection(v) == intersectionIndex) {
                double dist = getDistanceToTargetStopLine(v);
                if (dist >= 0 && dist <= 220.0) {
                    volume++;
                    sumSpeed += v.currentSpeed;
                }
            }
        }
        return (volume > 0) ? Math.round((sumSpeed / volume) * 100.0) / 100.0 : (2.4 * speedMultiplier);
    }

    @Override
    public double getAverageWaitingTime(int intersectionIndex) {
        int queue = 0;
        double sumWait = 0.0;
        for (SimVehicle v : vehicles) {
            if (getTargetIntersection(v) == intersectionIndex) {
                double dist = getDistanceToTargetStopLine(v);
                if (dist >= 0 && dist <= 220.0 && v.currentSpeed < 0.2) {
                    queue++;
                    double curWait = (v.currentStopStartSeconds >= 0) ? (currentSimTime - v.currentStopStartSeconds) : 0.0;
                    sumWait += curWait;
                }
            }
        }
        return (queue > 0) ? Math.round((sumWait / queue) * 10.0) / 10.0 : 0.0;
    }

    @Override
    public int getWeatherCondition() {
        return weatherCondition;
    }

    @Override
    public int getIncidentPresent() {
        return incidentPresent ? 1 : 0;
    }

    @Override
    public int getRoadCapacity() {
        return incidentPresent ? 2 : 4;
    }

    @Override
    public double getGlobalSpeedMultiplier() {
        return speedMultiplier;
    }

    @Override
    public String getDisruptionNotice() {
        return "Cenário: " + scenarioName + " | Clima=" + weatherCondition + " | Incidente=" + incidentPresent;
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

    public void cleanup() {
        TrafficEnvironment.setCustomDataProvider(null);
        SimulationClock.disableSimulatedMode();
    }
}
