package metrics;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Utilitário de Exportação de Métricas Experimentais em Formato Tabular CSV.
 *
 * Gera os ficheiros estruturados em conformidade com a Especificação da Fase 5:
 * 1. results/run_summary.csv: Resumo global de cada ensaio/execução.
 * 2. results/intersection_metrics.csv: Detalhe granular por cruzamento.
 *
 * Assegura o tratamento de valores ausentes (N/A) e independência de formatação local (Locale.US).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class TrafficMetricsExporter {

    public static final String DEFAULT_RESULTS_DIR = "results";
    public static final String RUN_SUMMARY_FILENAME = "run_summary.csv";
    public static final String INTERSECTION_METRICS_FILENAME = "intersection_metrics.csv";

    private static final String[] INTERSECTION_NAMES = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    public static final String RUN_SUMMARY_HEADER = 
            "runId,scenario,controlMode,seed,simulationDurationSeconds,realDurationSeconds,"
            + "totalCrossings,throughputVehiclesPerMinute,throughputVehiclesPerHour,"
            + "averageWaitingTimeSeconds,averageWaitingTimePerVehicle,uniqueVehicles,"
            + "averageQueueLength,maximumQueueLength,totalQueueSamples,"
            + "emergencyRequests,emergencyPreemptions,averageEmergencyGreenDelaySeconds,averageEmergencyClearDelaySeconds,"
            + "mlAlertsEmitted,coordinationCommandsIssued,coordinationCommandsAccepted,coordinationCommandsApplied,"
            + "coordinationCommandsPreempted,coordinationAdherenceRate";

    public static final String INTERSECTION_METRICS_HEADER = 
            "runId,scenario,controlMode,seed,intersectionIndex,intersectionName,"
            + "crossings,throughputVehiclesPerMinute,averageWaitingTimeSeconds,"
            + "averageQueueLength,maximumQueueLength,emergencyRequests,emergencyPreemptions,coordinationApplied";

    /**
     * Exporta o resumo global da execução para results/run_summary.csv.
     */
    public static synchronized void exportRunSummary(File file, TrafficMetricsSnapshot s, boolean append) throws IOException {
        ensureParentDirectory(file);
        boolean writeHeader = !file.exists() || !append || file.length() == 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, append))) {
            if (writeHeader) {
                writer.println(RUN_SUMMARY_HEADER);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(s.runId).append(",");
            sb.append(s.scenario).append(",");
            sb.append(s.controlMode).append(",");
            sb.append(s.seed).append(",");
            sb.append(String.format(Locale.US, "%.2f", s.simulationDurationSeconds)).append(",");
            sb.append(String.format(Locale.US, "%.2f", s.realDurationSeconds)).append(",");
            sb.append(s.totalCrossings).append(",");
            sb.append(String.format(Locale.US, "%.2f", s.throughputVehiclesPerMinute)).append(",");
            sb.append(String.format(Locale.US, "%.2f", s.throughputVehiclesPerHour)).append(",");
            sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageWaitingTimeSeconds, "%.2f")).append(",");
            sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageWaitingTimePerVehicle, "%.2f")).append(",");
            sb.append(s.uniqueVehiclesCount).append(",");
            sb.append(String.format(Locale.US, "%.2f", s.averageQueueLength)).append(",");
            sb.append(s.maximumQueueLength).append(",");
            sb.append(s.totalQueueSamples).append(",");
            sb.append(s.emergencyRequests).append(",");
            sb.append(s.emergencyPreemptions).append(",");
            sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageEmergencyGreenDelaySeconds, "%.2f")).append(",");
            sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageEmergencyClearDelaySeconds, "%.2f")).append(",");
            sb.append(s.mlAlertsEmitted).append(",");
            sb.append(s.coordinationCommandsIssued).append(",");
            sb.append(s.coordinationCommandsAccepted).append(",");
            sb.append(s.coordinationCommandsApplied).append(",");
            sb.append(s.coordinationCommandsPreempted).append(",");
            sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.coordinationAdherenceRate, "%.4f"));

            writer.println(sb.toString());
        }
    }

    /**
     * Exporta o detalhe por cruzamento para results/intersection_metrics.csv (4 linhas por execução).
     */
    public static synchronized void exportIntersectionMetrics(File file, TrafficMetricsSnapshot s, boolean append) throws IOException {
        ensureParentDirectory(file);
        boolean writeHeader = !file.exists() || !append || file.length() == 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, append))) {
            if (writeHeader) {
                writer.println(INTERSECTION_METRICS_HEADER);
            }

            for (int i = 0; i < 4; i++) {
                StringBuilder sb = new StringBuilder();
                sb.append(s.runId).append(",");
                sb.append(s.scenario).append(",");
                sb.append(s.controlMode).append(",");
                sb.append(s.seed).append(",");
                sb.append(i).append(",");
                sb.append(INTERSECTION_NAMES[i]).append(",");
                sb.append(s.crossingsPerIntersection[i]).append(",");
                sb.append(String.format(Locale.US, "%.2f", s.throughputPerIntersection[i])).append(",");
                sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageWaitingTimePerIntersection[i], "%.2f")).append(",");
                sb.append(String.format(Locale.US, "%.2f", s.averageQueuePerIntersection[i])).append(",");
                sb.append(s.maxQueuePerIntersection[i]).append(",");
                sb.append(s.emergencyRequestsPerIntersection[i]).append(",");
                sb.append(s.emergencyPreemptionsPerIntersection[i]).append(",");
                sb.append(s.coordinationAppliedPerIntersection[i]);

                writer.println(sb.toString());
            }
        }
    }

    /**
     * Exportação conveniente para os caminhos padrão dentro da pasta results/.
     */
    public static synchronized void exportDefault(TrafficMetricsSnapshot s, boolean append) throws IOException {
        File dir = new File(DEFAULT_RESULTS_DIR);
        if (!dir.exists()) dir.mkdirs();

        exportRunSummary(new File(dir, RUN_SUMMARY_FILENAME), s, append);
        exportIntersectionMetrics(new File(dir, INTERSECTION_METRICS_FILENAME), s, append);
    }

    private static void ensureParentDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
