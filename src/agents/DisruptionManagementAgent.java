package agents;

import environment.TrafficEnvironment;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import ml.ModelPersistenceManager;
import ml.TrafficModelBundle;

import java.io.File;
import java.util.Locale;

/**
 * Agente de Gestão e Previsão de Perturbações (Feature 5 - Disruption Management Agent).
 *
 * Responsável por antecipar perturbações e anomalias de circulação através de um modelo
 * de Inteligência Artificial / Machine Learning preditivo (condições climatéricas adversas,
 * sinistros, redução de atrito ou obras na via).
 *
 * Fase 4 — Etapa 4: Integração Operacional do ML
 * - Carrega o modelo serializado (data/traffic_model.bin) na inicialização.
 * - Comportamento periódico a cada 10 segundos de tempo real.
 * - Extrai 7 features em tempo real dos sensores do TrafficEnvironment para cada cruzamento.
 * - Executa inferência com o threshold congelado (τ = 0.40).
 * - Emite alertas FIPA-ACL DISTURBANCE_ALERT para CentralCoordinationAgent com debouncing (30s cooldown).
 * - Fallback gracioso para heurística legada se o ficheiro .bin não existir.
 *
 * Feature ordering (estritamente alinhado com o dataset de treino):
 *   [0] trafficVolume, [1] queueLength, [2] averageSpeed,
 *   [3] averageWaitingTime, [4] roadCapacity, [5] weatherCondition, [6] incidentPresent
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class DisruptionManagementAgent extends Agent {

    // ======================== MODELO ML ========================
    /** Modelo serializado carregado em setup() */
    private TrafficModelBundle modelBundle = null;

    /** Flag indicativa de carregamento com sucesso */
    private boolean mlModelLoaded = false;

    // ======================== CENÁRIOS LEGADOS (FALLBACK) ========================
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

    // ======================== DEBOUNCING / COOLDOWN ========================
    /** Cooldown mínimo entre alertas DISTURBANCE_ALERT consecutivos (30 segundos) */
    public static final long ALERT_COOLDOWN_MS = 30_000;

    /** Timestamp do último alerta DISTURBANCE_ALERT emitido com sucesso */
    private long lastAlertTimestamp = 0;

    /** Contador de inferências executadas (exposto para testes) */
    private volatile long totalInferenceCount = 0;

    /** Contador de alertas emitidos (exposto para testes) */
    private volatile long totalAlertsSent = 0;

    /** Último resultado de inferência (para consulta em testes) */
    private volatile double lastDisruptionProbability = -1.0;
    private volatile int lastPredictedClass = -1;
    private volatile int lastIntersectionAlerted = -1;

    // ======================== CONFIGURAÇÃO ========================
    /** Período do TickerBehaviour de inferência em milissegundos */
    public static final long INFERENCE_PERIOD_MS = 10_000;

    /** Caminho do modelo (pode ser substituído em testes) */
    private String modelPath = ModelPersistenceManager.DEFAULT_MODEL_PATH;

    @Override
    protected void setup() {
        // 0. Parâmetros opcionais do agente (usado em testes para apontar para ficheiro alternativo)
        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] instanceof String) {
            this.modelPath = (String) args[0];
        }

        // 1. Registo no Directory Facilitator
        registarNoDF();

        // 2. Carregamento do Modelo ML serializado
        carregarModeloML();

        if (mlModelLoaded) {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online com modelo ML operacional: " + modelBundle);
        } else {
            System.out.println("[DisruptionManagementAgent] Agente preditivo online em modo FALLBACK (heurística legada). " +
                    "Modelo ML não disponível em: " + modelPath);
        }

        // 3. Comportamento Periódico: Inferência ML a cada 10 segundos
        addBehaviour(new TickerBehaviour(this, INFERENCE_PERIOD_MS) {
            @Override
            protected void onTick() {
                if (mlModelLoaded) {
                    executarInferenciaMLOperacional();
                } else {
                    executarInferenciaLegada();
                }
            }
        });
    }

    // ======================== CARREGAMENTO DO MODELO ========================

    /**
     * Carrega o TrafficModelBundle a partir do ficheiro binário serializado.
     * Em caso de falha, ativa o modo fallback sem interromper o agente.
     */
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

    // ======================== INFERÊNCIA ML OPERACIONAL ========================

    /**
     * Executa a inferência ML operacional sobre os 4 cruzamentos em tempo real.
     *
     * Para cada cruzamento:
     * 1. Extrai o vetor de 7 features a partir dos sensores do TrafficEnvironment.
     * 2. Invoca modelBundle.predictProbability(features) para obter P(congestionamento).
     * 3. Se P >= threshold (0.40), e o cooldown de 30s foi respeitado, emite DISTURBANCE_ALERT.
     * 4. Atualiza o HUD com o estado preditivo mais severo.
     */
    private void executarInferenciaMLOperacional() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();

        double maxProbability = 0.0;
        int maxProbIntersection = -1;
        int maxPredClass = 0;

        for (int i = 0; i < 4; i++) {
            double[] features = extrairFeaturesTempoReal(env, i);
            double probability = modelBundle.predictProbability(features);
            int predicted = probability >= modelBundle.getDecisionThreshold() ? 1 : 0;

            totalInferenceCount++;

            if (probability > maxProbability) {
                maxProbability = probability;
                maxProbIntersection = i;
                maxPredClass = predicted;
            }

            // Log detalhado da inferência por cruzamento
            String crossingName = getCrossingName(i);
            int probPercent = (int) Math.round(probability * 100);
            String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");

            System.out.println(String.format(Locale.US,
                    "[DisruptionManagementAgent - ML] Cruzamento %s: P(Cong)=%.3f (%d%% %s) | Classe=%d | Features=%s",
                    crossingName, probability, probPercent, nivel, predicted,
                    formatFeatures(features)));
        }

        // Atualiza estado global para consulta
        lastDisruptionProbability = maxProbability;
        lastPredictedClass = maxPredClass;
        lastIntersectionAlerted = maxProbIntersection;

        // O multiplicador de velocidade físico é derivado exclusivamente do ambiente real (atrito/clima/obras)
        // e é independente da previsão do modelo ML, evitando loops circulares e desaceleração artificial.
        double environmentalSpeedMultiplier = env.getEnvironmentalSpeedMultiplier();

        // Atualiza HUD com diagnóstico preditivo sem alterar velocidade física dos veículos
        int probPercent = (int) Math.round(maxProbability * 100);
        String nivel = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");
        String hudMsg = String.format(Locale.US, "ML Preditivo: %d%% risco (%s) [C%d]",
                probPercent, nivel, (maxProbIntersection + 1));
        env.setMLPredictionNotice(hudMsg);

        // Emite alerta DISTURBANCE_ALERT se a probabilidade ultrapassa o threshold e o cooldown foi respeitado
        if (maxPredClass == 1) {
            long now = System.currentTimeMillis();
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

    /**
     * Extrai o vetor de 7 features em tempo real para um cruzamento específico.
     *
     * Feature ordering (OBRIGATÓRIO — alinhado com o dataset de treino):
     *   [0] trafficVolume     — Total de veículos em aproximação (NS + EW)
     *   [1] queueLength       — Total de veículos em fila de espera (NS + EW)
     *   [2] averageSpeed      — Velocidade média estimada (proxy: 2.0 - 0.3 * queueLength)
     *   [3] averageWaitingTime — Tempo médio de espera em segundos
     *   [4] roadCapacity      — Capacidade da via (4 faixas = constante 4)
     *   [5] weatherCondition  — Condição climatérica codificada (0=SECO, 1=CHUVA, 2=NEVOEIRO)
     *   [6] incidentPresent   — Presença de incidente (0=NÃO, 1=SIM)
     */
    public double[] extrairFeaturesTempoReal(TrafficEnvironment env, int intersectionIndex) {
        // [0] Traffic Volume: soma dos veículos em aproximação nos dois eixos (dist <= 220px)
        int approachingNS = env.getApproachingVehiclesCount(intersectionIndex, 0);
        int approachingEW = env.getApproachingVehiclesCount(intersectionIndex, 1);
        int trafficVolume = approachingNS + approachingEW;

        // [1] Queue Length: soma dos veículos em fila de espera nos dois eixos (dist <= 220px, speed < 0.2)
        int waitingNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
        int waitingEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
        int queueLength = waitingNS + waitingEW;

        // [2] Average Speed: velocidade média física real medida no cruzamento (px/tick)
        double averageSpeed = env.getAverageSpeed(intersectionIndex);

        // [3] Average Waiting Time: tempo médio real de espera das viaturas imobilizadas na fila (segundos)
        double averageWaitingTime = env.getAverageWaitingTime(intersectionIndex);

        // [4] Road Capacity: capacidade física da via (4 faixas, ou 2 se houver supressão por obras/acidente)
        int roadCapacity = env.getRoadCapacity();

        // [5] Weather Condition: condição meteorológica física atual (0=SECO, 1=CHUVA, 2=NEVOEIRO)
        int weatherCondition = env.getWeatherCondition();

        // [6] Incident Present: presença física de obras/acidente na via (0=NÃO, 1=SIM)
        int incidentPresent = env.getIncidentPresent();

        return new double[] {
            trafficVolume, queueLength, averageSpeed, averageWaitingTime,
            roadCapacity, weatherCondition, incidentPresent
        };
    }

    /**
     * Infere a condição climatérica a partir da mensagem de perturbação do HUD.
     * 0 = SECO, 1 = CHUVA, 2 = NEVOEIRO
     */
    private int inferirCondicaoClimatica(String notice) {
        if (notice == null) return 0;
        String lower = notice.toLowerCase();
        if (lower.contains("chuva") || lower.contains("rain") || lower.contains("escorregadi")) return 1;
        if (lower.contains("nevoeiro") || lower.contains("fog") || lower.contains("visibilidade")) return 2;
        return 0;
    }

    /**
     * Infere a presença de incidente a partir da mensagem de perturbação do HUD.
     * 0 = NÃO, 1 = SIM
     */
    private int inferirPresencaIncidente(String notice) {
        if (notice == null) return 0;
        String lower = notice.toLowerCase();
        if (lower.contains("obras") || lower.contains("acident") || lower.contains("incident")
                || lower.contains("supressão") || lower.contains("roadwork")) return 1;
        return 0;
    }

    /**
     * Calcula o multiplicador de velocidade adaptativo com base na probabilidade de congestionamento.
     *
     * P >= 0.70 → speedMultiplier = 0.60 (redução severa)
     * P >= 0.40 → speedMultiplier = 0.80 (redução moderada)
     * P < 0.40  → speedMultiplier = 1.00 (condições normais)
     */
    private double calcularMultiplicadorVelocidade(double probability) {
        if (probability >= 0.70) return 0.60;
        if (probability >= 0.40) return 0.80;
        return 1.00;
    }

    // ======================== EMISSÃO DE ALERTAS FIPA-ACL ========================

    /**
     * Emite uma mensagem FIPA-ACL INFORM com ontologia "DISTURBANCE_ALERT" para
     * CentralCoordinationAgent e todos os semáforos.
     *
     * Conteúdo estruturado (chave=valor; separado por ponto-e-vírgula):
     *   type=ML_PREDICTION;intersection=<idx>;probability=<p>;predicted=1;
     *   speedMultiplier=<mult>;horizon=<horizon_s>;model=<name>;threshold=<tau>
     */
    private void emitirDisturbanceAlert(int intersectionIndex, double probability, double speedMultiplier) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("DISTURBANCE_ALERT");
        msg.setConversationId("ml-alert-" + System.currentTimeMillis());

        long now = System.currentTimeMillis();
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

        // Destinatários: todos os semáforos dos 4 cruzamentos + coordenador central
        String[] crossings = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
        for (String c : crossings) {
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }
        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);

        System.out.println("\n[DisruptionManagementAgent - ML] >>> DISTURBANCE_ALERT EMITIDO <<<");
        System.out.println("  • Alerta ID: " + alertId);
        System.out.println("  • Cruzamento mais crítico: C" + (intersectionIndex + 1) + " (" + getCrossingName(intersectionIndex) + ")");
        System.out.println("  • Probabilidade: " + (int) Math.round(probability * 100) + "% (" + riskType + ")");
        System.out.println("  • Multiplicador de Velocidade: " + (int) (speedMultiplier * 100) + "%");
        System.out.println("  • Horizonte de Previsão: " + (int) modelBundle.getPredictionHorizonSeconds() + "s\n");
    }

    // ======================== FALLBACK LEGADO ========================

    /**
     * Executa a inferência preditiva heurística legada (quando o modelo ML não está disponível).
     * Preserva integralmente o comportamento das Fases 1–3.
     */
    private void executarInferenciaLegada() {
        WeatherScenario current = scenarios[currentScenarioIndex];
        currentScenarioIndex = (currentScenarioIndex + 1) % scenarios.length;

        // Modelo Logístico de Machine Learning: P(disrupção) = 1 / (1 + exp(-z))
        double z = -2.5 + (0.18 * current.rainMm) - (0.35 * current.visibilityKm) + (0.04 * current.windKmh) + (current.incidentFlag ? 3.0 : 0.0);
        double disruptionProbability = 1.0 / (1.0 + Math.exp(-z));
        int probPercent = (int) Math.round(disruptionProbability * 100);

        String nivelRisco = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");

        System.out.println("\n[DisruptionManagementAgent - FALLBACK] >>> Nova Inferência Meteorológica / Ocorrências <<<");
        System.out.println("  • Cenário: " + current.name);
        System.out.println("  • Features: Chuva=" + current.rainMm + "mm/h | Visibilidade=" + current.visibilityKm + "km | Vento=" + current.windKmh + "km/h");
        System.out.println("  • Probabilidade de Perturbação: " + probPercent + "% (" + nivelRisco + ")");
        System.out.println("  • Fator de Velocidade Ajustado: " + (int)(current.speedMultiplier * 100) + "%\n");

        // Atualiza a fachada do ambiente e a interface gráfica
        String statusHUD = current.name + " (" + probPercent + "% risco)";
        TrafficEnvironment.getInstance().setDisruptionNotice(statusHUD, current.speedMultiplier);

        // Difunde alerta FIPA INFORM a todos os semáforos e agentes coordenadores
        difundirAlertaFIPA(current.name, probPercent, current.speedMultiplier);
    }

    /**
     * Difunde mensagens FIPA INFORM com ontologia "disruption-alert" para o sistema multiagente.
     * (Legado — preserva compatibilidade com Fases 1–3)
     */
    private void difundirAlertaFIPA(String descricao, int probabilidade, double fatorVelocidade) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("disruption-alert");
        msg.setConversationId("disruption-notice-" + System.currentTimeMillis());
        msg.setContent("ALERTA_DISRUPCAO: [Modelo ML] " + descricao 
                + " | Probabilidade: " + probabilidade 
                + "% | Multiplicador de Deslocamento: " + fatorVelocidade);

        // Adiciona os semáforos dos 4 cruzamentos como destinatários
        String[] crossings = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
        for (String c : crossings) {
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }

        // Adiciona o Agente Coordenador Central
        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);
    }

    // ======================== REGISTO NO DF ========================

    /**
     * Regista o serviço nas páginas amarelas do JADE.
     */
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

    // ======================== UTILITÁRIOS ========================

    private static String getCrossingName(int index) {
        String[] names = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
        return (index >= 0 && index < names.length) ? names[index] : "UNKNOWN";
    }

    private static String formatFeatures(double[] features) {
        return String.format(Locale.US, "[Vol=%d, Queue=%d, Spd=%.2f, Wait=%.1f, Cap=%d, Weath=%d, Inc=%d]",
                (int) features[0], (int) features[1], features[2], features[3],
                (int) features[4], (int) features[5], (int) features[6]);
    }

    // ======================== MÉTODOS DE CONSULTA (EXPOSTOS PARA TESTES) ========================

    /** Verifica se o modelo ML foi carregado com sucesso. */
    public boolean isMLModelLoaded() {
        return mlModelLoaded;
    }

    /** Devolve o bundle do modelo ML carregado (pode ser null). */
    public TrafficModelBundle getModelBundle() {
        return modelBundle;
    }

    /** Devolve o total de inferências executadas. */
    public long getTotalInferenceCount() {
        return totalInferenceCount;
    }

    /** Devolve o total de alertas DISTURBANCE_ALERT emitidos. */
    public long getTotalAlertsSent() {
        return totalAlertsSent;
    }

    /** Devolve a última probabilidade de perturbação calculada. */
    public double getLastDisruptionProbability() {
        return lastDisruptionProbability;
    }

    /** Devolve a última classe predita (0 ou 1). */
    public int getLastPredictedClass() {
        return lastPredictedClass;
    }

    /** Devolve o índice do último cruzamento alertado (-1 se nenhum). */
    public int getLastIntersectionAlerted() {
        return lastIntersectionAlerted;
    }

    /** Timestamp do último alerta emitido. */
    public long getLastAlertTimestamp() {
        return lastAlertTimestamp;
    }

    /** Permite forçar o timestamp do último alerta (para testes de cooldown). */
    public void setLastAlertTimestamp(long timestamp) {
        this.lastAlertTimestamp = timestamp;
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[DisruptionManagementAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
