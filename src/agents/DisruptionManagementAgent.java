package agents;

import environment.FleetLifecycleManager;
import environment.NetworkTopologyConfig;
import environment.TrafficEnvironment;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import metrics.TrafficMetricsCollector;
import ml.ModelPersistenceManager;
import ml.TrafficModelBundle;

import java.io.File;
import java.util.Locale;

/**
 * Agente de Gestão e Previsão de Perturbações (Feature 5 / Fase 6.1 - Disruption Management Agent).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class DisruptionManagementAgent extends Agent {

    private TrafficModelBundle modelBundle = null;
    private ml.TrafficModelBundle3x3 modelBundle3x3 = null;
    private boolean mlModelLoaded = false;
    private boolean mlModel3x3Loaded = false;
    private String model3x3Path = ml.TrafficModelBundle3x3.DEFAULT_MODEL_PATH;

    private static class WeatherScenario {
        String name;
        double rainMm;
        double visibilityKm;
        double windKmh;
        boolean incidentFlag;
        double speedMultiplier;

        WeatherScenario(String name, double rainMm, double visibilityKm, double windKmh, boolean incidentFlag, double speedMultiplier) {
            this.name = name;
            this.rainMm = rainMm;
            this.visibilityKm = visibilityKm;
            this.windKmh = windKmh;
            this.incidentFlag = incidentFlag;
            this.speedMultiplier = speedMultiplier;
        }
    }

    private final WeatherScenario[] scenarios = {
        new WeatherScenario("Tempo Seco e Céu Limpo (Atrito 100%)", 0.0, 10.0, 12.0, false, 1.00),
        new WeatherScenario("Chuva Intensa e Piso Escorregadio", 18.5, 2.5, 45.0, false, 0.72),
        new WeatherScenario("Obras na Via com Supressão de Faixa", 0.0, 8.0, 15.0, true, 0.60),
        new WeatherScenario("Nevoeiro Denso com Baixa Visibilidade", 4.0, 0.4, 8.0, false, 0.78)
    };

    private int currentScenarioIndex = 0;

    public static final long ALERT_COOLDOWN_MS = 30_000;
    private long lastAlertTimestamp = 0;

    private volatile long totalInferenceCount = 0;
    private volatile long totalAlertsSent = 0;

    private volatile double lastDisruptionProbability = -1.0;
    private volatile int lastPredictedClass = -1;
    private volatile int lastIntersectionAlerted = -1;

    public static final long INFERENCE_PERIOD_MS = 10_000;
    private String modelPath = ModelPersistenceManager.DEFAULT_MODEL_PATH;

    private static volatile DisruptionManagementAgent activeInstance = null;

    public static DisruptionManagementAgent getActiveInstance() {
        return activeInstance;
    }

    @Override
    protected void setup() {
        activeInstance = this;
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "DisruptionManagementAgent");

        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] instanceof String) {
            this.modelPath = (String) args[0];
        }

        registarNoDF();
        carregarModeloML();
        carregarModeloML3x3();

        if (mlModel3x3Loaded) {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online com modelo 3×3 operacional: " + modelBundle3x3);
        } else if (mlModelLoaded) {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online com modelo ML operacional: " + modelBundle);
        } else {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online em modo FALLBACK (heurística legada). " +
                    "Modelos ML não disponíveis em: " + modelPath + " / " + model3x3Path);
        }

        addBehaviour(new TickerBehaviour(this, INFERENCE_PERIOD_MS) {
            @Override
            protected void onTick() {
                if (environment.SimulationClock.isSimulatedMode()) {
                    return;
                }
                NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
                if (topo != null && topo.getRows() == 3) {
                    if (mlModel3x3Loaded) {
                        executarInferenciaMLOperacional3x3();
                    } else {
                        executarInferenciaLegada();
                    }
                } else {
                    if (mlModelLoaded) {
                        executarInferenciaMLOperacional();
                    } else {
                        executarInferenciaLegada();
                    }
                }
            }
        });
    }

    private void carregarModeloML3x3() {
        File modelFile = new File(model3x3Path);
        if (!modelFile.exists()) {
            mlModel3x3Loaded = false;
            return;
        }

        try {
            modelBundle3x3 = ml.TrafficModelBundle3x3.loadFromFile(model3x3Path);
            mlModel3x3Loaded = true;

            System.out.println("[DisruptionManagementAgent] Modelo 3×3 carregado com sucesso:");
            System.out.println("  • Nome: " + modelBundle3x3.getModelName());
            System.out.println("  • Versão: " + modelBundle3x3.getModelVersion());
            System.out.println("  • Topologia: " + modelBundle3x3.getTopology());
            System.out.println("  • Threshold: " + modelBundle3x3.getDecisionThreshold());
            System.out.println("  • Horizonte: " + modelBundle3x3.getPredictionHorizonSeconds() + "s");
            System.out.println("  • Features (12): " + java.util.Arrays.toString(modelBundle3x3.getFeatureNames()));
            System.out.println("  • SHA-256 Dataset: " + modelBundle3x3.getDatasetSha256());

            File csvFile = new File("data/3x3/dataset_traffic_3x3.csv");
            if (csvFile.exists()) {
                String realSha = ml.TrafficModelBundle3x3.computeFileSha256(csvFile);
                boolean validHash = modelBundle3x3.isDatasetCompatible(realSha);
                System.out.println("  • Validação Integridade Dataset CSV 3x3: " + (validHash ? "VÁLIDO (100% íntegro)" : "INCOMPATÍVEL"));
            }

        } catch (Exception e) {
            System.err.println("[DisruptionManagementAgent] Erro ao carregar modelo ML 3x3: " + e.getMessage());
            mlModel3x3Loaded = false;
            modelBundle3x3 = null;
        }
    }

    private void carregarModeloML() {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            System.err.println("[DisruptionManagementAgent] Ficheiro de modelo não encontrado: " + modelPath);
            mlModelLoaded = false;
            return;
        }

        try {
            modelBundle = ModelPersistenceManager.loadModel(modelPath);
            mlModelLoaded = true;

            System.out.println("[DisruptionManagementAgent] Modelo ML carregado com sucesso:");
            System.out.println("  • Nome: " + modelBundle.getModelName());
            System.out.println("  • Versão: " + modelBundle.getModelVersion());
            System.out.println("  • Threshold: " + modelBundle.getDecisionThreshold());
            System.out.println("  • Horizonte: " + modelBundle.getPredictionHorizonSeconds() + "s");
            System.out.println("  • Features: " + java.util.Arrays.toString(modelBundle.getFeatureNames()));
            System.out.println("  • SHA-256 Dataset: " + modelBundle.getDatasetSha256());

            File csvFile = new File("data/traffic_disruption_dataset.csv");
            if (csvFile.exists()) {
                String realSha = TrafficModelBundle.computeFileSha256(csvFile);
                boolean validHash = modelBundle.isDatasetCompatible(realSha);
                System.out.println("  • Validação Integridade Dataset CSV: " + (validHash ? "VÁLIDO (100% íntegro)" : "INCOMPATÍVEL"));
            }

        } catch (Exception e) {
            System.err.println("[DisruptionManagementAgent] Erro ao carregar modelo ML: " + e.getMessage());
            mlModelLoaded = false;
            modelBundle = null;
        }
    }

    private void executarInferenciaMLOperacional() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        NetworkTopologyConfig topo = env.getTopology();
        int n = topo.getIntersectionCount();

        // Proteção de Validade Científica (Fase 6.2-D):
        // O modelo foi treinado exclusivamente na topologia 2×2 (4 cruzamentos).
        // Em malha 3×3 (9 cruzamentos), a atuação operacional preditiva é desativada
        // explicitamente para evitar extrapolação não validada, preservando os artefactos históricos.
        if (n > 4 || topo.getRows() == 3) {
            env.setMLPredictionNotice("ML Preditivo: Validação 3x3 Pendente (Modelo 2x2 Preservado)");
            System.out.println("[DisruptionManagementAgent - ML] Atuação operacional ML suspensa na malha 3x3: Validação pendente (Modelo 2x2 histórico preservado).");
            return;
        }

        double maxProbability = 0.0;
        int maxProbIntersection = -1;
        int maxPredClass = 0;

        for (int i = 0; i < n; i++) {
            double[] features = extrairFeaturesTempoReal(env, i);
            double probability = modelBundle.predictProbability(features);
            int predicted = probability >= modelBundle.getDecisionThreshold() ? 1 : 0;

            totalInferenceCount++;

            if (probability > maxProbability) {
                maxProbability = probability;
                maxProbIntersection = i;
                maxPredClass = predicted;
            }

            String crossingName = getCrossingName(i);
            int probPercent = (int) Math.round(probability * 100);
            String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");

            System.out.println(String.format(Locale.US,
                    "[DisruptionManagementAgent - ML] Cruzamento %s: P(Cong)=%.3f (%d%% %s) | Classe=%d | Features=%s",
                    crossingName, probability, probPercent, nivel, predicted,
                    formatFeatures(features)));
        }

        lastDisruptionProbability = maxProbability;
        lastPredictedClass = maxPredClass;
        lastIntersectionAlerted = maxProbIntersection;

        double environmentalSpeedMultiplier = env.getEnvironmentalSpeedMultiplier();

        int probPercent = (int) Math.round(maxProbability * 100);
        String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");
        String hudMsg = String.format(Locale.US, "ML Preditivo: %d%% risco (%s) [C%d]",
                probPercent, nivel, (maxProbIntersection + 1));
        env.setMLPredictionNotice(hudMsg);

        if (maxPredClass == 1) {
            long now = environment.SimulationClock.currentTimeMillis();
            if ((now - lastAlertTimestamp) >= ALERT_COOLDOWN_MS) {
                emitirDisturbanceAlert(maxProbIntersection, maxProbability, environmentalSpeedMultiplier);
                lastAlertTimestamp = now;
                totalAlertsSent++;
            } else {
                System.out.println("[DisruptionManagementAgent - ML] Alerta suprimido por cooldown (restam " +
                        ((ALERT_COOLDOWN_MS - (now - lastAlertTimestamp)) / 1000) + "s).");
            }
        }
    }

    public void executarInferenciaMLOperacional3x3() {
        if (!mlModel3x3Loaded || modelBundle3x3 == null) {
            TrafficEnvironment.getInstance().setMLPredictionNotice("ML Preditivo 3x3: Modelo Indisponível");
            return;
        }

        TrafficEnvironment env = TrafficEnvironment.getInstance();
        NetworkTopologyConfig topo = env.getTopology();
        int n = topo.getIntersectionCount();

        double maxProbability = 0.0;
        int maxProbIntersection = -1;
        int maxPredClass = 0;
        int threatenedAxisForMax = 0;

        for (int i = 0; i < n; i++) {
            double[] features = extrairFeaturesTempoReal3x3(env, i);
            double probability = modelBundle3x3.predictProbability(features);
            int predicted = probability >= modelBundle3x3.getDecisionThreshold() ? 1 : 0;

            totalInferenceCount++;

            int qNS = env.getWaitingVehiclesCount(i, 0);
            int qEW = env.getWaitingVehiclesCount(i, 1);
            int axis = (qEW > qNS) ? 1 : 0;

            if (probability > maxProbability) {
                maxProbability = probability;
                maxProbIntersection = i;
                maxPredClass = predicted;
                threatenedAxisForMax = axis;
            }

            String crossingName = getCrossingName(i);
            int probPercent = (int) Math.round(probability * 100);
            String nivel = (probability >= 0.25) ? "CRÍTICO" : ((probability >= modelBundle3x3.getDecisionThreshold()) ? "MODERADO" : "BAIXO");

            System.out.println(String.format(Locale.US,
                    "[DisruptionManagementAgent - ML 3x3] Cruzamento %s: P(Cong)=%.3f (%d%% %s) | Classe=%d | Eixo=%s",
                    crossingName, probability, probPercent, nivel, predicted, (axis == 0 ? "NS" : "EW")));
        }

        lastDisruptionProbability = maxProbability;
        lastPredictedClass = maxPredClass;
        lastIntersectionAlerted = maxProbIntersection;

        int probPercent = (int) Math.round(maxProbability * 100);
        String nivel = (maxProbability >= 0.25) ? "CRÍTICO" : ((maxProbability >= modelBundle3x3.getDecisionThreshold()) ? "MODERADO" : "BAIXO");
        String hudMsg = String.format(Locale.US, "ML 3x3: %d%% risco (%s) [C%d]",
                probPercent, nivel, (maxProbIntersection + 1));
        env.setMLPredictionNotice(hudMsg);

        if (maxPredClass == 1) {
            long now = environment.SimulationClock.currentTimeMillis();
            if ((now - lastAlertTimestamp) >= ALERT_COOLDOWN_MS) {
                emitirDisturbanceAlert3x3(maxProbIntersection, maxProbability, threatenedAxisForMax);
                lastAlertTimestamp = now;
                totalAlertsSent++;
            } else {
                System.out.println("[DisruptionManagementAgent - ML 3x3] Alerta suprimido por cooldown (restam " +
                        ((ALERT_COOLDOWN_MS - (now - lastAlertTimestamp)) / 1000) + "s).");
            }
        }
    }

    public double[] extrairFeaturesTempoReal3x3(TrafficEnvironment env, int intersectionIndex) {
        int queueNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
        int queueEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
        int totalQueue = queueNS + queueEW;
        double queueRatio = (totalQueue > 0)
                ? ((double) (queueNS - queueEW) / (double) (totalQueue + 1.0))
                : 0.0;

        int approachingNS = env.getApproachingVehiclesCount(intersectionIndex, 0);
        int approachingEW = env.getApproachingVehiclesCount(intersectionIndex, 1);
        int totalApproaching = approachingNS + approachingEW;

        double avgSpeed = env.getAverageSpeed(intersectionIndex);
        double avgWait = env.getAverageWaitingTime(intersectionIndex);

        environment.IntersectionSafetyAuthority auth = environment.IntersectionSafetyAuthority.getAuthority(intersectionIndex);
        double currentPhaseCode = 0.5;
        double greenElapsedSec = 0.0;
        if (auth != null) {
            environment.CitySimulationGUI.LightState stateNS = auth.getLightState(0);
            environment.CitySimulationGUI.LightState stateEW = auth.getLightState(1);
            if (stateNS == environment.CitySimulationGUI.LightState.GREEN) {
                currentPhaseCode = 0.0;
            } else if (stateEW == environment.CitySimulationGUI.LightState.GREEN) {
                currentPhaseCode = 1.0;
            } else {
                currentPhaseCode = 0.5;
            }
            greenElapsedSec = auth.getPhaseElapsedMs() / 1000.0;
        }

        int weatherCode = env.getWeatherCondition();
        double roadFrictionMu = 1.00;
        if (weatherCode == 1) {
            roadFrictionMu = 0.70;
        } else if (weatherCode == 2) {
            roadFrictionMu = 0.45;
        }

        int row = intersectionIndex / 3;
        int col = intersectionIndex % 3;
        int maxNeighborQueue = 0;
        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};

        for (int k = 0; k < 4; k++) {
            int nr = row + dr[k];
            int nc = col + dc[k];
            if (nr >= 0 && nr < 3 && nc >= 0 && nc < 3) {
                int neighborIndex = nr * 3 + nc;
                int nQ = env.getWaitingVehiclesCount(neighborIndex, 0) + env.getWaitingVehiclesCount(neighborIndex, 1);
                if (nQ > maxNeighborQueue) {
                    maxNeighborQueue = nQ;
                }
            }
        }

        return new double[] {
            queueNS,
            queueEW,
            totalQueue,
            queueRatio,
            totalApproaching,
            avgSpeed,
            avgWait,
            currentPhaseCode,
            greenElapsedSec,
            (double) weatherCode,
            roadFrictionMu,
            (double) maxNeighborQueue
        };
    }

    private void emitirDisturbanceAlert3x3(int intersectionIndex, double probability, int targetAxis) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("DISTURBANCE_ALERT");

        long now = environment.SimulationClock.currentTimeMillis();
        msg.setConversationId("ml-alert-3x3-" + now);

        long validUntil = now + (long) (modelBundle3x3.getPredictionHorizonSeconds() * 1000);
        String alertId = "ALERT_ML_3X3_C" + (intersectionIndex + 1) + "_" + now;
        String riskType = (probability >= 0.25) ? "CRITICAL_CONGESTION" : "MODERATE_CONGESTION";

        String content = String.format(Locale.US,
                "alertId=%s;type=ML_PREDICTION;intersection=%d;probability=%.4f;predicted=1;speedMultiplier=1.00;horizon=%.0f;model=%s;threshold=%.2f;timestamp=%d;validUntil=%d;riskType=%s;targetAxis=%d;topology=GRID_3X3",
                alertId, intersectionIndex, probability,
                modelBundle3x3.getPredictionHorizonSeconds(),
                modelBundle3x3.getModelName(),
                modelBundle3x3.getDecisionThreshold(),
                now, validUntil, riskType, targetAxis);
        msg.setContent(content);

        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        for (int i = 0; i < topo.getIntersectionCount(); i++) {
            String c = topo.getIntersectionName(i);
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }
        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();

        System.out.println("\n[DisruptionManagementAgent - ML 3x3] >>> DISTURBANCE_ALERT EMITIDO <<<");
        System.out.println("  • Alerta ID: " + alertId);
        System.out.println("  • Cruzamento mais crítico: C" + (intersectionIndex + 1) + " (" + getCrossingName(intersectionIndex) + ")");
        System.out.println("  • Eixo Ameaçado: " + (targetAxis == 0 ? "Norte-Sul (NS)" : "Este-Oeste (EW)"));
        System.out.println("  • Probabilidade: " + (int) Math.round(probability * 100) + "% (" + riskType + ")");
        System.out.println("  • Limiar de Decisão: " + (int) Math.round(modelBundle3x3.getDecisionThreshold() * 100) + "%");
        System.out.println("  • Horizonte de Previsão: " + (int) modelBundle3x3.getPredictionHorizonSeconds() + "s\n");
    }

    public double[] extrairFeaturesTempoReal(TrafficEnvironment env, int intersectionIndex) {
        int approachingNS = env.getApproachingVehiclesCount(intersectionIndex, 0);
        int approachingEW = env.getApproachingVehiclesCount(intersectionIndex, 1);
        int trafficVolume = approachingNS + approachingEW;

        int waitingNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
        int waitingEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
        int queueLength = waitingNS + waitingEW;

        double averageSpeed = env.getAverageSpeed(intersectionIndex);
        double averageWaitingTime = env.getAverageWaitingTime(intersectionIndex);
        int roadCapacity = env.getRoadCapacity();
        int weatherCondition = env.getWeatherCondition();
        int incidentPresent = env.getIncidentPresent();

        return new double[] {
            trafficVolume, queueLength, averageSpeed, averageWaitingTime,
            roadCapacity, weatherCondition, incidentPresent
        };
    }

    private void emitirDisturbanceAlert(int intersectionIndex, double probability, double speedMultiplier) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("DISTURBANCE_ALERT");

        long now = environment.SimulationClock.currentTimeMillis();
        msg.setConversationId("ml-alert-" + now);

        long validUntil = now + (long) (modelBundle.getPredictionHorizonSeconds() * 1000);
        String alertId = "ALERT_ML_C" + (intersectionIndex + 1) + "_" + now;
        String riskType = (probability >= 0.70) ? "CRITICAL_CONGESTION" : "MODERATE_CONGESTION";

        String content = String.format(Locale.US,
                "alertId=%s;type=ML_PREDICTION;intersection=%d;probability=%.4f;predicted=1;speedMultiplier=%.2f;horizon=%.0f;model=%s;threshold=%.2f;timestamp=%d;validUntil=%d;riskType=%s",
                alertId, intersectionIndex, probability, speedMultiplier,
                modelBundle.getPredictionHorizonSeconds(),
                modelBundle.getModelName(),
                modelBundle.getDecisionThreshold(),
                now, validUntil, riskType);
        msg.setContent(content);

        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        for (int i = 0; i < topo.getIntersectionCount(); i++) {
            String c = topo.getIntersectionName(i);
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }
        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();

        System.out.println("\n[DisruptionManagementAgent - ML] >>> DISTURBANCE_ALERT EMITIDO <<<");
        System.out.println("  • Alerta ID: " + alertId);
        System.out.println("  • Cruzamento mais crítico: C" + (intersectionIndex + 1) + " (" + getCrossingName(intersectionIndex) + ")");
        System.out.println("  • Probabilidade: " + (int) Math.round(probability * 100) + "% (" + riskType + ")");
        System.out.println("  • Multiplicador de Velocidade: " + (int) (speedMultiplier * 100) + "%");
        System.out.println("  • Horizonte de Previsão: " + (int) modelBundle.getPredictionHorizonSeconds() + "s\n");
    }

    private void executarInferenciaLegada() {
        WeatherScenario current = scenarios[currentScenarioIndex];
        currentScenarioIndex = (currentScenarioIndex + 1) % scenarios.length;

        double z = -2.5 + (0.18 * current.rainMm) - (0.35 * current.visibilityKm) + (0.04 * current.windKmh) + (current.incidentFlag ? 3.0 : 0.0);
        double disruptionProbability = 1.0 / (1.0 + Math.exp(-z));
        int probPercent = (int) Math.round(disruptionProbability * 100);

        String nivelRisco = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");

        System.out.println("\n[DisruptionManagementAgent - FALLBACK] >>> Nova Inferência Meteorológica / Ocorrências <<<");
        System.out.println("  • Cenário: " + current.name);
        System.out.println("  • Features: Chuva=" + current.rainMm + "mm/h | Visibilidade=" + current.visibilityKm + "km | Vento=" + current.windKmh + "km/h");
        System.out.println("  • Probabilidade de Perturbação: " + probPercent + "% (" + nivelRisco + ")");
        System.out.println("  • Fator de Velocidade Ajustado: " + (int)(current.speedMultiplier * 100) + "%\n");

        String statusHUD = current.name + " (" + probPercent + "% risco)";
        TrafficEnvironment.getInstance().setDisruptionNotice(statusHUD, current.speedMultiplier);

        difundirAlertaFIPA(current.name, probPercent, current.speedMultiplier);
    }

    private void difundirAlertaFIPA(String descricao, int probabilidade, double fatorVelocidade) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("disruption-alert");
        msg.setConversationId("disruption-notice-" + System.currentTimeMillis());
        msg.setContent("ALERTA_DISRUPCAO: [Modelo ML] " + descricao 
                + " | Probabilidade: " + probabilidade 
                + "% | Multiplicador de Deslocamento: " + fatorVelocidade);

        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        for (int i = 0; i < topo.getIntersectionCount(); i++) {
            String c = topo.getIntersectionName(i);
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }

        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("disruption-management-service");
        sd.setName("DisruptionManager-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[DisruptionManagementAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    private static String getCrossingName(int index) {
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        if (index >= 0 && index < topo.getIntersectionCount()) {
            return topo.getIntersectionName(index);
        }
        return "C" + (index + 1);
    }

    private static String formatFeatures(double[] features) {
        return String.format(Locale.US, "[Vol=%d, Queue=%d, Spd=%.2f, Wait=%.1f, Cap=%d, Weath=%d, Inc=%d]",
                (int) features[0], (int) features[1], features[2], features[3],
                (int) features[4], (int) features[5], (int) features[6]);
    }

    public boolean isMLModelLoaded() {
        return mlModelLoaded;
    }

    public TrafficModelBundle getModelBundle() {
        return modelBundle;
    }

    public long getTotalInferenceCount() {
        return totalInferenceCount;
    }

    public long getTotalAlertsSent() {
        return totalAlertsSent;
    }

    public double getLastDisruptionProbability() {
        return lastDisruptionProbability;
    }

    public int getLastPredictedClass() {
        return lastPredictedClass;
    }

    public int getLastIntersectionAlerted() {
        return lastIntersectionAlerted;
    }

    public long getLastAlertTimestamp() {
        return lastAlertTimestamp;
    }

    public void setLastAlertTimestamp(long timestamp) {
        this.lastAlertTimestamp = timestamp;
    }

    public boolean isMLModel3x3Loaded() {
        return mlModel3x3Loaded;
    }

    public ml.TrafficModelBundle3x3 getModelBundle3x3() {
        return modelBundle3x3;
    }

    public void setModelBundle3x3(ml.TrafficModelBundle3x3 bundle) {
        this.modelBundle3x3 = bundle;
        this.mlModel3x3Loaded = (bundle != null);
    }

    public synchronized void triggerScheduledInference() {
        NetworkTopologyConfig topo = TrafficEnvironment.getInstance().getTopology();
        if (topo != null && topo.getRows() == 3) {
            if (mlModel3x3Loaded && modelBundle3x3 != null) {
                executarInferenciaMLOperacional3x3();
            } else {
                executarInferenciaLegada();
            }
        } else {
            if (mlModelLoaded && modelBundle != null) {
                executarInferenciaMLOperacional();
            } else {
                executarInferenciaLegada();
            }
        }
    }

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        if (activeInstance == this) {
            activeInstance = null;
        }
        try {
            DFService.deregister(this);
            System.out.println("[DisruptionManagementAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
