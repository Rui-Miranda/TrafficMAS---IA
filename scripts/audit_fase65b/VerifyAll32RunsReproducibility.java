package audit;

import environment.NetworkTopologyConfig;
import ml.TrafficDatasetGenerator3x3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VerifyAll32RunsReproducibility {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   FASE 7 - AUDITORIA DE REPRODUTIBILIDADE DAS 32 CORRIDAS DO DATASET 3X3");
        System.out.println("================================================================================");

        String csvPath = "data/3x3/dataset_traffic_3x3.csv";
        Map<String, List<String>> expectedLinesByRun = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                String runId = parts[0];
                expectedLinesByRun.computeIfAbsent(runId, k -> new ArrayList<>()).add(line);
            }
        }

        System.out.println("Total de corridas no CSV original: " + expectedLinesByRun.size());
        System.out.println("Total de linhas por corrida no CSV original: 531");

        long masterSeed = 42L;
        String[] scenarios = { "NORMAL", "INTENSE", "ASYMMETRIC", "RAIN" };
        NetworkTopologyConfig topo3x3 = NetworkTopologyConfig.CONFIG_3X3;

        Method execRunMethod = TrafficDatasetGenerator3x3.class.getDeclaredMethod(
                "executeSingleSimulationRun", String.class, String.class, String.class, long.class, NetworkTopologyConfig.class);
        execRunMethod.setAccessible(true);

        int runGlobalIndex = 1;
        int totalMatches = 0;
        int totalRows = 0;
        int perfectRuns = 0;

        for (String scenario : scenarios) {
            for (int r = 1; r <= 8; r++) {
                String runId = String.format("RUN_%02d_%s", runGlobalIndex, scenario);
                long seed = masterSeed + (runGlobalIndex * 1013L);
                String split = (r <= 5) ? "TRAIN" : (r <= 7 ? "VAL" : "TEST");

                long tStart = System.currentTimeMillis();
                @SuppressWarnings("unchecked")
                List<TrafficDatasetGenerator3x3.DatasetRecord3x3> records =
                        (List<TrafficDatasetGenerator3x3.DatasetRecord3x3>) execRunMethod.invoke(
                                null, runId, scenario, split, seed, topo3x3);
                long tElapsed = System.currentTimeMillis() - tStart;

                List<String> expectedLines = expectedLinesByRun.get(runId);
                if (expectedLines == null) {
                    System.err.printf("[ERRO] Corrida %s no encontrada no CSV!\n", runId);
                    continue;
                }

                int runMatches = 0;
                int n = Math.min(records.size(), expectedLines.size());
                for (int i = 0; i < n; i++) {
                    String actualLine = records.get(i).toCsvLine();
                    String expLine = expectedLines.get(i);
                    if (actualLine.equals(expLine)) {
                        runMatches++;
                    }
                }

                totalMatches += runMatches;
                totalRows += expectedLines.size();

                boolean isPerfect = (runMatches == expectedLines.size() && records.size() == expectedLines.size());
                if (isPerfect) perfectRuns++;

                System.out.printf("[%02d/32] %-20s | Split=%-5s | Seed=%-8d | Linhas: %3d/%3d | Conc: %5.1f%% | Tempo: %4d ms | %s\n",
                        runGlobalIndex, runId, split, seed, runMatches, expectedLines.size(),
                        (runMatches * 100.0 / expectedLines.size()), tElapsed, (isPerfect ? "OK (100% BIT-EXATO)" : "DIVERGENCIA"));

                runGlobalIndex++;
            }
        }

        System.out.println("================================================================================");
        System.out.printf("RESUMO FINAL: %d/%d corridas 100%% perfeitas | %d/%d linhas idnticas (%.2f%%)\n",
                perfectRuns, expectedLinesByRun.size(), totalMatches, totalRows, (totalMatches * 100.0 / totalRows));
        System.out.println("================================================================================");

        if (perfectRuns == 32 && totalMatches == totalRows) {
            System.out.println("[SUCESSO CIENTFICO] Reprodutibilidade bit a bit COMPROVADA para todas as 32 corridas!");
        } else {
            System.out.println("[ALERTA] Algumas corridas apresentaram divergncia.");
        }
    }
}

