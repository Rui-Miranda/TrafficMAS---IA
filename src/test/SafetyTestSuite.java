package test;

import environment.CitySimulationGUI.LightState;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.EmergencyRequest;
import environment.IntersectionSafetyAuthority.EmergencyStatus;
import environment.IntersectionSafetyAuthority.Phase;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Suite de Testes de Segurança Semafórica e Emergências (Fase 1).
 *
 * Valida de forma automatizada e sem depender de janelas gráficas Swing:
 * 1. Ciclo de tráfego normal e respeito pelos tempos de transição.
 * 2. Preempção de emergência no eixo NS (com Yellow e All-Red obrigatórios).
 * 3. Preempção de emergência no eixo EW (com Yellow e All-Red obrigatórios).
 * 4. Resolução de conflito entre emergências simultâneas em eixos opostos (NS e EW).
 * 5. Idempotência / Anti-debouncing contra pedidos repetidos da mesma viatura.
 * 6. Restauro seguro e ordenado do controlo adaptativo.
 * 7. Invariante inviolável de segurança (ausência de verdes/amarelos incompatíveis).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class SafetyTestSuite {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE TESTES DE SEGURANÇA E EMERGÊNCIAS (FASE 1)");
        System.out.println("================================================================================");

        runTest("1. Tráfego Normal e Sequência de Fases", SafetyTestSuite::testNormalTrafficCycle);
        runTest("2. Emergência no Eixo Norte-Sul (Transição Segura)", SafetyTestSuite::testEmergencyNS);
        runTest("3. Emergência no Eixo Este-Oeste (Transição Segura)", SafetyTestSuite::testEmergencyEW);
        runTest("4. Resolução de Emergências Simultâneas (Conflito NS vs EW)", SafetyTestSuite::testSimultaneousEmergenciesNSEW);
        runTest("5. Idempotência e Anti-Debouncing (Pedidos Repetidos)", SafetyTestSuite::testRepeatedRequestsDebouncing);
        runTest("6. Restauro Ordenado do Controlo Adaptativo", SafetyTestSuite::testReturnToNormalControl);
        runTest("7. Verificação Estrita do Invariante Anti-Colisão", SafetyTestSuite::testInvariantNoIncompatibleGreens);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("RESULTADO GLOBAL: " + testsPassed + " PASSOU | " + testsFailed + " FALHOU");
        System.out.println("--------------------------------------------------------------------------------");

        if (testsFailed > 0) {
            System.err.println("ALERTA: Foram detetadas falhas nos testes de segurança!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: Todos os testes da Fase 1 foram validados com êxito!");
        }
    }

    private static void runTest(String testName, TestCase test) {
        System.out.print("[TESTE] " + testName + " ... ");
        try {
            IntersectionSafetyAuthority.resetAll();
            test.run();
            System.out.println("PASSOU ✔");
            testsPassed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ❌");
            System.err.println("   Detalhe do erro: " + t.getMessage());
            t.printStackTrace(System.err);
            testsFailed++;
        }
    }

    @FunctionalInterface
    interface TestCase {
        void run() throws Exception;
    }

    /* =========================================================================
     * TESTE 1: CICLO DE TRÁFEGO NORMAL
     * ========================================================================= */
    private static void testNormalTrafficCycle() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(0);
        auth.setYellowDuration(200); // Tempos acelerados para teste unitário
        auth.setAllRedDuration(100);
        auth.setCurrentGreenDuration(300);

        assertEquals("Fase inicial do cruzamento 0 deve ser NS_GREEN", Phase.NS_GREEN, auth.getCurrentPhase());
        assertEquals("NS deve estar GREEN", LightState.GREEN, auth.getLightState(0));
        assertEquals("EW deve estar RED", LightState.RED, auth.getLightState(1));

        // Avança tempo para terminar NS_GREEN
        Thread.sleep(350);
        auth.updateTick(0, 0);
        assertEquals("Após expirar verde NS, deve transitar para NS_YELLOW", Phase.NS_YELLOW, auth.getCurrentPhase());
        assertEquals("NS deve estar YELLOW", LightState.YELLOW, auth.getLightState(0));
        assertEquals("EW deve permanecer RED", LightState.RED, auth.getLightState(1));

        // Avança tempo para terminar NS_YELLOW
        Thread.sleep(250);
        auth.updateTick(0, 0);
        assertEquals("Após amarelo, deve transitar obrigatoriamente para ALL_RED", Phase.ALL_RED, auth.getCurrentPhase());
        assertEquals("NS deve estar RED", LightState.RED, auth.getLightState(0));
        assertEquals("EW deve estar RED", LightState.RED, auth.getLightState(1));

        // Avança tempo para terminar ALL_RED
        Thread.sleep(150);
        auth.updateTick(0, 0);
        assertEquals("Após all-red, deve transitar para EW_GREEN", Phase.EW_GREEN, auth.getCurrentPhase());
        assertEquals("NS deve estar RED", LightState.RED, auth.getLightState(0));
        assertEquals("EW deve estar GREEN", LightState.GREEN, auth.getLightState(1));
    }

    /* =========================================================================
     * TESTE 2: EMERGÊNCIA NO EIXO NS
     * ========================================================================= */
    private static void testEmergencyNS() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(1); // Cruzamento 1 começa em EW_GREEN
        auth.setYellowDuration(200);
        auth.setAllRedDuration(100);

        assertEquals("Cruzamento 1 começa em EW_GREEN", Phase.EW_GREEN, auth.getCurrentPhase());

        AtomicBoolean greenGrantedCalled = new AtomicBoolean(false);
        auth.setPhaseListener(new IntersectionSafetyAuthority.EmergencyPhaseListener() {
            @Override public void onRequestReceived(EmergencyRequest req) {}
            @Override public void onRequestAccepted(EmergencyRequest req) {}
            @Override public void onGreenGranted(EmergencyRequest req) {
                greenGrantedCalled.set(true);
            }
            @Override public void onEmergencyCleared(EmergencyRequest req) {}
        });

        // Submete emergência para o eixo NS (eixo 0)
        EmergencyRequest req = auth.requestEmergency("Ambulancia_01", 0, "Ambulancia_01");
        assertEquals("Status inicial do pedido deve ser ACEITE", EmergencyStatus.ACEITE, req.getStatus());
        assertEquals("Não pode conceder verde imediatamente se EW está verde!", false, greenGrantedCalled.get());
        assertEquals("Deve transitar de imediato para EW_YELLOW", Phase.EW_YELLOW, auth.getCurrentPhase());

        // Avança amarelo
        Thread.sleep(250);
        auth.updateTick(0, 0);
        assertEquals("Deve estar em ALL_RED", Phase.ALL_RED, auth.getCurrentPhase());
        assertEquals("Ambos os eixos a vermelho no desimpedimento", LightState.RED, auth.getLightState(0));
        assertEquals("Ambos os eixos a vermelho no desimpedimento", LightState.RED, auth.getLightState(1));

        // Avança all-red
        Thread.sleep(150);
        auth.updateTick(0, 0);
        assertEquals("Deve atingir NS_GREEN para a emergência", Phase.NS_GREEN, auth.getCurrentPhase());
        assertEquals("Status do pedido deve ser VERDE_CONCEDIDO", EmergencyStatus.VERDE_CONCEDIDO, req.getStatus());
        assertEquals("Callback onGreenGranted deve ter sido acionado", true, greenGrantedCalled.get());
    }

    /* =========================================================================
     * TESTE 3: EMERGÊNCIA NO EIXO EW
     * ========================================================================= */
    private static void testEmergencyEW() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(0); // Cruzamento 0 começa em NS_GREEN
        auth.setYellowDuration(200);
        auth.setAllRedDuration(100);

        assertEquals("Cruzamento 0 começa em NS_GREEN", Phase.NS_GREEN, auth.getCurrentPhase());

        EmergencyRequest req = auth.requestEmergency("Policia_01", 1, "Policia_01");
        assertEquals("Deve transitar para NS_YELLOW", Phase.NS_YELLOW, auth.getCurrentPhase());

        Thread.sleep(250);
        auth.updateTick(0, 0);
        assertEquals("Deve transitar para ALL_RED", Phase.ALL_RED, auth.getCurrentPhase());

        Thread.sleep(150);
        auth.updateTick(0, 0);
        assertEquals("Deve transitar para EW_GREEN", Phase.EW_GREEN, auth.getCurrentPhase());
        assertEquals("Status do pedido deve ser VERDE_CONCEDIDO", EmergencyStatus.VERDE_CONCEDIDO, req.getStatus());
    }

    /* =========================================================================
     * TESTE 4: EMERGÊNCIAS SIMULTÂNEAS (CONFLITO NS vs EW)
     * ========================================================================= */
    private static void testSimultaneousEmergenciesNSEW() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(0);
        auth.setYellowDuration(150);
        auth.setAllRedDuration(100);

        // Começa em NS_GREEN
        assertEquals("Começa em NS_GREEN", Phase.NS_GREEN, auth.getCurrentPhase());

        // Chegam duas emergências quase ao mesmo tempo: Ambulancia no NS e Bombeiros no EW
        EmergencyRequest reqNS = auth.requestEmergency("Ambulancia_NS", 0, "Ambulancia_NS");
        EmergencyRequest reqEW = auth.requestEmergency("Bombeiros_EW", 1, "Bombeiros_EW");

        // Como o cruzamento já está em NS_GREEN, reqNS obtém verde concedido imediatamente
        assertEquals("reqNS obtém verde concedido", EmergencyStatus.VERDE_CONCEDIDO, reqNS.getStatus());
        assertEquals("reqEW fica enfileirado com status ACEITE", EmergencyStatus.ACEITE, reqEW.getStatus());

        // Invariante: EW nunca pode estar verde aqui!
        assertEquals("EW deve estar RED", LightState.RED, auth.getLightState(1));

        // Ambulancia_NS conclui a travessia e desimpedimento
        auth.notifyEmergencyCleared("Ambulancia_NS");
        assertEquals("reqNS concluído", EmergencyStatus.CONCLUIDO, reqNS.getStatus());

        // Autoridade deve iniciar a transição segura para a emergência pendente no EW
        auth.updateTick(0, 0);
        assertEquals("Deve iniciar amarelo em NS", Phase.NS_YELLOW, auth.getCurrentPhase());

        Thread.sleep(200);
        auth.updateTick(0, 0);
        assertEquals("Deve passar por ALL_RED", Phase.ALL_RED, auth.getCurrentPhase());

        Thread.sleep(150);
        auth.updateTick(0, 0);
        assertEquals("Deve conceder verde para reqEW", Phase.EW_GREEN, auth.getCurrentPhase());
        assertEquals("reqEW agora possui VERDE_CONCEDIDO", EmergencyStatus.VERDE_CONCEDIDO, reqEW.getStatus());
        assertEquals("NS agora está bloqueado a RED", LightState.RED, auth.getLightState(0));
    }

    /* =========================================================================
     * TESTE 5: IDEMPOTÊNCIA E ANTI-DEBOUNCING (PEDIDOS REPETIDOS)
     * ========================================================================= */
    private static void testRepeatedRequestsDebouncing() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(2);
        auth.setYellowDuration(150);
        auth.setAllRedDuration(100);

        EmergencyRequest r1 = auth.requestEmergency("Ambulancia_INEM_01", 1, "Ambulancia_INEM_01");
        EmergencyRequest r2 = auth.requestEmergency("Ambulancia_INEM_01", 1, "Ambulancia_INEM_01");
        EmergencyRequest r3 = auth.requestEmergency("Ambulancia_INEM_01", 1, "Ambulancia_INEM_01");

        assertSame("Pedidos repetidos devem retornar exatamente a mesma instância de requisição", r1, r2);
        assertSame("Pedidos repetidos não devem duplicar requisições", r2, r3);
    }

    /* =========================================================================
     * TESTE 6: RESTAURO DO CONTROLO ADAPTATIVO
     * ========================================================================= */
    private static void testReturnToNormalControl() throws Exception {
        IntersectionSafetyAuthority auth = new IntersectionSafetyAuthority(3);
        auth.setYellowDuration(150);
        auth.setAllRedDuration(100);
        auth.setCurrentGreenDuration(250);

        EmergencyRequest req = auth.requestEmergency("Ambulancia_01", 0, "Ambulancia_01");
        Thread.sleep(200);
        auth.updateTick(0, 0);
        Thread.sleep(150);
        auth.updateTick(0, 0);

        assertEquals("Atingiu NS_GREEN para emergência", Phase.NS_GREEN, auth.getCurrentPhase());

        // Notifica desimpedimento
        auth.notifyEmergencyCleared("Ambulancia_01");

        // Continua ciclo adaptativo normal
        Thread.sleep(300);
        auth.updateTick(2, 5); // Fila no EW maior
        assertEquals("Após verde regular, transita para NS_YELLOW", Phase.NS_YELLOW, auth.getCurrentPhase());

        Thread.sleep(200);
        auth.updateTick(0, 0);
        assertEquals("Transita para ALL_RED", Phase.ALL_RED, auth.getCurrentPhase());

        Thread.sleep(150);
        auth.updateTick(0, 0);
        assertEquals("Transita para EW_GREEN adaptativo", Phase.EW_GREEN, auth.getCurrentPhase());
    }

    /* =========================================================================
     * TESTE 7: INVARIANTE RIGOROSO ANTI-COLISÃO
     * ========================================================================= */
    private static void testInvariantNoIncompatibleGreens() {
        // Validação de casos proibidos
        assertThrows(() -> IntersectionSafetyAuthority.assertSafetyInvariant(LightState.GREEN, LightState.GREEN));
        assertThrows(() -> IntersectionSafetyAuthority.assertSafetyInvariant(LightState.GREEN, LightState.YELLOW));
        assertThrows(() -> IntersectionSafetyAuthority.assertSafetyInvariant(LightState.YELLOW, LightState.GREEN));

        // Casos válidos não devem lançar exceção
        IntersectionSafetyAuthority.assertSafetyInvariant(LightState.GREEN, LightState.RED);
        IntersectionSafetyAuthority.assertSafetyInvariant(LightState.YELLOW, LightState.RED);
        IntersectionSafetyAuthority.assertSafetyInvariant(LightState.RED, LightState.GREEN);
        IntersectionSafetyAuthority.assertSafetyInvariant(LightState.RED, LightState.YELLOW);
        IntersectionSafetyAuthority.assertSafetyInvariant(LightState.RED, LightState.RED);
    }

    /* =========================================================================
     * UTILITÁRIOS DE ASSERTION
     * ========================================================================= */
    private static void assertEquals(String msg, Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(msg + " -> Esperado: " + expected + ", mas obtido: " + actual);
    }

    private static void assertSame(String msg, Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError(msg + " -> Instâncias diferentes!");
        }
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
            throw new AssertionError("Deveria ter lançado IllegalStateException, mas nada foi lançado!");
        } catch (IllegalStateException expected) {
            // Sucesso
        }
    }
}
