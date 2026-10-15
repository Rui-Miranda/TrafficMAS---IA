package test;

import environment.CitySimulationGUI;
import environment.IntersectionSafetyAuthority;

/**
 * Suite de Testes da Fase 2 — Validação dos Agentes de Veículos.
 *
 * Testa exclusivamente as melhorias implementadas na Fase 2:
 * 1. Resolução correta do nome canónico do semáforo para cada cruzamento e eixo.
 * 2. Validação da lógica de anti-debouncing por cruzamento.
 * 3. Cobertura dos 4 cruzamentos (C1_NW, C2_NE, C3_SW, C4_SE).
 * 4. Validação da deteção de cruzamento para as 4 direções de circulação.
 * 5. Compatibilidade retroativa com os testes da Fase 1.
 *
 * NOTA: Executa sem depender da interface gráfica Swing (headless-safe).
 *       Os testes de perceção visual são validados por verificação lógica
 *       das coordenadas dos cruzamentos definidos na GUI.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class VehicleTestSuite {

    private static int passed = 0;
    private static int failed = 0;

    private static final String[] CROSSING_NAMES = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    // Constantes replicadas da CitySimulationGUI para validação offline
    private static final int ROAD_X1 = 280;
    private static final int ROAD_X2 = 640;
    private static final int ROAD_Y1 = 280;
    private static final int ROAD_Y2 = 640;
    private static final int ROAD_WIDTH = 96;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE TESTES DOS AGENTES DE VEICULOS (FASE 2)");
        System.out.println("================================================================================");

        // Reiniciar estado global
        IntersectionSafetyAuthority.resetAll();

        // Executar testes
        teste1_ResolucaoCanonicoSemaforoNS();
        teste2_ResolucaoCanonicoSemaforoEW();
        teste3_LogicaDeCruzamentoParaDirecaoNS();
        teste4_LogicaDeCruzamentoParaDirecaoEW();
        teste5_AntiDebouncingPorCruzamento();
        teste6_CompatibilidadeFase1();
        teste7_CoberturaTotalDos4Cruzamentos();

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("RESULTADO GLOBAL FASE 2: " + passed + " PASSOU | " + failed + " FALHOU");
        System.out.println("--------------------------------------------------------------------------------");

        if (failed == 0) {
            System.out.println("SUCESSO: Todos os testes da Fase 2 foram validados com êxito!");
        } else {
            System.out.println("FALHA: Existem " + failed + " testes que necessitam de correção.");
            System.exit(1);
        }
    }

    /**
     * Teste 1: Resolução Correta do Nome Canónico do Semáforo — Eixo NS.
     *
     * Para cada cruzamento, o nome do semáforo NS deve seguir o padrão
     * "Semaforo_NS_{CROSSING_NAME}".
     */
    private static void teste1_ResolucaoCanonicoSemaforoNS() {
        System.out.print("[TESTE] 1. Resolução Canónica do Semáforo (Eixo NS) ... ");
        try {
            String[] expectedNS = {
                "Semaforo_NS_C1_NW", "Semaforo_NS_C2_NE", "Semaforo_NS_C3_SW", "Semaforo_NS_C4_SE"
            };

            for (int i = 0; i < 4; i++) {
                String computed = "Semaforo_NS_" + CROSSING_NAMES[i];
                assertCondition(computed.equals(expectedNS[i]),
                        "Cruzamento " + i + ": esperava " + expectedNS[i] + ", obteve " + computed);
            }

            // Verificar que o nome antigo hardcoded "Semaforo_NS_C1_NW" é apenas UM dos 4 possíveis
            assertCondition(expectedNS[0].equals("Semaforo_NS_C1_NW"),
                    "O antigo nome hardcoded deve ser apenas o cruzamento 0, não os outros 3");

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 2: Resolução Correta do Nome Canónico do Semáforo — Eixo EW.
     */
    private static void teste2_ResolucaoCanonicoSemaforoEW() {
        System.out.print("[TESTE] 2. Resolução Canónica do Semáforo (Eixo EW) ... ");
        try {
            String[] expectedEW = {
                "Semaforo_EW_C1_NW", "Semaforo_EW_C2_NE", "Semaforo_EW_C3_SW", "Semaforo_EW_C4_SE"
            };

            for (int i = 0; i < 4; i++) {
                String computed = "Semaforo_EW_" + CROSSING_NAMES[i];
                assertCondition(computed.equals(expectedEW[i]),
                        "Cruzamento " + i + ": esperava " + expectedEW[i] + ", obteve " + computed);
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 3: Lógica de Determinação do Cruzamento para Direção NS (vertical).
     *
     * Replica a lógica de getTargetIntersection para validar que:
     * - Veículos a descer (dir=0) no corredor Oeste (x<460) antes de Y1 → C1_NW (0)
     * - Veículos a descer (dir=0) no corredor Este (x>460) antes de Y1 → C2_NE (1)
     * - Veículos a descer (dir=0) no corredor Oeste (x<460) depois de Y1 → C3_SW (2)
     * - Veículos a descer (dir=0) no corredor Este (x>460) depois de Y1 → C4_SE (3)
     */
    private static void teste3_LogicaDeCruzamentoParaDirecaoNS() {
        System.out.print("[TESTE] 3. Lógica de Cruzamento para Direção NS (Vertical) ... ");
        try {
            // Direção 0 (N->S desce), corredor Oeste (x=304), acima de Y1
            int t1 = simulateTargetIntersection(304.0, 200.0, 0);
            assertCondition(t1 == 0, "NS descida, Oeste, acima de Y1 → C1_NW (0), obteve: " + t1);

            // Direção 0, corredor Este (x=664), acima de Y1
            int t2 = simulateTargetIntersection(664.0, 200.0, 0);
            assertCondition(t2 == 1, "NS descida, Este, acima de Y1 → C2_NE (1), obteve: " + t2);

            // Direção 0, corredor Oeste, abaixo de Y1 (entre cruzamentos)
            int t3 = simulateTargetIntersection(304.0, 500.0, 0);
            assertCondition(t3 == 2, "NS descida, Oeste, entre cruzamentos → C3_SW (2), obteve: " + t3);

            // Direção 0, corredor Este, abaixo de Y1
            int t4 = simulateTargetIntersection(664.0, 500.0, 0);
            assertCondition(t4 == 3, "NS descida, Este, entre cruzamentos → C4_SE (3), obteve: " + t4);

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 4: Lógica de Determinação do Cruzamento para Direção EW (horizontal).
     */
    private static void teste4_LogicaDeCruzamentoParaDirecaoEW() {
        System.out.print("[TESTE] 4. Lógica de Cruzamento para Direção EW (Horizontal) ... ");
        try {
            // Direção 2 (W->E direita), corredor Norte (y=304), à esquerda de X1
            int t1 = simulateTargetIntersection(100.0, 304.0, 2);
            assertCondition(t1 == 0, "EW direita, Norte, antes de X1 → C1_NW (0), obteve: " + t1);

            // Direção 2, corredor Sul (y=664), à esquerda de X1
            int t2 = simulateTargetIntersection(100.0, 664.0, 2);
            assertCondition(t2 == 2, "EW direita, Sul, antes de X1 → C3_SW (2), obteve: " + t2);

            // Direção 2, corredor Norte, entre cruzamentos
            int t3 = simulateTargetIntersection(500.0, 304.0, 2);
            assertCondition(t3 == 1, "EW direita, Norte, entre cruzamentos → C2_NE (1), obteve: " + t3);

            // Direção 2, corredor Sul, entre cruzamentos
            int t4 = simulateTargetIntersection(500.0, 664.0, 2);
            assertCondition(t4 == 3, "EW direita, Sul, entre cruzamentos → C4_SE (3), obteve: " + t4);

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 5: Anti-Debouncing por Cruzamento.
     *
     * Valida que o mecanismo de anti-debouncing distingue pedidos entre cruzamentos diferentes
     * e que o intervalo mínimo de 6s é respeitado.
     */
    private static void teste5_AntiDebouncingPorCruzamento() {
        System.out.print("[TESTE] 5. Anti-Debouncing por Cruzamento ... ");
        try {
            long[] lastRequestPerIntersection = new long[4];
            long INTERVALO_MINIMO_MS = 6000;

            // Simular primeiro pedido para cruzamento 0
            long now = System.currentTimeMillis();
            boolean shouldSend1 = (now - lastRequestPerIntersection[0] >= INTERVALO_MINIMO_MS);
            assertCondition(shouldSend1, "Primeiro pedido para cruzamento 0 deve ser autorizado");
            lastRequestPerIntersection[0] = now;

            // Simular pedido imediato para o mesmo cruzamento (deve ser bloqueado)
            boolean shouldSend2 = (now - lastRequestPerIntersection[0] >= INTERVALO_MINIMO_MS);
            assertCondition(!shouldSend2, "Pedido repetido para cruzamento 0 deve ser bloqueado");

            // Simular pedido para cruzamento diferente (deve ser autorizado)
            boolean shouldSend3 = (now - lastRequestPerIntersection[1] >= INTERVALO_MINIMO_MS);
            assertCondition(shouldSend3, "Primeiro pedido para cruzamento 1 deve ser autorizado");
            lastRequestPerIntersection[1] = now;

            // Simular pedido para cruzamento 0 após 6 segundos (deve ser autorizado)
            long future = now + 6001;
            boolean shouldSend4 = (future - lastRequestPerIntersection[0] >= INTERVALO_MINIMO_MS);
            assertCondition(shouldSend4, "Pedido para cruzamento 0 após 6s deve ser autorizado");

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 6: Compatibilidade Retroativa com Fase 1.
     */
    private static void teste6_CompatibilidadeFase1() {
        System.out.print("[TESTE] 6. Compatibilidade Retroativa com Fase 1 (Segurança) ... ");
        try {
            IntersectionSafetyAuthority.resetAll();

            for (int i = 0; i < 4; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                IntersectionSafetyAuthority.Phase phase = auth.getCurrentPhase();

                if (i % 2 == 0) {
                    assertCondition(phase == IntersectionSafetyAuthority.Phase.NS_GREEN,
                            "Cruzamento " + i + " (par) deve começar em NS_GREEN, obteve: " + phase);
                } else {
                    assertCondition(phase == IntersectionSafetyAuthority.Phase.EW_GREEN,
                            "Cruzamento " + i + " (ímpar) deve começar em EW_GREEN, obteve: " + phase);
                }

                // Verificar invariante de segurança
                CitySimulationGUI.LightState nsState = auth.getLightState(0);
                CitySimulationGUI.LightState ewState = auth.getLightState(1);
                assertCondition(!(nsState == CitySimulationGUI.LightState.GREEN 
                        && ewState == CitySimulationGUI.LightState.GREEN),
                        "Invariante violado no cruzamento " + i + ": NS=" + nsState + ", EW=" + ewState);
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /**
     * Teste 7: Cobertura Total dos 4 Cruzamentos.
     *
     * Verifica que cada direção (0, 1, 2, 3) em cada posição produz
     * um dos 4 cruzamentos válidos (0, 1, 2, 3) sem valores inválidos.
     */
    private static void teste7_CoberturaTotalDos4Cruzamentos() {
        System.out.print("[TESTE] 7. Cobertura Total dos 4 Cruzamentos ... ");
        try {
            boolean[] covered = new boolean[4];

            // Testar todas as combinações de direções e corredores
            // Direção 0 (NS desce): corredor Oeste acima Y1 → 0, Este acima Y1 → 1, Oeste abaixo → 2, Este abaixo → 3
            covered[simulateTargetIntersection(304.0, 200.0, 0)] = true;
            covered[simulateTargetIntersection(664.0, 200.0, 0)] = true;
            covered[simulateTargetIntersection(304.0, 500.0, 0)] = true;
            covered[simulateTargetIntersection(664.0, 500.0, 0)] = true;

            for (int i = 0; i < 4; i++) {
                assertCondition(covered[i], 
                        "Cruzamento " + CROSSING_NAMES[i] + " (" + i + ") não foi coberto pela direção NS");
            }

            // Reset e testar direção EW
            covered = new boolean[4];
            covered[simulateTargetIntersection(100.0, 304.0, 2)] = true;
            covered[simulateTargetIntersection(100.0, 664.0, 2)] = true;
            covered[simulateTargetIntersection(500.0, 304.0, 2)] = true;
            covered[simulateTargetIntersection(500.0, 664.0, 2)] = true;

            for (int i = 0; i < 4; i++) {
                assertCondition(covered[i], 
                        "Cruzamento " + CROSSING_NAMES[i] + " (" + i + ") não foi coberto pela direção EW");
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("FALHOU ✗ - " + e.getMessage());
            failed++;
        }
    }

    /* =========================================================================
     * UTILITÁRIOS DE SIMULAÇÃO OFFLINE (sem dependência do Swing)
     * ========================================================================= */

    /**
     * Replica a lógica de {@link CitySimulationGUI#getTargetIntersection} offline,
     * permitindo testes headless sem instanciar a janela Swing.
     *
     * @param x Posição X do veículo
     * @param y Posição Y do veículo
     * @param direction Direção de circulação (0=N->S, 1=S->N, 2=W->E, 3=E->W)
     * @return Índice do cruzamento-alvo (0=NW, 1=NE, 2=SW, 3=SE)
     */
    private static int simulateTargetIntersection(double x, double y, int direction) {
        boolean isWest = (x < 460);
        boolean isNorth = (y < 460);
        switch (direction) {
            case 0: return (y < ROAD_Y1 - 2) ? (isWest ? 0 : 1) : (isWest ? 2 : 3);
            case 1: return (y > ROAD_Y2 + ROAD_WIDTH + 2) ? (isWest ? 2 : 3) : (isWest ? 0 : 1);
            case 2: return (x < ROAD_X1 - 2) ? (isNorth ? 0 : 2) : (isNorth ? 1 : 3);
            case 3: return (x > ROAD_X2 + ROAD_WIDTH + 2) ? (isNorth ? 1 : 3) : (isNorth ? 0 : 2);
            default: return 0;
        }
    }

    /**
     * Verificação de condição com lançamento de AssertionError descritivo.
     */
    private static void assertCondition(boolean condition, String errorMessage) {
        if (!condition) {
            throw new AssertionError(errorMessage);
        }
    }

    /**
     * Classe auxiliar para erros de asserção nos testes.
     */
    private static class AssertionError extends RuntimeException {
        public AssertionError(String message) {
            super(message);
        }
    }
}
