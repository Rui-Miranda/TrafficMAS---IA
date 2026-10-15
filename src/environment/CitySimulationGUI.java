package environment;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;

/**
 * Interface Gráfica Urbana com Cinemática e Navegação Avançada de Veículos:
 * - 2 faixas por sentido (4 faixas por artéria).
 * - Faixa Exterior (Direita): Exclusiva para virar à direita (TURN_RIGHT).
 * - Faixa Interior (Esquerda, junto à linha amarela): Seguir em frente (GO_STRAIGHT) e virar à esquerda (TURN_LEFT).
 * - Curvas paramétricas contínuas no interior dos cruzamentos com rotação suave do chassi.
 * - Fila de espera nos semáforos com desfasamento de segurança anti-colisão.
 */
public class CitySimulationGUI extends JFrame {

    public enum LightState { GREEN, YELLOW, RED }

    public enum ManeuverIntent { GO_STRAIGHT, TURN_RIGHT, TURN_LEFT }

    public static class VisualVehicle {
        public String id;
        public double x, y;
        public double currentSpeed = 0.0;
        public double maxSpeed = 2.4;
        public int direction; // 0: Norte->Sul, 1: Sul->Norte, 2: Oeste->Este, 3: Este->Oeste
        public double headingAngle = 0.0; // Ângulo contínuo em radianos
        public Color color;
        public int startDelayTicks = 0;
        public boolean wasStopped = false;
        public boolean isBraking = false;
        public boolean isEmergency = false;
        public ManeuverIntent intent = ManeuverIntent.GO_STRAIGHT;
        public int laneIndex = 0; // 0: Faixa exterior/direita, 1: Faixa interior/esquerda

        // Métricas individuais de telemetria e espera (Feature 8)
        public long totalWaitMillis = 0;
        public long currentStopStartMillis = 0;

        // Estado da manobra dentro do cruzamento
        public boolean inIntersection = false;
        public double turnProgress = 0.0;
        public int activeIntersection = -1;
        public double turnStartX, turnStartY;
        public double turnEndX, turnEndY;
        public double turnStartAngle, turnTargetAngle;

        public VisualVehicle(String id, double x, double y, double maxSpeed, int direction, Color color, ManeuverIntent intent, int laneIndex) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.maxSpeed = maxSpeed;
            this.currentSpeed = maxSpeed * 0.75;
            this.direction = direction;
            this.color = color;
            this.intent = intent;
            this.laneIndex = laneIndex;
            this.isEmergency = id.toLowerCase().contains("emerg") || id.toLowerCase().contains("amb") || id.toLowerCase().contains("pol");
            this.headingAngle = getInitialAngle(direction);
        }

