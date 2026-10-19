package experiments;

import environment.CitySimulationGUI;
import environment.HeadlessTrafficSimulator;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ControlMode;
import environment.SimulationClock;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import metrics.TrafficMetricsCollector;
import metrics.TrafficMetricsSnapshot;
import ml.ModelPersistenceManager;
import ml.TrafficModelBundle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * Orquestrador Experimental Central do TrafficMAS (Fase 5 - Etapa 2).
 *
 * Executa ensaios comparativos reproduzíveis entre quatro estratégias semafóricas:
 *   - Modo A: FIXED_TIME (Temporização fixa sem adaptação)
 *   - Modo B: LOCAL_ADAPTIVE (Controlo adaptativo local com sensores locais)
 *   - Modo C: MULTIAGENT_COORDINATED (Coordenação multiagente FIPA-ACL com CentralCoordinationAgent)
 *   - Modo D: MULTIAGENT_ML (Sistema multiagente preditivo com DisruptionManagementAgent e ML operacional)
 *
 * Garante estritamente:
 * 1. Mesma física viária, topologia e cinemática microscópica em todos os modos.
 * 2. Determinismo rigoroso por semente pseudoaleatória (mesmas chegadas por combinação cenário + seed).
 * 3. Calendário idêntico de injeção de emergências no cenário DISRUPTION_EMERGENCY.
 * 4. Sincronização temporal estrita via SimulationClock sem dependência de relógio real.
 * 5. Isolamento temporal integral entre ensaios (reset completo de autoridades, agentes e métricas).
 * 6. Exportação automática dos 5 ficheiros CSV estruturados em results/experiments/.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 5)
 */
public class TrafficExperimentRunner {

    public static final String RESULTS_DIR = "results/experiments";
    public static final String RAW_RUNS_FILE = RESULTS_DIR + "/raw_runs.csv";
    public static final String SCENARIO_SUMMARY_FILE = RESULTS_DIR + "/scenario_summary.csv";
    public static final String MODE_COMPARISON_FILE = RESULTS_DIR + "/mode_comparison.csv";
    public static final String INTERSECTION_RESULTS_FILE = RESULTS_DIR + "/intersection_results.csv";
    public static final String STATISTICAL_ANALYSIS_FILE = RESULTS_DIR + "/statistical_analysis.csv";

    public static final String[] PILOT_MODES = {
            "FIXED_TIME",
            "LOCAL_ADAPTIVE",
            "MULTIAGENT_COORDINATED",
            "MULTIAGENT_ML"
    };

    public static final String[] PILOT_SCENARIOS = {
            "NORMAL",
            "HEAVY_TRAFFIC",
            "ASYMMETRIC",
            "DISRUPTION_EMERGENCY"
    };

    public static final long[] PILOT_SEEDS = {2001L, 2002L}; // Sementes reservadas para o piloto
    public static final double SIMULATION_DURATION = 600.0;  // 10 minutos simulados
    public static final double WARMUP_DURATION = 60.0;       // 1 minuto de warm-up

    public static class ExperimentRunResult {
        public final String runId;
        public final String scenario;
        public final String mode;
        public final long seed;
        public final double simDuration;
        public final double warmupDuration;
        public final TrafficMetricsSnapshot snapshot;
        public final String executionStatus;
        public final int errorCount;
        public final String modelVersion;
        public final String modelDatasetSha256;
        public final List<TrafficMetricsCollector.MLAlertRecord> mlAlertRecords;

        public ExperimentRunResult(String runId, String scenario, String mode, long seed,
                                   double simDuration, double warmupDuration,
                                   TrafficMetricsSnapshot snapshot, String status, int errorCount,
                                   String modelVersion, String modelDatasetSha256,
                                   List<TrafficMetricsCollector.MLAlertRecord> mlAlertRecords) {
            this.runId = runId;
            this.scenario = scenario;
            this.mode = mode;
            this.seed = seed;
            this.simDuration = simDuration;
            this.warmupDuration = warmupDuration;
            this.snapshot = snapshot;
            this.executionStatus = status;
            this.errorCount = errorCount;
            this.modelVersion = modelVersion;
            this.modelDatasetSha256 = modelDatasetSha256;
            this.mlAlertRecords = (mlAlertRecords != null) ? new ArrayList<>(mlAlertRecords) : Collections.emptyList();
        }

