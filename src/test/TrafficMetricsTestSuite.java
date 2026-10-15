package test;

import environment.IntersectionSafetyAuthority;
import metrics.TrafficMetricsCollector;
import metrics.TrafficMetricsExporter;
import metrics.TrafficMetricsSnapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Suite de Testes Automatizados do Sistema de Métricas, Instrumentação e Exportação (Fase 5 - Etapa 1).
 *
 * Valida os 15 requisitos estritos de rigor experimental:
 * 1. Contagem exata de travessia;
 * 2. Ausência de dupla contagem (anti-debouncing de travessia);
 * 3. Tempo de espera acumulado exato;
 * 4. Throughput com valores analíticos conhecidos;
 * 5. Comprimento médio das filas;
 * 6. Comprimento máximo de filas;
 * 7. Separação estrita por cruzamento e eixo;
 * 8. Pedido de emergência até concessão de verde (latência de preempção);
 * 9. Contagem única de preempção (idempotência de socorro);
 * 10. Distinção formal entre comando aceite e comando aplicado;
 * 11. Separação entre tempo real e tempo simulado;
 * 12. Exportação CSV e integridade de ficheiros;
 * 13. Conformidade estrita de cabeçalhos e unidades;
 * 14. Reprodutibilidade e determinação dos cálculos;
 * 15. Preservação integral das regras de segurança semafórica e casos extremos (zero dados).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class TrafficMetricsTestSuite {

    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE TESTES DE MÉTRICAS E EXPORTAÇÃO (FASE 5 - ETAPA 1)");
        System.out.println("================================================================================");

        runTest("1. Contagem exata de uma travessia", TrafficMetricsTestSuite::test01_ContagemExataTravessia);
        runTest("2. Ausência de dupla contagem de travessia", TrafficMetricsTestSuite::test02_AusenciaDuplaContagemTravessia);
        runTest("3. Tempo de espera acumulado exato", TrafficMetricsTestSuite::test03_TempoEsperaAcumulado);
        runTest("4. Throughput com valores analíticos conhecidos", TrafficMetricsTestSuite::test04_ThroughputValoresConhecidos);
        runTest("5. Comprimento médio das filas de espera", TrafficMetricsTestSuite::test05_ComprimentoMedioFilas);
        runTest("6. Comprimento máximo das filas de espera", TrafficMetricsTestSuite::test06_ComprimentoMaximoFilas);
        runTest("7. Separação estrita por cruzamento e eixo", TrafficMetricsTestSuite::test07_SeparacaoCruzamentoEEixo);
        runTest("8. Latência de emergência (pedido até verde concedido)", TrafficMetricsTestSuite::test08_LatenciaEmergencia);
        runTest("9. Contagem única de preempção (anti-duplicação)", TrafficMetricsTestSuite::test09_ContagemUnicaPreempcao);
        runTest("10. Distinção entre comando aceite e comando aplicado", TrafficMetricsTestSuite::test10_ComandoAceiteVsAplicado);
        runTest("11. Separação estrita entre tempo real e tempo simulado", TrafficMetricsTestSuite::test11_SeparacaoTempoRealESimulado);
        runTest("12. Exportação CSV e criação de diretórios", TrafficMetricsTestSuite::test12_ExportacaoCSV);
        runTest("13. Validação dos cabeçalhos, unidades e formato N/A", TrafficMetricsTestSuite::test13_CabecalhosUnidadesEFormatoNA);
        runTest("14. Reprodutibilidade estrita dos cálculos", TrafficMetricsTestSuite::test14_ReprodutibilidadeCalculos);
        runTest("15. Preservação de segurança e casos extremos (Zero Divisão)", TrafficMetricsTestSuite::test15_PreservacaoSegurancaECasosExtremos);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.printf(Locale.US, "RESULTADO GLOBAL MÉTRICAS: %d PASSOU | %d FALHOU%n", totalPassed, totalFailed);
        System.out.println("--------------------------------------------------------------------------------");

        if (totalFailed > 0) {
            System.err.println("ERRO: Ocorreram falhas na validação das Métricas da Fase 5!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: Todas as métricas e exportações foram validadas com 100% de precisão!");
            System.exit(0);
        }
    }

    private static void runTest(String testName, TestCase testCase) {
        try {
            TrafficMetricsCollector.reset();
            testCase.execute();
            System.out.println("[PASSOU] " + testName);
            totalPassed++;
        } catch (Throwable t) {
            System.err.println("[FALHOU] " + testName + " -> " + t.getMessage());
            totalFailed++;
        }
    }

    @FunctionalInterface
    interface TestCase {
        void execute() throws Exception;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(double expected, double actual, double epsilon, String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + String.format(Locale.US, " (Esperado: %.4f, Obtido: %.4f)", expected, actual));
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + String.format(Locale.US, " (Esperado: %d, Obtido: %d)", expected, actual));
        }
    }

    // =========================================================================
    // IMPLEMENTAÇÃO DOS 15 TESTES AUTOMATIZADOS
    // =========================================================================

    private static void test01_ContagemExataTravessia() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(10.0);

        boolean recorded = collector.recordCrossing("Carro_01", 0, 0, 4.5);
        assertTrue(recorded, "Falha ao registar primeira travessia legítima.");
        assertEquals(1L, collector.getTotalCrossings(), "Total global de travessias deve ser exatamente 1.");
        assertEquals(1L, collector.getCrossings(0), "Cruzamento 0 deve registar exatamente 1 travessia.");
        assertEquals(1L, collector.getCrossings(0, 0), "Eixo NS do Cruzamento 0 deve registar 1 travessia.");
        assertEquals(0L, collector.getCrossings(1), "Cruzamento 1 não deve ter travessias.");
    }

    private static void test02_AusenciaDuplaContagemTravessia() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(15.0);

        boolean first = collector.recordCrossing("Carro_01", 0, 0, 3.0);
        assertTrue(first, "Primeira travessia deve ser registada.");

        // Tentativa repetida imediata no mesmo instante simulado para o mesmo veículo no mesmo cruzamento
        collector.setSimulatedTime(15.5);
        boolean duplicate = collector.recordCrossing("Carro_01", 0, 0, 3.0);
        assertTrue(!duplicate, "Dupla contagem não foi prevenida pelo coletor de métricas.");
        assertEquals(1L, collector.getTotalCrossings(), "Total de travessias não pode sofrer contagem duplicada.");

        // Após tempo suficiente (> 2.0s simulados), uma nova travessia legítima do mesmo veículo deve ser aceite
        collector.setSimulatedTime(18.0);
        boolean second = collector.recordCrossing("Carro_01", 0, 0, 1.2);
        assertTrue(second, "Segunda travessia legítima após intervalo deve ser aceite.");
        assertEquals(2L, collector.getTotalCrossings(), "Total deve ser 2 após segunda travessia válida.");
    }

    private static void test03_TempoEsperaAcumulado() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(20.0);

        collector.recordCrossing("Carro_A", 0, 0, 5.0);
        collector.setSimulatedTime(25.0);
        collector.recordCrossing("Carro_B", 0, 1, 3.0);
        collector.setSimulatedTime(30.0);
        collector.recordCrossing("Carro_C", 1, 0, 4.0);

        // Média global: (5.0 + 3.0 + 4.0) / 3 = 12.0 / 3 = 4.0 segundos
        assertEquals(4.0, collector.getAverageWaitingTimeSeconds(), 0.001, "Tempo médio global de espera incorreto.");
        // Média Cruzamento 0: (5.0 + 3.0) / 2 = 4.0 segundos
        assertEquals(4.0, collector.getAverageWaitingTimeSeconds(0), 0.001, "Média de espera no Cruzamento 0 incorreta.");
        // Média Cruzamento 1: 4.0 / 1 = 4.0 segundos
        assertEquals(4.0, collector.getAverageWaitingTimeSeconds(1), 0.001, "Média de espera no Cruzamento 1 incorreta.");
        // Cruzamento 2 (sem travessias): deve devolver -1.0 (N/A)
        assertEquals(-1.0, collector.getAverageWaitingTimeSeconds(2), 0.001, "Cruzamento sem travessias deve reportar -1.0 (N/A).");
    }

    private static void test04_ThroughputValoresConhecidos() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        // 120 segundos simulados = 2 minutos = 1/30 hora
        collector.setSimulatedTime(120.0);

        // Regista 10 travessias no Cruzamento 0 e 6 travessias no Cruzamento 1 (Total = 16)
        for (int i = 0; i < 10; i++) {
            collector.setSimulatedTime(10.0 + (i * 10.0));
            collector.recordCrossing("Carro_0_" + i, 0, 0, 2.0);
        }
        for (int i = 0; i < 6; i++) {
            collector.setSimulatedTime(10.0 + (i * 15.0));
            collector.recordCrossing("Carro_1_" + i, 1, 1, 1.5);
        }
        collector.setSimulatedTime(120.0);

        // Global: 16 travessias em 2 minutos = 8.0 veículos/minuto
        assertEquals(8.0, collector.getGlobalThroughputPerMinute(), 0.01, "Throughput global em veic/min incorreto.");
        // Global em veic/hora: 16 / (120/3600) = 16 * 30 = 480 veic/hora
        assertEquals(480.0, collector.getGlobalThroughputPerHour(), 0.01, "Throughput global em veic/hora incorreto.");
        // Cruzamento 0: 10 em 2 min = 5.0 veic/min
        assertEquals(5.0, collector.getThroughputPerMinute(0), 0.01, "Throughput Cruzamento 0 incorreto.");
        // Cruzamento 1: 6 em 2 min = 3.0 veic/min
        assertEquals(3.0, collector.getThroughputPerMinute(1), 0.01, "Throughput Cruzamento 1 incorreto.");
    }

    private static void test05_ComprimentoMedioFilas() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

        // 3 amostras no Cruzamento 0, Eixo NS (axis 0): filas de 2, 4, 6 viaturas -> Média = 4.0
        collector.sampleQueue(0, 0, 2);
        collector.sampleQueue(0, 0, 4);
        collector.sampleQueue(0, 0, 6);

        // 1 amostra no Cruzamento 0, Eixo EW (axis 1): fila de 0 viaturas
        collector.sampleQueue(0, 1, 0);

        // Média Eixo 0 do Cruzamento 0: (2+4+6)/3 = 4.0
        assertEquals(4.0, collector.getAverageQueueLength(0, 0), 0.001, "Média de fila no C0 Eixo 0 incorreta.");
        // Média Cruzamento 0: (2+4+6+0)/4 = 12/4 = 3.0
        assertEquals(3.0, collector.getAverageQueueLength(0), 0.001, "Média de fila total no C0 incorreta.");
    }

    private static void test06_ComprimentoMaximoFilas() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

        collector.sampleQueue(0, 0, 2);
        collector.sampleQueue(0, 0, 7);
        collector.sampleQueue(0, 0, 3);
        collector.sampleQueue(1, 1, 9);
        collector.sampleQueue(1, 1, 4);

        assertEquals(7, collector.getMaximumQueueLength(0), "Máximo no Cruzamento 0 deve ser 7.");
        assertEquals(9, collector.getMaximumQueueLength(1), "Máximo no Cruzamento 1 deve ser 9.");
        assertEquals(9, collector.getMaximumQueueLength(), "Máximo global deve ser 9.");
        assertEquals(0, collector.getMaximumQueueLength(2), "Máximo no Cruzamento 2 sem amostras deve ser 0.");
    }

    private static void test07_SeparacaoCruzamentoEEixo() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(50.0);

        // C1 (0) NS (0)
        collector.recordCrossing("V1", 0, 0, 1.0);
        // C1 (0) EW (1)
        collector.recordCrossing("V2", 0, 1, 2.0);
        // C4 (3) EW (1)
        collector.recordCrossing("V3", 3, 1, 3.0);

        assertEquals(1L, collector.getCrossings(0, 0), "C1 Eixo NS deve ter 1 travessia.");
        assertEquals(1L, collector.getCrossings(0, 1), "C1 Eixo EW deve ter 1 travessia.");
        assertEquals(2L, collector.getCrossings(0), "C1 total deve ter 2 travessias.");
        assertEquals(0L, collector.getCrossings(1), "C2 não deve ter travessias.");
        assertEquals(1L, collector.getCrossings(3), "C4 deve ter 1 travessia.");
        assertEquals(3L, collector.getTotalCrossings(), "Total global deve ser 3.");
    }

    private static void test08_LatenciaEmergencia() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

        // Pedido de ambulância em t = 10.0s no Cruzamento 0, Eixo 0
        collector.setSimulatedTime(10.0);
        collector.recordEmergencyRequest("Ambulancia_01", 0, 0);

        // Aceite em t = 10.5s
        collector.setSimulatedTime(10.5);
        collector.recordEmergencyAccepted("Ambulancia_01", 0);

        // Verde efetivamente concedido em t = 13.5s (Latência de transição segura: 3.5s)
        collector.setSimulatedTime(13.5);
        collector.recordEmergencyGreenGranted("Ambulancia_01", 0);

        // Desimpedimento em t = 16.0s (Latência total de travessia: 6.0s)
        collector.setSimulatedTime(16.0);
        collector.recordEmergencyCleared("Ambulancia_01", 0);

        assertEquals(1, collector.getEmergencyRequests(), "Total de pedidos deve ser 1.");
        assertEquals(1, collector.getEmergencyPreemptions(), "Total de preempções concluídas deve ser 1.");
        assertEquals(3.5, collector.getAverageEmergencyGreenDelaySeconds(), 0.001, "Atraso médio pedido->verde deve ser 3.5s.");
        assertEquals(6.0, collector.getAverageEmergencyClearDelaySeconds(), 0.001, "Atraso médio pedido->desimpedimento deve ser 6.0s.");
    }

    private static void test09_ContagemUnicaPreempcao() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(10.0);

        // Pedido inicial
        collector.recordEmergencyRequest("Ambulancia_INEM", 1, 1);

        // Reenvio FIPA devido a timeout preventivo do agente viatura (mesma emergência)
        collector.setSimulatedTime(12.0);
        collector.recordEmergencyRequest("Ambulancia_INEM", 1, 1);

        // Concessão de verde
        collector.setSimulatedTime(14.0);
        collector.recordEmergencyGreenGranted("Ambulancia_INEM", 1);

        // Callback repetido não deve duplicar preempção
        collector.recordEmergencyGreenGranted("Ambulancia_INEM", 1);

        assertEquals(1, collector.getEmergencyRequests(), "Reenvio de pedido não pode inflacionar pedidos.");
        assertEquals(1, collector.getEmergencyPreemptions(), "Callback repetido não pode duplicar preempções.");
    }

    private static void test10_ComandoAceiteVsAplicado() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(10.0);

        // Emite comando coordenado
        collector.recordCoordinationIssued("CMD_ML_01", 0, "BALANCED", 6000);
        assertEquals(1, collector.getCoordinationCommandsIssued(), "Comando emitido deve ser registado.");
        assertEquals(0, collector.getCoordinationCommandsAccepted(), "Ainda não deve constar como aceite.");
        assertEquals(0, collector.getCoordinationCommandsApplied(), "Ainda não deve constar como aplicado.");

        // Semáforo responde AGREE (aceite para execução)
        collector.setSimulatedTime(10.2);
        collector.recordCoordinationAccepted("CMD_ML_01", 0);
        assertEquals(1, collector.getCoordinationCommandsAccepted(), "Comando deve constar como aceite.");
        assertEquals(0, collector.getCoordinationCommandsApplied(), "Aceite NÃO equivale a aplicado.");

        // Autoridade aplica efetivamente o verde coordenado
        collector.setSimulatedTime(12.0);
        collector.recordCoordinationApplied("CMD_ML_01", 0);
        assertEquals(1, collector.getCoordinationCommandsApplied(), "Comando deve constar como efetivamente aplicado.");
        assertEquals(1.0, collector.getCoordinationAdherenceRate(), 0.001, "Taxa de aderência deve ser 100%.");

        // Emite segundo comando mas revoga por emergência
        collector.recordCoordinationIssued("CMD_ML_02", 0, "BALANCED", 6000);
        collector.recordCoordinationPreempted("CMD_ML_02", 0, "PREEMPTED_BY_EMERGENCY");
        assertEquals(2, collector.getCoordinationCommandsIssued(), "Total de comandos emitidos deve ser 2.");
        assertEquals(1, collector.getCoordinationCommandsApplied(), "Total de comandos aplicados deve manter-se em 1.");
        assertEquals(1, collector.getCoordinationCommandsPreempted(), "Comandos revogados deve ser 1.");
        // Taxa de aderência: 1 aplicado / 2 emitidos = 0.50
        assertEquals(0.50, collector.getCoordinationAdherenceRate(), 0.001, "Taxa de aderência deve ser 50%.");
    }

    private static void test11_SeparacaoTempoRealESimulado() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

        // Fixa tempo simulado independentemente da hora do sistema
        collector.setSimulatedTime(0.0);
        collector.advanceSimulatedTime(60.0); // Avança 60s simulados em ~0ms de parede

        collector.recordCrossing("V_Fast", 0, 0, 5.0);

        // Throughput simulado: 1 travessia em 60s = 1.0 veic/min
        assertEquals(60.0, collector.getSimulatedDurationSeconds(), 0.001, "Duração simulada deve ser exatamente 60s.");
        assertEquals(1.0, collector.getGlobalThroughputPerMinute(), 0.001, "Throughput deve basear-se no tempo simulado.");
        assertTrue(collector.getRealDurationSeconds() < 1.0, "Tempo de parede deve ser residual em execução de teste.");
    }

    private static void test12_ExportacaoCSV() throws Exception {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
        collector.setSimulatedTime(60.0);
        collector.recordCrossing("Carro_01", 0, 0, 4.0);
        collector.recordEmergencyRequest("Amb_01", 0, 0);
        collector.recordEmergencyGreenGranted("Amb_01", 0);
        collector.sampleQueue(0, 0, 3);

        TrafficMetricsSnapshot snapshot = collector.getSnapshot("RUN_TEST_CSV_01", "INTENSE", "D_ML", 42L);

        File testDir = new File("results_test");
        File summaryFile = new File(testDir, "test_summary.csv");
        File interFile = new File(testDir, "test_intersections.csv");

        if (summaryFile.exists()) summaryFile.delete();
        if (interFile.exists()) interFile.delete();

        TrafficMetricsExporter.exportRunSummary(summaryFile, snapshot, false);
        TrafficMetricsExporter.exportIntersectionMetrics(interFile, snapshot, false);

        assertTrue(summaryFile.exists() && summaryFile.length() > 0, "Ficheiro de resumo global não foi criado.");
        assertTrue(interFile.exists() && interFile.length() > 0, "Ficheiro de métricas por cruzamento não foi criado.");

        // Valida contagem de linhas
        try (BufferedReader br = new BufferedReader(new FileReader(summaryFile))) {
            String header = br.readLine();
            String row = br.readLine();
            assertTrue(header != null && header.startsWith("runId,scenario"), "Cabeçalho de resumo inválido.");
            assertTrue(row != null && row.startsWith("RUN_TEST_CSV_01,INTENSE,D_ML,42"), "Linha de dados de resumo incorreta.");
        }

        try (BufferedReader br = new BufferedReader(new FileReader(interFile))) {
            String header = br.readLine();
            assertTrue(header != null && header.startsWith("runId,scenario"), "Cabeçalho por cruzamento inválido.");
            int count = 0;
            while (br.readLine() != null) count++;
            assertEquals(4, count, "Ficheiro por cruzamento deve ter exatamente 4 linhas de dados.");
        }

        // Limpeza dos ficheiros de teste
        summaryFile.delete();
        interFile.delete();
        testDir.delete();
    }

    private static void test13_CabecalhosUnidadesEFormatoNA() {
        // Testa a formatação explícita de ausência de dados (N/A)
        String naFormatted = TrafficMetricsSnapshot.formatDecimalOrNA(-1.0, "%.2f");
        assertTrue("N/A".equals(naFormatted), "Valor ausente (-1.0) deve ser formatado como 'N/A'.");

        String nanFormatted = TrafficMetricsSnapshot.formatDecimalOrNA(Double.NaN, "%.2f");
        assertTrue("N/A".equals(nanFormatted), "Double.NaN deve ser formatado como 'N/A'.");

        String validFormatted = TrafficMetricsSnapshot.formatDecimalOrNA(12.345, "%.2f");
        assertTrue("12.35".equals(validFormatted), "Valor numérico legítimo deve ser formatado corretamente.");

        // Valida campos do cabeçalho oficial
        assertTrue(TrafficMetricsExporter.RUN_SUMMARY_HEADER.contains("throughputVehiclesPerMinute"), "Cabeçalho deve conter throughputVehiclesPerMinute.");
        assertTrue(TrafficMetricsExporter.RUN_SUMMARY_HEADER.contains("averageEmergencyGreenDelaySeconds"), "Cabeçalho deve conter averageEmergencyGreenDelaySeconds.");
        assertTrue(TrafficMetricsExporter.INTERSECTION_METRICS_HEADER.contains("intersectionName"), "Cabeçalho granular deve conter intersectionName.");
    }

    private static void test14_ReprodutibilidadeCalculos() {
        TrafficMetricsCollector.reset();
        TrafficMetricsCollector colA = TrafficMetricsCollector.getInstance();
        colA.setSimulatedTime(100.0);
        colA.recordCrossing("V1", 0, 0, 5.0);
        colA.recordCrossing("V2", 1, 1, 3.0);
        TrafficMetricsSnapshot sA = colA.getSnapshot("R1", "NORMAL", "B", 100L);

        TrafficMetricsCollector.reset();
        TrafficMetricsCollector colB = TrafficMetricsCollector.getInstance();
        colB.setSimulatedTime(100.0);
        colB.recordCrossing("V1", 0, 0, 5.0);
        colB.recordCrossing("V2", 1, 1, 3.0);
        TrafficMetricsSnapshot sB = colB.getSnapshot("R1", "NORMAL", "B", 100L);

        assertEquals(sA.totalCrossings, sB.totalCrossings, "Reprodutibilidade: total de travessias deve ser idêntico.");
        assertEquals(sA.averageWaitingTimeSeconds, sB.averageWaitingTimeSeconds, 0.0001, "Reprodutibilidade: tempo médio de espera idêntico.");
        assertEquals(sA.throughputVehiclesPerMinute, sB.throughputVehiclesPerMinute, 0.0001, "Reprodutibilidade: throughput idêntico.");
    }

    private static void test15_PreservacaoSegurancaECasosExtremos() {
        TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

        // 1. Cenário extremo vazio (zero veículos, zero travessias, zero emergências)
        collector.setSimulatedTime(0.0);
        TrafficMetricsSnapshot emptySnapshot = collector.getSnapshot("EMPTY_RUN", "TEST", "A", 1L);

        assertEquals(0L, emptySnapshot.totalCrossings, "Travessias devem ser 0.");
        assertEquals(-1.0, emptySnapshot.averageWaitingTimeSeconds, 0.001, "Sem travessias, tempo médio de espera deve ser -1.0 (N/A).");
        assertEquals(0.0, emptySnapshot.averageQueueLength, 0.001, "Fila média vazia deve ser 0.0.");
        assertEquals(-1.0, emptySnapshot.averageEmergencyGreenDelaySeconds, 0.001, "Sem emergências, atraso deve ser -1.0 (N/A).");
        assertEquals(-1.0, emptySnapshot.coordinationAdherenceRate, 0.001, "Sem comandos, taxa de aderência deve ser -1.0 (N/A).");

        // 2. Preservação integral do invariante de segurança física dos semáforos
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        boolean safe = (auth.getCurrentPhase() != null);
        assertTrue(safe, "A autoridade de segurança deve manter as fases atómicas válidas sob instrumentação.");
    }
}
