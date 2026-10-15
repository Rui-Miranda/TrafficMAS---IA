package ml;

/**
 * Sistema Unificado de Avaliação Estatística para Classificadores Binários no TrafficMAS.
 * Calcula métricas canónicas: Accuracy, Precision, Recall, F1-Score, Matriz de Confusão
 * e telemetria temporal de treino e inferência, com proteção contra divisões por zero.
 */
public class ModelEvaluator {

    /**
     * Registo de métricas resultantes da avaliação.
     */
    public static class EvaluationMetrics {
        public final String modelName;
        public final int totalSamples;
        public final int class0Actual;
        public final int class1Actual;
        public final int class0Predicted;
        public final int class1Predicted;

        // Matriz de Confusão
        public final int trueNegatives;   // TN: Real=0, Previsto=0
        public final int falsePositives;  // FP: Real=0, Previsto=1 (Erro Tipo I)
        public final int falseNegatives;  // FN: Real=1, Previsto=0 (Erro Tipo II)
        public final int truePositives;   // TP: Real=1, Previsto=1

        // Métricas de Desempenho
        public final double accuracy;
        public final double precision;
        public final double recall;
        public final double f1Score;

        // Telemetria Temporal
        public final long trainTimeMs;
        public final long inferenceTimeMs;
        public final double avgInferenceMicrosPerSample;

        public EvaluationMetrics(String modelName, int totalSamples, int class0Actual, int class1Actual,
                                 int class0Predicted, int class1Predicted, int tn, int fp, int fn, int tp,
                                 double accuracy, double precision, double recall, double f1Score,
                                 long trainTimeMs, long inferenceTimeMs, double avgInferenceMicrosPerSample) {
            this.modelName = modelName;
            this.totalSamples = totalSamples;
            this.class0Actual = class0Actual;
            this.class1Actual = class1Actual;
            this.class0Predicted = class0Predicted;
            this.class1Predicted = class1Predicted;
            this.trueNegatives = tn;
            this.falsePositives = fp;
            this.falseNegatives = fn;
            this.truePositives = tp;
            this.accuracy = accuracy;
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.trainTimeMs = trainTimeMs;
            this.inferenceTimeMs = inferenceTimeMs;
            this.avgInferenceMicrosPerSample = avgInferenceMicrosPerSample;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Avaliação: %s ===\n", modelName));
            sb.append(String.format("Total de Amostras: %d (Classe 0: %d | Classe 1: %d)\n", totalSamples, class0Actual, class1Actual));
            sb.append(String.format("Matriz de Confusão:\n"));
            sb.append(String.format("                 Previsto 0 (Fluido)   Previsto 1 (Congestionado)\n"));
            sb.append(String.format("  Real 0 (Fluido)        TN = %-6d       FP = %-6d\n", trueNegatives, falsePositives));
            sb.append(String.format("  Real 1 (Congest)       FN = %-6d       TP = %-6d\n", falseNegatives, truePositives));
            sb.append(String.format("Accuracy:  %.4f (%.2f%%)\n", accuracy, accuracy * 100.0));
            sb.append(String.format("Precision: %.4f (%.2f%%)\n", precision, precision * 100.0));
            sb.append(String.format("Recall:    %.4f (%.2f%%)\n", recall, recall * 100.0));
            sb.append(String.format("F1-Score:  %.4f\n", f1Score));
            sb.append(String.format("Tempos: Treino = %d ms | Inferência = %d ms (%.2f µs/amostra)\n",
                    trainTimeMs, inferenceTimeMs, avgInferenceMicrosPerSample));
            return sb.toString();
        }
    }

    /**
     * Avalia um modelo de ML sobre um conjunto de observações X e rótulos reais y.
     */
    public static EvaluationMetrics evaluate(Classifier model, double[][] X, int[] y, long trainTimeMs) {
        if (model == null) {
            throw new IllegalArgumentException("Modelo de classificação nulo.");
        }
        if (X == null || y == null || X.length == 0) {
            throw new IllegalArgumentException("Conjunto de avaliação nulo ou vazio.");
        }
        if (X.length != y.length) {
            throw new IllegalArgumentException("Dimensões incompatíveis entre X e y na avaliação.");
        }

        int m = X.length;
        long startInf = System.nanoTime();
        int[] preds = model.predict(X);
        long endInf = System.nanoTime();

        long inferenceTimeNanos = endInf - startInf;
        long inferenceTimeMs = inferenceTimeNanos / 1_000_000L;
        double avgInferenceMicros = (double) inferenceTimeNanos / (m * 1000.0);

        int tn = 0, fp = 0, fn = 0, tp = 0;
        int c0Actual = 0, c1Actual = 0;
        int c0Pred = 0, c1Pred = 0;

        for (int i = 0; i < m; i++) {
            int actual = y[i];
            int pred = preds[i];

            if (actual == 1) c1Actual++; else c0Actual++;
            if (pred == 1) c1Pred++; else c0Pred++;

            if (actual == 0 && pred == 0) {
                tn++;
            } else if (actual == 0 && pred == 1) {
                fp++;
            } else if (actual == 1 && pred == 0) {
                fn++;
            } else if (actual == 1 && pred == 1) {
                tp++;
            }
        }

        double accuracy = (double) (tp + tn) / m;

        // Proteção estrita contra divisão por zero
        double precision = (tp + fp > 0) ? (double) tp / (tp + fp) : 0.0;
        double recall = (tp + fn > 0) ? (double) tp / (tp + fn) : 0.0;
        double f1 = (precision + recall > 0.0) ? (2.0 * precision * recall) / (precision + recall) : 0.0;

        return new EvaluationMetrics(model.getModelName(), m, c0Actual, c1Actual, c0Pred, c1Pred,
                tn, fp, fn, tp, accuracy, precision, recall, f1, trainTimeMs, inferenceTimeMs, avgInferenceMicros);
    }

    /**
     * Versão de avaliação quando o tempo de treino não é explicitamente monitorizado.
     */
    public static EvaluationMetrics evaluate(Classifier model, double[][] X, int[] y) {
        return evaluate(model, X, y, 0L);
    }
}
