package environment;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/**
 * Interface Gráfica Urbana Parametrizável (2×2 e 3×3) com Navegação A* e Cinemática Contínua (Fase 6.1).
 *
 * Características:
 * - Suporte dinâmico e parametrizável à malha 2×2 (4 cruzamentos) e 3×3 (9 cruzamentos) via {@link NetworkTopologyConfig}.
 * - 2 faixas por sentido (4 faixas por artéria: 24px por faixa, 96px por avenida).
 * - Integração com {@link RoadNetworkGraph} e {@link AStarPathfinder}: cada veículo segue rotas calculadas pelo A*
 *   e recalcula apenas quando ocorrem bloqueios/alterações no grafo ou ao concluir uma missão.
 * - Curvas paramétricas contínuas de Bézier no interior dos cruzamentos com rotação suave do chassi.
 * - Respeito rigoroso pelas linhas de paragem (stop lines) e pelos semáforos (zero atravessamentos no vermelho).
 * - Posicionamento inicial e dinâmico sem sobreposição física de veículos no spawn.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.1)
 */
public class CitySimulationGUI {

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
        public String category = "NORMAL";
        public ManeuverIntent intent = ManeuverIntent.GO_STRAIGHT;
        public int laneIndex = 0; // 0: Faixa exterior/direita, 1: Faixa interior/esquerda

        // Estado de Navegação A* (Fase 6.1 — R7, R8)
        public int originIntersectionId = 0;
        public int destinationIntersectionId = 0;
        public AStarPathfinder.RouteResult activeRoute = null;
        public long routeGraphVersion = -1L;
        public int completedRoutesCount = 0;
        public int routeRecalculationsCount = 0;

        // Métricas individuais de telemetria e espera (Feature 8)
        public long totalWaitMillis = 0;
        public long currentStopStartMillis = 0;
        public int crossingIntersection = -1;
        public double currentCrossingWaitSeconds = 0.0;

        // Estado da manobra dentro do cruzamento
        public boolean inIntersection = false;
        public double turnProgress = 0.0;
        public int activeIntersection = -1;
        public double turnStartX, turnStartY;
        public double turnEndX, turnEndY;
        public double turnStartAngle, turnTargetAngle;

