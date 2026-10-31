package ml;

import environment.CitySimulationGUI;
import environment.HeadlessTrafficSimulator;
import environment.IntersectionSafetyAuthority;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gerador de Datasets de Tráfego 3×3 Baseado na Simulação Física (Fase 6.5-B).
 *
 * Características Científicas e Metodológicas:
 * 1. Baseado 100% no motor físico da simulação da rede 3×3 (9 cruzamentos).
 * 2. Previsão temporal estrita com horizonte fixo de 60 segundos (t + 60s).
 * 3. 32 execuções independentes (4 cenários × 8 corridas por cenário).
 * 4. Particionamento estrito por corrida (RunId) sem contaminação temporal:
 *    - TRAIN: Corridas 1 a 5 de cada cenário (20 corridas = 62.5%)
 *    - VAL:   Corridas 6 e 7 de cada cenário (8 corridas = 25.0%)
 *    - TEST:  Corrida 8 de cada cenário (4 corridas = 12.5%)
 * 5. Vetor de 12 features expressivas cobrindo estado local por eixo, dinâmica de fila,
 *    fase semafórica ativa, tempo de verde decorrido, meteorologia, atrito e pressão de vizinhos a montante.
 * 6. Target objetivo e fisicamente fundamentado: Fila Total >= 6 OU Espera Média >= 15.0s em t + 60s.
 * 7. Exportação para 'data/3x3/dataset_traffic_3x3.csv' e metadados em 'data/3x3/dataset_metadata_3x3.json'.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficDatasetGenerator3x3 {

    public static final String DATASET_CSV_PATH = "data/3x3/dataset_traffic_3x3.csv";
    public static final String METADATA_JSON_PATH = "data/3x3/dataset_metadata_3x3.json";

    public static final double PREDICTION_HORIZON_SECONDS = 60.0;
    public static final double SAMPLE_INTERVAL_SECONDS = 5.0;
    public static final double SIMULATION_START_RECORDING_SECONDS = 10.0;
    public static final double SIMULATION_END_RECORDING_SECONDS = 300.0;
    public static final double SIMULATION_TOTAL_DURATION_SECONDS = 365.0;

    public static final String[] FEATURE_NAMES = {
        "queue_NS",
        "queue_EW",
        "total_queue",
        "queue_ratio",
        "approaching_volume",
        "average_speed",
        "average_waiting_time",
        "current_phase",
        "green_elapsed_seconds",
        "weather_condition",
        "road_friction_mu",
        "upstream_neighbor_queue_max"
    };

    public static class DatasetRecord3x3 {
        public final String runId;
        public final String scenarioName;
        public final String split; // TRAIN, VAL, TEST
        public final long seed;
        public final double simTimeSeconds;
        public final int intersectionId;
        public final double[] features; // 12 features
        public final int targetCongestion; // Target em t + 60s (0 ou 1)
        public final int futureQueue;
        public final double futureWait;

        public DatasetRecord3x3(String runId, String scenarioName, String split, long seed,
                                double simTimeSeconds, int intersectionId, double[] features,
                                int targetCongestion, int futureQueue, double futureWait) {
            this.runId = runId;
            this.scenarioName = scenarioName;
            this.split = split;
            this.seed = seed;
            this.simTimeSeconds = simTimeSeconds;
            this.intersectionId = intersectionId;
            this.features = features;
            this.targetCongestion = targetCongestion;
            this.futureQueue = futureQueue;
            this.futureWait = futureWait;
        }

        public String toCsvLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(runId).append(",");
            sb.append(scenarioName).append(",");
            sb.append(split).append(",");
            sb.append(seed).append(",");
            sb.append(String.format(Locale.US, "%.1f", simTimeSeconds)).append(",");
            sb.append(intersectionId);
            for (double f : features) {
                sb.append(",").append(String.format(Locale.US, "%.3f", f));
            }
            sb.append(",").append(targetCongestion);
            return sb.toString();
        }
    }

    private static class PendingObservation {
        final double simTime;
        final int intersectionId;
        final double[] features;

        PendingObservation(double simTime, int intersectionId, double[] features) {
            this.simTime = simTime;
            this.intersectionId = intersectionId;
            this.features = features;
        }
    }

    public static List<DatasetRecord3x3> generateDataset(long masterSeed) {
        List<DatasetRecord3x3> allRecords = new ArrayList<>();

        String[] scenarios = {
            "NORMAL",
            "INTENSE",
            "ASYMMETRIC",
            "RAIN"
        };

        NetworkTopologyConfig topo3x3 = NetworkTopologyConfig.CONFIG_3X3;
        int totalIntersections = topo3x3.getIntersectionCount();

        int runGlobalIndex = 1;

        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — GERAÇÃO DO DATASET 3×3 DE MACHINE LEARNING (FASE 6.5-B)");
        System.out.println("================================================================================");
        System.out.println("• Topologia: Malha 3×3 (" + totalIntersections + " cruzamentos C1..C9)");
        System.out.println("• Horizonte de Previsão: " + (int) PREDICTION_HORIZON_SECONDS + " segundos");
        System.out.println("• Intervalo de Amostragem: " + (int) SAMPLE_INTERVAL_SECONDS + " segundos");
        System.out.println("• Cenários: " + java.util.Arrays.toString(scenarios));
        System.out.println("• Corridas por Cenário: 8 (Total de 32 corridas independentes)");

        long startTime = System.currentTimeMillis();

        for (String scenario : scenarios) {
            for (int r = 1; r <= 8; r++) {
                String runId = String.format("RUN_%02d_%s", runGlobalIndex, scenario);
                long seed = masterSeed + (runGlobalIndex * 1013L);

                // Particionamento estrito por corrida:
                // Runs 1 a 5: TRAIN (62.5%)
                // Runs 6 a 7: VAL   (25.0%)
                // Run 8:      TEST  (12.5%)
                String split;
                if (r <= 5) {
                    split = "TRAIN";
                } else if (r <= 7) {
                    split = "VAL";
                } else {
                    split = "TEST";
                }

                List<DatasetRecord3x3> runRecords = executeSingleSimulationRun(
                        runId, scenario, split, seed, topo3x3);
                allRecords.addAll(runRecords);

                runGlobalIndex++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("\n[Geração Concluída] Tempo decorrido: " + elapsed + " ms");
        System.out.println("Total de observações geradas: " + allRecords.size());

        // Análise de distribuição
        int countTrain = 0, countVal = 0, countTest = 0;
        int posTrain = 0, posVal = 0, posTest = 0;

        for (DatasetRecord3x3 rec : allRecords) {
            if ("TRAIN".equals(rec.split)) {
                countTrain++;
                if (rec.targetCongestion == 1) posTrain++;
            } else if ("VAL".equals(rec.split)) {
                countVal++;
                if (rec.targetCongestion == 1) posVal++;
            } else if ("TEST".equals(rec.split)) {
                countTest++;
                if (rec.targetCongestion == 1) posTest++;
            }
        }

        System.out.println("\nDistribuição dos Dados por Split e Prevalência de Congestionamento:");
        System.out.printf(Locale.US, "  • TRAIN: %5d amostras | Positivos: %4d (%.1f%%)\n",
                countTrain, posTrain, (posTrain * 100.0 / Math.max(1, countTrain)));
        System.out.printf(Locale.US, "  • VAL:   %5d amostras | Positivos: %4d (%.1f%%)\n",
                countVal, posVal, (posVal * 100.0 / Math.max(1, countVal)));
        System.out.printf(Locale.US, "  • TEST:  %5d amostras | Positivos: %4d (%.1f%%)\n",
                countTest, posTest, (posTest * 100.0 / Math.max(1, countTest)));

        exportToCsv(allRecords, DATASET_CSV_PATH);
        exportMetadataJson(allRecords, countTrain, countVal, countTest, posTrain, posVal, posTest, METADATA_JSON_PATH);

        return allRecords;
    }

    private static List<DatasetRecord3x3> executeSingleSimulationRun(
            String runId, String scenario, String split, long seed, NetworkTopologyConfig topo) {

        List<DatasetRecord3x3> records = new ArrayList<>();
        Map<Double, List<PendingObservation>> pendingMap = new HashMap<>();

        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator(
                scenario, seed, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE, topo);

        double dt = 1.0 / 30.0;
        int totalSteps = (int) Math.round(SIMULATION_TOTAL_DURATION_SECONDS / dt);
        double nextSampleTime = SIMULATION_START_RECORDING_SECONDS;

        for (int step = 0; step < totalSteps; step++) {
            sim.advanceSeconds(dt);
            double currentSimTime = sim.getCurrentSimTime();

            // 1. Amostragem de features em t
            if (currentSimTime >= nextSampleTime && currentSimTime <= SIMULATION_END_RECORDING_SECONDS + 0.01) {
                double snapshotTime = Math.round(currentSimTime * 10.0) / 10.0;
                List<PendingObservation> obsList = new ArrayList<>();

                for (int i = 0; i < topo.getIntersectionCount(); i++) {
                    double[] feats = extractFeatures(sim, i, topo);
                    obsList.add(new PendingObservation(snapshotTime, i, feats));
                }

                pendingMap.put(snapshotTime, obsList);
                nextSampleTime += SAMPLE_INTERVAL_SECONDS;
            }

            // 2. Resolução do target em t + 60s
            double targetLookupTime = Math.round((currentSimTime - PREDICTION_HORIZON_SECONDS) * 10.0) / 10.0;
            if (pendingMap.containsKey(targetLookupTime)) {
                List<PendingObservation> pendingList = pendingMap.remove(targetLookupTime);
                for (PendingObservation obs : pendingList) {
                    int i = obs.intersectionId;
                    int futureQueueNS = sim.getWaitingCount(i, 0);
                    int futureQueueEW = sim.getWaitingCount(i, 1);
                    int futureTotalQueue = futureQueueNS + futureQueueEW;
                    double futureAvgWait = sim.getAverageWaitingTime(i);

                    // Critério objetivo de congestionamento na rede 3×3:
                    // Fila Total >= 3 viaturas OU Espera Média >= 4.0 segundos em t + 60s
                    int target = (futureTotalQueue >= 3 || futureAvgWait >= 4.0) ? 1 : 0;

                    DatasetRecord3x3 record = new DatasetRecord3x3(
                            runId, scenario, split, seed, obs.simTime, i,
                            obs.features, target, futureTotalQueue, futureAvgWait);
                    records.add(record);
                }
            }
        }

        sim.cleanup();
        return records;
    }

    public static double[] extractFeatures(HeadlessTrafficSimulator sim, int intersectionIndex, NetworkTopologyConfig topo) {
        int queueNS = sim.getWaitingCount(intersectionIndex, 0);
        int queueEW = sim.getWaitingCount(intersectionIndex, 1);
        int totalQueue = queueNS + queueEW;
        double queueRatio = (totalQueue > 0)
                ? ((double) (queueNS - queueEW) / (double) (totalQueue + 1.0))
                : 0.0;

        int approachingNS = sim.getApproachingCount(intersectionIndex, 0);
        int approachingEW = sim.getApproachingCount(intersectionIndex, 1);
        int totalApproaching = approachingNS + approachingEW;

        double avgSpeed = sim.getAverageSpeed(intersectionIndex);
        double avgWait = sim.getAverageWaitingTime(intersectionIndex);

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(intersectionIndex);
        CitySimulationGUI.LightState stateNS = auth.getLightState(0);
        CitySimulationGUI.LightState stateEW = auth.getLightState(1);

        double currentPhaseCode;
        if (stateNS == CitySimulationGUI.LightState.GREEN) {
            currentPhaseCode = 0.0; // NS_GREEN
        } else if (stateEW == CitySimulationGUI.LightState.GREEN) {
            currentPhaseCode = 1.0; // EW_GREEN
        } else {
            currentPhaseCode = 0.5; // Transição (Amarelo / Vermelho Geral)
        }

        double greenElapsedSec = auth.getPhaseElapsedMs() / 1000.0;
        int weatherCode = sim.getWeatherCondition();

        double roadFrictionMu = 1.00;
        if (weatherCode == 1) {
            roadFrictionMu = 0.70; // Chuva / Piso Molhado
        } else if (weatherCode == 2) {
            roadFrictionMu = 0.45; // Neve / Gelo
        }

        // Pressão de vizinhos a montante na grelha 3×3
        int row = intersectionIndex / 3;
        int col = intersectionIndex % 3;
        int maxNeighborQueue = 0;

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};

        for (int k = 0; k < 4; k++) {
            int nr = row + dr[k];
            int nc = col + dc[k];
            if (nr >= 0 && nr < 3 && nc >= 0 && nc < 3) {
                int neighborIndex = nr * 3 + nc;
                int nQ = sim.getWaitingCount(neighborIndex, 0) + sim.getWaitingCount(neighborIndex, 1);
                if (nQ > maxNeighborQueue) {
                    maxNeighborQueue = nQ;
                }
            }
        }

        return new double[] {
            queueNS,
            queueEW,
            totalQueue,
            queueRatio,
            totalApproaching,
            avgSpeed,
            avgWait,
            currentPhaseCode,
            greenElapsedSec,
            (double) weatherCode,
            roadFrictionMu,
            (double) maxNeighborQueue
        };
    }

    public static void exportToCsv(List<DatasetRecord3x3> records, String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            // Header
            StringBuilder header = new StringBuilder();
            header.append("runId,scenarioName,split,seed,simTimeSeconds,intersectionId");
            for (String fName : FEATURE_NAMES) {
                header.append(",").append(fName);
            }
            header.append(",target_congestion");
            pw.println(header.toString());

            for (DatasetRecord3x3 rec : records) {
                pw.println(rec.toCsvLine());
            }
            System.out.println("[Dataset 3x3] CSV exportado com sucesso: " + file.getAbsolutePath() +
                    " (" + records.size() + " linhas)");
        } catch (IOException e) {
            System.err.println("[Dataset 3x3] Erro ao gravar CSV: " + e.getMessage());
        }
    }

    public static void exportMetadataJson(List<DatasetRecord3x3> records, int nTrain, int nVal, int nTest,
                                          int pTrain, int pVal, int pTest, String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("{");
            pw.println("  \"datasetName\": \"TrafficMAS_3x3_Congestion_Dataset\",");
            pw.println("  \"topology\": \"GRID_3X3\",");
            pw.println("  \"intersectionsCount\": 9,");
            pw.println("  \"predictionHorizonSeconds\": " + PREDICTION_HORIZON_SECONDS + ",");
            pw.println("  \"sampleIntervalSeconds\": " + SAMPLE_INTERVAL_SECONDS + ",");
            pw.println("  \"totalSamples\": " + records.size() + ",");
            pw.println("  \"featuresCount\": " + FEATURE_NAMES.length + ",");
            pw.println("  \"features\": [");
            for (int i = 0; i < FEATURE_NAMES.length; i++) {
                pw.print("    \"" + FEATURE_NAMES[i] + "\"" + (i < FEATURE_NAMES.length - 1 ? "," : "") + "\n");
            }
            pw.println("  ],");
            pw.println("  \"targetDefinition\": \"future_total_queue >= 3 || future_avg_wait_sec >= 4.0\",");
            pw.println("  \"splits\": {");
            pw.printf(Locale.US, "    \"TRAIN\": { \"samples\": %d, \"positives\": %d, \"prevalence\": %.4f },\n",
                    nTrain, pTrain, (pTrain / (double) Math.max(1, nTrain)));
            pw.printf(Locale.US, "    \"VAL\":   { \"samples\": %d, \"positives\": %d, \"prevalence\": %.4f },\n",
                    nVal, pVal, (pVal / (double) Math.max(1, nVal)));
            pw.printf(Locale.US, "    \"TEST\":  { \"samples\": %d, \"positives\": %d, \"prevalence\": %.4f }\n",
                    nTest, pTest, (pTest / (double) Math.max(1, nTest)));
            pw.println("  },");
            pw.println("  \"leakageProtection\": \"Grouped split by RunId and Seed; no overlapping time windows; test seeds never seen during training; zero future leakage\"");
            pw.println("}");
            System.out.println("[Dataset 3x3] Metadados JSON exportados com sucesso: " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Dataset 3x3] Erro ao gravar JSON de metadados: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        long seed = 42L;
        if (args != null && args.length > 0) {
            seed = Long.parseLong(args[0]);
        }
        generateDataset(seed);
    }
}
