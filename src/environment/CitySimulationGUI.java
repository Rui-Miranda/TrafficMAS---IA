package environment;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import agents.VehicleAgent;
import jade.core.Agent;

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

        // Cedência Física e Cooperação de Emergência (Fase 6.3)
        public VehicleYieldState yieldState = VehicleYieldState.NORMAL_DRIVING;
        public String yieldingToEmergencyId = null;
        public EmergencyMission mission = null;
        public int urgencyLevel = 3;
        public boolean activeMission = true;
        public boolean yieldRecorded = false;

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
                this.laneIndex = 1; // Prioritárias utilizam a faixa interior/esquerda de ultrapassagem
                this.urgencyLevel = lower.contains("pol") ? 2 : (lower.contains("bomb") ? 4 : 3);
                this.mission = new EmergencyMission("MIS_" + id, id, this.category, this.urgencyLevel, "Socorro e Emergencia Prioritaria");
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

    private static volatile CitySimulationGUI instance;

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

    // Métricas de HUD em tempo real (volatile para visibilidade cross-thread entre EDT, Main e Agentes JADE)
    private volatile long simulationStartMillis;
    private volatile long tickCounter = 0;
    private int fpsCounter = 0;
    private volatile int displayedFps = 30;
    private long lastFpsCheck = System.currentTimeMillis();

    // Métricas agregadas de desempenho (Feature 8) e estado preditivo de perturbação (Feature 5)
    private volatile long totalCompletedPasses = 0;
    private volatile double totalWaitTimeSeconds = 0.0;
    private volatile int preemptionCount = 0;
    private volatile String disruptionNotice = "Condições Normais (Atrito 100%)";
    private volatile double globalSpeedMultiplier = 1.0;
    private volatile int weatherCondition = 0; // 0=SECO, 1=CHUVA, 2=NEVOEIRO
    private volatile boolean incidentPresent = false;
    private volatile String mlPredictionNotice = "ML Preditivo: Operacional";
    private JFrame windowFrame;
    private CityPanel cityPanel;
    private JPanel controlBarPanel;
    private Timer simulationTimer;

    // =========================================================================
    // FASE 6.2 — PAINEL INTERATIVO DE GESTÃO DA FROTA (50 NORMAIS + PRIORITÁRIOS)
    // =========================================================================
    public static final int CONTROL_BAR_HEIGHT = 86;
    private JButton btnAddVehicle;
    private JButton btnRemoveVehicle;
    private JComboBox<String> comboRemoveTarget;
    private JLabel lblNormalCount;
    private JLabel lblPriorityCount;
    private JLabel lblFleetFeedback;
    private volatile String lastFleetActionMessage = "Frota inicial pronta (Limite: 50 normais | Prioritários excluídos)";
    private volatile boolean addVehicleEnabledFlag = true;
    private volatile boolean removeVehicleEnabledFlag = false;
    private boolean updatingComboItems = false;

    // =========================================================================
    // FASE 6.2 — CONTROLO MANUAL SEGURO DE SEMÁFOROS POR CLIQUE
    // =========================================================================
    private volatile int selectedIntersectionIndex = 0;
    private JButton btnManualNS;
    private JButton btnManualEW;
    private JButton btnManualAuto;
    private JLabel lblSelectedCrossing;
    private JLabel lblCrossingStates;
    private JLabel lblManualStatus;
    private volatile String lastManualActionMessage = "Modo Automático em todos os cruzamentos (Clique num cruzamento)";

    // =========================================================================
    // FASE 6.2-C — PAINEL LATERAL RESPONSIVO, ZOOM E INSPEÇÃO INTERATIVA
    // =========================================================================
    private JScrollPane mapScrollPane;
    private JPanel lateralDashboardPanel;
    private JScrollPane lateralScrollPane;

    // Zoom (0.4x a 2.5x)
    private volatile double zoomScale = 1.0;
    private JLabel lblZoom;
    private JButton btnZoomIn;
    private JButton btnZoomOut;
    private JButton btnZoomReset;
    private JButton btnZoomFit;

    // Seleção Interativa de Veículos e Inspeção
    private volatile String selectedVehicleId = null;
    private JLabel lblSelectedVehicleInfo;
    private JComboBox<String> comboDestination;
    private JButton btnApplyDestination;
    private JButton btnManeuverStraight;
    private JButton btnManeuverLeft;
    private JButton btnManeuverRight;
    private JLabel lblVehicleActionFeedback;
    private volatile String lastVehicleActionFeedback = "Nenhum veículo selecionado. Clique num veículo no mapa.";

    // Controlos em Lote de Frota
    private JButton btnBatchAdd5;
    private JButton btnBatchRemove5;

    // Indicadores e Controlo de Emergência & Cedência Física (Fase 6.3)
    private JLabel lblEmergencyStatus;
    private JComboBox<String> cmbEmergencySelect;
    private JButton btnEmergencyUrgUp;
    private JButton btnEmergencyUrgDown;
    private JButton btnEmergencyToggleMission;
    private final AtomicLong totalYieldsInitiated = new AtomicLong(0);
    private final AtomicLong totalYieldsCompleted = new AtomicLong(0);
    private final AtomicLong totalYieldsUnsafe = new AtomicLong(0);
    public static volatile boolean yieldCooperationEnabled = true;

    // Componentes de Controlo de Meteorologia e Piso (Fase 6.4)
    private JLabel lblWeatherStatus;
    private JComboBox<WeatherType> cmbWeatherSelect;
    private JComboBox<RoadCondition> cmbRoadConditionSelect;
    private JButton btnWeatherApply;
    private JButton btnRoadAutoMode;
    private JLabel lblTransitionNotice;

    // Executor assíncrono dedicado para garantir que ações JADE nunca bloqueiam o EDT do Swing
    private final ExecutorService guiActionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TrafficMAS-GUI-Worker");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> lastPendingGuiTask = null;

    /**
     * Flag de exigência estrita de autonomia: se {@code true}, viaturas normais
     * só se deslocam sob controlo de um VehicleAgent ativo via VehicleControlGateway.
     */
    private volatile boolean requireAgentAutonomy = false;

    public void setRequireAgentAutonomy(boolean require) {
        this.requireAgentAutonomy = require;
    }

    public boolean isRequireAgentAutonomy() {
        return requireAgentAutonomy;
    }

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

        simulationStartMillis = System.currentTimeMillis();
        resetLights();

        // Inicializa os painéis e componentes Swing (mesmo em modo headless para testes e capturas PNG)
        cityPanel = new CityPanel();
        cityPanel.setDoubleBuffered(true);
        cityPanel.setPreferredSize(new Dimension(topology.getWorldWidth(), topology.getWorldHeight()));
        installCanvasMouseInteraction();

        initInteractiveControlBar();
        initLateralDashboard();
        refreshFleetControlState();
        refreshVehicleInspectionState();

        if (!effectiveHeadless) {
            windowFrame = new JFrame("TrafficMAS — Intelligent Multi-Agent Urban Simulation ("
                    + topology.getRows() + "x" + topology.getCols() + " | A* Navigation | Fase 6.2-C)");
            windowFrame.setLayout(new BorderLayout());

            mapScrollPane = new JScrollPane(cityPanel);
            mapScrollPane.setBorder(BorderFactory.createEmptyBorder());
            mapScrollPane.getHorizontalScrollBar().setUnitIncrement(16);
            mapScrollPane.getVerticalScrollBar().setUnitIncrement(16);

            windowFrame.add(mapScrollPane, BorderLayout.CENTER);
            windowFrame.add(lateralScrollPane, BorderLayout.EAST);

            // Redimensionamento responsivo respeitando os limites utilizáveis do monitor
            Rectangle maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int availW = Math.max(1024, maxBounds.width - 24);
            int availH = Math.max(680, maxBounds.height - 36);
            int targetW = Math.min(availW, topology.getWorldWidth() + 440);
            int targetH = Math.min(availH, topology.getWorldHeight() + 40);
            windowFrame.setSize(targetW, targetH);
            windowFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            windowFrame.setLocationRelativeTo(null);
            windowFrame.setResizable(true);

            if (targetH < topology.getWorldHeight() || targetW < topology.getWorldWidth() + 400) {
                fitMapToWindow();
            }

            // Loop de simulação a ~30 FPS (ativo em modo gráfico)
            simulationTimer = new Timer(30, e -> {
                tickCounter++;
                int interCount = topology.getIntersectionCount();
                metrics.TrafficMetricsCollector.getInstance().advanceSimulatedTime(0.03333333333333333);
                if (!SimulationClock.isSimulatedMode()) {
                    for (int i = 0; i < interCount; i++) {
                        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                        auth.updateTick(getWaitingCount(i, 0), getWaitingCount(i, 1));
                        lights[i][0] = auth.getLightState(0);
                        lights[i][1] = auth.getLightState(1);
                    }
                }
                if (tickCounter % 30 == 0) {
                    for (int i = 0; i < interCount; i++) {
                        metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 0, getWaitingCount(i, 0));
                        metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 1, getWaitingCount(i, 1));
                    }
                }
                if (tickCounter % 5 == 0) {
                    refreshManualControlState();
                    refreshVehicleInspectionState();
                }
                calculateFps();
                updatePositions();
                cityPanel.repaint();
            });
            simulationTimer.start();
        }
    }

    /**
     * Avança um único ciclo de física e controlo (útil para execuções headless ou demonstrações controladas).
     */
    public void stepSimulationOnce() {
        tickCounter++;
        int interCount = topology.getIntersectionCount();
        metrics.TrafficMetricsCollector.getInstance().advanceSimulatedTime(0.03333333333333333);
        if (!SimulationClock.isSimulatedMode()) {
            for (int i = 0; i < interCount; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                auth.updateTick(getWaitingCount(i, 0), getWaitingCount(i, 1));
                lights[i][0] = auth.getLightState(0);
                lights[i][1] = auth.getLightState(1);
            }
        }
        if (tickCounter % 30 == 0) {
            for (int i = 0; i < interCount; i++) {
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 0, getWaitingCount(i, 0));
                metrics.TrafficMetricsCollector.getInstance().sampleQueue(i, 1, getWaitingCount(i, 1));
            }
        }
        calculateFps();
        updatePositions();
        if (cityPanel != null) {
            cityPanel.repaint();
        }
    }

    private void installCanvasMouseInteraction() {
        cityPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleCanvasClick(e.getX(), e.getY());
            }
        });
    }

    public void handleCanvasClick(int mouseX, int mouseY) {
        int worldX = (zoomScale != 1.0) ? (int) Math.round(mouseX / zoomScale) : mouseX;
        int worldY = (zoomScale != 1.0) ? (int) Math.round(mouseY / zoomScale) : mouseY;

        // 1. Prioridade: Deteção de clique em veículo
        VisualVehicle hitVehicle = hitTestVehicle(worldX, worldY);
        if (hitVehicle != null) {
            selectVehicle(hitVehicle.id);
            return;
        }

        // 2. Deteção de clique em cruzamento ou semáforo
        int hitInter = hitTestIntersection(worldX, worldY);
        if (hitInter >= 0) {
            selectIntersection(hitInter);
            return;
        }

        // 3. Fallback com coordenadas brutas (ex: HUD)
        if (hitTestIntersection(mouseX, mouseY) >= 0) {
            selectIntersection(hitTestIntersection(mouseX, mouseY));
        }
    }

    private JPanel createCardPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(34, 42, 54));
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(65, 80, 105), 1),
                " " + title + " ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 10),
                new Color(255, 205, 70)
        );
        panel.setBorder(BorderFactory.createCompoundBorder(border, new EmptyBorder(5, 7, 7, 7)));
        panel.setMaximumSize(new Dimension(410, 32767));
        return panel;
    }

    private JButton createSmallButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    private String[] buildDestinationComboItems() {
        int count = topology.getIntersectionCount();
        String[] items = new String[count];
        for (int i = 0; i < count; i++) {
            items[i] = topology.getIntersectionDisplayLabel(i);
        }
        return items;
    }

    private void initLateralDashboard() {
        lateralDashboardPanel = new JPanel();
        lateralDashboardPanel.setLayout(new BoxLayout(lateralDashboardPanel, BoxLayout.Y_AXIS));
        lateralDashboardPanel.setBackground(new Color(24, 30, 40));
        lateralDashboardPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // 1. Header Card (ML Status + Zoom)
        JPanel cardHeader = createCardPanel("SISTEMA & AMBIENTE");

        JLabel lblTitle = new JLabel("TRAFFICMAS — CONTROLO OPERACIONAL");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblTitle.setForeground(Color.WHITE);
        lblTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel mlBannerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        mlBannerPanel.setBackground(new Color(55, 42, 20));
        mlBannerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230, 160, 30), 1),
                new EmptyBorder(2, 5, 2, 5)));
        mlBannerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lblMl = new JLabel("ML 3×3 — Validação Pendente (Modelo 2x2 Preservado)");
        lblMl.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblMl.setForeground(new Color(255, 205, 80));
        mlBannerPanel.add(lblMl);

        JLabel lblJadeCount = new JLabel("Agentes JADE: 73 (50 Normais | 3 Prioritários | 18 Semáforos | 2 Centrais)");
        lblJadeCount.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblJadeCount.setForeground(new Color(180, 200, 225));
        lblJadeCount.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Zoom Toolbar
        JPanel zoomRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        zoomRow.setOpaque(false);
        zoomRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblZoom = new JLabel("Zoom: 100%");
        lblZoom.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblZoom.setForeground(new Color(200, 215, 235));

        btnZoomOut = new JButton("[-] Zoom");
        btnZoomOut.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnZoomOut.setFocusPainted(false);
        btnZoomOut.addActionListener(e -> zoomOut());

        btnZoomReset = new JButton("100%");
        btnZoomReset.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnZoomReset.setFocusPainted(false);
        btnZoomReset.addActionListener(e -> zoomReset());

        btnZoomIn = new JButton("[+] Zoom");
        btnZoomIn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnZoomIn.setFocusPainted(false);
        btnZoomIn.addActionListener(e -> zoomIn());

        btnZoomFit = new JButton("Ajustar");
        btnZoomFit.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnZoomFit.setFocusPainted(false);
        btnZoomFit.addActionListener(e -> fitMapToWindow());

        zoomRow.add(lblZoom);
        zoomRow.add(btnZoomOut);
        zoomRow.add(btnZoomReset);
        zoomRow.add(btnZoomIn);
        zoomRow.add(btnZoomFit);

        cardHeader.add(lblTitle);
        cardHeader.add(Box.createVerticalStrut(4));
        cardHeader.add(mlBannerPanel);
        cardHeader.add(Box.createVerticalStrut(4));
        cardHeader.add(lblJadeCount);
        cardHeader.add(Box.createVerticalStrut(6));
        cardHeader.add(zoomRow);

        // 2. Fleet Management Card
        JPanel cardFleet = createCardPanel("GESTÃO DA FROTA MULTIAGENTE");

        JPanel fleetCountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fleetCountRow.setOpaque(false);
        fleetCountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        fleetCountRow.add(lblNormalCount);
        fleetCountRow.add(lblPriorityCount);

        JPanel fleetBtnRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fleetBtnRow1.setOpaque(false);
        fleetBtnRow1.setAlignmentX(Component.LEFT_ALIGNMENT);
        fleetBtnRow1.add(btnAddVehicle);
        fleetBtnRow1.add(btnRemoveVehicle);
        fleetBtnRow1.add(comboRemoveTarget);

        JPanel fleetBtnRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fleetBtnRow2.setOpaque(false);
        fleetBtnRow2.setAlignmentX(Component.LEFT_ALIGNMENT);

        btnBatchAdd5 = new JButton("+5 Lote");
        btnBatchAdd5.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnBatchAdd5.setFocusPainted(false);
        btnBatchAdd5.setToolTipText("Adicionar 5 veículos normais autónomos (até ao limite de 50)");
        btnBatchAdd5.addActionListener(e -> triggerBatchAddAsync(5));

        btnBatchRemove5 = new JButton("-5 Lote");
        btnBatchRemove5.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnBatchRemove5.setFocusPainted(false);
        btnBatchRemove5.setToolTipText("Remover 5 veículos normais autónomos");
        btnBatchRemove5.addActionListener(e -> triggerBatchRemoveAsync(5));

        fleetBtnRow2.add(btnBatchAdd5);
        fleetBtnRow2.add(btnBatchRemove5);

        lblFleetFeedback.setAlignmentX(Component.LEFT_ALIGNMENT);

        cardFleet.add(fleetCountRow);
        cardFleet.add(Box.createVerticalStrut(4));
        cardFleet.add(fleetBtnRow1);
        cardFleet.add(Box.createVerticalStrut(4));
        cardFleet.add(fleetBtnRow2);
        cardFleet.add(Box.createVerticalStrut(4));
        cardFleet.add(lblFleetFeedback);

        // 3. Vehicle Inspection & Action Card
        JPanel cardVehicle = createCardPanel("INSPEÇÃO & CONTROLO DO VEÍCULO");

        lblSelectedVehicleInfo = new JLabel("<html><i>Nenhum veículo selecionado.</i><br><font color='#a0b0c5'>Clique diretamente numa viatura no mapa para inspecionar e comandar o agente.</font></html>");
        lblSelectedVehicleInfo.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblSelectedVehicleInfo.setForeground(new Color(215, 225, 240));
        lblSelectedVehicleInfo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel destRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        destRow.setOpaque(false);
        destRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lblDestTag = new JLabel("Destino:");
        lblDestTag.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblDestTag.setForeground(new Color(255, 215, 70));

        comboDestination = new JComboBox<>(buildDestinationComboItems());
        comboDestination.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        comboDestination.setPreferredSize(new Dimension(110, 22));

        btnApplyDestination = new JButton("Alterar Destino");
        btnApplyDestination.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnApplyDestination.setFocusPainted(false);
        btnApplyDestination.setEnabled(false);
        btnApplyDestination.addActionListener(e -> {
            if (selectedVehicleId != null && comboDestination != null) {
                int targetIdx = comboDestination.getSelectedIndex();
                triggerDestinationChangeAsync(selectedVehicleId, targetIdx);
            }
        });

        destRow.add(lblDestTag);
        destRow.add(comboDestination);
        destRow.add(btnApplyDestination);

        JPanel maneuverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        maneuverRow.setOpaque(false);
        maneuverRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lblManeuverTag = new JLabel("Manobra:");
        lblManeuverTag.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblManeuverTag.setForeground(new Color(95, 195, 255));

        btnManeuverStraight = new JButton("[S] Frente");
        btnManeuverStraight.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnManeuverStraight.setFocusPainted(false);
        btnManeuverStraight.setEnabled(false);
        btnManeuverStraight.addActionListener(e -> {
            if (selectedVehicleId != null) {
                triggerManeuverChangeAsync(selectedVehicleId, ManeuverIntent.GO_STRAIGHT);
            }
        });

        btnManeuverLeft = new JButton("[L] Esq.");
        btnManeuverLeft.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnManeuverLeft.setFocusPainted(false);
        btnManeuverLeft.setEnabled(false);
        btnManeuverLeft.addActionListener(e -> {
            if (selectedVehicleId != null) {
                triggerManeuverChangeAsync(selectedVehicleId, ManeuverIntent.TURN_LEFT);
            }
        });

        btnManeuverRight = new JButton("[R] Dir.");
        btnManeuverRight.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnManeuverRight.setFocusPainted(false);
        btnManeuverRight.setEnabled(false);
        btnManeuverRight.addActionListener(e -> {
            if (selectedVehicleId != null) {
                triggerManeuverChangeAsync(selectedVehicleId, ManeuverIntent.TURN_RIGHT);
            }
        });

        maneuverRow.add(lblManeuverTag);
        maneuverRow.add(btnManeuverStraight);
        maneuverRow.add(btnManeuverLeft);
        maneuverRow.add(btnManeuverRight);

        lblVehicleActionFeedback = new JLabel("Ação: " + lastVehicleActionFeedback);
        lblVehicleActionFeedback.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblVehicleActionFeedback.setForeground(new Color(210, 220, 235));
        lblVehicleActionFeedback.setAlignmentX(Component.LEFT_ALIGNMENT);

        cardVehicle.add(lblSelectedVehicleInfo);
        cardVehicle.add(Box.createVerticalStrut(6));
        cardVehicle.add(destRow);
        cardVehicle.add(Box.createVerticalStrut(4));
        cardVehicle.add(maneuverRow);
        cardVehicle.add(Box.createVerticalStrut(4));
        cardVehicle.add(lblVehicleActionFeedback);

        // 4. Semaphore Control Card
        JPanel cardSemaphore = createCardPanel("CONTROLO MANUAL SEGURO DE SEMÁFOROS");

        JPanel crossingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        crossingRow.setOpaque(false);
        crossingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        crossingRow.add(lblSelectedCrossing);
        crossingRow.add(lblCrossingStates);

        JPanel semBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        semBtnRow.setOpaque(false);
        semBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        semBtnRow.add(btnManualNS);
        semBtnRow.add(btnManualEW);
        semBtnRow.add(btnManualAuto);

        lblManualStatus.setAlignmentX(Component.LEFT_ALIGNMENT);

        cardSemaphore.add(crossingRow);
        cardSemaphore.add(Box.createVerticalStrut(4));
        cardSemaphore.add(semBtnRow);
        cardSemaphore.add(Box.createVerticalStrut(4));
        cardSemaphore.add(lblManualStatus);

        // 5. Emergency Status Card (Fase 6.3)
        JPanel cardEmergency = createCardPanel("FROTA DE EMERGÊNCIA & PRIORIDADES (Fase 6.3)");

        lblEmergencyStatus = new JLabel("<html>"
                + "🚑 <b>Ambulância INEM</b>: Urgência 3 | Estado: <font color='#50e380'>ATIVA</font><br>"
                + "🚒 <b>Bombeiros Sapadores</b>: Urgência 4 | Estado: <font color='#50e380'>ATIVA</font><br>"
                + "🚓 <b>Polícia Segurança</b>: Urgência 2 | Estado: <font color='#50e380'>ATIVA</font><br>"
                + "<font color='#5fc3ff'>Cedências Físicas: <b>0</b> concluídas | <b>0</b> ativas</font>"
                + "</html>");
        lblEmergencyStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblEmergencyStatus.setForeground(new Color(220, 230, 245));
        lblEmergencyStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardEmergency.add(lblEmergencyStatus);
        cardEmergency.add(Box.createVerticalStrut(4));

        JPanel emergCtrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        emergCtrlRow.setOpaque(false);
        emergCtrlRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        cmbEmergencySelect = new JComboBox<>(new String[] {
                "Ambulancia_INEM_01",
                "Bombeiros_Sapadores_01",
                "Viatura_Policia_01"
        });
        cmbEmergencySelect.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        cmbEmergencySelect.setBackground(new Color(25, 30, 42));
        cmbEmergencySelect.setForeground(Color.WHITE);

        btnEmergencyUrgUp = createSmallButton("Urg +", new Color(40, 150, 80));
        btnEmergencyUrgDown = createSmallButton("Urg -", new Color(200, 120, 30));
        btnEmergencyToggleMission = createSmallButton("Missão", new Color(60, 90, 160));

        btnEmergencyUrgUp.addActionListener(e -> {
            String sel = (String) cmbEmergencySelect.getSelectedItem();
            if (sel != null) {
                int cur = getVehicleUrgency(sel);
                setVehicleUrgency(sel, cur + 1);
                refreshEmergencyControlState();
            }
        });

        btnEmergencyUrgDown.addActionListener(e -> {
            String sel = (String) cmbEmergencySelect.getSelectedItem();
            if (sel != null) {
                int cur = getVehicleUrgency(sel);
                setVehicleUrgency(sel, cur - 1);
                refreshEmergencyControlState();
            }
        });

        btnEmergencyToggleMission.addActionListener(e -> {
            String sel = (String) cmbEmergencySelect.getSelectedItem();
            if (sel != null) {
                boolean active = isVehicleActiveMission(sel);
                setVehicleActiveMission(sel, !active);
                refreshEmergencyControlState();
            }
        });

        emergCtrlRow.add(cmbEmergencySelect);
        emergCtrlRow.add(btnEmergencyUrgUp);
        emergCtrlRow.add(btnEmergencyUrgDown);
        emergCtrlRow.add(btnEmergencyToggleMission);
        cardEmergency.add(emergCtrlRow);

        // 6. Meteorologia & Condições do Piso Card (Fase 6.4)
        JPanel cardWeather = createCardPanel("METEOROLOGIA & CONDIÇÕES DA VIA (Fase 6.4)");

        lblWeatherStatus = new JLabel("<html><b>Meteorologia:</b> Céu Limpo | <b>Piso:</b> Seco [Auto]<br><font color='#72d572'>Atrito: 1.00 | Velocidade Segura: 100%</font></html>");
        lblWeatherStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblWeatherStatus.setForeground(new Color(220, 230, 245));
        lblWeatherStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardWeather.add(lblWeatherStatus);
        cardWeather.add(Box.createVerticalStrut(4));

        lblTransitionNotice = new JLabel("Transição: Estável");
        lblTransitionNotice.setFont(new Font("Segoe UI", Font.ITALIC, 9));
        lblTransitionNotice.setForeground(new Color(170, 190, 215));
        lblTransitionNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardWeather.add(lblTransitionNotice);
        cardWeather.add(Box.createVerticalStrut(4));

        JPanel weatherCtrlRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        weatherCtrlRow1.setOpaque(false);
        weatherCtrlRow1.setAlignmentX(Component.LEFT_ALIGNMENT);

        cmbWeatherSelect = new JComboBox<>(WeatherType.values());
        cmbWeatherSelect.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        cmbWeatherSelect.setBackground(new Color(25, 30, 42));
        cmbWeatherSelect.setForeground(Color.WHITE);

        cmbRoadConditionSelect = new JComboBox<>(RoadCondition.values());
        cmbRoadConditionSelect.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        cmbRoadConditionSelect.setBackground(new Color(25, 30, 42));
        cmbRoadConditionSelect.setForeground(Color.WHITE);

        btnWeatherApply = createSmallButton("Aplicar", new Color(35, 120, 200));
        btnRoadAutoMode = createSmallButton("Piso Auto", new Color(40, 150, 90));

        btnWeatherApply.addActionListener(e -> {
            WeatherType wt = (WeatherType) cmbWeatherSelect.getSelectedItem();
            RoadCondition rc = (RoadCondition) cmbRoadConditionSelect.getSelectedItem();
            guiActionExecutor.submit(() -> {
                if (wt != null) {
                    WeatherEnvironmentManager.getInstance().setWeather(wt);
                }
                if (rc != null && rc != WeatherEnvironmentManager.getInstance().getSnapshot().getRoadCondition()) {
                    WeatherEnvironmentManager.getInstance().setManualRoadCondition(rc);
                }
                SwingUtilities.invokeLater(this::refreshWeatherControlState);
            });
        });

        btnRoadAutoMode.addActionListener(e -> {
            guiActionExecutor.submit(() -> {
                WeatherEnvironmentManager.getInstance().returnToAutomaticRoadMode();
                SwingUtilities.invokeLater(this::refreshWeatherControlState);
            });
        });

        weatherCtrlRow1.add(new JLabel("Céu:"));
        weatherCtrlRow1.add(cmbWeatherSelect);
        weatherCtrlRow1.add(new JLabel("Piso:"));
        weatherCtrlRow1.add(cmbRoadConditionSelect);

        JPanel weatherCtrlRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        weatherCtrlRow2.setOpaque(false);
        weatherCtrlRow2.setAlignmentX(Component.LEFT_ALIGNMENT);
        weatherCtrlRow2.add(btnWeatherApply);
        weatherCtrlRow2.add(btnRoadAutoMode);

        cardWeather.add(weatherCtrlRow1);
        cardWeather.add(Box.createVerticalStrut(4));
        cardWeather.add(weatherCtrlRow2);

        // Assemble into lateralDashboardPanel with spacing
        lateralDashboardPanel.add(cardHeader);
        lateralDashboardPanel.add(Box.createVerticalStrut(6));
        lateralDashboardPanel.add(cardFleet);
        lateralDashboardPanel.add(Box.createVerticalStrut(6));
        lateralDashboardPanel.add(cardVehicle);
        lateralDashboardPanel.add(Box.createVerticalStrut(6));
        lateralDashboardPanel.add(cardSemaphore);
        lateralDashboardPanel.add(Box.createVerticalStrut(6));
        lateralDashboardPanel.add(cardEmergency);
        lateralDashboardPanel.add(Box.createVerticalStrut(6));
        lateralDashboardPanel.add(cardWeather);
        lateralDashboardPanel.add(Box.createVerticalGlue());

        lateralScrollPane = new JScrollPane(lateralDashboardPanel);
        lateralScrollPane.setPreferredSize(new Dimension(420, topology.getWorldHeight()));
        lateralScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        lateralScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        lateralScrollPane.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(50, 65, 85)));
        lateralScrollPane.getVerticalScrollBar().setUnitIncrement(16);
    }

    private void initInteractiveControlBar() {
        controlBarPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        controlBarPanel.setBackground(new Color(28, 34, 44));
        controlBarPanel.setBorder(new EmptyBorder(6, 12, 6, 12));
        controlBarPanel.setPreferredSize(new Dimension(topology.getWorldWidth(), CONTROL_BAR_HEIGHT));

        // 1. Painel Esquerdo: Gestão Interativa da Frota (50 Normais + Prioritários Excluídos)
        JPanel fleetPanel = new JPanel(new GridLayout(2, 1, 2, 3));
        fleetPanel.setBackground(new Color(38, 46, 58));
        fleetPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 90, 118), 1),
                new EmptyBorder(4, 8, 4, 8)));

        JPanel fleetTopRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        fleetTopRow.setOpaque(false);

        lblNormalCount = new JLabel("Normais: 0 / 50 (Máx: 50)");
        lblNormalCount.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblNormalCount.setForeground(new Color(95, 195, 255));

        lblPriorityCount = new JLabel("| Prioritários: 0 (Excluídos)");
        lblPriorityCount.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblPriorityCount.setForeground(new Color(255, 110, 125));

        btnAddVehicle = new JButton("+ Adicionar Veículo");
        btnAddVehicle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnAddVehicle.setFocusPainted(false);
        btnAddVehicle.addActionListener(e -> triggerAddNormalVehicleAsync());

        btnRemoveVehicle = new JButton("- Remover Veículo");
        btnRemoveVehicle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnRemoveVehicle.setFocusPainted(false);
        btnRemoveVehicle.addActionListener(e -> triggerRemoveNormalVehicleAsync());

        comboRemoveTarget = new JComboBox<>(new String[] { "Último Normal" });
        comboRemoveTarget.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        comboRemoveTarget.setPreferredSize(new Dimension(112, 22));

        fleetTopRow.add(lblNormalCount);
        fleetTopRow.add(lblPriorityCount);
        fleetTopRow.add(btnAddVehicle);
        fleetTopRow.add(btnRemoveVehicle);
        fleetTopRow.add(comboRemoveTarget);

        lblFleetFeedback = new JLabel("Estado Frota: " + lastFleetActionMessage);
        lblFleetFeedback.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblFleetFeedback.setForeground(new Color(210, 220, 235));

        fleetPanel.add(fleetTopRow);
        fleetPanel.add(lblFleetFeedback);

        // 2. Painel Direito: Controlo Manual Seguro dos Semáforos por Clique
        JPanel manualPanel = new JPanel(new GridLayout(2, 1, 2, 3));
        manualPanel.setBackground(new Color(38, 46, 58));
        manualPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 90, 118), 1),
                new EmptyBorder(4, 8, 4, 8)));

        JPanel manualTopRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        manualTopRow.setOpaque(false);

        lblSelectedCrossing = new JLabel("Cruzamento: C1 (NW)");
        lblSelectedCrossing.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblSelectedCrossing.setForeground(new Color(255, 215, 70));

        lblCrossingStates = new JLabel("| NS: GREEN | EW: RED | AUTO");
        lblCrossingStates.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblCrossingStates.setForeground(new Color(180, 235, 195));

        btnManualNS = new JButton("Verde NS");
        btnManualNS.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnManualNS.setFocusPainted(false);
        btnManualNS.addActionListener(e -> triggerManualPhaseAsync(selectedIntersectionIndex, 0));

        btnManualEW = new JButton("Verde EW");
        btnManualEW.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnManualEW.setFocusPainted(false);
        btnManualEW.addActionListener(e -> triggerManualPhaseAsync(selectedIntersectionIndex, 1));

        btnManualAuto = new JButton("Modo Automático");
        btnManualAuto.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnManualAuto.setFocusPainted(false);
        btnManualAuto.addActionListener(e -> triggerReturnToAutoAsync(selectedIntersectionIndex));

        manualTopRow.add(lblSelectedCrossing);
        manualTopRow.add(lblCrossingStates);
        manualTopRow.add(btnManualNS);
        manualTopRow.add(btnManualEW);
        manualTopRow.add(btnManualAuto);

        lblManualStatus = new JLabel("Controlo Semafórico: " + lastManualActionMessage);
        lblManualStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblManualStatus.setForeground(new Color(210, 220, 235));

        manualPanel.add(manualTopRow);
        manualPanel.add(lblManualStatus);

        controlBarPanel.add(fleetPanel);
        controlBarPanel.add(manualPanel);
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
        this.selectedVehicleId = null;
        if (selectedIntersectionIndex >= newTopology.getIntersectionCount()) {
            selectedIntersectionIndex = 0;
        }
        resetLights();
        if (comboDestination != null) {
            comboDestination.removeAllItems();
            for (String item : buildDestinationComboItems()) {
                comboDestination.addItem(item);
            }
        }
        refreshControlPanels();
        if (!java.awt.GraphicsEnvironment.isHeadless() && windowFrame != null) {
            windowFrame.setTitle("TrafficMAS — Intelligent Multi-Agent Urban Simulation ("
                    + topology.getRows() + "x" + topology.getCols() + " | A* Navigation | Fase 6.2-C)");
            if (cityPanel != null) {
                cityPanel.setPreferredSize(new Dimension(
                        (int) Math.round(topology.getWorldWidth() * zoomScale),
                        (int) Math.round(topology.getWorldHeight() * zoomScale)
                ));
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

    public static CitySimulationGUI peekInstance() {
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

    public void setTrafficLights(int intersectionIndex, LightState nsState, LightState ewState) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length) {
            lights[intersectionIndex][0] = nsState;
            lights[intersectionIndex][1] = ewState;
            IntersectionSafetyAuthority.assertSafetyInvariant(lights[intersectionIndex][0], lights[intersectionIndex][1]);
        }
    }

    public void setTrafficLight(int intersectionIndex, int axis, LightState state) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length && axis >= 0 && axis <= 1) {
            lights[intersectionIndex][axis] = state;
            IntersectionSafetyAuthority.assertSafetyInvariant(lights[intersectionIndex][0], lights[intersectionIndex][1]);
        }
    }

    public LightState getTrafficLightState(int intersectionIndex, int axis) {
        if (intersectionIndex >= 0 && intersectionIndex < lights.length && axis >= 0 && axis <= 1) {
            return lights[intersectionIndex][axis];
        }
        return LightState.RED;
    }

    public void resetLights() {
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

        // Regra Fase 6.2: A contagem de veículos normais nunca pode ultrapassar 50 (prioritários excluídos)
        if (!isEmergency) {
            long currentNormals = vehicles.stream().filter(vv -> !vv.isEmergency).count();
            if (currentNormals >= FleetLifecycleManager.MAX_NORMAL_VEHICLES) {
                lastFleetActionMessage = "Limite máximo atingido (50/50 veículos normais). Adição bloqueada.";
                refreshFleetControlState();
                return null;
            }
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
        refreshFleetControlState();
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
        VehicleControlGateway.getInstance().unregisterAgent(vehicleId);
        boolean removed = vehicles.removeIf(v -> vehicleId.equals(v.id));
        if (removed) {
            refreshFleetControlState();
        }
        return removed;
    }

    /**
     * Remove todos os veículos visuais (usado em reinicializações de testes).
     */
    public synchronized void clearVehicles() {
        vehicles.clear();
        spawnSequence.set(0);
        VehicleControlGateway.getInstance().clear();
        refreshFleetControlState();
    }

    public List<VisualVehicle> getVisualVehiclesSnapshot() {
        return new ArrayList<>(vehicles);
    }

    /* =========================================================================
     * FASE 6.2 — API INTERATIVA DE GESTÃO DA FROTA E CONTROLO MANUAL DE SEMÁFOROS
     * ========================================================================= */

    public void refreshControlPanels() {
        refreshFleetControlState();
        refreshManualControlState();
        refreshVehicleInspectionState();
        refreshEmergencyControlState();
        refreshWeatherControlState();
    }

    /**
     * Atualiza o estado visual dos botões (+ Adicionar / - Remover), contadores e lista de seleção,
     * refletindo rigorosamente a contagem real de veículos normais (0..50) e prioritários.
     */
    public void refreshFleetControlState() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        final int maxNormal = fleet.getMaxNormalVehicles();
        final int normalCount;
        final int priorityCount;

        if (fleet.getAgentContainer() != null && (fleet.getActiveNormalVehicleCount() > 0 || fleet.getActivePriorityVehicleCount() > 0 || vehicles.isEmpty())) {
            normalCount = fleet.getActiveNormalVehicleCount();
            priorityCount = fleet.getActivePriorityVehicleCount();
        } else {
            normalCount = (int) vehicles.stream().filter(v -> !v.isEmergency).count();
            priorityCount = (int) vehicles.stream().filter(v -> v.isEmergency).count();
        }

        final boolean canAdd = (normalCount < maxNormal);
        final boolean canRemove = (normalCount > 0);
        this.addVehicleEnabledFlag = canAdd;
        this.removeVehicleEnabledFlag = canRemove;
        final List<String> ids = (fleet.getAgentContainer() != null && !fleet.getActiveNormalVehicleIds().isEmpty())
                ? fleet.getActiveNormalVehicleIds()
                : getVisualNormalVehicleIds();

        Runnable uiUpdater = () -> {
            if (btnAddVehicle != null) {
                btnAddVehicle.setEnabled(canAdd);
                btnAddVehicle.setToolTipText(canAdd
                        ? "Adicionar 1 veículo normal autónomo JADE (" + normalCount + "/" + maxNormal + ")"
                        : "Limite máximo de " + maxNormal + " veículos normais atingido (Desativado)");
            }
            if (btnRemoveVehicle != null) {
                btnRemoveVehicle.setEnabled(canRemove);
                btnRemoveVehicle.setToolTipText(canRemove
                        ? "Remover veículo normal selecionado e encerrar agente JADE"
                        : "Sem veículos normais ativos para remover (0/" + maxNormal + ")");
            }
            if (lblNormalCount != null) {
                lblNormalCount.setText("Normais: " + normalCount + " / " + maxNormal + " (Máx: " + maxNormal + ")");
            }
            if (lblPriorityCount != null) {
                lblPriorityCount.setText("| Prioritários: " + priorityCount + " (Excluídos)");
            }
            if (lblFleetFeedback != null) {
                lblFleetFeedback.setText("Estado Frota: " + lastFleetActionMessage);
            }

            if (comboRemoveTarget != null && !updatingComboItems) {
                updatingComboItems = true;
                try {
                    Object prevSelected = comboRemoveTarget.getSelectedItem();
                    comboRemoveTarget.removeAllItems();
                    comboRemoveTarget.addItem("Último Normal");
                    for (String id : ids) {
                        comboRemoveTarget.addItem(id);
                    }
                    if (prevSelected != null) {
                        comboRemoveTarget.setSelectedItem(prevSelected);
                    }
                } finally {
                    updatingComboItems = false;
                }
            }
        };

        if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(uiUpdater);
        } else {
            uiUpdater.run();
        }
    }

    private List<String> getVisualNormalVehicleIds() {
        List<String> list = new ArrayList<>();
        for (VisualVehicle v : vehicles) {
            if (!v.isEmergency) {
                list.add(v.id);
            }
        }
        return list;
    }

    /**
     * Atualiza o painel de controlo manual semafórico para refletir o cruzamento selecionado,
     * o estado real efetivamente aplicado (NS/EW), eventual pedido pendente (ACCEPTED) ou aplicado (APPLIED),
     * modo atual (AUTOMÁTICO / MANUAL) e recusa/preempção por emergência.
     */
    public void refreshManualControlState() {
        int count = topology.getIntersectionCount();
        if (selectedIntersectionIndex < 0 || selectedIntersectionIndex >= count) {
            selectedIntersectionIndex = 0;
        }
        final int idx = selectedIntersectionIndex;
        final String label = topology.getIntersectionDisplayLabel(idx);
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(idx);

        final LightState nsReal = auth.getLightState(0);
        final LightState ewReal = auth.getLightState(1);
        lights[idx][0] = nsReal;
        lights[idx][1] = ewReal;

        final boolean emActive = auth.isEmergencyActive();
        final boolean manualActive = auth.isManualModeActive();
        final IntersectionSafetyAuthority.ManualOverrideRequest activeReq = auth.getActiveManualRequest();
        final IntersectionSafetyAuthority.ManualOverrideRequest lastReq = auth.getLastManualRequest();

        final String modeTag;
        if (emActive) {
            modeTag = "EMERGÊNCIA PRIORITÁRIA";
        } else if (manualActive && activeReq != null) {
            if (activeReq.getState() == IntersectionSafetyAuthority.ManualRequestState.ACCEPTED) {
                modeTag = "MANUAL (PENDENTE: Transição Segura)";
            } else {
                modeTag = "MANUAL (ATIVO: Eixo " + activeReq.getTargetAxisName() + ")";
            }
        } else {
            modeTag = "AUTOMÁTICO";
        }

        final String statusDetail;
        if (activeReq != null) {
            statusDetail = "Pedido " + activeReq.requestId + " [" + activeReq.getState() + "] — " + activeReq.getStatusReason();
        } else if (lastReq != null) {
            statusDetail = "Último: " + lastReq.requestId + " [" + lastReq.getState() + "] — " + lastReq.getStatusReason();
        } else {
            statusDetail = lastManualActionMessage;
        }

        Runnable uiUpdater = () -> {
            if (lblSelectedCrossing != null) {
                lblSelectedCrossing.setText("Cruzamento: " + label);
            }
            if (lblCrossingStates != null) {
                lblCrossingStates.setText("| NS: " + nsReal + " | EW: " + ewReal + " | " + modeTag);
            }
            if (btnManualAuto != null) {
                btnManualAuto.setEnabled(manualActive || activeReq != null);
            }
            if (lblManualStatus != null) {
                lblManualStatus.setText("Controlo Semafórico: " + statusDetail);
            }
        };

        if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(uiUpdater);
        } else {
            uiUpdater.run();
        }
    }

    /**
     * Executa adição interativa de veículo normal sem bloquear o Event Dispatch Thread (EDT) do Swing.
     */
    public void triggerAddNormalVehicleAsync() {
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performInteractiveAddNormalVehicle();
                SwingUtilities.invokeLater(() -> {
                    refreshControlPanels();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performInteractiveAddNormalVehicle();
            refreshControlPanels();
        }
    }

    /**
     * Executa remoção interativa de veículo normal sem bloquear o Event Dispatch Thread (EDT) do Swing.
     */
    public void triggerRemoveNormalVehicleAsync() {
        final String selectedTarget = getSelectedVehicleTargetForRemoval();
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performInteractiveRemoveNormalVehicle(selectedTarget);
                SwingUtilities.invokeLater(() -> {
                    refreshControlPanels();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performInteractiveRemoveNormalVehicle(selectedTarget);
            refreshControlPanels();
        }
    }

    private String getSelectedVehicleTargetForRemoval() {
        if (comboRemoveTarget != null) {
            Object item = comboRemoveTarget.getSelectedItem();
            if (item != null) {
                String s = item.toString().trim();
                if (!s.isEmpty() && !s.startsWith("Último")) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Operação síncrona de adição interativa usando {@link FleetLifecycleManager#addNormalVehicle()}.
     */
    public FleetLifecycleManager.LifecycleOperationResult performInteractiveAddNormalVehicle() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        String addedId = fleet.addNormalVehicle();
        boolean ok = (addedId != null);
        String msg = ok
                ? ("Adicionado com sucesso: " + addedId)
                : ("Limite máximo atingido (" + fleet.getMaxNormalVehicles() + " veículos normais). Adição bloqueada.");
        FleetLifecycleManager.LifecycleOperationResult res = new FleetLifecycleManager.LifecycleOperationResult(
                ok, addedId, fleet.getActiveNormalVehicleCount(), fleet.getActivePriorityVehicleCount(), msg);
        this.lastFleetActionMessage = res.message + " (Normais: " + res.activeNormalCount + "/" + fleet.getMaxNormalVehicles() + ")";
        refreshFleetControlState();
        return res;
    }

    /**
     * Operação síncrona de remoção interativa usando {@link FleetLifecycleManager#removeNormalVehicle()}.
     */
    public FleetLifecycleManager.LifecycleOperationResult performInteractiveRemoveNormalVehicle(String specificVehicleId) {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        String removedId;
        boolean ok;
        if (specificVehicleId != null && !specificVehicleId.isEmpty()) {
            ok = fleet.removeNormalVehicle(specificVehicleId);
            removedId = ok ? specificVehicleId : null;
        } else {
            removedId = fleet.removeNormalVehicle();
            ok = (removedId != null);
        }
        String msg = ok
                ? ("Removido com sucesso: " + removedId)
                : "Sem veículos normais ativos para remover (0/50) ou ID inválido.";
        FleetLifecycleManager.LifecycleOperationResult res = new FleetLifecycleManager.LifecycleOperationResult(
                ok, removedId, fleet.getActiveNormalVehicleCount(), fleet.getActivePriorityVehicleCount(), msg);
        this.lastFleetActionMessage = res.message + " (Normais: " + res.activeNormalCount + "/" + fleet.getMaxNormalVehicles() + ")";
        refreshFleetControlState();
        return res;
    }

    public boolean isAddVehicleButtonEnabled() {
        refreshFleetControlState();
        return addVehicleEnabledFlag;
    }

    public boolean isRemoveVehicleButtonEnabled() {
        refreshFleetControlState();
        return removeVehicleEnabledFlag;
    }

    public JButton getBtnAddVehicle() {
        return btnAddVehicle;
    }

    public JButton getBtnRemoveVehicle() {
        return btnRemoveVehicle;
    }

    public JButton getBtnManualNS() {
        return btnManualNS;
    }

    public JButton getBtnManualEW() {
        return btnManualEW;
    }

    public JButton getBtnManualAuto() {
        return btnManualAuto;
    }

    public String getFleetFeedbackText() {
        return (lblFleetFeedback != null) ? lblFleetFeedback.getText() : lastFleetActionMessage;
    }

    public String getManualStatusText() {
        return (lblManualStatus != null) ? lblManualStatus.getText() : lastManualActionMessage;
    }

    public String getSelectedCrossingStatesText() {
        return (lblCrossingStates != null) ? lblCrossingStates.getText() : "";
    }

    /**
     * Determina qual o cruzamento clicado nas coordenadas (mouseX, mouseY) do painel gráfico.
     * Suporta clique direto na caixa do cruzamento, nos 4 postes semafóricos do cruzamento,
     * ou no indicador correspondente do HUD superior.
     *
     * @return índice do cruzamento (0..N-1) ou -1 se o clique não ocorreu sobre nenhum cruzamento.
     */
    public int hitTestIntersection(int mouseX, int mouseY) {
        NetworkTopologyConfig top = this.topology;
        int interCount = top.getIntersectionCount();
        int rw = top.getRoadWidth();
        int margin = 34; // Inclui os 4 postes de semáforos montados nas esquinas do cruzamento

        for (int idx = 0; idx < interCount; idx++) {
            int ix = top.getIntersectionX(idx);
            int iy = top.getIntersectionY(idx);
            if (mouseX >= (ix - margin) && mouseX <= (ix + rw + margin)
                    && mouseY >= (iy - margin) && mouseY <= (iy + rw + margin)) {
                return idx;
            }
        }

        // Verifica também os indicadores luminosos do HUD superior
        int hudX = 16;
        int hudY = 10;
        int crossStartX = hudX + 685;
        int stepX = (interCount <= 4) ? 54 : 38;
        for (int i = 0; i < interCount; i++) {
            int cx = crossStartX + (i * stepX);
            if (mouseX >= cx - 2 && mouseX <= cx + 35 && mouseY >= hudY + 8 && mouseY <= hudY + 42) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Seleciona um cruzamento através de um clique nas coordenadas (mouseX, mouseY).
     * IMPORTANTE: O clique apenas seleciona o cruzamento para controlo; NUNCA altera diretamente a cor no ecrã.
     */
    public int selectIntersectionByClick(int mouseX, int mouseY) {
        int worldX = (zoomScale != 1.0) ? (int) Math.round(mouseX / zoomScale) : mouseX;
        int worldY = (zoomScale != 1.0) ? (int) Math.round(mouseY / zoomScale) : mouseY;
        int hit = hitTestIntersection(worldX, worldY);
        if (hit < 0 && (worldX != mouseX || worldY != mouseY)) {
            hit = hitTestIntersection(mouseX, mouseY);
        }
        if (hit >= 0) {
            this.selectedIntersectionIndex = hit;
            this.lastManualActionMessage = "Cruzamento " + topology.getIntersectionDisplayLabel(hit)
                    + " selecionado por clique. Escolha [Verde NS], [Verde EW] ou [Modo Automático].";
            refreshManualControlState();
            if (cityPanel != null) {
                cityPanel.repaint();
            }
        }
        return hit;
    }

    public void selectIntersection(int intersectionIndex) {
        if (intersectionIndex >= 0 && intersectionIndex < topology.getIntersectionCount()) {
            this.selectedIntersectionIndex = intersectionIndex;
            this.lastManualActionMessage = "Cruzamento " + topology.getIntersectionDisplayLabel(intersectionIndex) + " selecionado.";
            refreshManualControlState();
            if (cityPanel != null) {
                cityPanel.repaint();
            }
        }
    }

    public int getSelectedIntersectionIndex() {
        return selectedIntersectionIndex;
    }

    public void triggerManualPhaseAsync(int intersectionIdx, int targetAxis) {
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                requestManualPhaseFromGui(intersectionIdx, targetAxis);
                SwingUtilities.invokeLater(() -> {
                    refreshManualControlState();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            requestManualPhaseFromGui(intersectionIdx, targetAxis);
        }
    }

    public void triggerReturnToAutoAsync(int intersectionIdx) {
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                returnToAutomaticFromGui(intersectionIdx);
                SwingUtilities.invokeLater(() -> {
                    refreshManualControlState();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            returnToAutomaticFromGui(intersectionIdx);
        }
    }

    /**
     * Submete pedido manual através do {@link TrafficEnvironment} e {@link IntersectionSafetyAuthority}.
     * NUNCA altera diretamente as cores na GUI: a mudança visual só ocorre quando a Autoridade de Segurança
     * executa as transições seguras (YELLOW -> ALL_RED -> GREEN).
     */
    public IntersectionSafetyAuthority.ManualOverrideRequest requestManualPhaseFromGui(int intersectionIdx, int targetAxis) {
        selectIntersection(intersectionIdx);
        IntersectionSafetyAuthority.ManualOverrideRequest req =
                TrafficEnvironment.getInstance().requestManualTrafficLightPhase(intersectionIdx, targetAxis);
        if (req != null) {
            this.lastManualActionMessage = "Pedido " + req.requestId + " -> " + req.getState() + " (" + req.getStatusReason() + ")";
        }
        refreshManualControlState();
        return req;
    }

    /**
     * Regressa o cruzamento selecionado ao modo automático através do {@link TrafficEnvironment} e {@link IntersectionSafetyAuthority}.
     */
    public boolean returnToAutomaticFromGui(int intersectionIdx) {
        selectIntersection(intersectionIdx);
        IntersectionSafetyAuthority.ManualOverrideRequest prev =
                TrafficEnvironment.getInstance().returnTrafficLightToAutomatic(intersectionIdx);
        boolean changed = (prev != null);
        this.lastManualActionMessage = "Cruzamento " + topology.getIntersectionDisplayLabel(intersectionIdx)
                + " em MODO AUTOMÁTICO.";
        refreshManualControlState();
        return changed;
    }

    /**
     * Aguarda a conclusão de tarefas submetidas ao executor assíncrono da GUI (útil em testes de integração).
     */
    public boolean awaitPendingGuiActions(long timeoutMs) {
        try {
            Future<?> task = this.lastPendingGuiTask;
            if (task != null) {
                task.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* =========================================================================
     * FASE 6.2-C — API INTERATIVA DE ZOOM, SELEÇÃO E CONTROLO DE VEÍCULOS
     * ========================================================================= */

    public double getZoomScale() {
        return zoomScale;
    }

    public void setZoomScale(double scale) {
        this.zoomScale = Math.max(0.4, Math.min(2.5, scale));
        if (cityPanel != null) {
            cityPanel.setPreferredSize(new Dimension(
                    (int) Math.round(topology.getWorldWidth() * zoomScale),
                    (int) Math.round(topology.getWorldHeight() * zoomScale)
            ));
            cityPanel.revalidate();
            cityPanel.repaint();
        }
        if (lblZoom != null) {
            lblZoom.setText(String.format(Locale.ROOT, "Zoom: %d%%", (int) Math.round(zoomScale * 100)));
        }
    }

    public void zoomIn() {
        setZoomScale(zoomScale + 0.15);
    }

    public void zoomOut() {
        setZoomScale(zoomScale - 0.15);
    }

    public void zoomReset() {
        setZoomScale(1.0);
    }

    public void fitMapToWindow() {
        if (windowFrame != null && cityPanel != null) {
            int availW = Math.max(200, windowFrame.getContentPane().getWidth() - 440);
            int availH = Math.max(200, windowFrame.getContentPane().getHeight() - 40);
            double scaleW = (double) availW / topology.getWorldWidth();
            double scaleH = (double) availH / topology.getWorldHeight();
            double fitScale = Math.min(scaleW, scaleH);
            setZoomScale(fitScale);
        }
    }

    public VisualVehicle hitTestVehicle(int worldX, int worldY) {
        double minDistanceSq = 24.0 * 24.0;
        VisualVehicle best = null;
        double bestDistSq = minDistanceSq;
        for (VisualVehicle v : vehicles) {
            double dx = v.x - worldX;
            double dy = v.y - worldY;
            double distSq = dx * dx + dy * dy;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = v;
            }
        }
        return best;
    }

    public VisualVehicle selectVehicleByClick(int mouseX, int mouseY) {
        int worldX = (zoomScale != 1.0) ? (int) Math.round(mouseX / zoomScale) : mouseX;
        int worldY = (zoomScale != 1.0) ? (int) Math.round(mouseY / zoomScale) : mouseY;
        VisualVehicle hit = hitTestVehicle(worldX, worldY);
        if (hit == null && (worldX != mouseX || worldY != mouseY)) {
            hit = hitTestVehicle(mouseX, mouseY);
        }
        if (hit != null) {
            selectVehicle(hit.id);
        }
        return hit;
    }

    public void selectVehicle(String vehicleId) {
        this.selectedVehicleId = vehicleId;
        if (vehicleId != null) {
            VisualVehicle v = getVisualVehicle(vehicleId);
            String cat = (v != null) ? v.category : "NORMAL";
            this.lastVehicleActionFeedback = "Veículo " + vehicleId + " [" + cat + "] selecionado.";
        } else {
            this.lastVehicleActionFeedback = "Nenhum veículo selecionado. Clique num veículo no mapa.";
        }
        if (lblVehicleActionFeedback != null) {
            lblVehicleActionFeedback.setText("Ação: " + lastVehicleActionFeedback);
        }
        refreshVehicleInspectionState();
        if (cityPanel != null) {
            cityPanel.repaint();
        }
    }

    public void clearVehicleSelection() {
        selectVehicle(null);
    }

    public String getSelectedVehicleId() {
        return selectedVehicleId;
    }

    public VisualVehicle getSelectedVehicle() {
        return (selectedVehicleId != null) ? getVisualVehicle(selectedVehicleId) : null;
    }

    public void refreshVehicleInspectionState() {
        final String selId = this.selectedVehicleId;
        final VisualVehicle v = (selId != null) ? getVisualVehicle(selId) : null;

        Runnable uiUpdater = () -> {
            if (v != null) {
                String cat = v.category;
                String status = v.inIntersection ? "No Cruzamento (A virar)" : (v.currentSpeed < 0.2 ? "Travado / Espera" : "Em Movimento");
                String speedStr = String.format(Locale.ROOT, "%.1f km/h", v.currentSpeed * 10.0);
                String laneStr = (v.laneIndex == 0) ? "Exterior (Direita)" : "Interior (Esquerda)";
                String origStr = "C" + (v.originIntersectionId + 1);
                String destStr = "C" + (v.destinationIntersectionId + 1);
                String intentStr = v.intent.name();

                StringBuilder routeSb = new StringBuilder();
                if (v.activeRoute != null && v.activeRoute.nodePath != null && !v.activeRoute.nodePath.isEmpty()) {
                    for (int i = 0; i < v.activeRoute.nodePath.size(); i++) {
                        if (i > 0) routeSb.append(" -> ");
                        routeSb.append("C").append(v.activeRoute.nodePath.get(i) + 1);
                    }
                } else {
                    routeSb.append(origStr).append(" -> ").append(destStr);
                }

                if (lblSelectedVehicleInfo != null) {
                    lblSelectedVehicleInfo.setText("<html><b>Veículo:</b> <font color='#5fc3ff'>" + v.id + "</font> (" + cat + ") | <b>Velocidade:</b> " + speedStr + "<br>"
                            + "<b>Faixa:</b> " + laneStr + " | <b>Estado:</b> " + status + "<br>"
                            + "<b>Origem:</b> " + origStr + " -> <b>Destino:</b> " + destStr + " | <b>Manobra:</b> " + intentStr + "<br>"
                            + "<b>Rota A*:</b> <font color='#ffd746'>" + routeSb + "</font></html>");
                }
                if (btnApplyDestination != null) btnApplyDestination.setEnabled(true);
                if (btnManeuverStraight != null) btnManeuverStraight.setEnabled(true);
                if (btnManeuverLeft != null) btnManeuverLeft.setEnabled(true);
                if (btnManeuverRight != null) btnManeuverRight.setEnabled(true);
                if (comboDestination != null && v.destinationIntersectionId >= 0 && v.destinationIntersectionId < comboDestination.getItemCount()) {
                    if (!comboDestination.hasFocus()) {
                        comboDestination.setSelectedIndex(v.destinationIntersectionId);
                    }
                }
            } else {
                if (lblSelectedVehicleInfo != null) {
                    lblSelectedVehicleInfo.setText("<html><i>Nenhum veículo selecionado.</i><br><font color='#a0b0c5'>Clique diretamente numa viatura no mapa para inspecionar e comandar o agente.</font></html>");
                }
                if (btnApplyDestination != null) btnApplyDestination.setEnabled(false);
                if (btnManeuverStraight != null) btnManeuverStraight.setEnabled(false);
                if (btnManeuverLeft != null) btnManeuverLeft.setEnabled(false);
                if (btnManeuverRight != null) btnManeuverRight.setEnabled(false);
            }
            if (lblVehicleActionFeedback != null) {
                lblVehicleActionFeedback.setText("Ação: " + lastVehicleActionFeedback);
            }
        };

        if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(uiUpdater);
        } else {
            uiUpdater.run();
        }
    }

    public VehicleAgent.DestinationChangeResult performInteractiveDestinationChange(String vehicleId, int newDestinationNode) {
        if (vehicleId == null) {
            return new VehicleAgent.DestinationChangeResult(false, "Nenhum veículo selecionado", -1, -1, null);
        }
        Agent ag = VehicleControlGateway.getInstance().getAgent(vehicleId);
        if (ag instanceof VehicleAgent) {
            VehicleAgent vag = (VehicleAgent) ag;
            VehicleAgent.DestinationChangeResult res = vag.requestDestinationChange(newDestinationNode);
            this.lastVehicleActionFeedback = "Destino " + vehicleId + ": " + res.message;
            if (res.accepted) {
                VisualVehicle vv = getVisualVehicle(vehicleId);
                if (vv != null) {
                    vv.destinationIntersectionId = res.newDestination;
                    if (res.recalculatedRoute != null) {
                        vv.activeRoute = res.recalculatedRoute;
                    }
                }
            }
            refreshVehicleInspectionState();
            if (cityPanel != null) cityPanel.repaint();
            return res;
        } else {
            VisualVehicle vv = getVisualVehicle(vehicleId);
            if (vv != null) {
                if (newDestinationNode < 0 || newDestinationNode >= topology.getIntersectionCount()) {
                    return new VehicleAgent.DestinationChangeResult(false, "Destino inválido: C" + (newDestinationNode + 1), vv.destinationIntersectionId, newDestinationNode, null);
                }
                vv.destinationIntersectionId = newDestinationNode;
                this.lastVehicleActionFeedback = "Destino de " + vehicleId + " alterado para C" + (newDestinationNode + 1);
                refreshVehicleInspectionState();
                if (cityPanel != null) cityPanel.repaint();
                return new VehicleAgent.DestinationChangeResult(true, "Destino atualizado", vv.originIntersectionId, newDestinationNode, null, vv.activeRoute);
            }
            return new VehicleAgent.DestinationChangeResult(false, "Agente não encontrado: " + vehicleId, -1, newDestinationNode, null);
        }
    }

    public VehicleAgent.ManeuverChangeResult performInteractiveManeuverChange(String vehicleId, ManeuverIntent requestedIntent) {
        if (vehicleId == null || requestedIntent == null) {
            return new VehicleAgent.ManeuverChangeResult(false, "Parâmetros inválidos", requestedIntent, requestedIntent, -1, -1);
        }
        Agent ag = VehicleControlGateway.getInstance().getAgent(vehicleId);
        if (ag instanceof VehicleAgent) {
            VehicleAgent vag = (VehicleAgent) ag;
            VehicleAgent.ManeuverChangeResult res = vag.requestManeuverChange(requestedIntent);
            this.lastVehicleActionFeedback = "Manobra " + vehicleId + ": " + res.message;
            if (res.accepted) {
                VisualVehicle vv = getVisualVehicle(vehicleId);
                if (vv != null) {
                    vv.intent = res.newIntent;
                    if (res.recalculatedRoute != null) {
                        vv.activeRoute = res.recalculatedRoute;
                    }
                }
            }
            refreshVehicleInspectionState();
            if (cityPanel != null) cityPanel.repaint();
            return res;
        } else {
            VisualVehicle vv = getVisualVehicle(vehicleId);
            if (vv != null) {
                vv.intent = requestedIntent;
                this.lastVehicleActionFeedback = "Manobra de " + vehicleId + " alterada para " + requestedIntent;
                refreshVehicleInspectionState();
                if (cityPanel != null) cityPanel.repaint();
                return new VehicleAgent.ManeuverChangeResult(true, "Manobra atualizada", requestedIntent, requestedIntent, vv.originIntersectionId, -1, vv.activeRoute);
            }
            return new VehicleAgent.ManeuverChangeResult(false, "Agente não encontrado: " + vehicleId, requestedIntent, requestedIntent, -1, -1);
        }
    }

    public void triggerDestinationChangeAsync(String vehicleId, int newDestinationNode) {
        if (vehicleId == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performInteractiveDestinationChange(vehicleId, newDestinationNode);
                SwingUtilities.invokeLater(() -> {
                    refreshVehicleInspectionState();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performInteractiveDestinationChange(vehicleId, newDestinationNode);
            refreshVehicleInspectionState();
        }
    }

    public void triggerManeuverChangeAsync(String vehicleId, ManeuverIntent intent) {
        if (vehicleId == null || intent == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performInteractiveManeuverChange(vehicleId, intent);
                SwingUtilities.invokeLater(() -> {
                    refreshVehicleInspectionState();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performInteractiveManeuverChange(vehicleId, intent);
            refreshVehicleInspectionState();
        }
    }

    /* =========================================================================
     * FASE 6.3 — API DE GESTÃO DE MISSÕES DE EMERGÊNCIA E CEDÊNCIA FÍSICA
     * ========================================================================= */

    public void setVehicleEmergencyMission(String vehicleId, EmergencyMission mission) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        if (v != null) {
            v.mission = mission;
            if (mission != null) {
                v.urgencyLevel = mission.getUrgencyLevel();
                v.activeMission = mission.isActive();
            }
        }
        Agent ag = VehicleControlGateway.getInstance().getAgent(vehicleId);
        if (ag instanceof agents.EmergencyVehicleAgent) {
            ((agents.EmergencyVehicleAgent) ag).setMission(mission);
        }
        refreshEmergencyControlState();
    }

    public void setVehicleUrgency(String vehicleId, int urgency) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        int clamped = Math.max(1, Math.min(5, urgency));
        if (v != null) {
            v.urgencyLevel = clamped;
            if (v.mission != null) {
                v.mission.setUrgencyLevel(clamped);
            }
        }
        Agent ag = VehicleControlGateway.getInstance().getAgent(vehicleId);
        if (ag instanceof agents.EmergencyVehicleAgent) {
            ((agents.EmergencyVehicleAgent) ag).setUrgencyLevel(clamped);
        }
        refreshEmergencyControlState();
    }

    public void setVehicleActiveMission(String vehicleId, boolean active) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        if (v != null) {
            v.activeMission = active;
            if (v.mission != null) {
                v.mission.setState(active ? EmergencyMission.MissionState.EN_ROUTE : EmergencyMission.MissionState.IDLE);
            }
        }
        Agent ag = VehicleControlGateway.getInstance().getAgent(vehicleId);
        if (ag instanceof agents.EmergencyVehicleAgent) {
            ((agents.EmergencyVehicleAgent) ag).setActiveMission(active);
        }
        refreshEmergencyControlState();
    }

    public EmergencyMission getEmergencyMission(String vehicleId) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        return (v != null) ? v.mission : null;
    }

    public int getVehicleUrgency(String vehicleId) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        return (v != null) ? v.urgencyLevel : 0;
    }

    public boolean isVehicleActiveMission(String vehicleId) {
        VisualVehicle v = getVisualVehicle(vehicleId);
        return (v != null) && v.activeMission;
    }

    public long getTotalYieldsInitiated() {
        return totalYieldsInitiated.get();
    }

    public long getTotalYieldsCompleted() {
        return totalYieldsCompleted.get();
    }

    public long getTotalYieldsUnsafe() {
        return totalYieldsUnsafe.get();
    }

    public void resetYieldCounters() {
        totalYieldsInitiated.set(0);
        totalYieldsCompleted.set(0);
        totalYieldsUnsafe.set(0);
    }

    public static boolean isYieldCooperationEnabled() {
        return yieldCooperationEnabled;
    }

    public static void setYieldCooperationEnabled(boolean enabled) {
        yieldCooperationEnabled = enabled;
    }

    public void refreshEmergencyControlState() {
        if (lblEmergencyStatus == null) return;
        StringBuilder sb = new StringBuilder("<html>");
        VisualVehicle amb = getVisualVehicle("Ambulancia_INEM_01");
        VisualVehicle bomb = getVisualVehicle("Bombeiros_Sapadores_01");
        VisualVehicle pol = getVisualVehicle("Viatura_Policia_01");

        appendEmergencyLine(sb, "🚑 Ambulância INEM", amb, 3);
        appendEmergencyLine(sb, "🚒 Bombeiros Sapadores", bomb, 4);
        appendEmergencyLine(sb, "🚓 Polícia Segurança", pol, 2);

        sb.append(String.format(Locale.ROOT,
                "<font color='#5fc3ff'>Cedências Físicas: <b>%d</b> concluídas | <b>%d</b> iniciadas | <b>%d</b> inseguras</font></html>",
                totalYieldsCompleted.get(), totalYieldsInitiated.get(), totalYieldsUnsafe.get()));

        Runnable r = () -> lblEmergencyStatus.setText(sb.toString());
        if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(r);
        } else {
            r.run();
        }
    }

    private void appendEmergencyLine(StringBuilder sb, String label, VisualVehicle v, int defaultUrg) {
        int urg = (v != null) ? v.urgencyLevel : defaultUrg;
        boolean active = (v == null || v.activeMission);
        String state = active ? "<font color='#50e380'>ATIVA</font>" : "<font color='#e35050'>INATIVA</font>";
        sb.append(label).append(" (Urg: <b>").append(urg).append("</b>) — ").append(state).append("<br>");
    }

    public void refreshWeatherControlState() {
        if (lblWeatherStatus == null) return;
        WeatherStateSnapshot snap = WeatherEnvironmentManager.getInstance().getSnapshot();
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>Meteorologia:</b> ").append(snap.getWeatherType().getDisplayName());
        sb.append(" | <b>Piso:</b> ").append(snap.getRoadCondition().getDisplayName());
        sb.append(snap.isManualRoadOverride() ? " [Manual]" : " [Auto]");
        sb.append("<br><font color='#72d572'>Atrito: ").append(String.format(Locale.ROOT, "%.2f", snap.getFriction()))
          .append(" | Vel. Segura: ").append((int)(snap.getSafeSpeedFactor() * 100)).append("%")
          .append(" | Dist. Segura: +").append((int)((snap.getSafeDistanceFactor() - 1.0) * 100)).append("%</font>");
        sb.append("</html>");

        String statusStr = sb.toString();
        String transStr;
        Color transColor;
        boolean inTrans = WeatherEnvironmentManager.getInstance().isTransitionInProgress();
        if (inTrans) {
            double rem = WeatherEnvironmentManager.getInstance().getRemainingTransitionSec();
            RoadCondition target = WeatherEnvironmentManager.getInstance().getTransitionTargetRoad();
            transStr = String.format(Locale.ROOT, "Transição: a secar para %s (%.1fs rest.)",
                    (target != null ? target.getDisplayName() : "Seco"), rem);
            transColor = new Color(245, 180, 50);
        } else {
            transStr = snap.isManualRoadOverride() ? "Modo Piso: Sob Controlo Manual" : "Transição: Estável";
            transColor = new Color(170, 190, 215);
        }

        Runnable r = () -> {
            lblWeatherStatus.setText(statusStr);
            if (lblTransitionNotice != null) {
                lblTransitionNotice.setText(transStr);
                lblTransitionNotice.setForeground(transColor);
            }
        };

        if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(r);
        } else {
            r.run();
        }
    }

    public int performBatchAddNormalVehicles(int count) {
        int added = 0;
        for (int i = 0; i < count; i++) {
            FleetLifecycleManager.LifecycleOperationResult res = performInteractiveAddNormalVehicle();
            if (res.success) {
                added++;
            } else {
                break;
            }
        }
        this.lastFleetActionMessage = "Lote adicionado: +" + added + " normais (Total: "
                + FleetLifecycleManager.getInstance().getActiveNormalVehicleCount() + "/50)";
        refreshFleetControlState();
        if (cityPanel != null) cityPanel.repaint();
        return added;
    }

    public int performBatchRemoveNormalVehicles(int count) {
        int removed = 0;
        for (int i = 0; i < count; i++) {
            FleetLifecycleManager.LifecycleOperationResult res = performInteractiveRemoveNormalVehicle(null);
            if (res.success) {
                removed++;
            } else {
                break;
            }
        }
        this.lastFleetActionMessage = "Lote removido: -" + removed + " normais (Total: "
                + FleetLifecycleManager.getInstance().getActiveNormalVehicleCount() + "/50)";
        refreshFleetControlState();
        if (cityPanel != null) cityPanel.repaint();
        return removed;
    }

    public void triggerBatchAddAsync(int count) {
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performBatchAddNormalVehicles(count);
                SwingUtilities.invokeLater(() -> {
                    refreshControlPanels();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performBatchAddNormalVehicles(count);
            refreshControlPanels();
        }
    }

    public void triggerBatchRemoveAsync(int count) {
        if (SwingUtilities.isEventDispatchThread()) {
            lastPendingGuiTask = guiActionExecutor.submit(() -> {
                performBatchRemoveNormalVehicles(count);
                SwingUtilities.invokeLater(() -> {
                    refreshControlPanels();
                    if (cityPanel != null) cityPanel.repaint();
                });
            });
        } else {
            performBatchRemoveNormalVehicles(count);
            refreshControlPanels();
        }
    }

    public JButton getBtnBatchAdd5() {
        return btnBatchAdd5;
    }

    public JButton getBtnBatchRemove5() {
        return btnBatchRemove5;
    }

    public JButton getBtnApplyDestination() {
        return btnApplyDestination;
    }

    public JButton getBtnManeuverStraight() {
        return btnManeuverStraight;
    }

    public JButton getBtnManeuverLeft() {
        return btnManeuverLeft;
    }

    public JButton getBtnManeuverRight() {
        return btnManeuverRight;
    }

    public JComboBox<String> getComboDestination() {
        return comboDestination;
    }

    public String getVehicleActionFeedbackText() {
        return "Ação: " + lastVehicleActionFeedback;
    }

    public JFrame getWindowFrame() {
        return windowFrame;
    }

    /**
     * Avança um passo da simulação sincronizando todas as autoridades de segurança semafórica.
     */
    public synchronized void stepSimulationTickWithAuthority() {
        int interCount = topology.getIntersectionCount();
        for (int i = 0; i < interCount; i++) {
            IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
            auth.updateTick(getWaitingCount(i, 0), getWaitingCount(i, 1));
            lights[i][0] = auth.getLightState(0);
            lights[i][1] = auth.getLightState(1);
        }
        stepSimulationTick();
        refreshManualControlState();
    }

    /**
     * Exporta uma captura PNG completa da interface gráfica Swing (Mapa Urbano + HUD + Painel Interativo Fase 6.2).
     * Quando uma janela gráfica está ativa, executa o layout e renderização no EDT para respeitar o AWT TreeLock.
     */
    public boolean exportGuiScreenshot(String filePath) {
        try {
            refreshControlPanels();
            final int mapW = topology.getWorldWidth();
            final int mapH = topology.getWorldHeight();
            final int lateralW = 420;
            final int totalW = mapW + lateralW;
            final int totalH = mapH;
            final BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

            Runnable renderTask = () -> {
                cityPanel.setSize(mapW, mapH);
                cityPanel.doLayout();

                if (lateralDashboardPanel != null) {
                    lateralDashboardPanel.setSize(lateralW, totalH);
                    lateralDashboardPanel.doLayout();
                    for (Component comp : lateralDashboardPanel.getComponents()) {
                        if (comp instanceof Container) {
                            comp.doLayout();
                            for (Component sub : ((Container) comp).getComponents()) {
                                if (sub instanceof Container) {
                                    sub.doLayout();
                                    for (Component sub2 : ((Container) sub).getComponents()) {
                                        if (sub2 instanceof Container) {
                                            sub2.doLayout();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Graphics2D g2 = img.createGraphics();
                cityPanel.paint(g2);
                if (lateralDashboardPanel != null) {
                    g2.translate(mapW, 0);
                    lateralDashboardPanel.paint(g2);
                }
                g2.dispose();
            };

            if (windowFrame != null && !SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeAndWait(renderTask);
            } else {
                renderTask.run();
            }

            File outFile = new File(filePath);
            if (outFile.getParentFile() != null) {
                outFile.getParentFile().mkdirs();
            }
            return ImageIO.write(img, "png", outFile);
        } catch (Exception e) {
            System.err.println("[CitySimulationGUI] Erro ao exportar captura PNG: " + e.getMessage());
            return false;
        }
    }

    public long getTickCounter() {
        return tickCounter;
    }

    /**
     * Encerra de forma controlada o Timer gráfico, o executor assíncrono e a janela Swing.
     */
    public void shutdownGui() {
        try {
            if (simulationTimer != null) {
                simulationTimer.stop();
            }
            guiActionExecutor.shutdownNow();
            if (windowFrame != null) {
                if (SwingUtilities.isEventDispatchThread()) {
                    windowFrame.setVisible(false);
                    windowFrame.dispose();
                } else {
                    SwingUtilities.invokeAndWait(() -> {
                        windowFrame.setVisible(false);
                        windowFrame.dispose();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    /* =========================================================================
     * MÉTODOS DE PERCEÇÃO SENSORIAL E ATUAÇÃO PARA OS AGENTES (Features 1, 6, 7, 8)
     * ========================================================================= */

    public int getTargetIntersection(VisualVehicle v) {
        if (v == null) return 0;
        return topology.getTargetIntersection(v.x, v.y, v.direction);
    }

    public double getDistanceToTargetStopLine(VisualVehicle v) {
        if (v == null) return 9999.0;
        return topology.getDistanceToTargetStopLine(v.x, v.y, v.direction);
    }

    public int getApproachingCount(int interIdx, int axis) {
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

    public int getWaitingCount(int interIdx, int axis) {
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

    public boolean hasEmergencyApproaching(int interIdx, int axis) {
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

    public double getVehicleWaitSeconds(String vehicleId) {
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

    public VisualVehicle getVisualVehicle(String vehicleId) {
        for (VisualVehicle v : vehicles) {
            if (v.id.equals(vehicleId)) return v;
        }
        return null;
    }

    public void triggerEmergencyPreemption(int interIdx, int axis) {
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

    public String getMLPredictionNotice() {
        return mlPredictionNotice;
    }

    public int getWeatherCondition() {
        return weatherCondition;
    }

    public int getIncidentPresent() {
        return incidentPresent ? 1 : 0;
    }

    public int getRoadCapacity() {
        return incidentPresent ? 2 : 4;
    }

    public double getAverageSpeed(int interIdx) {
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

    public double getAverageWaitingTime(int interIdx) {
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

    public String getDisruptionNotice() {
        return disruptionNotice;
    }

    public double getGlobalSpeedMultiplier() {
        return globalSpeedMultiplier;
    }

    public double getAverageWaitSeconds() {
        double avg = metrics.TrafficMetricsCollector.getInstance().getAverageWaitingTimeSeconds();
        if (avg >= 0.0) {
            return Math.round(avg * 10.0) / 10.0;
        }
        return (totalCompletedPasses > 0) ? Math.round((totalWaitTimeSeconds / totalCompletedPasses) * 10.0) / 10.0 : 0.0;
    }

    public long getTotalCompletedPasses() {
        long c = metrics.TrafficMetricsCollector.getInstance().getTotalCrossings();
        return (c > 0) ? c : totalCompletedPasses;
    }

    public int getPreemptionCount() {
        int p = metrics.TrafficMetricsCollector.getInstance().getEmergencyPreemptions();
        return (p > 0) ? p : preemptionCount;
    }

    private void recordIntersectionCrossing(VisualVehicle v, int interIdx) {
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
        WeatherEnvironmentManager.getInstance().updateSimulationTick();
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

            // 4. Publicação da Perceção Sensorial no Gateway para o VehicleAgent
            VisualVehicle leaderObj = getLeadingVehicleObject(v);
            double distToLeader = getDistanceToLeadingVehicle(v);
            double leaderSpeed = (leaderObj != null) ? leaderObj.currentSpeed : -1.0;

            EmergencyDetectionInfo emergInfo = detectRearEmergency(v, top);

            WeatherStateSnapshot weatherSnap = WeatherEnvironmentManager.getInstance().getSnapshot();
            RoadCondition roadCond = weatherSnap.getRoadCondition();
            double safeDist = 38.0 * roadCond.getSafeDistanceFactor();
            double stoppingDist = roadCond.calculateStoppingDistance(v.currentSpeed, 4.0);
            double maxDecel = roadCond.getServiceDeceleration();

            VehiclePerception perception = new VehiclePerception(
                    v.id, v.x, v.y, v.currentSpeed, v.maxSpeed, v.direction, v.laneIndex,
                    v.inIntersection, nextIntersectionIdx, distToStopLine, nextLight,
                    distToLeader, leaderSpeed, roadGraph.getGraphVersion(), globalSpeedMultiplier,
                    System.currentTimeMillis(), tickCounter,
                    emergInfo.detected, emergInfo.vehicleId, emergInfo.category, emergInfo.missionId,
                    emergInfo.urgencyLevel, emergInfo.distance, emergInfo.relativeSpeed, emergInfo.emergencyLane,
                    emergInfo.rightLaneClear, emergInfo.pullOverSafe,
                    emergInfo.approachingFromBehind, emergInfo.estimatedTimeToArrivalSeconds,
                    weatherSnap.getWeatherType(), roadCond, roadCond.getFrictionCoefficient(),
                    weatherSnap.getVisibilityKm(), safeDist, stoppingDist, maxDecel, weatherSnap.getStateVersion());
            VehicleControlGateway.getInstance().publishPerception(v.id, perception);

            // 5. Deliberação Autónoma do VehicleAgent vs. Paragem Controlada vs. Fallback Legacy
            boolean hasAgent = VehicleControlGateway.getInstance().isAgentRegistered(v.id);
            boolean blockedByLeader = (distToLeader < 38.0);
            boolean approachingStopLine = (distToStopLine >= 0.0 && distToStopLine <= 65.0);
            boolean signalNotGreen = (nextLight != LightState.GREEN);

            if (hasAgent) {
                VehicleDecision decision = VehicleControlGateway.getInstance().getValidDecision(
                        v.id, System.currentTimeMillis(), VehicleControlGateway.DEFAULT_MAX_STALENESS_MS);

                if (decision == null) {
                    // Agente suspenso, sem decisão válida ou inativo -> Paragem Controlada Segura (STOP)
                    v.isBraking = true;
                    v.currentSpeed = Math.max(0.0, v.currentSpeed - 0.20);
                    if (v.currentSpeed == 0.0) v.wasStopped = true;
                    handleBoundaryWrapAround(v, top);
                    continue;
                } else {
                    // Executa a decisão autónoma emitida pelo VehicleAgent
                    v.intent = decision.getPlannedIntent();
                    v.destinationIntersectionId = decision.getDestinationNode();
                    v.originIntersectionId = decision.getOriginNode();
                    v.yieldState = decision.getYieldState();
                    v.yieldingToEmergencyId = decision.getYieldTargetVehicleId();

                    // Gestão dos contadores de cedência física e mudança gradual de faixa (Fase 6.3)
                    if (v.yieldState == VehicleYieldState.YIELDING_RIGHT || v.yieldState == VehicleYieldState.PULLING_OVER) {
                        if (!v.yieldRecorded) {
                            totalYieldsInitiated.incrementAndGet();
                            v.yieldRecorded = true;
                        }
                        performSmoothLateralShift(v, top, decision.getDesiredLane());
                    } else if (v.yieldState == VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE || v.yieldState == VehicleYieldState.YIELDING_STOPPED) {
                        if (v.yieldRecorded) {
                            totalYieldsCompleted.incrementAndGet();
                            v.yieldRecorded = false;
                        }
                    } else if (v.yieldState == VehicleYieldState.RESUMING_ROUTE) {
                        performSmoothLateralShift(v, top, decision.getDesiredLane());
                    } else if (v.yieldState == VehicleYieldState.YIELD_NOT_SAFE) {
                        if (!v.yieldRecorded) {
                            totalYieldsUnsafe.incrementAndGet();
                            v.yieldRecorded = true;
                        }
                    } else {
                        // Ajusta a faixa caso a decisão planeie uma viragem diferente
                        if (distToStopLine > 68.0 && distToStopLine < 230.0 && decision.getDesiredLane() != v.laneIndex) {
                            performSmoothLateralShift(v, top, decision.getDesiredLane());
                        }
                    }

                    if (decision.getAction() == VehicleAction.BRAKE || decision.getAction() == VehicleAction.STOP) {
                        v.isBraking = true;
                        double targetSpeed = decision.getTargetSpeed();
                        v.currentSpeed = Math.max(0.0, Math.min(v.currentSpeed - 0.16, targetSpeed));
                        if (v.currentSpeed == 0.0) {
                            v.wasStopped = true;
                            v.currentCrossingWaitSeconds += 0.03333333333333333;
                            if (v.currentStopStartMillis == 0) {
                                v.currentStopStartMillis = System.currentTimeMillis();
                            }
                        }
                    } else {
                        v.isBraking = false;
                        if (v.currentStopStartMillis > 0) {
                            v.totalWaitMillis += (System.currentTimeMillis() - v.currentStopStartMillis);
                            v.currentStopStartMillis = 0;
                        }
                        double targetSpeed = Math.min(decision.getTargetSpeed(), v.maxSpeed * globalSpeedMultiplier);
                        if (v.wasStopped) {
                            if (v.startDelayTicks < 10) {
                                v.startDelayTicks++;
                                v.currentSpeed = 0.0;
                            } else {
                                v.currentSpeed = Math.min(targetSpeed, v.currentSpeed + 0.08);
                                if (v.currentSpeed >= targetSpeed * 0.8) {
                                    v.wasStopped = false;
                                    v.startDelayTicks = 0;
                                }
                            }
                        } else {
                            v.currentSpeed = Math.min(targetSpeed, v.currentSpeed + 0.06);
                        }
                    }
                }
            } else if (requireAgentAutonomy && !v.isEmergency) {
                // Autonomia exigida mas sem agente registado -> paragem controlada
                v.isBraking = true;
                v.currentSpeed = Math.max(0.0, v.currentSpeed - 0.20);
                if (v.currentSpeed == 0.0) v.wasStopped = true;
                handleBoundaryWrapAround(v, top);
                continue;
            } else {
                // Fallback para viaturas de emergência ou testes unitários sem contentor JADE
                if (distToStopLine > 0.0 && distToStopLine <= 240.0 && nextIntersectionIdx >= 0) {
                    ensureValidAStarIntentForApproach(v, nextIntersectionIdx);
                }
                if (distToStopLine > 68.0 && distToStopLine < 230.0) {
                    alignVehicleLaneWithIntent(v, top);
                }
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
            }

            // 6. Invariante Físico de Segurança Absoluta (Anti-Colisão e Paragem no Vermelho)
            if (blockedByLeader || (approachingStopLine && signalNotGreen)) {
                double limitDist = blockedByLeader
                        ? ((approachingStopLine && signalNotGreen) ? Math.min(distToLeader - 32.0, distToStopLine) : (distToLeader - 32.0))
                        : distToStopLine;
                if (limitDist <= 3.0) {
                    v.currentSpeed = 0.0;
                    v.wasStopped = true;
                } else {
                    double safeCap = Math.max(0.0, (limitDist / 50.0) * (v.maxSpeed * globalSpeedMultiplier));
                    if (v.currentSpeed > safeCap) v.currentSpeed = safeCap;
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

    public static class EmergencyDetectionInfo {
        public boolean detected = false;
        public boolean approachingFromBehind = false;
        public double distance = -1.0;
        public int urgencyLevel = 1;
        public String vehicleId = "";
        public String category = "AMBULANCE";
        public String missionId = "";
        public int emergencyLane = 1;
        public boolean rightLaneClear = true;
        public boolean pullOverSafe = true;
        public int recommendedLane = 0;
        public double lateralClearance = 24.0;
        public double emergencySpeed = 0.0;
        public double relativeSpeed = 0.0;
        public double estimatedTimeToArrivalSeconds = 9999.0;
    }

    private EmergencyDetectionInfo detectRearEmergency(VisualVehicle v, NetworkTopologyConfig top) {
        EmergencyDetectionInfo info = new EmergencyDetectionInfo();
        if (!yieldCooperationEnabled || v.isEmergency) return info; // Viaturas prioritárias não cedem a si próprias

        int corridor = (v.direction == 0 || v.direction == 1) ? top.getClosestCol(v.x) : top.getClosestRow(v.y);
        VisualVehicle nearestEm = null;
        double minRearDist = Double.MAX_VALUE;

        for (VisualVehicle other : vehicles) {
            if (!other.isEmergency || !other.activeMission) continue;
            if (other.direction != v.direction) continue;

            int otherCorridor = (other.direction == 0 || other.direction == 1) ? top.getClosestCol(other.x) : top.getClosestRow(other.y);
            if (otherCorridor != corridor) continue;

            // Determinar se 'other' está ATRÁS de 'v'
            double rearDist = -1.0;
            switch (v.direction) {
                case 0: // N->S (Y cresce)
                    if (other.y < v.y) rearDist = v.y - other.y;
                    break;
                case 1: // S->N (Y decresce)
                    if (other.y > v.y) rearDist = other.y - v.y;
                    break;
                case 2: // W->E (X cresce)
                    if (other.x < v.x) rearDist = v.x - other.x;
                    break;
                case 3: // E->W (X decresce)
                    if (other.x > v.x) rearDist = other.x - v.x;
                    break;
            }

            if (rearDist > 0.0 && rearDist <= 220.0) { // Alcance de perceção de aproximação
                if (rearDist < minRearDist) {
                    minRearDist = rearDist;
                    nearestEm = other;
                }
            }
        }

        if (nearestEm != null) {
            info.detected = true;
            info.approachingFromBehind = true;
            info.distance = minRearDist;
            info.urgencyLevel = nearestEm.urgencyLevel;
            info.vehicleId = nearestEm.id;
            info.category = (nearestEm.category != null ? nearestEm.category : "AMBULANCE");
            info.missionId = (nearestEm.mission != null ? nearestEm.mission.getMissionId() : ("MIS-" + nearestEm.id));
            info.emergencySpeed = nearestEm.currentSpeed;
            info.emergencyLane = nearestEm.laneIndex;
            info.relativeSpeed = Math.max(0.1, nearestEm.currentSpeed - v.currentSpeed);
            info.estimatedTimeToArrivalSeconds = (info.relativeSpeed > 0.05) ? (minRearDist / (info.relativeSpeed * 30.0)) : 9999.0;

            // Avaliar condições de segurança para desvio para a direita (faixa 0 ou encostamento)
            double targetRightCoord = top.getLaneCenterCoordinate(corridor, v.direction, 0);
            boolean rightClear = true;
            for (VisualVehicle other : vehicles) {
                if (other == v || other == nearestEm) continue;
                if (other.direction != v.direction) continue;

                boolean isVert = (v.direction == 0 || v.direction == 1);
                double longDist = isVert ? Math.abs(other.y - v.y) : Math.abs(other.x - v.x);
                double latDistToLane0 = isVert ? Math.abs(other.x - targetRightCoord) : Math.abs(other.y - targetRightCoord);

                if (latDistToLane0 < 16.0 && longDist < 36.0) {
                    rightClear = false;
                    break;
                }
            }

            info.rightLaneClear = rightClear;
            info.recommendedLane = 0;
            info.pullOverSafe = (v.laneIndex == 0) || rightClear;
            info.lateralClearance = (v.laneIndex == 0) ? 12.0 : 24.0;
        }

        return info;
    }

    private void performSmoothLateralShift(VisualVehicle v, NetworkTopologyConfig top, int desiredLane) {
        int corridor = (v.direction == 0 || v.direction == 1) ? top.getClosestCol(v.x) : top.getClosestRow(v.y);
        double targetCoord = top.getLaneCenterCoordinate(corridor, v.direction, desiredLane);

        // Se estiver explicitamente a encostar à berma (PULLING_OVER ou YIELDING_STOPPED) na faixa exterior (0),
        // calcula coordenada encostada ao bordo da estrada mantendo estritamente o veículo dentro dos 96px da via
        if ((v.yieldState == VehicleYieldState.PULLING_OVER || v.yieldState == VehicleYieldState.YIELDING_STOPPED) && desiredLane == 0) {
            int rw = top.getRoadWidth(); // 96 px
            switch (v.direction) {
                case 0: // N->S (corredor vertical, tráfego na esquerda)
                    targetCoord = top.getRoadX(corridor) + 8.5; // veículo tem largura 14 -> borda do veículo a 1.5px do lancil
                    break;
                case 1: // S->N (corredor vertical, tráfego na direita)
                    targetCoord = top.getRoadX(corridor) + rw - 8.5;
                    break;
                case 2: // W->E (corredor horizontal, tráfego em baixo)
                    targetCoord = top.getRoadY(corridor) + rw - 8.5;
                    break;
                case 3: // E->W (corredor horizontal, tráfego em cima)
                    targetCoord = top.getRoadY(corridor) + 8.5;
                    break;
            }
        }

        boolean isVertical = (v.direction == 0 || v.direction == 1);
        double currentCoord = isVertical ? v.x : v.y;
        double diff = targetCoord - currentCoord;

        if (Math.abs(diff) < 0.6) {
            if (isVertical) {
                v.x = targetCoord;
                v.headingAngle = (v.direction == 0) ? Math.PI / 2.0 : -Math.PI / 2.0;
            } else {
                v.y = targetCoord;
                v.headingAngle = (v.direction == 2) ? 0.0 : Math.PI;
            }
            v.laneIndex = desiredLane;
            return;
        }

        // Deslocamento lateral gradual adaptado à aderência do piso (Fase 6.4)
        double maxShift = WeatherEnvironmentManager.getInstance().getSnapshot().getRoadCondition().getMaxLateralShiftRate();
        double step = Math.signum(diff) * Math.min(maxShift, Math.abs(diff));
        double candX = isVertical ? (v.x + step) : v.x;
        double candY = isVertical ? v.y : (v.y + step);

        // Verificação contínua de colisão durante a transição lateral
        for (VisualVehicle other : vehicles) {
            if (other == v) continue;
            if (Math.hypot(other.x - candX, other.y - candY) < 22.0) {
                return; // Bloqueado por veículo na transição lateral; suspende avanço lateral
            }
        }

        v.x = candX;
        v.y = candY;

        // Rotação suave do heading angle durante a manobra lateral (+/- 0.12 rad de inclinação)
        double baseAngle;
        switch (v.direction) {
            case 0: baseAngle = Math.PI / 2.0; break;
            case 1: baseAngle = -Math.PI / 2.0; break;
            case 2: baseAngle = 0.0; break;
            default: baseAngle = Math.PI; break;
        }
        double tilt = Math.signum(step) * 0.12;
        v.headingAngle = baseAngle + tilt;
    }

    private void alignVehicleLaneWithDesired(VisualVehicle v, NetworkTopologyConfig top, int desiredLane) {
        performSmoothLateralShift(v, top, desiredLane);
    }

    private void alignVehicleLaneWithIntent(VisualVehicle v, NetworkTopologyConfig top) {
        int desiredLane = (v.intent == ManeuverIntent.TURN_RIGHT) ? 0 : 1;
        alignVehicleLaneWithDesired(v, top, desiredLane);
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

    public double getDistanceToLeadingVehicle(VisualVehicle v) {
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

    private VisualVehicle getLeadingVehicleObject(VisualVehicle v) {
        VisualVehicle leading = null;
        double minDist = 9999.0;
        for (VisualVehicle other : vehicles) {
            if (other == v || other.direction != v.direction) continue;

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
            if (d < minDist) {
                minDist = d;
                leading = other;
            }
        }
        return leading;
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
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                if (zoomScale != 1.0) {
                    g2.scale(zoomScale, zoomScale);
                }

                int worldW = topology.getWorldWidth();
                int worldH = topology.getWorldHeight();

                // 1. Terreno Urbano Claro Diurno
                g2.setColor(new Color(230, 234, 240));
                g2.fillRect(0, 0, worldW, worldH);

                // 2. Quarteirões Urbanos com Zonas Verdes e Edifícios (parametrizado para 2×2 ou 3×3)
                drawUrbanBlocks(g2, worldW, worldH);

                // 3. Avenidas de 4 Faixas com Asfalto Limpo no Centro das Interseções
                drawRoadsAndMarkings(g2, worldW, worldH);

                // 4. Semáforos montados no bordo direito de cada fluxo com postes e luzes
                drawIntersectionsAndLights(g2);

                // 5. Rota A* da Viatura Selecionada (se houver)
                drawSelectedVehicleRoute(g2);

                // 6. Veículos com Rotação Suave Dinâmica e Luzes
                drawVehicles(g2);

                // 6.1 Efeitos Atmosféricos Discretos (Chuva / Neve - Fase 6.4)
                drawWeatherEffects(g2, worldW, worldH);

                // 7. HUD / Dashboard Superior
                drawHUD(g2, worldW);
            } finally {
                g2.dispose();
            }
        }

        private void drawSelectedVehicleRoute(Graphics2D g2) {
            if (selectedVehicleId == null) return;
            VisualVehicle v = getVisualVehicle(selectedVehicleId);
            if (v == null || v.activeRoute == null || v.activeRoute.nodePath == null || v.activeRoute.nodePath.isEmpty()) {
                return;
            }

            List<Integer> nodes = v.activeRoute.nodePath;
            Stroke oldStroke = g2.getStroke();
            Color oldColor = g2.getColor();

            float[] dash = { 8.0f, 6.0f };
            float dashPhase = (float) ((tickCounter * 2) % 14);
            g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dash, dashPhase));
            g2.setColor(new Color(0, 220, 255, 190)); // Cyan glow

            double prevX = v.x;
            double prevY = v.y;

            for (int i = 0; i < nodes.size(); i++) {
                int nodeId = nodes.get(i);
                if (nodeId >= 0 && nodeId < topology.getIntersectionCount()) {
                    double cx = topology.getIntersectionCenterX(nodeId);
                    double cy = topology.getIntersectionCenterY(nodeId);
                    g2.drawLine((int) Math.round(prevX), (int) Math.round(prevY), (int) Math.round(cx), (int) Math.round(cy));
                    prevX = cx;
                    prevY = cy;
                }
            }

            // Marcadores de Waypoint dourados
            for (int i = 0; i < nodes.size(); i++) {
                int nodeId = nodes.get(i);
                if (nodeId >= 0 && nodeId < topology.getIntersectionCount()) {
                    double cx = topology.getIntersectionCenterX(nodeId);
                    double cy = topology.getIntersectionCenterY(nodeId);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.setColor(new Color(255, 215, 0, 230));
                    g2.fillOval((int) cx - 9, (int) cy - 9, 18, 18);
                    g2.setColor(new Color(30, 30, 30));
                    g2.drawOval((int) cx - 9, (int) cy - 9, 18, 18);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                    String wpLabel = "C" + (nodeId + 1);
                    int strW = g2.getFontMetrics().stringWidth(wpLabel);
                    g2.drawString(wpLabel, (int) cx - strW / 2, (int) cy + 3);
                }
            }

            g2.setStroke(oldStroke);
            g2.setColor(oldColor);
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

            WeatherStateSnapshot weatherSnap = WeatherEnvironmentManager.getInstance().getSnapshot();
            RoadCondition rc = weatherSnap.getRoadCondition();
            Color asphalt;
            switch (rc) {
                case WET:
                    asphalt = new Color(36, 40, 48); // Asfalto escurecido e reluzente por humidade
                    break;
                case SNOW_COVERED:
                    asphalt = new Color(185, 195, 208); // Cobertura branca acinzentada de neve
                    break;
                case ICY:
                    asphalt = new Color(110, 135, 160); // Tom vítreo azulado com brilho de gelo
                    break;
                case DRY:
                default:
                    asphalt = new Color(52, 55, 62); // Asfalto seco padrão
                    break;
            }
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

                // Destaque visual do cruzamento selecionado por clique (Fase 6.2)
                if (idx == selectedIntersectionIndex) {
                    Stroke prevStroke = g2.getStroke();
                    g2.setColor(new Color(0, 215, 255, 70));
                    g2.setStroke(new BasicStroke(4.5f));
                    g2.draw(new RoundRectangle2D.Double(ix - 8, iy - 8, rw + 16, rw + 16, 12, 12));
                    g2.setColor(new Color(255, 215, 40, 230));
                    g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            0, new float[] { 6, 4 }, 0));
                    g2.draw(new RoundRectangle2D.Double(ix - 5, iy - 5, rw + 10, rw + 10, 10, 10));
                    g2.setStroke(prevStroke);
                }

                // 4 Semáforos montados no lado direito de cada aproximação
                drawTrafficLight(g2, ix - 13, iy - 22, 0, nsState);
                drawTrafficLight(g2, ix + rw + 13, iy + rw + 22, 1, nsState);
                drawTrafficLight(g2, ix - 22, iy + rw + 13, 2, ewState);
                drawTrafficLight(g2, ix + rw + 22, iy - 13, 3, ewState);

                g2.setColor(new Color(255, 255, 255, 215));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                g2.drawString(name, ix + rw / 2 - 20, iy + rw / 2 - 2);

                // Etiqueta de Modo de Controlo do Cruzamento (AUTO / MANUAL / TRANS. / EMERG!)
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(idx);
                IntersectionSafetyAuthority.ManualOverrideRequest mReq = auth.getActiveManualRequest();
                String modeBadge;
                Color badgeColor;
                if (auth.isEmergencyActive()) {
                    modeBadge = "EMERG!";
                    badgeColor = new Color(235, 45, 55);
                } else if (mReq != null && mReq.getState() == IntersectionSafetyAuthority.ManualRequestState.ACCEPTED) {
                    modeBadge = "TRANS.";
                    badgeColor = new Color(245, 175, 25);
                } else if (auth.isManualModeActive()) {
                    modeBadge = "MANUAL";
                    badgeColor = new Color(0, 195, 245);
                } else {
                    modeBadge = "AUTO";
                    badgeColor = new Color(150, 175, 195);
                }
                g2.setColor(new Color(20, 24, 30, 190));
                g2.fillRoundRect(ix + rw / 2 - 22, iy + rw / 2 + 4, 44, 13, 5, 5);
                g2.setColor(badgeColor);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.drawString(modeBadge, ix + rw / 2 - 18, iy + rw / 2 + 14);
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

                // Halo e anel dourado de seleção de viatura
                if (v.id.equals(selectedVehicleId)) {
                    g2.setColor(new Color(255, 215, 0, 80));
                    g2.fillOval(-vLength / 2 - 8, -vWidth / 2 - 8, vLength + 16, vWidth + 16);
                    g2.setColor(new Color(255, 215, 0, 240));
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawOval(-vLength / 2 - 5, -vWidth / 2 - 5, vLength + 10, vWidth + 10);
                }

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

                // Etiquetas adaptativas
                boolean isSelected = v.id.equals(selectedVehicleId);
                if (isSelected) {
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
                    String labelIntent = (v.intent == ManeuverIntent.TURN_RIGHT) ? "[R]"
                            : (v.intent == ManeuverIntent.TURN_LEFT ? "[L]" : "[S]");
                    String destTag = " -> C" + (v.destinationIntersectionId + 1);
                    String speedTag = " (" + String.format(Locale.ROOT, "%.1f", v.currentSpeed * 10.0) + " km/h)";
                    String fullTag = v.id + " " + labelIntent + destTag + speedTag;
                    int tagW = g2.getFontMetrics().stringWidth(fullTag);
                    int tagX = (int) Math.round(v.x) - tagW / 2;
                    int tagY = (int) Math.round(v.y) - 16;

                    g2.setColor(new Color(20, 26, 38, 225));
                    g2.fillRoundRect(tagX - 5, tagY - 11, tagW + 10, 15, 6, 6);
                    g2.setColor(new Color(255, 215, 70));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawRoundRect(tagX - 5, tagY - 11, tagW + 10, 15, 6, 6);
                    g2.drawString(fullTag, tagX, tagY);
                } else if (v.isEmergency) {
                    g2.setColor(new Color(210, 30, 50));
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                    String prefix = v.category.equals("FIRE_TRUCK") ? "[BOM] " : (v.category.equals("POLICE") ? "[POL] " : "[AMB] ");
                    String urgTag = " U" + v.urgencyLevel + (v.activeMission ? "" : " (INAT)");
                    g2.drawString(prefix + v.id + urgTag, (int) Math.round(v.x) - 24, (int) Math.round(v.y) - 10);
                } else if (v.yieldState != null && v.yieldState != VehicleYieldState.NORMAL_DRIVING) {
                    // Badge de estado de cedência física a viatura prioritária (Fase 6.3)
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 8));
                    String badge;
                    Color badgeBg;
                    if (v.yieldState == VehicleYieldState.YIELDING_RIGHT || v.yieldState == VehicleYieldState.PULLING_OVER) {
                        badge = "CEDER >>";
                        badgeBg = new Color(255, 140, 0, 220);
                    } else if (v.yieldState == VehicleYieldState.YIELDING_STOPPED || v.yieldState == VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE) {
                        badge = "CEDIDO";
                        badgeBg = new Color(34, 139, 34, 220);
                    } else if (v.yieldState == VehicleYieldState.YIELD_NOT_SAFE) {
                        badge = "INSEGURO";
                        badgeBg = new Color(220, 20, 60, 220);
                    } else if (v.yieldState == VehicleYieldState.RESUMING_ROUTE) {
                        badge = "RETOMA";
                        badgeBg = new Color(70, 130, 180, 220);
                    } else {
                        badge = v.yieldState.name();
                        badgeBg = new Color(100, 110, 125, 220);
                    }
                    int bw = g2.getFontMetrics().stringWidth(badge) + 6;
                    g2.setColor(badgeBg);
                    g2.fillRoundRect((int) Math.round(v.x) - bw / 2, (int) Math.round(v.y) - 17, bw, 11, 4, 4);
                    g2.setColor(Color.WHITE);
                    g2.drawString(badge, (int) Math.round(v.x) - bw / 2 + 3, (int) Math.round(v.y) - 8);
                } else {
                    // Micro-badge limpo para veículos normais não selecionados
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 8));
                    g2.setColor(new Color(50, 60, 75, 200));
                    String shortTag = "#" + v.id.replaceAll("\\D+", "");
                    if (shortTag.equals("#")) shortTag = v.id;
                    g2.drawString(shortTag, (int) Math.round(v.x) - 8, (int) Math.round(v.y) - 9);
                }
            }
        }

        private void drawWeatherEffects(Graphics2D g2, int width, int height) {
            WeatherStateSnapshot snap = WeatherEnvironmentManager.getInstance().getSnapshot();
            WeatherType wt = snap.getWeatherType();
            if (wt == WeatherType.CLEAR) return;

            long t = tickCounter;
            Stroke oldStroke = g2.getStroke();
            if (wt == WeatherType.RAIN) {
                g2.setColor(new Color(170, 205, 245, 90));
                g2.setStroke(new BasicStroke(1.2f));
                int count = 120;
                for (int i = 0; i < count; i++) {
                    int rx = (int) ((i * 73 + t * 9) % width);
                    int ry = (int) ((i * 47 + t * 18) % height);
                    g2.drawLine(rx, ry, rx - 3, ry + 10);
                }
            } else if (wt == WeatherType.SNOW) {
                g2.setColor(new Color(255, 255, 255, 140));
                int count = 90;
                for (int i = 0; i < count; i++) {
                    int sx = (int) ((i * 89 + (int)(Math.sin((t + i) * 0.1) * 6)) % width);
                    int sy = (int) ((i * 53 + t * 4) % height);
                    g2.fillOval(sx, sy, 3, 3);
                }
            }
            g2.setStroke(oldStroke);
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

            // Linha 2: Status do Ambiente e Cedência a Emergências
            WeatherStateSnapshot wSnap = WeatherEnvironmentManager.getInstance().getSnapshot();
            boolean hasAlert = globalSpeedMultiplier < 0.95 || wSnap.getRoadCondition() != RoadCondition.DRY;
            g2.setColor(hasAlert ? new Color(220, 50, 50) : new Color(30, 140, 60));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            String statusPrefix = hasAlert ? "[ALERTA] " : "[OK] ";
            String weatherText = " | " + wSnap.getWeatherType().getCode() + " + " + wSnap.getRoadCondition().getCode() + " (Atrito=" + String.format(Locale.ROOT, "%.2f", wSnap.getFriction()) + ")";
            String mlText = (mlPredictionNotice != null && !mlPredictionNotice.isEmpty()) ? " | " + mlPredictionNotice : "";
            String yieldHud = " | Cedências: " + totalYieldsCompleted + " concl.";
            g2.drawString(statusPrefix + disruptionNotice + weatherText + mlText + yieldHud, hudX + 14, hudY + 58);

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