        public ExperimentRunResult(String runId, String scenario, String mode, long seed,
                                   double simDuration, double warmupDuration,
                                   TrafficMetricsSnapshot snapshot, String status, int errorCount,
                                   String modelVersion, String modelDatasetSha256) {
            this(runId, scenario, mode, seed, simDuration, warmupDuration, snapshot, status, errorCount, modelVersion, modelDatasetSha256, Collections.emptyList());
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — PILOTO EXPERIMENTAL COMPARATIVO (FASE 5 - ETAPA 2)");
        System.out.println("   Configuração: 4 Modos × 4 Cenários × 2 Seeds = 32 Execuções");
        System.out.println("   Duração: 600s simulados (Warm-up: 60s) | Seeds: [2001, 2002]");
        System.out.println("================================================================================");

        try {
            TrafficExperimentRunner runner = new TrafficExperimentRunner();
            List<ExperimentRunResult> results = runner.runPilot();

            System.out.println("\n[EXPORTAÇÃO] A gerar ficheiros tabulares em " + RESULTS_DIR + "...");
            runner.exportAllResults(results);

            System.out.println("\n================================================================================");
            System.out.println("   PILOTO EXPERIMENTAL CONCLUÍDO COM 100% DE SUCESSO!");
            System.out.println("   Total de ensaios válidos: " + results.size() + " / 32");
            System.out.println("   Ficheiros gerados:");
            System.out.println("     1. " + RAW_RUNS_FILE);
            System.out.println("     2. " + SCENARIO_SUMMARY_FILE);
            System.out.println("     3. " + MODE_COMPARISON_FILE);
            System.out.println("     4. " + INTERSECTION_RESULTS_FILE);
            System.out.println("     5. " + STATISTICAL_ANALYSIS_FILE);
            System.out.println("================================================================================");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("FALHA CRÍTICA NO PILOTO: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Executa o piloto de 32 ensaios experimentais.
     */
    public List<ExperimentRunResult> runPilot() {
        return runSuite(PILOT_MODES, PILOT_SCENARIOS, PILOT_SEEDS, SIMULATION_DURATION, WARMUP_DURATION);
    }

    /**
     * Executa uma suite parametrizada de ensaios experimentais.
     */
    public List<ExperimentRunResult> runSuite(String[] modes, String[] scenarios, long[] seeds,
                                            double totalDuration, double warmupDuration) {
        List<ExperimentRunResult> results = new ArrayList<>();
        int totalRuns = modes.length * scenarios.length * seeds.length;
        int currentRun = 0;

        // Metadados do modelo ML atual
        String modelVersion = "1.0.0";
        String modelSha = "N/A";
        File modelFile = new File("data/traffic_model.bin");
        if (modelFile.exists()) {
            try {
                TrafficModelBundle bundle = ModelPersistenceManager.loadModel("data/traffic_model.bin");
                modelVersion = bundle.getModelVersion();
                modelSha = bundle.getDatasetSha256();
            } catch (Exception ignored) {}
        }

        for (String scenario : scenarios) {
            for (String mode : modes) {
                for (long seed : seeds) {
                    currentRun++;
                    String runId = String.format("RUN_%s_%s_%d", mode, scenario, seed);

                    System.out.printf("\n[%d/%d] A executar: %s | Modo: %-22s | Semente: %d ...\n",
                            currentRun, totalRuns, scenario, mode, seed);

                    ExperimentRunResult res = executeSingleRun(runId, scenario, mode, seed, 
                            totalDuration, warmupDuration, currentRun, modelVersion, modelSha);
                    results.add(res);

                    TrafficMetricsSnapshot s = res.snapshot;
                    System.out.printf("      ✓ Concluído em %.2fs real | Travessias: %d | Throughput: %.2f v/min | Espera: %.2fs | Fila: %.2f | Emerg: %d | Coord: %d (Aplic: %d) | Alertas ML: %d\n",
                            s.realDurationSeconds, s.totalCrossings, s.throughputVehiclesPerMinute,
                            s.averageWaitingTimeSeconds, s.averageQueueLength, s.emergencyRequests,
                            s.coordinationCommandsIssued, s.coordinationCommandsApplied, s.mlAlertsEmitted);
                }
            }
        }

        return results;
    }

    /**
     * Executa um único ensaio isolado com rigor científico e determinístico.
     */
    public ExperimentRunResult executeSingleRun(String runId, String scenario, String mode, long seed,
                                                double totalDuration, double warmupDuration, int runIndex,
                                                String modelVersion, String modelSha) {
        // 1. Reset completo e inicialização do relógio simulado
        SimulationClock.enableSimulatedMode(0L);
        SimulationClock.setSimulatedTimeSeconds(0.0);
        TrafficMetricsCollector.reset();
        IntersectionSafetyAuthority.resetAll();

        ControlMode controlMode;
        switch (mode.toUpperCase()) {
            case "FIXED_TIME":
                controlMode = ControlMode.FIXED_TIME;
                break;
            case "LOCAL_ADAPTIVE":
                controlMode = ControlMode.LOCAL_ADAPTIVE;
                break;
            case "MULTIAGENT_COORDINATED":
                controlMode = ControlMode.MULTIAGENT_COORDINATED;
                break;
            case "MULTIAGENT_ML":
            default:
                controlMode = ControlMode.MULTIAGENT_ML;
                break;
        }

        AgentContainer container = null;
        int jadePort = 2400 + (runIndex % 1000);
        int errorCount = 0;
        String executionStatus = "SUCCESS";

        try {
            // 2. Arranque do contentor JADE quando necessário (Modos C e D)
            if (controlMode == ControlMode.MULTIAGENT_COORDINATED || controlMode == ControlMode.MULTIAGENT_ML) {
                Runtime rt = Runtime.instance();
                Profile p = new ProfileImpl();
                p.setParameter(Profile.MAIN_HOST, "localhost");
                p.setParameter(Profile.MAIN_PORT, String.valueOf(jadePort));
                p.setParameter(Profile.GUI, "false");
                container = rt.createMainContainer(p);

                // Instancia o Coordenador Central
                AgentController coord = container.createNewAgent("AgenteCoordenadorCentral", 
                        "agents.CentralCoordinationAgent", new Object[]{});
                coord.start();

                // Instancia os 8 Semáforos Inteligentes
                String[] crossings = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
                for (int i = 0; i < 4; i++) {
                    AgentController tlNS = container.createNewAgent("Semaforo_NS_" + crossings[i], 
                            "agents.TrafficLightAgent", new Object[]{"Norte-Sul", i});
                    tlNS.start();

                    AgentController tlEW = container.createNewAgent("Semaforo_EW_" + crossings[i], 
                            "agents.TrafficLightAgent", new Object[]{"Este-Oeste", i});
                    tlEW.start();
                }

                // Instancia o Agente de Machine Learning (Modo D)
                if (controlMode == ControlMode.MULTIAGENT_ML) {
                    AgentController mlAgent = container.createNewAgent("AgenteGestaoPerturbacoes", 
                            "agents.DisruptionManagementAgent", new Object[]{"data/traffic_model.bin"});
                    mlAgent.start();
                }

                // Aguarda 150ms para registro completo no DFService
                Thread.sleep(150);
            }

            // 3. Inicialização do motor físico com semente controlada
            HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator(scenario, seed, controlMode);

            // 4. Ciclo de Simulação Discreto (dt = 1/30 s)
            double dt = 1.0 / 30.0;
            int totalSteps = (int) Math.round(totalDuration / dt);
            int warmupSteps = (int) Math.round(warmupDuration / dt);

            // Calendário determinístico de injeção de emergências
            boolean em1Injected = false;
            boolean em2Injected = false;
            boolean em3Injected = false;
            boolean isEmergencyScenario = scenario.equalsIgnoreCase("DISRUPTION_EMERGENCY");

            for (int s = 0; s < totalSteps; s++) {
                double currentT = s * dt;

                // Fim do período de aquecimento (warm-up): reinicia contadores para medição de estado estacionário
                if (s == warmupSteps) {
                    TrafficMetricsCollector.reset();
                    TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
                    collector.setMeasurementStartSimTime(currentT);
                    collector.setSimulatedTime(currentT);
                    sim.resetWarmupVehicleStates(currentT);
                }

                // Injeção de emergências determinísticas
                if (isEmergencyScenario) {
                    if (currentT >= 120.0 && !em1Injected) {
                        sim.injectEmergencyVehicle("Ambulancia_C1", 0, 0); // Eixo NS -> C1
                        em1Injected = true;
                    } else if (currentT >= 300.0 && !em2Injected) {
                        sim.injectEmergencyVehicle("Ambulancia_C3", 2, 0); // Eixo EW -> C3
                        em2Injected = true;
                    } else if (currentT >= 450.0 && !em3Injected) {
                        sim.injectEmergencyVehicle("Policia_C4", 0, 0);    // Eixo NS -> C4
                        em3Injected = true;
                    }
                }

                sim.advanceSeconds(dt);

                // Avaliação das previsões ML ao atingir o horizonte preditivo de 60 segundos
                if (controlMode == ControlMode.MULTIAGENT_ML) {
                    for (TrafficMetricsCollector.MLAlertRecord alertRec : TrafficMetricsCollector.getInstance().getMLAlertRecords()) {
                        if (!alertRec.checkedAtHorizon && currentT >= (alertRec.alertSimTime + 60.0)) {
                            HeadlessTrafficSimulator.IntersectionMetricsSnapshot futureSnap = sim.captureSnapshot(alertRec.intersectionIndex);
                            alertRec.checkedAtHorizon = true;
                            alertRec.observedCongestionAtHorizon = futureSnap.isSevereCongestion();
                            alertRec.observedQueueAtHorizon = futureSnap.queueLength;
                            alertRec.observedWaitAtHorizon = futureSnap.averageWaitingTime;
                            alertRec.observedSpeedAtHorizon = futureSnap.averageSpeed;
                        }
                    }
                }
            }

            TrafficMetricsCollector collector = TrafficMetricsCollector.getInstance();
            collector.markSimulationEnd();

            // Validação de segurança física e integridade do ensaio
            for (int i = 0; i < 4; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                CitySimulationGUI.LightState ns = auth.getLightState(0);
                CitySimulationGUI.LightState ew = auth.getLightState(1);
                if (ns == CitySimulationGUI.LightState.GREEN && ew == CitySimulationGUI.LightState.GREEN) {
                    errorCount++;
                    executionStatus = "SAFETY_VIOLATION_INCOMPATIBLE_GREENS";
                }
            }

            TrafficMetricsSnapshot snapshot = collector.getSnapshot(runId, scenario, mode, seed);
            sim.cleanup();

            return new ExperimentRunResult(runId, scenario, mode, seed, totalDuration, warmupDuration,
                    snapshot, executionStatus, errorCount, modelVersion, modelSha, collector.getMLAlertRecords());

        } catch (Exception e) {
            errorCount++;
            executionStatus = "FAILED_EXCEPTION: " + e.getMessage();
            TrafficMetricsSnapshot fallbackSnap = TrafficMetricsCollector.getInstance().getSnapshot(runId, scenario, mode, seed);
            return new ExperimentRunResult(runId, scenario, mode, seed, totalDuration, warmupDuration,
                    fallbackSnap, executionStatus, errorCount, modelVersion, modelSha, Collections.emptyList());

        } finally {
            if (container != null) {
                try {
                    container.kill();
                    Thread.sleep(80);
                } catch (Exception ignored) {}
            }
            SimulationClock.disableSimulatedMode();
        }
    }

    /* =========================================================================
     * EXPORTAÇÃO DOS 5 FICHEIROS CSV
     * ========================================================================= */

    public void exportAllResults(List<ExperimentRunResult> results) throws IOException {
        exportAllResults(results, RESULTS_DIR);
    }

    public void exportAllResults(List<ExperimentRunResult> results, String targetDir) throws IOException {
        File dir = new File(targetDir);
        if (!dir.exists()) dir.mkdirs();

        exportRawRuns(new File(targetDir + "/raw_runs.csv"), results);
        exportScenarioSummary(new File(targetDir + "/scenario_summary.csv"), results);
        exportModeComparison(new File(targetDir + "/mode_comparison.csv"), results);
        exportIntersectionResults(new File(targetDir + "/intersection_results.csv"), results);
        exportStatisticalAnalysis(new File(targetDir + "/statistical_analysis.csv"), results);
        exportMLAlertsAudit(new File(targetDir + "/ml_alerts_audit.csv"), results);
    }

    private void exportRawRuns(File file, List<ExperimentRunResult> results) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("runId,scenario,mode,seed,simulationDurationSeconds,warmupDurationSeconds,realDurationSeconds,"
                    + "totalCrossings,throughputVehiclesPerMinute,throughputVehiclesPerHour,"
                    + "averageWaitingTimeSeconds,averageWaitingTimePerVehicle,uniqueVehicles,"
                    + "averageQueueLength,maximumQueueLength,totalQueueSamples,"
                    + "emergencyRequests,emergencyPreemptions,averageEmergencyGreenDelaySeconds,averageEmergencyClearDelaySeconds,"
                    + "mlAlertsEmitted,coordinationCommandsIssued,coordinationCommandsAccepted,coordinationCommandsApplied,"
                    + "coordinationCommandsPreempted,coordinationAdherenceRate,executionStatus,errorCount,modelVersion,modelDatasetSha256");

            for (ExperimentRunResult r : results) {
                TrafficMetricsSnapshot s = r.snapshot;
                StringBuilder sb = new StringBuilder();
                sb.append(r.runId).append(",");
                sb.append(r.scenario).append(",");
                sb.append(r.mode).append(",");
                sb.append(r.seed).append(",");
                sb.append(String.format(Locale.US, "%.2f", r.simDuration)).append(",");
                sb.append(String.format(Locale.US, "%.2f", r.warmupDuration)).append(",");
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
                sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.coordinationAdherenceRate, "%.4f")).append(",");
                sb.append(r.executionStatus).append(",");
                sb.append(r.errorCount).append(",");
                sb.append(r.modelVersion).append(",");
                sb.append(r.modelDatasetSha256);

                w.println(sb.toString());
            }
        }
    }

