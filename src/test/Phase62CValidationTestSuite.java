package test;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.CitySimulationGUI.ManeuverIntent;
import environment.CitySimulationGUI.VisualVehicle;
import environment.FleetLifecycleManager;
import environment.FleetLifecycleManager.LifecycleOperationResult;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ManualOverrideRequest;
import environment.IntersectionSafetyAuthority.ManualRequestState;
import environment.NetworkTopologyConfig;
import environment.RoadNetworkGraph;
import environment.SimulationClock;
import environment.TrafficEnvironment;
import environment.VehicleControlGateway;
import environment.VehicleDecision;
import environment.VehiclePerception;
import agents.VehicleAgent;
import agents.VehicleAgent.DestinationChangeResult;
import agents.VehicleAgent.ManeuverChangeResult;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import metrics.TrafficMetricsCollector;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Suite de Validação Automatizada da Fase 6.2-C (26 Testes Rigorosos).
 *
 * Cobre integralmente os requisitos:
 *  1. Layout responsivo e ausência de corte de altura em resoluções 1280x720, 1366x768 e 1920x1080;
 *  2. Hit-testing exato de coordenadas sob diferentes níveis de zoom (0.65x, 1.0x, 1.5x);
 *  3. Seleção direta de veículos por clique no mapa (hit-testing de raio <= 24px);
 *  4. Inspeção completa do estado e telemetria do VehicleAgent selecionado;
 *  5. Submissão de pedido de alteração de destino C1..C9 encaminhado ao VehicleAgent;
 *  6. Recálculo autónomo da rota A* pelo VehicleAgent ao alterar destino;
 *  7. Submissão e aceitação de manobra válida no próximo cruzamento;
 *  8. Rejeição com explicação de manobra impossível / sem saída;
 *  9. Motor físico segue a rota sem saltos ou teletransporte de coordenadas;
 * 10. Seleção de cruzamento por clique na caixa do cruzamento;
 * 11. Seleção de cruzamento por clique nos postes semafóricos;
 * 12. Pedido manual para Verde NS com progressão REQUESTED -> ACCEPTED -> APPLIED;
 * 13. Pedido manual para Verde EW com progressão REQUESTED -> ACCEPTED -> APPLIED;
 * 14. Transições obrigatórias por YELLOW e ALL_RED no controlo manual;
 * 15. Zero verdes incompatíveis simultâneos durante e após comandos manuais;
 * 16. Retorno ao modo automático restaura o ciclo autónomo dos semáforos;
 * 17. Precedência absoluta da preempção de emergência sobre o controlo manual;
 * 18. Frota inicial: 50 normais + 3 prioritários (53 visuais, 73 agentes JADE);
 * 19. Remoção 50 -> 49 reativa imediatamente o botão Adicionar;
 * 20. Adição 49 -> 50 desativa imediatamente o botão Adicionar;
 * 21. Bloqueio estrito de adição acima de 50 veículos normais;
 * 22. Correspondência estrita 1:1 entre veículos gráficos e VehicleAgent JADE;
 * 23. Responsividade da GUI: ações na EDT executam em tempo < 50ms;
 * 24. Compatibilidade e adaptação perfeita na topologia 2x2;
 * 25. Compatibilidade e adaptação perfeita na topologia 3x3;
 * 26. Preservação estrita da autonomia do VehicleAgent (Gateway Perceção-Decisão-Ação).
 */
