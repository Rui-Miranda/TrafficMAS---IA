package ml;

/**
 * Experiência preliminar da Fase 4 — Etapa 2:
 * Treina o Baseline Maioritário, a Regressão Logística L2 e a Árvore de Decisão CART
 * exclusivamente no conjunto TRAIN e avalia o desempenho em TRAIN e VAL.
 * 
 * GARANTIA ANTI-LEAKAGE:
 * O conjunto TEST não é carregado nem acedido nesta fase.
 */
public class ModelComparisonExperiment {

    public static final String DATASET_CSV_PATH = "data/traffic_disruption_dataset.csv";

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — EXPERIÊNCIA DE MACHINE LEARNING (FASE 4 - ETAPA 2)");
        System.out.println("================================================================================");

        try {
            // 1. Carregar TRAIN e VAL
            System.out.println("[1/4] A carregar partições TRAIN e VAL do dataset...");
            DatasetLoader.SplitData trainData = DatasetLoader.loadSplit(DATASET_CSV_PATH, "TRAIN");
            DatasetLoader.SplitData valData = DatasetLoader.loadSplit(DATASET_CSV_PATH, "VAL");

            System.out.printf("      • TRAIN: %d observações (Features: %d)\n", trainData.size(), trainData.X[0].length);
            System.out.printf("      • VAL:   %d observações (Features: %d)\n", valData.size(), valData.X[0].length);
            System.out.println("      • TEST:  [RESERVADO - NÃO ACEDIDO NA ETAPA 2]\n");

            // 2. Modelo Baseline: Maioria
            System.out.println("[2/4] A treinar e avaliar Baseline de Classe Maioritária...");
            MajorityClassBaseline baseline = new MajorityClassBaseline();
            long t0 = System.currentTimeMillis();
            baseline.train(trainData.X, trainData.y);
            long baselineTrainTime = System.currentTimeMillis() - t0;

            ModelEvaluator.EvaluationMetrics baseTrainMet = ModelEvaluator.evaluate(baseline, trainData.X, trainData.y, baselineTrainTime);
            ModelEvaluator.EvaluationMetrics baseValMet = ModelEvaluator.evaluate(baseline, valData.X, valData.y);

            // 3. Modelo A: Regressão Logística com Regularização L2
            System.out.println("[3/4] A treinar e avaliar Modelo A: Regressão Logística L2...");
            LogisticRegression logReg = new LogisticRegression(0.1, 1500, 0.01, 1e-6, 0.5);
            long t1 = System.currentTimeMillis();
            logReg.train(trainData.X, trainData.y);
            long logRegTrainTime = System.currentTimeMillis() - t1;

            ModelEvaluator.EvaluationMetrics lrTrainMet = ModelEvaluator.evaluate(logReg, trainData.X, trainData.y, logRegTrainTime);
            ModelEvaluator.EvaluationMetrics lrValMet = ModelEvaluator.evaluate(logReg, valData.X, valData.y);

            // 4. Modelo B: Árvore de Decisão CART
            System.out.println("[4/4] A treinar e avaliar Modelo B: Árvore de Decisão CART...");
            DecisionTreeClassifier cart = new DecisionTreeClassifier(5, 10, 5);
            long t2 = System.currentTimeMillis();
            cart.train(trainData.X, trainData.y);
            long cartTrainTime = System.currentTimeMillis() - t2;

            ModelEvaluator.EvaluationMetrics cartTrainMet = ModelEvaluator.evaluate(cart, trainData.X, trainData.y, cartTrainTime);
            ModelEvaluator.EvaluationMetrics cartValMet = ModelEvaluator.evaluate(cart, valData.X, valData.y);

            // 5. Apresentação dos Resultados
            printComparisonTable(baseTrainMet, baseValMet, lrTrainMet, lrValMet, cartTrainMet, cartValMet);

            System.out.println("\n--- DETALHES DE CONVERGÊNCIA DA REGRESSÃO LOGÍSTICA ---");
            System.out.printf("Custo Inicial: %.5f | Custo Final: %.5f | Épocas executadas: %d\n",
                    logReg.getInitialCost(), logReg.getFinalCost(), logReg.getCostHistory().size());
            System.out.println("Pesos aprendidos (normalizados):");
            double[] w = logReg.getWeights();
            for (int j = 0; j < w.length; j++) {
                System.out.printf("  • %-20s: %+.4f\n", DatasetLoader.FEATURE_NAMES[j], w[j]);
            }
            System.out.printf("  • %-20s: %+.4f (Bias)\n", "Intercept", logReg.getBias());

            System.out.println("\n--- DETALHES DA ÁRVORE DE DECISÃO CART ---");
            System.out.printf("Profundidade Efetiva: %d (Limite maxDepth: %d)\n", cart.getTreeDepth(), cart.getMaxDepth());
            if (cart.getRoot() != null && !cart.getRoot().isLeaf) {
                System.out.printf("Divisão na Raiz: %s <= %.2f (Gini Ganho Inicial: %.4f)\n",
                        DatasetLoader.FEATURE_NAMES[cart.getRoot().featureIndex],
                        cart.getRoot().splitValue, cart.getRoot().gini);
            }

            System.out.println("\n--- MATRIZES DE CONFUSÃO EM VALIDAÇÃO (VAL) ---");
            System.out.println("[Baseline Maioritário]");
            printConfusionMatrix(baseValMet);
            System.out.println("\n[Regressão Logística L2]");
            printConfusionMatrix(lrValMet);
            System.out.println("\n[Árvore de Decisão CART]");
            printConfusionMatrix(cartValMet);

        } catch (Exception e) {
            System.err.println("[ERRO] Falha na execução da experiência: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printComparisonTable(
            ModelEvaluator.EvaluationMetrics baseTr, ModelEvaluator.EvaluationMetrics baseVal,
            ModelEvaluator.EvaluationMetrics lrTr, ModelEvaluator.EvaluationMetrics lrVal,
            ModelEvaluator.EvaluationMetrics cartTr, ModelEvaluator.EvaluationMetrics cartVal) {

        System.out.println("\n============================================================================================");
        System.out.println("             QUADRO COMPARATIVO DE DESEMPENHO (TRAIN vs. VALIDAÇÃO)");
        System.out.println("============================================================================================");
        System.out.printf("%-26s | %-12s | %-8s | %-9s | %-8s | %-8s | %-10s\n",
                "Modelo", "Split", "Accuracy", "Precision", "Recall", "F1-Score", "Treino (ms)");
        System.out.println("--------------------------------------------------------------------------------------------");

        printRow("Majority Baseline", "TRAIN", baseTr);
        printRow("Majority Baseline", "VAL", baseVal);
        System.out.println("--------------------------------------------------------------------------------------------");
        printRow("Logistic Regression (L2)", "TRAIN", lrTr);
        printRow("Logistic Regression (L2)", "VAL", lrVal);
        System.out.println("--------------------------------------------------------------------------------------------");
        printRow("Decision Tree CART", "TRAIN", cartTr);
        printRow("Decision Tree CART", "VAL", cartVal);
        System.out.println("============================================================================================");
    }

    private static void printRow(String model, String split, ModelEvaluator.EvaluationMetrics m) {
        System.out.printf("%-26s | %-12s | %7.2f%% | %8.2f%% | %7.2f%% | %8.4f | %6d ms\n",
                model, split, m.accuracy * 100.0, m.precision * 100.0, m.recall * 100.0, m.f1Score, m.trainTimeMs);
    }

    private static void printConfusionMatrix(ModelEvaluator.EvaluationMetrics m) {
        System.out.printf("  TN = %-4d | FP = %-4d  (Total Real 0: %d)\n", m.trueNegatives, m.falsePositives, m.class0Actual);
        System.out.printf("  FN = %-4d | TP = %-4d  (Total Real 1: %d)\n", m.falseNegatives, m.truePositives, m.class1Actual);
        System.out.printf("  Accuracy: %.2f%% | Precision: %.2f%% | Recall: %.2f%% | F1: %.4f\n",
                m.accuracy * 100.0, m.precision * 100.0, m.recall * 100.0, m.f1Score);
    }
}
