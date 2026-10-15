package ml;

import environment.HeadlessTrafficSimulator;
import environment.HeadlessTrafficSimulator.IntersectionMetricsSnapshot;
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
 * Gerador de Datasets de Tráfego Baseado na Simulação (Fase 4 - Etapa 1).
 *
 * Características essenciais:
 * 1. Baseado 100% no motor físico/cinemático da simulação (HeadlessTrafficSimulator).
 * 2. Previsão verdadeiramente temporal: horizonte fixo de 60 segundos (t + 60s).
 * 3. 16 execuções independentes cobrindo 8 cenários viários e meteorológicos distintos.
 * 4. Sementes pseudoaleatórias controladas para reprodutibilidade total.
 * 5. Particionamento estrito por corrida (TRAIN, VAL, TEST) sem contaminação temporal.
 * 6. Exportação para ficheiro CSV normalizado ("data/traffic_disruption_dataset.csv").
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficDatasetGenerator {

    public static final String DATASET_CSV_PATH = "data/traffic_disruption_dataset.csv";
    public static final double PREDICTION_HORIZON_SECONDS = 60.0;
    public static final double SAMPLE_INTERVAL_SECONDS = 5.0;
    public static final double SIMULATION_DURATION_SECONDS = 310.0;

    /**
     * Registo de observação no dataset com features em t e target em t + 60s.
     */
    public static class DatasetRecord {
        public final String runId;
        public final String scenarioName;
        public final String split; // TRAIN, VAL, TEST
        public final double simTimeSeconds;
        public final int intersectionId;
        public final int trafficVolume;
        public final int queueLength;
        public final double averageSpeed; // px/tick (1 px/tick ≈ 10.8 km/h)
        public final double averageWaitingTime; // segundos
        public final int roadCapacity; // faixas ativas
        public final int weatherCondition; // 0=SECO, 1=CHUVA, 2=NEVOEIRO
        public final int incidentPresent; // 0=NAO, 1=SIM
        public final int targetCongestion; // Target em t + 60s (0=Fluido, 1=Congestionado)

        public DatasetRecord(String runId, String scenarioName, String split, double simTimeSeconds, 
                             int intersectionId, int trafficVolume, int queueLength, double averageSpeed, 
                             double averageWaitingTime, int roadCapacity, int weatherCondition, 
                             int incidentPresent, int targetCongestion) {
            this.runId = runId;
            this.scenarioName = scenarioName;
            this.split = split;
            this.simTimeSeconds = simTimeSeconds;
            this.intersectionId = intersectionId;
            this.trafficVolume = trafficVolume;
            this.queueLength = queueLength;
            this.averageSpeed = averageSpeed;
            this.averageWaitingTime = averageWaitingTime;
            this.roadCapacity = roadCapacity;
            this.weatherCondition = weatherCondition;
            this.incidentPresent = incidentPresent;
            this.targetCongestion = targetCongestion;
        }

        public String toCsvLine() {
            return String.format(Locale.US, "%s,%s,%s,%.1f,%d,%d,%d,%.2f,%.1f,%d,%d,%d,%d",
                    runId, scenarioName, split, simTimeSeconds, intersectionId,
                    trafficVolume, queueLength, averageSpeed, averageWaitingTime,
                    roadCapacity, weatherCondition, incidentPresent, targetCongestion);
        }
    }

    /**
     * Executa a geração completa do dataset de simulação.
     */
    public static List<DatasetRecord> generateDataset(long masterSeed) {
        List<DatasetRecord> allRecords = new ArrayList<>();

        // 8 cenários distintos (2 corridas independentes por cenário = 16 corridas no total)
        String[] scenarios = {
            "NORMAL", "INTENSE", "ASYMMETRIC", "RAIN", 
            "FOG", "ACCIDENT", "ROADWORK", "SPECIAL_EVENT"
        };

        int runIndex = 1;

        for (String scenario : scenarios) {
            for (int r = 1; r <= 2; r++) {
                String runId = String.format("RUN_%02d_%s", runIndex, scenario);
                long seed = masterSeed + (runIndex * 1013L);

                // Particionamento estrito por corrida independente:
                // Runs 1 a 11: TRAIN (~70%)
                // Runs 12 e 13: VAL (~12%)
                // Runs 14 a 16: TEST (~18%)
                String split;
                if (runIndex <= 11) {
                    split = "TRAIN";
                } else if (runIndex <= 13) {
                    split = "VAL";
                } else {
                    split = "TEST";
                }

                List<DatasetRecord> runRecords = executeSimulationRun(runId, scenario, split, seed);
                allRecords.addAll(runRecords);

                runIndex++;
            }
        }

        return allRecords;
    }

    /**
     * Executa uma corrida de simulação e emparelha as observações em t com o target em t + 60s.
     */
    private static List<DatasetRecord> executeSimulationRun(String runId, String scenario, String split, long seed) {
        List<DatasetRecord> records = new ArrayList<>();
        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator(scenario, seed);

        // Mapa de snapshots por instante de tempo (arredondado para décimas de segundo) e cruzamento
        // Chave: ((long)(t * 10)) * 10 + intersectionId
        Map<Long, IntersectionMetricsSnapshot> timeline = new HashMap<>();

        double t = 0.0;
        double stepSize = SAMPLE_INTERVAL_SECONDS;

        // Fase 1: Executa a simulação completa e recolhe snapshots periódicos
        while (t <= SIMULATION_DURATION_SECONDS) {
            for (int i = 0; i < 4; i++) {
                IntersectionMetricsSnapshot snap = sim.captureSnapshot(i);
                long key = getKey(snap.simTimeSeconds, i);
                timeline.put(key, snap);
            }
            sim.advanceSeconds(stepSize);
            t = sim.getCurrentSimTime();
        }

        // Fase 2: Para cada instante t entre 10s e (310s - 60s = 250s), busca o target em t + 60s
        double tStart = 10.0;
        double tEnd = SIMULATION_DURATION_SECONDS - PREDICTION_HORIZON_SECONDS;

        for (double sampleT = tStart; sampleT <= tEnd; sampleT += stepSize) {
            double futureT = sampleT + PREDICTION_HORIZON_SECONDS;

            for (int i = 0; i < 4; i++) {
                IntersectionMetricsSnapshot currentSnap = timeline.get(getKey(sampleT, i));
                IntersectionMetricsSnapshot futureSnap = timeline.get(getKey(futureT, i));

                if (currentSnap != null && futureSnap != null) {
                    int target = futureSnap.isSevereCongestion() ? 1 : 0;

                    DatasetRecord record = new DatasetRecord(
                            runId, scenario, split, currentSnap.simTimeSeconds,
                            currentSnap.intersectionId, currentSnap.trafficVolume,
                            currentSnap.queueLength, currentSnap.averageSpeed,
                            currentSnap.averageWaitingTime, currentSnap.roadCapacity,
                            currentSnap.weatherCondition, currentSnap.incidentPresent,
                            target
                    );

                    records.add(record);
                }
            }
        }

        return records;
    }

    private static long getKey(double timeSeconds, int intersectionId) {
        long quantizedTime = Math.round(timeSeconds / 5.0) * 50L;
        return quantizedTime + intersectionId;
    }

    /**
     * Salva os registos num ficheiro CSV.
     */
    public static void saveToCsv(List<DatasetRecord> records, String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            // Cabeçalho CSV com especificações
            writer.println("runId,scenarioName,split,simTimeSeconds,intersectionId,trafficVolume,queueLength,averageSpeed,averageWaitingTime,roadCapacity,weatherCondition,incidentPresent,targetCongestion");
            for (DatasetRecord rec : records) {
                writer.println(rec.toCsvLine());
            }
        }
    }

    /**
     * Ponto de entrada para execução autónoma da geração do dataset.
     */
    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — GERAÇÃO DO DATASET DE MACHINE LEARNING (FASE 4 - ETAPA 1)");
        System.out.println("================================================================================");

        long seed = 42L;
        System.out.println("[1/3] A executar 16 corridas da simulação com semente controlada (Seed = " + seed + ")...");
        long startMs = System.currentTimeMillis();
        List<DatasetRecord> records = generateDataset(seed);
        long elapsed = System.currentTimeMillis() - startMs;

        System.out.println("[2/3] Simulação concluída com sucesso em " + elapsed + " ms.");
        System.out.println("      Total de observações geradas: " + records.size());

        // Estatísticas de Classes
        long positiveCount = records.stream().filter(r -> r.targetCongestion == 1).count();
        long negativeCount = records.stream().filter(r -> r.targetCongestion == 0).count();
        double positivePct = (positiveCount * 100.0) / records.size();

        System.out.println("\n[3/3] Distribuição das Classes no Dataset:");
        System.out.printf(Locale.US, "      • Classe 0 (Fluido):        %d (%.1f%%)\n", negativeCount, (100.0 - positivePct));
        System.out.printf(Locale.US, "      • Classe 1 (Congestionado): %d (%.1f%%)\n", positiveCount, positivePct);

        try {
            saveToCsv(records, DATASET_CSV_PATH);
            System.out.println("\n[SUCESSO] Ficheiro CSV gerado e salvo em: " + DATASET_CSV_PATH);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao salvar CSV: " + e.getMessage());
            System.exit(1);
        }
    }
}
