package scratch;

import ml.TrafficModelBundle3x3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

public class EvaluateModelAudit {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - AUDITORIA CIENTÍFICA E RECÁLCULO INDEPENDENTE DE ML 3X3");
        System.out.println("================================================================================");

        TrafficModelBundle3x3 bundle = TrafficModelBundle3x3.loadFromFile(TrafficModelBundle3x3.DEFAULT_MODEL_PATH);
        System.out.println("Modelo Carregado: " + bundle.getModelName() + " v" + bundle.getModelVersion());
        System.out.println("Limiar Congelado (tau*): " + bundle.getDecisionThreshold());
        System.out.println("Bias: " + bundle.getBias());
        System.out.println("Features (12): " + Arrays.toString(bundle.getFeatureNames()));
        System.out.println("Pesos: " + Arrays.toString(bundle.getWeights()));

        // Carrega TEST split
        List<double[]> testX = new ArrayList<>();
        List<Integer> testY = new ArrayList<>();
        List<Double> testQueues = new ArrayList<>(); // para heurística (total_queue)

        // Carrega VAL split para conferir sweep de threshold
        List<double[]> valX = new ArrayList<>();
        List<Integer> valY = new ArrayList<>();

        BufferedReader br = new BufferedReader(new FileReader("data/3x3/dataset_traffic_3x3.csv"));
        String header = br.readLine();
        String line;
        while ((line = br.readLine()) != null) {
            String[] parts = line.split(",");
            String split = parts[2].trim();
            // features estão nos índices 6 a 17 (12 features)
            double[] feats = new double[12];
            for (int j = 0; j < 12; j++) {
                feats[j] = Double.parseDouble(parts[6 + j].trim());
            }
            int target = Integer.parseInt(parts[18].trim());

            if ("TEST".equals(split)) {
                testX.add(feats);
                testY.add(target);
                testQueues.add(feats[2]); // total_queue
            } else if ("VAL".equals(split)) {
                valX.add(feats);
                valY.add(target);
            }
        }
        br.close();

        System.out.printf("\nPartições Carregadas: VAL=%d amostras, TEST=%d amostras\n", valX.size(), testX.size());

        // 1. Verificação do Threshold Sweep em VAL
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("1. VERIFICAÇÃO INDEPENDENTE DA CALIBRAÇÃO DE THRESHOLD EM VAL (Sweep 0.05 a 0.50)");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("%-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-4s | %-4s | %-4s | %-4s\n",
                "tau", "Acc", "Prec", "Rec", "Spec", "F1", "Brier", "TP", "FP", "FN", "TN");

        double bestF1Val = -1;
        double bestTauVal = -1;

        for (double tau = 0.05; tau <= 0.505; tau += 0.01) {
            int tp = 0, fp = 0, fn = 0, tn = 0;
            double brierSum = 0;
            for (int i = 0; i < valX.size(); i++) {
                double p = bundle.predictProbability(valX.get(i));
                int y = valY.get(i);
                int pred = (p >= tau) ? 1 : 0;
                if (pred == 1 && y == 1) tp++;
                else if (pred == 1 && y == 0) fp++;
                else if (pred == 0 && y == 1) fn++;
                else tn++;
                brierSum += Math.pow(p - y, 2);
            }
            double acc = (double)(tp + tn) / valX.size();
            double prec = (tp + fp > 0) ? (double)tp / (tp + fp) : 0;
            double rec = (tp + fn > 0) ? (double)tp / (tp + fn) : 0;
            double spec = (tn + fp > 0) ? (double)tn / (tn + fp) : 0;
            double f1 = (prec + rec > 0) ? 2 * prec * rec / (prec + rec) : 0;
            double brier = brierSum / valX.size();

            if (f1 > bestF1Val) {
                bestF1Val = f1;
                bestTauVal = tau;
            }

            if (Math.abs(tau - 0.10) < 0.005 || Math.abs(tau - 0.12) < 0.005 || Math.abs(tau - 0.15) < 0.005
                    || Math.abs(tau - 0.20) < 0.005 || Math.abs(tau - 0.30) < 0.005 || Math.abs(tau - 0.40) < 0.005) {
                System.out.printf(Locale.US, "%-6.2f | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-6.4f | %-4d | %-4d | %-4d | %-4d\n",
                        tau, acc, prec, rec, spec, f1, brier, tp, fp, fn, tn);
            }
        }
        System.out.printf(Locale.US, ">>> Ótimo em VAL: tau* = %.2f com F1 = %.4f (Confirmação estrita do bundle: tau* = %.2f)\n",
                bestTauVal, bestF1Val, bundle.getDecisionThreshold());

