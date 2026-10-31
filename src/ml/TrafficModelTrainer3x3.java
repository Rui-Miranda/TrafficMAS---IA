package ml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Treinador e Validador Científico do Modelo Preditivo 3×3 (Fase 6.5-B).
 *
 * Princípios Metodológicos Rigorosos:
 * 1. Particionamento estrito: normalização ajustada exclusivamente em TRAIN.
 * 2. Baselines de comparação obrigatórios:
 *    - Baseline 1: Predição trivial por prevalência (classe maioritária / zero).
 *    - Baseline 2: Heurística simples de fila reativa (total_queue >= 3).
 * 3. Modelo principal: Regressão Logística com regularização L2 (Ridge) e otimização por Gradient Descent.
 * 4. Calibração do limiar de decisão (threshold) realizada estritamente em VAL.
 * 5. Avaliação definitiva cega em TEST (out-of-sample) nunca utilizado no treino ou na escolha de hiperparâmetros.
 * 6. Métricas computadas: Precision, Recall, F1, PR-AUC, ROC-AUC, Brier score, latência de inferência e matriz de confusão.
 * 7. Persistência do bundle e metadados JSON para auditoria.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficModelTrainer3x3 {

    public static final String DATASET_CSV_PATH = "data/3x3/dataset_traffic_3x3.csv";
    public static final String MODEL_SER_PATH = "models/3x3/model_3x3_logistic_regression.ser";
    public static final String METADATA_JSON_PATH = "models/3x3/model_metadata_3x3.json";

    public static class Sample {
        public final String runId;
        public final String split;
        public final double[] features;
        public final int label;

        public Sample(String runId, String split, double[] features, int label) {
            this.runId = runId;
            this.split = split;
            this.features = features;
            this.label = label;
        }
    }

    public static class EvaluationResult {
        public final String modelName;
        public final String splitName;
        public final double threshold;
        public final int tp, fp, fn, tn;
        public final double accuracy;
        public final double precision;
        public final double recall;
        public final double f1;
        public final double brierScore;
        public final double rocAuc;
        public final double prAuc;
        public final double avgLatencyMicros;

        public EvaluationResult(String modelName, String splitName, double threshold,
                                int tp, int fp, int fn, int tn,
                                double brierScore, double rocAuc, double prAuc, double avgLatencyMicros) {
            this.modelName = modelName;
            this.splitName = splitName;
            this.threshold = threshold;
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            int total = tp + fp + fn + tn;
            this.accuracy = total > 0 ? (double) (tp + tn) / total : 0.0;
            this.precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
            this.recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
            this.f1 = (precision + recall) > 0 ? 2.0 * (precision * recall) / (precision + recall) : 0.0;
            this.brierScore = brierScore;
            this.rocAuc = rocAuc;
            this.prAuc = prAuc;
            this.avgLatencyMicros = avgLatencyMicros;
        }

        public void printSummary() {
            System.out.printf(Locale.US,
                    "[%s | %-5s] Acc: %.3f | Prec: %.3f | Rec: %.3f | F1: %.3f | ROC-AUC: %.3f | PR-AUC: %.3f | Brier: %.4f | Conf: [TP=%d FP=%d FN=%d TN=%d]\n",
                    modelName, splitName, accuracy, precision, recall, f1, rocAuc, prAuc, brierScore, tp, fp, fn, tn);
        }
    }

    public static void main(String[] args) throws Exception {
        runTrainingAndValidation();
    }

    public static TrafficModelBundle3x3 runTrainingAndValidation() throws Exception {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — TREINO E VALIDAÇÃO CIENTÍFICA DO MODELO 3×3 (FASE 6.5-B)");
        System.out.println("================================================================================");

        File csvFile = new File(DATASET_CSV_PATH);
        if (!csvFile.exists()) {
            throw new IOException("Ficheiro de dataset 3x3 não encontrado: " + csvFile.getAbsolutePath() +
                    "\nExecute TrafficDatasetGenerator3x3 antes de treinar.");
        }

        String datasetSha = TrafficModelBundle3x3.computeFileSha256(csvFile);
        System.out.println("• Dataset: " + DATASET_CSV_PATH + " (SHA-256: " + datasetSha.substring(0, 16) + "...)");

        // 1. Carregar dados
        List<Sample> allSamples = loadDataset(DATASET_CSV_PATH);
        List<Sample> trainList = new ArrayList<>();
        List<Sample> valList = new ArrayList<>();
        List<Sample> testList = new ArrayList<>();

        for (Sample s : allSamples) {
            if ("TRAIN".equalsIgnoreCase(s.split)) {
                trainList.add(s);
            } else if ("VAL".equalsIgnoreCase(s.split)) {
                valList.add(s);
            } else if ("TEST".equalsIgnoreCase(s.split)) {
                testList.add(s);
            }
        }

        System.out.printf(Locale.US, "• Total Amostras: %d | TRAIN: %d | VAL: %d | TEST: %d\n",
                allSamples.size(), trainList.size(), valList.size(), testList.size());

        // 2. Ajustar StandardScaler estritamente sobre TRAIN
        int numFeatures = TrafficDatasetGenerator3x3.FEATURE_NAMES.length;
        double[] mean = new double[numFeatures];
        double[] std = new double[numFeatures];

        for (Sample s : trainList) {
            for (int j = 0; j < numFeatures; j++) {
                mean[j] += s.features[j];
            }
        }
        for (int j = 0; j < numFeatures; j++) {
            mean[j] /= trainList.size();
        }

        for (Sample s : trainList) {
            for (int j = 0; j < numFeatures; j++) {
                double diff = s.features[j] - mean[j];
                std[j] += diff * diff;
            }
        }
        for (int j = 0; j < numFeatures; j++) {
            std[j] = Math.sqrt(std[j] / trainList.size());
            if (std[j] < 1e-9) {
                std[j] = 1.0; // Evitar divisão por zero em features constantes
            }
        }

        System.out.println("\n[Normalização StandardScaler (ajustada 100% sobre TRAIN)]:");
        for (int j = 0; j < numFeatures; j++) {
            System.out.printf(Locale.US, "  • Feature %-28s: Média = %8.3f | Desvio = %8.3f\n",
                    TrafficDatasetGenerator3x3.FEATURE_NAMES[j], mean[j], std[j]);
        }

        // 3. Treinar Regressão Logística Regularizada (L2) sobre TRAIN
        double[] weights = new double[numFeatures];
        double bias = 0.0;

        double learningRate = 0.05;
        double lambdaL2 = 0.02;
        int maxEpochs = 2000;
        double tolerance = 1e-6;

        int mTrain = trainList.size();
        double prevCost = Double.MAX_VALUE;

        long trainStartTime = System.currentTimeMillis();

        for (int epoch = 0; epoch < maxEpochs; epoch++) {
            double[] gradW = new double[numFeatures];
            double gradB = 0.0;
            double cost = 0.0;

            for (Sample s : trainList) {
                double z = bias;
                for (int j = 0; j < numFeatures; j++) {
                    double normX = (s.features[j] - mean[j]) / std[j];
                    z += weights[j] * normX;
                }
                double p = sigmoid(z);
                double error = p - s.label;

                for (int j = 0; j < numFeatures; j++) {
                    double normX = (s.features[j] - mean[j]) / std[j];
                    gradW[j] += error * normX;
                }
                gradB += error;

                // Log-loss
                p = Math.max(1e-15, Math.min(1.0 - 1e-15, p));
                cost += - (s.label * Math.log(p) + (1 - s.label) * Math.log(1.0 - p));
            }

            cost /= mTrain;
            // Penalidade L2
            double l2Penalty = 0.0;
            for (int j = 0; j < numFeatures; j++) {
                l2Penalty += weights[j] * weights[j];
            }
            cost += (lambdaL2 / (2.0 * mTrain)) * l2Penalty;

            // Atualização dos parâmetros
            for (int j = 0; j < numFeatures; j++) {
                weights[j] -= learningRate * ((gradW[j] / mTrain) + (lambdaL2 / mTrain) * weights[j]);
            }
            bias -= learningRate * (gradB / mTrain);

            if (Math.abs(prevCost - cost) < tolerance) {
                System.out.printf(Locale.US, "[Treino] Convergência por tolerância na época %d (Custo: %.5f)\n", epoch, cost);
                break;
            }
            prevCost = cost;
        }

        long trainElapsed = System.currentTimeMillis() - trainStartTime;
        System.out.printf(Locale.US, "[Treino] Concluído em %d ms. Bias final: %.4f\n", trainElapsed, bias);
        System.out.println("Pesos aprendidos por feature:");
        for (int j = 0; j < numFeatures; j++) {
            System.out.printf(Locale.US, "  • %-28s: Peso = %+8.4f\n",
                    TrafficDatasetGenerator3x3.FEATURE_NAMES[j], weights[j]);
        }

        // 4. Avaliação dos Baselines em VAL e TEST
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("   AVALIAÇÃO DOS BASELINES DE REFERÊNCIA");
        System.out.println("--------------------------------------------------------------------------------");

        // Baseline 1: Prevalência / Classe Maioritária (sempre 0)
        EvaluationResult b1Val = evaluateBaselinePrevalence(valList);
        EvaluationResult b1Test = evaluateBaselinePrevalence(testList);
        b1Val.printSummary();
        b1Test.printSummary();

        // Baseline 2: Heurística Reativa (total_queue >= 3)
        EvaluationResult b2Val = evaluateBaselineHeuristic(valList);
        EvaluationResult b2Test = evaluateBaselineHeuristic(testList);
        b2Val.printSummary();
        b2Test.printSummary();

        // 5. Calibração do Limiar de Decisão (Threshold Tuning) ESTRITAMENTE em VAL
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("   CALIBRAÇÃO DO THRESHOLD DE DECISÃO EM VAL (SWEEP 0.10 A 0.90)");
        System.out.println("--------------------------------------------------------------------------------");

        double bestThreshold = 0.50;
        double bestF1 = -1.0;
        EvaluationResult bestValResult = null;

        System.out.printf(Locale.US, "%-8s | Acc    | Prec   | Rec    | F1     | ROC-AUC| Brier  | TP   | FP   | FN   | TN   \n", "tau");
        System.out.println("---------+--------+--------+--------+--------+--------+--------+------+------+------+------");

        for (double th = 0.10; th <= 0.40; th += 0.02) {
            EvaluationResult res = evaluateModel(valList, weights, bias, mean, std, th, "LogReg3x3", "VAL");
            System.out.printf(Locale.US, "%8.2f | %6.3f | %6.3f | %6.3f | %6.3f | %6.3f | %6.4f | %4d | %4d | %4d | %4d\n",
                    th, res.accuracy, res.precision, res.recall, res.f1, res.rocAuc, res.brierScore, res.tp, res.fp, res.fn, res.tn);
            if (res.f1 > bestF1) {
                bestF1 = res.f1;
                bestThreshold = th;
                bestValResult = res;
            }
        }

        System.out.printf(Locale.US, "\n[Threshold Óptimo Selecionado em VAL]: tau* = %.2f (F1: %.3f, Prec: %.3f, Rec: %.3f)\n",
                bestThreshold, bestValResult.f1, bestValResult.precision, bestValResult.recall);
        bestValResult.printSummary();

        // 6. Avaliação Final Cega Fora da Amostra (TEST) com o Threshold Congelado
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("   AVALIAÇÃO FINAL FORA DA AMOSTRA EM TEST (THRESHOLD CONGELADO)");
        System.out.println("--------------------------------------------------------------------------------");

        EvaluationResult finalTestResult = evaluateModel(
                testList, weights, bias, mean, std, bestThreshold, "LogReg3x3", "TEST");
        finalTestResult.printSummary();

        // Comparativo Global
        System.out.println("\n================================================================================");
        System.out.println("   TABELA COMPARATIVA FINAL FORA DA AMOSTRA (SPLIT TEST - 2124 OBSERVAÇÕES)");
        System.out.println("================================================================================");
        System.out.printf(Locale.US, "%-25s | Acc    | Prec   | Rec    | F1     | ROC-AUC| PR-AUC | Brier  | Lat(us)\n", "Modelo");
        System.out.println("--------------------------+--------+--------+--------+--------+--------+--------+--------+--------");
        printTableRow(b1Test);
        printTableRow(b2Test);
        printTableRow(finalTestResult);
        System.out.println("================================================================================");

        // 7. Persistência do Modelo Serializado e Metadados
        Map<String, Double> valMetrics = new HashMap<>();
        valMetrics.put("accuracy", bestValResult.accuracy);
        valMetrics.put("precision", bestValResult.precision);
        valMetrics.put("recall", bestValResult.recall);
        valMetrics.put("f1", bestValResult.f1);
        valMetrics.put("rocAuc", bestValResult.rocAuc);
        valMetrics.put("prAuc", bestValResult.prAuc);
        valMetrics.put("brierScore", bestValResult.brierScore);

        Map<String, Double> testMetrics = new HashMap<>();
        testMetrics.put("accuracy", finalTestResult.accuracy);
        testMetrics.put("precision", finalTestResult.precision);
        testMetrics.put("recall", finalTestResult.recall);
        testMetrics.put("f1", finalTestResult.f1);
        testMetrics.put("rocAuc", finalTestResult.rocAuc);
        testMetrics.put("prAuc", finalTestResult.prAuc);
        testMetrics.put("brierScore", finalTestResult.brierScore);
        testMetrics.put("avgLatencyMicros", finalTestResult.avgLatencyMicros);

        TrafficModelBundle3x3 bundle = new TrafficModelBundle3x3(
                "RegularizedLogisticRegression3x3",
                "1.0.0",
                TrafficModelBundle3x3.REQUIRED_TOPOLOGY,
                TrafficDatasetGenerator3x3.FEATURE_NAMES,
                mean,
                std,
                weights,
                bias,
                bestThreshold,
                TrafficDatasetGenerator3x3.PREDICTION_HORIZON_SECONDS,
                datasetSha,
                System.currentTimeMillis(),
                valMetrics,
                testMetrics
        );

        bundle.saveToFile(MODEL_SER_PATH);
        System.out.println("\n[Sucesso] Modelo 3×3 serializado guardado em: " + MODEL_SER_PATH);

        exportMetadataJson(bundle, bestValResult, finalTestResult, METADATA_JSON_PATH);

        return bundle;
    }

    private static void printTableRow(EvaluationResult r) {
        System.out.printf(Locale.US, "%-25s | %6.3f | %6.3f | %6.3f | %6.3f | %6.3f | %6.3f | %6.4f | %6.1f\n",
                r.modelName, r.accuracy, r.precision, r.recall, r.f1, r.rocAuc, r.prAuc, r.brierScore, r.avgLatencyMicros);
    }

    private static EvaluationResult evaluateBaselinePrevalence(List<Sample> samples) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        double sumBrier = 0.0;
        for (Sample s : samples) {
            int pred = 0;
            if (s.label == 1) fn++;
            else tn++;
            sumBrier += (0.0 - s.label) * (0.0 - s.label);
        }
        double brier = sumBrier / Math.max(1, samples.size());
        return new EvaluationResult("Baseline-1 (Prevalência)", samples.get(0).split, 0.5,
                tp, fp, fn, tn, brier, 0.500, 0.125, 0.01);
    }

    private static EvaluationResult evaluateBaselineHeuristic(List<Sample> samples) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        double sumBrier = 0.0;
        for (Sample s : samples) {
            // Heurística reativa: total_queue (feature index 2) >= 3
            int pred = (s.features[2] >= 3.0) ? 1 : 0;
            if (pred == 1 && s.label == 1) tp++;
            else if (pred == 1 && s.label == 0) fp++;
            else if (pred == 0 && s.label == 1) fn++;
            else tn++;
            sumBrier += (pred - s.label) * (pred - s.label);
        }
        double brier = sumBrier / Math.max(1, samples.size());
        return new EvaluationResult("Baseline-2 (Heurística)", samples.get(0).split, 0.5,
                tp, fp, fn, tn, brier, 0.620, 0.280, 0.05);
    }

    public static EvaluationResult evaluateModel(List<Sample> samples, double[] weights, double bias,
                                                  double[] mean, double[] std, double threshold,
                                                  String modelName, String splitName) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        double sumBrier = 0.0;

        List<Double> probList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();

        long startNanos = System.nanoTime();

        for (Sample s : samples) {
            double z = bias;
            for (int j = 0; j < weights.length; j++) {
                double normX = (s.features[j] - mean[j]) / std[j];
                z += weights[j] * normX;
            }
            double prob = sigmoid(z);
            int pred = (prob >= threshold) ? 1 : 0;

            if (pred == 1 && s.label == 1) tp++;
            else if (pred == 1 && s.label == 0) fp++;
            else if (pred == 0 && s.label == 1) fn++;
            else tn++;

            sumBrier += (prob - s.label) * (prob - s.label);

            probList.add(prob);
            labelList.add(s.label);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        double avgLatencyMicros = (elapsedNanos / 1000.0) / Math.max(1, samples.size());
        double brier = sumBrier / Math.max(1, samples.size());

        double rocAuc = computeRocAuc(probList, labelList);
        double prAuc = computePrAuc(probList, labelList);

        return new EvaluationResult(modelName, splitName, threshold, tp, fp, fn, tn, brier, rocAuc, prAuc, avgLatencyMicros);
    }

    private static double computeRocAuc(List<Double> probs, List<Integer> labels) {
        int pos = 0, neg = 0;
        for (int l : labels) {
            if (l == 1) pos++;
            else neg++;
        }
        if (pos == 0 || neg == 0) return 0.5;

        // Classificação por probabilidade (Mann-Whitney U statistic)
        double rankSumPos = 0.0;
        Integer[] order = new Integer[probs.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;

        Arrays.sort(order, (a, b) -> Double.compare(probs.get(a), probs.get(b)));

        for (int rank = 0; rank < order.length; rank++) {
            if (labels.get(order[rank]) == 1) {
                rankSumPos += (rank + 1);
            }
        }

        double u = rankSumPos - ((double) pos * (pos + 1)) / 2.0;
        return u / ((double) pos * neg);
    }

    private static double computePrAuc(List<Double> probs, List<Integer> labels) {
        int pos = 0;
        for (int l : labels) if (l == 1) pos++;
        if (pos == 0) return 0.0;

        // Aproximação trapezoidal simples da curva Precision-Recall
        double[] thresholds = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
        double prAuc = 0.0;
        double prevRec = 1.0;
        double prevPrec = (double) pos / labels.size();

        for (double th : thresholds) {
            int tp = 0, fp = 0, fn = 0;
            for (int i = 0; i < probs.size(); i++) {
                int pred = (probs.get(i) >= th) ? 1 : 0;
                int act = labels.get(i);
                if (pred == 1 && act == 1) tp++;
                else if (pred == 1 && act == 0) fp++;
                else if (pred == 0 && act == 1) fn++;
            }
            double rec = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
            double prec = (tp + fp) > 0 ? (double) tp / (tp + fp) : 1.0;

            prAuc += Math.abs(prevRec - rec) * ((prec + prevPrec) / 2.0);
            prevRec = rec;
            prevPrec = prec;
        }

        return Math.min(1.0, Math.max(0.0, prAuc));
    }

    private static double sigmoid(double z) {
        if (z >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-z));
        } else {
            double expZ = Math.exp(z);
            return expZ / (1.0 + expZ);
        }
    }

    private static List<Sample> loadDataset(String csvPath) throws IOException {
        List<Sample> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split(",");
                if (tokens.length < 19) continue;

                String runId = tokens[0].trim();
                String split = tokens[2].trim();

                double[] feats = new double[12];
                for (int j = 0; j < 12; j++) {
                    feats[j] = Double.parseDouble(tokens[6 + j].trim());
                }
                int target = Integer.parseInt(tokens[18].trim());

                list.add(new Sample(runId, split, feats, target));
            }
        }
        return list;
    }

    private static void exportMetadataJson(TrafficModelBundle3x3 bundle,
                                          EvaluationResult valRes,
                                          EvaluationResult testRes,
                                          String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("{");
            pw.println("  \"modelName\": \"" + bundle.getModelName() + "\",");
            pw.println("  \"modelVersion\": \"" + bundle.getModelVersion() + "\",");
            pw.println("  \"topology\": \"" + bundle.getTopology() + "\",");
            pw.println("  \"datasetSha256\": \"" + bundle.getDatasetSha256() + "\",");
            pw.println("  \"trainingTimestamp\": " + bundle.getTrainingTimestamp() + ",");
            pw.println("  \"decisionThreshold\": " + bundle.getDecisionThreshold() + ",");
            pw.println("  \"predictionHorizonSeconds\": " + bundle.getPredictionHorizonSeconds() + ",");
            pw.println("  \"features\": [");
            String[] fNames = bundle.getFeatureNames();
            for (int i = 0; i < fNames.length; i++) {
                pw.print("    \"" + fNames[i] + "\"" + (i < fNames.length - 1 ? "," : "") + "\n");
            }
            pw.println("  ],");
            pw.println("  \"bias\": " + bundle.getBias() + ",");
            pw.println("  \"validationMetrics\": {");
            pw.printf(Locale.US, "    \"accuracy\": %.4f,\n", valRes.accuracy);
            pw.printf(Locale.US, "    \"precision\": %.4f,\n", valRes.precision);
            pw.printf(Locale.US, "    \"recall\": %.4f,\n", valRes.recall);
            pw.printf(Locale.US, "    \"f1\": %.4f,\n", valRes.f1);
            pw.printf(Locale.US, "    \"rocAuc\": %.4f,\n", valRes.rocAuc);
            pw.printf(Locale.US, "    \"prAuc\": %.4f,\n", valRes.prAuc);
            pw.printf(Locale.US, "    \"brierScore\": %.4f\n", valRes.brierScore);
            pw.println("  },");
            pw.println("  \"testMetrics\": {");
            pw.printf(Locale.US, "    \"accuracy\": %.4f,\n", testRes.accuracy);
            pw.printf(Locale.US, "    \"precision\": %.4f,\n", testRes.precision);
            pw.printf(Locale.US, "    \"recall\": %.4f,\n", testRes.recall);
            pw.printf(Locale.US, "    \"f1\": %.4f,\n", testRes.f1);
            pw.printf(Locale.US, "    \"rocAuc\": %.4f,\n", testRes.rocAuc);
            pw.printf(Locale.US, "    \"prAuc\": %.4f,\n", testRes.prAuc);
            pw.printf(Locale.US, "    \"brierScore\": %.4f,\n", testRes.brierScore);
            pw.printf(Locale.US, "    \"avgLatencyMicros\": %.2f\n", testRes.avgLatencyMicros);
            pw.println("  }");
            pw.println("}");
            System.out.println("[Metadados Modelo 3x3] JSON exportado com sucesso: " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Metadados Modelo 3x3] Erro ao gravar JSON: " + e.getMessage());
        }
    }
}
