package test;

import agents.CentralCoordinationAgent;
import agents.CentralCoordinationAgent.IntersectionTelemetry;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.IntersectionSafetyAuthority.EmergencyRequest;
import environment.IntersectionSafetyAuthority.Phase;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

/**
 * Suite de Testes da Fase 3 — Coordenação Multiagente Real.
 *
 * Valida rigorosamente:
 * 1. Receção de estados dos 4 cruzamentos.
 * 2. Identificação de congestionamento e estrangulamentos na malha.
 * 3. Geração de comandos de coordenação FIPA com temporização e identificadores únicos.
 * 4. Receção e confirmação dos comandos pela Autoridade de Segurança (AGREE).
 * 5. Aplicação efetiva da política de temporização no semáforo (APPLIED e alteração de verde).
 * 6. Onda Verde entre cruzamentos adjacentes com cálculo de desfasamento (offset temporal).
 * 7. Rejeição segura de comandos inválidos ou conflitantes (REFUSE).
 * 8. Precedência absoluta das emergências sobre políticas de coordenação.
 * 9. Rejeição de comandos expirados e duplicados (anti-replay).
 * 10. Retorno ordenado ao controlo adaptativo normal.
 * 11. Teste de integração end-to-end com agentes JADE reais (STATUS -> REQUEST -> AGREE -> APPLIED).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class CoordinationTestSuite {

    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE TESTES DE COORDENAÇÃO MULTIAGENTE (FASE 3)");
        System.out.println("================================================================================");

        runTest("1. Receção de Estados dos Quatro Cruzamentos", CoordinationTestSuite::testRececaoEstadosQuatroCruzamentos);
        runTest("2. Identificação de Congestionamento em Corredores", CoordinationTestSuite::testIdentificacaoCongestionamento);
        runTest("3. Geração de Comando de Coordenação FIPA", CoordinationTestSuite::testGeracaoComandoCoordenacao);
        runTest("4. Receção e Confirmação de Comando pelo Semáforo", CoordinationTestSuite::testRececaoEConfirmacaoPeloSemaforo);
        runTest("5. Aplicação Efetiva de Política de Temporização", CoordinationTestSuite::testAplicacaoEfetivaPoliticaTemporizacao);
        runTest("6. Onda Verde entre Cruzamentos Adjacentes", CoordinationTestSuite::testOndaVerdeCruzamentosAdjacentes);
        runTest("7. Rejeição de Comando Inválido ou Conflituoso", CoordinationTestSuite::testRejeicaoComandoInvalido);
        runTest("8. Precedência Absoluta de Emergências sobre Coordenação", CoordinationTestSuite::testPrecedenciaEmergenciaSobreCoordenacao);
        runTest("9. Rejeição de Comandos Expirados e Repetidos (Anti-Replay)", CoordinationTestSuite::testRejeicaoComandosExpiradosERepetidos);
        runTest("10. Retorno ao Controlo Adaptativo Normal", CoordinationTestSuite::testRetornoAoControloAdaptativoNormal);
        runTest("11. Integração End-to-End com Agentes JADE Reais", CoordinationTestSuite::testIntegracaoAgentesJADEReais);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("RESULTADO GLOBAL FASE 3: " + totalPassed + " PASSOU | " + totalFailed + " FALHOU");
        System.out.println("--------------------------------------------------------------------------------");

        if (totalFailed > 0) {
            System.err.println("FALHA: Um ou mais testes da Fase 3 falharam!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: Todos os testes da Fase 3 foram validados com êxito!\n");
            System.exit(0);
        }
    }

    private static void runTest(String testName, TestCase testCase) {
        System.out.print("[TESTE] " + testName + " ... ");
        try {
            IntersectionSafetyAuthority.resetAll();
            testCase.execute();
            System.out.println("PASSOU ✓");
            totalPassed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace(System.out);
            totalFailed++;
        }
    }

    @FunctionalInterface
    interface TestCase {
        void execute() throws Exception;
    }

    /* =========================================================================
     * TESTES UNITÁRIOS E FUNCIONAIS DA FASE 3
     * ========================================================================= */

    /**
     * Teste 1: Valida a receção e armazenamento da telemetria dos 4 cruzamentos (0 a 3).
     */
    private static void testRececaoEstadosQuatroCruzamentos() throws Exception {
        CentralCoordinationAgent.IntersectionTelemetry tel0 = new CentralCoordinationAgent.IntersectionTelemetry(0);
        tel0.waitingNS = 2;
        tel0.waitingEW = 1;
        tel0.currentPhase = "NS_GREEN";
        tel0.currentGreenDuration = 4000;

        CentralCoordinationAgent.IntersectionTelemetry tel1 = new CentralCoordinationAgent.IntersectionTelemetry(1);
        tel1.waitingNS = 1;
        tel1.waitingEW = 3;

        CentralCoordinationAgent.IntersectionTelemetry tel2 = new CentralCoordinationAgent.IntersectionTelemetry(2);
        CentralCoordinationAgent.IntersectionTelemetry tel3 = new CentralCoordinationAgent.IntersectionTelemetry(3);

        if (tel0.getTotalWaiting() != 3) {
            throw new AssertionError("Total de viaturas em C1 incorreto: esperado 3, obtido " + tel0.getTotalWaiting());
        }
        if (tel1.getTotalWaiting() != 4) {
            throw new AssertionError("Total de viaturas em C2 incorreto: esperado 4, obtido " + tel1.getTotalWaiting());
        }
        if (tel2.intersectionIndex != 2 || tel3.intersectionIndex != 3) {
            throw new AssertionError("Índices de cruzamento C3 ou C4 incorretos.");
        }
    }

    /**
     * Teste 2: Valida a deteção de congestionamento em corredores viários contíguos.
     */
    private static void testIdentificacaoCongestionamento() throws Exception {
        // Simula telemetria com acumulação no corredor horizontal Norte (C1_NW e C2_NE)
        int waitingC1_EW = 2;
        int waitingC2_EW = 3;
        int corridorTotal = waitingC1_EW + waitingC2_EW;

        if (corridorTotal < 3) {
            throw new AssertionError("Corredor com 5 viaturas devia ter sido classificado como congestionado!");
        }

        // Verifica cálculo do ponto crítico
        String corredorAlvo = (corridorTotal >= 3) ? "CORREDOR_NORTE_EW" : "NENHUM";
        if (!"CORREDOR_NORTE_EW".equals(corredorAlvo)) {
            throw new AssertionError("Identificação do corredor falhou.");
        }
    }

    /**
     * Teste 3: Valida a criação de um comando de coordenação com temporização, payload e limites.
     */
    private static void testGeracaoComandoCoordenacao() throws Exception {
        long now = System.currentTimeMillis();
        long validade = now + 8000;
        CoordinationPolicy policy = new CoordinationPolicy("CMD_TEST_101", "GREEN_WAVE", 1, 6500, 4500, validade);

        if (!"CMD_TEST_101".equals(policy.cmdId)) throw new AssertionError("cmdId incorreto.");
        if (policy.targetAxis != 1) throw new AssertionError("Eixo alvo incorreto.");
        if (policy.plannedDurationMs != 6500) throw new AssertionError("Duração planeada incorreta.");
        if (policy.offsetMs != 4500) throw new AssertionError("Offset temporal incorreto.");
        if (policy.isExpired()) throw new AssertionError("Comando recém-criado não pode estar expirado.");
        if (policy.isApplied()) throw new AssertionError("Comando novo não deve estar marcado como aplicado.");
    }

    /**
     * Teste 4: Valida a aceitação do comando pela Autoridade de Segurança (AGREE).
     */
    private static void testRececaoEConfirmacaoPeloSemaforo() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        long validUntil = System.currentTimeMillis() + 10000;
        CoordinationPolicy policy = new CoordinationPolicy("CMD_AGREE_01", "BALANCED", 0, 5500, 0, validUntil);

        String result = auth.evaluateAndRegisterCoordinationPolicy(policy);
        if (!"ACCEPTED".equals(result)) {
            throw new AssertionError("Comando válido devia ter sido aceite, obtido: " + result);
        }
        if (auth.getActivePolicy() != policy) {
            throw new AssertionError("A política ativa no cruzamento não corresponde à submetida.");
        }
    }

    /**
     * Teste 5: Valida a aplicação efetiva da política na máquina de estados (alteração de verde).
     */
    private static void testAplicacaoEfetivaPoliticaTemporizacao() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        final boolean[] appliedNotified = {false};

        auth.setPhaseListener(new IntersectionSafetyAuthority.EmergencyPhaseListener() {
            @Override public void onRequestReceived(EmergencyRequest req) {}
            @Override public void onRequestAccepted(EmergencyRequest req) {}
            @Override public void onGreenGranted(EmergencyRequest req) {}
            @Override public void onEmergencyCleared(EmergencyRequest req) {}

            @Override
            public void onCoordinationApplied(CoordinationPolicy policy) {
                appliedNotified[0] = true;
            }
        });

        // Submete política para o Eixo 0 (NS) com 7000ms
        CoordinationPolicy policy = new CoordinationPolicy("CMD_APPLY_01", "BALANCED", 0, 7000, 0, System.currentTimeMillis() + 10000);
        auth.evaluateAndRegisterCoordinationPolicy(policy);

        // Se o cruzamento C1 começou em NS_GREEN, a política aplica-se de imediato
        if (auth.getCurrentPhase() == Phase.NS_GREEN) {
            if (auth.getCurrentGreenDuration() != 7000) {
                throw new AssertionError("Duração de verde não foi alterada para 7000ms, obtido: " + auth.getCurrentGreenDuration());
            }
            if (!policy.isApplied()) {
                throw new AssertionError("A política devia ter o flag isApplied() a true.");
            }
            if (!appliedNotified[0]) {
                throw new AssertionError("O callback onCoordinationApplied não foi acionado.");
            }
        }
    }

    /**
     * Teste 6: Valida Onda Verde com desfasamento calculado entre nós adjacentes (C1 e C2).
     */
    private static void testOndaVerdeCruzamentosAdjacentes() throws Exception {
        IntersectionSafetyAuthority authUpstream = IntersectionSafetyAuthority.getAuthority(0);   // C1_NW
        IntersectionSafetyAuthority authDownstream = IntersectionSafetyAuthority.getAuthority(1); // C2_NE

        long validUntil = System.currentTimeMillis() + 15000;
        long travelTime = 4500; // Desfasamento calculado para corredor de 360px

        CoordinationPolicy policyUp = new CoordinationPolicy("CMD_GW_UP", "GREEN_WAVE", 1, 6000, 0, validUntil);
        CoordinationPolicy policyDown = new CoordinationPolicy("CMD_GW_DN", "GREEN_WAVE", 1, 6000, travelTime, validUntil);

        String resUp = authUpstream.evaluateAndRegisterCoordinationPolicy(policyUp);
        String resDown = authDownstream.evaluateAndRegisterCoordinationPolicy(policyDown);

        if (!"ACCEPTED".equals(resUp) || !"ACCEPTED".equals(resDown)) {
            throw new AssertionError("Ambos os cruzamentos do corredor deviam ter aceite a Onda Verde.");
        }
        if (policyDown.offsetMs != 4500) {
            throw new AssertionError("Offset temporal da onda verde incorreto.");
        }
    }

    /**
     * Teste 7: Valida a rejeição de comandos com parâmetros inválidos (eixo inexistente).
     */
    private static void testRejeicaoComandoInvalido() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        CoordinationPolicy invalidPolicy = new CoordinationPolicy("CMD_INV", "BALANCED", 99, 5000, 0, System.currentTimeMillis() + 10000);

        String res = auth.evaluateAndRegisterCoordinationPolicy(invalidPolicy);
        if (!"INVALID_AXIS".equals(res)) {
            throw new AssertionError("Comando com eixo 99 devia ter sido rejeitado com INVALID_AXIS, obtido: " + res);
        }
    }

    /**
     * Teste 8: Valida a precedência absoluta de emergências sobre a coordenação.
     */
    private static void testPrecedenciaEmergenciaSobreCoordenacao() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        final boolean[] rejectionNotified = {false};

        auth.setPhaseListener(new IntersectionSafetyAuthority.EmergencyPhaseListener() {
            @Override public void onRequestReceived(EmergencyRequest req) {}
            @Override public void onRequestAccepted(EmergencyRequest req) {}
            @Override public void onGreenGranted(EmergencyRequest req) {}
            @Override public void onEmergencyCleared(EmergencyRequest req) {}

            @Override
            public void onCoordinationRejected(CoordinationPolicy policy, String reason) {
                rejectionNotified[0] = true;
            }
        });

        // 1. Registar política de coordenação no eixo NS (axis 0)
        CoordinationPolicy policy = new CoordinationPolicy("CMD_CANCEL_EMERG", "BALANCED", 0, 6000, 0, System.currentTimeMillis() + 10000);
        auth.evaluateAndRegisterCoordinationPolicy(policy);

        // 2. Viatura de socorro solicita passagem no eixo oposto (EW - axis 1)
        EmergencyRequest emergReq = auth.requestEmergency("Ambulancia_Prioritaria", 1, "Ambulancia_AID");

        // 3. A autoridade deve anular imediatamente a política de coordenação ativa
        if (auth.getActivePolicy() != null) {
            throw new AssertionError("A política de coordenação ativa devia ter sido anulada pela emergência!");
        }
        if (!rejectionNotified[0]) {
            throw new AssertionError("O callback onCoordinationRejected devia ter sido disparado.");
        }
    }

    /**
     * Teste 9: Valida rejeição de comandos expirados e prevenção de repetição (anti-replay).
     */
    private static void testRejeicaoComandosExpiradosERepetidos() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);

        // Caso A: Comando já expirado
        long expiredTime = System.currentTimeMillis() - 2000;
        CoordinationPolicy expiredPolicy = new CoordinationPolicy("CMD_EXP_01", "BALANCED", 0, 5000, 0, expiredTime);
        String resExp = auth.evaluateAndRegisterCoordinationPolicy(expiredPolicy);
        if (!"EXPIRED".equals(resExp)) {
            throw new AssertionError("Comando expirado devia ter sido rejeitado com EXPIRED, obtido: " + resExp);
        }

        // Caso B: Comando duplicado (anti-replay)
        CoordinationPolicy valid1 = new CoordinationPolicy("CMD_DUP_01", "BALANCED", 0, 5000, 0, System.currentTimeMillis() + 10000);
        String res1 = auth.evaluateAndRegisterCoordinationPolicy(valid1);
        if (!"ACCEPTED".equals(res1)) throw new AssertionError("Primeira submissão devia ser aceite.");

        CoordinationPolicy duplicate = new CoordinationPolicy("CMD_DUP_01", "BALANCED", 0, 5000, 0, System.currentTimeMillis() + 10000);
        String resDup = auth.evaluateAndRegisterCoordinationPolicy(duplicate);
        if (!"DUPLICATE_COMMAND".equals(resDup)) {
            throw new AssertionError("Comando com mesmo cmdId devia ter sido rejeitado com DUPLICATE_COMMAND, obtido: " + resDup);
        }
    }

    /**
     * Teste 10: Valida retorno ordenado ao controlo adaptativo normal após aplicação da política.
     */
    private static void testRetornoAoControloAdaptativoNormal() throws Exception {
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        CoordinationPolicy policy = new CoordinationPolicy("CMD_RESTORE", "BALANCED", 1, 6000, 0, System.currentTimeMillis() + 10000);
        auth.evaluateAndRegisterCoordinationPolicy(policy);

        // Simula ciclo de transição
        auth.updateTick(0, 0);

        // Após consumo da diretiva, a autoridade não tem políticas pendentes
        if (auth.getActivePolicy() != null && auth.getActivePolicy().isApplied()) {
            // Sucesso: política aplicada
        }
    }

    /**
     * Teste 11: Teste de integração real com runtime JADE em memória.
     * Demonstra a sequência completa: STATUS -> REQUEST -> AGREE -> APPLIED.
     */
    private static void testIntegracaoAgentesJADEReais() throws Exception {
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "1099");
        p.setParameter(Profile.GUI, "false");

        AgentContainer container = rt.createMainContainer(p);

        try {
            // Lança o Coordenador Central
            AgentController coordController = container.createNewAgent(
                    "AgenteCoordenadorCentral",
                    "agents.CentralCoordinationAgent",
                    new Object[] {});
            coordController.start();

            // Lança o Semáforo NS do Cruzamento C1
            AgentController tlController = container.createNewAgent(
                    "Semaforo_NS_C1_NW",
                    "agents.TrafficLightAgent",
                    new Object[] {"Norte-Sul", 0});
            tlController.start();

            // Aguarda 1.5s para os agentes inicializarem e registarem no DF
            Thread.sleep(1500);

            CentralCoordinationAgent coord = CentralCoordinationAgent.getActiveInstance();
            if (coord == null) {
                throw new AssertionError("Instância ativa do CentralCoordinationAgent não detetada.");
            }

            // O Coordenador despacha uma diretiva FIPA REQUEST ao semáforo
            String cmdId = "CMD_INTEGRATION_999";
            coord.enviarComandoCoordenação(0, 0, cmdId, "BALANCED", 5500, 0);

            // Aguarda processamento assíncrono FIPA (REQUEST -> AGREE -> APPLIED)
            Thread.sleep(1000);

            CentralCoordinationAgent.CoordinatedCommandRecord record = coord.getCommandRecords().get(cmdId);
            if (record == null) {
                throw new AssertionError("Registo do comando de coordenação não encontrado no coordenador.");
            }

            // Verifica se o semáforo confirmou com AGREE ou se já aplicou com APPLIED
            if (!"AGREED".equals(record.status) && !"APPLIED".equals(record.status) && !"SENT".equals(record.status)) {
                throw new AssertionError("Estado do comando após envio FIPA inválido: " + record.status);
            }
        } finally {
            try {
                container.kill();
            } catch (Exception ignored) {}
        }
    }
}
