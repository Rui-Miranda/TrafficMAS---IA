package environment;

import environment.CitySimulationGUI.LightState;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Autoridade Única de Segurança e Decisão de Fases Semafóricas por Cruzamento.
 *
 * Garante rigorosamente:
 * 1. Uma única autoridade de decisão por cruzamento (índices 0 a 3).
 * 2. Máquina de estados segura com fases explícitas:
 *    - NS_GREEN
 *    - NS_YELLOW
 *    - ALL_RED
 *    - EW_GREEN
 *    - EW_YELLOW
 * 3. Transições seguras obrigatórias: Amarelo (1500ms) -> All-Red (1000ms) antes de abrir verde no eixo oposto.
 * 4. Invariante inviolável: NUNCA podem existir simultaneamente dois verdes (ou verde + amarelo) em eixos incompatíveis.
 * 5. Fila de pedidos de emergência com resolução de prioridades e conflitos entre eixos (FIFO ordenado).
 * 6. Idempotência / Anti-debouncing contra pedidos repetidos da mesma viatura.
 * 7. Confirmações distintas de emergência:
 *    - RECEBIDO
 *    - ACEITE
 *    - VERDE_CONCEDIDO
 * 8. Restauro ordenado e seguro do controlo adaptativo normal.
 * 9. Funcionamento 100% testável em modo headless (sem depender de janelas Swing).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class IntersectionSafetyAuthority {

    /** Fases explícitas do cruzamento */
    public enum Phase {
        NS_GREEN(LightState.GREEN, LightState.RED, 0),
        NS_YELLOW(LightState.YELLOW, LightState.RED, -1),
        ALL_RED(LightState.RED, LightState.RED, -1),
        EW_GREEN(LightState.RED, LightState.GREEN, 1),
        EW_YELLOW(LightState.RED, LightState.YELLOW, -1);

        public final LightState nsState;
        public final LightState ewState;
        public final int activeGreenAxis; // 0 = NS, 1 = EW, -1 = nenhum verde

        Phase(LightState nsState, LightState ewState, int activeGreenAxis) {
            this.nsState = nsState;
            this.ewState = ewState;
            this.activeGreenAxis = activeGreenAxis;
        }
    }

    /** Estado de processamento de um pedido de emergência */
    public enum EmergencyStatus {
        RECEBIDO,
        ACEITE,
        VERDE_CONCEDIDO,
        CONCLUIDO
    }

    /** Modos de controlo experimental semafórico (Fase 5 - Etapa 2) */
    public enum ControlMode {
        FIXED_TIME,
        LOCAL_ADAPTIVE,
        MULTIAGENT_COORDINATED,
        MULTIAGENT_ML
    }

    /** Estrutura imutável de um pedido de emergência */
    public static class EmergencyRequest {
        public final String vehicleId;
        public final int axis; // 0 = NS, 1 = EW
        public final long requestTime;
        public final String senderAID;
        private EmergencyStatus status;

        public EmergencyRequest(String vehicleId, int axis, long requestTime, String senderAID) {
            this.vehicleId = vehicleId;
            this.axis = axis;
            this.requestTime = requestTime;
            this.senderAID = senderAID;
            this.status = EmergencyStatus.RECEBIDO;
        }

        public EmergencyStatus getStatus() {
            return status;
        }

        public void setStatus(EmergencyStatus status) {
            this.status = status;
        }

        @Override
        public String toString() {
            return "EmergencyRequest[id=" + vehicleId + ", axis=" + (axis == 0 ? "NS" : "EW") 
                    + ", status=" + status + "]";
        }
    }

    /**
     * Diretiva de Coordenação Semafórica Global (Fase 3).
     *
     * Modela comandos macro emitidos pelo CentralCoordinationAgent, tais como:
     * - ONDA_VERDE (Green Wave com desfasamento temporal / offset)
     * - EQUILIBRIO_ADAPTATIVO (Extensão de tempos de verde em eixos congestionados)
     */
    public static class CoordinationPolicy {
        public final String cmdId;
        public final String policyType; // "EXTEND_GREEN", "GREEN_WAVE", "BALANCED"
        public final int targetAxis; // 0 = NS, 1 = EW
        public final long plannedDurationMs;
        public final long offsetMs;
        public final long validUntilMs;
        public final long issuedTimeMs;
        private boolean applied = false;

        public CoordinationPolicy(String cmdId, String policyType, int targetAxis,
                                  long plannedDurationMs, long offsetMs, long validUntilMs) {
            this.cmdId = cmdId;
            this.policyType = policyType;
            this.targetAxis = targetAxis;
            // Limites de segurança rigorosos: mínimo 2000ms, máximo 10000ms
            this.plannedDurationMs = Math.max(2000, Math.min(10000, plannedDurationMs));
            this.offsetMs = Math.max(0, offsetMs);
            this.validUntilMs = validUntilMs;
            this.issuedTimeMs = SimulationClock.currentTimeMillis();
        }

        public boolean isExpired() {
            return SimulationClock.currentTimeMillis() > validUntilMs;
        }

        public boolean isApplied() {
            return applied;
        }

        public void setApplied(boolean applied) {
            this.applied = applied;
        }

        @Override
        public String toString() {
            return "CoordinationPolicy[id=" + cmdId + ", type=" + policyType 
                    + ", axis=" + (targetAxis == 0 ? "NS" : "EW") 
                    + ", duration=" + plannedDurationMs + "ms, offset=" + offsetMs + "ms]";
        }
    }

    /** Interface de callback para notificar os agentes semáforo JADE */
    public interface EmergencyPhaseListener {
        void onRequestReceived(EmergencyRequest req);
        void onRequestAccepted(EmergencyRequest req);
        void onGreenGranted(EmergencyRequest req);
        void onEmergencyCleared(EmergencyRequest req);

        // Callbacks de Coordenação Multiagente (Fase 3 - Retrocompatíveis)
        default void onCoordinationApplied(CoordinationPolicy policy) {}
        default void onCoordinationRejected(CoordinationPolicy policy, String reason) {}
    }

    // Instâncias únicas por cruzamento (0=NW, 1=NE, 2=SW, 3=SE)
    private static final Map<Integer, IntersectionSafetyAuthority> AUTHORITIES = new HashMap<>();

    public static synchronized IntersectionSafetyAuthority getAuthority(int intersectionIndex) {
        if (!AUTHORITIES.containsKey(intersectionIndex)) {
            AUTHORITIES.put(intersectionIndex, new IntersectionSafetyAuthority(intersectionIndex));
        }
        return AUTHORITIES.get(intersectionIndex);
    }

    /** Reinicia todas as autoridades (útil para suites de testes) */
    public static synchronized void resetAll() {
        AUTHORITIES.clear();
    }

    // Identificação do cruzamento
    private final int intersectionIndex;

    // Estado atómico e seguro das fases
    private Phase currentPhase = Phase.NS_GREEN;
    private long phaseStartMillis;
    private Phase targetNextPhase = null; // Próxima fase planeada após All-Red

    // Temporizações de segurança (ms)
    private long yellowDuration = 1500;
    private long allRedDuration = 1000;
    private long currentGreenDuration = 4000;

    // Fila de emergências com resolução de conflitos
    private final Queue<EmergencyRequest> emergencyQueue = new ArrayDeque<>();
    private final Map<String, EmergencyRequest> activeRequestsByVehicle = new HashMap<>();
    private EmergencyRequest currentlyServingEmergency = null;
    private long emergencyMaxHoldUntil = 0;

    // Modo de Controlo e temporização fixa (Fase 5 - Etapa 2)
    private ControlMode controlMode = ControlMode.MULTIAGENT_COORDINATED;
    private long fixedGreenDuration = 4000L;

    // Políticas de Coordenação Global (Fase 3)
    private CoordinationPolicy activePolicy = null;
    private final java.util.Set<String> processedCmdIds = new java.util.HashSet<>();

    // Lista de listeners para despacho FIPA
    private final java.util.List<EmergencyPhaseListener> phaseListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Construtor da autoridade do cruzamento.
     */
    public IntersectionSafetyAuthority(int intersectionIndex) {
        this.intersectionIndex = intersectionIndex;
        this.phaseStartMillis = SimulationClock.currentTimeMillis();
        // Inicializa com segurança (cruzamentos pares começam em NS_GREEN, ímpares em EW_GREEN)
        if (intersectionIndex % 2 == 0) {
            this.currentPhase = Phase.NS_GREEN;
        } else {
            this.currentPhase = Phase.EW_GREEN;
        }
        applyPhaseAtomic(this.currentPhase);
    }

    public synchronized void setPhaseListener(EmergencyPhaseListener listener) {
        this.phaseListeners.clear();
        if (listener != null) {
            this.phaseListeners.add(listener);
        }
    }

    public synchronized void addPhaseListener(EmergencyPhaseListener listener) {
        if (listener != null && !this.phaseListeners.contains(listener)) {
            this.phaseListeners.add(listener);
        }
    }

    private void fireRequestReceived(EmergencyRequest req) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onRequestReceived(req); } catch (Throwable ignored) {}
        }
    }

    private void fireRequestAccepted(EmergencyRequest req) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onRequestAccepted(req); } catch (Throwable ignored) {}
        }
    }

    private void fireGreenGranted(EmergencyRequest req) {
        metrics.TrafficMetricsCollector.getInstance().recordEmergencyGreenGranted(req.vehicleId, intersectionIndex);
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onGreenGranted(req); } catch (Throwable ignored) {}
        }
    }

    private void fireEmergencyCleared(EmergencyRequest req) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onEmergencyCleared(req); } catch (Throwable ignored) {}
        }
    }

    private void fireCoordinationApplied(CoordinationPolicy policy) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onCoordinationApplied(policy); } catch (Throwable ignored) {}
        }
    }

    private void fireCoordinationRejected(CoordinationPolicy policy, String reason) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onCoordinationRejected(policy, reason); } catch (Throwable ignored) {}
        }
    }

    public synchronized int getIntersectionIndex() {
        return intersectionIndex;
    }

    public synchronized Phase getCurrentPhase() {
        return currentPhase;
    }

    public synchronized LightState getLightState(int axis) {
        return (axis == 0) ? currentPhase.nsState : currentPhase.ewState;
    }

    public synchronized void setYellowDuration(long yellowDuration) {
        this.yellowDuration = yellowDuration;
    }

    public synchronized void setAllRedDuration(long allRedDuration) {
        this.allRedDuration = allRedDuration;
    }

    public synchronized void setCurrentGreenDuration(long currentGreenDuration) {
        this.currentGreenDuration = currentGreenDuration;
    }

    public synchronized long getCurrentGreenDuration() {
        return currentGreenDuration;
    }

    public synchronized CoordinationPolicy getActivePolicy() {
        return activePolicy;
    }

    public synchronized ControlMode getControlMode() {
        return controlMode;
    }

    public synchronized void setControlMode(ControlMode controlMode) {
        this.controlMode = (controlMode != null) ? controlMode : ControlMode.LOCAL_ADAPTIVE;
    }

    public synchronized long getFixedGreenDuration() {
        return fixedGreenDuration;
    }

    public synchronized void setFixedGreenDuration(long fixedGreenDuration) {
        this.fixedGreenDuration = Math.max(2000L, Math.min(10000L, fixedGreenDuration));
    }

    /* =========================================================================
     * GESTÃO DE COORDENAÇÃO GLOBAL E ONDAS VERDES (Fase 3)
     * ========================================================================= */

    /**
     * Avalia e regista uma política de coordenação emitida pelo Agente Coordenador Central.
     *
     * Validações de segurança:
     * - Rejeição em Modo FIXED_TIME ou LOCAL_ADAPTIVE (isolamento experimental).
     * - Rejeição de comandos expirados (timeout).
     * - Idempotência / Anti-replay: rejeita comandos com cmdId já processado.
     * - Precedência de emergência: se existir socorro ativo no eixo incompatível, rejeita com REFUSE.
     * - Respeito por tempos mínimos e máximos legais de verde (2000ms a 10000ms).
     *
     * @param policy Política macro a avaliar
     * @return Código de resultado ("ACCEPTED", "EXPIRED", "DUPLICATE_COMMAND", "REJECTED_EMERGENCY_PRIORITY", "INVALID_AXIS", "REJECTED_FIXED_TIME_MODE", "REJECTED_LOCAL_ADAPTIVE_MODE")
     */
    public synchronized String evaluateAndRegisterCoordinationPolicy(CoordinationPolicy policy) {
        if (policy == null) return "INVALID_PAYLOAD";
        if (controlMode == ControlMode.FIXED_TIME) return "REJECTED_FIXED_TIME_MODE";
        if (controlMode == ControlMode.LOCAL_ADAPTIVE) return "REJECTED_LOCAL_ADAPTIVE_MODE";
        if (policy.isExpired()) return "EXPIRED";
        if (policy.targetAxis != 0 && policy.targetAxis != 1) return "INVALID_AXIS";

        // Idempotência
        if (processedCmdIds.contains(policy.cmdId)) {
            return "DUPLICATE_COMMAND";
        }

        // Precedência de emergência ativa em eixo conflitual
        if (currentlyServingEmergency != null && currentlyServingEmergency.axis != policy.targetAxis) {
            fireCoordinationRejected(policy, "REJECTED_EMERGENCY_PRIORITY");
            return "REJECTED_EMERGENCY_PRIORITY";
        }

        processedCmdIds.add(policy.cmdId);
        this.activePolicy = policy;
        metrics.TrafficMetricsCollector.getInstance().recordCoordinationAccepted(policy.cmdId, intersectionIndex);

        System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                + "] Diretiva de coordenação " + policy.cmdId + " (" + policy.policyType 
                + " | Eixo " + (policy.targetAxis == 0 ? "NS" : "EW") 
                + " | Duração " + policy.plannedDurationMs + "ms) ACEITE com sucesso.");

        // Se o eixo pretendido JÁ se encontra na fase verde, aplica imediatamente a extensão
        if ((policy.targetAxis == 0 && currentPhase == Phase.NS_GREEN) ||
            (policy.targetAxis == 1 && currentPhase == Phase.EW_GREEN)) {
            this.currentGreenDuration = policy.plannedDurationMs;
            policy.setApplied(true);
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Diretiva de coordenação " + policy.cmdId + " aplicada de imediato na fase verde ativa.");
            metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(policy.cmdId, intersectionIndex);
            fireCoordinationApplied(policy);
        }

        return "ACCEPTED";
    }

    /**
     * Cancela a política de coordenação ativa (ex: por sobreposição de emergência).
     */
    public synchronized void cancelCoordinationPolicy(String reason) {
        if (activePolicy != null) {
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Diretiva de coordenação " + activePolicy.cmdId + " cancelada. Motivo: " + reason);
            metrics.TrafficMetricsCollector.getInstance().recordCoordinationPreempted(activePolicy.cmdId, intersectionIndex, reason);
            fireCoordinationRejected(activePolicy, reason);
            activePolicy = null;
        }
    }

    /* =========================================================================
     * INVARIANTE ATÓMICO E APLICAÇÃO DE FASES
     * ========================================================================= */

    /**
     * Aplica uma nova fase de forma estritamente atómica, validando o invariante de segurança.
     *
     * @param newPhase Nova fase a aplicar
     * @throws IllegalStateException se os estados luminosos violarem a segurança
     */
    public synchronized void applyPhaseAtomic(Phase newPhase) {
        // Validação formal do invariante obrigatório
        assertSafetyInvariant(newPhase.nsState, newPhase.ewState);

        this.currentPhase = newPhase;
        this.phaseStartMillis = SimulationClock.currentTimeMillis();

        // Notifica o ambiente / interface gráfica
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.setTrafficLight(intersectionIndex, 0, newPhase.nsState);
            gui.setTrafficLight(intersectionIndex, 1, newPhase.ewState);
        }
    }

    /**
     * Validação rigorosa do invariante de tráfego.
     */
    public static void assertSafetyInvariant(LightState ns, LightState ew) {
        if (ns == LightState.GREEN && ew == LightState.GREEN) {
            throw new IllegalStateException("INVARIANTE VIOLADO: Verdes simultâneos incompatíveis no mesmo cruzamento! (NS=GREEN, EW=GREEN)");
        }
        if (ns == LightState.GREEN && ew == LightState.YELLOW) {
            throw new IllegalStateException("INVARIANTE VIOLADO: Verde e Amarelo simultâneos incompatíveis! (NS=GREEN, EW=YELLOW)");
        }
        if (ns == LightState.YELLOW && ew == LightState.GREEN) {
            throw new IllegalStateException("INVARIANTE VIOLADO: Amarelo e Verde simultâneos incompatíveis! (NS=YELLOW, EW=GREEN)");
        }
    }

    /* =========================================================================
     * GESTÃO DE EMERGÊNCIAS (Features 6, Confirmações FIPA e Fila de Conflito)
     * ========================================================================= */

    /**
     * Submete um pedido de emergência com garantia de idempotência e anti-debouncing.
     *
     * @param vehicleId Identificador da viatura de socorro
     * @param axis Eixo viário pretendido (0 = NS, 1 = EW)
     * @param senderAID AID do agente remetente para notificações
     * @return O pedido registado ou existente
     */
    public synchronized EmergencyRequest requestEmergency(String vehicleId, int axis, String senderAID) {
        long now = SimulationClock.currentTimeMillis();

        // 1. Idempotência / Anti-debouncing: se o mesmo veículo já tem pedido pendente ou ativo
        EmergencyRequest existing = activeRequestsByVehicle.get(vehicleId);
        if (existing != null && existing.getStatus() != EmergencyStatus.CONCLUIDO) {
            // Pedido repetido: não reinicia o ciclo nem duplica na fila; apenas confirma receção
            fireRequestReceived(existing);
            if (existing.getStatus() == EmergencyStatus.VERDE_CONCEDIDO) {
                fireGreenGranted(existing);
            } else if (existing.getStatus() == EmergencyStatus.ACEITE) {
                fireRequestAccepted(existing);
            }
            return existing;
        }

        // 2. Novo pedido: cria e regista
        EmergencyRequest newReq = new EmergencyRequest(vehicleId, axis, now, senderAID);
        activeRequestsByVehicle.put(vehicleId, newReq);
        emergencyQueue.add(newReq);
        metrics.TrafficMetricsCollector.getInstance().recordEmergencyRequest(vehicleId, intersectionIndex, axis);

        // Notifica confirmação 1: PEDIDO RECEBIDO
        fireRequestReceived(newReq);

        // 3. Avalia aceitação imediata
        newReq.setStatus(EmergencyStatus.ACEITE);
        metrics.TrafficMetricsCollector.getInstance().recordEmergencyAccepted(vehicleId, intersectionIndex);
        fireRequestAccepted(newReq);

        // Precedência Absoluta de Emergência: anula qualquer política de coordenação conflitual
        if (activePolicy != null && activePolicy.targetAxis != axis) {
            cancelCoordinationPolicy("PREEMPTED_BY_EMERGENCY_" + vehicleId);
        }

        // Processa a transição de segurança se não houver outra emergência em curso
        avaliaEFazProgressoEmergencia();

        return newReq;
    }

    /**
     * Notifica a autoridade de que a viatura de socorro transpôs o cruzamento com sucesso.
     *
     * @param vehicleId Identificador da viatura
     */
    public synchronized void notifyEmergencyCleared(String vehicleId) {
        metrics.TrafficMetricsCollector.getInstance().recordEmergencyCleared(vehicleId, intersectionIndex);
        if (currentlyServingEmergency != null && currentlyServingEmergency.vehicleId.equals(vehicleId)) {
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Viatura " + vehicleId + " transpôs o cruzamento. A concluir atendimento de socorro.");
            concluirEmergenciaAtual();
        } else {
            // Se estava na fila mas foi desimpedida
            EmergencyRequest req = activeRequestsByVehicle.get(vehicleId);
            if (req != null) {
                req.setStatus(EmergencyStatus.CONCLUIDO);
                emergencyQueue.remove(req);
            }
        }
    }

    /**
     * Conclui a emergência atual e prepara a transição para a próxima ou restauro adaptativo.
     */
    private synchronized void concluirEmergenciaAtual() {
        if (currentlyServingEmergency != null) {
            currentlyServingEmergency.setStatus(EmergencyStatus.CONCLUIDO);
            fireEmergencyCleared(currentlyServingEmergency);
            activeRequestsByVehicle.remove(currentlyServingEmergency.vehicleId);
            currentlyServingEmergency = null;
        }

        // Se houver outra emergência na fila (ex: no eixo oposto!), atende a próxima
        if (!emergencyQueue.isEmpty()) {
            avaliaEFazProgressoEmergencia();
        } else {
            // Restauro seguro do controlo adaptativo: não interrompe bruscamente;
            // mantém o verde atual durante um período residual seguro
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Fila de socorro vazia. A restaurar controlo adaptativo com segurança.");
            this.phaseStartMillis = SimulationClock.currentTimeMillis();
        }
    }

    /**
     * Avalia a fila de emergências e orienta as transições da máquina de estados.
     */
    private synchronized void avaliaEFazProgressoEmergencia() {
        if (currentlyServingEmergency == null && !emergencyQueue.isEmpty()) {
            currentlyServingEmergency = emergencyQueue.poll();
            emergencyMaxHoldUntil = SimulationClock.currentTimeMillis() + 8000; // Timeout de segurança máxima (8s)
        }

        if (currentlyServingEmergency == null) return;

        int reqAxis = currentlyServingEmergency.axis;

        // Se o eixo pretendido JÁ está verde:
        if ((reqAxis == 0 && currentPhase == Phase.NS_GREEN) || (reqAxis == 1 && currentPhase == Phase.EW_GREEN)) {
            if (currentlyServingEmergency.getStatus() != EmergencyStatus.VERDE_CONCEDIDO) {
                currentlyServingEmergency.setStatus(EmergencyStatus.VERDE_CONCEDIDO);
                fireGreenGranted(currentlyServingEmergency);
            }
            // Estende o verde para garantir passagem contínua da viatura
            this.phaseStartMillis = SimulationClock.currentTimeMillis();
            return;
        }

        // Se o eixo pretendido NÃO está verde, inicia a sequência segura de transição:
        // Se estiver verde no eixo oposto, transita imediatamente para amarelo desse eixo
        if (currentPhase == Phase.NS_GREEN && reqAxis == 1) {
            applyPhaseAtomic(Phase.NS_YELLOW);
            targetNextPhase = Phase.EW_GREEN;
        } else if (currentPhase == Phase.EW_GREEN && reqAxis == 0) {
            applyPhaseAtomic(Phase.EW_YELLOW);
            targetNextPhase = Phase.NS_GREEN;
        }
    }

    /* =========================================================================
     * CICLO PERIÓDICO / TICK DA MÁQUINA DE ESTADOS
     * ========================================================================= */

    /**
     * Executa um passo de relógio da máquina de estados.
     * Deve ser invocado periodicamente (ex: a cada 100ms a 500ms).
     *
     * @param waitingVehiclesNS Número de carros parados em NS (para adaptação)
     * @param waitingVehiclesEW Número de carros parados em EW (para adaptação)
     */
    public synchronized void updateTick(int waitingVehiclesNS, int waitingVehiclesEW) {
        long now = SimulationClock.currentTimeMillis();
        long elapsed = now - phaseStartMillis;

        // 1. Tratamento de timeout de segurança para emergência em curso
        if (currentlyServingEmergency != null) {
            if (now >= emergencyMaxHoldUntil) {
                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                        + "] Timeout de emergência atingido para " + currentlyServingEmergency.vehicleId + ". A libertar cruzamento.");
                concluirEmergenciaAtual();
                return;
            }
        }

        // 2. Máquina de Estados Segura
        switch (currentPhase) {
            case NS_GREEN:
                if (currentlyServingEmergency != null && currentlyServingEmergency.axis == 1) {
                    // Emergência aguarda no eixo EW: transita para amarelo
                    applyPhaseAtomic(Phase.NS_YELLOW);
                    targetNextPhase = Phase.EW_GREEN;
                } else if (currentlyServingEmergency == null && elapsed >= currentGreenDuration) {
                    // Ciclo adaptativo normal: fim do verde NS
                    applyPhaseAtomic(Phase.NS_YELLOW);
                    targetNextPhase = Phase.EW_GREEN;
                }
                break;

            case NS_YELLOW:
                if (elapsed >= yellowDuration) {
                    // Transição obrigatória para ALL_RED
                    applyPhaseAtomic(Phase.ALL_RED);
                }
                break;

            case ALL_RED:
                if (elapsed >= allRedDuration) {
                    // Determina qual o próximo verde com base em emergências ou planeamento
                    if (currentlyServingEmergency != null) {
                        if (currentlyServingEmergency.axis == 1) {
                            applyPhaseAtomic(Phase.EW_GREEN);
                            targetNextPhase = null;
                            if (currentlyServingEmergency.getStatus() != EmergencyStatus.VERDE_CONCEDIDO) {
                                currentlyServingEmergency.setStatus(EmergencyStatus.VERDE_CONCEDIDO);
                                fireGreenGranted(currentlyServingEmergency);
                            }
                        } else {
                            applyPhaseAtomic(Phase.NS_GREEN);
                            targetNextPhase = null;
                            if (currentlyServingEmergency.getStatus() != EmergencyStatus.VERDE_CONCEDIDO) {
                                currentlyServingEmergency.setStatus(EmergencyStatus.VERDE_CONCEDIDO);
                                fireGreenGranted(currentlyServingEmergency);
                            }
                        }
                    } else {
                        // Ciclo adaptativo normal ou coordenado (Fase 3 / Fase 5)
                        Phase next = (targetNextPhase != null) ? targetNextPhase : Phase.EW_GREEN;
                        targetNextPhase = null;

                        if (next == Phase.EW_GREEN) {
                            if (controlMode == ControlMode.FIXED_TIME) {
                                this.currentGreenDuration = fixedGreenDuration;
                            } else if (activePolicy != null && !activePolicy.isExpired() && activePolicy.targetAxis == 1) {
                                this.currentGreenDuration = activePolicy.plannedDurationMs;
                                activePolicy.setApplied(true);
                                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                                        + "] Diretiva de coordenação " + activePolicy.cmdId 
                                        + " aplicada com sucesso ao abrir fase EW_GREEN (" + currentGreenDuration + "ms).");
                                metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                                fireCoordinationApplied(activePolicy);
                                activePolicy = null;
                            } else {
                                this.currentGreenDuration = calcularTempoVerdeAdaptativo(waitingVehiclesEW);
                            }
                            applyPhaseAtomic(Phase.EW_GREEN);
                        } else {
                            if (controlMode == ControlMode.FIXED_TIME) {
                                this.currentGreenDuration = fixedGreenDuration;
                            } else if (activePolicy != null && !activePolicy.isExpired() && activePolicy.targetAxis == 0) {
                                this.currentGreenDuration = activePolicy.plannedDurationMs;
                                activePolicy.setApplied(true);
                                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                                        + "] Diretiva de coordenação " + activePolicy.cmdId 
                                        + " aplicada com sucesso ao abrir fase NS_GREEN (" + currentGreenDuration + "ms).");
                                metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                                fireCoordinationApplied(activePolicy);
                                activePolicy = null;
                            } else {
                                this.currentGreenDuration = calcularTempoVerdeAdaptativo(waitingVehiclesNS);
                            }
                            applyPhaseAtomic(Phase.NS_GREEN);
                        }
                    }
                }
                break;

            case EW_GREEN:
                if (currentlyServingEmergency != null && currentlyServingEmergency.axis == 0) {
                    // Emergência aguarda no eixo NS: transita para amarelo
                    applyPhaseAtomic(Phase.EW_YELLOW);
                    targetNextPhase = Phase.NS_GREEN;
                } else if (currentlyServingEmergency == null && elapsed >= currentGreenDuration) {
                    // Ciclo adaptativo normal: fim do verde EW
                    applyPhaseAtomic(Phase.EW_YELLOW);
                    targetNextPhase = Phase.NS_GREEN;
                }
                break;

            case EW_YELLOW:
                if (elapsed >= yellowDuration) {
                    // Transição obrigatória para ALL_RED
                    applyPhaseAtomic(Phase.ALL_RED);
                }
                break;
        }
    }

    /**
     * Calcula o tempo verde adaptativo com base na densidade de fila (Feature 7).
     */
    private long calcularTempoVerdeAdaptativo(int waitingVehicles) {
        long tempoBase = 3500;
        long extensaoPorCarro = 800;
        long tempoMaximo = 7500;
        return Math.min(tempoBase + (waitingVehicles * extensaoPorCarro), tempoMaximo);
    }
}
