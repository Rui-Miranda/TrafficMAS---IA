package scratch;

import environment.CorridorDefinition;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ControlMode;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;

import java.util.List;

public class CompareZeroVsPhysicalOffsets {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   COMPARAÇÃO CONTROLADA: OFFSETS FÍSICOS REAIS vs OFFSETS NULOS (ZERO)");
        System.out.println("================================================================================");

        long[] seeds = {5001L, 5002L, 5003L, 5004L, 5005L};

        for (long seed : seeds) {
            double waitZero = runSim("ASYMMETRIC", seed, true);
            double waitPhys = runSim("ASYMMETRIC", seed, false);
            System.out.printf("Seed %d | ASYMMETRIC | Offsets=0: Wait=%.3fs | Offsets=Physical: Wait=%.3fs | Diff=%.3fs\n",
                    seed, waitZero, waitPhys, (waitPhys - waitZero));
        }
    }

    private static double runSim(String scenario, long seed, boolean zeroOffsets) {
        NetworkTopologyConfig topo = NetworkTopologyConfig.CONFIG_3X3;
        environment.HeadlessTrafficSimulator sim = new environment.HeadlessTrafficSimulator(
                scenario, seed, ControlMode.MULTIAGENT_COORDINATED, topo);

        metrics.TrafficMetricsCollector.reset(topo.getIntersectionCount());
        metrics.TrafficMetricsCollector collector = metrics.TrafficMetricsCollector.getInstance();

        CorridorDefinition h1 = CorridorDefinition.findCorridorById("H1", topo);
        CorridorDefinition v2 = CorridorDefinition.findCorridorById("V2", topo);
        CorridorDefinition[] corridors = {h1, v2};

        int corridorTurn = 0;
        double nextCoordTime = 60.0;
        double dt = 1.0 / 30.0;
        int totalSteps = (int) Math.round(600.0 / dt);

        for (int step = 0; step < totalSteps; step++) {
            sim.advanceSeconds(dt);
            double t = sim.getCurrentSimTime();
            long nowMs = SimulationClock.currentTimeMillis();

            if (t >= nextCoordTime) {
                CorridorDefinition cor = corridors[corridorTurn % corridors.length];
                corridorTurn++;
                int axis = cor.getAxis();
                List<Integer> nodes = cor.getIntersectionIndices();
                double progSpeed = CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, 1.0);

                long refTime = nowMs;
                long off0 = 0L;
                long off1 = zeroOffsets ? 0L : CorridorDefinition.calculateTravelTimeOffsetMs(
                        (long) cor.getSegmentDistancePx(0, topo), progSpeed);
                long off2 = zeroOffsets ? 0L : off1 + CorridorDefinition.calculateTravelTimeOffsetMs(
                        (long) cor.getSegmentDistancePx(1, topo), progSpeed);

                long[] offs = {off0, off1, off2};
                for (int k = 0; k < 3; k++) {
                    int nodeIdx = nodes.get(k);
                    IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(nodeIdx);
                    String cmdId = "CMD_GW_" + cor.getId() + "_" + nodeIdx + "_" + nowMs;
                    CoordinationPolicy p = new CoordinationPolicy(
                            cmdId, "GREEN_WAVE", axis, 6500L, offs[k], refTime, cor.getId(), refTime + 30000L);
                    auth.evaluateAndRegisterCoordinationPolicy(p);
                }
                nextCoordTime += 15.0;
            }
        }

        collector.markSimulationEnd();
        double avgWait = collector.getAverageWaitingTimeSeconds();
        sim.cleanup();
        return avgWait;
    }
}
