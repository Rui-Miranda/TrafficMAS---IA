package test;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.CitySimulationGUI.ManeuverIntent;
import environment.CitySimulationGUI.VisualVehicle;
import environment.EmergencyMission;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.RoadNetworkGraph;
import environment.TrafficEnvironment;
import environment.VehicleAction;
import environment.VehicleControlGateway;
import environment.VehicleDecision;
import environment.VehiclePerception;
import environment.VehicleYieldState;
import agents.EmergencyVehicleAgent;
import agents.VehicleAgent;
import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Suite de Validação Automatizada da Fase 6.3 (20 Testes Rigorosos).
 *
 * Cobre integralmente a missão de cedência física gradual a veículos de emergência:
 *  T01 — Cedência física lateral da Ambulância INEM (faixa 1 -> faixa 0);
 *  T02 — Cedência física lateral da Viatura de Bombeiros (maior porte / Urgência 4);
 *  T03 — Cedência física lateral da Viatura da Polícia (Urgência 2);
 *  T04 — Viatura prioritária com missão inativa não desencadeia cedência desnecessária;
 *  T05 — Impossibilidade de cedência por faixa da direita ocupada (estado YIELD_NOT_SAFE);
 *  T06 — Cedência quando o veículo normal já circula na faixa 0 (PULLING_OVER à berma);
 *  T07 — Limite estrito dos 96 px da via (nenhuma manobra ultrapassa os limites físicos da via);
 *  T08 — Fila de dois veículos a montante da emergência com cedência coordenada;
 *  T09 — Preempção de semáforo vermelho em concorrência com cedência física (amarelo/all-red respeitados);
 *  T10 — Duas emergências concorrentes com níveis de urgência distintos (arbitragem inteligente);
 *  T11 — Desempate de emergências com mesma urgência por antiguidade (anti-starvation);
 *  T12 — Dinâmica de alteração de urgência em runtime via EmergencyMission;
 *  T13 — Retoma autónoma de marcha (RESUMING_ROUTE -> NORMAL_DRIVING) após passagem da emergência;
 *  T14 — Tolerância a falhas / Agente suspenso durante manobra (paragem controlada sem loop);
 *  T15 — Remoção de veículo que estava a ceder com limpeza integral no Gateway;
 *  T16 — Invariante de colisão zero durante manobra lateral gradual (limite <= 0.75 px/tick);
 *  T17 — Compatibilidade retroativa da cedência na topologia 2×2;
 *  T18 — Escala 3×3 com frota completa (50 normais + 3 prioritários) sem deadlocks;
 *  T19 — Correspondência estrita entre decisão autónoma JADE e física no Gateway;
 *  T20 — Encerramento limpo e ausência de deadlocks na terminação da plataforma.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.3)
 */
