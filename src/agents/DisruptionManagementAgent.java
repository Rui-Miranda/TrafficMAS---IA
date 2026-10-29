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
    private boolean mlModelLoaded = false;

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

        if (mlModelLoaded) {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online com modelo ML operacional: " + modelBundle);
        } else {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online em modo FALLBACK (heurística legada). " +
                    "Modelo ML não disponível em: " + modelPath);
        }

        addBehaviour(new TickerBehaviour(this, INFERENCE_PERIOD_MS) {
            @Override
            protected void onTick() {
                if (environment.SimulationClock.isSimulatedMode()) {
                    return;
                }
                if (mlModelLoaded) {
                    executarInferenciaMLOperacional();
                } else {
                    executarInferenciaLegada();
                }
            }
        });
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

    public synchronized void triggerScheduledInference() {
        if (mlModelLoaded) {
            executarInferenciaMLOperacional();
        } else {
            executarInferenciaLegada();
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
