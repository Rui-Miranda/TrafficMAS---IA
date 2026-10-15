package ml;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Carregador e particionador do dataset de tráfego (CSV).
 * 
 * GARANTIA ANTI-LEAKAGE:
 * - Filtra por partição explícita (TRAIN, VAL, TEST);
 * - Extrai exclusivamente as 7 features físicas observáveis em t;
 * - Isola o targetCongestion para o vetor y;
 * - Descarta identificadores de execução (runId, scenarioName, split, simTime, intersectionId).
 */
public class DatasetLoader {

    public static final String[] FEATURE_NAMES = {
        "trafficVolume",
        "queueLength",
        "averageSpeed",
        "averageWaitingTime",
        "roadCapacity",
        "weatherCondition",
        "incidentPresent"
    };

    public static class SplitData {
        public final String splitName;
        public final double[][] X;
        public final int[] y;

        public SplitData(String splitName, double[][] X, int[] y) {
            this.splitName = splitName;
            this.X = X;
            this.y = y;
        }

        public int size() {
            return y.length;
        }
    }

    /**
     * Carrega uma partição específica do dataset CSV.
     * @param csvPath Caminho do ficheiro CSV
     * @param targetSplit Nome da partição ("TRAIN", "VAL" ou "TEST")
     */
    public static SplitData loadSplit(String csvPath, String targetSplit) throws IOException {
        List<double[]> featuresList = new ArrayList<>();
        List<Integer> labelsList = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                throw new IOException("Ficheiro CSV vazio: " + csvPath);
            }

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] tokens = line.split(",");
                if (tokens.length < 13) {
                    continue;
                }

                String split = tokens[2].trim();
                if (!split.equalsIgnoreCase(targetSplit)) {
                    continue;
                }

                // Features físicas extraídas estritamente em t (colunas 5 a 11)
                double trafficVolume = Double.parseDouble(tokens[5]);
                double queueLength = Double.parseDouble(tokens[6]);
                double averageSpeed = Double.parseDouble(tokens[7]);
                double averageWaitingTime = Double.parseDouble(tokens[8]);
                double roadCapacity = Double.parseDouble(tokens[9]);
                double weatherCondition = Double.parseDouble(tokens[10]);
                double incidentPresent = Double.parseDouble(tokens[11]);

                double[] features = new double[] {
                    trafficVolume,
                    queueLength,
                    averageSpeed,
                    averageWaitingTime,
                    roadCapacity,
                    weatherCondition,
                    incidentPresent
                };

                // Target real em t + 60s (coluna 12)
                int target = Integer.parseInt(tokens[12]);

                featuresList.add(features);
                labelsList.add(target);
            }
        }

        int m = featuresList.size();
        double[][] X = new double[m][];
        int[] y = new int[m];

        for (int i = 0; i < m; i++) {
            X[i] = featuresList.get(i);
            y[i] = labelsList.get(i);
        }

        return new SplitData(targetSplit, X, y);
    }
}
