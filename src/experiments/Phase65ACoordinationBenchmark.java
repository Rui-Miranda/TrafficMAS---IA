package experiments;

import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.CorridorDefinition;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ControlMode;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.IntersectionSafetyAuthority.Phase;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Phase65ACoordinationBenchmark
 * 
 * Executa comparação experimental controlada entre os três modos operacionais:
 * - Modo A: FIXED_TIME (tempos fixos de verde, sem coordenação)
 * - Modo B: LOCAL_ADAPTIVE (adaptação local por fila, sem coordenação central)
 * - Modo C: MULTIAGENT_COORDINATED (Onda Verde Coordenada com agendamento físico de offsets)
 * 
 * Avalia nos corredores canónicos de 3 cruzamentos da malha 3x3:
 * - Corredor Horizontal H1: C1_NW -> C2_N -> C3_NE (Eixo EW, 620 px total)
 * - Corredor Vertical V2: C2_N -> C5_C -> C8_S (Eixo NS, 620 px total)
 * 
 * Sob duração de 30 segundos simulados (300 ticks a 100ms) com a mesma semente aleatória.
 * Exporta resultados para: results/experiments/fase65a_comparativo_modos.csv
 */
public class Phase65ACoordinationBenchmark {

    public static class BenchmarkResult {
        public final String mode;
        public final String corridorId;
        public final int axis;
        public final double distancePx;
        public final String appliedOffsets;
        public final double meanDeviationMs;
        public final int vehiclesCompleted;
        public final double avgTravelTimeSec;
        public final double avgWaitingTimeSec;
        public final double avgStops;
        public final double throughputVehiclesPerHour;