        public VisualVehicle(String id, double x, double y, double maxSpeed, int direction,
                             Color color, ManeuverIntent intent, int laneIndex) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.maxSpeed = maxSpeed;
            this.currentSpeed = maxSpeed * 0.75;
            this.direction = direction;
            this.color = color;
            this.intent = intent;
            this.laneIndex = laneIndex;
            String lower = (id != null) ? id.toLowerCase(Locale.ROOT) : "";
            this.isEmergency = lower.contains("emerg") || lower.contains("amb")
                    || lower.contains("pol") || lower.contains("bomb") || lower.contains("fire");
            if (this.isEmergency) {
                this.maxSpeed = 2.85;
                if (lower.contains("bomb") || lower.contains("fire")) this.category = "FIRE_TRUCK";
                else if (lower.contains("pol")) this.category = "POLICE";
                else this.category = "AMBULANCE";
            }
            this.headingAngle = getInitialAngle(direction);
        }

        private static double getInitialAngle(int dir) {
            switch (dir) {
                case 0: return Math.PI / 2.0;   // Desce (+Y)
                case 1: return -Math.PI / 2.0;  // Sobe (-Y)
                case 2: return 0.0;             // Direita (+X)
                case 3: return Math.PI;         // Esquerda (-X)
                default: return 0.0;
            }
        }
    }

    private static CitySimulationGUI instance;

    // Constantes públicas legadas mantidas para compatibilidade retroativa (Modo 2×2)
    public static final int ROAD_WIDTH = NetworkTopologyConfig.DEFAULT_ROAD_WIDTH; // 96px
    public static final int LANE_WIDTH = NetworkTopologyConfig.DEFAULT_LANE_WIDTH; // 24px
    public static final int ROAD_X1 = 280;
    public static final int ROAD_X2 = 640;
    public static final int ROAD_Y1 = 280;
    public static final int ROAD_Y2 = 640;

    // Topologia e Grafo Rodoviário ativos (suporta 2×2=4 e 3×3=9 cruzamentos)
    private volatile NetworkTopologyConfig topology;
    private volatile RoadNetworkGraph roadGraph;

    // Estado dos semáforos (dimensionado para suportar até 9 cruzamentos: [0..8][0=NS, 1=EW])
    private final LightState[][] lights = new LightState[16][2];

    // Lista thread-safe de veículos ativos
    private final List<VisualVehicle> vehicles = new CopyOnWriteArrayList<>();

    // Auditoria de segurança (zero atravessamentos no vermelho)
    private final AtomicInteger redLightViolationsCount = new AtomicInteger(0);
    private final AtomicInteger spawnSequence = new AtomicInteger(0);

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
    private int weatherCondition = 0; // 0=SECO, 1=CHUVA, 2=NEVOEIRO
    private boolean incidentPresent = false;
    private String mlPredictionNotice = "ML Preditivo: Operacional";
    private JFrame windowFrame;
    private CityPanel cityPanel;
    private Timer simulationTimer;

    public CitySimulationGUI() {
        this(java.awt.GraphicsEnvironment.isHeadless());
    }

    public CitySimulationGUI(boolean headless) {
        this(headless, resolveInitialTopology(headless));
    }

    public CitySimulationGUI(boolean headless, NetworkTopologyConfig initialTopology) {
        this.topology = (initialTopology != null) ? initialTopology : NetworkTopologyConfig.CONFIG_3X3;
        this.roadGraph = new RoadNetworkGraph(this.topology);
        boolean effectiveHeadless = headless || java.awt.GraphicsEnvironment.isHeadless();

        if (!effectiveHeadless) {
            windowFrame = new JFrame("TrafficMAS — Intelligent Multi-Agent Urban Simulation ("
                    + topology.getRows() + "x" + topology.getCols() + " | A* Navigation)");
            windowFrame.setSize(topology.getWorldWidth(), topology.getWorldHeight());
            windowFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            windowFrame.setLocationRelativeTo(null);
            windowFrame.setResizable(true);
        }

        simulationStartMillis = System.currentTimeMillis();
        resetLights();

        if (!effectiveHeadless && windowFrame != null) {
            cityPanel = new CityPanel();
            cityPanel.setDoubleBuffered(true);
            windowFrame.add(cityPanel);

            // Loop de simulação a ~30 FPS
            simulationTimer = new Timer(30, e -> {
                tickCounter++;
                int interCount = topology.getIntersectionCount();
                metrics.TrafficMetricsCollector.getInstance().advanceSimulatedTime(0.03333333333333333);
                if (tickCounter % 30 == 0) {
                    for (int i = 0; i < interCount; i++) {
                        metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 0, getWaitingCount(i, 0));
                        metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 1, getWaitingCount(i, 1));
                    }
                }
                calculateFps();
                updatePositions();
                cityPanel.repaint();
            });
            simulationTimer.start();
        }
    }

    private static NetworkTopologyConfig resolveInitialTopology(boolean headless) {
        if (NetworkTopologyConfig.isExplicitlyConfigured()) {
            return NetworkTopologyConfig.getActive();
        }
        if (!headless) {
            NetworkTopologyConfig.activate3x3();
            return NetworkTopologyConfig.CONFIG_3X3;
        }
        return NetworkTopologyConfig.getActive();
    }

    /**
     * Reconfigura dinamicamente a topologia (2×2 ou 3×3) e reconstrói o grafo rodoviário.
     */
    public synchronized void reconfigureTopology(NetworkTopologyConfig newTopology) {
        if (newTopology == null) return;
        this.topology = newTopology;
        this.roadGraph = new RoadNetworkGraph(newTopology);
        this.vehicles.clear();
        this.redLightViolationsCount.set(0);
        this.spawnSequence.set(0);
        resetLights();
        if (!java.awt.GraphicsEnvironment.isHeadless() && windowFrame != null) {
            windowFrame.setTitle("TrafficMAS — Intelligent Multi-Agent Urban Simulation ("
                    + topology.getRows() + "x" + topology.getCols() + " | A* Navigation)");
            windowFrame.setSize(topology.getWorldWidth(), topology.getWorldHeight());
            if (cityPanel != null) {
                cityPanel.repaint();
            }
        }
    }

    public NetworkTopologyConfig getTopology() {
        return topology;
    }

    public RoadNetworkGraph getRoadGraph() {
        return roadGraph;
    }

    public int getDisplayedFps() {
        return displayedFps;
    }

    public int getRedLightViolationsCount() {
        return redLightViolationsCount.get();
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
            if (!headless && instance.windowFrame != null) {
                SwingUtilities.invokeLater(() -> instance.windowFrame.setVisible(true));
            }
        } else if (NetworkTopologyConfig.isExplicitlyConfigured()
                && instance.topology.getMode() != NetworkTopologyConfig.getActive().getMode()) {
            instance.reconfigureTopology(NetworkTopologyConfig.getActive());
        }
        return instance;
    }

    public synchronized void setTrafficLights(int intersectionIndex, LightState nsState, LightState ewState) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length) {
            lights[intersectionIndex][0] = nsState;
            lights[intersectionIndex][1] = ewState;
            IntersectionSafetyAuthority.assertSafetyInvariant(lights[intersectionIndex][0], lights[intersectionIndex][1]);
        }
    }

    public synchronized void setTrafficLight(int intersectionIndex, int axis, LightState state) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length && axis >= 0 && axis <= 1) {
            lights[intersectionIndex][axis] = state;
            IntersectionSafetyAuthority.assertSafetyInvariant(lights[intersectionIndex][0], lights[intersectionIndex][1]);
        }
    }

    public synchronized LightState getTrafficLightState(int intersectionIndex, int axis) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length && axis >= 0 && axis <= 1) {
            return lights[intersectionIndex][axis];
        }
        return LightState.RED;
    }

    public synchronized void resetLights() {
        for (int i = 0; i < lights.length; i++) {
            lights[i][0] = (i % 2 == 0) ? LightState.GREEN : LightState.RED;
            lights[i][1] = (i % 2 == 0) ? LightState.RED : LightState.GREEN;
        }
    }

    /* =========================================================================
     * GESTÃO DE VEÍCULOS, SPAWN SEM SOBREPOSIÇÃO E INTEGRAÇÃO A*
     * ========================================================================= */

    public synchronized void addVehicle(String id, int direction, Color color) {
        VisualVehicle existing = getVisualVehicle(id);
        if (existing != null) {
            return;
        }
        int slot = spawnSequence.getAndIncrement();
        boolean isEmerg = id != null && (id.toLowerCase(Locale.ROOT).contains("emerg")
                || id.toLowerCase(Locale.ROOT).contains("amb")
                || id.toLowerCase(Locale.ROOT).contains("pol")
                || id.toLowerCase(Locale.ROOT).contains("bomb")
                || id.toLowerCase(Locale.ROOT).contains("fire"));
        spawnVehicleSafely(id, direction, color, isEmerg, isEmerg ? "EMERGENCY" : "NORMAL", slot);
    }

    public synchronized void addVehicle(String id, int direction, Color color, ManeuverIntent intent) {
        VisualVehicle existing = getVisualVehicle(id);
        if (existing != null) {
            return;
        }
        int slot = spawnSequence.getAndIncrement();
        boolean isEmerg = id != null && (id.toLowerCase(Locale.ROOT).contains("emerg")
                || id.toLowerCase(Locale.ROOT).contains("amb")
                || id.toLowerCase(Locale.ROOT).contains("pol")
                || id.toLowerCase(Locale.ROOT).contains("bomb")
                || id.toLowerCase(Locale.ROOT).contains("fire"));
        VisualVehicle v = spawnVehicleSafely(id, direction, color, isEmerg, isEmerg ? "EMERGENCY" : "NORMAL", slot);
        if (v != null && intent != null) {
            v.intent = intent;
        }
    }

    /**
     * Cria e posiciona um veículo garantindo ausência estrita de sobreposição física no spawn
     * e atribuindo uma rota inicial válida calculada pelo algoritmo A* sobre o {@link RoadNetworkGraph}.
     */
    public synchronized VisualVehicle spawnVehicleSafely(String id, int preferredDirection, Color color,
                                                         boolean isEmergency, String category, int slotIndex) {
        VisualVehicle existing = getVisualVehicle(id);
        if (existing != null) {
            return existing;
        }

        NetworkTopologyConfig top = this.topology;
        int rows = top.getRows();
        int cols = top.getCols();

        // Procura posição segura (sem colisão/sobreposição >= 36px) distribuindo pelas vias e segmentos
        double chosenX = 0;
        double chosenY = 0;
        int chosenDir = preferredDirection % 4;
        int chosenLane = 1;
        boolean foundSafeSpot = false;

        int totalDirections = 4;
        for (int dOffset = 0; dOffset < totalDirections && !foundSafeSpot; dOffset++) {
            int dir = (preferredDirection + dOffset) % 4;
            int numCorridors = (dir == 0 || dir == 1) ? cols : rows;

            for (int cOffset = 0; cOffset < numCorridors && !foundSafeSpot; cOffset++) {
                int corridor = ((slotIndex / 2) + cOffset) % numCorridors;
                for (int lOffset = 0; lOffset < 2 && !foundSafeSpot; lOffset++) {
                    int lane = ((slotIndex + lOffset) % 2);
                    double laneCoord = top.getLaneCenterCoordinate(corridor, dir, lane);

                    double[] candidateLongitudinal = buildSafeLongitudinalCandidates(top, dir);
                    int startCandidateIdx = (slotIndex / (numCorridors * 2)) % candidateLongitudinal.length;

                    for (int k = 0; k < candidateLongitudinal.length; k++) {
                        double longPos = candidateLongitudinal[(startCandidateIdx + k) % candidateLongitudinal.length];
                        double candX = (dir == 0 || dir == 1) ? laneCoord : longPos;
                        double candY = (dir == 0 || dir == 1) ? longPos : laneCoord;

                        if (isSpawnPositionClear(candX, candY, 36.0)) {
                            chosenX = candX;
                            chosenY = candY;
                            chosenDir = dir;
                            chosenLane = lane;
                            foundSafeSpot = true;
                            break;
                        }
                    }
                }
            }
        }

        // Fallback em fila estendida de entrada caso todos os segmentos internos estejam ocupados
        if (!foundSafeSpot) {
            chosenDir = preferredDirection % 4;
            int numCorridors = (chosenDir == 0 || chosenDir == 1) ? cols : rows;
            int corridor = slotIndex % numCorridors;
            chosenLane = (slotIndex / numCorridors) % 2;
            double laneCoord = top.getLaneCenterCoordinate(corridor, chosenDir, chosenLane);
            double offset = 35.0;
            while (true) {
                double candX, candY;
                switch (chosenDir) {
                    case 0: candX = laneCoord; candY = -offset; break;
                    case 1: candX = laneCoord; candY = top.getWorldHeight() + offset; break;
                    case 2: candX = -offset; candY = laneCoord; break;
                    default: candX = top.getWorldWidth() + offset; candY = laneCoord; break;
                }
                if (isSpawnPositionClear(candX, candY, 36.0)) {
                    chosenX = candX;
                    chosenY = candY;
                    break;
                }
                offset += 40.0;
            }
        }

        double maxSpd = isEmergency ? 2.85 : 2.4;
        VisualVehicle v = new VisualVehicle(id, chosenX, chosenY, maxSpd, chosenDir, color, ManeuverIntent.GO_STRAIGHT, chosenLane);
        v.isEmergency = isEmergency;
        if (category != null && !category.isEmpty()) {
            v.category = category;
        }

        // Calcula rota A* inicial a partir do próximo cruzamento na direção do veículo
        int firstIntersection = top.getTargetIntersection(v.x, v.y, v.direction);
        int destIntersection = selectNextDestination(firstIntersection, v.direction, slotIndex);
        assignAStarRoute(v, firstIntersection, destIntersection);

        // Ajusta a faixa inicial para coincidir com a primeira manobra planeada pelo A*, se estiver livre
        int desiredLane = (v.intent == ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        if (desiredLane != v.laneIndex) {
            int corridor = (v.direction == 0 || v.direction == 1) ? top.getClosestCol(v.x) : top.getClosestRow(v.y);
            double altCoord = top.getLaneCenterCoordinate(corridor, v.direction, desiredLane);
            double altX = (v.direction == 0 || v.direction == 1) ? altCoord : v.x;
            double altY = (v.direction == 0 || v.direction == 1) ? v.y : altCoord;
            if (isSpawnPositionClear(altX, altY, 36.0)) {
                v.x = altX;
                v.y = altY;
                v.laneIndex = desiredLane;
            }
        }

        vehicles.add(v);
        return v;
    }

    private double[] buildSafeLongitudinalCandidates(NetworkTopologyConfig top, int dir) {
        List<Double> coords = new ArrayList<>();
        int rw = top.getRoadWidth();

        if (dir == 0) { // N->S (Y crescente, antes das stop lines roadY[r] - 2)
            for (int r = 0; r < top.getRows(); r++) {
                double stopY = top.getRoadY(r) - 22.0;
                double minPrevExit = (r == 0) ? 20.0 : (top.getRoadY(r - 1) + rw + 28.0);
                for (double y = stopY; y >= minPrevExit; y -= 44.0) {
                    coords.add(y);
                }
            }
        } else if (dir == 1) { // S->N (Y decrescente, antes das stop lines roadY[r] + rw + 2)
            for (int r = top.getRows() - 1; r >= 0; r--) {
                double stopY = top.getRoadY(r) + rw + 22.0;
                double maxPrevExit = (r == top.getRows() - 1)
                        ? (top.getWorldHeight() - 35.0)
                        : (top.getRoadY(r + 1) - 28.0);
                for (double y = stopY; y <= maxPrevExit; y += 44.0) {
                    coords.add(y);
                }
            }
        } else if (dir == 2) { // W->E (X crescente, antes das stop lines roadX[c] - 2)
            for (int c = 0; c < top.getCols(); c++) {
                double stopX = top.getRoadX(c) - 22.0;
                double minPrevExit = (c == 0) ? 20.0 : (top.getRoadX(c - 1) + rw + 28.0);
                for (double x = stopX; x >= minPrevExit; x -= 44.0) {
                    coords.add(x);
                }
            }
        } else { // E->W (X decrescente, antes das stop lines roadX[c] + rw + 2)
            for (int c = top.getCols() - 1; c >= 0; c--) {
                double stopX = top.getRoadX(c) + rw + 22.0;
                double maxPrevExit = (c == top.getCols() - 1)
                        ? (top.getWorldWidth() - 35.0)
                        : (top.getRoadX(c + 1) - 28.0);
                for (double x = stopX; x <= maxPrevExit; x += 44.0) {
                    coords.add(x);
                }
            }
        }

        if (coords.isEmpty()) {
            return new double[] { 60.0, 110.0 };
        }
        double[] arr = new double[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            arr[i] = coords.get(i);
        }
        return arr;
    }

    public synchronized boolean isSpawnPositionClear(double x, double y, double minDistancePx) {
        for (VisualVehicle other : vehicles) {
            if (Math.hypot(other.x - x, other.y - y) < minDistancePx) {
                return false;
            }
        }
        return true;
    }

    private int selectNextDestination(int originNode, int currentDirection, int seedOffset) {
        int count = topology.getIntersectionCount();
        if (count <= 1) return 0;

        // Procura um destino alcançável sem inversão de marcha que maximize a travessia na grelha
        int bestCandidate = (originNode + (count / 2) + (seedOffset % count)) % count;
        if (bestCandidate == originNode) {
            bestCandidate = (originNode + 1) % count;
        }

        for (int attempt = 0; attempt < count; attempt++) {
            int candidate = (bestCandidate + attempt) % count;
            if (candidate == originNode) continue;
            AStarPathfinder.RouteResult testRoute = AStarPathfinder.findPathAStar(
                    roadGraph, originNode, candidate, currentDirection);
            if (testRoute.reachable && testRoute.segmentPath.size() >= 1) {
                return candidate;
            }
        }
        return bestCandidate;
    }

    private void assignAStarRoute(VisualVehicle v, int originNode, int destNode) {
        v.originIntersectionId = originNode;
        v.destinationIntersectionId = destNode;
        AStarPathfinder.RouteResult route = AStarPathfinder.findPathAStar(
                roadGraph, originNode, destNode, v.direction);
        if (!route.reachable) {
            // Se o destino escolhido não for alcançável sem inversão de marcha, tenta qualquer destino alcançável
            int count = topology.getIntersectionCount();
            for (int cand = 0; cand < count; cand++) {
                if (cand == originNode) continue;
                AStarPathfinder.RouteResult alt = AStarPathfinder.findPathAStar(
                        roadGraph, originNode, cand, v.direction);
                if (alt.reachable && !alt.segmentPath.isEmpty()) {
                    route = alt;
                    v.destinationIntersectionId = cand;
                    break;
                }
            }
        }
        v.activeRoute = route;
        v.routeGraphVersion = roadGraph.getGraphVersion();
        v.routeRecalculationsCount++;
        if (route.reachable) {
            v.intent = route.getManeuverForIntersection(originNode);
        } else {
            v.intent = ManeuverIntent.GO_STRAIGHT;
        }
    }

    /**
     * Remove um veículo visual pelo seu ID (invocado na remoção dinâmica de frota).
     */
    public synchronized boolean removeVehicle(String vehicleId) {
        if (vehicleId == null) return false;
        return vehicles.removeIf(v -> vehicleId.equals(v.id));
    }

    /**
     * Remove todos os veículos visuais (usado em reinicializações de testes).
     */
    public synchronized void clearVehicles() {
        vehicles.clear();
        spawnSequence.set(0);
    }

    public List<VisualVehicle> getVisualVehiclesSnapshot() {
        return new ArrayList<>(vehicles);
    }

    /* =========================================================================
     * MÉTODOS DE PERCEÇÃO SENSORIAL E ATUAÇÃO PARA OS AGENTES (Features 1, 6, 7, 8)
     * ========================================================================= */

    public synchronized int getTargetIntersection(VisualVehicle v) {
        if (v == null) return 0;
        return topology.getTargetIntersection(v.x, v.y, v.direction);
    }

    public synchronized double getDistanceToTargetStopLine(VisualVehicle v) {
        if (v == null) return 9999.0;
        return topology.getDistanceToTargetStopLine(v.x, v.y, v.direction);
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
        if (interIdx >= 0 && interIdx < topology.getIntersectionCount() && axis >= 0 && axis <= 1) {
            preemptionCount++;
            IntersectionSafetyAuthority.getAuthority(interIdx).requestEmergency("MANUAL_EMERGENCY", axis, null);
            System.out.println("[CitySimulationGUI] Preempção de emergência encaminhada para a Autoridade de Segurança do Cruzamento C"
                    + (interIdx + 1) + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");
        }
    }

    public synchronized void setDisruptionNotice(String notice, double speedFactor) {
        this.disruptionNotice = notice;
        this.globalSpeedMultiplier = Math.max(0.4, Math.min(1.2, speedFactor));
        if (notice != null) {
            String lower = notice.toLowerCase(Locale.ROOT);
            if (lower.contains("chuva") || lower.contains("rain") || lower.contains("escorregadi")) {
                this.weatherCondition = 1;
            } else if (lower.contains("nevoeiro") || lower.contains("fog") || lower.contains("visibilidade")) {
                this.weatherCondition = 2;
            } else {
                this.weatherCondition = 0;
            }
            this.incidentPresent = (lower.contains("obras") || lower.contains("acident") || lower.contains("incident")
                    || lower.contains("supressão") || lower.contains("roadwork"));
        }
    }

    public synchronized void setMLPredictionNotice(String notice) {
        this.mlPredictionNotice = notice;
    }

    public synchronized String getMLPredictionNotice() {
        return mlPredictionNotice;
    }

    public synchronized int getWeatherCondition() {
        return weatherCondition;
    }

    public synchronized int getIncidentPresent() {
        return incidentPresent ? 1 : 0;
    }

    public synchronized int getRoadCapacity() {
        return incidentPresent ? 2 : 4;
    }

    public synchronized double getAverageSpeed(int interIdx) {
        int volume = 0;
        double sumSpeed = 0.0;
        for (VisualVehicle v : vehicles) {
            if (v.inIntersection) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 220.0) {
                volume++;
                sumSpeed += v.currentSpeed;
            }
        }
        return (volume > 0) ? Math.round((sumSpeed / volume) * 100.0) / 100.0 : (2.4 * globalSpeedMultiplier);
    }

    public synchronized double getAverageWaitingTime(int interIdx) {
        int queue = 0;
        double sumWait = 0.0;
        long now = System.currentTimeMillis();
        for (VisualVehicle v : vehicles) {
            if (v.inIntersection) continue;
            double dist = getDistanceToTargetStopLine(v);
            if (getTargetIntersection(v) == interIdx && dist >= 0 && dist <= 220.0 && v.currentSpeed < 0.2) {
                queue++;
                double waitSec = (v.currentStopStartMillis > 0) ? ((now - v.currentStopStartMillis) / 1000.0) : 0.0;
                sumWait += waitSec;
            }
        }
        return (queue > 0) ? Math.round((sumWait / queue) * 10.0) / 10.0 : 0.0;
    }

    public synchronized String getDisruptionNotice() {
        return disruptionNotice;
    }

    public synchronized double getGlobalSpeedMultiplier() {
        return globalSpeedMultiplier;
    }

    public synchronized double getAverageWaitSeconds() {
        double avg = metrics.TrafficMetricsCollector.getInstance().getAverageWaitingTimeSeconds();
        if (avg >= 0.0) {
            return Math.round(avg * 10.0) / 10.0;
        }
        return (totalCompletedPasses > 0) ? Math.round((totalWaitTimeSeconds / totalCompletedPasses) * 10.0) / 10.0 : 0.0;
    }

    public synchronized long getTotalCompletedPasses() {
        long c = metrics.TrafficMetricsCollector.getInstance().getTotalCrossings();
        return (c > 0) ? c : totalCompletedPasses;
    }

    public synchronized int getPreemptionCount() {
        int p = metrics.TrafficMetricsCollector.getInstance().getEmergencyPreemptions();
        return (p > 0) ? p : preemptionCount;
    }

    private synchronized void recordIntersectionCrossing(VisualVehicle v, int interIdx) {
        if (interIdx < 0 || interIdx >= topology.getIntersectionCount()) return;
        int axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
        double wait = v.currentCrossingWaitSeconds;
        if (v.currentStopStartMillis > 0) {
            wait += (System.currentTimeMillis() - v.currentStopStartMillis) / 1000.0;
            v.currentStopStartMillis = 0;
        }
        v.currentCrossingWaitSeconds = 0.0;
        v.totalWaitMillis = 0;

        metrics.TrafficMetricsCollector.getInstance().recordCrossing(v.id, interIdx, axis, wait);
        totalCompletedPasses++;
        totalWaitTimeSeconds += wait;

        if (v.isEmergency) {
            IntersectionSafetyAuthority.getAuthority(interIdx).notifyEmergencyCleared(v.id);
        }
    }

    /**
     * Avança um passo cinemático da simulação (pode ser chamado pelo Timer Swing ou diretamente em testes).
     */
    public synchronized void stepSimulationTick() {
        tickCounter++;
        int interCount = topology.getIntersectionCount();
        metrics.TrafficMetricsCollector.getInstance().advanceSimulatedTime(0.03333333333333333);
        if (tickCounter % 30 == 0) {
            for (int i = 0; i < interCount; i++) {
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 0, getWaitingCount(i, 0));
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 1, getWaitingCount(i, 1));
            }
        }
        updatePositions();
    }

    private void updatePositions() {
        NetworkTopologyConfig top = this.topology;
        int interCount = top.getIntersectionCount();

        for (VisualVehicle v : vehicles) {
            // 1. Tratamento de Manobra Curva Ativa no Interior do Cruzamento
            if (v.inIntersection) {
                updateIntersectionManeuver(v);
                continue;
            }

            // 2. Se o veículo já iniciou a travessia retilínea em sinal VERDE, conclui a saída do cruzamento
            if (v.crossingIntersection >= 0) {
                double clearSpeed = Math.max(1.6, v.currentSpeed);
                switch (v.direction) {
                    case 0: v.y += clearSpeed; v.headingAngle = Math.PI / 2.0; break;
                    case 1: v.y -= clearSpeed; v.headingAngle = -Math.PI / 2.0; break;
                    case 2: v.x += clearSpeed; v.headingAngle = 0.0; break;
                    case 3: v.x -= clearSpeed; v.headingAngle = Math.PI; break;
                }
                int ix = v.crossingIntersection;
                if (top.hasExitedIntersection(ix, v.x, v.y, v.direction)) {
                    recordIntersectionCrossing(v, ix);
                    v.crossingIntersection = -1;
                    onVehicleExitedIntersection(v, ix);
                }
                continue;
            }

            // 3. Determinar o próximo cruzamento e a distância exata até à sua Stop Line
            int nextIntersectionIdx = top.getTargetIntersection(v.x, v.y, v.direction);
            double distToStopLine = top.getDistanceToTargetStopLine(v.x, v.y, v.direction);
            int axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;

            LightState nextLight = LightState.GREEN;
            if (nextIntersectionIdx >= 0 && nextIntersectionIdx < interCount && distToStopLine >= -4.0) {
                nextLight = lights[nextIntersectionIdx][axis];
                IntersectionSafetyAuthority.assertSafetyInvariant(
                        lights[nextIntersectionIdx][0], lights[nextIntersectionIdx][1]);
            }

            // 4. Atualizar intenção de manobra segundo a rota A* (recalculando apenas se o grafo mudou ou chegou ao destino)
            if (distToStopLine > 0.0 && distToStopLine <= 240.0 && nextIntersectionIdx >= 0) {
                ensureValidAStarIntentForApproach(v, nextIntersectionIdx);
            }

            // 5. Adaptação preventiva de faixa para coincidir com a manobra planeada pelo A* (fora da zona de travagem)
            if (distToStopLine > 68.0 && distToStopLine < 230.0) {
                alignVehicleLaneWithIntent(v, top);
            }

            // 6. Deteção de Veículo da Frente na Mesma Faixa (Fila de Espera Anti-Colisão)
            double distToLeader = getDistanceToLeadingVehicle(v);
            boolean blockedByLeader = (distToLeader < 38.0);

            // 7. Decisão Estrita de Travagem por Semáforo ou Fila (Zero Atravessamentos no Vermelho)
            boolean approachingStopLine = (distToStopLine >= 0.0 && distToStopLine <= 65.0);
            boolean signalNotGreen = (nextLight != LightState.GREEN);
            boolean shouldBrakeForSignal = approachingStopLine && signalNotGreen;
            boolean shouldBrake = blockedByLeader || shouldBrakeForSignal;

            if (shouldBrake) {
                v.isBraking = true;
                double limitDist = blockedByLeader
                        ? (shouldBrakeForSignal ? Math.min(distToLeader - 32.0, distToStopLine) : (distToLeader - 32.0))
                        : distToStopLine;

                if (limitDist <= 3.0) {
                    v.currentSpeed = 0.0;
                    v.wasStopped = true;
                    v.currentCrossingWaitSeconds += 0.03333333333333333;
                    if (v.currentStopStartMillis == 0) {
                        v.currentStopStartMillis = System.currentTimeMillis();
                    }
                } else {
                    double targetSpeed = Math.max(0.0, (limitDist / 50.0) * (v.maxSpeed * globalSpeedMultiplier));
                    v.currentSpeed = Math.max(0.0, v.currentSpeed - 0.16);
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
                    if (v.startDelayTicks < 10) {
                        v.startDelayTicks++;
                        v.currentSpeed = 0.0;
                    } else {
                        v.currentSpeed = Math.min(effectiveMaxSpeed, v.currentSpeed + 0.08);
                        if (v.currentSpeed >= effectiveMaxSpeed * 0.8) {
                            v.wasStopped = false;
                            v.startDelayTicks = 0;
                        }
                    }
                } else {
                    v.currentSpeed = Math.min(effectiveMaxSpeed, v.currentSpeed + 0.06);
                }
            }

            // 8. Início de Travessia ou Curva ao Atingir a Stop Line em Sinal VERDE
            if (distToStopLine >= -1.0 && distToStopLine <= 3.5 && nextLight == LightState.GREEN && !blockedByLeader) {
                if (!isIntersectionExitBlocked(v, nextIntersectionIdx, top)) {
                    if (v.intent == ManeuverIntent.TURN_RIGHT || v.intent == ManeuverIntent.TURN_LEFT) {
                        initiateIntersectionTurn(v, nextIntersectionIdx);
                        continue;
                    } else {
                        v.crossingIntersection = nextIntersectionIdx;
                    }
                } else {
                    v.currentSpeed = 0.0;
                    v.wasStopped = true;
                    continue;
                }
            }

            // 9. Proteção física absoluta da Stop Line: nunca permite transpor a linha se o sinal não estiver VERDE
            double stepDist = v.currentSpeed;
            if (approachingStopLine && signalNotGreen && (distToStopLine - stepDist) < 1.0) {
                stepDist = Math.max(0.0, distToStopLine - 1.0);
                v.currentSpeed = 0.0;
                v.wasStopped = true;
            }

            double oldDist = distToStopLine;
            switch (v.direction) {
                case 0: v.y += stepDist; v.headingAngle = Math.PI / 2.0; break;
                case 1: v.y -= stepDist; v.headingAngle = -Math.PI / 2.0; break;
                case 2: v.x += stepDist; v.headingAngle = 0.0; break;
                case 3: v.x -= stepDist; v.headingAngle = Math.PI; break;
            }

            double newDist = top.getDistanceToTargetStopLine(v.x, v.y, v.direction);
            if (oldDist > 0.5 && newDist < -0.5 && nextLight == LightState.RED && v.crossingIntersection < 0 && !v.inIntersection) {
                redLightViolationsCount.incrementAndGet();
            }

            // 10. Wrap-around seguro quando um veículo sai das extremidades da malha urbana
            handleBoundaryWrapAround(v, top);
        }
    }

    private void ensureValidAStarIntentForApproach(VisualVehicle v, int approachingIntersectionIdx) {
        long currentGraphVer = roadGraph.getGraphVersion();
        boolean needsRecalc = (v.activeRoute == null)
                || !v.activeRoute.reachable
                || (v.routeGraphVersion != currentGraphVer)
                || !v.activeRoute.nodePath.contains(approachingIntersectionIdx);

        if (!needsRecalc && approachingIntersectionIdx == v.destinationIntersectionId) {
            // Chegou ao cruzamento de destino da missão atual: conclui missão e planeia nova rota A* contínua
            v.completedRoutesCount++;
            int nextDest = selectNextDestination(approachingIntersectionIdx, v.direction, v.completedRoutesCount + v.id.hashCode());
            assignAStarRoute(v, approachingIntersectionIdx, nextDest);
            return;
        }

        if (needsRecalc) {
            int targetDest = v.destinationIntersectionId;
            if (targetDest == approachingIntersectionIdx || targetDest < 0 || targetDest >= topology.getIntersectionCount()) {
                targetDest = selectNextDestination(approachingIntersectionIdx, v.direction, v.completedRoutesCount + 1);
            }
            assignAStarRoute(v, approachingIntersectionIdx, targetDest);
        } else {
            v.intent = v.activeRoute.getManeuverForIntersection(approachingIntersectionIdx);
        }
    }

    private void onVehicleExitedIntersection(VisualVehicle v, int exitedIntersectionIdx) {
        int nextTarget = topology.getTargetIntersection(v.x, v.y, v.direction);
        if (exitedIntersectionIdx == v.destinationIntersectionId) {
            v.completedRoutesCount++;
            int nextDest = selectNextDestination(nextTarget, v.direction, v.completedRoutesCount + Math.abs(v.id.hashCode()));
            assignAStarRoute(v, nextTarget, nextDest);
        } else if (v.activeRoute != null && v.activeRoute.reachable) {
            v.intent = v.activeRoute.getManeuverForIntersection(nextTarget);
        }
    }

    private void alignVehicleLaneWithIntent(VisualVehicle v, NetworkTopologyConfig top) {
        int desiredLane = (v.intent == ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        if (v.laneIndex == desiredLane) return;

        int corridor = (v.direction == 0 || v.direction == 1) ? top.getClosestCol(v.x) : top.getClosestRow(v.y);
        double targetCoord = top.getLaneCenterCoordinate(corridor, v.direction, desiredLane);
        double candX = (v.direction == 0 || v.direction == 1) ? targetCoord : v.x;
        double candY = (v.direction == 0 || v.direction == 1) ? v.y : targetCoord;

        for (VisualVehicle other : vehicles) {
            if (other == v) continue;
            if (Math.hypot(other.x - candX, other.y - candY) < 34.0) {
                return; // Faixa adjacente ocupada; mantém faixa atual com segurança
            }
        }
        v.x = candX;
        v.y = candY;
        v.laneIndex = desiredLane;
    }

    private boolean isIntersectionExitBlocked(VisualVehicle v, int interIdx, NetworkTopologyConfig top) {
        int outDir = v.direction;
        if (v.intent == ManeuverIntent.TURN_RIGHT) {
            switch (v.direction) {
                case 0: outDir = 3; break;
                case 1: outDir = 2; break;
                case 2: outDir = 0; break;
                case 3: outDir = 1; break;
            }
        } else if (v.intent == ManeuverIntent.TURN_LEFT) {
            switch (v.direction) {
                case 0: outDir = 2; break;
                case 1: outDir = 3; break;
                case 2: outDir = 1; break;
                case 3: outDir = 0; break;
            }
        }

        int ix = top.getIntersectionX(interIdx);
        int iy = top.getIntersectionY(interIdx);
        int rw = top.getRoadWidth();
        int lw = top.getLaneWidth();
        int outLane = (v.intent == ManeuverIntent.TURN_RIGHT) ? 0 : (v.intent == ManeuverIntent.TURN_LEFT ? 1 : v.laneIndex);

        double checkX = v.x, checkY = v.y;
        switch (outDir) {
            case 0:
                checkX = (outLane == 0) ? (ix + 0.5 * lw) : (ix + 1.5 * lw);
                checkY = iy + rw + 16;
                break;
            case 1:
                checkX = (outLane == 0) ? (ix + 3.5 * lw) : (ix + 2.5 * lw);
                checkY = iy - 16;
                break;
            case 2:
                checkX = ix + rw + 16;
                checkY = (outLane == 0) ? (iy + 3.5 * lw) : (iy + 2.5 * lw);
                break;
            case 3:
                checkX = ix - 16;
                checkY = (outLane == 0) ? (iy + 0.5 * lw) : (iy + 1.5 * lw);
                break;
        }

        for (VisualVehicle other : vehicles) {
            if (other == v || other.inIntersection) continue;
            if (other.direction == outDir && other.currentSpeed < 0.2 && Math.hypot(other.x - checkX, other.y - checkY) < 28.0) {
                return true;
            }
        }
        return false;
    }

    private void handleBoundaryWrapAround(VisualVehicle v, NetworkTopologyConfig top) {
        int ww = top.getWorldWidth();
        int wh = top.getWorldHeight();
        boolean wrapped = false;

        if (v.y > wh + 25) {
            v.y = findSafeWrapCoordinate(v, v.x, -25.0, 0, -38.0);
            wrapped = true;
        } else if (v.y < -35) {
            v.y = findSafeWrapCoordinate(v, v.x, wh + 15.0, 1, 38.0);
            wrapped = true;
        } else if (v.x > ww + 25) {
            v.x = findSafeWrapCoordinate(v, -25.0, v.y, 2, -38.0);
            wrapped = true;
        } else if (v.x < -35) {
            v.x = findSafeWrapCoordinate(v, ww + 15.0, v.y, 3, 38.0);
            wrapped = true;
        }

        if (wrapped) {
            v.wasStopped = false;
            v.crossingIntersection = -1;
            v.inIntersection = false;
            int firstNode = top.getTargetIntersection(v.x, v.y, v.direction);
            int nextDest = selectNextDestination(firstNode, v.direction, v.completedRoutesCount + 1);
            assignAStarRoute(v, firstNode, nextDest);
        }
    }

    private double findSafeWrapCoordinate(VisualVehicle self, double baseX, double baseY, int dir, double stepOffset) {
        double candX = baseX;
        double candY = baseY;
        for (int attempt = 0; attempt < 15; attempt++) {
            boolean clash = false;
            for (VisualVehicle other : vehicles) {
                if (other == self) continue;
                if (Math.hypot(other.x - candX, other.y - candY) < 36.0) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return (dir == 0 || dir == 1) ? candY : candX;
            }
            if (dir == 0 || dir == 1) candY += stepOffset;
            else candX += stepOffset;
        }
        return (dir == 0 || dir == 1) ? candY : candX;
    }

    private double getDistanceToLeadingVehicle(VisualVehicle v) {
        double minDist = 9999.0;
        for (VisualVehicle other : vehicles) {
            if (other == v || other.direction != v.direction) continue;

            // Verificar se estão na mesma faixa física (tolerância lateral de 14px)
            if (v.direction == 0 || v.direction == 1) {
                if (Math.abs(other.x - v.x) > 14.0) continue;
            } else {
                if (Math.abs(other.y - v.y) > 14.0) continue;
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
        NetworkTopologyConfig top = this.topology;
        if (!top.isValidIntersection(interIdx)) return;

        int ix = top.getIntersectionX(interIdx);
        int iy = top.getIntersectionY(interIdx);
        int rw = top.getRoadWidth();
        int lw = top.getLaneWidth();

        v.inIntersection = true;
        v.turnProgress = 0.0;
        v.activeIntersection = interIdx;
        v.turnStartX = v.x;
        v.turnStartY = v.y;
        v.turnStartAngle = v.headingAngle;

        if (v.intent == ManeuverIntent.TURN_RIGHT) {
            v.laneIndex = 0;
            switch (v.direction) {
                case 0: // N->S virando para Oeste (E->W)
                    v.turnEndX = ix - 2;
                    v.turnEndY = iy + 0.5 * lw;
                    v.turnTargetAngle = Math.PI;
                    break;
                case 1: // S->N virando para Este (W->E)
                    v.turnEndX = ix + rw + 2;
                    v.turnEndY = iy + 3.5 * lw;
                    v.turnTargetAngle = 0.0;
                    break;
                case 2: // W->E virando para Sul (N->S)
                    v.turnEndX = ix + 0.5 * lw;
                    v.turnEndY = iy + rw + 2;
                    v.turnTargetAngle = Math.PI / 2.0;
                    break;
                case 3: // E->W virando para Norte (S->N)
                    v.turnEndX = ix + 3.5 * lw;
                    v.turnEndY = iy - 2;
                    v.turnTargetAngle = -Math.PI / 2.0;
                    break;
            }
        } else if (v.intent == ManeuverIntent.TURN_LEFT) {
            v.laneIndex = 1;
            switch (v.direction) {
                case 0: // N->S virando para Este (W->E)
                    v.turnEndX = ix + rw + 2;
                    v.turnEndY = iy + 2.5 * lw;
                    v.turnTargetAngle = 0.0;
                    break;
                case 1: // S->N virando para Oeste (E->W)
                    v.turnEndX = ix - 2;
                    v.turnEndY = iy + 1.5 * lw;
                    v.turnTargetAngle = Math.PI;
                    break;
                case 2: // W->E virando para Norte (S->N)
                    v.turnEndX = ix + 2.5 * lw;
                    v.turnEndY = iy - 2;
                    v.turnTargetAngle = -Math.PI / 2.0;
                    break;
                case 3: // E->W virando para Sul (N->S)
                    v.turnEndX = ix + 1.5 * lw;
                    v.turnEndY = iy + rw + 2;
                    v.turnTargetAngle = Math.PI / 2.0;
                    break;
            }
        }
    }

    private void updateIntersectionManeuver(VisualVehicle v) {
        NetworkTopologyConfig top = this.topology;
        double curveSpeed = Math.max(1.4, v.currentSpeed * 0.85);
        v.turnProgress += (curveSpeed / 45.0);

        if (v.turnProgress >= 1.0) {
            int completedInter = v.activeIntersection;
            v.inIntersection = false;
            recordIntersectionCrossing(v, completedInter);
            v.activeIntersection = -1;
            v.x = v.turnEndX;
            v.y = v.turnEndY;
            v.headingAngle = v.turnTargetAngle;

            if (Math.abs(v.turnTargetAngle - 0.0) < 0.1) v.direction = 2; // W->E
            else if (Math.abs(v.turnTargetAngle - Math.PI / 2.0) < 0.1) v.direction = 0; // N->S
            else if (Math.abs(v.turnTargetAngle - Math.PI) < 0.1 || Math.abs(v.turnTargetAngle + Math.PI) < 0.1) v.direction = 3; // E->W
            else if (Math.abs(v.turnTargetAngle + Math.PI / 2.0) < 0.1) v.direction = 1; // S->N

            onVehicleExitedIntersection(v, completedInter);
            return;
        }

        double t = v.turnProgress;
        int ix = top.getIntersectionX(v.activeIntersection);
        int iy = top.getIntersectionY(v.activeIntersection);
        int rw = top.getRoadWidth();

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
            ctrlX = ix + rw / 2.0;
            ctrlY = iy + rw / 2.0;
        }

        double nextX = Math.pow(1 - t, 2) * v.turnStartX + 2 * (1 - t) * t * ctrlX + Math.pow(t, 2) * v.turnEndX;
        double nextY = Math.pow(1 - t, 2) * v.turnStartY + 2 * (1 - t) * t * ctrlY + Math.pow(t, 2) * v.turnEndY;

        double dx = nextX - v.x;
        double dy = nextY - v.y;
        if (Math.hypot(dx, dy) > 0.01) {
            v.headingAngle = Math.atan2(dy, dx);
        }

        v.x = nextX;
        v.y = nextY;
    }

    /* =========================================================================
     * RENDERIZAÇÃO SWING PARAMETRIZÁVEL (2×2 e 3×3)
     * ========================================================================= */

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

            // 2. Quarteirões Urbanos com Zonas Verdes e Edifícios (parametrizado para 2×2 ou 3×3)
            drawUrbanBlocks(g2, width, height);

            // 3. Avenidas de 4 Faixas com Asfalto Limpo no Centro das Interseções
            drawRoadsAndMarkings(g2, width, height);

            // 4. Semáforos montados no bordo direito de cada fluxo com postes e luzes
            drawIntersectionsAndLights(g2);

            // 5. Veículos com Rotação Suave Dinâmica e Luzes
            drawVehicles(g2);

            // 6. HUD / Dashboard Superior
            drawHUD(g2, width);
        }

        private void drawUrbanBlocks(Graphics2D g2, int width, int height) {
            NetworkTopologyConfig top = topology;
            int[] rx = top.getRoadX();
            int[] ry = top.getRoadY();
            int rw = top.getRoadWidth();

            int numBlockCols = rx.length + 1;
            int numBlockRows = ry.length + 1;

            int topMargin = 92; // Reserva espaço superior para o HUD
            int sideMargin = 16;
            int roadGap = 12;

            int blockIdx = 0;
            for (int r = 0; r < numBlockRows; r++) {
                int by = (r == 0) ? topMargin : (ry[r - 1] + rw + roadGap);
                int byEnd = (r == ry.length) ? (height - sideMargin) : (ry[r] - roadGap);
                int bh = byEnd - by;
                if (bh <= 20) continue;

                for (int c = 0; c < numBlockCols; c++) {
                    int bx = (c == 0) ? sideMargin : (rx[c - 1] + rw + roadGap);
                    int bxEnd = (c == rx.length) ? (width - sideMargin) : (rx[c] - roadGap);
                    int bw = bxEnd - bx;
                    if (bw <= 20) continue;

                    g2.setColor(new Color(212, 218, 226));
                    g2.fill(new RoundRectangle2D.Double(bx + 2, by + 3, bw, bh, 14, 14));

                    g2.setColor(new Color(242, 245, 248));
                    g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 14, 14));

                    g2.setColor(new Color(195, 202, 212));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 14, 14));

                    boolean isParkBlock = (r == 1 && c == 1) || (rx.length == 3 && r == 2 && c == 2);
                    if (isParkBlock && bw >= 70 && bh >= 70) {
                        g2.setColor(new Color(130, 195, 140));
                        g2.fill(new RoundRectangle2D.Double(bx + 10, by + 10, bw - 20, bh - 20, 12, 12));
                        g2.setColor(new Color(110, 180, 225));
                        int pondSize = Math.min(44, Math.min(bw, bh) / 3);
                        g2.fillOval(bx + bw / 2 - pondSize / 2, by + bh / 2 - pondSize / 2, pondSize, pondSize);
                        drawTree(g2, bx + 18, by + 18);
                        drawTree(g2, bx + bw - 34, by + 18);
                        drawTree(g2, bx + 18, by + bh - 34);
                        drawTree(g2, bx + bw - 34, by + bh - 34);
                    } else {
                        drawBuilding(g2, bx + 12, by + 12, bw / 2 - 16, bh - 24,
                                (blockIdx % 2 == 0) ? new Color(180, 188, 200) : new Color(172, 182, 196));
                        drawBuilding(g2, bx + bw / 2 + 4, by + 12, bw / 2 - 16, bh - 24,
                                new Color(165, 175, 188));
                    }
                    blockIdx++;
                }
            }
        }

        private void drawBuilding(Graphics2D g2, int x, int y, int w, int h, Color topColor) {
            if (w <= 8 || h <= 8) return;
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
            g2.fillOval(x + 1, y + 2, 15, 15);
            g2.setColor(new Color(95, 175, 105));
            g2.fillOval(x, y, 15, 15);
        }

        private void drawRoadsAndMarkings(Graphics2D g2, int width, int height) {
            NetworkTopologyConfig top = topology;
            int[] rx = top.getRoadX();
            int[] ry = top.getRoadY();
            int rw = top.getRoadWidth();
            int lw = top.getLaneWidth();

            Color asphalt = new Color(52, 55, 62);
            g2.setColor(asphalt);
            for (int ix : rx) {
                g2.fillRect(ix, 0, rw, height);
            }
            for (int iy : ry) {
                g2.fillRect(0, iy, width, rw);
            }

            // Linhas de Berma
            g2.setColor(new Color(230, 235, 245, 180));
            g2.setStroke(new BasicStroke(2.0f));
            for (int ix : rx) {
                g2.drawLine(ix, 0, ix, height);
                g2.drawLine(ix + rw, 0, ix + rw, height);
            }
            for (int iy : ry) {
                g2.drawLine(0, iy, width, iy);
                g2.drawLine(0, iy + rw, width, iy + rw);
            }

            // Eixo Separador Central Duplo Amarelo
            g2.setColor(new Color(245, 195, 50));
            g2.setStroke(new BasicStroke(1.8f));
            for (int ix : rx) {
                int midX = ix + rw / 2;
                g2.drawLine(midX - 2, 0, midX - 2, height);
                g2.drawLine(midX + 2, 0, midX + 2, height);
            }
            for (int iy : ry) {
                int midY = iy + rw / 2;
                g2.drawLine(0, midY - 2, width, midY - 2);
                g2.drawLine(0, midY + 2, width, midY + 2);
            }

            // Divisores de Faixas do Mesmo Sentido (Tracejado Branco)
            g2.setColor(new Color(240, 240, 245, 200));
            Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{8, 8}, 0);
            g2.setStroke(dashed);
            for (int ix : rx) {
                g2.drawLine(ix + lw, 0, ix + lw, height);
                g2.drawLine(ix + 3 * lw, 0, ix + 3 * lw, height);
            }
            for (int iy : ry) {
                g2.drawLine(0, iy + lw, width, iy + lw);
                g2.drawLine(0, iy + 3 * lw, width, iy + 3 * lw);
            }

            // Limpar o miolo de todos os cruzamentos (4 em 2×2, 9 em 3×3)
            g2.setColor(asphalt);
            for (int ix : rx) {
                for (int iy : ry) {
                    g2.fillRect(ix, iy, rw, rw);
                }
            }

            // Stop Lines Brancas nas faixas de entrada de cada cruzamento
            g2.setStroke(new BasicStroke(3.2f));
            g2.setColor(new Color(250, 250, 255));
            for (int ix : rx) {
                for (int iy : ry) {
                    // Entrada Norte (desce na metade esquerda)
                    g2.drawLine(ix, iy - 2, ix + 2 * lw, iy - 2);
                    // Entrada Sul (sobe na metade direita)
                    g2.drawLine(ix + 2 * lw, iy + rw + 2, ix + rw, iy + rw + 2);
                    // Entrada Oeste (vai para Este na metade inferior)
                    g2.drawLine(ix - 2, iy + 2 * lw, ix - 2, iy + rw);
                    // Entrada Este (vai para Oeste na metade superior)
                    g2.drawLine(ix + rw + 2, iy, ix + rw + 2, iy + 2 * lw);
                }
            }
        }

        private void drawIntersectionsAndLights(Graphics2D g2) {
            NetworkTopologyConfig top = topology;
            int interCount = top.getIntersectionCount();
            int rw = top.getRoadWidth();

            for (int idx = 0; idx < interCount; idx++) {
                int ix = top.getIntersectionX(idx);
                int iy = top.getIntersectionY(idx);
                String name = top.getIntersectionDisplayLabel(idx);

                LightState nsState = lights[idx][0];
                LightState ewState = lights[idx][1];

                // 4 Semáforos montados no lado direito de cada aproximação
                drawTrafficLight(g2, ix - 13, iy - 22, 0, nsState);
                drawTrafficLight(g2, ix + rw + 13, iy + rw + 22, 1, nsState);
                drawTrafficLight(g2, ix - 22, iy + rw + 13, 2, ewState);
                drawTrafficLight(g2, ix + rw + 22, iy - 13, 3, ewState);

                g2.setColor(new Color(255, 255, 255, 195));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                g2.drawString(name, ix + rw / 2 - 18, iy + rw / 2 + 4);
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
                g2.rotate(v.headingAngle);

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

                // Sirene rotativa para veículos prioritários (Ambulância, Bombeiros, Polícia)
                if (v.isEmergency) {
                    boolean strobe = (tickCounter / 6) % 2 == 0;
                    Color sirenColor = strobe ? new Color(0, 140, 255) : new Color(255, 30, 30);
                    g2.setColor(sirenColor);
                    g2.fillOval(-3, -3, 6, 6);
                }

                g2.setTransform(old);

                // Etiqueta compacta com ID, Manobra A* e Destino
                g2.setColor(v.isEmergency ? new Color(180, 15, 25) : new Color(20, 25, 35));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                String labelIntent = (v.intent == ManeuverIntent.TURN_RIGHT) ? "[R]"
                        : (v.intent == ManeuverIntent.TURN_LEFT ? "[L]" : "[S]");
                String destTag = "->C" + (v.destinationIntersectionId + 1);
                g2.drawString(v.id + labelIntent + destTag, (int) v.x - 18, (int) v.y - 10);
            }
        }

        private void drawHUD(Graphics2D g2, int width) {
            NetworkTopologyConfig top = topology;
            int interCount = top.getIntersectionCount();

            int hudX = 16;
            int hudY = 10;
            int hudW = width - 32;
            int hudH = 74;

            g2.setColor(new Color(250, 252, 255, 245));
            g2.fill(new RoundRectangle2D.Double(hudX, hudY, hudW, hudH, 14, 14));

            g2.setColor(new Color(190, 200, 215));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Double(hudX, hudY, hudW, hudH, 14, 14));

            // Título Principal e Topologia
            g2.setColor(new Color(30, 40, 55));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.drawString("TrafficMAS " + top.getRows() + "x" + top.getCols(), hudX + 14, hudY + 21);

            g2.setColor(new Color(100, 115, 135));
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            g2.drawString(interCount + " Cruzamentos | " + (interCount * 2) + " Semáforos | A* + Manhattan", hudX + 14, hudY + 35);

            // Linha 2: Status do Ambiente e ML
            boolean hasAlert = globalSpeedMultiplier < 0.95;
            g2.setColor(hasAlert ? new Color(220, 50, 50) : new Color(30, 140, 60));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            String statusPrefix = hasAlert ? "[ALERTA] " : "[OK] ";
            String mlText = (mlPredictionNotice != null && !mlPredictionNotice.isEmpty()) ? " | " + mlPredictionNotice : "";
            g2.drawString(statusPrefix + disruptionNotice + mlText, hudX + 14, hudY + 58);

            long normalCars = vehicles.stream().filter(v -> !v.isEmergency).count();
            long priorityCars = vehicles.stream().filter(v -> v.isEmergency).count();
            long stoppedCars = vehicles.stream().filter(v -> v.currentSpeed < 0.2).count();
            long elapsedSec = (System.currentTimeMillis() - simulationStartMillis) / 1000;
            double avgWait = getAverageWaitSeconds();
            int jadeAgents = FleetLifecycleManager.getInstance().getLiveJadeAgentsCount();

            // Badges de Telemetria
            drawBadge(g2, hudX + 235, hudY + 10, "NORMAIS", normalCars + "/" + FleetLifecycleManager.MAX_NORMAL_VEHICLES, new Color(20, 130, 210));
            drawBadge(g2, hudX + 312, hudY + 10, "PRIORIT.", String.valueOf(priorityCars), new Color(200, 35, 65));
            drawBadge(g2, hudX + 372, hudY + 10, "JADE", String.valueOf(jadeAgents > 0 ? jadeAgents : vehicles.size()), new Color(60, 95, 180));
            drawBadge(g2, hudX + 422, hudY + 10, "PARADOS", String.valueOf(stoppedCars), (stoppedCars > 0) ? new Color(220, 60, 60) : new Color(40, 170, 90));
            drawBadge(g2, hudX + 485, hudY + 10, "ESPERA", avgWait + "s", new Color(220, 120, 20));
            drawBadge(g2, hudX + 545, hudY + 10, "DÉBITO", String.valueOf(getTotalCompletedPasses()), new Color(30, 160, 110));
            drawBadge(g2, hudX + 605, hudY + 10, "FPS / T", displayedFps + " / " + elapsedSec + "s", new Color(95, 95, 145));

            // Indicadores luminosos de todos os Cruzamentos (4 em 2×2 ou 9 em 3×3)
            int crossStartX = hudX + 685;
            int stepX = (interCount <= 4) ? 54 : 38;
            for (int i = 0; i < interCount; i++) {
                int cx = crossStartX + (i * stepX);
                if (cx + 34 > hudX + hudW - 6) break;

                g2.setColor(new Color(80, 95, 115));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.drawString(top.getIntersectionShortLabel(i), cx, hudY + 21);

                LightState ns = lights[i][0];
                g2.setColor(ns == LightState.GREEN ? new Color(40, 200, 90)
                        : (ns == LightState.YELLOW ? new Color(240, 190, 30) : new Color(230, 60, 60)));
                g2.fillRoundRect(cx, hudY + 26, 15, 7, 3, 3);

                LightState ew = lights[i][1];
                g2.setColor(ew == LightState.GREEN ? new Color(40, 200, 90)
                        : (ew == LightState.YELLOW ? new Color(240, 190, 30) : new Color(230, 60, 60)));
                g2.fillRoundRect(cx + 17, hudY + 26, 15, 7, 3, 3);
            }
        }

        private void drawBadge(Graphics2D g2, int x, int y, String title, String val, Color c) {
            g2.setColor(new Color(110, 125, 145));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
            g2.drawString(title, x, y + 10);

            g2.setColor(c);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.drawString(val, x, y + 26);
        }
    }
}