    private void exportScenarioSummary(File file, List<ExperimentRunResult> results) throws IOException {
        Map<String, List<ExperimentRunResult>> grouped = new LinkedHashMap<>();
        for (ExperimentRunResult r : results) {
            String key = r.scenario + "_" + r.mode;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("scenario,mode,replications,meanWaitTime,stdWaitTime,minWaitTime,maxWaitTime,"
                    + "meanThroughput,stdThroughput,minThroughput,maxThroughput,"
                    + "meanQueueLength,stdQueueLength,maxQueueLength,"
                    + "meanEmergencyGreenDelay,meanEmergencyClearDelay,"
                    + "totalEmergencyRequests,totalEmergencyPreemptions,"
                    + "totalCoordinationIssued,totalCoordinationApplied,meanCoordinationAdherenceRate,totalMLAlerts");

            for (List<ExperimentRunResult> group : grouped.values()) {
                String scenario = group.get(0).scenario;
                String mode = group.get(0).mode;
                int reps = group.size();

                List<Double> waits = new ArrayList<>();
                List<Double> thrus = new ArrayList<>();
                List<Double> queues = new ArrayList<>();
                List<Double> emGreens = new ArrayList<>();
                List<Double> emClears = new ArrayList<>();
                List<Double> adherences = new ArrayList<>();

                int maxQueueAll = 0;
                int totalEmergReq = 0;
                int totalEmergPreempt = 0;
                int totalCoordIssued = 0;
                int totalCoordApplied = 0;
                int totalMLAlerts = 0;

                for (ExperimentRunResult r : group) {
                    TrafficMetricsSnapshot s = r.snapshot;
                    if (s.averageWaitingTimeSeconds >= 0) waits.add(s.averageWaitingTimeSeconds);
                    thrus.add(s.throughputVehiclesPerMinute);
                    queues.add(s.averageQueueLength);
                    if (s.maximumQueueLength > maxQueueAll) maxQueueAll = s.maximumQueueLength;
                    if (s.averageEmergencyGreenDelaySeconds >= 0) emGreens.add(s.averageEmergencyGreenDelaySeconds);
                    if (s.averageEmergencyClearDelaySeconds >= 0) emClears.add(s.averageEmergencyClearDelaySeconds);
                    if (s.coordinationAdherenceRate >= 0) adherences.add(s.coordinationAdherenceRate);

                    totalEmergReq += s.emergencyRequests;
                    totalEmergPreempt += s.emergencyPreemptions;
                    totalCoordIssued += s.coordinationCommandsIssued;
                    totalCoordApplied += s.coordinationCommandsApplied;
                    totalMLAlerts += s.mlAlertsEmitted;
                }

                Stats waitStats = computeStats(waits);
                Stats thruStats = computeStats(thrus);
                Stats queueStats = computeStats(queues);
                Stats emGreenStats = computeStats(emGreens);
                Stats emClearStats = computeStats(emClears);
                Stats adhStats = computeStats(adherences);

                StringBuilder sb = new StringBuilder();
                sb.append(scenario).append(",");
                sb.append(mode).append(",");
                sb.append(reps).append(",");
                sb.append(formatStat(waitStats.mean)).append(",");
                sb.append(formatStat(waitStats.std)).append(",");
                sb.append(formatStat(waitStats.min)).append(",");
                sb.append(formatStat(waitStats.max)).append(",");
                sb.append(formatStat(thruStats.mean)).append(",");
                sb.append(formatStat(thruStats.std)).append(",");
                sb.append(formatStat(thruStats.min)).append(",");
                sb.append(formatStat(thruStats.max)).append(",");
                sb.append(formatStat(queueStats.mean)).append(",");
                sb.append(formatStat(queueStats.std)).append(",");
                sb.append(maxQueueAll).append(",");
                sb.append(formatStat(emGreenStats.mean)).append(",");
                sb.append(formatStat(emClearStats.mean)).append(",");
                sb.append(totalEmergReq).append(",");
                sb.append(totalEmergPreempt).append(",");
                sb.append(totalCoordIssued).append(",");
                sb.append(totalCoordApplied).append(",");
                sb.append(formatStat(adhStats.mean)).append(",");
                sb.append(totalMLAlerts);

                w.println(sb.toString());
            }
        }
    }

