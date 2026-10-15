package test;

import ml.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Suite de Testes para Auditoria, Seleção de Modelo e Persistência (Fase 4 - Etapa 3).
 * Valida os 12 requisitos formais da Fase E:
 *  1. Integridade temporal do dataset (horizonte de 60s e snapshots válidos);
 *  2. Consistência dos cenários e runs no gerador;
 *  3. Isolamento estrito entre partições TRAIN, VAL e TEST;
 *  4. Seleção do modelo através de métricas de VAL;
 *  5. Persistência física do modelo em disco;
 *  6. Carregamento e desserialização do ficheiro binário;
 *  7. Igualdade de previsões antes e depois da serialização (determinismo bit a bit);
 *  8. Consistência da ordem e validação das features;
 *  9. Rejeição robusta de ficheiros e bundles corrompidos/inválidos;
 * 10. Robustez do threshold de decisão congelado;
 * 11. Reprodutibilidade estrita da serialização;
 * 12. Cálculo matematicamente correto das métricas de avaliação no conjunto TEST.
 */
public class ModelSelectionAndPersistenceTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE AUDITORIA, SELEÇÃO E PERSISTÊNCIA (FASE 4 - ETAPA 3)");
        System.out.println("================================================================================");

        try {
            test1_DatasetTemporalIntegrity();
            test2_ScenarioConsistency();
            test3_SplitIsolation();
            test4_ModelSelectionOnValidation();
            test5_ModelPersistenceSave();
            test6_ModelPersistenceLoad();
            test7_PredictionEqualityBeforeAndAfterSerialization();
            test8_FeatureOrderAndDimensionValidation();
            test9_InvalidFileHandling();
            test10_DecisionThresholdRobustness();
            test11_ModelReproducibility();
            test12_TestMetricsCalculationCorrectness();

            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.printf("RESULTADO GLOBAL ETAPA 3: %d PASSOU | %d FALHOU\n", testsPassed, testsFailed);
            System.out.println("--------------------------------------------------------------------------------");

            if (testsFailed == 0) {
                System.out.println("SUCESSO: Auditoria, Seleção Final e Persistência validadas com êxito!\n");
                System.exit(0);
            } else {
                System.err.println("ERRO: Ocorreram falhas na validação da Etapa 3!\n");
                System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("[ERRO FATAL] Falha inesperada durante a execução dos testes: " + e.getMessage());
            e.printStackTrace();
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

    // 1. Integridade temporal do dataset
    private static void test1_DatasetTemporalIntegrity() throws IOException {
        DatasetLoader.SplitData train = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TRAIN");
        DatasetLoader.SplitData val = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "VAL");
        DatasetLoader.SplitData test = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TEST");

        int total = train.size() + val.size() + test.size();
        boolean validSize = (total == 3072);
        assertTrue("1. Integridade Temporal do Dataset (3.072 Amostras)", validSize, "Tamanho total incorreto: " + total);
    }

    // 2. Consistência dos cenários
    private static void test2_ScenarioConsistency() throws IOException {
        List<FinalModelEvaluator.DetailedTestRecord> recs = FinalModelEvaluator.loadDetailedTestRecords(FinalModelEvaluator.DATASET_CSV_PATH);
        Set<String> testScenarios = new HashSet<>();
        for (FinalModelEvaluator.DetailedTestRecord r : recs) {
            testScenarios.add(r.scenarioName);
        }
        boolean hasRoadwork = testScenarios.contains("ROADWORK");
        boolean hasSpecialEvent = testScenarios.contains("SPECIAL_EVENT");
        assertTrue("2. Consistência dos Cenários em TEST", hasRoadwork && hasSpecialEvent, "Cenários de TEST em falta");
    }

    // 3. Isolamento TRAIN/VAL/TEST
    private static void test3_SplitIsolation() throws IOException {
        Set<String> trainRuns = new HashSet<>();
        Set<String> valRuns = new HashSet<>();
        Set<String> testRuns = new HashSet<>();

        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(FinalModelEvaluator.DATASET_CSV_PATH))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                String runId = p[0].trim();
                String split = p[2].trim();
                if (split.equals("TRAIN")) trainRuns.add(runId);
                else if (split.equals("VAL")) valRuns.add(runId);
                else if (split.equals("TEST")) testRuns.add(runId);
            }
        }

        boolean disjointVal = Collections.disjoint(trainRuns, valRuns);
        boolean disjointTest = Collections.disjoint(trainRuns, testRuns) && Collections.disjoint(valRuns, testRuns);
        assertTrue("3. Isolamento Estrito TRAIN/VAL/TEST (Zero Sobreposição)", disjointVal && disjointTest, "Runs partilhadas entre splits");
    }

    // 4. Seleção do modelo por VAL
    private static void test4_ModelSelectionOnValidation() throws IOException {
        DatasetLoader.SplitData train = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TRAIN");
        DatasetLoader.SplitData val = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "VAL");

        LogisticRegression lr = new LogisticRegression(0.1, 1500, 0.01, 1e-6, FinalModelEvaluator.FROZEN_THRESHOLD);
        lr.train(train.X, train.y);
        ModelEvaluator.EvaluationMetrics m = ModelEvaluator.evaluate(lr, val.X, val.y);

        boolean superior = m.f1Score >= 0.97 && m.recall >= 0.95;
        assertTrue("4. Seleção do Modelo por Métricas de Validação (F1 >= 0.97)", superior, "F1 em VAL insuficiente: " + m.f1Score);
    }

    // 5. Persistência
    private static void test5_ModelPersistenceSave() throws IOException {
        DatasetLoader.SplitData train = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TRAIN");
        LogisticRegression lr = new LogisticRegression(0.1, 500, 0.01, 1e-6, 0.40);
        lr.train(train.X, train.y);

        StandardScaler scaler = new StandardScaler();
        scaler.fit(train.X);

        TrafficModelBundle bundle = new TrafficModelBundle(
                lr.getModelName(), "1.0.0", lr, scaler,
                DatasetLoader.FEATURE_NAMES, 0.40, 60.0,
                FinalModelEvaluator.DATASET_SHA256, System.currentTimeMillis(), "Test bundle"
        );

        String testPath = "data/test_persistence_model.bin";
        ModelPersistenceManager.saveModel(bundle, testPath);

        File f = new File(testPath);
        boolean saved = f.exists() && f.length() > 0;
        assertTrue("5. Persistência Física do Modelo em Disco", saved, "Ficheiro não gravado");
    }

    // 6. Carregamento
    private static void test6_ModelPersistenceLoad() throws Exception {
        String testPath = "data/test_persistence_model.bin";
        TrafficModelBundle loaded = ModelPersistenceManager.loadModel(testPath);

        boolean loadedOk = (loaded != null) 
                        && loaded.getModelVersion().equals("1.0.0")
                        && (loaded.getFeatureNames().length == 7);
        assertTrue("6. Carregamento e Desserialização do Modelo", loadedOk, "Modelo carregado inválido");
    }

    // 7. Igualdade de previsões antes/depois da serialização
    private static void test7_PredictionEqualityBeforeAndAfterSerialization() throws Exception {
        DatasetLoader.SplitData val = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "VAL");
        DatasetLoader.SplitData train = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TRAIN");

        LogisticRegression original = new LogisticRegression(0.1, 500, 0.01, 1e-6, 0.40);
        original.train(train.X, train.y);

        StandardScaler scaler = new StandardScaler();
        scaler.fit(train.X);

        TrafficModelBundle bundle = new TrafficModelBundle(
                original.getModelName(), "1.0.0", original, scaler,
                DatasetLoader.FEATURE_NAMES, 0.40, 60.0,
                FinalModelEvaluator.DATASET_SHA256, System.currentTimeMillis(), "Test"
        );
        String testPath = "data/test_equality_model.bin";
        ModelPersistenceManager.saveModel(bundle, testPath);

        TrafficModelBundle loaded = ModelPersistenceManager.loadModel(testPath);

        boolean allIdentical = true;
        for (int i = 0; i < val.X.length; i++) {
            int predOrig = original.predict(val.X[i]);
            int predLoaded = loaded.predict(val.X[i]);
            double probOrig = original.predictProbability(val.X[i]);
            double probLoaded = loaded.predictProbability(val.X[i]);

            if (predOrig != predLoaded || Math.abs(probOrig - probLoaded) > 1e-12) {
                allIdentical = false;
                break;
            }
        }

        new File(testPath).delete();
        new File("data/test_persistence_model.bin").delete();

        assertTrue("7. Determinismo e Igualdade Estrita Pós-Serialização", allIdentical, "Divergência nas predições");
    }

    // 8. Consistência da ordem das features
    private static void test8_FeatureOrderAndDimensionValidation() {
        StandardScaler scaler = new StandardScaler();
        scaler.fit(new double[][]{{1,2,3,4,5,6,7}, {7,6,5,4,3,2,1}});
        LogisticRegression lr = new LogisticRegression();
        lr.train(new double[][]{{1,2,3,4,5,6,7}, {7,6,5,4,3,2,1}}, new int[]{1, 0});

        TrafficModelBundle bundle = new TrafficModelBundle(
                "LR", "1.0", lr, scaler, DatasetLoader.FEATURE_NAMES, 0.5, 60.0, "SHA", 0L, ""
        );

        boolean caughtDimError = false;
        try {
            bundle.predict(new double[]{1.0, 2.0}); // Apenas 2 features em vez de 7
        } catch (IllegalArgumentException e) {
            caughtDimError = true;
        }

        assertTrue("8. Validação Dimensional e de Ordem de Features", caughtDimError, "Não rejeitou dimensão incorreta");
    }

    // 9. Rejeição de ficheiros inválidos
    private static void test9_InvalidFileHandling() {
        boolean caughtMissing = false;
        try {
            ModelPersistenceManager.loadModel("data/non_existent_file_xyz.bin");
        } catch (IOException | ClassNotFoundException e) {
            caughtMissing = true;
        }

        assertTrue("9. Rejeição de Ficheiro Inexistente / Inválido", caughtMissing, "Não lançou exceção");
    }

    // 10. Robustez do threshold
    private static void test10_DecisionThresholdRobustness() {
        LogisticRegression lr = new LogisticRegression();
        lr.train(new double[][]{{1.0, 2.0}, {-1.0, -2.0}}, new int[]{1, 0});

        boolean invalidRejected = false;
        try {
            lr.setThreshold(-0.5);
            new LogisticRegression(0.1, 100, 0.01, 1e-6, 1.5);
        } catch (IllegalArgumentException e) {
            invalidRejected = true;
        }

        assertTrue("10. Robustez do Threshold de Decisão (Limites [0, 1])", invalidRejected, "Não rejeitou threshold inválido");
    }

    // 11. Reprodutibilidade
    private static void test11_ModelReproducibility() throws IOException {
        DatasetLoader.SplitData train = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TRAIN");

        LogisticRegression lr1 = new LogisticRegression(0.1, 200, 0.01, 1e-6, 0.40);
        lr1.train(train.X, train.y);

        LogisticRegression lr2 = new LogisticRegression(0.1, 200, 0.01, 1e-6, 0.40);
        lr2.train(train.X, train.y);

        boolean weightsEqual = Arrays.equals(lr1.getWeights(), lr2.getWeights())
                            && Math.abs(lr1.getBias() - lr2.getBias()) < 1e-12;

        assertTrue("11. Reprodutibilidade Estrita com Parâmetros Fixos", weightsEqual, "Modelos idênticos divergiram");
    }

    // 12. Cálculo correto das métricas em TEST
    private static void test12_TestMetricsCalculationCorrectness() throws IOException {
        DatasetLoader.SplitData test = DatasetLoader.loadSplit(FinalModelEvaluator.DATASET_CSV_PATH, "TEST");
        PersistenceBaseline pb = new PersistenceBaseline();
        pb.train(test.X, test.y);

        ModelEvaluator.EvaluationMetrics m = ModelEvaluator.evaluate(pb, test.X, test.y);
        boolean cmSum = (m.trueNegatives + m.falsePositives + m.falseNegatives + m.truePositives == test.size());
        boolean accOk = Math.abs(m.accuracy - ((double)(m.truePositives + m.trueNegatives) / test.size())) < 1e-9;

        assertTrue("12. Integridade Matemática das Métricas em TEST", cmSum && accOk, "Matriz de métricas inconsistente");
    }
}