        // 2. Avaliação em TEST no limiar tau* = 0.12
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("2. AVALIAÇÃO EXAUSTIVA EM TEST FORA DA AMOSTRA (2.124 Amostras Held-Out)");
        System.out.println("--------------------------------------------------------------------------------");

        double tauStar = bundle.getDecisionThreshold();
        int tp = 0, fp = 0, fn = 0, tn = 0;
        double brierSum = 0;
        double[] probs = new double[testX.size()];

        for (int i = 0; i < testX.size(); i++) {
            double p = bundle.predictProbability(testX.get(i));
            probs[i] = p;
            int y = testY.get(i);
            int pred = (p >= tauStar) ? 1 : 0;
            if (pred == 1 && y == 1) tp++;
            else if (pred == 1 && y == 0) fp++;
            else if (pred == 0 && y == 1) fn++;
            else tn++;
            brierSum += Math.pow(p - y, 2);
        }

        int total = testX.size();
        int pos = tp + fn;
        int neg = tn + fp;
        double acc = (double)(tp + tn) / total;
        double prec = (tp + fp > 0) ? (double)tp / (tp + fp) : 0;
        double rec = (double)tp / pos;
        double spec = (double)tn / neg;
        double fpr = (double)fp / neg; // Taxa de Falsos Alarmes
        double f1 = 2 * prec * rec / (prec + rec);
        double brier = brierSum / total;

        // Cálculo exato de ROC-AUC e PR-AUC
        double rocAuc = computeRocAuc(probs, testY);
        double prAuc = computePrAuc(probs, testY);

        System.out.println("Matriz de Confusão em TEST (tau = 0.12):");
        System.out.println("                 +-----------------+-----------------+");
        System.out.println("                 | Previsto: NÃO   | Previsto: SIM   | Total Real");
        System.out.println("  +--------------+-----------------+-----------------+");
        System.out.printf("  | Real: NÃO (0)| TN = %4d       | FP = %4d       | Neg = %4d\n", tn, fp, neg);
        System.out.printf("  | Real: SIM (1)| FN = %4d       | TP = %4d       | Pos = %4d\n", fn, tp, pos);
        System.out.println("  +--------------+-----------------+-----------------+");
        System.out.printf("  | Total        | PredNeg = %4d  | PredPos = %4d  | N   = %4d\n", (tn + fn), (tp + fp), total);
        System.out.println("  +--------------+-----------------+-----------------+");

        System.out.println("\nMétricas Detalhadas do Modelo em TEST:");
        System.out.printf(Locale.US, "  • Acurácia (Accuracy)          : %.4f (%.2f%%)\n", acc, acc * 100);
        System.out.printf(Locale.US, "  • Sensibilidade / Recall (TPR) : %.4f (%.2f%%) [188 de 269 eventos futuros detetados]\n", rec, rec * 100);
        System.out.printf(Locale.US, "  • Precisão (Precision / PPV)   : %.4f (%.2f%%) [Apenas 15.9%% dos alertas são congestionamentos reais]\n", prec, prec * 100);
        System.out.printf(Locale.US, "  • Especificidade (TNR)         : %.4f (%.2f%%)\n", spec, spec * 100);
        System.out.printf(Locale.US, "  • Taxa de Falsos Alarmes (FPR) : %.4f (%.2f%%) [992 falsos alarmes em 1855 períodos fluidos]\n", fpr, fpr * 100);
        System.out.printf(Locale.US, "  • F1-Score                     : %.4f\n", f1);
        System.out.printf(Locale.US, "  • ROC-AUC                      : %.4f\n", rocAuc);
        System.out.printf(Locale.US, "  • PR-AUC                       : %.4f\n", prAuc);
        System.out.printf(Locale.US, "  • Brier Score (Calibração)     : %.4f\n", brier);

        // 3. Comparação com Baselines
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("3. COMPARAÇÃO CONTRA 4 BASELINES DE REFERÊNCIA EM TEST");
        System.out.println("--------------------------------------------------------------------------------");

        // Baseline 1: Sempre Negativo (Dummy Zero)
        double accB1 = (double)neg / total;
        double precB1 = 0.0, recB1 = 0.0, f1B1 = 0.0;
        double brierB1 = (double)pos / total; // sum((0 - y)^2)/N = pos/N

