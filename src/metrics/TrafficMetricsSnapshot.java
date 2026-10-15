package metrics;

import java.io.Serializable;
import java.util.Locale;

/**
 * Fotografia imutável das métricas de desempenho consolidadas de uma execução experimental.
 *
 * Suporta exportação tabular, análise estatística e separação estrita por cruzamento e eixo.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class TrafficMetricsSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    // Metadados do Ensaio Experimental
    public final String runId;
    public final String scenario;
    public final String controlMode; // A (Tempo Fixo), B (Adaptativo Local), C (Coordenado FIPA), D (Coordenado ML)
    public final long seed;
    public final double simulationDurationSeconds;
    public final double realDurationSeconds;

    // Métricas Globais de Eficiência Viária
    public final long totalCrossings;
    public final double throughputVehiclesPerMinute;
    public final double throughputVehiclesPerHour;
    public final double averageWaitingTimeSeconds; // -1.0 se N/A
    public final double averageWaitingTimePerVehicle; // -1.0 se N/A
    public final int uniqueVehiclesCount;

    // Métricas Globais de Filas de Retenção
    public final double averageQueueLength;
    public final int maximumQueueLength;
    public final long totalQueueSamples;

    // Métricas Globais de Veículos de Emergência
    public final int emergencyRequests;
    public final int emergencyPreemptions;
    public final double averageEmergencyGreenDelaySeconds; // -1.0 se N/A
    public final double averageEmergencyClearDelaySeconds; // -1.0 se N/A

    // Métricas Globais de Coordenação e Machine Learning
    public final int mlAlertsEmitted;
    public final int coordinationCommandsIssued;
    public final int coordinationCommandsAccepted;
    public final int coordinationCommandsApplied;
    public final int coordinationCommandsPreempted;
    public final double coordinationAdherenceRate; // -1.0 se N/A

    // Métricas Detalhadas por Cruzamento (Índices 0 a 3: C1_NW, C2_NE, C3_SW, C4_SE)
    public final long[] crossingsPerIntersection;
    public final double[] throughputPerIntersection;
    public final double[] averageWaitingTimePerIntersection;
    public final double[] averageQueuePerIntersection;
    public final int[] maxQueuePerIntersection;
    public final int[] emergencyRequestsPerIntersection;
    public final int[] emergencyPreemptionsPerIntersection;
    public final int[] coordinationAppliedPerIntersection;

    public TrafficMetricsSnapshot(String runId, String scenario, String controlMode, long seed,
                                  double simulationDurationSeconds, double realDurationSeconds,
                                  long totalCrossings, double throughputVehiclesPerMinute, double throughputVehiclesPerHour,
                                  double averageWaitingTimeSeconds, double averageWaitingTimePerVehicle, int uniqueVehiclesCount,
                                  double averageQueueLength, int maximumQueueLength, long totalQueueSamples,
                                  int emergencyRequests, int emergencyPreemptions,
                                  double averageEmergencyGreenDelaySeconds, double averageEmergencyClearDelaySeconds,
                                  int mlAlertsEmitted, int coordinationCommandsIssued, int coordinationCommandsAccepted,
                                  int coordinationCommandsApplied, int coordinationCommandsPreempted, double coordinationAdherenceRate,
                                  long[] crossingsPerIntersection, double[] throughputPerIntersection,
                                  double[] averageWaitingTimePerIntersection, double[] averageQueuePerIntersection,
                                  int[] maxQueuePerIntersection, int[] emergencyRequestsPerIntersection,
                                  int[] emergencyPreemptionsPerIntersection, int[] coordinationAppliedPerIntersection) {
        this.runId = runId;
        this.scenario = scenario;
        this.controlMode = controlMode;
        this.seed = seed;
        this.simulationDurationSeconds = simulationDurationSeconds;
        this.realDurationSeconds = realDurationSeconds;
        this.totalCrossings = totalCrossings;
        this.throughputVehiclesPerMinute = throughputVehiclesPerMinute;
        this.throughputVehiclesPerHour = throughputVehiclesPerHour;
        this.averageWaitingTimeSeconds = averageWaitingTimeSeconds;
        this.averageWaitingTimePerVehicle = averageWaitingTimePerVehicle;
        this.uniqueVehiclesCount = uniqueVehiclesCount;
        this.averageQueueLength = averageQueueLength;
        this.maximumQueueLength = maximumQueueLength;
        this.totalQueueSamples = totalQueueSamples;
        this.emergencyRequests = emergencyRequests;
        this.emergencyPreemptions = emergencyPreemptions;
        this.averageEmergencyGreenDelaySeconds = averageEmergencyGreenDelaySeconds;
        this.averageEmergencyClearDelaySeconds = averageEmergencyClearDelaySeconds;
        this.mlAlertsEmitted = mlAlertsEmitted;
        this.coordinationCommandsIssued = coordinationCommandsIssued;
        this.coordinationCommandsAccepted = coordinationCommandsAccepted;
        this.coordinationCommandsApplied = coordinationCommandsApplied;
        this.coordinationCommandsPreempted = coordinationCommandsPreempted;
        this.coordinationAdherenceRate = coordinationAdherenceRate;

        this.crossingsPerIntersection = crossingsPerIntersection.clone();
        this.throughputPerIntersection = throughputPerIntersection.clone();
        this.averageWaitingTimePerIntersection = averageWaitingTimePerIntersection.clone();
        this.averageQueuePerIntersection = averageQueuePerIntersection.clone();
        this.maxQueuePerIntersection = maxQueuePerIntersection.clone();
        this.emergencyRequestsPerIntersection = emergencyRequestsPerIntersection.clone();
        this.emergencyPreemptionsPerIntersection = emergencyPreemptionsPerIntersection.clone();
        this.coordinationAppliedPerIntersection = coordinationAppliedPerIntersection.clone();
    }

    /**
     * Formata um valor numérico decimal com tolerância explícita a dados ausentes (N/A).
     */
    public static String formatDecimalOrNA(double value, String format) {
        if (Double.isNaN(value) || value < 0.0) {
            return "N/A";
        }
        return String.format(Locale.US, format, value);
    }
}
