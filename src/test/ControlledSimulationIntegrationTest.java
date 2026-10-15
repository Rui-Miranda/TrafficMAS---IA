package test;

import metrics.TrafficMetricsCollector;
import metrics.TrafficMetricsExporter;
import metrics.TrafficMetricsSnapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Teste de Integração Controlado do Sistema de Métricas (Fase 5 - Etapa 1, Secção 8).
 *
 * Executa um ensaio experimental simulado com parâmetros estritamente determinísticos e cronológicos,
 * gera os ficheiros CSV reais em results/ e afere a paridade exata (bit a bit e valor a valor)
 * entre os dados em memória (TrafficMetricsSnapshot) e o conteúdo dos ficheiros CSV.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class ControlledSimulationIntegrationTest {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — TESTE DE INTEGRAÇÃO CONTROLADO DE MÉTRICAS (FASE 5)");
        System.out.println("================================================================================");

        try {
            TrafficMetricsCollector.reset();
            TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();

            // 1. Configuração do Ensaio Controlado: 300 segundos simulados (5 minutos)
            double simDuration = 300.0;
            collector.setSimulatedTime(0.0);

            // 2. Simulação de 24 Travessias Conhecidas em ordem cronológica estritamente crescente
            // Ciclo 1 (t = 10s a 45s)
            collector.setSimulatedTime(10.0);
            collector.recordCrossing("Carro_01", 0, 0, 2.5);
            collector.recordCrossing("Carro_02", 0, 1, 3.5);

            collector.setSimulatedTime(20.0);
            collector.recordCrossing("Carro_03", 1, 0, 4.0);
            collector.recordCrossing("Carro_04", 1, 1, 6.0);

            collector.setSimulatedTime(30.0);
            collector.recordCrossing("Carro_05", 2, 0, 3.0);
            collector.recordCrossing("Carro_06", 2, 1, 3.0);

            collector.setSimulatedTime(40.0);
            collector.recordCrossing("Carro_01", 3, 0, 2.0);
            collector.recordCrossing("Carro_02", 3, 1, 2.0);

            // Emergência 1 no Cruzamento C1 (t = 50s a 58s)
            collector.setSimulatedTime(50.0);
            collector.recordEmergencyRequest("Ambulancia_INEM_01", 0, 0);
            collector.recordEmergencyAccepted("Ambulancia_INEM_01", 0);
            collector.setSimulatedTime(53.5);
            collector.recordEmergencyGreenGranted("Ambulancia_INEM_01", 0);
            collector.setSimulatedTime(58.0);
            collector.recordEmergencyCleared("Ambulancia_INEM_01", 0);

            // Ciclo 2 (t = 70s a 105s)
            collector.setSimulatedTime(70.0);
            collector.recordCrossing("Carro_01", 0, 0, 2.5);
            collector.recordCrossing("Carro_02", 0, 1, 3.5);

            collector.setSimulatedTime(85.0);
            collector.recordCrossing("Carro_03", 1, 0, 4.0);
            collector.recordCrossing("Carro_04", 1, 1, 6.0);

            collector.setSimulatedTime(100.0);
            collector.recordCrossing("Carro_05", 2, 0, 3.0);
            collector.recordCrossing("Carro_06", 2, 1, 3.0);

            // Emergência 2 no Cruzamento C2 (t = 120s a 130s)
            collector.setSimulatedTime(120.0);
            collector.recordEmergencyRequest("Viatura_Policia_01", 1, 1);
            collector.recordEmergencyAccepted("Viatura_Policia_01", 1);
            collector.setSimulatedTime(124.5);
            collector.recordEmergencyGreenGranted("Viatura_Policia_01", 1);
            collector.setSimulatedTime(130.0);
            collector.recordEmergencyCleared("Viatura_Policia_01", 1);

            // Ciclo 3 (t = 140s a 210s)
            collector.setSimulatedTime(140.0);
            collector.recordCrossing("Carro_01", 3, 0, 2.0);
            collector.recordCrossing("Carro_02", 3, 1, 2.0);

            collector.setSimulatedTime(160.0);
            collector.recordCrossing("Carro_01", 0, 0, 2.5);
            collector.recordCrossing("Carro_02", 0, 1, 3.5);

            collector.setSimulatedTime(185.0);
            collector.recordCrossing("Carro_03", 1, 0, 4.0);
            collector.recordCrossing("Carro_04", 1, 1, 6.0);

            collector.setSimulatedTime(210.0);
            collector.recordCrossing("Carro_05", 2, 0, 3.0);
            collector.recordCrossing("Carro_06", 2, 1, 3.0);

            // Ciclo 4 (t = 240s)
            collector.setSimulatedTime(240.0);
            collector.recordCrossing("Carro_01", 0, 0, 2.5);
            collector.recordCrossing("Carro_02", 0, 1, 3.5);

            // Total de travessias:
            // C0: 4 pares = 8 travessias (espera = 24.0s)
            // C1: 3 pares = 6 travessias (espera = 30.0s)
            // C2: 3 pares = 6 travessias (espera = 18.0s)
            // C3: 2 pares = 4 travessias (espera = 8.0s)
            // Total = 24 travessias, Espera total = 80.0s, Média = 80/24 = 3.33s

            // 4. Amostragem Periódica de Filas
            for (int s = 0; s < 10; s++) {
                collector.sampleQueue(0, 0, 2);
                collector.sampleQueue(0, 1, 4);
                collector.sampleQueue(1, 0, 3);
                collector.sampleQueue(1, 1, 5);
                collector.sampleQueue(2, 0, 1);
                collector.sampleQueue(2, 1, 2);
                collector.sampleQueue(3, 0, 0);
                collector.sampleQueue(3, 1, 1);
            }

            // 5. Alertas ML e Diretivas de Coordenação
            for (int c = 0; c < 4; c++) {
                String alertId = "ALERT_CTRL_" + c;
                String cmdId = "CMD_CTRL_" + c;
                collector.recordMLAlert(alertId, c, 0.85, "CRITICAL_CONGESTION");
                collector.recordCoordinationIssued(cmdId, c, "BALANCED", 6500);
                collector.recordCoordinationAccepted(cmdId, c);
                collector.recordCoordinationApplied(cmdId, c);
            }

            collector.setSimulatedTime(simDuration);
            collector.markSimulationEnd();

            // 6. Geração do Snapshot e Validação em Memória
            TrafficMetricsSnapshot snapshot = collector.getSnapshot(
                    "RUN_EVAL_INTEGRATION_001", "INTENSE_RAIN", "D_COORD_ML", 42L);

            System.out.println("[MEMÓRIA] Total Travessias: " + snapshot.totalCrossings);
            System.out.println("[MEMÓRIA] Throughput Global (veic/min): " + String.format(Locale.US, "%.2f", snapshot.throughputVehiclesPerMinute));
            System.out.println("[MEMÓRIA] Tempo Médio Espera (s): " + String.format(Locale.US, "%.2f", snapshot.averageWaitingTimeSeconds));
            System.out.println("[MEMÓRIA] Emergências Requisitadas/Concedidas: " + snapshot.emergencyRequests + " / " + snapshot.emergencyPreemptions);
            System.out.println("[MEMÓRIA] Atraso Médio Verde Emergência (s): " + String.format(Locale.US, "%.2f", snapshot.averageEmergencyGreenDelaySeconds));
            System.out.println("[MEMÓRIA] Comandos Coordenação Aplicados: " + snapshot.coordinationCommandsApplied);

            if (snapshot.totalCrossings != 24L) throw new AssertionError("Travessias esperadas: 24, obtidas: " + snapshot.totalCrossings);
            if (Math.abs(snapshot.throughputVehiclesPerMinute - 4.80) > 0.05) throw new AssertionError("Throughput esperado ~4.80 veic/min (24 em 5 min)");
            if (Math.abs(snapshot.averageWaitingTimeSeconds - 3.33) > 0.05) throw new AssertionError("Tempo médio de espera esperado ~3.33s");
            if (snapshot.emergencyRequests != 2 || snapshot.emergencyPreemptions != 2) throw new AssertionError("Emergências esperadas: 2/2");
            if (Math.abs(snapshot.averageEmergencyGreenDelaySeconds - 4.0) > 0.01) throw new AssertionError("Atraso médio emergência esperado: 4.0s");

            // 7. Exportação Real para results/run_summary.csv e results/intersection_metrics.csv
            File resultsDir = new File("results");
            if (!resultsDir.exists()) resultsDir.mkdirs();

            File summaryFile = new File(resultsDir, "run_summary.csv");
            File interFile = new File(resultsDir, "intersection_metrics.csv");

            // Limpa para ensaio limpo
            if (summaryFile.exists()) summaryFile.delete();
            if (interFile.exists()) interFile.delete();

            TrafficMetricsExporter.exportRunSummary(summaryFile, snapshot, false);
            TrafficMetricsExporter.exportIntersectionMetrics(interFile, snapshot, false);

            System.out.println("\n[EXPORTAÇÃO] Ficheiros CSV gerados com sucesso:");
            System.out.println("  • " + summaryFile.getAbsolutePath() + " (" + summaryFile.length() + " bytes)");
            System.out.println("  • " + interFile.getAbsolutePath() + " (" + interFile.length() + " bytes)");

            // 8. Leitura e Comparação Fiel CSV vs Memória
            try (BufferedReader reader = new BufferedReader(new FileReader(summaryFile))) {
                String header = reader.readLine();
                String data = reader.readLine();
                System.out.println("\n[CSV: run_summary.csv]");
                System.out.println("  HEADER: " + header);
                System.out.println("  DATA:   " + data);

                String[] parts = data.split(",");
                if (!parts[0].equals("RUN_EVAL_INTEGRATION_001")) throw new AssertionError("runId no CSV diverge!");
                if (!parts[1].equals("INTENSE_RAIN")) throw new AssertionError("scenario no CSV diverge!");
                if (!parts[2].equals("D_COORD_ML")) throw new AssertionError("controlMode no CSV diverge!");
                if (Long.parseLong(parts[6]) != 24L) throw new AssertionError("totalCrossings no CSV diverge!");
                if (Double.parseDouble(parts[7]) != 4.80) throw new AssertionError("throughput no CSV diverge!");
                if (Double.parseDouble(parts[9]) != 3.33) throw new AssertionError("averageWaitingTime no CSV diverge!");
                if (Integer.parseInt(parts[15]) != 2) throw new AssertionError("emergencyRequests no CSV diverge!");
                if (Integer.parseInt(parts[16]) != 2) throw new AssertionError("emergencyPreemptions no CSV diverge!");
                if (Double.parseDouble(parts[17]) != 4.00) throw new AssertionError("emergencyDelay no CSV diverge!");
                if (Integer.parseInt(parts[22]) != 4) throw new AssertionError("commandsApplied no CSV diverge!");
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(interFile))) {
                String header = reader.readLine();
                System.out.println("\n[CSV: intersection_metrics.csv]");
                System.out.println("  HEADER: " + header);
                for (int i = 0; i < 4; i++) {
                    String line = reader.readLine();
                    System.out.println("  ROW " + i + ": " + line);
                    String[] parts = line.split(",");
                    if (Integer.parseInt(parts[4]) != i) throw new AssertionError("intersectionIndex diverge!");
                    if (Long.parseLong(parts[6]) != snapshot.crossingsPerIntersection[i]) throw new AssertionError("crossings diverge no cruzamento " + i);
                }
            }

            System.out.println("\n================================================================================");
            System.out.println("SUCESSO: Teste de integração concluído! Paridade CSV vs Memória = 100%!");
            System.out.println("================================================================================");

        } catch (Throwable t) {
            System.err.println("FALHA no teste de integração: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
