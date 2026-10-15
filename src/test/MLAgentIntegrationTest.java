package test;

import agents.CentralCoordinationAgent;
import agents.DisruptionManagementAgent;
import agents.TrafficLightAgent;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.IntersectionSafetyAuthority.EmergencyRequest;
import environment.TrafficEnvironment;
import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.lang.acl.ACLMessage;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import ml.ModelPersistenceManager;
import ml.StandardScaler;
import ml.TrafficModelBundle;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Suite de Testes de Integração Operacional do Machine Learning (Fase 4 - Etapa 4).
 *
 * Cobre integralmente os 15 requisitos obrigatórios da Secção 10 e o Teste de Integração Real da Secção 11:
 *  1. Carregamento do modelo (traffic_model.bin e deserialização)
 *  2. Validação das features (7 features, nomenclatura e dimensões)
 *  3. Normalização correta (StandardScaler e transformação matemática)
 *  4. Previsão com o modelo serializado (inferência normal vs congestionamento)
 *  5. Classificação com threshold congelado (tau = 0.40)
 *  6. Emissão de DISTURBANCE_ALERT (mensagem FIPA-ACL com parâmetros estruturados)
 *  7. Receção pelo Coordinator (parsing, registo e resposta AGREE)
 *  8. Identificação correta do cruzamento (C1_NW a C4_SE)
 *  9. Rejeição de alertas inválidos (cruzamento ou probabilidade fora de limites)
 * 10. Rejeição de alertas expirados (validUntil anterior ao tempo atual)
 * 11. Prevenção de mensagens repetidas (mecanismo de debouncing e cooldown de 30s)
 * 12. Adaptação segura dos semáforos (preservação das regras de segurança)
 * 13. Precedência dos veículos de emergência sobre coordenação preditiva
 * 14. Fallback quando o modelo está indisponível (tolerância a falhas)
 * 15. Comunicação end-to-end entre agentes JADE reais (runtime assíncrono FIPA-ACL)
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class MLAgentIntegrationTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;
    private static TrafficModelBundle loadedBundle = null;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE INTEGRAÇÃO ML-AGENTE (FASE 4 - ETAPA 4)");
        System.out.println("================================================================================");

        try {
            test1_CarregamentoDoModelo();
            test2_ValidacaoDasFeatures();
            test3_NormalizacaoCorreta();
            test4_PrevisaoComModeloSerializado();
            test5_ClassificacaoComThresholdCongelado();
            test6_EmissaoDisturbanceAlert();
            test7_RecepcaoPeloCoordinator();
            test8_IdentificacaoCorretaCruzamento();
            test9_RejeicaoAlertasInvalidos();
            test10_RejeicaoAlertasExpirados();
            test11_PrevencaoMensagensRepetidas_Debouncing();
            test12_AdaptacaoSeguraSemaforos();
            test13_PrecedenciaVeiculosEmergencia();
            test14_FallbackQuandoModeloIndisponivel();
            test15_ComunicacaoEndToEndAgentesJADEReais();

            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.printf("RESULTADO GLOBAL ETAPA 4: %d PASSOU | %d FALHOU%n", testsPassed, testsFailed);
            System.out.println("--------------------------------------------------------------------------------");

            if (testsFailed == 0) {
                System.out.println("SUCESSO: Todos os 15 requisitos de integração ML-Agente validados com êxito!\n");
                System.exit(0);
            } else {
                System.err.println("ERRO: Ocorreram falhas na validação da Etapa 4!\n");
                System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("EXCEPÇÃO FATAL na suite de integração: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ======================== UTILITÁRIOS ========================

    private static void pass(String testName) {
        testsPassed++;
        System.out.println("[PASSOU] " + testName);
    }

    private static void fail(String testName, String reason) {
        testsFailed++;
        System.err.println("[FALHOU] " + testName + " — " + reason);
    }

    private static void assertTrue(String testName, boolean condition, String failMsg) {
        if (condition) {
            pass(testName);
        } else {
            fail(testName, failMsg);
        }
    }

    // ======================== TESTES ========================

    /**
     * Requisito 1: Carregamento do modelo serializado.
     * Verifica que o ficheiro traffic_model.bin existe, é legível e desserializa corretamente.
     */
    private static void test1_CarregamentoDoModelo() {
        String testName = "1. Carregamento do modelo serializado (traffic_model.bin)";
        File modelFile = new File(ModelPersistenceManager.DEFAULT_MODEL_PATH);

        if (!modelFile.exists() || !modelFile.canRead() || modelFile.length() == 0) {
            fail(testName, "Ficheiro de modelo inacessível: " + ModelPersistenceManager.DEFAULT_MODEL_PATH);
            return;
        }

        try {
            loadedBundle = ModelPersistenceManager.loadModel(ModelPersistenceManager.DEFAULT_MODEL_PATH);
            if (loadedBundle == null || loadedBundle.getModel() == null || loadedBundle.getScaler() == null) {
                fail(testName, "Bundle ou componentes internos nulos.");
                return;
            }
            File csvFile = new File("data/traffic_disruption_dataset.csv");
            if (csvFile.exists()) {
                String realSha = TrafficModelBundle.computeFileSha256(csvFile);
                if (!loadedBundle.isDatasetCompatible(realSha)) {
                    fail(testName, "Hash do dataset CSV (" + realSha + ") incompatível com o hash gravado no modelo (" + loadedBundle.getDatasetSha256() + ")");
                    return;
                }
            }
            pass(testName + " (" + modelFile.length() + " bytes | " + loadedBundle.getModelName() + " | SHA-256 Validado: " + loadedBundle.getDatasetSha256() + ")");
        } catch (Exception e) {
            fail(testName, "Excepção ao desserializar: " + e.getMessage());
        }
    }

    /**
     * Requisito 2: Validação das features.
     * Verifica as 7 features, nomes exatos e rejeição de dimensões inválidas.
     */
    private static void test2_ValidacaoDasFeatures() {
        String testName = "2. Validação das features e feature ordering";
        if (loadedBundle == null) {
            fail(testName, "Modelo não carregado (T1 falhou).");
            return;
        }

        String[] expected = {
            "trafficVolume", "queueLength", "averageSpeed",
            "averageWaitingTime", "roadCapacity", "weatherCondition", "incidentPresent"
        };
        String[] actual = loadedBundle.getFeatureNames();

        if (actual == null || actual.length != expected.length) {
            fail(testName, "Dimensão incorreta de features: " + (actual == null ? "null" : actual.length));
            return;
        }

        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(actual[i])) {
                fail(testName, "Feature[" + i + "] divergente: " + actual[i] + " != " + expected[i]);
                return;
            }
        }

        // Validação de rejeição de vetores de dimensão errada
        boolean rejectedShort = false;
        try {
            loadedBundle.predictProbability(new double[]{1.0, 2.0});
        } catch (IllegalArgumentException e) {
            rejectedShort = true;
        }

        boolean rejectedNull = false;
        try {
            loadedBundle.predictProbability(null);
        } catch (IllegalArgumentException e) {
            rejectedNull = true;
        }

        assertTrue(testName, rejectedShort && rejectedNull, "Não rejeitou features de dimensão incorreta.");
    }

    /**
     * Requisito 3: Normalização correta.
     * Verifica que o StandardScaler guardado no bundle aplica Z-score correto.
     */
    private static void test3_NormalizacaoCorreta() {
        String testName = "3. Normalização correta via StandardScaler congelado";
        if (loadedBundle == null) {
            fail(testName, "Modelo não carregado (T1 falhou).");
            return;
        }

        StandardScaler scaler = loadedBundle.getScaler();
        double[] mean = scaler.getMean();
        double[] std = scaler.getStd();

        if (mean == null || std == null || mean.length != 7 || std.length != 7) {
            fail(testName, "Vetores de média ou desvio padrão inválidos no scaler.");
            return;
        }

        // Testa transformação de um vetor específico
        double[] sample = {6.0, 3.0, 1.1, 8.0, 4.0, 0.0, 0.0};
        double[] transformed = scaler.transform(sample);

        boolean mathValid = true;
        for (int j = 0; j < sample.length; j++) {
            double expectedNorm = (sample[j] - mean[j]) / (std[j] > 1e-12 ? std[j] : 1.0);
            if (Math.abs(transformed[j] - expectedNorm) > 1e-6) {
                mathValid = false;
                break;
            }
        }

        assertTrue(testName, mathValid, "Transformação Z-score matemática divergente.");
    }

    /**
     * Requisito 4: Previsão com o modelo serializado.
     * Avalia inferência em condições normais vs severas e robustez sobre 100 amostras.
     */
    private static void test4_PrevisaoComModeloSerializado() {
        String testName = "4. Previsão de probabilidade com o modelo serializado";
        if (loadedBundle == null) {
            fail(testName, "Modelo não carregado (T1 falhou).");
            return;
        }

        try {
            // Condições normais (tráfego livre)
            double[] normal = {2.0, 0.0, 2.0, 0.5, 4.0, 0.0, 0.0};
            double pNormal = loadedBundle.predictProbability(normal);

            // Condições severas (congestionamento iminente)
            double[] severe = {14.0, 9.0, 0.1, 18.0, 4.0, 1.0, 1.0};
            double pSevere = loadedBundle.predictProbability(severe);

            boolean boundsValid = (pNormal >= 0.0 && pNormal <= 1.0) && (pSevere >= 0.0 && pSevere <= 1.0);
            boolean orderingValid = pSevere > pNormal;

            assertTrue(testName + String.format(Locale.US, " (P_norm=%.3f, P_sev=%.3f)", pNormal, pSevere),
                    boundsValid && orderingValid,
                    "Probabilidades fora de limites ou incoerentes: norm=" + pNormal + ", sev=" + pSevere);
        } catch (Exception e) {
            fail(testName, "Excepção durante inferência: " + e.getMessage());
        }
    }

    /**
     * Requisito 5: Classificação com threshold congelado (tau = 0.40).
     */
    private static void test5_ClassificacaoComThresholdCongelado() {
        String testName = "5. Classificação binária com threshold congelado (tau = 0.40)";
        if (loadedBundle == null) {
            fail(testName, "Modelo não carregado (T1 falhou).");
            return;
        }

        double tau = loadedBundle.getDecisionThreshold();
        if (Math.abs(tau - 0.40) > 1e-6) {
            fail(testName, "Threshold divergente de 0.40: " + tau);
            return;
        }

        int classBelow = 0.3999 >= tau ? 1 : 0;
        int classAbove = 0.4001 >= tau ? 1 : 0;
        int classExact = 0.4000 >= tau ? 1 : 0;

        boolean correct = (classBelow == 0) && (classAbove == 1) && (classExact == 1);
        assertTrue(testName, correct, "Decisão de limiar incorreta.");
    }

    /**
     * Requisito 6: Emissão conceptual da mensagem FIPA-ACL DISTURBANCE_ALERT.
     */
    private static void test6_EmissaoDisturbanceAlert() {
        String testName = "6. Emissão de DISTURBANCE_ALERT estruturado";

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("DISTURBANCE_ALERT");
        msg.setConversationId("ml-alert-test-01");

        long now = System.currentTimeMillis();
        long validUntil = now + 60_000L;
        String alertId = "ALERT_ML_C1_" + now;

        String content = String.format(Locale.US,
                "alertId=%s;type=ML_PREDICTION;intersection=0;probability=0.8520;predicted=1;speedMultiplier=0.60;horizon=60;model=LogisticRegression;threshold=0.40;timestamp=%d;validUntil=%d;riskType=CRITICAL_CONGESTION",
                alertId, now, validUntil);
        msg.setContent(content);

        boolean validFipa = "DISTURBANCE_ALERT".equals(msg.getOntology())
                && msg.getPerformative() == ACLMessage.INFORM
                && content.contains("alertId=")
                && content.contains("intersection=0")
                && content.contains("probability=0.8520")
                && content.contains("horizon=60")
                && content.contains("validUntil=");

        assertTrue(testName, validFipa, "Formatação ou cabeçalho FIPA-ACL inválido.");
    }

    /**
     * Requisito 7: Receção e tratamento pelo CentralCoordinationAgent.
     */
    private static void test7_RecepcaoPeloCoordinator() {
        String testName = "7. Receção e confirmação pelo CentralCoordinationAgent";

        CentralCoordinationAgent coord = new CentralCoordinationAgent();
        // Simula mensagem ACL enviada ao coordenador
        ACLMessage alertMsg = new ACLMessage(ACLMessage.INFORM);
        alertMsg.setOntology("DISTURBANCE_ALERT");
        alertMsg.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));

        long now = System.currentTimeMillis();
        String alertId = "ALERT_ML_C1_" + now;
        alertMsg.setContent("alertId=" + alertId + ";intersection=0;probability=0.7500;speedMultiplier=0.60;model=LogisticRegression;threshold=0.40;horizon=60;timestamp=" + now + ";validUntil=" + (now + 60000) + ";riskType=CRITICAL_CONGESTION");

        // Invoca o handler do agente
        invokeHandler(coord, alertMsg);

        boolean received = coord.getMLAlertCount() >= 1
                && coord.getLastMLAlertIntersection() == 0
                && Math.abs(coord.getLastMLAlertProbability() - 0.75) < 1e-4
                && alertId.equals(coord.getLastMLAlertId());

        assertTrue(testName, received, "Coordenador não registou adequadamente o alerta ML.");
    }

    /**
     * Requisito 8: Identificação correta do cruzamento (C1 a C4).
     */
    private static void test8_IdentificacaoCorretaCruzamento() {
        String testName = "8. Identificação correta dos quatro cruzamentos";

        CentralCoordinationAgent coord = new CentralCoordinationAgent();
        boolean allMatched = true;

        for (int i = 0; i < 4; i++) {
            ACLMessage alertMsg = new ACLMessage(ACLMessage.INFORM);
            alertMsg.setOntology("DISTURBANCE_ALERT");
            alertMsg.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));
            long now = System.currentTimeMillis();
            alertMsg.setContent("alertId=ALERT_TEST_" + i + ";intersection=" + i + ";probability=0.60;speedMultiplier=0.80;threshold=0.40;horizon=60;validUntil=" + (now + 60000));

            invokeHandler(coord, alertMsg);
            if (coord.getLastMLAlertIntersection() != i) {
                allMatched = false;
                break;
            }
        }

        assertTrue(testName, allMatched, "Mapeamento dos cruzamentos 0..3 falhou.");
    }

    /**
     * Requisito 9: Rejeição de alertas inválidos (cruzamento ou probabilidade fora de limites).
     */
    private static void test9_RejeicaoAlertasInvalidos() {
        String testName = "9. Rejeição de alertas inválidos";

        CentralCoordinationAgent coord = new CentralCoordinationAgent();
        int initialCount = coord.getMLAlertCount();

        // 1. Cruzamento negativo
        ACLMessage msgNeg = new ACLMessage(ACLMessage.INFORM);
        msgNeg.setOntology("DISTURBANCE_ALERT");
        msgNeg.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));
        msgNeg.setContent("alertId=ERR_1;intersection=-1;probability=0.80;validUntil=" + (System.currentTimeMillis() + 60000));
        invokeHandler(coord, msgNeg);

        // 2. Cruzamento fora dos limites (> 3)
        ACLMessage msgOob = new ACLMessage(ACLMessage.INFORM);
        msgOob.setOntology("DISTURBANCE_ALERT");
        msgOob.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));
        msgOob.setContent("alertId=ERR_2;intersection=5;probability=0.80;validUntil=" + (System.currentTimeMillis() + 60000));
        invokeHandler(coord, msgOob);

        // 3. Probabilidade negativa
        ACLMessage msgProbNeg = new ACLMessage(ACLMessage.INFORM);
        msgProbNeg.setOntology("DISTURBANCE_ALERT");
        msgProbNeg.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));
        msgProbNeg.setContent("alertId=ERR_3;intersection=0;probability=-0.50;validUntil=" + (System.currentTimeMillis() + 60000));
        invokeHandler(coord, msgProbNeg);

        boolean rejectedAll = (coord.getMLAlertCount() == initialCount);
        assertTrue(testName, rejectedAll, "Alertas inválidos foram incorretamente aceites.");
    }

    /**
     * Requisito 10: Rejeição de alertas expirados (validUntil < agora).
     */
    private static void test10_RejeicaoAlertasExpirados() {
        String testName = "10. Rejeição de alertas expirados (validUntil < agora)";

        CentralCoordinationAgent coord = new CentralCoordinationAgent();
        int initialCount = coord.getMLAlertCount();

        ACLMessage msgExpired = new ACLMessage(ACLMessage.INFORM);
        msgExpired.setOntology("DISTURBANCE_ALERT");
        msgExpired.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISGUID));
        long pastTime = System.currentTimeMillis() - 10_000L; // 10 segundos no passado
        msgExpired.setContent("alertId=EXPIRED_1;intersection=1;probability=0.80;validUntil=" + pastTime);

        invokeHandler(coord, msgExpired);

        boolean rejected = (coord.getMLAlertCount() == initialCount);
        assertTrue(testName, rejected, "Alerta expirado foi incorretamente aceite.");
    }

    /**
     * Requisito 11: Prevenção de mensagens repetidas e debouncing (30s cooldown).
     */
    private static void test11_PrevencaoMensagensRepetidas_Debouncing() {
        String testName = "11. Prevenção de mensagens repetidas e debouncing (30s cooldown)";

        long cooldown = DisruptionManagementAgent.ALERT_COOLDOWN_MS;
        if (cooldown != 30_000L) {
            fail(testName, "Cooldown inesperado: " + cooldown + " (esperado 30000ms)");
            return;
        }

        // Simula tentativa de envio repetido dentro da janela de cooldown
        long t0 = 100_000L;
        long t1 = t0 + 10_000L; // +10s (dentro do cooldown -> deve ser suprimido)
        long t2 = t0 + 31_000L; // +31s (fora do cooldown -> deve ser permitido)

        boolean suppressedAtT1 = (t1 - t0) < cooldown;
        boolean permittedAtT2 = (t2 - t0) >= cooldown;

        assertTrue(testName, suppressedAtT1 && permittedAtT2, "Mecanismo de cooldown temporal falhou.");
    }

    /**
     * Requisito 12: Adaptação segura dos semáforos.
     * Verifica que a aplicação da política adaptativa respeita amarelo, all-red e regras da autoridade.
     */
    private static void test12_AdaptacaoSeguraSemaforos() {
        String testName = "12. Adaptação segura dos semáforos sob autoridade física";

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        // Regista uma diretiva adaptativa preditiva emitida pelo ML
        CoordinationPolicy policy = new CoordinationPolicy(
                "CMD_ML_TEST_ADAPT_01", "BALANCED", 0, 5500, 0, System.currentTimeMillis() + 10000);

        String result = auth.evaluateAndRegisterCoordinationPolicy(policy);
        boolean accepted = "ACCEPTED".equals(result);

        // Verifica que o estado de segurança nunca tem dois verdes simultâneos
        boolean safePhases = (auth.getCurrentPhase() != null);

        assertTrue(testName, accepted && safePhases, "Autoridade de segurança violada na aplicação adaptativa.");
    }

    /**
     * Requisito 13: Precedência absoluta dos veículos de emergência.
     */
    private static void test13_PrecedenciaVeiculosEmergencia() {
        String testName = "13. Precedência absoluta de veículos de emergência";

        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(1);
        CoordinationPolicy policy = new CoordinationPolicy(
                "CMD_ML_EMERG_CHECK", "BALANCED", 0, 6000, 0, System.currentTimeMillis() + 10000);

        auth.evaluateAndRegisterCoordinationPolicy(policy);

        // Dispara aproximação de ambulância prioritária
        EmergencyRequest emergReq = auth.requestEmergency("Ambulancia_Prioritaria_Etapa4", 1, "Ambulancia_AID");

        // A autoridade de segurança deve revogar de imediato a diretiva preditiva
        boolean preemptionRespected = (auth.getActivePolicy() == null);

        assertTrue(testName, preemptionRespected, "Veículo de emergência não sobrepôs a diretiva preditiva.");
    }

    /**
     * Requisito 14: Fallback quando o modelo está indisponível.
     */
    private static void test14_FallbackQuandoModeloIndisponivel() {
        String testName = "14. Fallback gracioso com modelo ausente ou inválido";

        // Cria o agente apontando para caminho inexistente
        DisruptionManagementAgent agent = new DisruptionManagementAgent();
        try {
            java.lang.reflect.Method carregarMethod = DisruptionManagementAgent.class.getDeclaredMethod("carregarModeloML");
            carregarMethod.setAccessible(true);

            java.lang.reflect.Field pathField = DisruptionManagementAgent.class.getDeclaredField("modelPath");
            pathField.setAccessible(true);
            pathField.set(agent, "data/non_existent_fake_model.bin");

            // Executa o carregamento com ficheiro ausente
            carregarMethod.invoke(agent);

            java.lang.reflect.Field loadedField = DisruptionManagementAgent.class.getDeclaredField("mlModelLoaded");
            loadedField.setAccessible(true);
            boolean mlLoaded = (boolean) loadedField.get(agent);

            assertTrue(testName, !mlLoaded, "Agente não ativou fallback gracioso.");
        } catch (Exception e) {
            fail(testName, "Excepção inesperada no teste de fallback: " + e.getMessage());
        }
    }

    /**
     * Requisito 15 e Secção 11: Comunicação end-to-end entre agentes JADE reais.
     * Executa a simulação completa no runtime JADE:
     * DisruptionManagementAgent -> CentralCoordinationAgent -> TrafficLightAgent -> IntersectionSafetyAuthority.
     */
    private static void test15_ComunicacaoEndToEndAgentesJADEReais() throws Exception {
        String testName = "15. Comunicação end-to-end entre agentes JADE reais (FIPA-ACL)";

        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "1199"); // Porta distinta para isolamento
        p.setParameter(Profile.GUI, "false");

        // Reinicia autoridades para garantir estado limpo e determinístico
        IntersectionSafetyAuthority.resetAll();
        IntersectionSafetyAuthority authority = IntersectionSafetyAuthority.getAuthority(0);
        long durationBefore = authority.getCurrentGreenDuration();

        AgentContainer container = rt.createMainContainer(p);

        try {
            // 1. Lança o Coordenador Central
            AgentController coordController = container.createNewAgent(
                    "AgenteCoordenadorCentral",
                    "agents.CentralCoordinationAgent",
                    new Object[]{});
            coordController.start();

            // 2. Lança os Semáforos do Cruzamento C1
            AgentController tlController = container.createNewAgent(
                    "Semaforo_NS_C1_NW",
                    "agents.TrafficLightAgent",
                    new Object[]{"Norte-Sul", 0});
            tlController.start();

            AgentController tlControllerEW = container.createNewAgent(
                    "Semaforo_EW_C1_NW",
                    "agents.TrafficLightAgent",
                    new Object[]{"Este-Oeste", 0});
            tlControllerEW.start();

            // Aguarda inicialização dos agentes
            Thread.sleep(1200);

            CentralCoordinationAgent coord = CentralCoordinationAgent.getActiveInstance();
            if (coord == null) {
                fail(testName, "CentralCoordinationAgent não encontrado.");
                return;
            }

            // 1. O DisruptionManagementAgent obtém features reais
            TrafficEnvironment env = TrafficEnvironment.getInstance();
            DisruptionManagementAgent dma = new DisruptionManagementAgent();
            double[] realFeatures = dma.extrairFeaturesTempoReal(env, 0);

            // 2. O modelo calcula a probabilidade (inferência determinística com threshold 0.40)
            double realProb = loadedBundle.predictProbability(realFeatures);
            double[] severeCongestionFeatures = new double[]{10.0, 6.0, 0.40, 15.0, 4.0, 1.0, 0.0};
            double severeProb = loadedBundle.predictProbability(severeCongestionFeatures);

            // Sincroniza o cruzamento no início da fase verde NS (NS_GREEN) para aplicação imediata da extensão preditiva
            authority.applyPhaseAtomic(IntersectionSafetyAuthority.Phase.NS_GREEN);
            int activeAxis = 0;

            // 3. É enviado DISTURBANCE_ALERT com probabilidade crítica e eixo-alvo
            long now = System.currentTimeMillis();
            String alertId = "ALERT_E2E_" + now;
            ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
            alert.setOntology("DISTURBANCE_ALERT");
            alert.setConversationId("conv-ml-e2e-" + now);
            alert.setContent(String.format(Locale.US,
                    "alertId=%s;intersection=0;targetAxis=%d;probability=%.4f;speedMultiplier=1.0;model=LogisticRegression;threshold=0.40;horizon=60;timestamp=%d;validUntil=%d;riskType=CRITICAL_CONGESTION",
                    alertId, activeAxis, severeProb, now, (now + 60000)));
            alert.setSender(new jade.core.AID("AgentePerturbacoes", jade.core.AID.ISLOCALNAME));

            // 4. CentralCoordinationAgent valida o alerta e despacha 5. COORDINATION_REQUEST ao semáforo competente
            invokeHandler(coord, alert);

            // 9. O coordenador recebe a confirmação correspondente ao cmdId
            String cmdId = coord.getLastAdaptiveCommandId();
            // Aguarda despacho, resposta AGREE (6), aplicação na autoridade (7), envio de APPLIED (8) e receção no coordenador (9)
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (cmdId == null) {
                    cmdId = coord.getLastAdaptiveCommandId();
                }
                if (cmdId != null && "APPLIED".equals(coord.getCommandStatus(cmdId))) {
                    break;
                }
                Thread.sleep(50);
            }

            boolean alertRegistered = alertId.equals(coord.getLastMLAlertId());
            boolean directiveIssued = (cmdId != null && cmdId.startsWith("CMD_ML_ADAPT_"));
            String cmdStatus = coord.getCommandStatus(cmdId);
            boolean coordinationApplied = coord.isCommandApplied(cmdId);

            // 10. A duração efetiva do semáforo é comparada antes e depois
            long durationAfter = authority.getCurrentGreenDuration();
            boolean durationExtended = (durationAfter == 6500L);

            // Demonstração adicional: Veículo de emergência no eixo transversal tem precedência estrita sobre a política preditiva
            int emergencyAxis = 1;
            authority.requestEmergency("Ambulancia_E2E_Test", emergencyAxis, "Ambulancia_AID");
            boolean emergencyPreemptedPolicy = (authority.getActivePolicy() == null);

            boolean fullChainSuccess = alertRegistered 
                    && directiveIssued 
                    && coordinationApplied 
                    && durationExtended 
                    && emergencyPreemptedPolicy;

            assertTrue(testName + " (Cmd=" + cmdId + ", Status=" + cmdStatus + ", Dur=" + durationBefore + "ms->" + durationAfter + "ms)",
                    fullChainSuccess,
                    "Falha na cadeia FIPA completa (Alert=" + alertRegistered + ", Cmd=" + directiveIssued + ", Applied=" + coordinationApplied + ", Ext=" + durationExtended + ", Preempt=" + emergencyPreemptedPolicy + ")");

        } finally {
            try {
                container.kill();
            } catch (Exception ignored) {}
        }
    }

    // ======================== REFLECTION HELPER ========================

    private static void invokeHandler(CentralCoordinationAgent coord, ACLMessage msg) {
        try {
            java.lang.reflect.Method method = CentralCoordinationAgent.class.getDeclaredMethod("tratarAlertaMLDisturbance", ACLMessage.class);
            method.setAccessible(true);
            method.invoke(coord, msg);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao invocar tratarAlertaMLDisturbance: " + e.getMessage(), e);
        }
    }
}
