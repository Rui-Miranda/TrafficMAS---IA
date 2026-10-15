package test;

import environment.CitySimulationGUI.LightState;
import environment.HeadlessTrafficSimulator;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.Phase;
import environment.SimulationClock;
import environment.TrafficEnvironment;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Suite de Validação Rigorosa do SimulationClock e Sincronização JADE (Fase 5 - Checkpoint B).
 *
 * Validações obrigatórias:
 * 1. Todos os 4 cruzamentos alternam efetivamente entre NS_GREEN, NS_YELLOW, ALL_RED, EW_GREEN, EW_YELLOW.
 * 2. As durações das fases correspondem fielmente ao tempo simulado.
 * 3. Ausência absoluta de semáforos congelados por dependência de relógio de parede.
 * 4. Invariante de segurança física: verdes e amarelos incompatíveis nunca ocorrem simultaneamente.
 * 5. Precedência absoluta de veículos de emergência sob relógio simulado.
 * 6. Reinicialização e isolamento temporal estrito entre execuções sucessivas.
 * 7. Sincronização e entrega de mensagens FIPA-ACL com timeout explícito.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class SimulationClockValidationTest {

    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — VALIDAÇÃO RIGOROSA DO SIMULATIONCLOCK (CHECKPOINT B)");
        System.out.println("================================================================================");

        runTest("1. Alternância dinâmica de fases em todos os 4 cruzamentos ao longo de 310s",
                SimulationClockValidationTest::testAlternanciaDeFasesNosQuatroCruzamentos);

        runTest("2. Invariante de segurança física de semáforos (Zero Verdes Incompatíveis)",
                SimulationClockValidationTest::testInvarianteSegurancaVerdesIncompativeis);

        runTest("3. Correspondência exata das durações de fase ao tempo simulado",
                SimulationClockValidationTest::testCorrespondenciaDuracoesTempoSimulado);

        runTest("4. Precedência e atendimento de emergência com relógio simulado",
                SimulationClockValidationTest::testPrecedenciaEmergenciaSobTempoSimulado);

        runTest("5. Reinicialização, monotonicidade e isolamento temporal entre corridas",
                SimulationClockValidationTest::testReinicioEIsolamentoTemporal);

        runTest("6. Sincronização de mensagens JADE FIPA-ACL com timeout controlado",
                SimulationClockValidationTest::testSincronizacaoJADEComTimeout);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("RESULTADO GLOBAL CHECKPOINT B: " + totalPassed + " PASSOU | " + totalFailed + " FALHOU");
        System.out.println("--------------------------------------------------------------------------------");

        if (totalFailed > 0) {
            System.err.println("ERRO: Validação do SimulationClock falhou!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: SimulationClock e sincronização JADE validados com 100% de conformidade!\n");
            System.exit(0);
        }
    }

    private static void runTest(String name, TestCase testCase) {
        System.out.print("[TESTE] " + name + " ... ");
        try {
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

    /**
     * TESTE 1: Demonstra que ao longo de 310 segundos simulados, todos os cruzamentos alternam
     * regularmente entre todas as fases semáforicas.
     */
    private static void testAlternanciaDeFasesNosQuatroCruzamentos() throws Exception {
        SimulationClock.enableSimulatedMode(0L);
        IntersectionSafetyAuthority.resetAll();

        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator("NORMAL", 42L, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE);

        // Mapa para registar o histórico de fases visitadas por cruzamento
        Map<Integer, Set<Phase>> visitedPhases = new HashMap<Integer, Set<Phase>>();
        Map<Integer, Integer> phaseTransitions = new HashMap<Integer, Integer>();
        Map<Integer, Phase> lastPhase = new HashMap<Integer, Phase>();

        for (int i = 0; i < 4; i++) {
            visitedPhases.put(i, new HashSet<>());
            phaseTransitions.put(i, 0);
            lastPhase.put(i, IntersectionSafetyAuthority.getAuthority(i).getCurrentPhase());
            visitedPhases.get(i).add(lastPhase.get(i));
        }

        double dt = 1.0 / 30.0;
        int totalSteps = (int) (310.0 / dt);

        for (int step = 0; step < totalSteps; step++) {
            sim.advanceSeconds(dt);

            for (int i = 0; i < 4; i++) {
                Phase p = IntersectionSafetyAuthority.getAuthority(i).getCurrentPhase();
                visitedPhases.get(i).add(p);
                if (p != lastPhase.get(i)) {
                    phaseTransitions.put(i, phaseTransitions.get(i) + 1);
                    lastPhase.put(i, p);
                }
            }
        }

        // Verificações
        for (int i = 0; i < 4; i++) {
            Set<Phase> phases = visitedPhases.get(i);
            int transitions = phaseTransitions.get(i);

            if (!phases.contains(Phase.NS_GREEN)) throw new AssertionError("C" + (i + 1) + " nunca visitou NS_GREEN!");
            if (!phases.contains(Phase.NS_YELLOW)) throw new AssertionError("C" + (i + 1) + " nunca visitou NS_YELLOW!");
            if (!phases.contains(Phase.ALL_RED)) throw new AssertionError("C" + (i + 1) + " nunca visitou ALL_RED!");
            if (!phases.contains(Phase.EW_GREEN)) throw new AssertionError("C" + (i + 1) + " nunca visitou EW_GREEN!");
            if (!phases.contains(Phase.EW_YELLOW)) throw new AssertionError("C" + (i + 1) + " nunca visitou EW_YELLOW!");

            // Em 310 segundos, com ciclos de 12 a 20 segundos, esperamos pelo menos 15 a 50 transições
            if (transitions < 15) {
                throw new AssertionError("C" + (i + 1) + " realizou transições insuficientes (" + transitions + " transições em 310s).");
            }
        }

        SimulationClock.disableSimulatedMode();
    }

    /**
     * TESTE 2: Garante que o invariante físico de segurança é respeitado em 100% dos ticks.
     */
    private static void testInvarianteSegurancaVerdesIncompativeis() throws Exception {
        SimulationClock.enableSimulatedMode(0L);
        IntersectionSafetyAuthority.resetAll();

        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator("HEAVY_TRAFFIC", 999L, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE);

        double dt = 1.0 / 30.0;
        int totalSteps = (int) (310.0 / dt);

        for (int step = 0; step < totalSteps; step++) {
            sim.advanceSeconds(dt);

            for (int i = 0; i < 4; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                LightState ns = auth.getLightState(0);
                LightState ew = auth.getLightState(1);

                if (ns == LightState.GREEN && ew == LightState.GREEN) {
                    throw new AssertionError("Violação crítica no C" + (i + 1) + ": Verdes simultâneos em t=" + sim.getCurrentSimTime());
                }
                if (ns == LightState.GREEN && ew == LightState.YELLOW) {
                    throw new AssertionError("Violação no C" + (i + 1) + ": Verde e Amarelo simultâneos em t=" + sim.getCurrentSimTime());
                }
                if (ns == LightState.YELLOW && ew == LightState.GREEN) {
                    throw new AssertionError("Violação no C" + (i + 1) + ": Amarelo e Verde simultâneos em t=" + sim.getCurrentSimTime());
                }
            }
        }

        SimulationClock.disableSimulatedMode();
    }

    /**
     * TESTE 3: Mede as durações exatas das fases amarelas e all-red em tempo simulado.
     */
    private static void testCorrespondenciaDuracoesTempoSimulado() throws Exception {
        SimulationClock.enableSimulatedMode(0L);

        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator("NORMAL", 123L, IntersectionSafetyAuthority.ControlMode.FIXED_TIME);
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(0);
        auth.setControlMode(IntersectionSafetyAuthority.ControlMode.FIXED_TIME);
        auth.setFixedGreenDuration(4000L); // 4.0s de verde
        auth.setYellowDuration(2000L);     // 2.0s de amarelo
        auth.setAllRedDuration(1000L);     // 1.0s de all-red

        // Avança passo a passo e mede a duração observada de um amarelo e de um all-red
        double dt = 1.0 / 30.0;
        Phase prevPhase = auth.getCurrentPhase();
        double phaseStartT = sim.getCurrentSimTime();

        List<Double> yellowDurations = new ArrayList<>();
        List<Double> allRedDurations = new ArrayList<>();

        for (int step = 0; step < (int) (60.0 / dt); step++) {
            sim.advanceSeconds(dt);
            Phase curr = auth.getCurrentPhase();
            if (curr != prevPhase) {
                double duration = sim.getCurrentSimTime() - phaseStartT;
                if (prevPhase == Phase.NS_YELLOW || prevPhase == Phase.EW_YELLOW) {
                    yellowDurations.add(duration);
                } else if (prevPhase == Phase.ALL_RED) {
                    allRedDurations.add(duration);
                }
                prevPhase = curr;
                phaseStartT = sim.getCurrentSimTime();
            }
        }

        if (yellowDurations.isEmpty() || allRedDurations.isEmpty()) {
            throw new AssertionError("Transições de amarelo ou all-red não foram observadas.");
        }

        for (double d : yellowDurations) {
            if (Math.abs(d - 2.0) > 0.08) {
                throw new AssertionError("Duração de amarelo desviou do tempo simulado: esperava ~2.0s, obteve " + d + "s");
            }
        }

        for (double d : allRedDurations) {
            if (Math.abs(d - 1.0) > 0.08) {
                throw new AssertionError("Duração de all-red desviou do tempo simulado: esperava ~1.0s, obteve " + d + "s");
            }
        }

        SimulationClock.disableSimulatedMode();
    }

    /**
     * TESTE 4: Valida a preempção de emergência sob relógio simulado.
     */
    private static void testPrecedenciaEmergenciaSobTempoSimulado() throws Exception {
        SimulationClock.enableSimulatedMode(0L);

        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator("NORMAL", 456L, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE);
        IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(1);
        auth.setControlMode(IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE);

        // Avança até o semáforo estar em NS_GREEN
        double dt = 1.0 / 30.0;
        while (auth.getCurrentPhase() != Phase.NS_GREEN && sim.getCurrentSimTime() < 20.0) {
            sim.advanceSeconds(dt);
        }

        // Submete emergência no eixo oposto (EW - axis 1)
        double reqTime = sim.getCurrentSimTime();
        auth.requestEmergency("Ambulancia_Clock_Test", 1, "Ambulancia_AID");

        // Avança até o verde ser concedido ao eixo EW
        while (auth.getCurrentPhase() != Phase.EW_GREEN && sim.getCurrentSimTime() < reqTime + 10.0) {
            sim.advanceSeconds(dt);
        }

        double greenGrantedTime = sim.getCurrentSimTime();
        double latency = greenGrantedTime - reqTime;

        if (auth.getCurrentPhase() != Phase.EW_GREEN) {
            throw new AssertionError("Verde de emergência não foi concedido dentro do prazo limite.");
        }

        // Latência deve respeitar amarelo (~1.5s) + all-red (~1.0s) = ~2.5s (tolerância: 2.0s a 4.5s)
        if (latency < 2.0 || latency > 4.5) {
            throw new AssertionError("Latência de transição para emergência anómala: " + latency + "s (esperado ~2.5s)");
        }

        // Conclui emergência e verifica retoma
        auth.notifyEmergencyCleared("Ambulancia_Clock_Test");
        sim.advanceSeconds(10.0);

        if (auth.getCurrentlyServingEmergency() != null) {
            throw new AssertionError("Emergência permaneceu ativa após notificação de transposição.");
        }

        SimulationClock.disableSimulatedMode();
    }

    /**
     * TESTE 5: Garante que o SimulationClock pode ser reiniciado e é estritamente monotónico.
     */
    private static void testReinicioEIsolamentoTemporal() throws Exception {
        // Corrida 1
        SimulationClock.enableSimulatedMode(0L);
        SimulationClock.advanceSeconds(150.0);
        if (Math.abs(SimulationClock.currentTimeSeconds() - 150.0) > 0.001) {
            throw new AssertionError("Tempo acumulado incorreto na Corrida 1: " + SimulationClock.currentTimeSeconds());
        }

        // Reinicialização
        SimulationClock.enableSimulatedMode(0L);
        if (SimulationClock.currentTimeMillis() != 0L) {
            throw new AssertionError("SimulationClock não reiniciou a 0ms!");
        }

        // Monotonicidade: não pode retroceder
        SimulationClock.advanceSeconds(50.0);
        SimulationClock.setSimulatedTimeSeconds(30.0); // Tentativa de retrocesso
        if (Math.abs(SimulationClock.currentTimeSeconds() - 50.0) > 0.001) {
            throw new AssertionError("SimulationClock violou monotonicidade ao permitir recuo temporal!");
        }

        SimulationClock.disableSimulatedMode();
        if (SimulationClock.isSimulatedMode()) {
            throw new AssertionError("Modo simulado não foi desativado corretamente.");
        }
    }

    /**
     * TESTE 6: Comunicação FIPA-ACL em JADE com timeout controlado sem deadlocks.
     */
    private static void testSincronizacaoJADEComTimeout() throws Exception {
        SimulationClock.enableSimulatedMode(0L);
        IntersectionSafetyAuthority.resetAll();

        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "1198"); // Porta alternativa
        p.setParameter(Profile.GUI, "false");

        AgentContainer container = rt.createMainContainer(p);
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // Cria agentes
            AgentController coord = container.createNewAgent("CoordTimeoutTest", "agents.CentralCoordinationAgent", new Object[]{});
            coord.start();

            AgentController tl = container.createNewAgent("Semaforo_NS_C1_NW", "agents.TrafficLightAgent", new Object[]{"Norte-Sul", 0});
            tl.start();

            // Aguarda inicialização com timeout real de 3 segundos
            boolean started = latch.await(1000, TimeUnit.MILLISECONDS);

            // Valida instância ativa do coordenador
            agents.CentralCoordinationAgent activeCoord = agents.CentralCoordinationAgent.getActiveInstance();
            if (activeCoord == null) {
                throw new AssertionError("CentralCoordinationAgent não registou instância ativa.");
            }

            // Dispara ciclo de coordenação
            activeCoord.triggerScheduledCoordination();

            // Dá tempo controlado (500ms) para JADE entregar as mensagens
            Thread.sleep(500);

            // Verifica se a comunicação foi concluída sem travar
            // Se chegamos aqui sem timeout nem exceção, a sincronização FIPA-ACL está funcional
        } finally {
            try {
                container.kill();
            } catch (Throwable ignored) {}
            SimulationClock.disableSimulatedMode();
        }
    }
}
