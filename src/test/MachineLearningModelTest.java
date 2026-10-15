package test;

import ml.*;
import java.util.Arrays;
import java.util.List;

/**
 * Suite de Testes Unitários e de Integração para os Modelos de Machine Learning (Fase 4 - Etapa 2).
 * Valida os 14 requisitos formais especificados:
 *  1. Aprendizagem da Regressão Logística num dataset conhecido;
 *  2. Redução consistente da função de custo (BCE + L2);
 *  3. Estabilidade numérica da função Sigmoid;
 *  4. Regularização L2 e atenuação dos pesos;
 *  5. Aprendizagem da Árvore de Decisão CART num dataset conhecido;
 *  6. Seleção de divisões e cálculo exato da Impureza de Gini;
 *  7. Respeito estrito pelo limite de profundidade (maxDepth);
 *  8. Intervalo válido de probabilidades [0.0, 1.0];
 *  9. Cálculo analítico exato de Accuracy, Precision, Recall e F1;
 * 10. Integridade matemática da Matriz de Confusão;
 * 11. Comportamento do Classificador Baseline de Classe Maioritária;
 * 12. Normalização StandardScaler sem fuga de dados (anti-leakage);
 * 13. Reprodutibilidade e determinismo estrito;
 * 14. Tratamento robusto de entradas inválidas e exceções.
 */
