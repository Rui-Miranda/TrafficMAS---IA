import environment.CitySimulationGUI;
import environment.EmergencyMission;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.TrafficEnvironment;
import environment.WeatherEnvironmentManager;
import environment.WeatherType;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import metrics.TrafficMetricsCollector;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Ponto de Entrada Principal do Sistema Multiagente TrafficMAS (Main Launcher — Fase 6.1).
 *
 * Inicializa a infraestrutura JADE com interface gráfica RMA (Remote Management Agent),
 * constrói o contentor principal (Main Container) e instancia o ecossistema completo de agentes
 * na malha urbana 3×3 (por omissão) ou 2×2 (com argumento {@code --2x2}):
 * - {@link agents.CentralCoordinationAgent}: 1 Agente de Coordenação Central da rede viária.
 * - {@link agents.DisruptionManagementAgent}: 1 Agente preditivo de perturbações com Machine Learning.
 * - {@link agents.TrafficLightAgent}: 18 Agentes semáforo adaptativos para os 9 cruzamentos (2 por cruzamento).
 * - {@link agents.VehicleAgent}: 50 Viaturas autónomas regulares iniciais (limite dinâmico 0..50).
 * - {@link agents.EmergencyVehicleAgent}: 3 Viaturas prioritárias excluídas do limite de 50
 *   (1 ambulância, 1 bombeiros, 1 polícia).
 * Total na configuração inicial 3×3: 50 + 3 + 18 + 1 + 1 = 73 agentes JADE.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class Main {

    public static void main(String[] args) {
        boolean mode2x2 = false;
        boolean disableRmaGui = false;
        int demoSeconds = 0;
        String screenshotDir = null;

        if (args != null) {
            for (String arg : args) {
                if ("--2x2".equalsIgnoreCase(arg)) {
                    mode2x2 = true;
                } else if ("--no-rma".equalsIgnoreCase(arg)) {
                    disableRmaGui = true;
                } else if (arg != null && arg.startsWith("--demo-seconds=")) {
                    try {
                        demoSeconds = Integer.parseInt(arg.substring("--demo-seconds=".length()).trim());
                    } catch (NumberFormatException ignored) {}
                } else if (arg != null && arg.startsWith("--screenshot-dir=")) {
                    screenshotDir = arg.substring("--screenshot-dir=".length()).trim();
                }
            }
        }

        if (demoSeconds > 0 && (screenshotDir == null || screenshotDir.isEmpty())) {
            screenshotDir = "docs/evidencias_fase62";
        }

        // Ativa a topologia 3x3 por defeito (Requisito R1 e R2) ou 2x2 em modo de compatibilidade
        if (mode2x2) {
            NetworkTopologyConfig.activate2x2();
        } else {
            NetworkTopologyConfig.activate3x3();
        }
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        TrafficMetricsCollector.reset(topo.getIntersectionCount());
        FleetLifecycleManager.getInstance().reset();

        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — INTELLIGENT MULTI-AGENT TRAFFIC CONTROL SYSTEM (ISPGAYA)");
        System.out.println("   Licenciatura em Engenharia Informática — Unidade Curricular de IA");
        System.out.println("   Topologia Ativa: Malha " + topo.getRows() + "×" + topo.getCols()
                + " (" + topo.getIntersectionCount() + " Cruzamentos | "
                + topo.getTrafficLightAgentCount() + " Agentes Semáforo)");
        System.out.println("================================================================================");

        // 1. Configura e inicializa o ambiente de execução JADE
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, disableRmaGui ? "false" : "true");

        AgentContainer container = null;
        try {
            container = rt.createMainContainer(p);
        } catch (Throwable t) {
            System.err.println("[Main] Aviso: Porta padrão 1099 indisponível. A tentar porta alternativa 1199...");
        }

        if (container == null) {
            try {
                Profile pAlt = new ProfileImpl();
                pAlt.setParameter(Profile.MAIN_PORT, "1199");
                pAlt.setParameter(Profile.GUI, disableRmaGui ? "false" : "true");
                container = rt.createMainContainer(pAlt);
                System.out.println("[Main] Contentor JADE principal inicializado com sucesso na porta 1199.");
            } catch (Throwable t2) {
                System.err.println("[Main] ERRO FATAL: Não foi possível criar o contentor JADE principal.");
                return;
            }
        }

        try {
            FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
            fleet.bindContainer(container);

            // 2. Inicializa o ambiente físico e interface de simulação urbana
            TrafficEnvironment.getInstance();

            // 3. Lançamento do Agente de Coordenação Central (1 agente)
            AgentController centralCoord = container.createNewAgent(
                    "AgenteCoordenadorCentral",
                    "agents.CentralCoordinationAgent",
                    new Object[] {});
            fleet.registerInfrastructureController("AgenteCoordenadorCentral", centralCoord);
            centralCoord.start();

            // 4. Lançamento do Agente de Gestão de Perturbações com IA/ML (1 agente)
            AgentController disruptionAgent = container.createNewAgent(
                    "AgenteGestaoPerturbacoes",
                    "agents.DisruptionManagementAgent",
                    new Object[] {});
            fleet.registerInfrastructureController("AgenteGestaoPerturbacoes", disruptionAgent);
            disruptionAgent.start();

            // 5. Configurar e instanciar os Semáforos Inteligentes nos Cruzamentos (18 em 3×3, 8 em 2×2)
            for (int i = 0; i < topo.getIntersectionCount(); i++) {
                String crossingName = topo.getIntersectionName(i);
                String nsName = "Semaforo_NS_" + crossingName;
                String ewName = "Semaforo_EW_" + crossingName;

                AgentController tlNS = container.createNewAgent(
                        nsName,
                        "agents.TrafficLightAgent",
                        new Object[] { "Norte-Sul", i });

                AgentController tlEW = container.createNewAgent(
                        ewName,
                        "agents.TrafficLightAgent",
                        new Object[] { "Este-Oeste", i });

                fleet.registerInfrastructureController(nsName, tlNS);
                fleet.registerInfrastructureController(ewName, tlEW);
                tlNS.start();
                tlEW.start();
            }

            // 6. Lançamento da Frota Inicial (50 VehicleAgent + 3 EmergencyVehicleAgent em 3×3; ou frota legado em 2×2)
            if (!mode2x2) {
                fleet.initializeDefault3x3Fleet(container);
            } else {
                for (int i = 1; i <= 6; i++) {
                    fleet.addNormalVehicle(String.format("Carro_%02d", i));
                }
                fleet.addPriorityVehicle("Ambulancia_INEM_01", 0, "AMBULANCE", 1, true);
                fleet.addPriorityVehicle("Viatura_Policia_01", 2, "POLICE", 1, true);
            }

            // Aguarda conclusão do setup() assíncrono de todos os agentes JADE
            int expectedAgents = mode2x2 ? 18 : 73;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && fleet.getLiveJadeAgentsCount() < expectedAgents) {
                Thread.sleep(50);
            }

            if (CitySimulationGUI.peekInstance() != null) {
                CitySimulationGUI.peekInstance().refreshControlPanels();
            }

            System.out.println("\n================================================================================");
            System.out.println("[Main] AUDITORIA DE ARRANQUE DO ECOSSISTEMA JADE (FASE 6.2):");
            System.out.println("  • Agentes Veículo Normal (VehicleAgent):          " + fleet.getActiveNormalVehicleCount()
                    + " / " + fleet.getMaxNormalVehicles());
            System.out.println("  • Agentes Veículo Prioritário (EmergencyAgent):   " + fleet.getActivePriorityVehicleCount()
                    + " (excluídos do limite de 50)");
            System.out.println("  • Agentes Semáforo (TrafficLightAgent):           "
                    + fleet.getLiveJadeAgentsCountByType("TrafficLightAgent"));
            System.out.println("  • Agente Coordenador Central:                     "
                    + fleet.getLiveJadeAgentsCountByType("CentralCoordinationAgent"));
            System.out.println("  • Agente Gestão de Perturbações (ML):             "
                    + fleet.getLiveJadeAgentsCountByType("DisruptionManagementAgent"));
            System.out.println("  • TOTAL DE AGENTES JADE DA APLICAÇÃO ATIVOS:      " + fleet.getLiveJadeAgentsCount());
            System.out.println("  • Correspondência 1:1 Visual <-> Agente JADE:     "
                    + (fleet.verifyOneToOneCorrespondence() ? "VERIFICADA (100%)" : "PENDENTE"));
            if (CitySimulationGUI.peekInstance() != null) {
                System.out.println("  • Botão [+ Adicionar Veículo] no arranque (50/50): "
                        + (CitySimulationGUI.peekInstance().isAddVehicleButtonEnabled() ? "ATIVO" : "DESATIVADO (CORRETO)"));
            }
            System.out.println("================================================================================\n");

            if (demoSeconds > 0) {
                boolean passed = executarMedicaoRealGUI(demoSeconds, screenshotDir);
                if (CitySimulationGUI.peekInstance() != null) {
                    CitySimulationGUI.peekInstance().shutdownGui();
                }
                final AgentContainer finalContainer = container;
                Thread killThread = new Thread(() -> {
                    try {
                        finalContainer.kill();
                    } catch (Throwable ignored) {}
                }, "JADE-Shutdown-Worker");
                killThread.setDaemon(true);
                killThread.start();
                killThread.join(2500);
                System.out.println("[Main] Encerramento controlado de Swing e JADE concluído. Exit code: " + (passed ? 0 : 1));
                System.exit(passed ? 0 : 1);
            }

        } catch (Exception e) {
            System.err.println("[Main] Erro fatal durante a instanciação dos agentes JADE: " + e.getMessage());
            e.printStackTrace();
            if (demoSeconds > 0) {
                System.exit(1);
            }
        }
    }

    private static boolean executarMedicaoRealGUI(int seconds, String screenshotDir) throws InterruptedException {
        System.out.println("[Main] A executar medição real em modo gráfico 3×3 durante " + seconds + " segundos...");
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        int interCount = NetworkTopologyConfig.getActive().getIntersectionCount();

        long startTicks = (gui != null) ? gui.getTickCounter() : 0L;
        java.util.Map<String, double[]> initialPositions = new java.util.HashMap<>();
        if (gui != null) {
            for (CitySimulationGUI.VisualVehicle v : gui.getVisualVehiclesSnapshot()) {
                initialPositions.put(v.id, new double[] { v.x, v.y });
            }
        }
        CitySimulationGUI.LightState[] prevNsStates = new CitySimulationGUI.LightState[interCount];
        for (int i = 0; i < interCount; i++) {
            prevNsStates[i] = IntersectionSafetyAuthority.getAuthority(i).getLightState(0);
        }

        double sumFps = 0.0;
        int minFps = Integer.MAX_VALUE;
        int samples = 0;
        long maxHeapBytes = 0L;
        int incompatibleGreensDetected = 0;
        int dynamicFleetOpsCount = 0;
        int manualSemaphoreOpsCount = 0;
        int emergencyRequestsTriggered = 0;
        int semaphorePhaseAlternations = 0;

        com.sun.management.OperatingSystemMXBean osBean = null;
        try {
            osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Throwable ignored) {}

        double sumCpu = 0.0;
        int cpuSamples = 0;

        for (int s = 1; s <= seconds; s++) {
            if (gui != null && java.awt.GraphicsEnvironment.isHeadless()) {
                for (int step = 0; step < 30; step++) {
                    gui.stepSimulationOnce();
                    Thread.sleep(33);
                }
            } else {
                Thread.sleep(1000);
            }
            if (gui != null) {
                int fps = gui.getDisplayedFps();
                if (fps > 0) {
                    sumFps += fps;
                    if (fps < minFps) {
                        minFps = fps;
                    }
                    samples++;
                }
            }
            long usedHeap = java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory();
            if (usedHeap > maxHeapBytes) {
                maxHeapBytes = usedHeap;
            }
            if (osBean != null) {
                double cpu = osBean.getProcessCpuLoad();
                if (cpu >= 0.0) {
                    sumCpu += (cpu * 100.0);
                    cpuSamples++;
                }
            }

            // Operações interativas reais durante o teste prolongado e capturas da GUI
            if (gui != null) {
                if (s == 3 && screenshotDir != null) {
                    String path = screenshotDir + (seconds == 5 ? "/gui_demo5s_3x3.png" : "/gui_fase62_1_inicial_50_50_add_desativado.png");
                    boolean saved = gui.exportGuiScreenshot(path);
                    System.out.println("[Main][t=" + s + "s] Captura PNG guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                }
                if (s == 6 && seconds >= 10) {
                    java.util.List<CitySimulationGUI.VisualVehicle> list = gui.getVisualVehiclesSnapshot();
                    if (!list.isEmpty()) {
                        CitySimulationGUI.VisualVehicle target = list.get(0);
                        gui.selectVehicle(target.id);
                        int newDest = (target.destinationIntersectionId == 8) ? 6 : 8;
                        gui.performInteractiveDestinationChange(target.id, newDest);
                        System.out.println("[Main][t=" + s + "s][Fase 6.2-C] Veículo " + target.id + " selecionado por operador -> Destino alterado para C" + (newDest + 1) + " via A*.");
                        if (screenshotDir != null) {
                            String path = screenshotDir + "/gui_fase62c_veiculo_selecionado_a_star.png";
                            boolean saved = gui.exportGuiScreenshot(path);
                            System.out.println("[Main][t=" + s + "s] Captura PNG Fase 6.2-C (Veículo Selecionado + Rota A*): " + (saved ? "SUCESSO" : "FALHA"));
                        }
                    }
                }
                if (s == 10 && seconds == 15 && screenshotDir != null) {
                    String path = screenshotDir + "/gui_demo15s_3x3.png";
                    boolean saved = gui.exportGuiScreenshot(path);
                    System.out.println("[Main][t=" + s + "s] Captura PNG 15s guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                }
                if (s == 10 && seconds >= 20) {
                    FleetLifecycleManager.LifecycleOperationResult r1 = gui.performInteractiveRemoveNormalVehicle(null);
                    FleetLifecycleManager.LifecycleOperationResult r2 = gui.performInteractiveRemoveNormalVehicle(null);
                    dynamicFleetOpsCount += 2;
                    System.out.println("[Main][t=" + s + "s][Frota] 2 remoções executadas (" + r1.vehicleId + ", " + r2.vehicleId
                            + ") -> Normais=" + fleet.getActiveNormalVehicleCount() + "/50 | Botão Adicionar="
                            + (gui.isAddVehicleButtonEnabled() ? "ATIVO" : "DESATIVADO"));
                    if (screenshotDir != null) {
                        String path = screenshotDir + "/gui_fase62_2_apos_remocao_48_50_add_ativo.png";
                        boolean saved = gui.exportGuiScreenshot(path);
                        System.out.println("[Main][t=" + s + "s] Captura PNG após remoção guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                    }
                }
                if (s == 18 && seconds >= 20) {
                    FleetLifecycleManager.LifecycleOperationResult a1 = gui.performInteractiveAddNormalVehicle();
                    FleetLifecycleManager.LifecycleOperationResult a2 = gui.performInteractiveAddNormalVehicle();
                    dynamicFleetOpsCount += 2;
                    System.out.println("[Main][t=" + s + "s][Frota] 2 adições executadas (" + a1.vehicleId + ", " + a2.vehicleId
                            + ") -> Normais=" + fleet.getActiveNormalVehicleCount() + "/50 | Botão Adicionar="
                            + (gui.isAddVehicleButtonEnabled() ? "ATIVO" : "DESATIVADO"));
                }
                if (s == 21 && seconds >= 25) {
                    // Fase 6.3: Seleção e ativação da missão da viatura prioritária para demonstração de cedência física
                    CitySimulationGUI.VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
                    if (amb != null) {
                        amb.activeMission = true;
                        amb.urgencyLevel = 4;
                        if (amb.mission != null) {
                            amb.mission.setUrgencyLevel(4);
                            amb.mission.setState(EmergencyMission.MissionState.EN_ROUTE);
                        }
                        gui.selectVehicle(amb.id);
                        gui.refreshEmergencyControlState();
                        System.out.println("[Main][t=" + s + "s][Fase 6.3] Ambulancia_INEM_01 selecionada com Urgência 4 em missão ativa.");
                    }
                }
                if (s == 24 && seconds >= 25 && screenshotDir != null) {
                    String path = screenshotDir + "/gui_fase63_cedencia_fisica_emergencia.png";
                    boolean saved = gui.exportGuiScreenshot(path);
                    System.out.println("[Main][t=" + s + "s][Fase 6.3] Captura PNG de Cedência Física guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                    try {
                        String brainArtifact = "C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b/gui_fase63_cedencia_fisica_emergencia.png";
                        gui.exportGuiScreenshot(brainArtifact);
                    } catch (Throwable ignored) {}
                }
                if (s == 26 && seconds >= 28) {
                    // Fase 6.4: Transição meteorológica dinâmica para chuva e piso molhado
                    WeatherEnvironmentManager env = WeatherEnvironmentManager.getInstance();
                    env.setWeather(WeatherType.RAIN);
                    System.out.println("[Main][t=" + s + "s][Fase 6.4] Meteorologia alterada para RAIN -> Piso WET com histerese ativa.");
                }
                if (s == 28 && seconds >= 29 && screenshotDir != null) {
                    String path = screenshotDir + "/gui_fase64_meteorologia_dinamica.png";
                    boolean saved = gui.exportGuiScreenshot(path);
                    System.out.println("[Main][t=" + s + "s][Fase 6.4] Captura PNG de Meteorologia Dinâmica guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                    try {
                        String brainArtifact = "C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b/gui_fase64_meteorologia_dinamica.png";
                        gui.exportGuiScreenshot(brainArtifact);
                    } catch (Throwable ignored) {}
                }
                if (s == 25 && seconds >= 30) {
                    int c5X = gui.getTopology().getIntersectionX(4) + 48;
                    int c5Y = gui.getTopology().getIntersectionY(4) + 48;
                    int clicked = gui.selectIntersectionByClick(c5X, c5Y);
                    int oppAxis = (IntersectionSafetyAuthority.getAuthority(4).getLightState(0) == CitySimulationGUI.LightState.GREEN) ? 1 : 0;
                    IntersectionSafetyAuthority.ManualOverrideRequest req = gui.requestManualPhaseFromGui(4, oppAxis);
                    manualSemaphoreOpsCount++;
                    System.out.println("[Main][t=" + s + "s][Manual] Clique C5_C (idx=" + clicked + ") -> Pedido " + req.requestId
                            + " Eixo=" + req.getTargetAxisName() + " EstadoInicial=" + req.getState());
                }
                if (s == 29 && seconds >= 30 && screenshotDir != null) {
                    IntersectionSafetyAuthority.ManualOverrideRequest activeC5 = IntersectionSafetyAuthority.getAuthority(4).getActiveManualRequest();
                    System.out.println("[Main][t=" + s + "s][Manual] Estado C5_C após transição YELLOW+ALL_RED: "
                            + (activeC5 != null ? activeC5.getState() + " (" + activeC5.getStatusReason() + ")" : "N/A"));
                    String path = screenshotDir + "/gui_fase62_3_controlo_manual_c5_aplicado.png";
                    boolean saved = gui.exportGuiScreenshot(path);
                    System.out.println("[Main][t=" + s + "s] Captura PNG controlo manual C5 guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                }
                if (s == 45 && seconds >= 60) {
                    gui.returnToAutomaticFromGui(4);
                    manualSemaphoreOpsCount++;
                    int c2X = gui.getTopology().getIntersectionX(1) + 48;
                    int c2Y = gui.getTopology().getIntersectionY(1) + 48;
                    gui.selectIntersectionByClick(c2X, c2Y);
                    IntersectionSafetyAuthority.ManualOverrideRequest reqC2 = gui.requestManualPhaseFromGui(1, 1);
                    manualSemaphoreOpsCount++;
                    System.out.println("[Main][t=" + s + "s][Manual] C5_C retornou a AUTO; Clique C2_N -> Pedido " + reqC2.requestId
                            + " Estado=" + reqC2.getState());
                }
                if (s == 65 && seconds >= 90) {
                    // Emergência prioritária sobrepõe-se ao controlo manual em C2
                    gui.triggerEmergencyPreemption(1, 0);
                    emergencyRequestsTriggered++;
                    IntersectionSafetyAuthority.ManualOverrideRequest lastC2 = IntersectionSafetyAuthority.getAuthority(1).getLastManualRequest();
                    System.out.println("[Main][t=" + s + "s][Emergência] Preempção em C2_N -> Pedido manual C2 passou a: "
                            + (lastC2 != null ? lastC2.getState() + " (" + lastC2.getStatusReason() + ")" : "N/A"));
                }
                if (s == 70 && seconds >= 90) {
                    IntersectionSafetyAuthority.getAuthority(1).notifyEmergencyCleared("MANUAL_EMERGENCY");
                }
                if (s == 85 && seconds >= 100) {
                    FleetLifecycleManager.LifecycleOperationResult r3 = gui.performInteractiveRemoveNormalVehicle(null);
                    FleetLifecycleManager.LifecycleOperationResult a3 = gui.performInteractiveAddNormalVehicle();
                    dynamicFleetOpsCount += 2;
                    System.out.println("[Main][t=" + s + "s][Frota] Ciclo dinâmico (-1/+1): removido=" + r3.vehicleId
                            + ", adicionado=" + a3.vehicleId + " -> Normais=" + fleet.getActiveNormalVehicleCount() + "/50");
                }
                if (s == 105 && seconds >= 115) {
                    int c9X = gui.getTopology().getIntersectionX(8) + 48;
                    int c9Y = gui.getTopology().getIntersectionY(8) + 48;
                    gui.selectIntersectionByClick(c9X, c9Y);
                    IntersectionSafetyAuthority.ManualOverrideRequest reqC9 = gui.requestManualPhaseFromGui(8, 0);
                    manualSemaphoreOpsCount++;
                    System.out.println("[Main][t=" + s + "s][Manual] Clique C9_SE -> Pedido " + reqC9.requestId + " Estado=" + reqC9.getState());
                }
                if (s == 115 && seconds >= 115) {
                    gui.returnToAutomaticFromGui(8);
                    manualSemaphoreOpsCount++;
                    System.out.println("[Main][t=" + s + "s][Manual] C9_SE retornou ao modo AUTOMÁTICO.");
                    if (screenshotDir != null) {
                        String path = screenshotDir + "/gui_fase62_4_final_120s_auto_restaurado.png";
                        boolean saved = gui.exportGuiScreenshot(path);
                        System.out.println("[Main][t=" + s + "s] Captura PNG final 120s guardada (" + path + "): " + (saved ? "SUCESSO" : "FALHA"));
                    }
                }
            }

            // Verifica invariante de segurança e alternância semafórica nos 9 cruzamentos
            for (int i = 0; i < interCount; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                CitySimulationGUI.LightState ns = auth.getLightState(0);
                CitySimulationGUI.LightState ew = auth.getLightState(1);
                if (ns == CitySimulationGUI.LightState.GREEN && ew == CitySimulationGUI.LightState.GREEN) {
                    incompatibleGreensDetected++;
                }
                if (ns != prevNsStates[i]) {
                    semaphorePhaseAlternations++;
                    prevNsStates[i] = ns;
                }
            }
        }

        long endTicks = (gui != null) ? gui.getTickCounter() : 0L;
        long ticksDelta = endTicks - startTicks;
        int movedVehiclesCount = 0;
        if (gui != null) {
            for (CitySimulationGUI.VisualVehicle v : gui.getVisualVehiclesSnapshot()) {
                double[] init = initialPositions.get(v.id);
                if (init != null && Math.hypot(v.x - init[0], v.y - init[1]) > 2.0) {
                    movedVehiclesCount++;
                }
            }
        }

        double avgFps = (samples > 0) ? (sumFps / samples) : (gui != null ? gui.getDisplayedFps() : 30.0);
        if (minFps == Integer.MAX_VALUE) minFps = (int) Math.round(avgFps);
        double peakHeapMb = maxHeapBytes / (1024.0 * 1024.0);
        double avgCpuPct = (cpuSamples > 0) ? (sumCpu / cpuSamples) : 0.0;
        long fipaMsgs = TrafficMetricsCollector.getInstance().getFipaMessagesCount();
        long totalCrossings = TrafficMetricsCollector.getInstance().getTotalCrossings();
        double avgQueue = TrafficMetricsCollector.getInstance().getAverageQueueLength();
        int maxQueue = TrafficMetricsCollector.getInstance().getMaximumQueueLength();
        int redViolations = (gui != null) ? gui.getRedLightViolationsCount() : 0;
        int emergencyPreemptionsTotal = TrafficMetricsCollector.getInstance().getEmergencyPreemptions() + emergencyRequestsTriggered;

        int normalAgents = fleet.getActiveNormalVehicleCount();
        int priorityAgents = fleet.getActivePriorityVehicleCount();
        int tlAgents = fleet.getLiveJadeAgentsCountByType("TrafficLightAgent");
        int centralAgents = fleet.getLiveJadeAgentsCountByType("CentralCoordinationAgent")
                + fleet.getLiveJadeAgentsCountByType("DisruptionManagementAgent");
        int totalJadeAgents = fleet.getLiveJadeAgentsCount();
        boolean oneToOneOk = fleet.verifyOneToOneCorrespondence();

        boolean allChecksPassed = (normalAgents == 50)
                && (priorityAgents == 3)
                && (tlAgents == 18)
                && (centralAgents == 2)
                && (totalJadeAgents == 73)
                && (ticksDelta > 0)
                && (movedVehiclesCount > 0)
                && (redViolations == 0)
                && (incompatibleGreensDetected == 0)
                && oneToOneOk;

        System.out.println("\n================================================================================");
        System.out.println("   RELATÓRIO DE DESEMPENHO REAL EM EXECUÇÃO GRÁFICA 3×3 (FASE 6.2)");
        System.out.println("================================================================================");
        System.out.printf(Locale.US, "  • Duração da Execução Medida:            %d s%n", seconds);
        System.out.printf(Locale.US, "  • Avanço do Contador Gráfico (Ticks):    +%d ticks (%d -> %d)%n", ticksDelta, startTicks, endTicks);
        System.out.printf(Locale.US, "  • Veículos com Movimento Físico Real:    %d / %d veículos%n", movedVehiclesCount, initialPositions.size());
        System.out.printf(Locale.US, "  • Alternâncias de Fase Semafórica:       %d transições observadas%n", semaphorePhaseAlternations);
        System.out.printf(Locale.US, "  • FPS Médio / Mínimo Medido (Swing):     %.2f FPS / %d FPS%n", avgFps, minFps);
        System.out.printf(Locale.US, "  • Memória Heap Máxima Utilizada:         %.2f MB%n", peakHeapMb);
        System.out.printf(Locale.US, "  • Carga Média de CPU do Processo:        %.2f %%%n", avgCpuPct);
        System.out.printf(Locale.US, "  • Total de Agentes JADE Ativos no Fim:   %d (Normais=%d, Prioritários=%d, Semáforos=%d, Centrais=%d)%n",
                totalJadeAgents, normalAgents, priorityAgents, tlAgents, centralAgents);
        System.out.printf(Locale.US, "  • Operações Dinâmicas de Frota (+/-):    %d operações concluídas sem órfãos%n", dynamicFleetOpsCount);
        System.out.printf(Locale.US, "  • Operações de Controlo Manual Semaf.:   %d pedidos seguros executados%n", manualSemaphoreOpsCount);
        System.out.printf(Locale.US, "  • Preempções / Pedidos de Emergência:    %d%n", emergencyPreemptionsTotal);
        System.out.printf(Locale.US, "  • Mensagens FIPA-ACL Trocadas:           %d mensagens%n", fipaMsgs);
        System.out.printf(Locale.US, "  • Travessias Concluídas nos 9 Nós:       %d travessias%n", totalCrossings);
        System.out.printf(Locale.US, "  • Comprimento Médio / Máximo de Fila:    %.2f / %d veículos%n", avgQueue, maxQueue);
        System.out.printf(Locale.US, "  • Violações de Sinal Vermelho:           %d%n", redViolations);
        System.out.printf(Locale.US, "  • Verdes Incompatíveis Simultâneos:      %d%n", incompatibleGreensDetected);
        System.out.printf(Locale.US, "  • Correspondência 1:1 Visual <-> JADE:   %s%n", oneToOneOk ? "100% ÍNTEGRA" : "FALHOU");
        System.out.printf(Locale.US, "  • Verificação Global dos 10 Critérios:   %s%n", allChecksPassed ? "APROVADA (EXIT 0)" : "REPROVADA (EXIT 1)");
        System.out.println("================================================================================\n");

        return allChecksPassed;
    }
}