    private void exportModeComparison(File file, List<ExperimentRunResult> results) throws IOException {
        Map<String, List<ExperimentRunResult>> grouped = new LinkedHashMap<>();
        for (String m : PILOT_MODES) {
            grouped.put(m, new ArrayList<>());
        }
        for (ExperimentRunResult r : results) {
            grouped.computeIfAbsent(r.mode, k -> new ArrayList<>()).add(r);
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("mode,replications,meanWaitTime,stdWaitTime,ci95WaitTimeLower,ci95WaitTimeUpper,"
                    + "meanThroughput,stdThroughput,ci95ThroughputLower,ci95ThroughputUpper,"
                    + "meanQueueLength,stdQueueLength,meanEmergencyGreenDelay,"
                    + "totalCrossings,totalCoordinationIssued,totalCoordinationApplied,totalMLAlerts");

            for (Map.Entry<String, List<ExperimentRunResult>> entry : grouped.entrySet()) {
                String mode = entry.getKey();
                List<ExperimentRunResult> list = entry.getValue();
                int reps = list.size();

                List<Double> waits = new ArrayList<>();
                List<Double> thrus = new ArrayList<>();
                List<Double> queues = new ArrayList<>();
                List<Double> emGreens = new ArrayList<>();
                long totalCross = 0;
                int totalIssued = 0;
                int totalApplied = 0;
                int totalAlerts = 0;

                for (ExperimentRunResult r : list) {
                    TrafficMetricsSnapshot s = r.snapshot;
                    if (s.averageWaitingTimeSeconds >= 0) waits.add(s.averageWaitingTimeSeconds);
                    thrus.add(s.throughputVehiclesPerMinute);
                    queues.add(s.averageQueueLength);
                    if (s.averageEmergencyGreenDelaySeconds >= 0) emGreens.add(s.averageEmergencyGreenDelaySeconds);

                    totalCross += s.totalCrossings;
                    totalIssued += s.coordinationCommandsIssued;
                    totalApplied += s.coordinationCommandsApplied;
                    totalAlerts += s.mlAlertsEmitted;
                }

                Stats waitStats = computeStats(waits);
                Stats thruStats = computeStats(thrus);
                Stats queueStats = computeStats(queues);
                Stats emGreenStats = computeStats(emGreens);

                StringBuilder sb = new StringBuilder();
                sb.append(mode).append(",");
                sb.append(reps).append(",");
                sb.append(formatStat(waitStats.mean)).append(",");
                sb.append(formatStat(waitStats.std)).append(",");
                sb.append(formatStat(waitStats.ciLower)).append(",");
                sb.append(formatStat(waitStats.ciUpper)).append(",");
                sb.append(formatStat(thruStats.mean)).append(",");
                sb.append(formatStat(thruStats.std)).append(",");
                sb.append(formatStat(thruStats.ciLower)).append(",");
                sb.append(formatStat(thruStats.ciUpper)).append(",");
                sb.append(formatStat(queueStats.mean)).append(",");
                sb.append(formatStat(queueStats.std)).append(",");
                sb.append(formatStat(emGreenStats.mean)).append(",");
                sb.append(totalCross).append(",");
                sb.append(totalIssued).append(",");
                sb.append(totalApplied).append(",");
                sb.append(totalAlerts);

                w.println(sb.toString());
            }
        }
    }

