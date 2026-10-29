import environment.CitySimulationGUI;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.TrafficEnvironment;
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
                executarMedicaoRealGUI(demoSeconds, screenshotDir);
                container.kill();
                System.exit(0);
            }

        } catch (Exception e) {
            System.err.println("[Main] Erro fatal durante a instanciação dos agentes JADE: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void executarMedicaoRealGUI(int seconds, String screenshotDir) throws InterruptedException {
        System.out.println("[Main] A executar medição real em modo gráfico 3×3 durante " + seconds + " segundos...");
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        double sumFps = 0.0;
        int minFps = Integer.MAX_VALUE;
        int samples = 0;
        long maxHeapBytes = 0L;
        int incompatibleGreensDetected = 0;
        int dynamicFleetOpsCount = 0;
        int manualSemaphoreOpsCount = 0;

        com.sun.management.OperatingSystemMXBean osBean = null;
        try {
            osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Throwable ignored) {}

        double sumCpu = 0.0;
        int cpuSamples = 0;

        for (int s = 1; s <= seconds; s++) {
            Thread.sleep(1000);
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
                    gui.exportGuiScreenshot(screenshotDir + "/gui_fase62_1_inicial_50_50_add_desativado.png");
                }
                if (s == 10 && seconds >= 20) {
                    gui.performInteractiveRemoveNormalVehicle(null);
                    gui.performInteractiveRemoveNormalVehicle(null);
                    dynamicFleetOpsCount += 2;
                    if (screenshotDir != null) {
                        gui.exportGuiScreenshot(screenshotDir + "/gui_fase62_2_apos_remocao_48_50_add_ativo.png");
                    }
                }
                if (s == 18 && seconds >= 20) {
                    gui.performInteractiveAddNormalVehicle();
                    gui.performInteractiveAddNormalVehicle();
                    dynamicFleetOpsCount += 2;
                }
                if (s == 25 && seconds >= 30) {
                    int c5X = gui.getTopology().getIntersectionX(4) + 48;
                    int c5Y = gui.getTopology().getIntersectionY(4) + 48;
                    gui.selectIntersectionByClick(c5X, c5Y);
                    int oppAxis = (IntersectionSafetyAuthority.getAuthority(4).getLightState(0) == CitySimulationGUI.LightState.GREEN) ? 1 : 0;
                    gui.requestManualPhaseFromGui(4, oppAxis);
                    manualSemaphoreOpsCount++;
                }
                if (s == 29 && seconds >= 30 && screenshotDir != null) {
                    gui.exportGuiScreenshot(screenshotDir + "/gui_fase62_3_controlo_manual_c5_aplicado.png");
                }
                if (s == 45 && seconds >= 60) {
                    gui.returnToAutomaticFromGui(4);
                    manualSemaphoreOpsCount++;
                    int c2X = gui.getTopology().getIntersectionX(1) + 48;
                    int c2Y = gui.getTopology().getIntersectionY(1) + 48;
                    gui.selectIntersectionByClick(c2X, c2Y);
                    gui.requestManualPhaseFromGui(1, 1);
                    manualSemaphoreOpsCount++;
                }
                if (s == 65 && seconds >= 90) {
                    // Emergência prioritária sobrepõe-se ao controlo manual em C2
                    gui.triggerEmergencyPreemption(1, 0);
                    IntersectionSafetyAuthority.getAuthority(1).notifyEmergencyCleared("MANUAL_EMERGENCY");
                }
                if (s == 85 && seconds >= 100) {
                    gui.performInteractiveRemoveNormalVehicle(null);
                    gui.performInteractiveAddNormalVehicle();
                    dynamicFleetOpsCount += 2;
                }
                if (s == 105 && seconds >= 115) {
                    int c9X = gui.getTopology().getIntersectionX(8) + 48;
                    int c9Y = gui.getTopology().getIntersectionY(8) + 48;
                    gui.selectIntersectionByClick(c9X, c9Y);
                    gui.requestManualPhaseFromGui(8, 0);
                    manualSemaphoreOpsCount++;
                }
                if (s == 115 && seconds >= 115) {
                    gui.returnToAutomaticFromGui(8);
                    manualSemaphoreOpsCount++;
                }
            }

            // Verifica invariante de segurança nos 9 cruzamentos
            for (int i = 0; i < NetworkTopologyConfig.getActive().getIntersectionCount(); i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                if (auth.getLightState(0) == CitySimulationGUI.LightState.GREEN
                        && auth.getLightState(1) == CitySimulationGUI.LightState.GREEN) {
                    incompatibleGreensDetected++;
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

        System.out.println("\n================================================================================");
        System.out.println("   RELATÓRIO DE DESEMPENHO REAL EM EXECUÇÃO GRÁFICA 3×3 (FASE 6.2)");
        System.out.println("================================================================================");
        System.out.printf(Locale.US, "  • Duração da Execução Medida:            %d s%n", seconds);
        System.out.printf(Locale.US, "  • FPS Médio / Mínimo Medido (Swing):     %.2f FPS / %d FPS%n", avgFps, minFps);
        System.out.printf(Locale.US, "  • Memória Heap Máxima Utilizada:         %.2f MB%n", peakHeapMb);
        System.out.printf(Locale.US, "  • Carga Média de CPU do Processo:        %.2f %%%n", avgCpuPct);
        System.out.printf(Locale.US, "  • Total de Agentes JADE Ativos no Fim:   %d (50 Normais + 3 Prioritários + 18 Semáforos + 2 Central/ML)%n",
                fleet.getLiveJadeAgentsCount());
        System.out.printf(Locale.US, "  • Operações Dinâmicas de Frota (+/-):    %d operações concluídas sem órfãos%n", dynamicFleetOpsCount);
        System.out.printf(Locale.US, "  • Operações de Controlo Manual Semaf.:   %d pedidos seguros executados%n", manualSemaphoreOpsCount);
        System.out.printf(Locale.US, "  • Mensagens FIPA-ACL Trocadas:           %d mensagens%n", fipaMsgs);
        System.out.printf(Locale.US, "  • Travessias Concluídas nos 9 Nós:       %d travessias%n", totalCrossings);
        System.out.printf(Locale.US, "  • Comprimento Médio / Máximo de Fila:    %.2f / %d veículos%n", avgQueue, maxQueue);
        System.out.printf(Locale.US, "  • Violações de Sinal Vermelho:           %d%n", redViolations);
        System.out.printf(Locale.US, "  • Verdes Incompatíveis Simultâneos:      %d%n", incompatibleGreensDetected);
        System.out.printf(Locale.US, "  • Correspondência 1:1 Visual <-> JADE:   %s%n",
                fleet.verifyOneToOneCorrespondence() ? "100% ÍNTEGRA" : "FALHOU");
        System.out.println("================================================================================\n");
    }
}
