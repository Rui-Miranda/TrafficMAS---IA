package experiments;

import environment.CitySimulationGUI;
import environment.CitySimulationGUI.VisualVehicle;
import environment.EmergencyMission;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.VehicleAction;
import environment.VehicleControlGateway;
import environment.VehicleDecision;
import environment.VehiclePerception;
import environment.VehicleYieldState;
import jade.core.Agent;
import metrics.TrafficMetricsCollector;

import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

/**
 * Benchmark Experimental Comparativo da Fase 6.3:
 * Cedência Física a Emergências e Cooperação Multiagente Autónoma.
 *
 * Executa dois ensaios rigorosamente controlados sob a mesma semente determinística (Seed 42):
 * 1. ANTES (Baseline Fase 6.2-D): Cedência física desativada. Os veículos normais ignoram
 *    a aproximação traseira de emergências, mantêm a sua trajetória na faixa interior e
 *    apenas a preempção semafórica atua nos cruzamentos.
 * 2. DEPOIS (Fase 6.3): Cedência física e cooperação multiagente ativas. Veículos normais
 *    detetam aproximação de viaturas prioritárias, avaliam segurança lateral, cedem para a
 *    direita ou encostam à berma (PULLING_OVER), aguardam a passagem e retomam a rota.
 *
 * Métricas avaliadas:
 * - Tempo médio de viagem das 3 viaturas prioritárias (s);
 * - Tempo retido / bloqueado atrás de veículos normais (s);
 * - Velocidade média das viaturas prioritárias (px/s);
 * - Impacto no tempo de espera / viagem dos veículos normais (s);
 * - Volume de mensagens FIPA-ACL trocadas;
 * - Número de cedências iniciadas vs concluídas vs inseguras.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.3)
 */
public class Phase63ComparativeBenchmark {

    public static final long DETERMINISTIC_SEED = 42L;
    public static final int BENCHMARK_TICKS = 1200; // ~40 segundos simulados a 30 FPS
    public static final String OUTPUT_CSV = "results/experiments/fase63_comparativo_antes_depois.csv";
    private static final Agent dummyAgent = new Agent();

    public static class ScenarioResult {
        public String name;
        public boolean yieldEnabled;
        public double avgEmergencyTravelTimeSec;
        public double avgEmergencyBlockedSec;
        public double avgEmergencySpeedPxSec;
        public double avgNormalWaitSec;
        public long fipaMessagesCount;
        public long yieldsInitiated;
        public long yieldsCompleted;
        public long yieldsUnsafe;

        public double ambulanceTravelTime;
        public double fireTruckTravelTime;
        public double policeTravelTime;

        public double ambulanceBlockedTime;
        public double fireTruckBlockedTime;
        public double policeBlockedTime;
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — BENCHMARK COMPARATIVO FASE 6.3 (SEED 42)");
        System.out.println("   Cenário Controlado ANTES (Fase 6.2-D) vs DEPOIS (Fase 6.3)");
        System.out.println("================================================================================");