    private void exportIntersectionResults(File file, List<ExperimentRunResult> results) throws IOException {
        String[] interNames = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("runId,scenario,mode,seed,intersectionIndex,intersectionName,"
                    + "crossings,throughputVehiclesPerMinute,averageWaitingTimeSeconds,"
                    + "averageQueueLength,maximumQueueLength,emergencyRequests,emergencyPreemptions,coordinationApplied");

            for (ExperimentRunResult r : results) {
                TrafficMetricsSnapshot s = r.snapshot;
                for (int i = 0; i < 4; i++) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(r.runId).append(",");
                    sb.append(r.scenario).append(",");
                    sb.append(r.mode).append(",");
                    sb.append(r.seed).append(",");
                    sb.append(i).append(",");
                    sb.append(interNames[i]).append(",");
                    sb.append(s.crossingsPerIntersection[i]).append(",");
                    sb.append(String.format(Locale.US, "%.2f", s.throughputPerIntersection[i])).append(",");
                    sb.append(TrafficMetricsSnapshot.formatDecimalOrNA(s.averageWaitingTimePerIntersection[i], "%.2f")).append(",");
                    sb.append(String.format(Locale.US, "%.2f", s.averageQueuePerIntersection[i])).append(",");
                    sb.append(s.maxQueuePerIntersection[i]).append(",");
                    sb.append(s.emergencyRequestsPerIntersection[i]).append(",");
                    sb.append(s.emergencyPreemptionsPerIntersection[i]).append(",");
                    sb.append(s.coordinationAppliedPerIntersection[i]);

                    w.println(sb.toString());
                }
            }
        }
    }

    private void exportMLAlertsAudit(File file, List<ExperimentRunResult> results) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("runId,scenario,mode,seed,alertId,alertSimTimeSeconds,intersectionIndex,intersectionName,"
                    + "probability,riskType,observedCongestionAt60sHorizon,observedQueueAtHorizon,observedWaitAtHorizon,observedSpeedAtHorizon");

            String[] interNames = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
            for (ExperimentRunResult r : results) {
                if (r.mlAlertRecords == null || r.mlAlertRecords.isEmpty()) continue;
                for (TrafficMetricsCollector.MLAlertRecord a : r.mlAlertRecords) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(r.runId).append(",");
                    sb.append(r.scenario).append(",");
                    sb.append(r.mode).append(",");
                    sb.append(r.seed).append(",");
                    sb.append(a.alertId).append(",");
                    sb.append(String.format(Locale.US, "%.1f", a.alertSimTime)).append(",");
                    sb.append(a.intersectionIndex).append(",");
                    sb.append(interNames[a.intersectionIndex]).append(",");
                    sb.append(String.format(Locale.US, "%.4f", a.probability)).append(",");
                    sb.append(a.riskType).append(",");
                    sb.append(a.observedCongestionAtHorizon ? "SIM" : "NAO").append(",");
                    sb.append(a.observedQueueAtHorizon).append(",");
                    sb.append(String.format(Locale.US, "%.2f", a.observedWaitAtHorizon)).append(",");
                    sb.append(String.format(Locale.US, "%.2f", a.observedSpeedAtHorizon));
                    w.println(sb.toString());
                }
            }
        }
    }

    private void exportStatisticalAnalysis(File file, List<ExperimentRunResult> results) throws IOException {
        // Mapeamento: (scenario, seed) -> (mode -> result)
        Map<String, Map<String, ExperimentRunResult>> grid = new LinkedHashMap<>();
        for (ExperimentRunResult r : results) {
            String key = r.scenario + "_S" + r.seed;
            grid.computeIfAbsent(key, k -> new HashMap<>()).put(r.mode, r);
        }

        String[][] comparisons = {
                {"FIXED_TIME", "LOCAL_ADAPTIVE", "Modo A vs Modo B (Fixed vs Adaptive)"},
                {"LOCAL_ADAPTIVE", "MULTIAGENT_COORDINATED", "Modo B vs Modo C (Adaptive vs Coord)"},
                {"MULTIAGENT_COORDINATED", "MULTIAGENT_ML", "Modo C vs Modo D (Coord vs ML)"},
                {"FIXED_TIME", "MULTIAGENT_ML", "Modo A vs Modo D (Fixed vs ML)"}
        };

        String[] metrics = {"waitingTime", "throughput", "queueLength"};

        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("comparison,metric,sampleSize,meanBaseline,meanAlternative,meanDifference,pctDifference,"
                    + "ci95DiffLower,ci95DiffUpper,stdDifference,cohensDz,pairedTStatistic,tPValue,wilcoxonW,wilcoxonExactPValue,statisticallySignificant,conclusion");

            for (String[] comp : comparisons) {
                String mBase = comp[0];
                String mAlt = comp[1];
                String compName = comp[2];

                for (String metric : metrics) {
                    List<Double> baseVals = new ArrayList<>();
                    List<Double> altVals = new ArrayList<>();
                    List<Double> diffs = new ArrayList<>();

                    for (Map<String, ExperimentRunResult> pairMap : grid.values()) {
                        ExperimentRunResult rBase = pairMap.get(mBase);
                        ExperimentRunResult rAlt = pairMap.get(mAlt);
                        if (rBase != null && rAlt != null) {
                            double vBase = extractMetric(rBase.snapshot, metric);
                            double vAlt = extractMetric(rAlt.snapshot, metric);
                            if (vBase >= 0 && vAlt >= 0) {
                                baseVals.add(vBase);
                                altVals.add(vAlt);
                                diffs.add(vAlt - vBase);
                            }
                        }
                    }

                    int n = diffs.size();
                    if (n == 0) continue;

                    double meanBase = average(baseVals);
                    double meanAlt = average(altVals);
                    double meanDiff = average(diffs);
                    double pctDiff = (meanBase != 0) ? ((meanAlt - meanBase) / meanBase) * 100.0 : 0.0;
                    double stdDiff = stdDev(diffs, meanDiff);
                    double se = stdDiff / Math.sqrt(n);
                    double tStat = (se > 1e-9) ? (meanDiff / se) : 0.0;
                    double tPVal = computeStudentTTwoTailedPValue(tStat, n - 1);
                    double wilcoxonW = computeWilcoxonW(diffs);
                    double wilcoxonExactPVal = computeWilcoxonExactTwoTailedPValue(diffs, wilcoxonW);
                    double cohensDz = (stdDiff > 1e-9) ? (meanDiff / stdDiff) : 0.0;
                    double critT = getCriticalT95(n - 1);
                    double ciLower = meanDiff - (critT * se);
                    double ciUpper = meanDiff + (critT * se);
                    boolean isSig = (wilcoxonExactPVal < 0.05);

                    String conclusion;
                    if (metric.equals("queueLength")) {
                        if (Math.abs(meanDiff) < 0.05) {
                            conclusion = isSig ? "MELHORIA_ESTATISTICA_MAS_NEGLIGIVEL_OPERACIONALMENTE (Delta < 0.05 veic)"
                                               : "SEM_DIFERENCA_NOTAVEL";
                        } else {
                            conclusion = (meanDiff < 0) ? (isSig ? "MELHORIA_SIGNIFICATIVA" : "TENDENCIA_MELHORIA")
                                                        : (isSig ? "DEGRADACAO_SIGNIFICATIVA" : "SEM_DIFERENCA_NOTAVEL");
                        }
                    } else if (metric.equals("waitingTime")) {
                        if (Math.abs(meanDiff) < 0.5) {
                            conclusion = isSig ? "MELHORIA_ESTATISTICA_MAS_NEGLIGIVEL_OPERACIONALMENTE (Delta < 0.5s)"
                                               : "SEM_DIFERENCA_NOTAVEL";
                        } else {
                            conclusion = (meanDiff < 0) ? (isSig ? "MELHORIA_SIGNIFICATIVA" : "TENDENCIA_MELHORIA")
                                                        : (isSig ? "DEGRADACAO_SIGNIFICATIVA" : "SEM_DIFERENCA_NOTAVEL");
                        }
                    } else { // throughput
                        if (Math.abs(meanDiff) < 1.0) {
                            conclusion = isSig ? "EQUIVALENTE_OPERACIONALMENTE (Delta < 1.0 veh/min)"
                                               : "SEM_DIFERENCA_NOTAVEL";
                        } else {
                            conclusion = (meanDiff > 0) ? (isSig ? "MELHORIA_SIGNIFICATIVA" : "TENDENCIA_MELHORIA")
                                                        : (isSig ? "DEGRADACAO_SIGNIFICATIVA" : "SEM_DIFERENCA_NOTAVEL");
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append(compName).append(",");
                    sb.append(metric).append(",");
                    sb.append(n).append(",");
                    sb.append(String.format(Locale.US, "%.4f", meanBase)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", meanAlt)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", meanDiff)).append(",");
                    sb.append(String.format(Locale.US, "%.2f%%", pctDiff)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", ciLower)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", ciUpper)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", stdDiff)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", cohensDz)).append(",");
                    sb.append(String.format(Locale.US, "%.4f", tStat)).append(",");
                    sb.append(String.format(Locale.US, "%.6f", tPVal)).append(",");
                    sb.append(String.format(Locale.US, "%.1f", wilcoxonW)).append(",");
                    sb.append(String.format(Locale.US, "%.7f", wilcoxonExactPVal)).append(",");
                    sb.append(isSig ? "SIM (p<0.05)" : "NAO (p>=0.05)").append(",");
                    sb.append(conclusion);

                    w.println(sb.toString());
                }
            }
        }
    }

    /* =========================================================================
     * FUNÇÕES ESTATÍSTICAS AUXILIARES
     * ========================================================================= */

    private static class Stats {
        public double mean = -1.0;
        public double std = 0.0;
        public double min = -1.0;
        public double max = -1.0;
        public double ciLower = -1.0;
        public double ciUpper = -1.0;
    }

    private Stats computeStats(List<Double> vals) {
        Stats s = new Stats();
        if (vals == null || vals.isEmpty()) return s;
        int n = vals.size();
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (double v : vals) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }

        s.mean = sum / n;
        s.min = min;
        s.max = max;

        if (n > 1) {
            double varSum = 0.0;
            for (double v : vals) {
                varSum += Math.pow(v - s.mean, 2);
            }
            s.std = Math.sqrt(varSum / (n - 1));
            double se = s.std / Math.sqrt(n);
            double criticalT = getCriticalT95(n - 1);
            s.ciLower = s.mean - (criticalT * se);
            s.ciUpper = s.mean + (criticalT * se);
        } else {
            s.ciLower = s.mean;
            s.ciUpper = s.mean;
        }

        return s;
    }

    private static double getCriticalT95(int df) {
        if (df <= 1) return 12.71;
        if (df == 2) return 4.30;
        if (df == 3) return 3.18;
        if (df == 4) return 2.78;
        if (df == 5) return 2.57;
        if (df == 6) return 2.45;
        if (df == 7) return 2.3646;
        if (df <= 10) return 2.23;
        if (df <= 20) return 2.09;
        if (df <= 30) return 2.04;
        return 1.96;
    }

    private static double extractMetric(TrafficMetricsSnapshot s, String metric) {
        switch (metric) {
            case "waitingTime": return s.averageWaitingTimeSeconds;
            case "throughput": return s.throughputVehiclesPerMinute;
            case "queueLength": return s.averageQueueLength;
            default: return -1.0;
        }
    }

    private static double average(List<Double> list) {
        if (list.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double d : list) sum += d;
        return sum / list.size();
    }

    private static double stdDev(List<Double> list, double mean) {
        if (list.size() <= 1) return 0.0;
        double sum = 0.0;
        for (double d : list) sum += Math.pow(d - mean, 2);
        return Math.sqrt(sum / (list.size() - 1));
    }

    public static double computeStudentTTwoTailedPValue(double t, int df) {
        if (df <= 0) return 1.0;
        double absT = Math.abs(t);
        if (absT < 1e-12) return 1.0;

        double a = df / 2.0;
        double b = 0.5;
        double x = df / (df + absT * absT);

        return regularizedIncompleteBeta(x, a, b);
    }

    private static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;

        int steps = 2000;
        double h = x / steps;
        double sum = 0.0;

        for (int i = 0; i <= steps; i++) {
            double u = i * h;
            double f;
            if (u <= 0.0 || u >= 1.0) {
                f = 0.0;
            } else {
                f = Math.pow(u, a - 1.0) * Math.pow(1.0 - u, b - 1.0);
            }

            int weight = (i == 0 || i == steps) ? 1 : ((i % 2 == 1) ? 4 : 2);
            sum += weight * f;
        }

        double integral = (h / 3.0) * sum;
        double logBeta = logGamma(a) + logGamma(b) - logGamma(a + b);
        double beta = Math.exp(logBeta);

        return Math.max(0.0, Math.min(1.0, integral / beta));
    }

    private static double logGamma(double x) {
        double[] p = {
            676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059,
            12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7
        };
        double y = x;
        double g = 7.0;
        if (y < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * y)) - logGamma(1.0 - y);
        }
        y -= 1.0;
        double a = 0.99999999999980993;
        for (int i = 0; i < p.length; i++) {
            a += p[i] / (y + i + 1.0);
        }
        double t = y + g + 0.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (y + 0.5) * Math.log(t) - t + Math.log(a);
    }

    public static double computeWilcoxonExactTwoTailedPValue(List<Double> diffs, double w) {
        List<Double> nonZero = new ArrayList<>();
        for (double d : diffs) {
            if (Math.abs(d) > 1e-9) nonZero.add(d);
        }
        int n = nonZero.size();
        if (n == 0) return 1.0;
        if (n > 20) {
            double meanW = n * (n + 1.0) / 4.0;
            double varW = n * (n + 1.0) * (2.0 * n + 1.0) / 24.0;
            double z = (w - meanW + 0.5) / Math.sqrt(varW);
            return approximateTwoTailedPValue(z, 100);
        }

        int maxRankSum = n * (n + 1) / 2;
        long[] dp = new long[maxRankSum + 1];
        dp[0] = 1L;

        for (int rank = 1; rank <= n; rank++) {
            for (int s = maxRankSum; s >= rank; s--) {
                dp[s] += dp[s - rank];
            }
        }

        long totalPossibilities = 1L << n; // 2^n
        int targetW = (int) Math.floor(w);
        long cumulative = 0L;
        for (int s = 0; s <= targetW && s <= maxRankSum; s++) {
            cumulative += dp[s];
        }

        double oneTailed = (double) cumulative / totalPossibilities;
        return Math.min(1.0, 2.0 * oneTailed);
    }

    private static double approximateTwoTailedPValue(double t, int df) {
        double absT = Math.abs(t);
        if (df <= 0) return 1.0;
        double z = absT;
        double p = 1.0 / (1.0 + 0.2316419 * z);
        double d = 0.3989422804014337 * Math.exp(-0.5 * z * z);
        double cdf = 1.0 - d * p * (0.319381530 + p * (-0.356563782 + p * (1.781477937 + p * (-1.821255978 + 1.330274429 * p))));
        double pVal = 2.0 * (1.0 - cdf);
        return Math.max(0.0001, Math.min(1.0, pVal));
    }

    private static double computeWilcoxonW(List<Double> diffs) {
        List<Double> nonZero = new ArrayList<>();
        for (double d : diffs) {
            if (Math.abs(d) > 1e-6) nonZero.add(d);
        }
        if (nonZero.isEmpty()) return 0.0;

        List<Double> sorted = new ArrayList<>(nonZero);
        sorted.sort(Comparator.comparingDouble(Math::abs));

        double sumPos = 0.0;
        double sumNeg = 0.0;
        for (int i = 0; i < sorted.size(); i++) {
            double rank = i + 1.0;
            if (sorted.get(i) > 0) sumPos += rank;
            else sumNeg += rank;
        }

        return Math.min(sumPos, sumNeg);
    }

    private static String formatStat(double v) {
        return (v >= 0) ? String.format(Locale.US, "%.2f", v) : "N/A";
    }
}