        public BenchmarkResult(String mode, String corridorId, int axis, double distancePx,
                               String appliedOffsets, double meanDeviationMs, int vehiclesCompleted,
                               double avgTravelTimeSec, double avgWaitingTimeSec, double avgStops,
                               double throughputVehiclesPerHour) {
            this.mode = mode;
            this.corridorId = corridorId;
            this.axis = axis;
            this.distancePx = distancePx;
            this.appliedOffsets = appliedOffsets;
            this.meanDeviationMs = meanDeviationMs;
            this.vehiclesCompleted = vehiclesCompleted;
            this.avgTravelTimeSec = avgTravelTimeSec;
            this.avgWaitingTimeSec = avgWaitingTimeSec;
            this.avgStops = avgStops;
            this.throughputVehiclesPerHour = throughputVehiclesPerHour;
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - BENCHMARK EXPERIMENTAL DE ONDAS VERDES 3X3 (FASE 6.5-A)");
        System.out.println("================================================================================");

        File dir = new File("results/experiments");
        if (!dir.exists()) dir.mkdirs();
        File csvFile = new File(dir, "fase65a_comparativo_modos.csv");

        NetworkTopologyConfig topo = NetworkTopologyConfig.CONFIG_3X3;
        NetworkTopologyConfig.setActive(topo);

        CorridorDefinition h1 = CorridorDefinition.findCorridorById("H1", topo);
        CorridorDefinition v2 = CorridorDefinition.findCorridorById("V2", topo);

        ControlMode[] modes = {
            ControlMode.FIXED_TIME,
            ControlMode.LOCAL_ADAPTIVE,
            ControlMode.MULTIAGENT_COORDINATED
        };

        List<BenchmarkResult> results = new ArrayList<>();

        for (ControlMode mode : modes) {
            results.add(runCorridorSimulation(h1, mode, topo, 42L));
            results.add(runCorridorSimulation(v2, mode, topo, 42L));
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
            writer.println("ControlMode,Corridor,Axis,DistancePx,OffsetsMs,MeanDeviationMs,VehiclesCompleted,"
                    + "AvgTravelTimeSec,AvgWaitingTimeSec,AvgStops,ThroughputVehiclesPerHour");

            for (BenchmarkResult r : results) {
                writer.printf(Locale.US, "%s,%s,%s,%.1f,\"%s\",%.1f,%d,%.2f,%.2f,%.2f,%.1f%n",
                        r.mode, r.corridorId, (r.axis == 0 ? "NS" : "EW"), r.distancePx,
                        r.appliedOffsets, r.meanDeviationMs, r.vehiclesCompleted,
                        r.avgTravelTimeSec, r.avgWaitingTimeSec, r.avgStops,
                        r.throughputVehiclesPerHour);

                System.out.printf(Locale.US, "  • [%s | %s]: Passou=%d, TempoMedio=%.2fs, Espera=%.2fs, Paragens=%.2f, Throughput=%.1f veh/h%n",
                        r.mode, r.corridorId, r.vehiclesCompleted, r.avgTravelTimeSec, r.avgWaitingTimeSec, r.avgStops, r.throughputVehiclesPerHour);
            }
            System.out.println("\n[SUCESSO] Resultados do benchmark exportados para: " + csvFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar benchmark: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static BenchmarkResult runCorridorSimulation(CorridorDefinition corridor, ControlMode mode,
                                                        NetworkTopologyConfig topo, long seed) {
        IntersectionSafetyAuthority.resetAll();
        SimulationClock.reset();
        SimulationClock.enableSimulatedMode(100000L);

        List<Integer> nodes = corridor.getIntersectionIndices();
        int axis = corridor.getAxis();
        double d0 = corridor.getSegmentDistancePx(0, topo); // 310 px
        double d1 = corridor.getSegmentDistancePx(1, topo); // 310 px
        double totalDist = d0 + d1;

        double progSpeed = CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, 1.0); // 20 px/s
        long off0 = 0L;
        long off1 = CorridorDefinition.calculateTravelTimeOffsetMs(d0, progSpeed); // 15500 ms
        long off2 = off1 + CorridorDefinition.calculateTravelTimeOffsetMs(d1, progSpeed); // 31000 ms

        String offsetsStr = String.format("%d,%d,%d", off0, off1, off2);

        // Configura autoridades
        for (int i = 0; i < topo.getIntersectionCount(); i++) {
            IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
            auth.setControlMode(mode);
            auth.setFixedGreenDuration(4000L);
        }

        long t0 = SimulationClock.currentTimeMillis();

        // Se for Modo C (Coordenado), emite as diretivas de onda verde com agendamento
        if (mode == ControlMode.MULTIAGENT_COORDINATED) {
            IntersectionSafetyAuthority a0 = IntersectionSafetyAuthority.getAuthority(nodes.get(0));
            IntersectionSafetyAuthority a1 = IntersectionSafetyAuthority.getAuthority(nodes.get(1));
            IntersectionSafetyAuthority a2 = IntersectionSafetyAuthority.getAuthority(nodes.get(2));

            CoordinationPolicy p0 = new CoordinationPolicy("CMD_BENCH_0", "GREEN_WAVE", axis, 6500L, off0, t0, corridor.getId(), t0 + 40000L);
            CoordinationPolicy p1 = new CoordinationPolicy("CMD_BENCH_1", "GREEN_WAVE", axis, 6500L, off1, t0, corridor.getId(), t0 + 40000L);
            CoordinationPolicy p2 = new CoordinationPolicy("CMD_BENCH_2", "GREEN_WAVE", axis, 6500L, off2, t0, corridor.getId(), t0 + 40000L);

            a0.evaluateAndRegisterCoordinationPolicy(p0);
            a1.evaluateAndRegisterCoordinationPolicy(p1);
            a2.evaluateAndRegisterCoordinationPolicy(p2);
        }

        // Simulação de tráfego de pelotão ao longo do corredor
        Random rng = new Random(seed);
        int platoonSize = 10;
        double[] vehiclePos = new double[platoonSize];
        double[] vehicleSpeed = new double[platoonSize];
        double[] vehicleWaitTime = new double[platoonSize];
        int[] vehicleStops = new int[platoonSize];
        boolean[] vehicleDone = new boolean[platoonSize];
        double[] vehicleDepartureTime = new double[platoonSize];
        double[] vehicleArrivalTime = new double[platoonSize];

        for (int v = 0; v < platoonSize; v++) {
            vehiclePos[v] = - (v * 40.0); // Espaçamento de 40 px entre veículos no início do corredor
            vehicleSpeed[v] = 2.0;
            vehicleWaitTime[v] = 0.0;
            vehicleStops[v] = 0;
            vehicleDone[v] = false;
            vehicleDepartureTime[v] = 0.0;
        }

        double stopLineNode0 = 50.0;
        double stopLineNode1 = stopLineNode0 + d0;
        double stopLineNode2 = stopLineNode1 + d1;

        // Loop de simulação discreta: 600 ticks a 100ms = 60 segundos
        int totalTicks = 600;
        for (int tick = 0; tick < totalTicks; tick++) {
            SimulationClock.advanceMillis(100);
            double currentSimSec = (tick + 1) * 0.1;

            // Atualiza autoridades dos 3 nós do corredor
            for (int nIdx : nodes) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(nIdx);
                int waitNS = (axis == 0) ? 2 : 1;
                int waitEW = (axis == 1) ? 2 : 1;
                auth.updateTick(waitNS, waitEW);
            }

            // Atualiza veículos do pelotão
            for (int v = 0; v < platoonSize; v++) {
                if (vehicleDone[v]) continue;

                double pos = vehiclePos[v];
                LightState lightAtAhead = LightState.GREEN;
                double nextStopLine = -1.0;

                if (pos < stopLineNode0) {
                    nextStopLine = stopLineNode0;
                    lightAtAhead = IntersectionSafetyAuthority.getAuthority(nodes.get(0)).getLightState(axis);
                } else if (pos < stopLineNode1) {
                    nextStopLine = stopLineNode1;
                    lightAtAhead = IntersectionSafetyAuthority.getAuthority(nodes.get(1)).getLightState(axis);
                } else if (pos < stopLineNode2) {
                    nextStopLine = stopLineNode2;
                    lightAtAhead = IntersectionSafetyAuthority.getAuthority(nodes.get(2)).getLightState(axis);
                }

                boolean mustStop = (nextStopLine > 0 && Math.abs(nextStopLine - pos) < 25.0 && lightAtAhead != LightState.GREEN);

                if (mustStop) {
                    vehicleWaitTime[v] += 0.1;
                    if (vehicleSpeed[v] > 0.1) {
                        vehicleStops[v]++;
                        vehicleSpeed[v] = 0.0;
                    }
                } else {
                    vehicleSpeed[v] = 2.0;
                    vehiclePos[v] += vehicleSpeed[v];
                }

                if (vehiclePos[v] >= (stopLineNode2 + 60.0)) {
                    vehicleDone[v] = true;
                    vehicleArrivalTime[v] = currentSimSec;
                }
            }
        }

        // Compilação estatística
        int completedCount = 0;
        double totalTravelTime = 0.0;
        double totalWait = 0.0;
        int totalStops = 0;

        for (int v = 0; v < platoonSize; v++) {
            if (vehicleDone[v]) {
                completedCount++;
                totalTravelTime += (vehicleArrivalTime[v] - vehicleDepartureTime[v]);
                totalWait += vehicleWaitTime[v];
                totalStops += vehicleStops[v];
            }
        }

        double avgTravelTime = (completedCount > 0) ? (totalTravelTime / completedCount) : 60.0;
        double avgWait = (completedCount > 0) ? (totalWait / completedCount) : 20.0;
        double avgStops = (completedCount > 0) ? ((double) totalStops / completedCount) : 3.0;
        double throughput = (completedCount / 60.0) * 3600.0;

        double meanDev = 0.0;
        if (mode == ControlMode.MULTIAGENT_COORDINATED) {
            for (int nIdx : nodes) {
                CoordinationPolicy p = IntersectionSafetyAuthority.getAuthority(nIdx).getLastAppliedPolicy();
                if (p != null) {
                    meanDev += Math.abs(p.getTemporalDeviationMs());
                }
            }
            meanDev /= nodes.size();
        }

        return new BenchmarkResult(
                mode.name(), corridor.getId(), axis, totalDist, offsetsStr, meanDev,
                completedCount, avgTravelTime, avgWait, avgStops, throughput);
    }
}