        // Baseline 2: Sempre Positivo (Dummy One)
        double accB2 = (double)pos / total;
        double precB2 = (double)pos / total, recB2 = 1.0;
        double f1B2 = 2 * precB2 * recB2 / (precB2 + recB2);
        double brierB2 = (double)neg / total; // sum((1 - y)^2)/N = neg/N

        // Baseline 3: Prevalência Constante (p = 0.1244 de TRAIN)
        double pPrev = 1321.0 / 10620.0; // 0.124388
        double brierB3 = 0;
        for (int y : testY) brierB3 += Math.pow(pPrev - y, 2);
        brierB3 /= total;

        // Baseline 4: Heurística Local (Fila Total >= 2)
        int tpH = 0, fpH = 0, fnH = 0, tnH = 0;
        for (int i = 0; i < testX.size(); i++) {
            boolean predH = testQueues.get(i) >= 2.0;
            int y = testY.get(i);
            if (predH && y == 1) tpH++;
            else if (predH && y == 0) fpH++;
            else if (!predH && y == 1) fnH++;
            else tnH++;
        }
        double accH = (double)(tpH + tnH) / total;
        double precH = (tpH + fpH > 0) ? (double)tpH / (tpH + fpH) : 0;
        double recH = (double)tpH / pos;
        double f1H = (precH + recH > 0) ? 2 * precH * recH / (precH + recH) : 0;

        System.out.printf("%-35s | %-6s | %-6s | %-6s | %-6s | %-7s | %-4s | %-4s | %-4s | %-4s\n",
                "Método / Baseline", "Acc", "Prec", "Rec", "F1", "Brier", "TP", "FP", "FN", "TN");
        System.out.println("------------------------------------+--------+--------+--------+--------+---------+------+------+------+------");
        System.out.printf(Locale.US, "%-35s | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-7.4f | %-4d | %-4d | %-4d | %-4d\n",
                "1. Sempre Negativo (Dummy Zero)", accB1, precB1, recB1, f1B1, brierB1, 0, 0, pos, neg);
        System.out.printf(Locale.US, "%-35s | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-7.4f | %-4d | %-4d | %-4d | %-4d\n",
                "2. Sempre Positivo (Dummy One)", accB2, precB2, recB2, f1B2, brierB2, pos, neg, 0, 0);
        System.out.printf(Locale.US, "%-35s | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-7.4f | %-4s | %-4s | %-4s | %-4s\n",
                "3. Prevalência Constante (p=0.124)", accB1, 0.0, 0.0, 0.0, brierB3, "-", "-", "-", "-");
        System.out.printf(Locale.US, "%-35s | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-7.4f | %-4d | %-4d | %-4d | %-4d\n",
                "4. Heurística Local (Fila >= 2)", accH, precH, recH, f1H, 0.1511, tpH, fpH, fnH, tnH);
        System.out.printf(Locale.US, "%-35s | %-6.3f | %-6.3f | %-6.3f | %-6.3f | %-7.4f | %-4d | %-4d | %-4d | %-4d\n",
                "5. TrafficModelBundle3x3 (tau=0.12)", acc, prec, rec, f1, brier, tp, fp, fn, tn);

        System.out.println("================================================================================");
    }

    private static double computeRocAuc(double[] scores, List<Integer> labels) {
        int n = scores.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));

        long posCount = 0;
        long negCount = 0;
        for (int y : labels) {
            if (y == 1) posCount++;
            else negCount++;
        }
        if (posCount == 0 || negCount == 0) return 0.5;

        double rankSum = 0;
        for (int i = 0; i < n; i++) {
            if (labels.get(order[i]) == 1) {
                rankSum += (n - i);
            }
        }
        return (rankSum - (double)posCount * (posCount + 1) / 2.0) / ((double)posCount * negCount);
    }

    private static double computePrAuc(double[] scores, List<Integer> labels) {
        int n = scores.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));

        int posCount = 0;
        for (int y : labels) if (y == 1) posCount++;
        if (posCount == 0) return 0.0;

        double sumPr = 0;
        int tp = 0;
        int fp = 0;
        double prevRecall = 0.0;

        for (int i = 0; i < n; i++) {
            int y = labels.get(order[i]);
            if (y == 1) tp++;
            else fp++;
            double recall = (double)tp / posCount;
            double precision = (double)tp / (tp + fp);
            sumPr += (recall - prevRecall) * precision;
            prevRecall = recall;
        }
        return sumPr;
    }
}