        private static double getInitialAngle(int dir) {
            switch (dir) {
                case 0: return Math.PI / 2.0;   // Desce
                case 1: return -Math.PI / 2.0;  // Sobe
                case 2: return 0.0;             // Direita
                case 3: return Math.PI;         // Esquerda
                default: return 0.0;
            }
        }
    }

    private static CitySimulationGUI instance;

    // Estado dos semáforos dos 4 cruzamentos (0=NW, 1=NE, 2=SW, 3=SE)
    // [i][0] = Norte-Sul (Vertical), [i][1] = Este-Oeste (Horizontal)
    private final LightState[][] lights = new LightState[4][2];

    // Lista thread-safe de veículos ativos
    private final List<VisualVehicle> vehicles = new CopyOnWriteArrayList<>();

    // Dimensões do Ecrã e Grelha Viária (Avenidas Largas com 4 Faixas)
    public static final int ROAD_WIDTH = 96; // 4 Faixas de 24px cada
    public static final int LANE_WIDTH = ROAD_WIDTH / 4; // 24px por via

    public static final int ROAD_X1 = 280; // Avenida vertical Oeste
    public static final int ROAD_X2 = 640; // Avenida vertical Este
    public static final int ROAD_Y1 = 280; // Avenida horizontal Norte
    public static final int ROAD_Y2 = 640; // Avenida horizontal Sul

    // Métricas de HUD em tempo real
    private long simulationStartMillis;
    private long tickCounter = 0;
    private int fpsCounter = 0;
    private int displayedFps = 30;
    private long lastFpsCheck = System.currentTimeMillis();

    // Métricas agregadas de desempenho (Feature 8) e estado preditivo de perturbação (Feature 5)
    private long totalCompletedPasses = 0;
    private double totalWaitTimeSeconds = 0.0;
    private int preemptionCount = 0;
    private String disruptionNotice = "Condições Normais (Atrito 100%)";
    private double globalSpeedMultiplier = 1.0;

    public CitySimulationGUI() {
        this(java.awt.GraphicsEnvironment.isHeadless());
    }

    public CitySimulationGUI(boolean headless) {
        if (!headless) {
            setTitle("TrafficMAS — Intelligent Multi-Agent Urban Simulation (Kinematic)");
            setSize(980, 1010);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLocationRelativeTo(null);
            setResizable(false);
        }

        simulationStartMillis = System.currentTimeMillis();

        // Inicializar estados alternados de segurança
        for (int i = 0; i < 4; i++) {
            lights[i][0] = (i % 2 == 0) ? LightState.GREEN : LightState.RED;
            lights[i][1] = (i % 2 == 0) ? LightState.RED : LightState.GREEN;
        }

        if (!headless) {
            CityPanel panel = new CityPanel();
            panel.setDoubleBuffered(true);
            add(panel);

            // Loop de simulação a ~30 FPS
            Timer timer = new Timer(30, e -> {
                tickCounter++;
                calculateFps();
                updatePositions();
                panel.repaint();
            });
            timer.start();
        }
    }

    private void calculateFps() {
        fpsCounter++;
        long now = System.currentTimeMillis();
        if (now - lastFpsCheck >= 1000) {
            displayedFps = fpsCounter;
            fpsCounter = 0;
            lastFpsCheck = now;
        }
    }

    public static synchronized CitySimulationGUI peekInstance() {
        return instance;
    }

    public static synchronized CitySimulationGUI getInstance() {
        if (instance == null) {
            boolean headless = java.awt.GraphicsEnvironment.isHeadless();
            instance = new CitySimulationGUI(headless);
            if (!headless) {
                SwingUtilities.invokeLater(() -> instance.setVisible(true));
            }
        }
        return instance;
    }

    public synchronized void setTrafficLight(int intersectionIndex, int axis, LightState state) {
        if (intersectionIndex >= 0 && intersectionIndex < 4 && axis >= 0 && axis <= 1) {
            lights[intersectionIndex][axis] = state;
            IntersectionSafetyAuthority.assertSafetyInvariant(lights[intersectionIndex][0], lights[intersectionIndex][1]);
        }
    }

    public void addVehicle(String id, int direction, Color color) {
        int count = vehicles.size();
        ManeuverIntent intent;
        if (count % 3 == 0) intent = ManeuverIntent.TURN_RIGHT;
        else if (count % 3 == 1) intent = ManeuverIntent.GO_STRAIGHT;
        else intent = ManeuverIntent.TURN_LEFT;

        addVehicle(id, direction, color, intent);
    }

    public void addVehicle(String id, int direction, Color color, ManeuverIntent intent) {
        double startX = 0, startY = 0;
        double maxSpeed = 2.4;

        // Regra de Trânsito:
        // Faixa Mais à Direita (laneIndex 0): Exclusiva para TURN_RIGHT.
        // Faixa da Esquerda (laneIndex 1): GO_STRAIGHT ou TURN_LEFT.
        int laneIndex = (intent == ManeuverIntent.TURN_RIGHT) ? 0 : 1;

        switch (direction) {
            case 0: // Norte -> Sul (Desce na metade esquerda de X1)
                startX = (laneIndex == 0) ? (ROAD_X1 + 0.5 * LANE_WIDTH) : (ROAD_X1 + 1.5 * LANE_WIDTH);
                startY = -30;
                break;
            case 1: // Sul -> Norte (Sobe na metade direita de X2)
                startX = (laneIndex == 0) ? (ROAD_X2 + 3.5 * LANE_WIDTH) : (ROAD_X2 + 2.5 * LANE_WIDTH);
                startY = 1000;
                break;
            case 2: // Oeste -> Este (Vai para a direita na metade inferior de Y1)
                startX = -30;
                startY = (laneIndex == 0) ? (ROAD_Y1 + 3.5 * LANE_WIDTH) : (ROAD_Y1 + 2.5 * LANE_WIDTH);
                break;
            case 3: // Este -> Oeste (Vai para a esquerda na metade superior de Y2)
                startX = 1000;
                startY = (laneIndex == 0) ? (ROAD_Y2 + 0.5 * LANE_WIDTH) : (ROAD_Y2 + 1.5 * LANE_WIDTH);
                break;
        }

        vehicles.add(new VisualVehicle(id, startX, startY, maxSpeed, direction, color, intent, laneIndex));
    }

    /* =========================================================================
     * MÉTODOS DE PERCEÇÃO SENSORIAL E ATUAÇÃO PARA OS AGENTES (Features 1, 6, 7, 8)
     * ========================================================================= */

    public synchronized int getTargetIntersection(VisualVehicle v) {
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

    public synchronized double getDistanceToTargetStopLine(VisualVehicle v) {
        switch (v.direction) {
            case 0: return (v.y < ROAD_Y1 - 2) ? ((ROAD_Y1 - 2) - v.y) : ((ROAD_Y2 - 2) - v.y);
            case 1: return (v.y > ROAD_Y2 + ROAD_WIDTH + 2) ? (v.y - (ROAD_Y2 + ROAD_WIDTH + 2)) : (v.y - (ROAD_Y1 + ROAD_WIDTH + 2));
            case 2: return (v.x < ROAD_X1 - 2) ? ((ROAD_X1 - 2) - v.x) : ((ROAD_X2 - 2) - v.x);
            case 3: return (v.x > ROAD_X2 + ROAD_WIDTH + 2) ? (v.x - (ROAD_X2 + ROAD_WIDTH + 2)) : (v.x - (ROAD_X1 + ROAD_WIDTH + 2));
            default: return 9999.0;
        }
    }

    public synchronized int getApproachingCount(int interIdx, int axis) {
        int count = 0;
        for (VisualVehicle v : vehicles) {
            if (v.inIntersection) continue;
            int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            if (targetAxis != axis) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 220.0) {
                count++;
            }
        }
        return count;
    }

    public synchronized int getWaitingCount(int interIdx, int axis) {
        int count = 0;
        for (VisualVehicle v : vehicles) {
            if (v.inIntersection) continue;
            int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            if (targetAxis != axis) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 220.0 && v.currentSpeed < 0.2) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean hasEmergencyApproaching(int interIdx, int axis) {
        for (VisualVehicle v : vehicles) {
            if (!v.isEmergency || v.inIntersection) continue;
            int targetAxis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            if (targetAxis != axis) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 260.0) {
                return true;
            }
        }
        return false;
    }

    public synchronized double getVehicleWaitSeconds(String vehicleId) {
        for (VisualVehicle v : vehicles) {
            if (v.id.equals(vehicleId)) {
                if (v.currentStopStartMillis > 0) {
                    return (System.currentTimeMillis() - v.currentStopStartMillis) / 1000.0;
                }
                return 0.0;
            }
        }
        return 0.0;
    }

    public synchronized VisualVehicle getVisualVehicle(String vehicleId) {
        for (VisualVehicle v : vehicles) {
            if (v.id.equals(vehicleId)) return v;
        }
        return null;
    }

    public synchronized void triggerEmergencyPreemption(int interIdx, int axis) {
        if (interIdx >= 0 && interIdx < 4 && axis >= 0 && axis <= 1) {
            preemptionCount++;
            IntersectionSafetyAuthority.getAuthority(interIdx).requestEmergency("MANUAL_EMERGENCY", axis, null);
            System.out.println("[CitySimulationGUI] Preempção de emergência encaminhada para a Autoridade de Segurança do Cruzamento C" + (interIdx + 1) + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");
        }
    }

    public synchronized void setDisruptionNotice(String notice, double speedFactor) {
        this.disruptionNotice = notice;
        this.globalSpeedMultiplier = Math.max(0.4, Math.min(1.2, speedFactor));
    }

    public synchronized String getDisruptionNotice() {
        return disruptionNotice;
    }

    public synchronized double getGlobalSpeedMultiplier() {
        return globalSpeedMultiplier;
    }

    public synchronized double getAverageWaitSeconds() {
        if (totalCompletedPasses == 0) {
            long stopped = vehicles.stream().filter(v -> v.currentSpeed < 0.2).count();
            return stopped * 1.5;
        }
        return Math.round((totalWaitTimeSeconds / totalCompletedPasses) * 10.0) / 10.0;
    }

    public synchronized long getTotalCompletedPasses() {
        return totalCompletedPasses;
    }

    public synchronized int getPreemptionCount() {
        return preemptionCount;
    }

    private synchronized void recordVehiclePass(VisualVehicle v) {
        totalCompletedPasses++;
        if (v.currentStopStartMillis > 0) {
            v.totalWaitMillis += (System.currentTimeMillis() - v.currentStopStartMillis);
            v.currentStopStartMillis = 0;
        }
        totalWaitTimeSeconds += (v.totalWaitMillis / 1000.0);
        v.totalWaitMillis = 0;
    }

    private void updatePositions() {
        for (VisualVehicle v : vehicles) {
            // 1. Tratamento de Manobra Ativa no Interior do Cruzamento
            if (v.inIntersection) {
                updateIntersectionManeuver(v);
                continue;
            }

            LightState nextLight = LightState.GREEN;
            double distToStopLine = 9999;
            int nextIntersectionIdx = -1;

            // 2. Determinar a distância exata até à Stop Line da próxima interseção
            boolean isWest = (v.x < 460);
            boolean isNorth = (v.y < 460);
            switch (v.direction) {
                case 0: // N->S
                    if (v.y < ROAD_Y1 - 2) {
                        distToStopLine = (ROAD_Y1 - 2) - v.y;
                        nextIntersectionIdx = isWest ? 0 : 1;
                        nextLight = lights[nextIntersectionIdx][0];
                    } else if (v.y < ROAD_Y2 - 2) {
                        distToStopLine = (ROAD_Y2 - 2) - v.y;
                        nextIntersectionIdx = isWest ? 2 : 3;
                        nextLight = lights[nextIntersectionIdx][0];
                    }
                    break;
                case 1: // S->N
                    if (v.y > ROAD_Y2 + ROAD_WIDTH + 2) {
                        distToStopLine = v.y - (ROAD_Y2 + ROAD_WIDTH + 2);
                        nextIntersectionIdx = isWest ? 2 : 3;
                        nextLight = lights[nextIntersectionIdx][0];
                    } else if (v.y > ROAD_Y1 + ROAD_WIDTH + 2) {
                        distToStopLine = v.y - (ROAD_Y1 + ROAD_WIDTH + 2);
                        nextIntersectionIdx = isWest ? 0 : 1;
                        nextLight = lights[nextIntersectionIdx][0];
                    }
                    break;
                case 2: // W->E
                    if (v.x < ROAD_X1 - 2) {
                        distToStopLine = (ROAD_X1 - 2) - v.x;
                        nextIntersectionIdx = isNorth ? 0 : 2;
                        nextLight = lights[nextIntersectionIdx][1];
                    } else if (v.x < ROAD_X2 - 2) {
                        distToStopLine = (ROAD_X2 - 2) - v.x;
                        nextIntersectionIdx = isNorth ? 1 : 3;
                        nextLight = lights[nextIntersectionIdx][1];
                    }
                    break;
                case 3: // E->W
                    if (v.x > ROAD_X2 + ROAD_WIDTH + 2) {
                        distToStopLine = v.x - (ROAD_X2 + ROAD_WIDTH + 2);
                        nextIntersectionIdx = isNorth ? 1 : 3;
                        nextLight = lights[nextIntersectionIdx][1];
                    } else if (v.x > ROAD_X1 + ROAD_WIDTH + 2) {
                        distToStopLine = v.x - (ROAD_X1 + ROAD_WIDTH + 2);
                        nextIntersectionIdx = isNorth ? 0 : 2;
                        nextLight = lights[nextIntersectionIdx][1];
                    }
                    break;
            }

            // 3. Deteção de Veículo da Frente na Mesma Faixa (Fila de Espera Anti-Colisão)
            double distToLeader = getDistanceToLeadingVehicle(v);
            boolean blockedByLeader = (distToLeader < 38.0); // Margem de segurança de ~38px

            // 4. Decisão de Travagem por Semáforo ou Fila
            boolean shouldBrakeForYellow = (nextLight == LightState.YELLOW && distToStopLine <= 55 && distToStopLine > 15);
            boolean shouldBrakeForRed = (nextLight == LightState.RED && distToStopLine <= 65);

            // Viatura de emergência abranda de forma cautelosa se o semáforo ainda não concedeu verde
            boolean isEmergencyBraking = v.isEmergency && (shouldBrakeForRed || (shouldBrakeForYellow && distToStopLine <= 40));
            boolean shouldBrake = blockedByLeader || (v.isEmergency ? isEmergencyBraking : (shouldBrakeForYellow || shouldBrakeForRed));

            if (shouldBrake) {
                v.isBraking = true;
                double limitDist = blockedByLeader ? (distToLeader - 32.0) : distToStopLine;

                if (limitDist <= 2.0) {
                    v.currentSpeed = 0.0;
                    v.wasStopped = true;
                    if (v.currentStopStartMillis == 0) {
                        v.currentStopStartMillis = System.currentTimeMillis();
                    }
                } else {
                    double targetSpeed = Math.max(0.0, (limitDist / 50.0) * (v.maxSpeed * globalSpeedMultiplier));
                    v.currentSpeed = Math.max(0.0, v.currentSpeed - 0.14);
                    if (v.currentSpeed > targetSpeed) v.currentSpeed = targetSpeed;
                }
            } else {
                v.isBraking = false;
                if (v.currentStopStartMillis > 0) {
                    v.totalWaitMillis += (System.currentTimeMillis() - v.currentStopStartMillis);
                    v.currentStopStartMillis = 0;
                }
                double effectiveMaxSpeed = v.maxSpeed * globalSpeedMultiplier;
                if (v.wasStopped) {
                    if (v.startDelayTicks < 14) {
                        v.startDelayTicks++;
                        v.currentSpeed = 0.0;
                    } else {
                        v.currentSpeed = Math.min(effectiveMaxSpeed, v.currentSpeed + 0.06);
                        if (v.currentSpeed >= effectiveMaxSpeed * 0.8) {
                            v.wasStopped = false;
                            v.startDelayTicks = 0;
                        }
                    }
                } else {
                    v.currentSpeed = Math.min(effectiveMaxSpeed, v.currentSpeed + 0.05);
                }
            }

            // 5. Início de Manobra ao Transpor a Stop Line em Sinal Verde
            if (distToStopLine <= 2.0 && nextLight == LightState.GREEN && !blockedByLeader) {
                if (v.intent == ManeuverIntent.TURN_RIGHT || v.intent == ManeuverIntent.TURN_LEFT) {
                    initiateIntersectionTurn(v, nextIntersectionIdx);
                    continue;
                }
            }

            // 6. Translação Retilínea
            switch (v.direction) {
                case 0: v.y += v.currentSpeed; v.headingAngle = Math.PI / 2.0; break;
                case 1: v.y -= v.currentSpeed; v.headingAngle = -Math.PI / 2.0; break;
                case 2: v.x += v.currentSpeed; v.headingAngle = 0.0; break;
                case 3: v.x -= v.currentSpeed; v.headingAngle = Math.PI; break;
            }

            // Wrap-around contínuo com registo de telemetria
            if (v.y > 1030) { v.y = -30; v.wasStopped = false; recordVehiclePass(v); }
            if (v.y < -40) { v.y = 1010; v.wasStopped = false; recordVehiclePass(v); }
            if (v.x > 1030) { v.x = -30; v.wasStopped = false; recordVehiclePass(v); }
            if (v.x < -40) { v.x = 1010; v.wasStopped = false; recordVehiclePass(v); }
        }
    }

    private double getDistanceToLeadingVehicle(VisualVehicle v) {
        double minDist = 9999.0;
        for (VisualVehicle other : vehicles) {
            if (other == v || other.direction != v.direction || other.laneIndex != v.laneIndex || other.inIntersection) continue;

            // Verificar se estão no mesmo corredor viário (evita colisões falsas entre vias paralelas)
            if (v.direction == 0 || v.direction == 1) {
                if (Math.abs(other.x - v.x) > 15.0) continue;
            } else {
                if (Math.abs(other.y - v.y) > 15.0) continue;
            }

            double d = 9999.0;
            switch (v.direction) {
                case 0: // N->S
                    if (other.y > v.y) d = other.y - v.y;
                    break;
                case 1: // S->N
                    if (other.y < v.y) d = v.y - other.y;
                    break;
                case 2: // W->E
                    if (other.x > v.x) d = other.x - v.x;
                    break;
                case 3: // E->W
                    if (other.x < v.x) d = v.x - other.x;
                    break;
            }
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    private void initiateIntersectionTurn(VisualVehicle v, int interIdx) {
        if (interIdx < 0 || interIdx > 3) return;

        int ix = (interIdx == 0 || interIdx == 2) ? ROAD_X1 : ROAD_X2;
        int iy = (interIdx == 0 || interIdx == 1) ? ROAD_Y1 : ROAD_Y2;

        v.inIntersection = true;
        v.turnProgress = 0.0;
        v.activeIntersection = interIdx;
        v.turnStartX = v.x;
        v.turnStartY = v.y;
        v.turnStartAngle = v.headingAngle;

        // Determinar coordenadas de saída e direção final conforme a manobra:
        if (v.intent == ManeuverIntent.TURN_RIGHT) {
            // Curva à Direita: Faixa exterior -> Faixa exterior da nova via (pequeno raio)
            switch (v.direction) {
                case 0: // N->S virando para Oeste: sai na metade superior (Y + 0.5 * LANE)
                    v.turnEndX = ix - 2;
                    v.turnEndY = iy + 0.5 * LANE_WIDTH;
                    v.turnTargetAngle = Math.PI; // E->W
                    break;
                case 1: // S->N virando para Este: sai na metade inferior (Y + 3.5 * LANE)
                    v.turnEndX = ix + ROAD_WIDTH + 2;
                    v.turnEndY = iy + 3.5 * LANE_WIDTH;
                    v.turnTargetAngle = 0.0; // W->E
                    break;
                case 2: // W->E virando para Sul: sai na metade esquerda (X + 0.5 * LANE)
                    v.turnEndX = ix + 0.5 * LANE_WIDTH;
                    v.turnEndY = iy + ROAD_WIDTH + 2;
                    v.turnTargetAngle = Math.PI / 2.0; // N->S
                    break;
                case 3: // E->W virando para Norte: sai na metade direita (X + 3.5 * LANE)
                    v.turnEndX = ix + 3.5 * LANE_WIDTH;
                    v.turnEndY = iy - 2;
                    v.turnTargetAngle = -Math.PI / 2.0; // S->N
                    break;
            }
        } else if (v.intent == ManeuverIntent.TURN_LEFT) {
            // Curva à Esquerda: Faixa interior -> Faixa interior da nova via (raio largo em torno do centro)
            switch (v.direction) {
                case 0: // N->S virando para Este: sai na metade inferior (Y + 2.5 * LANE)
                    v.turnEndX = ix + ROAD_WIDTH + 2;
                    v.turnEndY = iy + 2.5 * LANE_WIDTH;
                    v.turnTargetAngle = 0.0;
                    break;
                case 1: // S->N virando para Oeste: sai na metade superior (Y + 1.5 * LANE)
                    v.turnEndX = ix - 2;
                    v.turnEndY = iy + 1.5 * LANE_WIDTH;
                    v.turnTargetAngle = Math.PI;
                    break;
                case 2: // W->E virando para Norte: sai na metade direita (X + 2.5 * LANE)
                    v.turnEndX = ix + 2.5 * LANE_WIDTH;
                    v.turnEndY = iy - 2;
                    v.turnTargetAngle = -Math.PI / 2.0;
                    break;
                case 3: // E->W virando para Sul: sai na metade esquerda (X + 1.5 * LANE)
                    v.turnEndX = ix + 1.5 * LANE_WIDTH;
                    v.turnEndY = iy + ROAD_WIDTH + 2;
                    v.turnTargetAngle = Math.PI / 2.0;
                    break;
            }
        }
    }

    private void updateIntersectionManeuver(VisualVehicle v) {
        // Velocidade suave ao descrever a curva
        double curveSpeed = Math.max(1.2, v.currentSpeed * 0.85);
        v.turnProgress += (curveSpeed / 45.0);

        if (v.turnProgress >= 1.0) {
            // Conclusão da manobra: restaura movimento retilíneo na nova direção
            v.inIntersection = false;
            v.x = v.turnEndX;
            v.y = v.turnEndY;
            v.headingAngle = v.turnTargetAngle;

            // Atualiza a nova direção de marcha
            if (Math.abs(v.turnTargetAngle - 0.0) < 0.1) v.direction = 2; // W->E
            else if (Math.abs(v.turnTargetAngle - Math.PI / 2.0) < 0.1) v.direction = 0; // N->S
            else if (Math.abs(v.turnTargetAngle - Math.PI) < 0.1 || Math.abs(v.turnTargetAngle + Math.PI) < 0.1) v.direction = 3; // E->W
            else if (Math.abs(v.turnTargetAngle + Math.PI / 2.0) < 0.1) v.direction = 1; // S->N

            v.intent = ManeuverIntent.GO_STRAIGHT; // Próximo cruzamento segue reto
            return;
        }

        // Interpolação suave de Bézier Quadrática: P(t) = (1-t)^2 P0 + 2(1-t)t P1 + t^2 P2
        double t = v.turnProgress;
        int ix = (v.activeIntersection == 0 || v.activeIntersection == 2) ? ROAD_X1 : ROAD_X2;
        int iy = (v.activeIntersection == 0 || v.activeIntersection == 1) ? ROAD_Y1 : ROAD_Y2;

        // Ponto de controlo P1 varia se a curva for fechada (à direita) ou aberta (à esquerda)
        double ctrlX, ctrlY;
        if (v.intent == ManeuverIntent.TURN_RIGHT) {
            if (v.direction == 0 || v.direction == 1) {
                ctrlX = v.turnStartX;
                ctrlY = v.turnEndY;
            } else {
                ctrlX = v.turnEndX;
                ctrlY = v.turnStartY;
            }
        } else {
            ctrlX = ix + ROAD_WIDTH / 2.0;
            ctrlY = iy + ROAD_WIDTH / 2.0;
        }

        double nextX = Math.pow(1 - t, 2) * v.turnStartX + 2 * (1 - t) * t * ctrlX + Math.pow(t, 2) * v.turnEndX;
        double nextY = Math.pow(1 - t, 2) * v.turnStartY + 2 * (1 - t) * t * ctrlY + Math.pow(t, 2) * v.turnEndY;

        // Rotação suave contínua alinhada com a tangente de deslocamento
        double dx = nextX - v.x;
        double dy = nextY - v.y;
        if (Math.hypot(dx, dy) > 0.01) {
            v.headingAngle = Math.atan2(dy, dx);
        }

        v.x = nextX;
        v.y = nextY;
    }

    private class CityPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            int width = getWidth();
            int height = getHeight();

            // 1. Terreno Urbano Claro Diurno
            g2.setColor(new Color(230, 234, 240));
            g2.fillRect(0, 0, width, height);

            // 2. Quarteirões Urbanos com Zonas Verdes e Edifícios
            drawUrbanBlocks(g2);

            // 3. Avenidas de 4 Faixas com Asfalto Limpo no Centro das Interseções
            drawRoadsAndMarkings(g2, width, height);

            // 4. Semáforos montados no bordo direito de cada fluxo com postes e luzes
            drawIntersectionsAndLights(g2);

            // 5. Veículos com Rotação Suave Dinâmica e Luzes
            drawVehicles(g2);

            // 6. HUD / Dashboard Superior
            drawHUD(g2, width);
        }

        private void drawUrbanBlocks(Graphics2D g2) {
            int[][] blockBounds = {
                { 20, 20, ROAD_X1 - 35, ROAD_Y1 - 35 },
                { ROAD_X1 + ROAD_WIDTH + 15, 20, ROAD_X2 - (ROAD_X1 + ROAD_WIDTH) - 30, ROAD_Y1 - 35 },
                { ROAD_X2 + ROAD_WIDTH + 15, 20, 240, ROAD_Y1 - 35 },

                { 20, ROAD_Y1 + ROAD_WIDTH + 15, ROAD_X1 - 35, ROAD_Y2 - (ROAD_Y1 + ROAD_WIDTH) - 30 },
                { ROAD_X1 + ROAD_WIDTH + 15, ROAD_Y1 + ROAD_WIDTH + 15, ROAD_X2 - (ROAD_X1 + ROAD_WIDTH) - 30, ROAD_Y2 - (ROAD_Y1 + ROAD_WIDTH) - 30 },
                { ROAD_X2 + ROAD_WIDTH + 15, ROAD_Y1 + ROAD_WIDTH + 15, 240, ROAD_Y2 - (ROAD_Y1 + ROAD_WIDTH) - 30 },

                { 20, ROAD_Y2 + ROAD_WIDTH + 15, ROAD_X1 - 35, 240 },
                { ROAD_X1 + ROAD_WIDTH + 15, ROAD_Y2 + ROAD_WIDTH + 15, ROAD_X2 - (ROAD_X1 + ROAD_WIDTH) - 30, 240 },
                { ROAD_X2 + ROAD_WIDTH + 15, ROAD_Y2 + ROAD_WIDTH + 15, 240, 240 }
            };

            for (int i = 0; i < blockBounds.length; i++) {
                int bx = blockBounds[i][0];
                int by = blockBounds[i][1];
                int bw = blockBounds[i][2];
                int bh = blockBounds[i][3];

                g2.setColor(new Color(212, 218, 226));
                g2.fill(new RoundRectangle2D.Double(bx + 2, by + 3, bw, bh, 16, 16));

                g2.setColor(new Color(242, 245, 248));
                g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 16, 16));

                g2.setColor(new Color(195, 202, 212));
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 16, 16));

                if (i == 4) {
                    g2.setColor(new Color(130, 195, 140));
                    g2.fill(new RoundRectangle2D.Double(bx + 14, by + 14, bw - 28, bh - 28, 14, 14));
                    g2.setColor(new Color(110, 180, 225));
                    g2.fillOval(bx + bw / 2 - 25, by + bh / 2 - 25, 50, 50);
                    drawTree(g2, bx + 30, by + 30);
                    drawTree(g2, bx + bw - 40, by + 30);
                    drawTree(g2, bx + 30, by + bh - 40);
                    drawTree(g2, bx + bw - 40, by + bh - 40);
                } else {
                    drawBuilding(g2, bx + 16, by + 16, bw / 2 - 22, bh - 32, new Color(180, 188, 200));
                    drawBuilding(g2, bx + bw / 2 + 6, by + 16, bw / 2 - 22, bh - 32, new Color(165, 175, 188));
                }
            }
        }

        private void drawBuilding(Graphics2D g2, int x, int y, int w, int h, Color topColor) {
            if (w <= 0 || h <= 0) return;
            g2.setColor(new Color(160, 170, 185, 90));
            g2.fill(new RoundRectangle2D.Double(x + 2, y + 3, w, h, 8, 8));
            g2.setColor(topColor);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
            g2.setColor(topColor.darker());
            g2.setStroke(new BasicStroke(1.0f));
            g2.draw(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        }

        private void drawTree(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(80, 145, 90));
            g2.fillOval(x + 1, y + 2, 16, 16);
            g2.setColor(new Color(95, 175, 105));
            g2.fillOval(x, y, 16, 16);
        }

        private void drawRoadsAndMarkings(Graphics2D g2, int width, int height) {
            Color asphalt = new Color(52, 55, 62);
            g2.setColor(asphalt);
            g2.fillRect(ROAD_X1, 0, ROAD_WIDTH, height);
            g2.fillRect(ROAD_X2, 0, ROAD_WIDTH, height);
            g2.fillRect(0, ROAD_Y1, width, ROAD_WIDTH);
            g2.fillRect(0, ROAD_Y2, width, ROAD_WIDTH);

            // Linhas de Berma
            g2.setColor(new Color(230, 235, 245, 180));
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawLine(ROAD_X1, 0, ROAD_X1, height);
            g2.drawLine(ROAD_X1 + ROAD_WIDTH, 0, ROAD_X1 + ROAD_WIDTH, height);
            g2.drawLine(ROAD_X2, 0, ROAD_X2, height);
            g2.drawLine(ROAD_X2 + ROAD_WIDTH, 0, ROAD_X2 + ROAD_WIDTH, height);
            g2.drawLine(0, ROAD_Y1, width, ROAD_Y1);
            g2.drawLine(0, ROAD_Y1 + ROAD_WIDTH, width, ROAD_Y1 + ROAD_WIDTH);
            g2.drawLine(0, ROAD_Y2, width, ROAD_Y2);
            g2.drawLine(0, ROAD_Y2 + ROAD_WIDTH, width, ROAD_Y2 + ROAD_WIDTH);

            // Eixo Separador Central Duplo Amarelo
            g2.setColor(new Color(245, 195, 50));
            g2.setStroke(new BasicStroke(1.8f));
            int midX1 = ROAD_X1 + ROAD_WIDTH / 2;
            int midX2 = ROAD_X2 + ROAD_WIDTH / 2;
            int midY1 = ROAD_Y1 + ROAD_WIDTH / 2;
            int midY2 = ROAD_Y2 + ROAD_WIDTH / 2;

            g2.drawLine(midX1 - 2, 0, midX1 - 2, height);
            g2.drawLine(midX1 + 2, 0, midX1 + 2, height);
            g2.drawLine(midX2 - 2, 0, midX2 - 2, height);
            g2.drawLine(midX2 + 2, 0, midX2 + 2, height);
            g2.drawLine(0, midY1 - 2, width, midY1 - 2);
            g2.drawLine(0, midY1 + 2, width, midY1 + 2);
            g2.drawLine(0, midY2 - 2, width, midY2 - 2);
            g2.drawLine(0, midY2 + 2, width, midY2 + 2);

            // Divisores de Faixas do Mesmo Sentido (Tracejado Branco)
            g2.setColor(new Color(240, 240, 245, 200));
            Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{8, 8}, 0);
            g2.setStroke(dashed);

            g2.drawLine(ROAD_X1 + LANE_WIDTH, 0, ROAD_X1 + LANE_WIDTH, height);
            g2.drawLine(ROAD_X1 + 3 * LANE_WIDTH, 0, ROAD_X1 + 3 * LANE_WIDTH, height);
            g2.drawLine(ROAD_X2 + LANE_WIDTH, 0, ROAD_X2 + LANE_WIDTH, height);
            g2.drawLine(ROAD_X2 + 3 * LANE_WIDTH, 0, ROAD_X2 + 3 * LANE_WIDTH, height);

            g2.drawLine(0, ROAD_Y1 + LANE_WIDTH, width, ROAD_Y1 + LANE_WIDTH);
            g2.drawLine(0, ROAD_Y1 + 3 * LANE_WIDTH, width, ROAD_Y1 + 3 * LANE_WIDTH);
            g2.drawLine(0, ROAD_Y2 + LANE_WIDTH, width, ROAD_Y2 + LANE_WIDTH);
            g2.drawLine(0, ROAD_Y2 + 3 * LANE_WIDTH, width, ROAD_Y2 + 3 * LANE_WIDTH);

            // Limpar o miolo dos 4 cruzamentos (sem linhas centrais)
            int[] xCross = {ROAD_X1, ROAD_X2};
            int[] yCross = {ROAD_Y1, ROAD_Y2};

            g2.setColor(asphalt);
            for (int ix : xCross) {
                for (int iy : yCross) {
                    g2.fillRect(ix, iy, ROAD_WIDTH, ROAD_WIDTH);
                }
            }

            // Stop Lines Brancas apenas nas faixas de entrada
            g2.setStroke(new BasicStroke(3.2f));
            g2.setColor(new Color(250, 250, 255));

            for (int ix : xCross) {
                for (int iy : yCross) {
                    // Entrada Norte (desce na metade esquerda)
                    g2.drawLine(ix, iy - 2, ix + 2 * LANE_WIDTH, iy - 2);
                    // Entrada Sul (sobe na metade direita)
                    g2.drawLine(ix + 2 * LANE_WIDTH, iy + ROAD_WIDTH + 2, ix + ROAD_WIDTH, iy + ROAD_WIDTH + 2);
                    // Entrada Oeste (vai para Este na metade inferior)
                    g2.drawLine(ix - 2, iy + 2 * LANE_WIDTH, ix - 2, iy + ROAD_WIDTH);
                    // Entrada Este (vai para Oeste na metade superior)
                    g2.drawLine(ix + ROAD_WIDTH + 2, iy, ix + ROAD_WIDTH + 2, iy + 2 * LANE_WIDTH);
                }
            }
        }

        private void drawIntersectionsAndLights(Graphics2D g2) {
            Object[][] intersections = {
                { ROAD_X1, ROAD_Y1, 0, "C1: NW" },
                { ROAD_X2, ROAD_Y1, 1, "C2: NE" },
                { ROAD_X1, ROAD_Y2, 2, "C3: SW" },
                { ROAD_X2, ROAD_Y2, 3, "C4: SE" }
            };

            for (Object[] inter : intersections) {
                int ix = (Integer) inter[0];
                int iy = (Integer) inter[1];
                int idx = (Integer) inter[2];
                String name = (String) inter[3];

                LightState nsState = lights[idx][0];
                LightState ewState = lights[idx][1];

                // 4 Semáforos montados rigorosamente no lado direito da marcha com ordem canónica
                drawTrafficLight(g2, ix - 14, iy - 24, 0, nsState);
                drawTrafficLight(g2, ix + ROAD_WIDTH + 14, iy + ROAD_WIDTH + 24, 1, nsState);
                drawTrafficLight(g2, ix - 24, iy + ROAD_WIDTH + 14, 2, ewState);
                drawTrafficLight(g2, ix + ROAD_WIDTH + 24, iy - 14, 3, ewState);

                g2.setColor(new Color(255, 255, 255, 180));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                g2.drawString(name, ix + ROAD_WIDTH / 2 - 16, iy + ROAD_WIDTH / 2 + 4);
            }
        }

        private void drawTrafficLight(Graphics2D g2, double x, double y, int approachDirection, LightState state) {
            AffineTransform oldTransform = g2.getTransform();

            double rotAngle = 0;
            switch (approachDirection) {
                case 0: rotAngle = Math.PI; break;        // N->S
                case 1: rotAngle = 0; break;              // S->N
                case 2: rotAngle = Math.PI / 2.0; break;  // W->E
                case 3: rotAngle = -Math.PI / 2.0; break; // E->W
            }

            g2.translate(x, y);
            g2.rotate(rotAngle);

            g2.setColor(new Color(110, 115, 125));
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(0, 18, 0, 26);
            g2.setColor(new Color(60, 65, 75));
            g2.fillOval(-3, 24, 6, 6);

            int bw = 13;
            int bh = 33;
            int bx = -bw / 2;
            int by = -bh / 2;

            g2.setColor(new Color(24, 27, 33));
            g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 6, 6));
            g2.setColor(new Color(85, 92, 105));
            g2.setStroke(new BasicStroke(1.0f));
            g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 6, 6));

            drawBulb(g2, bx + 2, by + 3, state == LightState.RED, Color.RED);
            drawBulb(g2, bx + 2, by + 12, state == LightState.YELLOW, Color.YELLOW);
            drawBulb(g2, bx + 2, by + 21, state == LightState.GREEN, new Color(40, 225, 100));

            g2.setTransform(oldTransform);
        }

        private void drawBulb(Graphics2D g2, int bx, int by, boolean active, Color c) {
            int size = 8;
            if (active) {
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                g2.fillOval(bx - 3, by - 3, size + 6, size + 6);
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 130));
                g2.fillOval(bx - 1, by - 1, size + 2, size + 2);
                g2.setColor(c);
                g2.fillOval(bx, by, size, size);
            } else {
                g2.setColor(new Color(c.getRed() / 4, c.getGreen() / 4, c.getBlue() / 4, 210));
                g2.fillOval(bx, by, size, size);
            }
        }

        private void drawVehicles(Graphics2D g2) {
            for (VisualVehicle v : vehicles) {
                AffineTransform old = g2.getTransform();

                int vLength = 26;
                int vWidth = 14;

                g2.translate(v.x, v.y);
                g2.rotate(v.headingAngle); // Rotação dinâmica contínua alinhada à cinemática!

                // Sombra suave
                g2.setColor(new Color(0, 0, 0, 90));
                g2.fill(new RoundRectangle2D.Double(-vLength / 2.0 + 2, -vWidth / 2.0 + 3, vLength, vWidth, 6, 6));

                // Carroçaria
                g2.setColor(v.color);
                g2.fill(new RoundRectangle2D.Double(-vLength / 2.0, -vWidth / 2.0, vLength, vWidth, 6, 6));

                g2.setColor(new Color(255, 255, 255, 110));
                g2.setStroke(new BasicStroke(1.0f));
                g2.draw(new RoundRectangle2D.Double(-vLength / 2.0, -vWidth / 2.0, vLength, vWidth, 6, 6));

                // Vidros / Para-brisas
                g2.setColor(new Color(25, 30, 40));
                g2.fillRoundRect(2, -vWidth / 2 + 2, 6, vWidth - 4, 3, 3);
                g2.fillRoundRect(-vLength / 2 + 4, -vWidth / 2 + 2, 4, vWidth - 4, 2, 2);

                // Faróis discretos
                g2.setColor(new Color(245, 245, 230));
                g2.fillRect(vLength / 2 - 2, -vWidth / 2 + 2, 2, 3);
                g2.fillRect(vLength / 2 - 2, vWidth / 2 - 5, 2, 3);

                // Luzes de travão
                if (v.isBraking || v.currentSpeed < 0.2) {
                    g2.setColor(new Color(235, 40, 40));
                    g2.fillRect(-vLength / 2, -vWidth / 2 + 2, 3, 3);
                    g2.fillRect(-vLength / 2, vWidth / 2 - 5, 3, 3);
                } else {
                    g2.setColor(new Color(130, 40, 40));
                    g2.fillRect(-vLength / 2, -vWidth / 2 + 2, 2, 2);
                    g2.fillRect(-vLength / 2, vWidth / 2 - 4, 2, 2);
                }

                // Sirene de emergência
                if (v.isEmergency) {
                    boolean strobe = (tickCounter / 6) % 2 == 0;
                    Color sirenColor = strobe ? new Color(0, 140, 255) : new Color(255, 30, 30);
                    g2.setColor(sirenColor);
                    g2.fillOval(-2, -3, 6, 6);
                }

                g2.setTransform(old);

                // Etiqueta com ID e Intenção de Manobra
                g2.setColor(new Color(20, 25, 35));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
                String labelIntent = (v.intent == ManeuverIntent.TURN_RIGHT) ? " [R]" : (v.intent == ManeuverIntent.TURN_LEFT ? " [L]" : " [S]");
                g2.drawString(v.id + labelIntent, (int) v.x - 14, (int) v.y - 12);
            }
        }

        private void drawHUD(Graphics2D g2, int width) {
            int hudX = 20;
            int hudY = 12;
            int hudW = width - 40;
            int hudH = 74;

            g2.setColor(new Color(250, 252, 255, 245));
            g2.fill(new RoundRectangle2D.Double(hudX, hudY, hudW, hudH, 14, 14));

            g2.setColor(new Color(190, 200, 215));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Double(hudX, hudY, hudW, hudH, 14, 14));

            // Título Principal
            g2.setColor(new Color(30, 40, 55));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
            g2.drawString("TrafficMAS", hudX + 16, hudY + 22);

            g2.setColor(new Color(110, 125, 145));
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            g2.drawString("Autonomous 4-Lane City Control (ISPGaya)", hudX + 16, hudY + 36);

            // Linha 2: Status do Agente de Perturbações (Feature 5)
            boolean hasAlert = globalSpeedMultiplier < 0.95;
            g2.setColor(hasAlert ? new Color(220, 50, 50) : new Color(30, 140, 60));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            String statusPrefix = hasAlert ? "⚠️ [IA-ML ALERTA] " : "✔ [IA-ML NORMAL] ";
            g2.drawString(statusPrefix + disruptionNotice, hudX + 16, hudY + 58);

            int totalCars = vehicles.size();
            long stoppedCars = vehicles.stream().filter(v -> v.currentSpeed < 0.2).count();
            long elapsedSec = (System.currentTimeMillis() - simulationStartMillis) / 1000;
            double avgWait = getAverageWaitSeconds();

            // Badges de Telemetria
            drawBadge(g2, hudX + 235, hudY + 12, "VEÍCULOS", String.valueOf(totalCars), new Color(20, 130, 210));
            drawBadge(g2, hudX + 315, hudY + 12, "PARADOS", String.valueOf(stoppedCars), (stoppedCars > 0) ? new Color(220, 60, 60) : new Color(40, 170, 90));
            drawBadge(g2, hudX + 390, hudY + 12, "ESPERA", avgWait + "s", new Color(220, 120, 20));
            drawBadge(g2, hudX + 465, hudY + 12, "SOCORRO", String.valueOf(preemptionCount), new Color(170, 30, 200));
            drawBadge(g2, hudX + 540, hudY + 12, "DÉBITO", String.valueOf(totalCompletedPasses), new Color(30, 160, 110));
            drawBadge(g2, hudX + 605, hudY + 12, "TEMPO", elapsedSec + "s", new Color(100, 100, 150));

            // Indicadores dos 4 Cruzamentos (NS e EW)
            int crossX = hudX + 675;
            String[] crossLabels = {"NW", "NE", "SW", "SE"};
            for (int i = 0; i < 4; i++) {
                g2.setColor(new Color(90, 105, 125));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.drawString(crossLabels[i], crossX + (i * 54), hudY + 20);

                boolean isNSGreen = lights[i][0] == LightState.GREEN;
                g2.setColor(isNSGreen ? new Color(40, 200, 90) : new Color(230, 60, 60));
                g2.fillRoundRect(crossX + (i * 54), hudY + 26, 20, 7, 3, 3);

                boolean isEWGreen = lights[i][1] == LightState.GREEN;
                g2.setColor(isEWGreen ? new Color(40, 200, 90) : new Color(230, 60, 60));
                g2.fillRoundRect(crossX + (i * 54) + 23, hudY + 26, 20, 7, 3, 3);
            }
        }

        private void drawBadge(Graphics2D g2, int x, int y, String title, String val, Color c) {
            g2.setColor(new Color(110, 125, 145));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
            g2.drawString(title, x, y + 10);

            g2.setColor(c);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
            g2.drawString(val, x, y + 27);
        }
    }
}