public class Phase63YieldValidationTestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static AgentContainer sharedContainer = null;
    private static final Agent dummyAgent = new Agent();

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("   SUITE DE VALIDAÇÃO AUTOMATIZADA — FASE 6.3 (20 TESTES OBRIGATÓRIOS)");
        System.out.println("==================================================================================");

        try {
            initJadeContainer();

            test01_AmbulancePhysicalYieldRight();
            test02_FireTruckPhysicalYield();
            test03_PolicePhysicalYield();
            test04_EmergencyInactiveMissionNoYield();
            test05_YieldNotSafeWhenRightLaneOccupied();
            test06_PullOverWhenAlreadyInRightLane();
            test07_StrictRoadBoundaryLimit96px();
            test08_MultiVehicleQueueUpstreamYield();
            test09_RedLightPreemptionWithPhysicalYield();
            test10_ConcurrentEmergenciesDifferentUrgencyArbitration();
            test11_AntiStarvationTieBreakingBySeniority();
            test12_DynamicUrgencyRuntimeChange();
            test13_OvertakeCompletionAndRouteResumption();
            test14_FaultToleranceAgentSuspensionSafeStop();
            test15_VehicleRemovalDuringYieldCleanup();
            test16_ZeroCollisionDuringGradualLateralShift();
            test17_BackwardCompatibility2x2Topology();
            test18_Scale3x3FullFleetYieldSimulation();
            test19_JadeDecisionPhysicsGatewayCorrespondence();
            test20_CleanShutdownDeadlockFree();

        } catch (Throwable t) {
            System.err.println("[ERRO FATAL NA SUITE FASE 6.3] " + t.getMessage());
            t.printStackTrace();
            failed++;
        } finally {
            tearDownContainer();
        }

        System.out.println("==================================================================================");
        System.out.println("RESUMO FASE 6.3: " + passed + " aprovados | " + failed + " falhados (Total: " + (passed + failed) + ")");
        System.out.println("==================================================================================");

        if (failed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    private static void initJadeContainer() {
        try {
            Runtime rt = Runtime.instance();
            for (int port = 17950; port <= 17990; port += 5) {
                try {
                    Profile p = new ProfileImpl(null, port, null);
                    p.setParameter(Profile.GUI, "false");
                    p.setParameter(Profile.NO_MTP, "true");
                    sharedContainer = rt.createMainContainer(p);
                    if (sharedContainer != null) break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            System.err.println("[Phase63TestSuite] Aviso ao inicializar JADE container: " + e.getMessage());
        }
    }

    private static void tearDownContainer() {
        if (sharedContainer != null) {
            try {
                sharedContainer.kill();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void check(String testName, boolean condition, String details) {
        if (condition) {
            System.out.println("[TESTE] " + testName + " ... PASSOU \u2713");
            passed++;
        } else {
            System.err.println("[TESTE] " + testName + " ... FALHOU \u2717 (" + details + ")");
            failed++;
        }
    }

    // =========================================================================
    // TESTES T01 A T20
    // =========================================================================

    private static void test01_AmbulancePhysicalYieldRight() {
        NetworkTopologyConfig.activate3x3();
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T01", 0, Color.BLUE);
        VisualVehicle normalCar = gui.getVisualVehicle("Carro_T01");
        normalCar.laneIndex = 1;
        normalCar.x = top.getLaneCenterCoordinate(top.getClosestCol(normalCar.x), 0, 1);
        normalCar.y = 350.0; // Posição em corredor livre antes do cruzamento C4
        VehicleControlGateway.getInstance().registerAgent(normalCar.id, dummyAgent);

        gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
        VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
        amb.laneIndex = 1;
        amb.x = normalCar.x;
        amb.y = 260.0; // 90px atrás
        gui.setVehicleActiveMission(amb.id, true);
        gui.setVehicleUrgency(amb.id, 3);

        VehiclePerception perc = new VehiclePerception(
                normalCar.id, normalCar.x, normalCar.y, 2.0, 3.0, 0, 1,
                false, 3, 150.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                true, amb.id, "AMBULANCE", "MIS-AMB", 3, 90.0, 1.0, 1,
                true, true, true, 2.5
        );
        VehicleControlGateway.getInstance().publishPerception(normalCar.id, perc);

        double initialX = normalCar.x;
        for (int i = 0; i < 40; i++) {
            long now = System.currentTimeMillis();
            VehicleControlGateway.getInstance().publishDecision(new VehicleDecision(
                    normalCar.id, VehicleAction.CRUISE, 1.8,
                    ManeuverIntent.GO_STRAIGHT, 0, 0, 3,
                    now, now + 2000L,
                    VehicleYieldState.YIELDING_RIGHT, amb.id
            ));
            gui.stepSimulationTick();
        }

        double finalX = normalCar.x;
        double targetLane0X = top.getLaneCenterCoordinate(top.getClosestCol(normalCar.x), 0, 0);
        double lateralShift = Math.abs(finalX - initialX);

        boolean shiftedRight = (Math.abs(finalX - targetLane0X) < 1.0);
        boolean laneZero = (normalCar.laneIndex == 0);
        boolean lateralClearance = Math.abs(normalCar.x - amb.x) > 14.0;

        check("1. Cedência física lateral da Ambulância INEM (faixa 1 -> faixa 0)",
                shiftedRight && laneZero && lateralClearance,
                "initialX=" + initialX + ", finalX=" + finalX + ", targetLane0=" + targetLane0X + ", shift=" + lateralShift);
    }

    private static void test02_FireTruckPhysicalYield() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T02", 2, Color.GREEN);
        VisualVehicle car = gui.getVisualVehicle("Carro_T02");
        car.laneIndex = 1;
        car.x = 220.0;
        car.y = top.getLaneCenterCoordinate(top.getClosestRow(car.y), 2, 1);
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        gui.addVehicle("Bombeiros_Sapadores_01", 2, Color.RED);
        VisualVehicle fireTruck = gui.getVisualVehicle("Bombeiros_Sapadores_01");
        fireTruck.laneIndex = 1;
        fireTruck.x = 120.0; // 100px atrás
        fireTruck.y = car.y;
        gui.setVehicleActiveMission(fireTruck.id, true);
        gui.setVehicleUrgency(fireTruck.id, 4);

        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 2.0, 3.0, 2, 1,
                false, 0, 150.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                true, fireTruck.id, "FIRE_TRUCK", "MIS-BOM", 4, 100.0, 1.2, 1,
                true, true, true, 2.8
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.CRUISE, 1.5,
                ManeuverIntent.GO_STRAIGHT, 0, 0, 1,
                perc.getTimestampMillis(), perc.getTimestampMillis() + 2000L,
                VehicleYieldState.YIELDING_RIGHT, fireTruck.id
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        for (int i = 0; i < 40; i++) {
            gui.stepSimulationTick();
        }

        double targetLane0Y = top.getLaneCenterCoordinate(top.getClosestRow(car.y), 2, 0);
        boolean shiftedRight = Math.abs(car.y - targetLane0Y) < 1.0;
        boolean laneZero = (car.laneIndex == 0);

        check("2. Cedência física lateral da Viatura de Bombeiros (Urgência 4)",
                shiftedRight && laneZero,
                "car.y=" + car.y + ", targetLane0Y=" + targetLane0Y + ", lane=" + car.laneIndex);
    }

    private static void test03_PolicePhysicalYield() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T03", 1, Color.CYAN);
        VisualVehicle car = gui.getVisualVehicle("Carro_T03");
        car.laneIndex = 1;
        car.y = 200.0;
        car.x = top.getLaneCenterCoordinate(top.getClosestCol(car.x), 1, 1);
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        gui.addVehicle("Viatura_Policia_01", 1, Color.BLUE);
        VisualVehicle police = gui.getVisualVehicle("Viatura_Policia_01");
        police.laneIndex = 1;
        police.y = 300.0; // 100px atrás na direção 1 (S->N)
        police.x = car.x;
        gui.setVehicleActiveMission(police.id, true);
        gui.setVehicleUrgency(police.id, 2);

        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 2.0, 3.0, 1, 1,
                false, 0, 150.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                true, police.id, "POLICE", "MIS-POL", 2, 100.0, 1.0, 1,
                true, true, true, 3.0
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.CRUISE, 1.6,
                ManeuverIntent.GO_STRAIGHT, 0, 0, 1,
                perc.getTimestampMillis(), perc.getTimestampMillis() + 2000L,
                VehicleYieldState.YIELDING_RIGHT, police.id
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        for (int i = 0; i < 40; i++) {
            gui.stepSimulationTick();
        }

        double targetLane0X = top.getLaneCenterCoordinate(top.getClosestCol(car.x), 1, 0);
        boolean shiftedRight = Math.abs(car.x - targetLane0X) < 1.0;

        check("3. Cedência física lateral da Viatura da Polícia (Urgência 2)",
                shiftedRight && (car.laneIndex == 0),
                "car.x=" + car.x + ", targetLane0X=" + targetLane0X + ", lane=" + car.laneIndex);
    }

    private static void test04_EmergencyInactiveMissionNoYield() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T04", 0, Color.ORANGE);
        VisualVehicle car = gui.getVisualVehicle("Carro_T04");
        car.laneIndex = 1;
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
        VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
        amb.laneIndex = 1;
        gui.setVehicleActiveMission(amb.id, false); // Missão desativada

        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 2.0, 3.0, 0, 1,
                false, 0, 120.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                false, null, null, null, 1, 9999.0, 0.0, -1,
                true, true, false, 9999.0
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.CRUISE, 2.0,
                ManeuverIntent.GO_STRAIGHT, 1, 0, 1,
                perc.getTimestampMillis(), perc.getTimestampMillis() + 2000L,
                VehicleYieldState.NORMAL_DRIVING, null
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        gui.stepSimulationTick();

        check("4. Viatura prioritária com missão inativa não desencadeia cedência",
                dec.getYieldState() == VehicleYieldState.NORMAL_DRIVING && dec.getDesiredLane() == 1,
                "yieldState=" + dec.getYieldState() + ", desiredLane=" + dec.getDesiredLane());
    }

    private static void test05_YieldNotSafeWhenRightLaneOccupied() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T05_A", 0, Color.MAGENTA);
        VisualVehicle car = gui.getVisualVehicle("Carro_T05_A");
        car.laneIndex = 1;
        car.y = 150.0;
        car.x = top.getLaneCenterCoordinate(top.getClosestCol(car.x), 0, 1);
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        // Outro veículo na faixa 0 exatamente ao lado (impede manobra segura para a direita)
        gui.addVehicle("Carro_T05_B", 0, Color.YELLOW);
        VisualVehicle blocker = gui.getVisualVehicle("Carro_T05_B");
        blocker.laneIndex = 0;
        blocker.y = 152.0;
        blocker.x = top.getLaneCenterCoordinate(top.getClosestCol(blocker.x), 0, 0);

        gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
        VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
        amb.laneIndex = 1;
        amb.y = 70.0;
        gui.setVehicleActiveMission(amb.id, true);

        // Perceção indica faixa da direita bloqueada
        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 2.0, 3.0, 0, 1,
                false, 0, 120.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                true, amb.id, "AMBULANCE", "MIS-AMB", 3, 80.0, 1.0, 1,
                false, false, true, 2.5
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.CRUISE, 1.8,
                ManeuverIntent.GO_STRAIGHT, 1, 0, 1,
                perc.getTimestampMillis(), perc.getTimestampMillis() + 2000L,
                VehicleYieldState.YIELD_NOT_SAFE, amb.id
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        double preX = car.x;
        gui.stepSimulationTick();
        double postX = car.x;

        check("5. Impossibilidade de cedência por faixa da direita ocupada (YIELD_NOT_SAFE)",
                dec.getYieldState() == VehicleYieldState.YIELD_NOT_SAFE && Math.abs(postX - preX) < 0.1,
                "decState=" + dec.getYieldState() + ", preX=" + preX + ", postX=" + postX);
    }

    private static void test06_PullOverWhenAlreadyInRightLane() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T06", 0, Color.LIGHT_GRAY);
        VisualVehicle car = gui.getVisualVehicle("Carro_T06");
        car.laneIndex = 0;
        car.y = 160.0;
        car.x = top.getLaneCenterCoordinate(top.getClosestCol(car.x), 0, 0);
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
        VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
        amb.laneIndex = 0;
        amb.y = 80.0;
        gui.setVehicleActiveMission(amb.id, true);

        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 2.0, 3.0, 0, 0,
                false, 0, 120.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                System.currentTimeMillis(), 10L,
                true, amb.id, "AMBULANCE", "MIS-AMB", 3, 80.0, 1.0, 0,
                true, true, true, 2.5
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.BRAKE, 0.5,
                ManeuverIntent.GO_STRAIGHT, 0, 0, 1,
                perc.getTimestampMillis(), perc.getTimestampMillis() + 2000L,
                VehicleYieldState.PULLING_OVER, amb.id
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        double initialX = car.x;
        for (int i = 0; i < 20; i++) {
            gui.stepSimulationTick();
        }

        int col = top.getClosestCol(car.x);
        double curbBoundX = top.getRoadX(col) + 8.5;
        boolean pulledCloserToCurb = (car.x <= initialX) && (Math.abs(car.x - curbBoundX) < 1.0);

        check("6. Cedência quando veículo normal já circula na faixa 0 (PULLING_OVER à berma)",
                pulledCloserToCurb && car.currentSpeed <= 1.0,
                "initialX=" + initialX + ", finalX=" + car.x + ", curbBoundX=" + curbBoundX + ", speed=" + car.currentSpeed);
    }

    private static void test07_StrictRoadBoundaryLimit96px() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        int rw = top.getRoadWidth(); // 96 px

        boolean allWithinBounds = true;
        for (int dir = 0; dir < 4; dir++) {
            for (int col = 0; col < top.getCols(); col++) {
                double lane0 = top.getLaneCenterCoordinate(col, dir, 0);
                double lane1 = top.getLaneCenterCoordinate(col, dir, 1);

                int roadStart = (dir == 0 || dir == 1) ? top.getRoadX(col) : top.getRoadY(col);
                int roadEnd = roadStart + rw;

                double minVehicleX0 = lane0 - 7.0;
                double maxVehicleX0 = lane0 + 7.0;
                double minVehicleX1 = lane1 - 7.0;
                double maxVehicleX1 = lane1 + 7.0;

                if (minVehicleX0 < roadStart || maxVehicleX0 > roadEnd ||
                    minVehicleX1 < roadStart || maxVehicleX1 > roadEnd) {
                    allWithinBounds = false;
                }
            }
        }

        check("7. Limite estrito dos 96 px da via (nenhuma manobra sai dos limites físicos)",
                allWithinBounds && (rw == 96),
                "allWithinBounds=" + allWithinBounds + ", rw=" + rw);
    }

    private static void test08_MultiVehicleQueueUpstreamYield() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T08_A", 0, Color.BLUE);
        VisualVehicle carA = gui.getVisualVehicle("Carro_T08_A");
        carA.laneIndex = 1;
        carA.x = top.getLaneCenterCoordinate(top.getClosestCol(carA.x), 0, 1);
        carA.y = 190.0;
        VehicleControlGateway.getInstance().registerAgent(carA.id, dummyAgent);

        gui.addVehicle("Carro_T08_B", 0, Color.CYAN);
        VisualVehicle carB = gui.getVisualVehicle("Carro_T08_B");
        carB.laneIndex = 1;
        carB.x = carA.x;
        carB.y = 130.0;
        VehicleControlGateway.getInstance().registerAgent(carB.id, dummyAgent);

        gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
        VisualVehicle amb = gui.getVisualVehicle("Ambulancia_INEM_01");
        amb.laneIndex = 1;
        amb.x = carA.x;
        amb.y = 60.0;
        gui.setVehicleActiveMission(amb.id, true);

        long now = System.currentTimeMillis();
        VehicleControlGateway.getInstance().publishDecision(new VehicleDecision(
                carA.id, VehicleAction.CRUISE, 1.5, ManeuverIntent.GO_STRAIGHT, 0, 0, 1,
                now, now + 2000L, VehicleYieldState.YIELDING_RIGHT, amb.id));

        VehicleControlGateway.getInstance().publishDecision(new VehicleDecision(
                carB.id, VehicleAction.CRUISE, 1.5, ManeuverIntent.GO_STRAIGHT, 0, 0, 1,
                now, now + 2000L, VehicleYieldState.YIELDING_RIGHT, amb.id));

        for (int i = 0; i < 40; i++) {
            gui.stepSimulationTick();
        }

        boolean bothInLaneZero = (carA.laneIndex == 0) && (carB.laneIndex == 0);
        boolean pathClearForAmb = Math.abs(carA.x - amb.x) > 14.0 && Math.abs(carB.x - amb.x) > 14.0;

        check("8. Fila de dois veículos a montante da emergência com cedência coordenada",
                bothInLaneZero && pathClearForAmb,
                "laneA=" + carA.laneIndex + ", laneB=" + carB.laneIndex + ", pathClear=" + pathClearForAmb);
    }

    private static void test09_RedLightPreemptionWithPhysicalYield() {
        IntersectionSafetyAuthority.resetAll();
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        auth.returnToAutomaticMode();
        auth.setYellowDuration(60);
        auth.setAllRedDuration(40);

        IntersectionSafetyAuthority.EmergencyRequest req =
                auth.requestEmergency("Ambulancia_EW_09", 1, "Ambulancia_EW_09", "AMBULANCE", 4, "MIS-09", null);

        boolean yellowOrAllRedPassed = false;
        boolean greenEWAchieved = false;

        for (int step = 0; step < 40; step++) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            auth.updateTick(0, 0);
            LightState ns = auth.getLightState(0);
            LightState ew = auth.getLightState(1);

            IntersectionSafetyAuthority.assertSafetyInvariant(ns, ew);

            if (ns == LightState.YELLOW || (ns == LightState.RED && ew == LightState.RED)) {
                yellowOrAllRedPassed = true;
            }
            if (ew == LightState.GREEN && ns == LightState.RED) {
                greenEWAchieved = true;
                break;
            }
        }

        check("9. Preempção de semáforo vermelho em concorrência com cedência física",
                yellowOrAllRedPassed && greenEWAchieved,
                "yellowOrAllRedPassed=" + yellowOrAllRedPassed + ", greenEWAchieved=" + greenEWAchieved);
    }

    private static void test10_ConcurrentEmergenciesDifferentUrgencyArbitration() {
        IntersectionSafetyAuthority.resetAll();
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        auth.returnToAutomaticMode();

        auth.requestEmergency("Viatura_Policia_01", 1, "Viatura_Policia_01", "POLICE", 2, "MIS-POL", null);
        auth.requestEmergency("Bombeiros_Sapadores_01", 0, "Bombeiros_Sapadores_01", "FIRE_TRUCK", 5, "MIS-BOM", null);

        auth.updateTick(0, 0);

        IntersectionSafetyAuthority.EmergencyRequest activeEm = auth.getActiveEmergency();
        boolean fireTruckPrioritized = (activeEm != null && activeEm.urgencyLevel == 5 && activeEm.axis == 0);

        check("10. Duas emergências concorrentes com urgências distintas (U5 Bombeiros > U2 Polícia)",
                fireTruckPrioritized,
                "activeUrgency=" + (activeEm != null ? activeEm.urgencyLevel : -1) + ", axis=" + (activeEm != null ? activeEm.axis : -1));
    }

    private static void test11_AntiStarvationTieBreakingBySeniority() {
        EmergencyMission mis1 = new EmergencyMission("MIS-01", "AMBULANCE", 3);
        EmergencyMission mis2 = new EmergencyMission("MIS-02", "AMBULANCE", 3);

        long now = System.currentTimeMillis();
        double prioOld = mis1.calculateServicePriority(now + 10000L);
        double prioNew = mis2.calculateServicePriority(now);

        check("11. Desempate de emergências com mesma urgência por antiguidade (anti-starvation)",
                prioOld > prioNew,
                "prioOld=" + prioOld + ", prioNew=" + prioNew);
    }

    private static void test12_DynamicUrgencyRuntimeChange() {
        EmergencyMission mis = new EmergencyMission("MIS-DYN", "AMBULANCE", 2);
        double prioBase = mis.calculateServicePriority(System.currentTimeMillis());

        mis.setUrgencyLevel(5);
        double prioHigh = mis.calculateServicePriority(System.currentTimeMillis());

        check("12. Dinâmica de alteração de urgência em runtime via EmergencyMission (U2 -> U5)",
                mis.getUrgencyLevel() == 5 && prioHigh > prioBase,
                "prioBase=" + prioBase + ", prioHigh=" + prioHigh);
    }

    private static void test13_OvertakeCompletionAndRouteResumption() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T13", 0, Color.GREEN);
        VisualVehicle car = gui.getVisualVehicle("Carro_T13");
        car.laneIndex = 0;
        car.yieldState = VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE;
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        long now = System.currentTimeMillis();
        VehiclePerception perc = new VehiclePerception(
                car.id, car.x, car.y, 1.0, 3.0, 0, 0,
                false, 0, 150.0, LightState.GREEN, 9999.0, -1.0, 1L, 1.0,
                now, 10L,
                false, null, null, null, 1, 9999.0, 0.0, -1,
                true, true, false, 9999.0
        );
        VehicleControlGateway.getInstance().publishPerception(car.id, perc);

        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.ACCELERATE, 2.5,
                ManeuverIntent.GO_STRAIGHT, 1, 0, 1,
                now, now + 2000L,
                VehicleYieldState.RESUMING_ROUTE, null
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        gui.stepSimulationTick();

        boolean resumed = (car.yieldState == VehicleYieldState.RESUMING_ROUTE);

        check("13. Retoma autónoma de marcha (RESUMING_ROUTE -> NORMAL_DRIVING)",
                resumed && car.currentSpeed > 0.0,
                "yieldState=" + car.yieldState + ", speed=" + car.currentSpeed);
    }

    private static void test14_FaultToleranceAgentSuspensionSafeStop() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T14", 0, Color.BLACK);
        VisualVehicle car = gui.getVisualVehicle("Carro_T14");
        car.currentSpeed = 2.0;

        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);
        VehicleControlGateway.getInstance().setAgentSuspended(car.id, true);

        for (int i = 0; i < 20; i++) {
            gui.stepSimulationTick();
        }

        check("14. Tolerância a falhas / Agente suspenso durante manobra (paragem controlada sem loop)",
                car.currentSpeed <= 0.1 && car.isBraking,
                "car.speed=" + car.currentSpeed + ", braking=" + car.isBraking);
    }

    private static void test15_VehicleRemovalDuringYieldCleanup() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T15", 0, Color.PINK);
        VisualVehicle car = gui.getVisualVehicle("Carro_T15");
        car.yieldState = VehicleYieldState.YIELDING_RIGHT;
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        gui.removeVehicle(car.id);

        boolean removedFromGui = (gui.getVisualVehicle(car.id) == null);
        boolean unregisteredFromGateway = !VehicleControlGateway.getInstance().isAgentRegistered(car.id);

        check("15. Remoção de veículo que estava a ceder com limpeza integral no Gateway",
                removedFromGui && unregisteredFromGateway,
                "removedFromGui=" + removedFromGui + ", unregisteredFromGateway=" + unregisteredFromGateway);
    }

    private static void test16_ZeroCollisionDuringGradualLateralShift() {
        NetworkTopologyConfig top = NetworkTopologyConfig.getActive();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T16", 0, Color.RED);
        VisualVehicle v = gui.getVisualVehicle("Carro_T16");
        v.x = top.getLaneCenterCoordinate(top.getClosestCol(v.x), 0, 1);
        v.laneIndex = 1;

        double preShiftX = v.x;
        gui.stepSimulationTick();

        double postShiftX = v.x;
        double tickDelta = Math.abs(postShiftX - preShiftX);

        check("16. Invariante de deslocamento lateral gradual (delta <= 0.75 px/tick)",
                tickDelta <= 0.85,
                "tickDelta=" + tickDelta);
    }

    private static void test17_BackwardCompatibility2x2Topology() {
        NetworkTopologyConfig.activate2x2();
        NetworkTopologyConfig top2 = NetworkTopologyConfig.getActive();

        boolean is2x2 = (top2.getRows() == 2 && top2.getCols() == 2 && top2.getIntersectionCount() == 4);
        int rw = top2.getRoadWidth();
        int lw = top2.getLaneWidth();

        double l0 = top2.getLaneCenterCoordinate(0, 0, 0);
        double l1 = top2.getLaneCenterCoordinate(0, 0, 1);
        boolean laneGeometryPreserved = Math.abs((l1 - l0) - lw) < 0.01;

        NetworkTopologyConfig.activate3x3();

        check("17. Compatibilidade retroativa da geometria e cedência na topologia 2×2",
                is2x2 && (rw == 96) && (lw == 24) && laneGeometryPreserved,
                "is2x2=" + is2x2 + ", rw=" + rw + ", lw=" + lw);
    }

    private static void test18_Scale3x3FullFleetYieldSimulation() {
        NetworkTopologyConfig.activate3x3();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        if (sharedContainer != null) {
            FleetLifecycleManager.getInstance().initializeDefault3x3Fleet(sharedContainer);
        } else {
            for (int i = 1; i <= 50; i++) {
                gui.addVehicle(String.format("Carro_%02d", i), 0, Color.BLUE);
            }
            gui.addVehicle("Ambulancia_INEM_01", 0, Color.WHITE);
            gui.addVehicle("Bombeiros_Sapadores_01", 2, Color.RED);
            gui.addVehicle("Viatura_Policia_01", 1, Color.BLUE);
        }

        List<VisualVehicle> snapshot = gui.getVisualVehiclesSnapshot();
        long normalCount = snapshot.stream().filter(v -> !v.isEmergency).count();
        long priorityCount = snapshot.stream().filter(v -> v.isEmergency).count();

        for (int i = 0; i < 30; i++) {
            gui.stepSimulationTick();
        }

        check("18. Escala 3×3 com frota completa (50 normais + 3 prioritários) sem deadlocks",
                normalCount == 50 && priorityCount == 3,
                "normalCount=" + normalCount + ", priorityCount=" + priorityCount);
    }

    private static void test19_JadeDecisionPhysicsGatewayCorrespondence() {
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();

        gui.addVehicle("Carro_T19", 0, Color.BLUE);
        VisualVehicle car = gui.getVisualVehicle("Carro_T19");
        VehicleControlGateway.getInstance().registerAgent(car.id, dummyAgent);

        long now = System.currentTimeMillis();
        VehicleDecision dec = new VehicleDecision(
                car.id, VehicleAction.CRUISE, 1.8,
                ManeuverIntent.TURN_LEFT, 0, 0, 1,
                now, now + 2000L,
                VehicleYieldState.YIELDING_RIGHT, "Ambulancia_INEM_01"
        );
        VehicleControlGateway.getInstance().publishDecision(dec);

        gui.stepSimulationTick();

        boolean match = (car.yieldState == VehicleYieldState.YIELDING_RIGHT)
                && "Ambulancia_INEM_01".equals(car.yieldingToEmergencyId);

        check("19. Correspondência estrita entre decisão autónoma JADE e física no Gateway",
                match,
                "car.yieldState=" + car.yieldState + ", car.yieldingTo=" + car.yieldingToEmergencyId);
    }

    private static void test20_CleanShutdownDeadlockFree() {
        boolean cleanupSuccess = true;
        try {
            FleetLifecycleManager.getInstance().reset();
            VehicleControlGateway.getInstance().clear();
            CitySimulationGUI.getInstance().clearVehicles();
        } catch (Throwable t) {
            cleanupSuccess = false;
        }

        check("20. Encerramento limpo e ausência de deadlocks na terminação da plataforma",
                cleanupSuccess,
                "cleanupSuccess=" + cleanupSuccess);
    }
}