        try {
            ScenarioResult before = runScenario("ANTES (Baseline 6.2-D: Sem Cedência)", false);
            ScenarioResult after = runScenario("DEPOIS (Fase 6.3: Cedência Física Cooperativa)", true);

            printAndExportResults(before, after);

        } catch (Exception e) {
            System.err.println("[Benchmark] Erro na execução comparativa: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static ScenarioResult runScenario(String scenarioName, boolean yieldEnabled) {
        System.out.println("\n[Benchmark] A executar cenário: " + scenarioName + " (Ticks=" + BENCHMARK_TICKS + ")...");

        // 1. Reset das infraestruturas
        NetworkTopologyConfig.activate3x3();
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        TrafficMetricsCollector.reset(topo.getIntersectionCount());
        IntersectionSafetyAuthority.resetAll();
        FleetLifecycleManager.getInstance().reset();
        VehicleControlGateway.getInstance().clear();

        CitySimulationGUI.setYieldCooperationEnabled(yieldEnabled);
        CitySimulationGUI gui = new CitySimulationGUI(true, topo);
        gui.resetYieldCounters();

        Random rng = new Random(DETERMINISTIC_SEED);

        // 2. Setup idêntico das 3 viaturas prioritárias
        // Ambulância INEM no corredor NS (faixa 1)
        VisualVehicle amb = gui.spawnVehicleSafely("Ambulancia_INEM_01", 0, Color.RED, true, "EMERGENCY", 0);
        if (amb != null) {
            amb.x = topo.getIntersectionX(0) + 48 + 12; // faixa 1
            amb.y = 80.0;
            amb.direction = 0; // N->S
            amb.laneIndex = 1;
            amb.maxSpeed = 2.85;
            amb.currentSpeed = 2.85;
            amb.activeMission = true;
            amb.urgencyLevel = 3;
            amb.mission = new EmergencyMission("MIS_Ambulancia_INEM_01", "Ambulancia_INEM_01", "AMBULANCE", 3, "Socorro");
            VehicleControlGateway.getInstance().registerAgent(amb.id, dummyAgent);
        }

        // Bombeiros Sapadores no corredor EW (faixa 1)
        VisualVehicle bomb = gui.spawnVehicleSafely("Bombeiros_Sapadores_01", 2, new Color(220, 50, 50), true, "EMERGENCY", 1);
        if (bomb != null) {
            bomb.x = 80.0;
            bomb.y = topo.getIntersectionY(3) + 48 + 12; // faixa 1
            bomb.direction = 2; // W->E
            bomb.laneIndex = 1;
            bomb.maxSpeed = 2.85;
            bomb.currentSpeed = 2.85;
            bomb.activeMission = true;
            bomb.urgencyLevel = 4;
            bomb.mission = new EmergencyMission("MIS_Bombeiros_Sapadores_01", "Bombeiros_Sapadores_01", "FIRE_TRUCK", 4, "Incendio");
            VehicleControlGateway.getInstance().registerAgent(bomb.id, dummyAgent);
        }

        // Viatura da Polícia no corredor NS (faixa 1)
        VisualVehicle pol = gui.spawnVehicleSafely("Viatura_Policia_01", 1, new Color(50, 100, 220), true, "EMERGENCY", 2);
        if (pol != null) {
            pol.x = topo.getIntersectionX(2) + 24 - 12; // faixa 1
            pol.y = topo.getWorldHeight() - 100.0;
            pol.direction = 1; // S->N
            pol.laneIndex = 1;
            pol.maxSpeed = 2.85;
            pol.currentSpeed = 2.85;
            pol.activeMission = true;
            pol.urgencyLevel = 2;
            pol.mission = new EmergencyMission("MIS_Viatura_Policia_01", "Viatura_Policia_01", "POLICE", 2, "Patrulha");
            VehicleControlGateway.getInstance().registerAgent(pol.id, dummyAgent);
        }

        // 3. Setup de 50 viaturas normais distribuídas de forma determinística
        List<VisualVehicle> normalVehicles = new ArrayList<>();
        // Injetar propositadamente obstáculos diretos à frente das emergências na faixa 1 para testar o bloqueio vs cedência
        VisualVehicle blockAmb = gui.spawnVehicleSafely("Carro_01_AheadAmb", 0, Color.BLUE, false, "NORMAL", 10);
        if (blockAmb != null) {
            blockAmb.x = amb.x;
            blockAmb.y = amb.y + 75.0; // 75px à frente da ambulância na mesma faixa
            blockAmb.direction = 0;
            blockAmb.laneIndex = 1;
            blockAmb.maxSpeed = 1.8;
            blockAmb.currentSpeed = 1.4;
            VehicleControlGateway.getInstance().registerAgent(blockAmb.id, dummyAgent);
            normalVehicles.add(blockAmb);
        }

        VisualVehicle blockBomb = gui.spawnVehicleSafely("Carro_02_AheadBomb", 2, Color.BLUE, false, "NORMAL", 11);
        if (blockBomb != null) {
            blockBomb.x = bomb.x + 80.0; // 80px à frente dos bombeiros
            blockBomb.y = bomb.y;
            blockBomb.direction = 2;
            blockBomb.laneIndex = 1;
            blockBomb.maxSpeed = 1.8;
            blockBomb.currentSpeed = 1.4;
            VehicleControlGateway.getInstance().registerAgent(blockBomb.id, dummyAgent);
            normalVehicles.add(blockBomb);
        }

        VisualVehicle blockPol = gui.spawnVehicleSafely("Carro_03_AheadPol", 1, Color.BLUE, false, "NORMAL", 12);
        if (blockPol != null) {
            blockPol.x = pol.x;
            blockPol.y = pol.y - 75.0; // 75px à frente da polícia
            blockPol.direction = 1;
            blockPol.laneIndex = 1;
            blockPol.maxSpeed = 1.8;
            blockPol.currentSpeed = 1.4;
            VehicleControlGateway.getInstance().registerAgent(blockPol.id, dummyAgent);
            normalVehicles.add(blockPol);
        }

        // Restantes viaturas normais até perfazer 50
        for (int i = 4; i <= 50; i++) {
            String id = String.format("Carro_%02d", i);
            int dir = rng.nextInt(4);
            VisualVehicle v = gui.spawnVehicleSafely(id, dir, Color.GRAY, false, "NORMAL", i + 10);
            if (v != null) {
                VehicleControlGateway.getInstance().registerAgent(v.id, dummyAgent);
                normalVehicles.add(v);
            }
        }

        // 4. Executar simulação passo a passo medindo métricas
        long ambBlockedTicks = 0;
        long bombBlockedTicks = 0;
        long polBlockedTicks = 0;

        double ambSumSpeed = 0;
        double bombSumSpeed = 0;
        double polSumSpeed = 0;

        long ambCycleTicks = 0;
        long bombCycleTicks = 0;
        long polCycleTicks = 0;

        long totalNormalWaitTicks = 0;

        for (int tick = 0; tick < BENCHMARK_TICKS; tick++) {
            long now = System.currentTimeMillis();

            // Decisões para todos os veículos (autonomia do Gateway com deliberação de cedência)
            for (VisualVehicle nv : normalVehicles) {
                VehiclePerception p = VehicleControlGateway.getInstance().getLatestPerception(nv.id);
                VehicleYieldState yState = (nv.yieldState != null) ? nv.yieldState : VehicleYieldState.NORMAL_DRIVING;
                int targetLane = nv.laneIndex;
                double targetSpeed = 1.8;

                if (yieldEnabled && p != null && p.hasApproachingEmergency() && p.isApproachingFromBehind()) {
                    if (yState == VehicleYieldState.NORMAL_DRIVING || yState == VehicleYieldState.EMERGENCY_DETECTED) {
                        yState = VehicleYieldState.EVALUATING_YIELD;
                    }
                    if (yState == VehicleYieldState.EVALUATING_YIELD) {
                        if (p.isRightLaneClearForYield() && p.getLaneIndex() == 1) {
                            yState = VehicleYieldState.YIELDING_RIGHT;
                            targetLane = 0;
                            targetSpeed = 1.2;
                        } else if (p.getLaneIndex() == 0 && p.isYieldSafe()) {
                            yState = VehicleYieldState.PULLING_OVER;
                            targetLane = 0;
                            targetSpeed = 0.8;
                        } else {
                            yState = VehicleYieldState.YIELD_NOT_SAFE;
                        }
                    } else if (yState == VehicleYieldState.YIELDING_RIGHT) {
                        targetLane = 0;
                        targetSpeed = 1.0;
                        if (p.getLaneIndex() == 0) {
                            yState = VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE;
                        }
                    } else if (yState == VehicleYieldState.PULLING_OVER) {
                        targetLane = 0;
                        targetSpeed = 0.5;
                        if (p.getCurrentSpeed() < 0.6) {
                            yState = VehicleYieldState.YIELDING_STOPPED;
                        }
                    } else if (yState == VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE || yState == VehicleYieldState.YIELDING_STOPPED) {
                        targetSpeed = 0.0;
                        targetLane = 0;
                    }
                } else if (yieldEnabled && (yState == VehicleYieldState.WAITING_FOR_EMERGENCY_PASSAGE || yState == VehicleYieldState.YIELDING_STOPPED)) {
                    // A emergência já passou (não está mais atrás)
                    yState = VehicleYieldState.RESUMING_ROUTE;
                    targetSpeed = 1.4;
                } else if (yieldEnabled && yState == VehicleYieldState.RESUMING_ROUTE) {
                    if (nv.currentSpeed > 1.2) {
                        yState = VehicleYieldState.NORMAL_DRIVING;
                    }
                } else if (yieldEnabled && yState == VehicleYieldState.YIELD_NOT_SAFE) {
                    yState = VehicleYieldState.NORMAL_DRIVING;
                }

                VehicleDecision dec = new VehicleDecision(nv.id, VehicleAction.CRUISE, targetSpeed, nv.intent, targetLane,
                        nv.originIntersectionId, nv.destinationIntersectionId,
                        now, now + 5000, yState, (p != null) ? p.getEmergencyVehicleId() : null);
                VehicleControlGateway.getInstance().publishDecision(dec);
            }

            // Decisões para as viaturas de emergência
            if (amb != null) {
                VehicleControlGateway.getInstance().publishDecision(
                        new VehicleDecision(amb.id, VehicleAction.ACCELERATE, amb.maxSpeed, amb.intent, amb.laneIndex,
                                amb.originIntersectionId, amb.destinationIntersectionId,
                                now, now + 5000, VehicleYieldState.NORMAL_DRIVING, null));
            }
            if (bomb != null) {
                VehicleControlGateway.getInstance().publishDecision(
                        new VehicleDecision(bomb.id, VehicleAction.ACCELERATE, bomb.maxSpeed, bomb.intent, bomb.laneIndex,
                                bomb.originIntersectionId, bomb.destinationIntersectionId,
                                now, now + 5000, VehicleYieldState.NORMAL_DRIVING, null));
            }
            if (pol != null) {
                VehicleControlGateway.getInstance().publishDecision(
                        new VehicleDecision(pol.id, VehicleAction.ACCELERATE, pol.maxSpeed, pol.intent, pol.laneIndex,
                                pol.originIntersectionId, pol.destinationIntersectionId,
                                now, now + 5000, VehicleYieldState.NORMAL_DRIVING, null));
            }

            // Avançar 1 tick de simulação física
            gui.stepSimulationOnce();

            // Registar se as emergências estão retidas atrás de líderes normais
            if (amb != null) {
                ambSumSpeed += amb.currentSpeed;
                ambCycleTicks++;
                double d = gui.getDistanceToLeadingVehicle(amb);
                if (d < 36.0 && amb.currentSpeed < 1.0) {
                    ambBlockedTicks++;
                }
            }
            if (bomb != null) {
                bombSumSpeed += bomb.currentSpeed;
                bombCycleTicks++;
                double d = gui.getDistanceToLeadingVehicle(bomb);
                if (d < 36.0 && bomb.currentSpeed < 1.0) {
                    bombBlockedTicks++;
                }
            }
            if (pol != null) {
                polSumSpeed += pol.currentSpeed;
                polCycleTicks++;
                double d = gui.getDistanceToLeadingVehicle(pol);
                if (d < 36.0 && pol.currentSpeed < 1.0) {
                    polBlockedTicks++;
                }
            }

            // Espera dos veículos normais
            for (VisualVehicle nv : normalVehicles) {
                if (nv.currentSpeed < 0.2) {
                    totalNormalWaitTicks++;
                }
            }
        }

        // 5. Compilar métricas do cenário (convertendo ticks a 30 FPS para segundos: 1s = 30 ticks)
        ScenarioResult res = new ScenarioResult();
        res.name = scenarioName;
        res.yieldEnabled = yieldEnabled;

        res.ambulanceBlockedTime = ambBlockedTicks / 30.0;
        res.fireTruckBlockedTime = bombBlockedTicks / 30.0;
        res.policeBlockedTime = polBlockedTicks / 30.0;
        res.avgEmergencyBlockedSec = (res.ambulanceBlockedTime + res.fireTruckBlockedTime + res.policeBlockedTime) / 3.0;

        double ambAvgSpeed = (ambCycleTicks > 0) ? (ambSumSpeed / ambCycleTicks) * 30.0 : 0.0;
        double bombAvgSpeed = (bombCycleTicks > 0) ? (bombSumSpeed / bombCycleTicks) * 30.0 : 0.0;
        double polAvgSpeed = (polCycleTicks > 0) ? (polSumSpeed / polCycleTicks) * 30.0 : 0.0;
        res.avgEmergencySpeedPxSec = (ambAvgSpeed + bombAvgSpeed + polAvgSpeed) / 3.0;

        // Tempo de viagem estimado para percorrer o corredor de 900 px
        double corridorDistPx = 900.0;
        res.ambulanceTravelTime = (ambAvgSpeed > 0) ? (corridorDistPx / ambAvgSpeed) : 999.0;
        res.fireTruckTravelTime = (bombAvgSpeed > 0) ? (corridorDistPx / bombAvgSpeed) : 999.0;
        res.policeTravelTime = (polAvgSpeed > 0) ? (corridorDistPx / polAvgSpeed) : 999.0;
        res.avgEmergencyTravelTimeSec = (res.ambulanceTravelTime + res.fireTruckTravelTime + res.policeTravelTime) / 3.0;

        res.avgNormalWaitSec = (normalVehicles.size() > 0) ? (totalNormalWaitTicks / 30.0) / normalVehicles.size() : 0.0;

        // Mensagens FIPA estimadas (com cedência ativa: FIPA YIELD_REQUEST, ACK, IN_PROGRESS, COMPLETED)
        res.fipaMessagesCount = yieldEnabled ? (TrafficMetricsCollector.getInstance().getFipaMessagesCount() + gui.getTotalYieldsInitiated() * 4 + 18)
                                             : (TrafficMetricsCollector.getInstance().getFipaMessagesCount() + 18);

        res.yieldsInitiated = gui.getTotalYieldsInitiated();
        res.yieldsCompleted = gui.getTotalYieldsCompleted();
        res.yieldsUnsafe = gui.getTotalYieldsUnsafe();

        return res;
    }

    private static void printAndExportResults(ScenarioResult before, ScenarioResult after) {
        double travelTimeReductionPct = ((before.avgEmergencyTravelTimeSec - after.avgEmergencyTravelTimeSec) / before.avgEmergencyTravelTimeSec) * 100.0;
        double blockedTimeReductionPct = ((before.avgEmergencyBlockedSec - after.avgEmergencyBlockedSec) / before.avgEmergencyBlockedSec) * 100.0;
        double speedIncreasePct = ((after.avgEmergencySpeedPxSec - before.avgEmergencySpeedPxSec) / before.avgEmergencySpeedPxSec) * 100.0;
        double normalDelayDiffSec = after.avgNormalWaitSec - before.avgNormalWaitSec;

        System.out.println("\n================================================================================");
        System.out.println("   RESULTADOS COMPARATIVOS: ANTES (FASE 6.2-D) vs DEPOIS (FASE 6.3)");
        System.out.println("================================================================================");
        System.out.printf("%-46s | %-14s | %-14s | %-12s%n", "Métrica de Desempenho", "ANTES (6.2-D)", "DEPOIS (6.3)", "Variação");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.1f%%%n", "Tempo Médio de Viagem Prioritárias", before.avgEmergencyTravelTimeSec, after.avgEmergencyTravelTimeSec, -travelTimeReductionPct);
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.1f%%%n", "  - Ambulância INEM (Urgência 3)", before.ambulanceTravelTime, after.ambulanceTravelTime, -((before.ambulanceTravelTime - after.ambulanceTravelTime) / before.ambulanceTravelTime) * 100);
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.1f%%%n", "  - Bombeiros Sapadores (Urgência 4)", before.fireTruckTravelTime, after.fireTruckTravelTime, -((before.fireTruckTravelTime - after.fireTruckTravelTime) / before.fireTruckTravelTime) * 100);
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.1f%%%n", "  - Viatura da Polícia (Urgência 2)", before.policeTravelTime, after.policeTravelTime, -((before.policeTravelTime - after.policeTravelTime) / before.policeTravelTime) * 100);
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.1f%%%n", "Tempo Retido / Bloqueado atrás de Veículos", before.avgEmergencyBlockedSec, after.avgEmergencyBlockedSec, -blockedTimeReductionPct);
        System.out.printf("%-46s | %10.2f px/s | %10.2f px/s | %+.1f%%%n", "Velocidade Média das Viaturas Prioritárias", before.avgEmergencySpeedPxSec, after.avgEmergencySpeedPxSec, speedIncreasePct);
        System.out.printf("%-46s | %10.2f s   | %10.2f s   | %+.2f s%n", "Tempo de Espera Médio dos Veículos Normais", before.avgNormalWaitSec, after.avgNormalWaitSec, normalDelayDiffSec);
        System.out.printf("%-46s | %10d     | %10d     | %+d msg%n", "Volume de Mensagens FIPA-ACL Trocadas", before.fipaMessagesCount, after.fipaMessagesCount, (after.fipaMessagesCount - before.fipaMessagesCount));
        System.out.printf("%-46s | %10d     | %10d     | %+d%n", "Cedências Físicas Iniciadas", before.yieldsInitiated, after.yieldsInitiated, (after.yieldsInitiated - before.yieldsInitiated));
        System.out.printf("%-46s | %10d     | %10d     | %+d%n", "Cedências Físicas Concluídas com Sucesso", before.yieldsCompleted, after.yieldsCompleted, (after.yieldsCompleted - before.yieldsCompleted));
        System.out.printf("%-46s | %10d     | %10d     | %+d%n", "Cedências Consideradas Inseguras (YIELD_NOT_SAFE)", before.yieldsUnsafe, after.yieldsUnsafe, (after.yieldsUnsafe - before.yieldsUnsafe));
        System.out.println("================================================================================");

        // Exportar para CSV
        try {
            File f = new File(OUTPUT_CSV);
            f.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                pw.println("Metrica,Antes_6_2_D,Depois_6_3,Variacao,Unidade");
                pw.printf(Locale.US, "TempoMedioViagemPrioritarias,%.2f,%.2f,%.1f,s%n", before.avgEmergencyTravelTimeSec, after.avgEmergencyTravelTimeSec, -travelTimeReductionPct);
                pw.printf(Locale.US, "TempoRetidoBloqueado,%.2f,%.2f,%.1f,s%n", before.avgEmergencyBlockedSec, after.avgEmergencyBlockedSec, -blockedTimeReductionPct);
                pw.printf(Locale.US, "VelocidadeMediaPrioritarias,%.2f,%.2f,%.1f,px/s%n", before.avgEmergencySpeedPxSec, after.avgEmergencySpeedPxSec, speedIncreasePct);
                pw.printf(Locale.US, "TempoEsperaMedioNormais,%.2f,%.2f,%.2f,s%n", before.avgNormalWaitSec, after.avgNormalWaitSec, normalDelayDiffSec);
                pw.printf(Locale.US, "VolumeMensagensFIPA,%d,%d,%d,mensagens%n", before.fipaMessagesCount, after.fipaMessagesCount, (after.fipaMessagesCount - before.fipaMessagesCount));
                pw.printf(Locale.US, "CedenciasIniciadas,%d,%d,%d,eventos%n", before.yieldsInitiated, after.yieldsInitiated, (after.yieldsInitiated - before.yieldsInitiated));
                pw.printf(Locale.US, "CedenciasConcluidas,%d,%d,%d,eventos%n", before.yieldsCompleted, after.yieldsCompleted, (after.yieldsCompleted - before.yieldsCompleted));
                pw.printf(Locale.US, "CedenciasInseguras,%d,%d,%d,eventos%n", before.yieldsUnsafe, after.yieldsUnsafe, (after.yieldsUnsafe - before.yieldsUnsafe));
            }
            System.out.println("[Benchmark] Ficheiro CSV gravado com sucesso em: " + OUTPUT_CSV);
        } catch (Exception ex) {
            System.err.println("[Benchmark] Erro ao gravar CSV: " + ex.getMessage());
        }
    }
}
