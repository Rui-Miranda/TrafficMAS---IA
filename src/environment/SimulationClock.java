package environment;

/**
 * Provedor Centralizado e Determinístico de Tempo para o TrafficMAS (Fase 5 - Etapa 2).
 *
 * Resolve a dessincronização entre o relógio de parede (System.currentTimeMillis())
 * e o relógio da simulação acelerada (HeadlessTrafficSimulator).
 *
 * Características:
 * 1. Em modo real (GUI Swing ou execução padrão de agentes JADE), retorna System.currentTimeMillis().
 * 2. Em modo simulado, fornece tempo discreto monotónico, avançado tick a tick pelo motor físico.
 * 3. Thread-safe e reutilizável com reinicialização explícita entre execuções experimentais.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public final class SimulationClock {

    private static volatile boolean simulatedMode = false;
    private static volatile long simulatedTimeMillis = 0L;
    private static volatile long runStartWallClockMillis = 0L;

    private SimulationClock() {}

    /**
     * Retorna o carimbo temporal atual em milissegundos.
     * Se estiver em modo simulado, retorna o tempo simulado acumulado.
     * Caso contrário, retorna o relógio do sistema operativo (System.currentTimeMillis()).
     */
    public static long currentTimeMillis() {
        if (simulatedMode) {
            return simulatedTimeMillis;
        }
        return System.currentTimeMillis();
    }

    /**
     * Retorna o tempo atual em segundos (double).
     */
    public static double currentTimeSeconds() {
        return currentTimeMillis() / 1000.0;
    }

    /**
     * Ativa o modo de tempo simulado partindo de t = 0ms.
     */
    public static synchronized void enableSimulatedMode() {
        simulatedMode = true;
        simulatedTimeMillis = 0L;
        runStartWallClockMillis = System.currentTimeMillis();
    }

    /**
     * Ativa o modo de tempo simulado inicializando com um timestamp específico.
     */
    public static synchronized void enableSimulatedMode(long initialMillis) {
        simulatedMode = true;
        simulatedTimeMillis = Math.max(0L, initialMillis);
        runStartWallClockMillis = System.currentTimeMillis();
    }

    /**
     * Desativa o modo simulado e restaura a delegação direta em System.currentTimeMillis().
     */
    public static synchronized void disableSimulatedMode() {
        simulatedMode = false;
        simulatedTimeMillis = 0L;
        runStartWallClockMillis = 0L;
    }

    /**
     * Define o tempo simulado em segundos (garantindo monotonicidade).
     */
    public static synchronized void setSimulatedTimeSeconds(double seconds) {
        long targetMillis = (long) Math.round(seconds * 1000.0);
        if (targetMillis >= simulatedTimeMillis) {
            simulatedTimeMillis = targetMillis;
        }
    }

    /**
     * Define o tempo simulado em milissegundos (garantindo monotonicidade).
     */
    public static synchronized void setSimulatedTimeMillis(long millis) {
        if (millis >= simulatedTimeMillis) {
            simulatedTimeMillis = millis;
        }
    }

    /**
     * Avança o tempo simulado por um delta de segundos (ex: dt = 1/30 s).
     */
    public static synchronized void advanceSeconds(double deltaSeconds) {
        if (deltaSeconds > 0) {
            simulatedTimeMillis += (long) Math.round(deltaSeconds * 1000.0);
        }
    }

    /**
     * Avança o tempo simulado por um delta de milissegundos.
     */
    public static synchronized void advanceMillis(long deltaMillis) {
        if (deltaMillis > 0) {
            simulatedTimeMillis += deltaMillis;
        }
    }

    /**
     * Indica se o relógio está em modo de tempo simulado.
     */
    public static boolean isSimulatedMode() {
        return simulatedMode;
    }

    /**
     * Reinicializa o relógio para o estado inicial de simulação.
     */
    public static synchronized void reset() {
        simulatedMode = false;
        simulatedTimeMillis = 0L;
        runStartWallClockMillis = 0L;
    }
}