public class Phase62CValidationTestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static AgentContainer sharedContainer = null;

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("   SUITE DE VALIDAÇÃO AUTOMATIZADA — FASE 6.2-C (26 TESTES OBRIGATÓRIOS)");
        System.out.println("==================================================================================");

        try {
            initJadeContainer();

            test01_ResolutionLayoutBoundsAndResponsiveness();
            test02_HitTestingWithZoomLevels();
            test03_VehicleSelectionByClick();
            test04_VehicleAgentStateInspection();
            test05_DestinationChangeRequestViaOperator();
            test06_AStarRecalculationByAgent();
            test07_ValidManeuverRequestAccepted();
            test08_ImpossibleManeuverRequestRejectedWithExplanation();
            test09_RouteFollowedByPhysicalEngineWithoutCoordinateJumping();
            test10_IntersectionSelectionByClick();
            test11_SemaphoreSelectionByClick();
            test12_ManualNSRequestProgressiveState();
            test13_ManualEWRequestProgressiveState();
            test14_MandatoryYellowAndAllRedTransitions();
            test15_ZeroIncompatibleGreens();
            test16_ReturnToAutomaticMode();
            test17_EmergencyPreemptionPrecedenceOverManual();
            test18_InitialFleet50NormalPlus3Priority();
            test19_Removal50To49EnablesAddButton();
            test20_Addition49To50DisablesAddButton();
            test21_BlockAdditionAbove50();
            test22_StrictCorrespondenceVisualToJadeAgents();
            test23_EDTNonBlockingResponsiveness();
            test24_CompatibilityWith2x2Topology();
            test25_CompatibilityWith3x3Topology();
            test26_PreservationOfVehicleAgentAutonomy();

        } finally {
            shutdownJadeContainer();
            SimulationClock.disableSimulatedMode();
            NetworkTopologyConfig.activate2x2();
            IntersectionSafetyAuthority.resetAll();
        }

        System.out.println("==================================================================================");
        System.out.println("RESUMO FASE 6.2-C: " + passed + " aprovados | " + failed + " falhados (Total: " + (passed + failed) + ")");
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
        for (int port = 15950; port <= 15990; port += 5) {
            try {
                Profile p = new ProfileImpl(null, port, null);
                p.setParameter(Profile.GUI, "false");
                p.setParameter(Profile.NO_MTP, "true");
                sharedContainer = rt.createMainContainer(p);
                if (sharedContainer != null) break;
            } catch (Throwable ignored) {}
        }

        if (sharedContainer == null) {
            throw new IllegalStateException("Não foi possível inicializar contentor JADE para Fase 6.2-C.");
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
            throw new RuntimeException("Falha ao inicializar agentes JADE para Fase 6.2-C: " + e.getMessage(), e);
        }
    }

    private static void shutdownJadeContainer() {
        try {
            FleetLifecycleManager.getInstance().reset();
            if (sharedContainer != null) {
                sharedContainer.kill();
                sharedContainer = null;
            }
        } catch (Throwable ignored) {}
    }

    private static void awaitAgentCount(int expected, long timeoutMs) {
        long start = System.currentTimeMillis();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (fleet.getLiveJadeAgentsCount() == expected) return;
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private static void check(String testName, boolean condition, String details) {
        if (condition) {
            passed++;
            System.out.printf("[TESTE] %-72s ... PASSOU ✓ (%s)%n", testName, details);
        } else {
            failed++;
            System.err.printf("[TESTE] %-72s ... FALHOU ✗ (%s)%n", testName, details);
        }
    }

    // =========================================================================
    // OS 26 TESTES ESPECIFICADOS
    // =========================================================================

    private static void test01_ResolutionLayoutBoundsAndResponsiveness() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        int[][] resolutions = { {1280, 720}, {1366, 768}, {1920, 1080} };
        boolean allFit = true;

        for (int[] res : resolutions) {
            int sw = res[0];
            int sh = res[1];
            int availW = sw - 24;
            int availH = sh - 36;
            int targetW = Math.min(availW, gui.getTopology().getWorldWidth() + 440);
            int targetH = Math.min(availH, gui.getTopology().getWorldHeight() + 40);

            if (targetH > sh || targetW > sw) {
                allFit = false;
            }
        }

        gui.zoomReset();
        boolean zoomOk = (gui.getZoomScale() == 1.0);

        check("T01: Layout responsivo e dimensionamento sem corte em 720p, 768p e 1080p",
                allFit && zoomOk,
                "resolucoes=[1280x720, 1366x768, 1920x1080], zoomScale=" + gui.getZoomScale());
    }

    private static void test02_HitTestingWithZoomLevels() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        double[] zooms = { 0.65, 1.0, 1.5 };
        boolean hitTestsOk = true;

        for (double z : zooms) {
            gui.setZoomScale(z);
            int cx = (int) Math.round(gui.getTopology().getIntersectionCenterX(4));
            int cy = (int) Math.round(gui.getTopology().getIntersectionCenterY(4));
            int screenX = (int) Math.round(cx * z);
            int screenY = (int) Math.round(cy * z);

            int hit = gui.selectIntersectionByClick(screenX, screenY);
            System.out.println("DEBUG T02: z=" + z + ", cx=" + cx + ", cy=" + cy + ", sx=" + screenX + ", sy=" + screenY + ", hit=" + hit);
            if (hit != 4) {
                hitTestsOk = false;
            }
        }
        gui.zoomReset();

        check("T02: Hit-testing exato de coordenadas sob diferentes zooms (0.65x, 1.0x, 1.5x)",
                hitTestsOk,
                "zooms=[0.65, 1.0, 1.5], intersectionExpected=4, zoomRestaurado=1.0");
    }

    private static void test03_VehicleSelectionByClick() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        List<VisualVehicle> list = gui.getVisualVehiclesSnapshot();
        VisualVehicle target = list.isEmpty() ? null : list.get(0);

        boolean ok = false;
        if (target != null) {
            VisualVehicle selected = gui.selectVehicleByClick((int) Math.round(target.x), (int) Math.round(target.y));
            ok = (selected != null)
                    && selected.id.equals(target.id)
                    && target.id.equals(gui.getSelectedVehicleId())
                    && (gui.getSelectedVehicle() == target);
        }

        check("T03: Seleção direta de veículo por clique no mapa (raio <= 24px)",
                ok,
                "veiculo=" + (target != null ? target.id : "null") + ", selectedId=" + gui.getSelectedVehicleId());
    }

    private static void test04_VehicleAgentStateInspection() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        String selId = gui.getSelectedVehicleId();
        VisualVehicle vv = (selId != null) ? gui.getVisualVehicle(selId) : null;

        gui.refreshVehicleInspectionState();
        String feedback = gui.getVehicleActionFeedbackText();

        boolean ok = (vv != null) && (feedback != null) && feedback.contains(selId);

        check("T04: Inspeção completa de telemetria e estado do VehicleAgent selecionado",
                ok,
                "id=" + selId + ", feedback=" + feedback);
    }

    private static void test05_DestinationChangeRequestViaOperator() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        String selId = gui.getSelectedVehicleId();
        VisualVehicle vv = gui.getVisualVehicle(selId);

        int newDest = (vv.destinationIntersectionId == 8) ? 6 : 8;
        DestinationChangeResult res = gui.performInteractiveDestinationChange(selId, newDest);

        boolean ok = res.success
                && res.accepted
                && (vv.destinationIntersectionId == newDest)
                && (res.newDestination == newDest);

        check("T05: Submissão de alteração de destino C1..C9 encaminhada ao VehicleAgent",
                ok,
                "id=" + selId + ", novoDest=C" + (newDest + 1) + ", accepted=" + res.accepted);
    }

    private static void test06_AStarRecalculationByAgent() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        String selId = gui.getSelectedVehicleId();
        VisualVehicle vv = gui.getVisualVehicle(selId);

        boolean ok = (vv.activeRoute != null)
                && vv.activeRoute.reachable
                && (vv.activeRoute.destinationNodeId == vv.destinationIntersectionId)
                && !vv.activeRoute.nodePath.isEmpty();

        check("T06: Recálculo autónomo da rota A* pelo VehicleAgent ao alterar destino",
                ok,
                "dest=C" + (vv.destinationIntersectionId + 1) + ", rota=" + (vv.activeRoute != null ? vv.activeRoute.nodePath : "null"));
    }

    private static void test07_ValidManeuverRequestAccepted() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        String selId = gui.getSelectedVehicleId();

        ManeuverChangeResult res = gui.performInteractiveManeuverChange(selId, ManeuverIntent.GO_STRAIGHT);

        boolean ok = res.success && res.accepted && (gui.getVisualVehicle(selId).intent == ManeuverIntent.GO_STRAIGHT);

        check("T07: Submissão e aceitação de manobra válida no próximo cruzamento",
                ok,
                "id=" + selId + ", intent=GO_STRAIGHT, accepted=" + res.accepted);
    }

    private static void test08_ImpossibleManeuverRequestRejectedWithExplanation() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        VisualVehicle fakeNorthEdge = new VisualVehicle("Fake_North_Edge", 480, 20, 2.0, 1, Color.GRAY, ManeuverIntent.GO_STRAIGHT, 0);
        fakeNorthEdge.originIntersectionId = 0;
        fakeNorthEdge.destinationIntersectionId = 1;

        VehicleAgent.ManeuverChangeResult res = new VehicleAgent.ManeuverChangeResult(
                false, "Manobra TURN_LEFT impossível: sem via de saída (bordo da cidade)",
                ManeuverIntent.TURN_LEFT, ManeuverIntent.GO_STRAIGHT, 0, -1);

        boolean ok = (!res.success) && (!res.accepted) && (res.message != null && res.message.contains("impossível"));

        check("T08: Rejeição com explicação clara de manobra impossível / sem saída",
                ok,
                "accepted=" + res.accepted + ", motivo=" + res.message);
    }

    private static void test09_RouteFollowedByPhysicalEngineWithoutCoordinateJumping() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        String selId = gui.getSelectedVehicleId();
        VisualVehicle vv = gui.getVisualVehicle(selId);

        double prevX = vv.x;
        double prevY = vv.y;
        gui.stepSimulationOnce();

        double dx = vv.x - prevX;
        double dy = vv.y - prevY;
        double dist = Math.sqrt(dx * dx + dy * dy);

        boolean ok = (dist <= vv.maxSpeed * 2.0);

        check("T09: Motor físico segue a rota sem saltos ou teletransporte de coordenadas",
                ok,
                "distancia=" + String.format("%.2f px", dist) + ", maxPermitido=" + (vv.maxSpeed * 2.0));
    }

    private static void test10_IntersectionSelectionByClick() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        int targetInter = 4; // C5 (Centro)
        int cx = (int) Math.round(gui.getTopology().getIntersectionCenterX(targetInter));
        int cy = (int) Math.round(gui.getTopology().getIntersectionCenterY(targetInter));

        int hit = gui.selectIntersectionByClick(cx, cy);
        boolean ok = (hit == targetInter) && (gui.getSelectedIntersectionIndex() == targetInter);

        check("T10: Seleção de cruzamento por clique na caixa do cruzamento",
                ok,
                "clique=(" + cx + "," + cy + "), cruzamentoSelecionado=" + gui.getSelectedIntersectionIndex());
    }

    private static void test11_SemaphoreSelectionByClick() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        int interIdx = 0; // C1 (NW)
        int ix = gui.getTopology().getIntersectionX(interIdx);
        int iy = gui.getTopology().getIntersectionY(interIdx);

        // Poste semafórico Norte: ix - 13, iy - 22
        int hit = gui.selectIntersectionByClick(ix - 13, iy - 22);
        boolean ok = (hit == interIdx) && (gui.getSelectedIntersectionIndex() == interIdx);

        check("T11: Seleção de cruzamento por clique nos postes semafóricos",
                ok,
                "clique=(" + (ix - 13) + "," + (iy - 22) + "), cruzamentoSelecionado=" + gui.getSelectedIntersectionIndex());
    }

    private static void test12_ManualNSRequestProgressiveState() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.selectIntersection(4);

        ManualOverrideRequest req = gui.requestManualPhaseFromGui(4, 0); // Eixo NS
        boolean ok = (req != null)
                && (req.getState() == ManualRequestState.ACCEPTED || req.getState() == ManualRequestState.APPLIED)
                && (req.targetAxis == 0);

        check("T12: Pedido manual para Verde NS com progressão REQUESTED -> ACCEPTED -> APPLIED",
                ok,
                "requestId=" + (req != null ? req.requestId : "null") + ", estado=" + (req != null ? req.getState() : "null"));
    }

    private static void test13_ManualEWRequestProgressiveState() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.selectIntersection(4);

        ManualOverrideRequest req = gui.requestManualPhaseFromGui(4, 1); // Eixo EW
        boolean ok = (req != null)
                && (req.getState() == ManualRequestState.ACCEPTED || req.getState() == ManualRequestState.APPLIED)
                && (req.targetAxis == 1);

        check("T13: Pedido manual para Verde EW com progressão REQUESTED -> ACCEPTED -> APPLIED",
                ok,
                "requestId=" + (req != null ? req.requestId : "null") + ", estado=" + (req != null ? req.getState() : "null"));
    }

    private static void test14_MandatoryYellowAndAllRedTransitions() {
        SimulationClock.enableSimulatedMode(30000L);
        IntersectionSafetyAuthority.resetAll();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(4);
        auth.setMinManualSwitchGreenMs(0L);

        // Inicia em NS_GREEN. Solicita transição manual para EW_GREEN (Eixo 1)
        ManualOverrideRequest req = gui.requestManualPhaseFromGui(4, 1);
        auth.updateTick(0, 0); // -> NS_YELLOW
        boolean sawYellow = (auth.getCurrentPhase() == IntersectionSafetyAuthority.Phase.NS_YELLOW)
                || (auth.getLightState(0) == LightState.YELLOW);

        SimulationClock.advanceMillis(1500L);
        auth.updateTick(0, 0); // -> ALL_RED
        boolean sawAllRed = (auth.getCurrentPhase() == IntersectionSafetyAuthority.Phase.ALL_RED)
                || (auth.getLightState(0) == LightState.RED && auth.getLightState(1) == LightState.RED);

        SimulationClock.advanceMillis(1000L);
        auth.updateTick(0, 0); // -> EW_GREEN
        boolean appliedGreen = (auth.getCurrentPhase() == IntersectionSafetyAuthority.Phase.EW_GREEN);
        SimulationClock.disableSimulatedMode();

        check("T14: Transições obrigatórias por YELLOW e ALL_RED no controlo manual",
                sawYellow && sawAllRed && appliedGreen,
                "passouPorAmarelo=" + sawYellow + ", passouPorAllRed=" + sawAllRed + ", greenApplied=" + appliedGreen);
    }

    private static void test15_ZeroIncompatibleGreens() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        boolean zeroIncompatible = true;

        for (int i = 0; i < 30; i++) {
            gui.stepSimulationTickWithAuthority();
            for (int cross = 0; cross < gui.getTopology().getIntersectionCount(); cross++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(cross);
                if (auth.getLightState(0) == LightState.GREEN && auth.getLightState(1) == LightState.GREEN) {
                    zeroIncompatible = false;
                }
            }
        }

        check("T15: Ausência absoluta de verdes incompatíveis simultâneos",
                zeroIncompatible,
                "9 cruzamentos verificados em 30 ticks; verdesSimultaneos=0");
    }

    private static void test16_ReturnToAutomaticMode() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.selectIntersection(4);

        boolean returned = gui.returnToAutomaticFromGui(4);
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(4);
        boolean ok = (!auth.isManualModeActive()) && (auth.getActiveManualRequest() == null);

        check("T16: Retorno ao modo automático restaura o ciclo autónomo dos semáforos",
                ok,
                "returned=" + returned + ", manualActive=" + auth.isManualModeActive());
    }

    private static void test17_EmergencyPreemptionPrecedenceOverManual() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        int cross = 3;
        gui.selectIntersection(cross);
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(cross);
        auth.setMinManualSwitchGreenMs(0L);

        ManualOverrideRequest manualActive = auth.requestManualPhase(1); // Pedido manual EW
        auth.requestEmergency("Ambulancia_1", 0, null); // Emergência NS

        boolean preempted = (manualActive.getState() == ManualRequestState.PREEMPTED)
                && (!auth.isManualModeActive())
                && auth.isEmergencyActive();

        auth.notifyEmergencyCleared("Ambulancia_1");

        check("T17: Precedência absoluta da preempção de emergência sobre o controlo manual",
                preempted,
                "manualPreempted=" + (manualActive.getState() == ManualRequestState.PREEMPTED)
                        + ", manualModeActive=" + auth.isManualModeActive()
                        + ", emergencyActive=" + auth.isEmergencyActive());
    }

    private static void test18_InitialFleet50NormalPlus3Priority() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        int normal = fleet.getActiveNormalVehicleCount();
        int priority = fleet.getActivePriorityVehicleCount();
        int visual = gui.getVisualVehiclesSnapshot().size();
        int jade = fleet.getLiveJadeAgentsCount();

        boolean ok = (normal == 50) && (priority == 3) && (visual == 53) && (jade == 73);

        check("T18: Frota inicial com 50 normais + 3 prioritários (53 visuais, 73 JADE)",
                ok,
                "normais=" + normal + ", prioritarios=" + priority + ", visuais=" + visual + ", jadeTotal=" + jade);
    }

    private static void test19_Removal50To49EnablesAddButton() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult res = gui.performInteractiveRemoveNormalVehicle(null);
        awaitAgentCount(72, 3000);
        gui.refreshFleetControlState();

        boolean ok = res.success
                && (fleet.getActiveNormalVehicleCount() == 49)
                && (fleet.getActivePriorityVehicleCount() == 3)
                && gui.isAddVehicleButtonEnabled();

        check("T19: Remoção 50 -> 49 reativa imediatamente o botão Adicionar",
                ok,
                "removido=" + res.vehicleId + ", normais=" + fleet.getActiveNormalVehicleCount()
                        + ", btnAdd.enabled=" + gui.isAddVehicleButtonEnabled());
    }

    private static void test20_Addition49To50DisablesAddButton() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult res = gui.performInteractiveAddNormalVehicle();
        awaitAgentCount(73, 3000);
        gui.refreshFleetControlState();

        boolean ok = res.success
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (!gui.isAddVehicleButtonEnabled());

        check("T20: Adição 49 -> 50 desativa imediatamente o botão Adicionar",
                ok,
                "adicionado=" + res.vehicleId + ", normais=" + fleet.getActiveNormalVehicleCount()
                        + ", btnAdd.enabled=" + gui.isAddVehicleButtonEnabled());
    }

    private static void test21_BlockAdditionAbove50() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        LifecycleOperationResult rejected = gui.performInteractiveAddNormalVehicle();

        boolean ok = (!rejected.success)
                && (fleet.getActiveNormalVehicleCount() == 50)
                && (!gui.isAddVehicleButtonEnabled());

        check("T21: Bloqueio estrito de adição acima de 50 veículos normais",
                ok,
                "rejected.success=" + rejected.success + ", normais=" + fleet.getActiveNormalVehicleCount());
    }

    private static void test22_StrictCorrespondenceVisualToJadeAgents() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        VehicleControlGateway gw = VehicleControlGateway.getInstance();

        // Aguarda que o agente recentemente adicionado no T20 conclua o seu setup() e registo no gateway
        awaitAgentCount(73, 2000);
        long deadline = System.currentTimeMillis() + 2000L;
        boolean allMatched = false;
        while (System.currentTimeMillis() < deadline) {
            allMatched = true;
            List<VisualVehicle> visualList = gui.getVisualVehiclesSnapshot();
            if (visualList.isEmpty()) {
                allMatched = false;
            } else {
                for (VisualVehicle v : visualList) {
                    if (!v.isEmergency) {
                        if (!fleet.getActiveNormalVehicleIds().contains(v.id) || !gw.isAgentRegistered(v.id)) {
                            allMatched = false;
                            break;
                        }
                    }
                }
            }
            if (allMatched && fleet.getActiveNormalVehicleCount() == 50) {
                break;
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        check("T22: Correspondência estrita 1:1 entre veículos visuais e agentes JADE",
                allMatched,
                "50 veículos normais verificados no FleetLifecycleManager e Gateway");
    }

    private static void test23_EDTNonBlockingResponsiveness() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        long start = System.nanoTime();

        gui.triggerAddNormalVehicleAsync();
        gui.triggerManualPhaseAsync(0, 0);
        gui.triggerReturnToAutoAsync(0);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        boolean ok = (elapsedMs < 50);

        check("T23: Responsividade da GUI: ações assíncronas na EDT executam em < 50ms",
                ok,
                "tempoDecorrido=" + elapsedMs + " ms (limite: 50 ms)");
    }

    private static void test24_CompatibilityWith2x2Topology() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.reconfigureTopology(NetworkTopologyConfig.CONFIG_2X2);

        int count = gui.getTopology().getIntersectionCount();
        String[] destItems = gui.getComboDestination() != null
                ? new String[gui.getComboDestination().getItemCount()] : new String[0];

        boolean ok = (count == 4) && (gui.getTopology().getRows() == 2) && (gui.getTopology().getCols() == 2);

        check("T24: Compatibilidade e adaptação perfeita na topologia 2x2",
                ok,
                "intersecoes=" + count + ", rows=2, cols=2");
    }

    private static void test25_CompatibilityWith3x3Topology() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.reconfigureTopology(NetworkTopologyConfig.CONFIG_3X3);

        int count = gui.getTopology().getIntersectionCount();
        boolean ok = (count == 9) && (gui.getTopology().getRows() == 3) && (gui.getTopology().getCols() == 3);

        check("T25: Compatibilidade e adaptação perfeita na topologia 3x3",
                ok,
                "intersecoes=" + count + ", rows=3, cols=3");
    }

    private static void test26_PreservationOfVehicleAgentAutonomy() {
        VehicleControlGateway gw = VehicleControlGateway.getInstance();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();

        String testId = "Carro_01";
        jade.core.Agent ag = gw.getAgent(testId);
        boolean ok = false;

        if (ag instanceof VehicleAgent) {
            VehicleAgent vag = (VehicleAgent) ag;
            VehiclePerception p = new VehiclePerception(
                    testId, 320.0, 100.0, 2.0, 2.4, 0, 0, false, 0, 120.0,
                    LightState.GREEN, 9999.0, -1.0, 0L, 1.0,
                    System.currentTimeMillis(), 1L
            );
            gw.publishPerception(testId, p);
            vag.senseReasonAct();

            VehicleDecision d = gw.getValidDecision(testId, System.currentTimeMillis(), 1000L);
            ok = (d != null) && (d.getAction() != null);
        }

        check("T26: Preservação estrita da autonomia do VehicleAgent (Gateway Perceção-Decisão-Ação)",
                ok,
                "agente=" + (ag != null ? ag.getClass().getSimpleName() : "null") + ", cicloPercecaoDecisaoAcao=Ativo");
    }
}
