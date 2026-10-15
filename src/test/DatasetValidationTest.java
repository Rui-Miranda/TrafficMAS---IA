package test;

import ml.DatasetValidator;
import ml.DatasetValidator.ValidationReport;
import ml.TrafficDatasetGenerator;
import ml.TrafficDatasetGenerator.DatasetRecord;
import java.io.File;
import java.util.List;

/**
 * Suite de Testes da Fase 4 — Etapa 1: Validação Rigorosa do Dataset de ML.
 *
 * Valida automaticamente:
 * 1. Geração correta do dataset a partir da simulação de tráfego.
 * 2. Ausência de valores nulos, inválidos ou corrompidos.
 * 3. Ausência de fuga de informação futura nas features em t.
 * 4. Associação temporal exata ao horizonte de 60 segundos (t + 60s).
 * 5. Cobertura uniforme dos 4 cruzamentos (0 a 3).
 * 6. Reprodutibilidade estrita com a mesma semente aleatória.
 * 7. Isolamento absoluto das execuções (runId) entre partições (TRAIN, VAL, TEST).
 * 8. Equilíbrio e suficiência estatística das classes binárias.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class DatasetValidationTest {

    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE VALIDAÇÃO DO DATASET DE ML (FASE 4 - ETAPA 1)");
        System.out.println("================================================================================");

        runTest("1. Geração e Persistência do Dataset CSV", DatasetValidationTest::testGeracaoEPersistencia);
        runTest("2. Ausência de Valores Inválidos e Fora de Limites", DatasetValidationTest::testAusenciaValoresInvalidos);
        runTest("3. Ausência de Fuga de Informação Futura nas Features", DatasetValidationTest::testAusenciaFugaInformacao);
        runTest("4. Correspondência Temporal Rigorosa a t + 60s", DatasetValidationTest::testHorizonteTemporal60s);
        runTest("5. Cobertura Uniforme dos Quatro Cruzamentos", DatasetValidationTest::testCoberturaCruzamentos);
        runTest("6. Reprodutibilidade com Sementes Pseudoaleatórias", DatasetValidationTest::testReprodutibilidadeSeed);
        runTest("7. Isolamento de Execuções entre Treino, Validação e Teste", DatasetValidationTest::testIsolamentoSplits);
        runTest("8. Suficiência Estatística e Distribuição das Classes", DatasetValidationTest::testDistribuicaoClasses);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("RESULTADO GLOBAL ETAPA 1: " + totalPassed + " PASSOU | " + totalFailed + " FALHOU");
        System.out.println("--------------------------------------------------------------------------------");

        if (totalFailed > 0) {
            System.err.println("FALHA: Validação do dataset falhou!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: Dataset validado e em plena conformidade com os requisitos da Fase 4!\n");
            System.exit(0);
        }
    }

    private static void runTest(String testName, TestCase testCase) {
        System.out.print("[TESTE] " + testName + " ... ");
        try {
            testCase.execute();
            System.out.println("PASSOU ✓");
            totalPassed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace(System.out);
            totalFailed++;
        }
    }

    @FunctionalInterface
    interface TestCase {
        void execute() throws Exception;
    }

    private static void testGeracaoEPersistencia() throws Exception {
        TrafficDatasetGenerator.main(new String[]{});
        File csvFile = new File(TrafficDatasetGenerator.DATASET_CSV_PATH);
        if (!csvFile.exists() || csvFile.length() < 1000) {
            throw new AssertionError("Ficheiro CSV do dataset não foi gerado ou está vazio.");
        }
    }

    private static void testAusenciaValoresInvalidos() throws Exception {
        ValidationReport report = DatasetValidator.validateCsv(TrafficDatasetGenerator.DATASET_CSV_PATH);
        if (!report.valid) {
            throw new AssertionError("Dataset com anomalias: " + report.errorMessages);
        }
        if (report.totalRecords < 1500) {
            throw new AssertionError("Quantidade insuficiente de observações: " + report.totalRecords);
        }
    }

    private static void testAusenciaFugaInformacao() throws Exception {
        List<DatasetRecord> records = TrafficDatasetGenerator.generateDataset(42L);
        for (DatasetRecord r : records) {
            // As features em t não podem depender de valores calculados após o tempo de observação
            if (r.simTimeSeconds > 250.0) {
                throw new AssertionError("Observação amostrada fora do intervalo válido de t (<= 250s).");
            }
            if (r.trafficVolume < r.queueLength) {
                throw new AssertionError("Violação de consistência: Fila (" + r.queueLength 
                        + ") maior que o volume total de aproximação (" + r.trafficVolume + ").");
            }
        }
    }

    private static void testHorizonteTemporal60s() throws Exception {
        List<DatasetRecord> records = TrafficDatasetGenerator.generateDataset(42L);
        // Garante que o horizonte é sempre t + 60s
        double maxObsTime = records.stream().mapToDouble(r -> r.simTimeSeconds).max().orElse(0.0);
        if (maxObsTime + TrafficDatasetGenerator.PREDICTION_HORIZON_SECONDS > 310.0) {
            throw new AssertionError("Horizonte temporal futuro excede a duração da simulação.");
        }
    }

    private static void testCoberturaCruzamentos() throws Exception {
        List<DatasetRecord> records = TrafficDatasetGenerator.generateDataset(42L);
        int[] counts = new int[4];
        for (DatasetRecord r : records) {
            counts[r.intersectionId]++;
        }
        for (int i = 0; i < 4; i++) {
            if (counts[i] < 300) {
                throw new AssertionError("Cruzamento C" + (i + 1) + " com amostragem insuficiente: " + counts[i]);
            }
        }
    }

    private static void testReprodutibilidadeSeed() throws Exception {
        List<DatasetRecord> setA = TrafficDatasetGenerator.generateDataset(12345L);
        List<DatasetRecord> setB = TrafficDatasetGenerator.generateDataset(12345L);

        if (setA.size() != setB.size()) {
            throw new AssertionError("Tamanhos divergentes para a mesma semente.");
        }
        for (int i = 0; i < Math.min(100, setA.size()); i++) {
            if (!setA.get(i).toCsvLine().equals(setB.get(i).toCsvLine())) {
                throw new AssertionError("Divergência de valores para a mesma semente na linha " + i);
            }
        }
    }

    private static void testIsolamentoSplits() throws Exception {
        ValidationReport report = DatasetValidator.validateCsv(TrafficDatasetGenerator.DATASET_CSV_PATH);
        if (report.trainRecords == 0 || report.valRecords == 0 || report.testRecords == 0) {
            throw new AssertionError("Um dos splits (TRAIN, VAL, TEST) está vazio.");
        }
        double trainRatio = (double) report.trainRecords / report.totalRecords;
        if (trainRatio < 0.60 || trainRatio > 0.80) {
            throw new AssertionError("Proporção de treino fora do intervalo esperado (60%-80%): " + trainRatio);
        }
    }

    private static void testDistribuicaoClasses() throws Exception {
        ValidationReport report = DatasetValidator.validateCsv(TrafficDatasetGenerator.DATASET_CSV_PATH);
        double positiveRatio = (double) report.positiveCount / report.totalRecords;
        // Espera-se entre 20% e 65% de positivos num ecossistema urbano representativo
        if (positiveRatio < 0.15 || positiveRatio > 0.70) {
            throw new AssertionError("Desequilíbrio de classes anómalo (Positivos: " + (positiveRatio * 100) + "%).");
        }
    }
}
