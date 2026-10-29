package test;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.CitySimulationGUI.VisualVehicle;
import environment.FleetLifecycleManager;
import environment.FleetLifecycleManager.LifecycleOperationResult;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ManualOverrideRequest;
import environment.IntersectionSafetyAuthority.ManualRequestState;
import environment.IntersectionSafetyAuthority.Phase;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;
import environment.TrafficEnvironment;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import metrics.TrafficMetricsCollector;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Suite de Testes Automatizados da Fase 6.2 (25 Testes Obrigatórios).
 *
 * Valida:
 *  1. Frota inicial com 50 veículos normais;
 *  2. 3 veículos prioritários excluídos do limite de 50;
 *  3. Botão ADICIONAR desativado quando existem 50 veículos normais;
 *  4. Remoção 50 -> 49 (e reativação imediata do botão ADICIONAR);
 *  5. Adição 49 -> 50 (e desativação imediata do botão ADICIONAR);
 *  6. Bloqueio de adição acima de 50;
 *  7. Remoção até 0 veículos normais;
 *  8. Bloqueio de remoção abaixo de 0 (botão REMOVER desativado em 0);
 *  9. Identidade estrita 1:1 entre veículo visual e agente JADE;
 * 10. Ausência de agentes órfãos após múltiplos ciclos de remoção e adição;
 * 11. Clique no cruzamento correto (sem alterar diretamente a cor no ecrã);
 * 12. Pedido manual para Norte-Sul (REQUESTED -> ACCEPTED -> APPLIED);
 * 13. Pedido manual para Este-Oeste (REQUESTED -> ACCEPTED -> APPLIED);
 * 14. Transição obrigatória por YELLOW antes de abrir o eixo oposto;
 * 15. Transição obrigatória por ALL_RED após YELLOW;
 * 16. Ausência absoluta de verdes incompatíveis simultâneos;
 * 17. Retorno ao modo automático (por comando do operador e por expiração temporal);
 * 18. Prioridade absoluta de emergência sobre controlo manual (rejeição e preempção);
 * 19. Cliques repetidos e concorrentes sem saltar YELLOW nem ALL_RED;
 * 20. Paragem física de veículos no vermelho após transição manual;
 * 21. Retoma física de marcha no verde manual (zero violações e zero duplicação de travessia);
 * 22. Preservação das rotas A* durante alterações manuais de semáforos;
 * 23. Compatibilidade com o modo 2×2;
 * 24. Compatibilidade com o modo 3×3;
 * 25. Não bloqueio da GUI (Event Dispatch Thread do Swing responsivo).
 */