public class MachineLearningModelTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE TESTES DE MACHINE LEARNING (FASE 4 - ETAPA 2)");
        System.out.println("================================================================================");

        test1_LogisticRegressionLearningOnKnownData();
        test2_CostFunctionReduction();
        test3_NumericalStabilitySigmoid();
        test4_L2RegularizationWeightShrinkage();
        test5_DecisionTreeLearningOnKnownData();
        test6_GiniImpurityAndSplitSelection();
        test7_DecisionTreeMaxDepthLimit();
        test8_ValidProbabilityInterval();
        test9_MetricFormulasCorrectness();
        test10_ConfusionMatrixIntegrity();
        test11_MajorityClassBaseline();
        test12_StandardScalerAntiLeakage();
        test13_ModelReproducibility();
        test14_InvalidInputHandling();

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.printf("RESULTADO GLOBAL ML: %d PASSOU | %d FALHOU\n", testsPassed, testsFailed);
        System.out.println("--------------------------------------------------------------------------------");

        if (testsFailed == 0) {
            System.out.println("SUCESSO: Todos os modelos e avaliadores de ML foram validados com êxito!\n");
            System.exit(0);
        } else {
            System.err.println("ERRO: Ocorreram falhas na validação dos modelos de ML!\n");
            System.exit(1);
        }
    }

    private static void assertTrue(String testName, boolean condition, String errorMsg) {
        System.out.printf("[TESTE] %-60s ... ", testName);
        if (condition) {
            System.out.println("PASSOU ✓");
            testsPassed++;
        } else {
            System.out.println("FALHOU ✗ (" + errorMsg + ")");
            testsFailed++;
        }
    }

    // 1. Aprendizagem da regressão logística num dataset simples conhecido
    private static void test1_LogisticRegressionLearningOnKnownData() {
        // Dataset linearmente separável 2D: x1 + x2 > 0 => 1, else 0
        double[][] X = {
            {2.0, 3.0}, {3.0, 1.0}, {1.5, 2.5}, {4.0, 2.0}, {2.0, 1.0},
            {-2.0, -3.0}, {-3.0, -1.0}, {-1.5, -2.5}, {-4.0, -2.0}, {-2.0, -1.0}
        };
        int[] y = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0};

        LogisticRegression lr = new LogisticRegression(0.2, 500, 0.001, 1e-6, 0.5);
        lr.train(X, y);

        int[] preds = lr.predict(X);
        boolean correct = Arrays.equals(preds, y);
        assertTrue("1. Aprendizagem de Regressão Logística em Dados Conhecidos", correct, "Predições não coincidem a 100%");
    }

    // 2. Redução da função de custo
    private static void test2_CostFunctionReduction() {
        double[][] X = {
            {1.0}, {2.0}, {3.0}, {4.0}, {5.0},
            {-1.0}, {-2.0}, {-3.0}, {-4.0}, {-5.0}
        };
        int[] y = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0};

        LogisticRegression lr = new LogisticRegression(0.1, 300, 0.0, 1e-6, 0.5);
        lr.train(X, y);

        List<Double> costs = lr.getCostHistory();
        boolean reduced = costs.size() > 1 && costs.get(costs.size() - 1) < costs.get(0);
        double costDrop = costs.get(0) - costs.get(costs.size() - 1);
        assertTrue("2. Redução da Função de Custo durante o Treino", reduced && costDrop > 0.2, 
                "Custo não reduziu significativamente");
    }

    // 3. Estabilidade numérica
    private static void test3_NumericalStabilitySigmoid() {
        double sExtNeg = LogisticRegression.stableSigmoid(-1000.0);
        double sExtPos = LogisticRegression.stableSigmoid(1000.0);
        double sZero = LogisticRegression.stableSigmoid(0.0);

        boolean stable = !Double.isNaN(sExtNeg) && !Double.isInfinite(sExtNeg) && sExtNeg == 0.0
                      && !Double.isNaN(sExtPos) && !Double.isInfinite(sExtPos) && sExtPos == 1.0
                      && Math.abs(sZero - 0.5) < 1e-9;

        assertTrue("3. Estabilidade Numérica da Função Sigmoid (Overflow-Proof)", stable, "Sigmoid instável em extremos");
    }

    // 4. Regularização L2
    private static void test4_L2RegularizationWeightShrinkage() {
        double[][] X = {
            {1.0, 2.0}, {2.0, 3.0}, {3.0, 4.0},
            {-1.0, -2.0}, {-2.0, -3.0}, {-3.0, -4.0}
        };
        int[] y = {1, 1, 1, 0, 0, 0};

        LogisticRegression lrNoReg = new LogisticRegression(0.1, 500, 0.0, 1e-7, 0.5);
        lrNoReg.train(X, y);

        LogisticRegression lrHighReg = new LogisticRegression(0.1, 500, 5.0, 1e-7, 0.5);
        lrHighReg.train(X, y);

        double normNoReg = 0.0;
        for (double w : lrNoReg.getWeights()) normNoReg += w * w;

        double normHighReg = 0.0;
        for (double w : lrHighReg.getWeights()) normHighReg += w * w;

        boolean shrinkage = normHighReg < normNoReg;
        assertTrue("4. Regularização L2 com Atenuação dos Pesos (Weight Decay)", shrinkage, 
                "Pesos regularizados não são menores");
    }

    // 5. Aprendizagem de uma árvore de decisão
    private static void test5_DecisionTreeLearningOnKnownData() {
        // Separação em patamares
        double[][] X = {
            {10.0, 1.0}, {12.0, 2.0}, {15.0, 1.5},
            {1.0, 0.5}, {2.0, 1.0}, {0.5, 0.2}
        };
        int[] y = {1, 1, 1, 0, 0, 0};

        DecisionTreeClassifier dt = new DecisionTreeClassifier(4, 2, 1);
        dt.train(X, y);

        int[] preds = dt.predict(X);
        boolean correct = Arrays.equals(preds, y);
        assertTrue("5. Aprendizagem da Árvore CART em Dados Conhecidos", correct, "CART não acertou 100%");
    }

    // 6. Seleção de divisões e cálculo de Gini
    private static void test6_GiniImpurityAndSplitSelection() {
        double giniPure = DecisionTreeClassifier.computeGini(10, 0);
        double giniEqual = DecisionTreeClassifier.computeGini(10, 10);
        double giniAsym = DecisionTreeClassifier.computeGini(3, 7);

        boolean giniOk = Math.abs(giniPure - 0.0) < 1e-9
                      && Math.abs(giniEqual - 0.5) < 1e-9
                      && Math.abs(giniAsym - (1.0 - (0.09 + 0.49))) < 1e-9;

        assertTrue("6. Seleção de Divisões e Cálculo da Impureza de Gini", giniOk, "Gini calculado incorretamente");
    }

    // 7. Limite de profundidade
    private static void test7_DecisionTreeMaxDepthLimit() {
        // Dataset com múltiplos degraus que poderiam gerar árvore profunda
        double[][] X = new double[20][1];
        int[] y = new int[20];
        for (int i = 0; i < 20; i++) {
            X[i][0] = i;
            y[i] = (i % 2 == 0) ? 1 : 0;
        }

        int maxD = 2;
        DecisionTreeClassifier dt = new DecisionTreeClassifier(maxD, 2, 1);
        dt.train(X, y);

        boolean depthOk = dt.getTreeDepth() <= maxD;
        assertTrue("7. Respeito pelo Limite de Profundidade (maxDepth = 2)", depthOk, 
                "Profundidade excedeu maxDepth: " + dt.getTreeDepth());
    }

    // 8. Previsões de probabilidades válidas
    private static void test8_ValidProbabilityInterval() {
        double[][] X = {
            {1.0, 5.0}, {2.0, 6.0}, {-3.0, -1.0}, {-4.0, -2.0}
        };
        int[] y = {1, 1, 0, 0};

        LogisticRegression lr = new LogisticRegression(0.1, 100, 0.01, 1e-5, 0.5);
        lr.train(X, y);

        DecisionTreeClassifier dt = new DecisionTreeClassifier(3, 2, 1);
        dt.train(X, y);

        boolean probsOk = true;
        for (double[] x : X) {
            double pLr = lr.predictProbability(x);
            double pDt = dt.predictProbability(x);
            if (pLr < 0.0 || pLr > 1.0 || pDt < 0.0 || pDt > 1.0) {
                probsOk = false;
                break;
            }
        }

        assertTrue("8. Previsão de Probabilidades no Intervalo Válido [0.0, 1.0]", probsOk, "Probabilidade fora de limites");
    }

    // 9. Cálculo correto de Accuracy, Precision, Recall e F1
    private static void test9_MetricFormulasCorrectness() {
        // Modelo dummy mock para forçar matriz com:
        // TN = 3, FP = 1, FN = 2, TP = 4 (Total = 10)
        double[][] X = new double[10][1];
        int[] yReal = {0, 0, 0, 0, 1, 1, 1, 1, 1, 1};
        final int[] preds = {0, 0, 0, 1, 0, 0, 1, 1, 1, 1};

        Classifier mockModel = new Classifier() {
            @Override public void train(double[][] X, int[] y) {}
            @Override public int predict(double[] x) { return 0; }
            @Override public double predictProbability(double[] x) { return 0.5; }
            @Override public int[] predict(double[][] X) { return preds; }
            @Override public String getModelName() { return "Mock"; }
        };

        ModelEvaluator.EvaluationMetrics m = ModelEvaluator.evaluate(mockModel, X, yReal);

        // Accuracy = (4 + 3) / 10 = 0.70
        // Precision = 4 / (4 + 1) = 0.80
        // Recall = 4 / (4 + 2) = 4 / 6 ≈ 0.6667
        // F1 = 2 * (0.80 * 0.6667) / (0.80 + 0.6667) = 1.0667 / 1.4667 ≈ 0.7273
        boolean metricsOk = Math.abs(m.accuracy - 0.70) < 1e-4
                         && Math.abs(m.precision - 0.80) < 1e-4
                         && Math.abs(m.recall - (4.0 / 6.0)) < 1e-4
                         && Math.abs(m.f1Score - (2.0 * 0.8 * (4.0/6.0) / (0.8 + (4.0/6.0)))) < 1e-4;

        assertTrue("9. Fórmulas Analíticas de Accuracy, Precision, Recall e F1", metricsOk, "Cálculo de métricas divergente");
    }

    // 10. Matriz de confusão
    private static void test10_ConfusionMatrixIntegrity() {
        double[][] X = new double[10][1];
        int[] yReal = {0, 0, 0, 0, 1, 1, 1, 1, 1, 1};
        final int[] preds = {0, 0, 0, 1, 0, 0, 1, 1, 1, 1};

        Classifier mockModel = new Classifier() {
            @Override public void train(double[][] X, int[] y) {}
            @Override public int predict(double[] x) { return 0; }
            @Override public double predictProbability(double[] x) { return 0.5; }
            @Override public int[] predict(double[][] X) { return preds; }
            @Override public String getModelName() { return "Mock"; }
        };

        ModelEvaluator.EvaluationMetrics m = ModelEvaluator.evaluate(mockModel, X, yReal);
        boolean cmOk = (m.trueNegatives == 3) && (m.falsePositives == 1)
                    && (m.falseNegatives == 2) && (m.truePositives == 4)
                    && (m.trueNegatives + m.falsePositives + m.falseNegatives + m.truePositives == 10);

        assertTrue("10. Contagens e Invariante da Matriz de Confusão", cmOk, "Matriz de confusão inconsistente");
    }

    // 11. Baseline de classe maioritária
    private static void test11_MajorityClassBaseline() {
        double[][] X = new double[10][2];
        int[] y = {1, 1, 1, 1, 1, 1, 1, 0, 0, 0}; // 70% classe 1

        MajorityClassBaseline base = new MajorityClassBaseline();
        base.train(X, y);

        boolean baseOk = (base.getMajorityClass() == 1)
                      && Math.abs(base.getMajorityProbability() - 0.70) < 1e-9
                      && base.predict(new double[]{99.0, 99.0}) == 1;

        ModelEvaluator.EvaluationMetrics m = ModelEvaluator.evaluate(base, X, y);
        baseOk = baseOk && Math.abs(m.accuracy - 0.70) < 1e-9;

        assertTrue("11. Classificador Baseline de Classe Maioritária", baseOk, "Baseline com comportamento inesperado");
    }

    // 12. Normalização sem fuga de dados
    private static void test12_StandardScalerAntiLeakage() {
        double[][] XTrain = {
            {10.0, 100.0},
            {20.0, 200.0},
            {30.0, 300.0}
        };
        // Média de XTrain: [20.0, 200.0]
        // Std de XTrain: sqrt(((100 + 0 + 100) / 3)) = sqrt(66.6667) ≈ 8.1649

        StandardScaler scaler = new StandardScaler();
        scaler.fit(XTrain);
        double[] meanTrain = scaler.getMean();
        double[] stdTrain = scaler.getStd();

        // Aplicar a conjunto de validação com distribuição diferente
        double[][] XVal = {
            {1000.0, 5000.0},
            {2000.0, 6000.0}
        };
        double[][] XValNorm = scaler.transform(XVal);

        // Garantir que as estatísticas internas do scaler não sofreram mutação
        boolean noLeak = Arrays.equals(scaler.getMean(), meanTrain)
                      && Arrays.equals(scaler.getStd(), stdTrain)
                      && (XValNorm.length == 2);

        assertTrue("12. Normalização StandardScaler sem Fuga de Dados (Anti-Leakage)", noLeak, 
                "Scaler sofreu contaminação de validação");
    }

    // 13. Reprodutibilidade com sementes fixas
    private static void test13_ModelReproducibility() {
        double[][] X = {
            {1.0, 2.0}, {3.0, 4.0}, {5.0, 6.0},
            {-1.0, -2.0}, {-3.0, -4.0}, {-5.0, -6.0}
        };
        int[] y = {1, 1, 1, 0, 0, 0};

        LogisticRegression lr1 = new LogisticRegression(0.1, 200, 0.01, 1e-6, 0.5);
        lr1.train(X, y);

        LogisticRegression lr2 = new LogisticRegression(0.1, 200, 0.01, 1e-6, 0.5);
        lr2.train(X, y);

        boolean reprod = Arrays.equals(lr1.getWeights(), lr2.getWeights())
                      && Math.abs(lr1.getBias() - lr2.getBias()) < 1e-12;

        assertTrue("13. Reprodutibilidade e Determinismo Estrito nos Modelos", reprod, "Modelos idênticos divergiram");
    }

    // 14. Tratamento de entradas inválidas
    private static void test14_InvalidInputHandling() {
        boolean caughtDim = false;
        boolean caughtUntrained = false;

        LogisticRegression lr = new LogisticRegression();
        try {
            lr.predict(new double[]{1.0, 2.0});
        } catch (IllegalStateException e) {
            caughtUntrained = true;
        }

        try {
            lr.train(new double[5][2], new int[3]); // Dimensões X e y incompatíveis
        } catch (IllegalArgumentException e) {
            caughtDim = true;
        }

        boolean handlingOk = caughtDim && caughtUntrained;
        assertTrue("14. Tratamento Robusto de Entradas Inválidas e Exceções", handlingOk, 
                "Exceções não foram devidamente capturadas");
    }
}
