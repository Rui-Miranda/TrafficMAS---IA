package metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coletor Centralizado de Métricas e Telemetria Experimental do TrafficMAS.
 *
 * Concebido para operar com máxima precisão tanto em modo Gráfico (Swing)
 * como em modo Headless (simulação acelerada), sem introduzir distorções na dinâmica viária.
 *
 * Características arquiteturais:
 * 1. Separação explícita entre Tempo Simulado (segundos) e Tempo Real de Parede (milissegundos).
 * 2. Rastreio individual de travessias com prevenção estrita de contagem duplicada.
 * 3. Amostragem contínua de comprimento de filas (médio e máximo por cruzamento e eixo).
 * 4. Registo do ciclo de vida completo de veículos de emergência (requisição, concessão e desimpedimento).
 * 5. Monitorização da cadeia FIPA e adesão de diretivas ML (alerta -> pedido -> aplicação -> revogação).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class TrafficMetricsCollector {

    private static volatile TrafficMetricsCollector instance = null;

    // Relógio e Temporização
    private volatile double currentSimTimeSeconds = 0.0;
    private volatile double measurementStartSimTimeSeconds = 0.0;
    private volatile long realStartTimeMillis = 0L;
    private volatile long realEndTimeMillis = 0L;

    // Registo de Travessias e Espera
    private final AtomicLong totalCrossings = new AtomicLong(0);
    private final AtomicLong[] crossingsPerIntersection = new AtomicLong[4];
    private final AtomicLong[][] crossingsPerAxis = new AtomicLong[4][2]; // [interIdx][0=NS, 1=EW]

    private double totalWaitTimeSeconds = 0.0;
    private final double[] waitTimePerIntersection = new double[4];
    private final Set<String> uniqueVehicles = ConcurrentHashMap.newKeySet();
    private final Map<String, Double> waitTimePerVehicle = new ConcurrentHashMap<>();
    private final Map<String, Integer> crossingsPerVehicle = new ConcurrentHashMap<>();

    // Rastreio de Estado de Passagem dos Veículos nos Cruzamentos para Evitar Dupla Contagem
    // Chave: "vehicleId_interIdx" -> valor: Timestamp/Estado da última travessia
    private final Map<String, Double> lastCrossingSimTimeByVehicleAndInter = new ConcurrentHashMap<>();

    // Amostragem de Filas (Queue Lengths)
    private final AtomicLong totalQueueSamples = new AtomicLong(0);
    private final AtomicLong[][] queueSamplesPerIntersection = new AtomicLong[4][2];
    private final AtomicLong[][] queueSumPerIntersection = new AtomicLong[4][2];
    private final AtomicInteger[][] maxQueuePerIntersection = new AtomicInteger[4][2];

    // Registo de Veículos de Emergência
    public static class EmergencyEventRecord {
        public final String vehicleId;
        public final int intersectionIndex;
        public final int axis;
        public final double requestSimTime;
        public volatile double acceptedSimTime = -1.0;
        public volatile double greenGrantedSimTime = -1.0;
        public volatile double clearedSimTime = -1.0;
        public volatile boolean isPreempted = false;

        public EmergencyEventRecord(String vehicleId, int intersectionIndex, int axis, double requestSimTime) {
            this.vehicleId = vehicleId;
            this.intersectionIndex = intersectionIndex;
            this.axis = axis;
            this.requestSimTime = requestSimTime;
        }
    }

    private final Map<String, EmergencyEventRecord> activeEmergencies = new ConcurrentHashMap<>();
    private final List<EmergencyEventRecord> completedEmergencies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger emergencyRequestsCount = new AtomicInteger(0);
    private final AtomicInteger emergencyPreemptionsCount = new AtomicInteger(0);
    private final AtomicInteger[] emergencyRequestsPerIntersection = new AtomicInteger[4];
    private final AtomicInteger[] emergencyPreemptionsPerIntersection = new AtomicInteger[4];

    // Registo de Coordenação FIPA e Machine Learning
    public static class CoordinationEventRecord {
        public final String cmdId;
        public final int intersectionIndex;
        public final String policyType;
        public final long plannedDurationMs;
        public final double issuedSimTime;
        public volatile double acceptedSimTime = -1.0;
        public volatile double appliedSimTime = -1.0;
        public volatile double preemptedSimTime = -1.0;
        public volatile String preemptionReason = null;

        public CoordinationEventRecord(String cmdId, int intersectionIndex, String policyType, 
                                       long plannedDurationMs, double issuedSimTime) {
            this.cmdId = cmdId;
            this.intersectionIndex = intersectionIndex;
            this.policyType = policyType;
            this.plannedDurationMs = plannedDurationMs;
            this.issuedSimTime = issuedSimTime;
        }
    }

    public static class MLAlertRecord {
        public final String alertId;
        public final int intersectionIndex;
        public final double alertSimTime;
        public final double probability;
        public final String riskType;
        public volatile boolean checkedAtHorizon = false;
        public volatile boolean observedCongestionAtHorizon = false;
        public volatile int observedQueueAtHorizon = 0;
        public volatile double observedWaitAtHorizon = 0.0;
        public volatile double observedSpeedAtHorizon = 0.0;

        public MLAlertRecord(String alertId, int intersectionIndex, double alertSimTime, double probability, String riskType) {
            this.alertId = alertId;
            this.intersectionIndex = intersectionIndex;
            this.alertSimTime = alertSimTime;
            this.probability = probability;
            this.riskType = riskType;
        }
    }

    private final AtomicInteger mlAlertsEmitted = new AtomicInteger(0);
    private final List<MLAlertRecord> mlAlertRecords = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CoordinationEventRecord> coordinationRecords = new ConcurrentHashMap<>();
    private final AtomicInteger coordinationIssuedCount = new AtomicInteger(0);
    private final AtomicInteger coordinationAcceptedCount = new AtomicInteger(0);
    private final AtomicInteger coordinationAppliedCount = new AtomicInteger(0);
    private final AtomicInteger coordinationPreemptedCount = new AtomicInteger(0);
    private final AtomicInteger[] coordinationAppliedPerIntersection = new AtomicInteger[4];

    /**
     * Construtor do coletor. Inicializa estruturas atómicas e acumuladores.
     */
    public TrafficMetricsCollector() {
        for (int i = 0; i < 4; i++) {
            crossingsPerIntersection[i] = new AtomicLong(0);
            emergencyRequestsPerIntersection[i] = new AtomicInteger(0);
            emergencyPreemptionsPerIntersection[i] = new AtomicInteger(0);
            coordinationAppliedPerIntersection[i] = new AtomicInteger(0);
            for (int a = 0; a < 2; a++) {
                crossingsPerAxis[i][a] = new AtomicLong(0);
                queueSamplesPerIntersection[i][a] = new AtomicLong(0);
                queueSumPerIntersection[i][a] = new AtomicLong(0);
                maxQueuePerIntersection[i][a] = new AtomicInteger(0);
            }
        }
        this.realStartTimeMillis = System.currentTimeMillis();
    }

    public static synchronized TrafficMetricsCollector getInstance() {
        if (instance == null) {
            instance = new TrafficMetricsCollector();
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = new TrafficMetricsCollector();
    }

    /* =========================================================================
     * GESTÃO DE RELÓGIO (Simulado vs Real)
     * ========================================================================= */

    public synchronized void setSimulatedTime(double simTimeSeconds) {
        if (simTimeSeconds >= this.currentSimTimeSeconds) {
            this.currentSimTimeSeconds = simTimeSeconds;
        }
    }

    public synchronized void advanceSimulatedTime(double deltaSeconds) {
        if (deltaSeconds > 0) {
            this.currentSimTimeSeconds += deltaSeconds;
        }
    }

    public double getSimulatedTime() {
        return currentSimTimeSeconds;
    }

    public void markSimulationEnd() {
        this.realEndTimeMillis = System.currentTimeMillis();
    }

    public double getRealDurationSeconds() {
        long end = (realEndTimeMillis > 0) ? realEndTimeMillis : System.currentTimeMillis();
        return Math.max(0.001, (end - realStartTimeMillis) / 1000.0);
    }

    public synchronized void setMeasurementStartSimTime(double startSimTime) {
        this.measurementStartSimTimeSeconds = Math.max(0.0, startSimTime);
    }

    public double getMeasurementStartSimTime() {
        return measurementStartSimTimeSeconds;
    }

    public double getSimulatedDurationSeconds() {
        return Math.max(0.001, currentSimTimeSeconds - measurementStartSimTimeSeconds);
    }

    /* =========================================================================
     * TRAVESSIAS DE CRUZAMENTOS E TEMPO DE ESPERA
     * ========================================================================= */

    /**
     * Regista a travessia efetiva de um veículo pela linha de saída de um cruzamento.
     *
     * @param vehicleId Identificador da viatura
     * @param intersectionIndex Cruzamento transposto (0 a 3)
     * @param axis Eixo de travessia (0=NS, 1=EW)
     * @param waitTimeSeconds Tempo de espera acumulado nesta aproximação
     * @return true se a travessia for inédita e registada com sucesso; false se for duplicada (debounce)
     */
    public synchronized boolean recordCrossing(String vehicleId, int intersectionIndex, int axis, double waitTimeSeconds) {
        if (intersectionIndex < 0 || intersectionIndex >= 4 || axis < 0 || axis > 1) {
            return false;
        }

        String key = vehicleId + "_C" + intersectionIndex;
        Double lastCrossingTime = lastCrossingSimTimeByVehicleAndInter.get(key);

        // Prevenção de contagem duplicada: o mesmo veículo não pode transpor a mesma saída
        // do mesmo cruzamento com intervalo inferior a 2.0 segundos simulados
        if (lastCrossingTime != null && (currentSimTimeSeconds - lastCrossingTime) < 2.0) {
            return false;
        }

        lastCrossingSimTimeByVehicleAndInter.put(key, currentSimTimeSeconds);

        totalCrossings.incrementAndGet();
        crossingsPerIntersection[intersectionIndex].incrementAndGet();
        crossingsPerAxis[intersectionIndex][axis].incrementAndGet();

        double wait = Math.max(0.0, waitTimeSeconds);
        totalWaitTimeSeconds += wait;
        waitTimePerIntersection[intersectionIndex] += wait;

        uniqueVehicles.add(vehicleId);
        crossingsPerVehicle.merge(vehicleId, 1, Integer::sum);
        waitTimePerVehicle.merge(vehicleId, wait, Double::sum);

        return true;
    }

    public long getTotalCrossings() {
        return totalCrossings.get();
    }

    public long getCrossings(int intersectionIndex) {
        if (intersectionIndex >= 0 && intersectionIndex < 4) {
            return crossingsPerIntersection[intersectionIndex].get();
        }
        return 0;
    }

    public long getCrossings(int intersectionIndex, int axis) {
        if (intersectionIndex >= 0 && intersectionIndex < 4 && axis >= 0 && axis <= 1) {
            return crossingsPerAxis[intersectionIndex][axis].get();
        }
        return 0;
    }

    public synchronized double getAverageWaitingTimeSeconds() {
        long count = totalCrossings.get();
        return (count > 0) ? (totalWaitTimeSeconds / count) : -1.0;
    }

    public synchronized double getAverageWaitingTimeSeconds(int intersectionIndex) {
        if (intersectionIndex >= 0 && intersectionIndex < 4) {
            long count = crossingsPerIntersection[intersectionIndex].get();
            return (count > 0) ? (waitTimePerIntersection[intersectionIndex] / count) : -1.0;
        }
        return -1.0;
    }

    public synchronized double getAverageWaitingTimePerVehicle() {
        int vCount = uniqueVehicles.size();
        return (vCount > 0) ? (totalWaitTimeSeconds / vCount) : -1.0;
    }

    public int getUniqueVehiclesCount() {
        return uniqueVehicles.size();
    }

    /* =========================================================================
     * THROUGHPUT (Débito de Tráfego)
     * ========================================================================= */

    public double getGlobalThroughputPerMinute() {
        double minutes = getSimulatedDurationSeconds() / 60.0;
        return (minutes > 0) ? (totalCrossings.get() / minutes) : 0.0;
    }

    public double getGlobalThroughputPerHour() {
        double hours = getSimulatedDurationSeconds() / 3600.0;
        return (hours > 0) ? (totalCrossings.get() / hours) : 0.0;
    }

    public double getThroughputPerMinute(int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return 0.0;
        double minutes = getSimulatedDurationSeconds() / 60.0;
        return (minutes > 0) ? (crossingsPerIntersection[intersectionIndex].get() / minutes) : 0.0;
    }

    /* =========================================================================
     * COMPRIMENTO DE FILAS (Amostragem Periódica)
     * ========================================================================= */

    public void sampleQueue(int intersectionIndex, int axis, int queueLength) {
        if (intersectionIndex < 0 || intersectionIndex >= 4 || axis < 0 || axis > 1) return;
        int q = Math.max(0, queueLength);

        totalQueueSamples.incrementAndGet();
        queueSamplesPerIntersection[intersectionIndex][axis].incrementAndGet();
        queueSumPerIntersection[intersectionIndex][axis].addAndGet(q);

        maxQueuePerIntersection[intersectionIndex][axis].accumulateAndGet(q, Math::max);
    }

    public double getAverageQueueLength() {
        long totalSamples = totalQueueSamples.get();
        if (totalSamples == 0) return 0.0;

        long sum = 0;
        for (int i = 0; i < 4; i++) {
            for (int a = 0; a < 2; a++) {
                sum += queueSumPerIntersection[i][a].get();
            }
        }
        return (double) sum / totalSamples;
    }

    public double getAverageQueueLength(int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return 0.0;
        long samples = queueSamplesPerIntersection[intersectionIndex][0].get() 
                     + queueSamplesPerIntersection[intersectionIndex][1].get();
        if (samples == 0) return 0.0;

        long sum = queueSumPerIntersection[intersectionIndex][0].get() 
                 + queueSumPerIntersection[intersectionIndex][1].get();
        return (double) sum / samples;
    }

    public double getAverageQueueLength(int intersectionIndex, int axis) {
        if (intersectionIndex < 0 || intersectionIndex >= 4 || axis < 0 || axis > 1) return 0.0;
        long samples = queueSamplesPerIntersection[intersectionIndex][axis].get();
        return (samples > 0) ? ((double) queueSumPerIntersection[intersectionIndex][axis].get() / samples) : 0.0;
    }

    public int getMaximumQueueLength() {
        int max = 0;
        for (int i = 0; i < 4; i++) {
            for (int a = 0; a < 2; a++) {
                max = Math.max(max, maxQueuePerIntersection[i][a].get());
            }
        }
        return max;
    }

    public int getMaximumQueueLength(int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return 0;
        return Math.max(maxQueuePerIntersection[intersectionIndex][0].get(),
                        maxQueuePerIntersection[intersectionIndex][1].get());
    }

    public int getMaximumQueueLength(int intersectionIndex, int axis) {
        if (intersectionIndex < 0 || intersectionIndex >= 4 || axis < 0 || axis > 1) return 0;
        return maxQueuePerIntersection[intersectionIndex][axis].get();
    }

    public long getTotalQueueSamples() {
        return totalQueueSamples.get();
    }

    /* =========================================================================
     * VEÍCULOS DE EMERGÊNCIA (Ciclo de Vida e Preempção)
     * ========================================================================= */

    public synchronized void recordEmergencyRequest(String vehicleId, int intersectionIndex, int axis) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return;
        String key = vehicleId + "_C" + intersectionIndex;

        // Se já existe pedido ativo para a mesma viatura neste cruzamento, trata-se de reenvio FIPA (idempotente)
        EmergencyEventRecord existing = activeEmergencies.get(key);
        if (existing != null) {
            return;
        }

        EmergencyEventRecord rec = new EmergencyEventRecord(vehicleId, intersectionIndex, axis, currentSimTimeSeconds);
        activeEmergencies.put(key, rec);

        emergencyRequestsCount.incrementAndGet();
        emergencyRequestsPerIntersection[intersectionIndex].incrementAndGet();
    }

    public synchronized void recordEmergencyAccepted(String vehicleId, int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return;
        String key = vehicleId + "_C" + intersectionIndex;
        EmergencyEventRecord rec = activeEmergencies.get(key);
        if (rec != null && rec.acceptedSimTime < 0) {
            rec.acceptedSimTime = currentSimTimeSeconds;
        }
    }

    public synchronized void recordEmergencyGreenGranted(String vehicleId, int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return;
        String key = vehicleId + "_C" + intersectionIndex;
        EmergencyEventRecord rec = activeEmergencies.get(key);
        if (rec != null && !rec.isPreempted) {
            rec.greenGrantedSimTime = currentSimTimeSeconds;
            rec.isPreempted = true;
            emergencyPreemptionsCount.incrementAndGet();
            emergencyPreemptionsPerIntersection[intersectionIndex].incrementAndGet();
        }
    }

    public synchronized void recordEmergencyCleared(String vehicleId, int intersectionIndex) {
        if (intersectionIndex < 0 || intersectionIndex >= 4) return;
        String key = vehicleId + "_C" + intersectionIndex;
        EmergencyEventRecord rec = activeEmergencies.remove(key);
        if (rec != null) {
            rec.clearedSimTime = currentSimTimeSeconds;
            completedEmergencies.add(rec);
        }
    }

    public int getEmergencyRequests() {
        return emergencyRequestsCount.get();
    }

    public int getEmergencyPreemptions() {
        return emergencyPreemptionsCount.get();
    }

    public synchronized double getAverageEmergencyGreenDelaySeconds() {
        double sum = 0.0;
        int count = 0;

        List<EmergencyEventRecord> all = new ArrayList<>(completedEmergencies);
        all.addAll(activeEmergencies.values());

        for (EmergencyEventRecord rec : all) {
            if (rec.isPreempted && rec.greenGrantedSimTime >= rec.requestSimTime) {
                sum += (rec.greenGrantedSimTime - rec.requestSimTime);
                count++;
            }
        }
        return (count > 0) ? (sum / count) : -1.0;
    }

    public synchronized double getAverageEmergencyClearDelaySeconds() {
        double sum = 0.0;
        int count = 0;

        for (EmergencyEventRecord rec : completedEmergencies) {
            if (rec.clearedSimTime >= rec.requestSimTime) {
                sum += (rec.clearedSimTime - rec.requestSimTime);
                count++;
            }
        }
        return (count > 0) ? (sum / count) : -1.0;
    }

    /* =========================================================================
     * COORDENAÇÃO MULTIAGENTE E MACHINE LEARNING
     * ========================================================================= */

    public void recordMLAlert(String alertId, int intersectionIndex, double probability, String riskType) {
        mlAlertsEmitted.incrementAndGet();
        mlAlertRecords.add(new MLAlertRecord(alertId, intersectionIndex, currentSimTimeSeconds, probability, riskType));
    }

    public List<MLAlertRecord> getMLAlertRecords() {
        return new ArrayList<>(mlAlertRecords);
    }

    public void recordCoordinationIssued(String cmdId, int intersectionIndex, String policyType, long plannedDurationMs) {
        if (cmdId == null) return;
        coordinationRecords.put(cmdId, new CoordinationEventRecord(cmdId, intersectionIndex, policyType, plannedDurationMs, currentSimTimeSeconds));
        coordinationIssuedCount.incrementAndGet();
    }

    public void recordCoordinationAccepted(String cmdId, int intersectionIndex) {
        if (cmdId == null) return;
        CoordinationEventRecord rec = coordinationRecords.get(cmdId);
        if (rec != null && rec.acceptedSimTime < 0) {
            rec.acceptedSimTime = currentSimTimeSeconds;
            coordinationAcceptedCount.incrementAndGet();
        }
    }

    public void recordCoordinationApplied(String cmdId, int intersectionIndex) {
        if (cmdId == null) return;
        CoordinationEventRecord rec = coordinationRecords.get(cmdId);
        if (rec != null && rec.appliedSimTime < 0) {
            rec.appliedSimTime = currentSimTimeSeconds;
            coordinationAppliedCount.incrementAndGet();
            if (intersectionIndex >= 0 && intersectionIndex < 4) {
                coordinationAppliedPerIntersection[intersectionIndex].incrementAndGet();
            }
        }
    }

    public void recordCoordinationPreempted(String cmdId, int intersectionIndex, String reason) {
        if (cmdId == null) return;
        CoordinationEventRecord rec = coordinationRecords.get(cmdId);
        if (rec != null && rec.preemptedSimTime < 0) {
            rec.preemptedSimTime = currentSimTimeSeconds;
            rec.preemptionReason = reason;
            coordinationPreemptedCount.incrementAndGet();
        }
    }

    public int getMLAlertsEmitted() {
        return mlAlertsEmitted.get();
    }

    public int getCoordinationCommandsIssued() {
        return coordinationIssuedCount.get();
    }

    public int getCoordinationCommandsAccepted() {
        return coordinationAcceptedCount.get();
    }

    public int getCoordinationCommandsApplied() {
        return coordinationAppliedCount.get();
    }

    public int getCoordinationCommandsPreempted() {
        return coordinationPreemptedCount.get();
    }

    public double getCoordinationAdherenceRate() {
        int issued = coordinationIssuedCount.get();
        return (issued > 0) ? ((double) coordinationAppliedCount.get() / issued) : -1.0;
    }

    /* =========================================================================
     * GERAÇÃO DO SNAPSHOT CONSOLIDADO
     * ========================================================================= */

    public synchronized TrafficMetricsSnapshot getSnapshot(String runId, String scenario, String controlMode, long seed) {
        long[] crossings = new long[4];
        double[] throughput = new double[4];
        double[] avgWait = new double[4];
        double[] avgQueue = new double[4];
        int[] maxQueue = new int[4];
        int[] emergReq = new int[4];
        int[] emergPreempt = new int[4];
        int[] coordApp = new int[4];

        for (int i = 0; i < 4; i++) {
            crossings[i] = crossingsPerIntersection[i].get();
            throughput[i] = getThroughputPerMinute(i);
            avgWait[i] = getAverageWaitingTimeSeconds(i);
            avgQueue[i] = getAverageQueueLength(i);
            maxQueue[i] = getMaximumQueueLength(i);
            emergReq[i] = emergencyRequestsPerIntersection[i].get();
            emergPreempt[i] = emergencyPreemptionsPerIntersection[i].get();
            coordApp[i] = coordinationAppliedPerIntersection[i].get();
        }

        return new TrafficMetricsSnapshot(
                runId, scenario, controlMode, seed,
                getSimulatedDurationSeconds(), getRealDurationSeconds(),
                getTotalCrossings(), getGlobalThroughputPerMinute(), getGlobalThroughputPerHour(),
                getAverageWaitingTimeSeconds(), getAverageWaitingTimePerVehicle(), getUniqueVehiclesCount(),
                getAverageQueueLength(), getMaximumQueueLength(), getTotalQueueSamples(),
                getEmergencyRequests(), getEmergencyPreemptions(),
                getAverageEmergencyGreenDelaySeconds(), getAverageEmergencyClearDelaySeconds(),
                getMLAlertsEmitted(), getCoordinationCommandsIssued(), getCoordinationCommandsAccepted(),
                getCoordinationCommandsApplied(), getCoordinationCommandsPreempted(), getCoordinationAdherenceRate(),
                crossings, throughput, avgWait, avgQueue, maxQueue, emergReq, emergPreempt, coordApp
        );
    }
}