public class Phase62ValidationTestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static AgentContainer sharedContainer = null;

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("   SUITE DE VALIDAÇÃO AUTOMATIZADA — FASE 6.2 (25 TESTES OBRIGATÓRIOS)");
        System.out.println("==================================================================================");

        try {
            initJadeContainer();

            test01_InitialFleet50NormalVehicles();
            test02_ThreePriorityVehiclesExcludedFromLimit();
            test03_AddButtonDisabledWhen50NormalVehicles();
            test04_Removal50To49EnablesAddButton();
            test05_Addition49To50DisablesAddButton();
            test06_BlockAdditionAbove50();
            test07_RemovalDownToZero();
            test08_BlockRemovalBelowZero();
            test09_StrictOneToOneCorrespondenceVisualAndJade();
            test10_NoOrphanAgentsAfterRepeatedAddRemoveCycles();
            test11_ClickSelectsCorrectIntersectionWithoutChangingColors();
            test12_ManualRequestNS_RequestedAcceptedApplied();
            test13_ManualRequestEW_RequestedAcceptedApplied();
            test14_MandatoryYellowTransitionOnManualSwitch();
            test15_MandatoryAllRedTransitionOnManualSwitch();
            test16_ZeroIncompatibleGreensAcrossManualTransitions();
            test17_ReturnToAutomaticModeByOperatorAndTimeout();
            test18_EmergencyPriorityRejectsAndPreemptsManualControl();
            test19_RepeatedAndConcurrentManualClicksNeverSkipPhases();
            test20_PhysicalVehicleStopsOnManualRedLight();
            test21_PhysicalVehicleResumesOnManualGreenWithZeroIllegalOrDuplicateCrossings();
            test22_PreservationOfAStarRoutesDuringManualChanges();
            test23_CompatibilityWith2x2Topology();
            test24_CompatibilityWith3x3Topology();
            test25_NonBlockingGuiEdtResponsiveness();

        } finally {
            shutdownJadeContainer();
            SimulationClock.disableSimulatedMode();
            NetworkTopologyConfig.activate2x2();
            IntersectionSafetyAuthority.resetAll();
        }

        System.out.println("==================================================================================");
        System.out.println("RESUMO FASE 6.2: " + passed + " aprovados | " + failed + " falhados (Total: " + (passed + failed) + ")");
        System.out.println("==================================================================================");

        if (failed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    private static void initJadeContainer() {
        NetworkTopologyConfig.activate3x3();
        TrafficMetricsCollector.reset(9);
        IntersectionSafetyAuthority.resetAll();

        Runtime rt = Runtime.instance();
        for (int port = 14950; port <= 14990; port += 5) {
            try {
                Profile p = new ProfileImpl(null, port, null);
                p.setParameter(Profile.GUI, "false");
                p.setParameter(Profile.NO_MTP, "true");
                sharedContainer = rt.createMainContainer(p);
                if (sharedContainer != null) break;
            } catch (Throwable ignored) {}
        }

        if (sharedContainer == null) {
            throw new IllegalStateException("Não foi possível inicializar o contentor JADE para os testes da Fase 6.2.");
        }

        try {
            FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
            fleet.reset();
            fleet.bindContainer(sharedContainer);
            TrafficEnvironment.getInstance();

            AgentController central = sharedContainer.createNewAgent(
                    "AgenteCoordenadorCentral", "agents.CentralCoordinationAgent", new Object[] {});
            fleet.registerInfrastructureController("AgenteCoordenadorCentral", central);
            central.start();

            AgentController disruption = sharedContainer.createNewAgent(
                    "AgenteGestaoPerturbacoes", "agents.DisruptionManagementAgent", new Object[] {});
            fleet.registerInfrastructureController("AgenteGestaoPerturbacoes", disruption);
            disruption.start();

            NetworkTopologyConfig topo = NetworkTopologyConfig.CONFIG_3X3;
            for (int i = 0; i < topo.getIntersectionCount(); i++) {
                String crossing = topo.getIntersectionName(i);
                String nsName = "Semaforo_NS_" + crossing;
                String ewName = "Semaforo_EW_" + crossing;

                AgentController tlNS = sharedContainer.createNewAgent(
                        nsName, "agents.TrafficLightAgent", new Object[] { "Norte-Sul", i });
                AgentController tlEW = sharedContainer.createNewAgent(
                        ewName, "agents.TrafficLightAgent", new Object[] { "Este-Oeste", i });

                fleet.registerInfrastructureController(nsName, tlNS);
                fleet.registerInfrastructureController(ewName, tlEW);
                tlNS.start();
                tlEW.start();
            }

            fleet.initializeDefault3x3Fleet(sharedContainer);
            awaitAgentCount(73, 6000);
            CitySimulationGUI.getInstance().refreshControlPanels();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao inicializar agentes JADE na Fase 6.2: " + e.getMessage(), e);
        }
    }

    private static void shutdownJadeContainer() {
        if (sharedContainer != null) {
            try {
                sharedContainer.kill();
            } catch (Throwable ignored) {}
            sharedContainer = null;
        }
    }

    private static void awaitAgentCount(int expectedTotal, long timeoutMs) {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (fleet.getLiveJadeAgentsCount() == expectedTotal && fleet.verifyOneToOneCorrespondence()) {
                return;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void check(String testName, boolean condition, String details) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + testName + " -> " + details);
        } else {
            failed++;
            System.err.println("  [FAIL] " + testName + " -> " + details);
        }
    }

    // =========================================================================
    // TESTES 1 A 10 — GESTÃO INTERATIVA DA FROTA (50 NORMAIS + 3 PRIORITÁRIOS)
    // =========================================================================

    private static void test01_InitialFleet50NormalVehicles() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        int activeNormals = fleet.getActiveNormalVehicleCount();
        int maxNormals = fleet.getMaxNormalVehicles();
        int jadeNormals = fleet.getLiveJadeAgentsCountByType("VehicleAgent");

        boolean ok = (activeNormals == 50)
                && (maxNormals == 50)
                && (jadeNormals == 50);

        check("T01: Frota inicial com exatamente 50 veículos normais",
                ok,
                "ativos=" + activeNormals + "/" + maxNormals + ", VehicleAgent JADE=" + jadeNormals);
    }

    private static void test02_ThreePriorityVehiclesExcludedFromLimit() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        int priorityCount = fleet.getActivePriorityVehicleCount();
        int jadeEmergency = fleet.getLiveJadeAgentsCountByType("EmergencyVehicleAgent");
        int totalJade = fleet.getLiveJadeAgentsCount();

        boolean ok = (priorityCount == 3)
                && (jadeEmergency == 3)
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (totalJade == 73);

        check("T02: 3 veículos prioritários excluídos do limite de 50 (total=73 agentes JADE)",
                ok,
                "prioritários=" + priorityCount + ", EmergencyVehicleAgent=" + jadeEmergency + ", totalJADE=" + totalJade);
    }

    private static void test03_AddButtonDisabledWhen50NormalVehicles() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.refreshFleetControlState();
        boolean addEnabled = gui.isAddVehicleButtonEnabled();
        boolean removeEnabled = gui.isRemoveVehicleButtonEnabled();

        boolean ok = (!addEnabled) && removeEnabled && (FleetLifecycleManager.getInstance().getActiveNormalVehicleCount() == 50);

        check("T03: Botão ADICIONAR desativado quando existem 50 veículos normais",
                ok,
                "btnAdd.enabled=" + addEnabled + ", btnRemove.enabled=" + removeEnabled);
    }

    private static void test04_Removal50To49EnablesAddButton() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult res = gui.performInteractiveRemoveNormalVehicle(null);
        awaitAgentCount(72, 3000);
        gui.refreshFleetControlState();

        boolean ok = res.success
                && (fleet.getActiveNormalVehicleCount() == 49)
                && (fleet.getActivePriorityVehicleCount() == 3)
                && gui.isAddVehicleButtonEnabled()
                && gui.isRemoveVehicleButtonEnabled()
                && fleet.verifyOneToOneCorrespondence();

        check("T04: Remoção 50 -> 49 e reativação imediata do botão ADICIONAR",
                ok,
                "removido=" + res.vehicleId + ", normais=" + fleet.getActiveNormalVehicleCount()
                        + ", btnAdd.enabled=" + gui.isAddVehicleButtonEnabled());
    }

    private static void test05_Addition49To50DisablesAddButton() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult res = gui.performInteractiveAddNormalVehicle();
        awaitAgentCount(73, 3000);
        gui.refreshFleetControlState();

        boolean ok = res.success
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (!gui.isAddVehicleButtonEnabled())
                && gui.isRemoveVehicleButtonEnabled()
                && fleet.verifyOneToOneCorrespondence();

        check("T05: Adição 49 -> 50 e desativação imediata do botão ADICIONAR",
                ok,
                "adicionado=" + res.vehicleId + ", normais=" + fleet.getActiveNormalVehicleCount()
                        + ", btnAdd.enabled=" + gui.isAddVehicleButtonEnabled());
    }

    private static void test06_BlockAdditionAbove50() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult rejected = gui.performInteractiveAddNormalVehicle();
        VisualVehicle directSpawn = gui.spawnVehicleSafely("Carro_Excesso_51", 0, Color.BLUE, false, "NORMAL", 99);

        boolean ok = (!rejected.success)
                && (directSpawn == null)
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (fleet.getLiveJadeAgentsCountByType("VehicleAgent") == 50)
                && (!gui.isAddVehicleButtonEnabled());

        check("T06: Bloqueio estrito de adição acima de 50 veículos normais",
                ok,
                "rejected.success=" + rejected.success + ", directSpawn=" + directSpawn
                        + ", normais=" + fleet.getActiveNormalVehicleCount());
    }

    private static void test07_RemovalDownToZero() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        int removedCount = 0;
        while (fleet.getActiveNormalVehicleCount() > 0) {
            LifecycleOperationResult r = gui.performInteractiveRemoveNormalVehicle(null);
            if (!r.success) break;
            removedCount++;
        }
        awaitAgentCount(23, 5000); // 0 normais + 3 prioritários + 18 semáforos + 2 centrais = 23 agentes
        gui.refreshFleetControlState();

        boolean ok = (removedCount == 50)
                && (fleet.getActiveNormalVehicleCount() == 0)
                && (fleet.getActivePriorityVehicleCount() == 3) // Prioritários intocados!
                && (fleet.getLiveJadeAgentsCountByType("VehicleAgent") == 0)
                && gui.isAddVehicleButtonEnabled()
                && (!gui.isRemoveVehicleButtonEnabled());

        check("T07: Remoção dinâmica até 0 veículos normais preservando os 3 prioritários",
                ok,
                "removidos=" + removedCount + ", normais=" + fleet.getActiveNormalVehicleCount()
                        + ", prioritários=" + fleet.getActivePriorityVehicleCount()
                        + ", btnRemove.enabled=" + gui.isRemoveVehicleButtonEnabled());
    }

    private static void test08_BlockRemovalBelowZero() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult belowZero = gui.performInteractiveRemoveNormalVehicle(null);
        boolean ok = (!belowZero.success)
                && (fleet.getActiveNormalVehicleCount() == 0)
                && (fleet.getActivePriorityVehicleCount() == 3)
                && (!gui.isRemoveVehicleButtonEnabled())
                && gui.isAddVehicleButtonEnabled();

        // Restaura os 50 veículos normais para os testes seguintes
        for (int i = 0; i < 50; i++) {
            gui.performInteractiveAddNormalVehicle();
        }
        awaitAgentCount(73, 6000);
        gui.refreshFleetControlState();

        ok = ok && (fleet.getActiveNormalVehicleCount() == 50) && (!gui.isAddVehicleButtonEnabled());

        check("T08: Bloqueio de remoção abaixo de 0 e restauro integral 0 -> 50",
                ok,
                "belowZero.success=" + belowZero.success + ", normaisRestaurados=" + fleet.getActiveNormalVehicleCount());
    }

    private static void test09_StrictOneToOneCorrespondenceVisualAndJade() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        List<String> managerNormalIds = fleet.getActiveNormalVehicleIds();
        Set<String> uniqueNormalIds = new HashSet<>(managerNormalIds);
        List<VisualVehicle> visualVehicles = gui.getVisualVehiclesSnapshot();

        long visualNormals = visualVehicles.stream().filter(v -> !v.isEmergency).count();
        long visualPriorities = visualVehicles.stream().filter(v -> v.isEmergency).count();
        boolean oneToOne = fleet.verifyOneToOneCorrespondence();

        boolean ok = oneToOne
                && (managerNormalIds.size() == 50)
                && (uniqueNormalIds.size() == 50)
                && (visualNormals == 50)
                && (visualPriorities == 3)
                && (fleet.getLiveJadeAgentsCountByType("VehicleAgent") == 50);

        check("T09: Identidade estrita 1:1 entre veículo visual e agente JADE (sem duplicados)",
                ok,
                "1:1=" + oneToOne + ", únicos=" + uniqueNormalIds.size()
                        + ", visuaisNormais=" + visualNormals + ", visuaisPrioritários=" + visualPriorities);
    }

    private static void test10_NoOrphanAgentsAfterRepeatedAddRemoveCycles() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        // Executa 15 ciclos repetidos de remoção e adição (incluindo remoção de ID específico)
        for (int cycle = 0; cycle < 15; cycle++) {
            List<String> currentIds = fleet.getActiveNormalVehicleIds();
            String targetId = (cycle % 2 == 0 && !currentIds.isEmpty()) ? currentIds.get(0) : null;
            LifecycleOperationResult rem = gui.performInteractiveRemoveNormalVehicle(targetId);
            LifecycleOperationResult add = gui.performInteractiveAddNormalVehicle();
            if (!rem.success || !add.success) {
                check("T10: Ausência de agentes órfãos após 15 ciclos de remoção e adição", false,
                        "Falha no ciclo " + cycle);
                return;
            }
        }
        awaitAgentCount(73, 4000);

        // Verifica também que não há sobreposição física entre os 53 veículos ativos
        List<VisualVehicle> snapshot = gui.getVisualVehiclesSnapshot();
        boolean anyOverlap = false;
        for (int i = 0; i < snapshot.size(); i++) {
            for (int j = i + 1; j < snapshot.size(); j++) {
                double d = Math.hypot(snapshot.get(i).x - snapshot.get(j).x, snapshot.get(i).y - snapshot.get(j).y);
                if (d < 18.0) {
                    anyOverlap = true;
                    break;
                }
            }
        }

        boolean ok = (fleet.getActiveNormalVehicleCount() == 50)
                && (fleet.getLiveJadeAgentsCountByType("VehicleAgent") == 50)
                && (fleet.getLiveJadeAgentsCount() == 73)
                && fleet.verifyOneToOneCorrespondence()
                && !anyOverlap;

        check("T10: Ausência de agentes órfãos ou duplicações após 15 ciclos repetidos (+/-)",
                ok,
                "normais=" + fleet.getActiveNormalVehicleCount()
                        + ", VehicleAgent=" + fleet.getLiveJadeAgentsCountByType("VehicleAgent")
                        + ", totalJADE=" + fleet.getLiveJadeAgentsCount()
                        + ", sobreposições=" + anyOverlap);
    }

    // =========================================================================
    // TESTES 11 A 19 — CONTROLO MANUAL POR CLIQUE E TRANSIÇÕES SEGURAS
    // =========================================================================

    private static void test11_ClickSelectsCorrectIntersectionWithoutChangingColors() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        NetworkTopologyConfig top = gui.getTopology();
        boolean allHitsCorrect = true;
        boolean colorsUnchangedOnClick = true;

        for (int idx = 0; idx < top.getIntersectionCount(); idx++) {
            LightState nsBefore = gui.getTrafficLightState(idx, 0);
            LightState ewBefore = gui.getTrafficLightState(idx, 1);

            int centerX = top.getIntersectionX(idx) + top.getRoadWidth() / 2;
            int centerY = top.getIntersectionY(idx) + top.getRoadWidth() / 2;

            int hit = gui.selectIntersectionByClick(centerX, centerY);
            if (hit != idx || gui.getSelectedIntersectionIndex() != idx) {
                allHitsCorrect = false;
            }

            LightState nsAfter = gui.getTrafficLightState(idx, 0);
            LightState ewAfter = gui.getTrafficLightState(idx, 1);
            if (nsBefore != nsAfter || ewBefore != ewAfter) {
                colorsUnchangedOnClick = false;
            }
        }

        int outsideHit = gui.selectIntersectionByClick(5, 95);

        boolean ok = allHitsCorrect && colorsUnchangedOnClick && (outsideHit == -1);
        check("T11: Clique seleciona o cruzamento correto (C1..C9) sem alterar diretamente a cor no ecrã",
                ok,
                "allHitsCorrect=" + allHitsCorrect + ", colorsUnchangedOnClick=" + colorsUnchangedOnClick
                        + ", outsideHit=" + outsideHit);
    }

    private static void test12_ManualRequestNS_RequestedAcceptedApplied() {
        SimulationClock.enableSimulatedMode(10000L);
        IntersectionSafetyAuthority.resetAll();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        // Cruzamento C2 (índice 1) arranca em EW_GREEN (NS=RED, EW=GREEN)
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(1);
        auth.setMinManualSwitchGreenMs(0L);

        ManualOverrideRequest req = gui.requestManualPhaseFromGui(1, 0); // Solicita Verde NS

        // Imediatamente após o pedido: estado tem de ser ACCEPTED (e NÃO APPLIED!) e fase continua EW_GREEN ou EW_YELLOW
        boolean acceptedNotAppliedYet = (req != null)
                && (req.getState() == ManualRequestState.ACCEPTED)
                && (!req.wasEverApplied())
                && (auth.getLightState(0) == LightState.RED);

        // 1º Tick: entra em EW_YELLOW (1500ms)
        auth.updateTick(0, 0);
        boolean inYellow = (auth.getCurrentPhase() == Phase.EW_YELLOW)
                && (req.getState() == ManualRequestState.ACCEPTED);

        // Avança 1500ms -> entra em ALL_RED (1000ms)
        SimulationClock.advanceMillis(1500L);
        auth.updateTick(0, 0);
        boolean inAllRed = (auth.getCurrentPhase() == Phase.ALL_RED)
                && (req.getState() == ManualRequestState.ACCEPTED);

        // Avança 1000ms -> entra em NS_GREEN e marca APPLIED
        SimulationClock.advanceMillis(1000L);
        auth.updateTick(0, 0);
        boolean appliedInNsGreen = (auth.getCurrentPhase() == Phase.NS_GREEN)
                && (req.getState() == ManualRequestState.APPLIED)
                && req.wasEverApplied()
                && (req.getAcceptedAtMs() <= req.getAppliedAtMs() - 2500L)
                && (gui.getTrafficLightState(1, 0) == LightState.GREEN)
                && (gui.getTrafficLightState(1, 1) == LightState.RED);

        boolean ok = acceptedNotAppliedYet && inYellow && inAllRed && appliedInNsGreen;
        check("T12: Pedido manual para Norte-Sul distingue ACCEPTED de APPLIED após YELLOW+ALL_RED",
                ok,
                "acceptedFirst=" + acceptedNotAppliedYet + ", yellow=" + inYellow
                        + ", allRed=" + inAllRed + ", applied=" + appliedInNsGreen);
    }

    private static void test13_ManualRequestEW_RequestedAcceptedApplied() {
        SimulationClock.enableSimulatedMode(20000L);
        IntersectionSafetyAuthority.resetAll();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        // Cruzamento C1 (índice 0) arranca em NS_GREEN (NS=GREEN, EW=RED)
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        auth.setMinManualSwitchGreenMs(0L);

        ManualOverrideRequest req = gui.requestManualPhaseFromGui(0, 1); // Solicita Verde EW

        boolean acceptedFirst = (req != null)
                && (req.getState() == ManualRequestState.ACCEPTED)
                && (!req.wasEverApplied());

        auth.updateTick(0, 0); // -> NS_YELLOW
        boolean sawYellow = (auth.getCurrentPhase() == Phase.NS_YELLOW);

        SimulationClock.advanceMillis(1500L);
        auth.updateTick(0, 0); // -> ALL_RED
        boolean sawAllRed = (auth.getCurrentPhase() == Phase.ALL_RED);

        SimulationClock.advanceMillis(1000L);
        auth.updateTick(0, 0); // -> EW_GREEN (APPLIED)
        boolean appliedEw = (auth.getCurrentPhase() == Phase.EW_GREEN)
                && (req.getState() == ManualRequestState.APPLIED)
                && (gui.getTrafficLightState(0, 0) == LightState.RED)
                && (gui.getTrafficLightState(0, 1) == LightState.GREEN);

        boolean ok = acceptedFirst && sawYellow && sawAllRed && appliedEw;
        check("T13: Pedido manual para Este-Oeste distingue ACCEPTED de APPLIED após YELLOW+ALL_RED",
                ok,
                "acceptedFirst=" + acceptedFirst + ", yellow=" + sawYellow
                        + ", allRed=" + sawAllRed + ", appliedEw=" + appliedEw);
    }

    private static void test14_MandatoryYellowTransitionOnManualSwitch() {
        SimulationClock.enableSimulatedMode(30000L);
        IntersectionSafetyAuthority.resetAll();

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(4); // C5_C (NS_GREEN)
        auth.setMinManualSwitchGreenMs(500L);

        // Solicita mudança para EW apenas 100ms após abrir NS_GREEN (antes do verde mínimo de 500ms)
        SimulationClock.advanceMillis(100L);
        ManualOverrideRequest req = auth.requestManualPhase(1);
        auth.updateTick(0, 0);
        boolean respectsMinGreen = (auth.getCurrentPhase() == Phase.NS_GREEN)
                && (req.getState() == ManualRequestState.ACCEPTED);

        // Atinge os 500ms de verde mínimo -> entra obrigatoriamente em NS_YELLOW
        SimulationClock.advanceMillis(400L);
        auth.updateTick(0, 0);
        boolean enteredYellow = (auth.getCurrentPhase() == Phase.NS_YELLOW)
                && (auth.getLightState(0) == LightState.YELLOW)
                && (auth.getLightState(1) == LightState.RED);

        // Durante os 1500ms de amarelo (ex: aos 1400ms), ainda permanece em NS_YELLOW
        SimulationClock.advanceMillis(1400L);
        auth.updateTick(0, 0);
        boolean heldFullYellow = (auth.getCurrentPhase() == Phase.NS_YELLOW);

        boolean ok = respectsMinGreen && enteredYellow && heldFullYellow;
        check("T14: Transição obrigatória por YELLOW (1500ms) respeitando tempo mínimo de segurança",
                ok,
                "respectsMinGreen=" + respectsMinGreen + ", enteredYellow=" + enteredYellow + ", heldFullYellow=" + heldFullYellow);
    }

    private static void test15_MandatoryAllRedTransitionOnManualSwitch() {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(4);

        // Conclui os restantes 100ms do amarelo do teste T14 -> entra em ALL_RED
        SimulationClock.advanceMillis(100L);
        auth.updateTick(0, 0);
        boolean enteredAllRed = (auth.getCurrentPhase() == Phase.ALL_RED)
                && (auth.getLightState(0) == LightState.RED)
                && (auth.getLightState(1) == LightState.RED);

        // Aos 900ms de ALL_RED (< 1000ms), ainda permanece em ALL_RED
        SimulationClock.advanceMillis(900L);
        auth.updateTick(0, 0);
        boolean heldAllRed = (auth.getCurrentPhase() == Phase.ALL_RED);

        // Aos 1000ms de ALL_RED -> abre EW_GREEN e aplica o pedido manual
        SimulationClock.advanceMillis(100L);
        auth.updateTick(0, 0);
        boolean openedTargetGreen = (auth.getCurrentPhase() == Phase.EW_GREEN)
                && (auth.getActiveManualRequest() != null)
                && (auth.getActiveManualRequest().getState() == ManualRequestState.APPLIED);

        boolean ok = enteredAllRed && heldAllRed && openedTargetGreen;
        check("T15: Transição obrigatória por ALL_RED (1000ms) antes de abrir o verde solicitado",
                ok,
                "enteredAllRed=" + enteredAllRed + ", heldAllRed=" + heldAllRed + ", openedTargetGreen=" + openedTargetGreen);
    }

    private static void test16_ZeroIncompatibleGreensAcrossManualTransitions() {
        SimulationClock.enableSimulatedMode(50000L);
        IntersectionSafetyAuthority.resetAll();

        int violations = 0;
        for (int inter = 0; inter < 9; inter++) {
            IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(inter);
            auth.setMinManualSwitchGreenMs(0L);
            for (int toggle = 0; toggle < 6; toggle++) {
                auth.requestManualPhase(toggle % 2);
                for (int step = 0; step < 30; step++) {
                    SimulationClock.advanceMillis(100L);
                    auth.updateTick(2, 2);
                    LightState ns = auth.getLightState(0);
                    LightState ew = auth.getLightState(1);
                    if ((ns == LightState.GREEN && ew == LightState.GREEN)
                            || (ns == LightState.GREEN && ew == LightState.YELLOW)
                            || (ns == LightState.YELLOW && ew == LightState.GREEN)) {
                        violations++;
                    }
                }
            }
        }

        check("T16: Ausência absoluta de verdes incompatíveis nos 9 cruzamentos em transições manuais",
                violations == 0,
                "violaçõesDeInvariante=" + violations);
    }

    private static void test17_ReturnToAutomaticModeByOperatorAndTimeout() {
        SimulationClock.enableSimulatedMode(100000L);
        IntersectionSafetyAuthority.resetAll();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        // Caso A: Retorno explícito pelo operador
        IntersectionSafetyAuthority auth0 = IntersectionSafetyAuthority.getAuthority(0); // NS_GREEN
        ManualOverrideRequest reqA = gui.requestManualPhaseFromGui(0, 0); // APPLIED imediato
        boolean activeBeforeReturn = auth0.isManualModeActive() && (reqA.getState() == ManualRequestState.APPLIED);
        boolean returnedByOperator = gui.returnToAutomaticFromGui(0);
        boolean autoAfterOperator = (!auth0.isManualModeActive())
                && (auth0.getActiveManualRequest() == null)
                && (reqA.getState() == ManualRequestState.EXPIRED);

        // Caso B: Retorno automático após expirar a duração máxima configurada (ex: 5000ms)
        IntersectionSafetyAuthority auth2 = IntersectionSafetyAuthority.getAuthority(2); // NS_GREEN
        ManualOverrideRequest reqB = auth2.requestManualPhase(0, 5000L);
        boolean activeB = auth2.isManualModeActive() && (reqB.getState() == ManualRequestState.APPLIED);
        SimulationClock.advanceMillis(5100L);
        auth2.updateTick(1, 3);
        boolean autoAfterTimeout = (!auth2.isManualModeActive())
                && (auth2.getActiveManualRequest() == null)
                && (reqB.getState() == ManualRequestState.EXPIRED);

        boolean ok = activeBeforeReturn && returnedByOperator && autoAfterOperator && activeB && autoAfterTimeout;
        check("T17: Retorno seguro ao modo automático por comando do operador e por expiração de timeout",
                ok,
                "porOperador=" + autoAfterOperator + ", porTimeout=" + autoAfterTimeout);
    }

    private static void test18_EmergencyPriorityRejectsAndPreemptsManualControl() {
        SimulationClock.enableSimulatedMode(150000L);
        IntersectionSafetyAuthority.resetAll();

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(3); // C4 (EW_GREEN)
        auth.setMinManualSwitchGreenMs(0L);

        // 1. Ativa controlo manual em C4 para EW_GREEN
        ManualOverrideRequest manualActive = auth.requestManualPhase(1);
        boolean wasManualApplied = (manualActive.getState() == ManualRequestState.APPLIED) && auth.isManualModeActive();

        // 2. Chega uma emergência no eixo NS (0): deve PREEMPTAR imediatamente o controlo manual!
        auth.requestEmergency("Ambulancia_INEM_01", 0, null);
        boolean preemptedByEmergency = (manualActive.getState() == ManualRequestState.PREEMPTED)
                && (!auth.isManualModeActive())
                && auth.isEmergencyActive();

        // 3. Enquanto a emergência está ativa, novo pedido manual do utilizador tem de ser REJEITADO!
        ManualOverrideRequest rejectedDuringEmergency = auth.requestManualPhase(1);
        boolean rejectedProperly = (rejectedDuringEmergency.getState() == ManualRequestState.REJECTED)
                && rejectedDuringEmergency.getStatusReason().contains("REJECTED_EMERGENCY_PRIORITY");

        // 4. A emergência transita com segurança (EW_YELLOW -> ALL_RED -> NS_GREEN) e conclui travessia
        SimulationClock.advanceMillis(1500L);
        auth.updateTick(0, 0); // -> ALL_RED
        SimulationClock.advanceMillis(1000L);
        auth.updateTick(0, 0); // -> NS_GREEN (VERDE_CONCEDIDO à ambulância)
        boolean emergencyGotGreen = (auth.getCurrentPhase() == Phase.NS_GREEN);

        auth.notifyEmergencyCleared("Ambulancia_INEM_01");
        boolean restoredAutoAfterEmergency = (!auth.isEmergencyActive()) && (!auth.isManualModeActive());

        boolean ok = wasManualApplied && preemptedByEmergency && rejectedProperly && emergencyGotGreen && restoredAutoAfterEmergency;
        check("T18: Prioridade absoluta de emergência rejeita e preempta controlo manual com transição segura",
                ok,
                "preempted=" + preemptedByEmergency + ", rejectedDuringEm=" + rejectedProperly
                        + ", emGreen=" + emergencyGotGreen + ", restoredAuto=" + restoredAutoAfterEmergency);
    }

    private static void test19_RepeatedAndConcurrentManualClicksNeverSkipPhases() {
        SimulationClock.enableSimulatedMode(200000L);
        IntersectionSafetyAuthority.resetAll();

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(5); // C6 (EW_GREEN)
        auth.setMinManualSwitchGreenMs(0L);

        // Solicita NS (0) -> inicia EW_YELLOW
        auth.requestManualPhase(0);
        auth.updateTick(0, 0);
        boolean startedYellow = (auth.getCurrentPhase() == Phase.EW_YELLOW);

        // Utilizador clica 10 vezes seguidas em NS e EW a meio do amarelo (aos 500ms)
        SimulationClock.advanceMillis(500L);
        for (int k = 0; k < 10; k++) {
            auth.requestManualPhase(k % 2);
            auth.updateTick(0, 0);
        }
        // NUNCA pode ter saltado diretamente para NS_GREEN nem EW_GREEN sem terminar EW_YELLOW (1500ms) e ALL_RED (1000ms)!
        boolean stillInYellowAt500ms = (auth.getCurrentPhase() == Phase.EW_YELLOW);

        // Completa os restantes 1000ms do amarelo -> entra obrigatoriamente em ALL_RED
        SimulationClock.advanceMillis(1000L);
        auth.updateTick(0, 0);
        boolean enteredAllRed = (auth.getCurrentPhase() == Phase.ALL_RED);

        // Mais cliques durante ALL_RED (aos 400ms) -> continua em ALL_RED até perfazer 1000ms
        SimulationClock.advanceMillis(400L);
        auth.requestManualPhase(0);
        auth.updateTick(0, 0);
        boolean stillInAllRedAt400ms = (auth.getCurrentPhase() == Phase.ALL_RED);

        // Completa os restantes 600ms de ALL_RED -> abre NS_GREEN
        SimulationClock.advanceMillis(600L);
        auth.updateTick(0, 0);
        boolean openedNsGreenSafely = (auth.getCurrentPhase() == Phase.NS_GREEN)
                && (auth.getActiveManualRequest() != null)
                && (auth.getActiveManualRequest().getState() == ManualRequestState.APPLIED);

        boolean ok = startedYellow && stillInYellowAt500ms && enteredAllRed && stillInAllRedAt400ms && openedNsGreenSafely;
        check("T19: Cliques repetidos e concorrentes nunca saltam YELLOW nem ALL_RED",
                ok,
                "stillInYellow=" + stillInYellowAt500ms + ", enteredAllRed=" + enteredAllRed
                        + ", stillInAllRed=" + stillInAllRedAt400ms + ", openedNsGreen=" + openedNsGreenSafely);
    }

    // =========================================================================
    // TESTES 20 A 25 — OBEDIÊNCIA FÍSICA DOS VEÍCULOS, A*, 2x2/3x3 E EDT SWING
    // =========================================================================

    private static void test20_PhysicalVehicleStopsOnManualRedLight() {
        SimulationClock.enableSimulatedMode(300000L);
        IntersectionSafetyAuthority.resetAll();

        CitySimulationGUI testGui = new CitySimulationGUI(true, NetworkTopologyConfig.CONFIG_3X3);
        IntersectionSafetyAuthority auth0 = IntersectionSafetyAuthority.getAuthority(0); // C1_NW (roadX=170, roadY=150)
        auth0.setMinManualSwitchGreenMs(0L);

        // Solicita Verde EW (1) em C1 -> NS passa de GREEN para YELLOW -> ALL_RED -> RED
        auth0.requestManualPhase(1);
        auth0.updateTick(0, 0);
        SimulationClock.advanceMillis(1500L);
        auth0.updateTick(0, 0);
        SimulationClock.advanceMillis(1000L);
        auth0.updateTick(0, 0);
        testGui.setTrafficLights(0, auth0.getLightState(0), auth0.getLightState(1));

        // Coloca um veículo no eixo Norte-Sul (dir=0, descendo para a stop line de C1 em Y=148)
        double laneX = NetworkTopologyConfig.CONFIG_3X3.getLaneCenterCoordinate(0, 0, 1);
        VisualVehicle v = new VisualVehicle("Carro_Teste_Stop", laneX, 95.0, 2.4, 0,
                Color.CYAN, CitySimulationGUI.ManeuverIntent.GO_STRAIGHT, 1);
        testGui.clearVehicles();
        testGui.getRoadGraph();
        // Adiciona através de spawn controlado na posição exata
        testGui.addVehicle("Carro_Teste_Stop", 0, Color.CYAN, CitySimulationGUI.ManeuverIntent.GO_STRAIGHT);
        VisualVehicle spawned = testGui.getVisualVehicle("Carro_Teste_Stop");
        spawned.x = laneX;
        spawned.y = 95.0;
        spawned.direction = 0;
        spawned.currentSpeed = 2.2;

        // Avança 80 ticks com NS=RED em C1
        for (int t = 0; t < 80; t++) {
            testGui.stepSimulationTick();
        }

        double stopLineY = NetworkTopologyConfig.CONFIG_3X3.getRoadY(0) - 2.0; // 148.0
        boolean stoppedBeforeLine = (spawned.currentSpeed == 0.0)
                && (spawned.y <= stopLineY + 0.1)
                && (testGui.getRedLightViolationsCount() == 0);

        check("T20: Paragem física rigorosa dos veículos no vermelho imposto por controlo manual",
                stoppedBeforeLine,
                "speed=" + spawned.currentSpeed + ", posY=" + String.format("%.2f", spawned.y)
                        + " (stopLine=" + stopLineY + "), redViolations=" + testGui.getRedLightViolationsCount());
    }

    private static void test21_PhysicalVehicleResumesOnManualGreenWithZeroIllegalOrDuplicateCrossings() {
        CitySimulationGUI testGui = new CitySimulationGUI(true, NetworkTopologyConfig.CONFIG_3X3);
        TrafficMetricsCollector.reset(9);
        SimulationClock.enableSimulatedMode(400000L);
        IntersectionSafetyAuthority.resetAll();

        IntersectionSafetyAuthority auth0 = IntersectionSafetyAuthority.getAuthority(0);
        auth0.setMinManualSwitchGreenMs(0L);
        // Começa com NS=RED, EW=GREEN em C1
        auth0.requestManualPhase(1);
        auth0.updateTick(0, 0);
        SimulationClock.advanceMillis(1500L);
        auth0.updateTick(0, 0);
        SimulationClock.advanceMillis(1000L);
        auth0.updateTick(0, 0);
        testGui.setTrafficLights(0, auth0.getLightState(0), auth0.getLightState(1));

        double laneX = NetworkTopologyConfig.CONFIG_3X3.getLaneCenterCoordinate(0, 0, 1);
        testGui.addVehicle("Carro_Teste_Resume", 0, Color.GREEN, CitySimulationGUI.ManeuverIntent.GO_STRAIGHT);
        VisualVehicle v = testGui.getVisualVehicle("Carro_Teste_Resume");
        v.x = laneX;
        v.y = 110.0;
        v.direction = 0;
        v.currentSpeed = 2.0;
        v.intent = CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;

        // 1. Para no vermelho NS
        for (int t = 0; t < 60; t++) {
            testGui.stepSimulationTick();
        }
        boolean stoppedAtRed = (v.currentSpeed == 0.0) && (TrafficMetricsCollector.getInstance().getTotalCrossings() == 0);

        // 2. Operador solicita Verde NS (0) -> passa por EW_YELLOW (1500ms) + ALL_RED (1000ms) -> NS_GREEN
        auth0.requestManualPhase(0);
        auth0.updateTick(1, 0);
        SimulationClock.advanceMillis(1500L);
        auth0.updateTick(1, 0);
        SimulationClock.advanceMillis(1000L);
        auth0.updateTick(1, 0);
        testGui.setTrafficLights(0, auth0.getLightState(0), auth0.getLightState(1));

        // 3. Veículo retoma marcha e transpõe completamente C1 (Y > 150 + 96 = 246)
        for (int t = 0; t < 90; t++) {
            testGui.stepSimulationTick();
        }

        long crossings = TrafficMetricsCollector.getInstance().getTotalCrossings();
        boolean resumedAndCrossedOnce = stoppedAtRed
                && (v.y > 246.0)
                && (v.currentSpeed > 1.0)
                && (crossings == 1)
                && (testGui.getRedLightViolationsCount() == 0);

        check("T21: Retoma física de marcha no verde manual com exatamente 1 travessia e 0 violações",
                resumedAndCrossedOnce,
                "stoppedAtRed=" + stoppedAtRed + ", finalY=" + String.format("%.1f", v.y)
                        + ", crossings=" + crossings + ", redViolations=" + testGui.getRedLightViolationsCount());
    }

    private static void test22_PreservationOfAStarRoutesDuringManualChanges() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        List<VisualVehicle> vehicles = gui.getVisualVehiclesSnapshot();

        int validRoutesBefore = 0;
        for (VisualVehicle v : vehicles) {
            if (v.activeRoute != null && v.activeRoute.reachable && !v.activeRoute.nodePath.isEmpty()) {
                validRoutesBefore++;
            }
        }

        // Executa alterações manuais em C1, C5 e C9 e avança 40 ticks cinemáticos
        SimulationClock.enableSimulatedMode(500000L);
        IntersectionSafetyAuthority.getAuthority(0).requestManualPhase(1);
        IntersectionSafetyAuthority.getAuthority(4).requestManualPhase(1);
        IntersectionSafetyAuthority.getAuthority(8).requestManualPhase(1);
        for (int step = 0; step < 40; step++) {
            SimulationClock.advanceMillis(100L);
            gui.stepSimulationTickWithAuthority();
        }

        List<VisualVehicle> after = gui.getVisualVehiclesSnapshot();
        int validRoutesAfter = 0;
        for (VisualVehicle v : after) {
            if (v.activeRoute != null && v.activeRoute.reachable && !v.activeRoute.nodePath.isEmpty()) {
                AStarPathfinder.RouteResult checkRoute = v.activeRoute;
                if (checkRoute.totalCost > 0) {
                    validRoutesAfter++;
                }
            }
        }

        boolean ok = (validRoutesBefore == 53) && (validRoutesAfter == 53) && (gui.getRedLightViolationsCount() == 0);
        check("T22: Preservação integral das rotas A* dos 53 veículos durante alterações manuais",
                ok,
                "rotasAntes=" + validRoutesBefore + "/53, rotasDepois=" + validRoutesAfter
                        + "/53, redViolations=" + gui.getRedLightViolationsCount());
    }

    private static void test23_CompatibilityWith2x2Topology() {
        CitySimulationGUI gui2x2 = new CitySimulationGUI(true, NetworkTopologyConfig.CONFIG_2X2);
        IntersectionSafetyAuthority.resetAll();
        SimulationClock.enableSimulatedMode(600000L);

        int interCount = gui2x2.getTopology().getIntersectionCount();
        int c4X = gui2x2.getTopology().getIntersectionX(3) + 48;
        int c4Y = gui2x2.getTopology().getIntersectionY(3) + 48;
        int hit = gui2x2.selectIntersectionByClick(c4X, c4Y);

        IntersectionSafetyAuthority auth3 = IntersectionSafetyAuthority.getAuthority(3);
        auth3.setMinManualSwitchGreenMs(0L);
        ManualOverrideRequest req = auth3.requestManualPhase(0);
        auth3.updateTick(0, 0);
        SimulationClock.advanceMillis(1500L);
        auth3.updateTick(0, 0);
        SimulationClock.advanceMillis(1000L);
        auth3.updateTick(0, 0);

        boolean ok = (interCount == 4)
                && (hit == 3)
                && (req.getState() == ManualRequestState.APPLIED)
                && (auth3.getCurrentPhase() == Phase.NS_GREEN);

        check("T23: Compatibilidade integral do controlo manual e seleção por clique no modo 2×2 (4 cruzamentos)",
                ok,
                "interCount=" + interCount + ", hitC4=" + hit + ", manualState=" + req.getState());
    }

    private static void test24_CompatibilityWith3x3Topology() {
        NetworkTopologyConfig.activate3x3();
        CitySimulationGUI gui3x3 = CitySimulationGUI.getInstance();
        IntersectionSafetyAuthority.resetAll();
        SimulationClock.enableSimulatedMode(700000L);

        int interCount = gui3x3.getTopology().getIntersectionCount();
        int c9X = gui3x3.getTopology().getIntersectionX(8) + 48;
        int c9Y = gui3x3.getTopology().getIntersectionY(8) + 48;
        int hit = gui3x3.selectIntersectionByClick(c9X, c9Y);

        IntersectionSafetyAuthority auth8 = IntersectionSafetyAuthority.getAuthority(8); // C9_SE (NS_GREEN)
        auth8.setMinManualSwitchGreenMs(0L);
        ManualOverrideRequest req = gui3x3.requestManualPhaseFromGui(8, 1); // Solicita EW
        auth8.updateTick(0, 0);
        SimulationClock.advanceMillis(1500L);
        auth8.updateTick(0, 0);
        SimulationClock.advanceMillis(1000L);
        auth8.updateTick(0, 0);

        boolean ok = (interCount == 9)
                && (hit == 8)
                && (req.getState() == ManualRequestState.APPLIED)
                && (auth8.getCurrentPhase() == Phase.EW_GREEN)
                && (FleetLifecycleManager.getInstance().getActiveNormalVehicleCount() == 50);

        check("T24: Compatibilidade integral do controlo manual e gestão de frota no modo 3×3 (9 cruzamentos)",
                ok,
                "interCount=" + interCount + ", hitC9=" + hit + ", manualState=" + req.getState()
                        + ", normais=" + FleetLifecycleManager.getInstance().getActiveNormalVehicleCount());
    }

    private static void test25_NonBlockingGuiEdtResponsiveness() {
        SimulationClock.disableSimulatedMode();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        AtomicLong edtRemoveDispatchMs = new AtomicLong(9999L);
        AtomicLong edtAddDispatchMs = new AtomicLong(9999L);
        AtomicLong edtManualDispatchMs = new AtomicLong(9999L);
        AtomicBoolean allDispatchedOnEdt = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            long t0 = System.nanoTime();
            gui.getBtnRemoveVehicle().doClick(0);
            long t1 = System.nanoTime();
            edtRemoveDispatchMs.set((t1 - t0) / 1_000_000L);

            long t2 = System.nanoTime();
            gui.getBtnManualNS().doClick(0);
            long t3 = System.nanoTime();
            edtManualDispatchMs.set((t3 - t2) / 1_000_000L);

            allDispatchedOnEdt.set(true);
            latch.countDown();
        });

        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gui.awaitPendingGuiActions(3000);
        awaitAgentCount(72, 3000);

        CountDownLatch latchAdd = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            long t0 = System.nanoTime();
            gui.getBtnAddVehicle().doClick(0);
            long t1 = System.nanoTime();
            edtAddDispatchMs.set((t1 - t0) / 1_000_000L);
            latchAdd.countDown();
        });

        try {
            latchAdd.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gui.awaitPendingGuiActions(3000);
        awaitAgentCount(73, 3000);

        boolean ok = allDispatchedOnEdt.get()
                && (edtRemoveDispatchMs.get() < 50L)
                && (edtAddDispatchMs.get() < 50L)
                && (edtManualDispatchMs.get() < 50L)
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (!gui.isAddVehicleButtonEnabled());

        check("T25: Não bloqueio da GUI (Event Dispatch Thread responde em < 50ms em todas as ações)",
                ok,
                "edtRemove=" + edtRemoveDispatchMs.get() + "ms, edtAdd=" + edtAddDispatchMs.get()
                        + "ms, edtManual=" + edtManualDispatchMs.get() + "ms, normaisFinais=" + fleet.getActiveNormalVehicleCount());
    }
}

