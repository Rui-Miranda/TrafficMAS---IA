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

    /** Estrutura imutável de um pedido de emergência (Fase 6.3: Estendida com categoria, urgência e missão) */
    public static class EmergencyRequest {
        public final String vehicleId;
        public final int axis; // 0 = NS, 1 = EW
        public final long requestTime;
        public final String senderAID;
        public final String category; // AMBULANCE, FIRE_TRUCK, POLICE
        public final int urgencyLevel; // 1 a 5
        public final String missionId;
        public final EmergencyMission mission;
        private EmergencyStatus status;

        public EmergencyRequest(String vehicleId, int axis, long requestTime, String senderAID,
                                String category, int urgencyLevel, String missionId, EmergencyMission mission) {
            this.vehicleId = vehicleId;
            this.axis = axis;
            this.requestTime = requestTime;
            this.senderAID = senderAID;
            this.category = (category != null) ? category : "AMBULANCE";
            this.urgencyLevel = (urgencyLevel > 0) ? urgencyLevel : 3;
            this.missionId = missionId;
            this.mission = mission;
            this.status = EmergencyStatus.RECEBIDO;
        }

        public EmergencyRequest(String vehicleId, int axis, long requestTime, String senderAID) {
            this(vehicleId, axis, requestTime, senderAID, "AMBULANCE", 3, null, null);
        }

        public double calculateServicePriority(long nowMillis) {
            if (mission != null) {
                return mission.calculateServicePriority(nowMillis);
            }
            long waitSeconds = Math.max(0L, (nowMillis - requestTime) / 1000L);
            double starvationBonus = Math.min(50.0, waitSeconds * 2.5);
            return (this.urgencyLevel * 100.0) + starvationBonus;
        }

        public EmergencyStatus getStatus() {
            return status;
        }

        public void setStatus(EmergencyStatus status) {
            this.status = status;
        }

        @Override
        public String toString() {
            return "EmergencyRequest[id=" + vehicleId + ", cat=" + category + ", urg=" + urgencyLevel
                    + ", axis=" + (axis == 0 ? "NS" : "EW") + ", status=" + status + "]";
        }
    }

    /** Estados de ciclo de vida de uma Diretiva de Coordenação Semafórica (Fase 6.5-A) */
    public enum PolicyState {
        REQUESTED,
        AGREED,
        SCHEDULED,
        APPLIED,
        EXPIRED,
        REJECTED,
        PREEMPTED
    }

    /**
     * Diretiva de Coordenação Semafórica Global (Fase 3 / Fase 6.5-A).
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
        public final long referenceTimeMillis;
        public final long scheduledStartMillis; // referenceTimeMillis + offsetMs
        public final String corridorId;
        public final long validUntilMs;
        public final long issuedTimeMs;

        private volatile PolicyState policyState;
        private volatile boolean applied = false;
        private volatile long appliedTimeMillis = 0L;
        private volatile long temporalDeviationMs = 0L;
        private volatile String statusReason = "REQUESTED";

        public CoordinationPolicy(String cmdId, String policyType, int targetAxis,
                                  long plannedDurationMs, long offsetMs, long validUntilMs) {
            this(cmdId, policyType, targetAxis, plannedDurationMs, offsetMs,
                 SimulationClock.currentTimeMillis(), "CORRIDOR_GENERAL", validUntilMs);
        }

        public CoordinationPolicy(String cmdId, String policyType, int targetAxis,
                                  long plannedDurationMs, long offsetMs,
                                  long referenceTimeMillis, String corridorId, long validUntilMs) {
            this.cmdId = cmdId;
            this.policyType = policyType;
            this.targetAxis = targetAxis;
            // Limites de segurança rigorosos: mínimo 2000ms, máximo 10000ms
            this.plannedDurationMs = Math.max(2000, Math.min(10000, plannedDurationMs));
            this.offsetMs = Math.max(0, offsetMs);
            this.referenceTimeMillis = (referenceTimeMillis > 0) ? referenceTimeMillis : SimulationClock.currentTimeMillis();
            this.scheduledStartMillis = this.referenceTimeMillis + this.offsetMs;
            this.corridorId = (corridorId != null) ? corridorId : "CORRIDOR_GENERAL";
            this.validUntilMs = validUntilMs;
            this.issuedTimeMs = SimulationClock.currentTimeMillis();
            this.policyState = PolicyState.REQUESTED;
        }

        public PolicyState getPolicyState() {
            return policyState;
        }

        public long getReferenceTimeMillis() {
            return referenceTimeMillis;
        }

        public long getScheduledStartMillis() {
            return scheduledStartMillis;
        }

        public long getAppliedTimeMillis() {
            return appliedTimeMillis;
        }

        public long getTemporalDeviationMs() {
            return temporalDeviationMs;
        }

        public String getCorridorId() {
            return corridorId;
        }

        public String getStatusReason() {
            return statusReason;
        }

        public boolean isScheduled() {
            return policyState == PolicyState.SCHEDULED;
        }

        public boolean isExpired() {
            return policyState == PolicyState.EXPIRED || SimulationClock.currentTimeMillis() > validUntilMs;
        }

        public boolean isApplied() {
            return policyState == PolicyState.APPLIED || applied;
        }

        public synchronized void setApplied(boolean applied) {
            this.applied = applied;
            if (applied && this.policyState != PolicyState.APPLIED) {
                markApplied(SimulationClock.currentTimeMillis(), "SET_APPLIED_MANUAL");
            }
        }

        public synchronized void markAgreed(String reason) {
            this.policyState = PolicyState.AGREED;
            this.statusReason = reason;
        }

        public synchronized void markScheduled(String reason) {
            this.policyState = PolicyState.SCHEDULED;
            this.statusReason = reason;
        }

        public synchronized void markApplied(long nowMillis, String reason) {
            this.policyState = PolicyState.APPLIED;
            this.applied = true;
            this.appliedTimeMillis = nowMillis;
            this.temporalDeviationMs = nowMillis - scheduledStartMillis;
            this.statusReason = reason;
        }

        public synchronized void markRejected(String reason) {
            this.policyState = PolicyState.REJECTED;
            this.statusReason = reason;
        }

        public synchronized void markPreempted(String reason) {
            this.policyState = PolicyState.PREEMPTED;
            this.statusReason = reason;
        }

        public synchronized void markExpired(String reason) {
            this.policyState = PolicyState.EXPIRED;
            this.statusReason = reason;
        }

        @Override
        public String toString() {
            return "CoordinationPolicy[id=" + cmdId + ", type=" + policyType 
                    + ", axis=" + (targetAxis == 0 ? "NS" : "EW") 
                    + ", state=" + policyState
                    + ", duration=" + plannedDurationMs + "ms, offset=" + offsetMs + "ms"
                    + ", schedStart=" + scheduledStartMillis + ", appTime=" + appliedTimeMillis
                    + ", dev=" + temporalDeviationMs + "ms]";
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

        // Callbacks de Controlo Manual Seguro (Fase 6.2 - Retrocompatíveis)
        default void onManualControlStateChanged(ManualOverrideRequest req) {}
        default void onManualControlReturnedToAuto(int intersectionIndex, String reason) {}
    }

    /** Estados rastreáveis de um pedido de alteração manual de fase semafórica (Fase 6.2) */
    public enum ManualRequestState {
        REQUESTED,
        ACCEPTED,
        APPLIED,
        REJECTED,
        CANCELLED,
        PREEMPTED,
        EXPIRED
    }

    /**
     * Registo auditável de intervenção manual do operador sobre um cruzamento (Fase 6.2).
     * Distingue estritamente os estados REQUESTED, ACCEPTED e APPLIED.
     */
    public static class ManualOverrideRequest {
        public final String requestId;
        public final int intersectionIndex;
        public final int targetAxis; // 0 = NS, 1 = EW
        public final long requestedAtMs;
        public final long maxHoldDurationMs;

        private volatile ManualRequestState state;
        private volatile long acceptedAtMs = 0L;
        private volatile long appliedAtMs = 0L;
        private volatile long terminatedAtMs = 0L;
        private volatile boolean wasEverApplied = false;
        private volatile String statusReason = "REQUESTED";

        public ManualOverrideRequest(String requestId, int intersectionIndex, int targetAxis,
                                     long requestedAtMs, long maxHoldDurationMs) {
            this.requestId = requestId;
            this.intersectionIndex = intersectionIndex;
            this.targetAxis = targetAxis;
            this.requestedAtMs = requestedAtMs;
            this.maxHoldDurationMs = Math.max(2000L, Math.min(30000L, maxHoldDurationMs));
            this.state = ManualRequestState.REQUESTED;
        }

        public ManualRequestState getState() {
            return state;
        }

        public long getAcceptedAtMs() {
            return acceptedAtMs;
        }

        public long getAppliedAtMs() {
            return appliedAtMs;
        }

        public long getTerminatedAtMs() {
            return terminatedAtMs;
        }

        public boolean wasEverApplied() {
            return wasEverApplied;
        }

        public String getStatusReason() {
            return statusReason;
        }

        synchronized void markAccepted(long timestampMs, String reason) {
            this.state = ManualRequestState.ACCEPTED;
            this.acceptedAtMs = timestampMs;
            this.statusReason = reason;
        }

        synchronized void markApplied(long timestampMs, String reason) {
            this.state = ManualRequestState.APPLIED;
            if (this.acceptedAtMs == 0L) {
                this.acceptedAtMs = timestampMs;
            }
            this.appliedAtMs = timestampMs;
            this.wasEverApplied = true;
            this.statusReason = reason;
        }

        synchronized void markRejected(long timestampMs, String reason) {
            this.state = ManualRequestState.REJECTED;
            this.terminatedAtMs = timestampMs;
            this.statusReason = reason;
        }

        synchronized void markCancelled(long timestampMs, String reason) {
            this.state = ManualRequestState.CANCELLED;
            this.terminatedAtMs = timestampMs;
            this.statusReason = reason;
        }

        synchronized void markPreempted(long timestampMs, String reason) {
            this.state = ManualRequestState.PREEMPTED;
            this.terminatedAtMs = timestampMs;
            this.statusReason = reason;
        }

        synchronized void markExpired(long timestampMs, String reason) {
            this.state = ManualRequestState.EXPIRED;
            this.terminatedAtMs = timestampMs;
            this.statusReason = reason;
        }

        synchronized void recordOperatorAutoReturn(long timestampMs, String reason) {
            this.terminatedAtMs = timestampMs;
            this.statusReason = reason;
        }

        public String getTargetAxisName() {
            return (targetAxis == 0) ? "NS" : (targetAxis == 1 ? "EW" : "AUTO");
        }

        @Override
        public String toString() {
            return "ManualOverrideRequest[id=" + requestId
                    + ", C" + (intersectionIndex + 1)
                    + ", axis=" + (targetAxis == 0 ? "NS" : "EW")
                    + ", state=" + state
                    + ", reqAt=" + requestedAtMs
                    + ", accAt=" + acceptedAtMs
                    + ", appAt=" + appliedAtMs
                    + ", reason=" + statusReason + "]";
        }
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
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.resetLights();
        }
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

    // Políticas de Coordenação Global (Fase 3 / Fase 6.5-A)
    private CoordinationPolicy activePolicy = null;
    private CoordinationPolicy lastAppliedPolicy = null;
    private final java.util.Set<String> processedCmdIds = new java.util.HashSet<>();

    // Controlo Manual Seguro por Cruzamento (Fase 6.2)
    public static final long DEFAULT_MANUAL_HOLD_DURATION_MS = 12000L;
    private static final java.util.concurrent.atomic.AtomicLong MANUAL_SEQ =
            new java.util.concurrent.atomic.AtomicLong(2000L);
    private ManualOverrideRequest activeManualRequest = null;
    private ManualOverrideRequest lastManualRequest = null;
    private final java.util.List<ManualOverrideRequest> manualRequestHistory =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean manualModeActive = false;
    private long manualHoldUntilMs = 0L;
    private long minManualSwitchGreenMs = 1500L;

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

    private void fireManualControlStateChanged(ManualOverrideRequest req) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onManualControlStateChanged(req); } catch (Throwable ignored) {}
        }
    }

    private void fireManualControlReturnedToAuto(String reason) {
        for (EmergencyPhaseListener l : phaseListeners) {
            try { l.onManualControlReturnedToAuto(intersectionIndex, reason); } catch (Throwable ignored) {}
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

    public synchronized EmergencyRequest getCurrentlyServingEmergency() {
        return currentlyServingEmergency;
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

    public synchronized CoordinationPolicy getLastAppliedPolicy() {
        return lastAppliedPolicy;
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
     * CONTROLO MANUAL SEGURO DE SEMÁFOROS POR CLIQUE (Fase 6.2 — R5)
     * ========================================================================= */

    public synchronized boolean isManualModeActive() {
        return manualModeActive && activeManualRequest != null;
    }

    public synchronized boolean isEmergencyActive() {
        return currentlyServingEmergency != null || !emergencyQueue.isEmpty();
    }

    public synchronized EmergencyRequest getActiveEmergency() {
        return currentlyServingEmergency != null ? currentlyServingEmergency : emergencyQueue.peek();
    }

    public synchronized ManualOverrideRequest getActiveManualRequest() {
        return activeManualRequest;
    }

    public synchronized ManualOverrideRequest getLastManualRequest() {
        return lastManualRequest;
    }

    public synchronized java.util.List<ManualOverrideRequest> getManualRequestHistory() {
        return new java.util.ArrayList<>(manualRequestHistory);
    }

    public synchronized void setMinManualSwitchGreenMs(long minManualSwitchGreenMs) {
        this.minManualSwitchGreenMs = Math.max(0L, Math.min(5000L, minManualSwitchGreenMs));
    }

    public synchronized long getMinManualSwitchGreenMs() {
        return minManualSwitchGreenMs;
    }

    /**
     * Submete um pedido de alteração manual de fase semafórica para o eixo indicado (0=NS, 1=EW)
     * com a duração máxima padrão de manutenção manual.
     */
    public synchronized ManualOverrideRequest requestManualPhase(int targetAxis) {
        return requestManualPhase(targetAxis, DEFAULT_MANUAL_HOLD_DURATION_MS);
    }

    /**
     * Submete um pedido de alteração manual de fase semafórica garantindo:
     * 1. Precedência absoluta de veículos de emergência ativos (rejeita se existir socorro ativo).
     * 2. Transição obrigatória pelo AMARELO (1500ms) e ALL_RED (1000ms) quando muda de eixo.
     * 3. Separação estrita entre o estado ACCEPTED (pedido validado em transição) e APPLIED (verde efetivado).
     * 4. Imunidade a cliques rápidos e concorrentes (nunca salta YELLOW nem ALL_RED).
     *
     * @param targetAxis Eixo solicitado pelo operador (0 = Norte-Sul, 1 = Este-Oeste)
     * @param holdDurationMs Duração máxima do verde manual antes de retorno automático (ms)
     * @return Objeto {@link ManualOverrideRequest} rastreável com ID, timestamps e estado
     */
    public synchronized ManualOverrideRequest requestManualPhase(int targetAxis, long holdDurationMs) {
        long now = SimulationClock.currentTimeMillis();
        long seq = MANUAL_SEQ.incrementAndGet();
        String axisLabel = (targetAxis == 0) ? "NS" : (targetAxis == 1 ? "EW" : "INV");
        String reqId = "MAN_C" + (intersectionIndex + 1) + "_" + axisLabel + "_" + seq;

        ManualOverrideRequest req = new ManualOverrideRequest(
                reqId, intersectionIndex, targetAxis, now, holdDurationMs);
        this.lastManualRequest = req;
        this.manualRequestHistory.add(req);
        fireManualControlStateChanged(req); // Estado inicial: REQUESTED

        if (targetAxis != 0 && targetAxis != 1) {
            req.markRejected(now, "REJECTED_INVALID_AXIS");
            fireManualControlStateChanged(req);
            return req;
        }

        // 1. Precedência Absoluta de Emergência Ativa (Secção 5)
        if (currentlyServingEmergency != null || !emergencyQueue.isEmpty()) {
            String emId = (currentlyServingEmergency != null)
                    ? currentlyServingEmergency.vehicleId
                    : emergencyQueue.peek().vehicleId;
            String reason = "REJECTED_EMERGENCY_PRIORITY (Emergência ativa: " + emId + ")";
            req.markRejected(now, reason);
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                    + "] Pedido manual " + reqId + " REJEITADO: " + reason);
            fireManualControlStateChanged(req);
            return req;
        }

        // 2. Gestão de pedidos manuais anteriores / cliques repetidos e concorrentes
        if (activeManualRequest != null) {
            if (activeManualRequest.getState() == ManualRequestState.ACCEPTED) {
                if (activeManualRequest.targetAxis == targetAxis) {
                    // Clique repetido no mesmo eixo enquanto a transição YELLOW/ALL_RED está em curso:
                    // Cancela a referência anterior por substituição idempotente, mas NUNCA reinicia nem salta fases!
                    activeManualRequest.markCancelled(now, "SUPERSEDED_BY_REPEAT_CLICK_" + reqId);
                    fireManualControlStateChanged(activeManualRequest);

                    req.markAccepted(now, "ACCEPTED_CONTINUING_SAFE_TRANSITION");
                    this.activeManualRequest = req;
                    this.manualModeActive = true;
                    fireManualControlStateChanged(req);
                    return req;
                } else {
                    // Clique concorrente para o eixo oposto antes da aplicação:
                    activeManualRequest.markCancelled(now, "SUPERSEDED_BY_OPPOSITE_REQUEST_" + reqId);
                    fireManualControlStateChanged(activeManualRequest);
                }
            } else if (activeManualRequest.getState() == ManualRequestState.APPLIED) {
                if (activeManualRequest.targetAxis == targetAxis
                        && ((targetAxis == 0 && currentPhase == Phase.NS_GREEN)
                        || (targetAxis == 1 && currentPhase == Phase.EW_GREEN))) {
                    // Já aplicado no eixo pedido: renova o hold manual de forma segura
                    activeManualRequest.markCancelled(now, "RENEWED_BY_" + reqId);
                    fireManualControlStateChanged(activeManualRequest);
                } else {
                    activeManualRequest.recordOperatorAutoReturn(now, "SWITCHED_TO_" + reqId);
                }
            }
        }

        // 3. Suspende qualquer política de coordenação automática concorrente
        if (activePolicy != null) {
            cancelCoordinationPolicy("SUSPENDED_BY_MANUAL_CONTROL_" + reqId);
        }

        // 4. Aceita o pedido manual (ACCEPTED != APPLIED)
        req.markAccepted(now, "ACCEPTED_PENDING_SAFE_PHASE");
        this.activeManualRequest = req;
        this.manualModeActive = true;
        fireManualControlStateChanged(req);

        // 5. Verifica se o cruzamento já está na fase verde do eixo pretendido
        if ((targetAxis == 0 && currentPhase == Phase.NS_GREEN)
                || (targetAxis == 1 && currentPhase == Phase.EW_GREEN)) {
            this.currentGreenDuration = req.maxHoldDurationMs;
            this.manualHoldUntilMs = now + req.maxHoldDurationMs;
            this.phaseStartMillis = now;
            req.markApplied(now, "APPLIED_IMMEDIATELY_ON_ACTIVE_GREEN");
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                    + "] Controlo manual " + reqId + " ACEITE e APLICADO (eixo " + axisLabel + " já estava em verde).");
            fireManualControlStateChanged(req);
            return req;
        }

        // 6. Se estiver em verde no eixo oposto, agenda ou inicia a transição segura obrigatória:
        //    GREEN -> YELLOW (1500ms) -> ALL_RED (1000ms) -> GREEN do eixo solicitado
        long elapsedInCurrentPhase = now - phaseStartMillis;
        if (currentPhase == Phase.NS_GREEN && targetAxis == 1) {
            targetNextPhase = Phase.EW_GREEN;
            if (elapsedInCurrentPhase >= minManualSwitchGreenMs) {
                applyPhaseAtomic(Phase.NS_YELLOW);
                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                        + "] Pedido manual " + reqId + " ACEITE. Transição segura iniciada: NS_GREEN -> NS_YELLOW -> ALL_RED -> EW_GREEN.");
            } else {
                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                        + "] Pedido manual " + reqId + " ACEITE. A aguardar verde mínimo ("
                        + (minManualSwitchGreenMs - elapsedInCurrentPhase) + "ms) antes de NS_YELLOW.");
            }
        } else if (currentPhase == Phase.EW_GREEN && targetAxis == 0) {
            targetNextPhase = Phase.NS_GREEN;
            if (elapsedInCurrentPhase >= minManualSwitchGreenMs) {
                applyPhaseAtomic(Phase.EW_YELLOW);
                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                        + "] Pedido manual " + reqId + " ACEITE. Transição segura iniciada: EW_GREEN -> EW_YELLOW -> ALL_RED -> NS_GREEN.");
            } else {
                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                        + "] Pedido manual " + reqId + " ACEITE. A aguardar verde mínimo ("
                        + (minManualSwitchGreenMs - elapsedInCurrentPhase) + "ms) antes de EW_YELLOW.");
            }
        } else {
            // Já se encontra em NS_YELLOW, EW_YELLOW ou ALL_RED: preserva integralmente a transição de segurança!
            targetNextPhase = (targetAxis == 0) ? Phase.NS_GREEN : Phase.EW_GREEN;
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                    + "] Pedido manual " + reqId + " ACEITE durante transição (" + currentPhase
                    + "). Próxima fase segura agendada: " + targetNextPhase + ".");
        }

        return req;
    }

    /**
     * Restitui o cruzamento ao controlo automático normal por comando do operador.
     * Se existia um pedido manual pendente (ainda não aplicado), marca-o como CANCELLED.
     */
    public synchronized ManualOverrideRequest returnToAutomaticMode() {
        long now = SimulationClock.currentTimeMillis();
        ManualOverrideRequest prev = this.activeManualRequest;
        this.manualModeActive = false;
        this.manualHoldUntilMs = 0L;
        if (prev != null) {
            if (prev.getState() == ManualRequestState.ACCEPTED) {
                prev.markCancelled(now, "CANCELLED_BY_OPERATOR_AUTO_MODE");
                fireManualControlStateChanged(prev);
            } else if (prev.getState() == ManualRequestState.APPLIED) {
                prev.markExpired(now, "RETURNED_TO_AUTO_BY_OPERATOR");
                fireManualControlStateChanged(prev);
            }
            this.activeManualRequest = null;
        }
        System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                + "] Controlo manual desativado. Cruzamento restituído ao MODO AUTOMÁTICO.");
        fireManualControlReturnedToAuto("OPERATOR_REQUESTED_AUTO");
        return prev;
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
        if (controlMode == ControlMode.FIXED_TIME) {
            policy.markRejected("REJECTED_FIXED_TIME_MODE");
            return "REJECTED_FIXED_TIME_MODE";
        }
        if (controlMode == ControlMode.LOCAL_ADAPTIVE) {
            policy.markRejected("REJECTED_LOCAL_ADAPTIVE_MODE");
            return "REJECTED_LOCAL_ADAPTIVE_MODE";
        }
        if (policy.isExpired()) {
            policy.markExpired("EXPIRED");
            return "EXPIRED";
        }
        if (policy.targetAxis != 0 && policy.targetAxis != 1) {
            policy.markRejected("INVALID_AXIS");
            return "INVALID_AXIS";
        }

        // Idempotência
        if (processedCmdIds.contains(policy.cmdId)) {
            policy.markRejected("DUPLICATE_COMMAND");
            return "DUPLICATE_COMMAND";
        }

        // Precedência de emergência ativa em eixo conflitual
        if ((currentlyServingEmergency != null && currentlyServingEmergency.axis != policy.targetAxis)
                || (!emergencyQueue.isEmpty() && emergencyQueue.peek().axis != policy.targetAxis)) {
            policy.markRejected("REJECTED_EMERGENCY_PRIORITY");
            fireCoordinationRejected(policy, "REJECTED_EMERGENCY_PRIORITY");
            return "REJECTED_EMERGENCY_PRIORITY";
        }

        // Precedência de controlo manual ativo em eixo conflitual
        if (isManualModeActive() && activeManualRequest.targetAxis != policy.targetAxis) {
            policy.markRejected("REJECTED_MANUAL_CONTROL_ACTIVE");
            fireCoordinationRejected(policy, "REJECTED_MANUAL_CONTROL_ACTIVE");
            return "REJECTED_MANUAL_CONTROL_ACTIVE";
        }

        processedCmdIds.add(policy.cmdId);
        policy.markScheduled("ACCEPTED_AND_SCHEDULED");
        this.activePolicy = policy;
        metrics.TrafficMetricsCollector.getInstance().recordCoordinationAccepted(policy.cmdId, intersectionIndex);

        System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                + "] Diretiva de coordenação " + policy.cmdId + " (" + policy.policyType 
                + " | Eixo " + (policy.targetAxis == 0 ? "NS" : "EW") 
                + " | Duração " + policy.plannedDurationMs + "ms | Sched=" + policy.scheduledStartMillis + ") ACEITE e AGENDADA.");

        long now = SimulationClock.currentTimeMillis();
        // Se o eixo pretendido JÁ se encontra na fase verde E já atingiu o instante de agendamento (ou offset == 0)
        if (((policy.targetAxis == 0 && currentPhase == Phase.NS_GREEN) ||
             (policy.targetAxis == 1 && currentPhase == Phase.EW_GREEN)) &&
            now >= policy.scheduledStartMillis) {
            this.currentGreenDuration = policy.plannedDurationMs;
            policy.markApplied(now, "APPLIED_IMMEDIATELY_ON_ACTIVE_GREEN");
            this.lastAppliedPolicy = policy;
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Diretiva de coordenação " + policy.cmdId + " aplicada de imediato na fase verde ativa.");
            metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(policy.cmdId, intersectionIndex);
            fireCoordinationApplied(policy);
        }

        return "ACCEPTED";
    }

    /**
     * Cancela a política de coordenação ativa (ex: por sobreposição de emergência ou controlo manual).
     */
    public synchronized void cancelCoordinationPolicy(String reason) {
        if (activePolicy != null) {
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                    + "] Diretiva de coordenação " + activePolicy.cmdId + " cancelada/preemptada. Motivo: " + reason);
            metrics.TrafficMetricsCollector.getInstance().recordCoordinationPreempted(activePolicy.cmdId, intersectionIndex, reason);
            activePolicy.markPreempted(reason);
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

        // Notifica o ambiente / interface gráfica de forma atómica
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.setTrafficLights(intersectionIndex, newPhase.nsState, newPhase.ewState);
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
    /**
     * Submete um pedido de emergência com garantia de idempotência, prioridade por missão e anti-debouncing.
     */
    public synchronized EmergencyRequest requestEmergency(String vehicleId, int axis, String senderAID,
                                                          String category, int urgencyLevel,
                                                          String missionId, EmergencyMission mission) {
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

        // 2. Novo pedido: cria e regista com metadados de missão e urgência
        EmergencyRequest newReq = new EmergencyRequest(vehicleId, axis, now, senderAID, category, urgencyLevel, missionId, mission);
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

        // Precedência Absoluta de Emergência sobre Controlo Manual (Fase 6.2 — Secção 5):
        // Revoga/preempta qualquer pedido manual pendente (ACCEPTED) ou ativo (APPLIED).
        if (activeManualRequest != null) {
            String manualPreemptReason = "PREEMPTED_BY_EMERGENCY_" + vehicleId;
            activeManualRequest.markPreempted(now, manualPreemptReason);
            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                    + "] Controlo manual " + activeManualRequest.requestId
                    + " PREEMPTADO por emergência ativa (" + vehicleId + ").");
            fireManualControlStateChanged(activeManualRequest);
            this.activeManualRequest = null;
            this.manualModeActive = false;
            this.manualHoldUntilMs = 0L;
            fireManualControlReturnedToAuto(manualPreemptReason);
        }

        // Processa a transição de segurança se não houver outra emergência em curso
        avaliaEFazProgressoEmergencia();

        return newReq;
    }

    public synchronized EmergencyRequest requestEmergency(String vehicleId, int axis, String senderAID) {
        return requestEmergency(vehicleId, axis, senderAID, "AMBULANCE", 3, null, null);
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
     * Implementa arbitragem dinâmica por prioridade de serviço (urgência da missão + anti-inanição).
     */
    private synchronized void avaliaEFazProgressoEmergencia() {
        long now = SimulationClock.currentTimeMillis();

        // Se a emergência selecionada ainda está apenas em ACEITE (ainda não concedeu verde nem atravessou)
        // e existe na fila uma emergência de maior urgência/prioridade, reavalia para servir a mais urgente
        if (currentlyServingEmergency != null && currentlyServingEmergency.getStatus() == EmergencyStatus.ACEITE && !emergencyQueue.isEmpty()) {
            EmergencyRequest bestQueued = null;
            double curPrio = currentlyServingEmergency.calculateServicePriority(now);
            double bestQueuedPrio = curPrio;
            for (EmergencyRequest r : emergencyQueue) {
                double rPrio = r.calculateServicePriority(now);
                if (rPrio > bestQueuedPrio) {
                    bestQueuedPrio = rPrio;
                    bestQueued = r;
                }
            }
            if (bestQueued != null) {
                emergencyQueue.remove(bestQueued);
                emergencyQueue.add(currentlyServingEmergency);
                currentlyServingEmergency = bestQueued;
                emergencyMaxHoldUntil = now + 8000;
            }
        }

        if (currentlyServingEmergency == null && !emergencyQueue.isEmpty()) {
            if (emergencyQueue.size() == 1) {
                currentlyServingEmergency = emergencyQueue.poll();
            } else {
                EmergencyRequest best = null;
                for (EmergencyRequest r : emergencyQueue) {
                    if (best == null) {
                        best = r;
                    } else {
                        double pBest = best.calculateServicePriority(now);
                        double pCurr = r.calculateServicePriority(now);
                        if (pCurr > pBest) {
                            best = r;
                        } else if (Math.abs(pCurr - pBest) < 0.001) {
                            if (r.requestTime < best.requestTime) {
                                best = r;
                            } else if (r.requestTime == best.requestTime && r.vehicleId.compareTo(best.vehicleId) < 0) {
                                best = r;
                            }
                        }
                    }
                }
                emergencyQueue.remove(best);
                currentlyServingEmergency = best;
            }
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

        // 1.5. Agendamento e Antecipação Física de Políticas de Coordenação (Fase 6.5-A)
        if (activePolicy != null && currentlyServingEmergency == null && activeManualRequest == null) {
            if (activePolicy.isExpired()) {
                activePolicy.markExpired("EXPIRED_BEFORE_APPLICATION");
                fireCoordinationRejected(activePolicy, "EXPIRED");
                activePolicy = null;
            } else if (activePolicy.getPolicyState() == PolicyState.SCHEDULED) {
                long clearanceTimeMs = yellowDuration + allRedDuration;
                long prepTimeMs = activePolicy.scheduledStartMillis - clearanceTimeMs;

                // Se estamos no eixo oposto em verde e já atingimos o instante de preparação para o verde agendado
                if (now >= prepTimeMs) {
                    if (currentPhase == Phase.NS_GREEN && activePolicy.targetAxis == 1) {
                        if (elapsed >= 2000L) { // Respeita verde mínimo legal
                            applyPhaseAtomic(Phase.NS_YELLOW);
                            targetNextPhase = Phase.EW_GREEN;
                            return;
                        }
                    } else if (currentPhase == Phase.EW_GREEN && activePolicy.targetAxis == 0) {
                        if (elapsed >= 2000L) { // Respeita verde mínimo legal
                            applyPhaseAtomic(Phase.EW_YELLOW);
                            targetNextPhase = Phase.NS_GREEN;
                            return;
                        }
                    }
                }

                // Se já estamos no eixo verde pretendido e atingimos o instante agendado
                if (now >= activePolicy.scheduledStartMillis) {
                    if (activePolicy.targetAxis == 0 && currentPhase == Phase.NS_GREEN) {
                        activePolicy.markApplied(now, "APPLIED_ON_TARGET_GREEN");
                        this.currentGreenDuration = activePolicy.plannedDurationMs;
                        this.lastAppliedPolicy = activePolicy;
                        metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                        fireCoordinationApplied(activePolicy);
                    } else if (activePolicy.targetAxis == 1 && currentPhase == Phase.EW_GREEN) {
                        activePolicy.markApplied(now, "APPLIED_ON_TARGET_GREEN");
                        this.currentGreenDuration = activePolicy.plannedDurationMs;
                        this.lastAppliedPolicy = activePolicy;
                        metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                        fireCoordinationApplied(activePolicy);
                    }
                }
            }
        }

        // 2. Máquina de Estados Segura
        switch (currentPhase) {
            case NS_GREEN:
                if (currentlyServingEmergency != null && currentlyServingEmergency.axis == 1) {
                    // Emergência aguarda no eixo EW: transita para amarelo
                    applyPhaseAtomic(Phase.NS_YELLOW);
                    targetNextPhase = Phase.EW_GREEN;
                } else if (currentlyServingEmergency == null && activeManualRequest != null) {
                    if (activeManualRequest.getState() == ManualRequestState.ACCEPTED) {
                        if (activeManualRequest.targetAxis == 1) {
                            if (elapsed >= minManualSwitchGreenMs) {
                                applyPhaseAtomic(Phase.NS_YELLOW);
                                targetNextPhase = Phase.EW_GREEN;
                            }
                        } else {
                            this.currentGreenDuration = activeManualRequest.maxHoldDurationMs;
                            this.manualHoldUntilMs = now + activeManualRequest.maxHoldDurationMs;
                            activeManualRequest.markApplied(now, "APPLIED_ON_NS_GREEN");
                            fireManualControlStateChanged(activeManualRequest);
                        }
                    } else if (activeManualRequest.getState() == ManualRequestState.APPLIED) {
                        if (now >= manualHoldUntilMs) {
                            activeManualRequest.markExpired(now, "EXPIRED_MAX_HOLD_AUTO_RETURN");
                            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                                     + "] Controlo manual " + activeManualRequest.requestId
                                    + " expirou. A regressar ao MODO AUTOMÁTICO.");
                            fireManualControlStateChanged(activeManualRequest);
                            this.activeManualRequest = null;
                            this.manualModeActive = false;
                            this.manualHoldUntilMs = 0L;
                            fireManualControlReturnedToAuto("EXPIRED_MAX_HOLD");
                            applyPhaseAtomic(Phase.NS_YELLOW);
                            targetNextPhase = Phase.EW_GREEN;
                        }
                    }
                } else if (currentlyServingEmergency == null && elapsed >= currentGreenDuration) {
                    if (activePolicy != null && activePolicy.isApplied()) {
                        this.activePolicy = null; // Liberta a diretiva concluída prevenindo inanição transversal
                    }
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
                    // Determina qual o próximo verde com base em emergências, controlo manual ou planeamento
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
                    } else if (activeManualRequest != null && activeManualRequest.getState() == ManualRequestState.ACCEPTED) {
                        Phase nextManual = (activeManualRequest.targetAxis == 0) ? Phase.NS_GREEN : Phase.EW_GREEN;
                        targetNextPhase = null;
                        this.currentGreenDuration = activeManualRequest.maxHoldDurationMs;
                        this.manualHoldUntilMs = now + activeManualRequest.maxHoldDurationMs;
                        applyPhaseAtomic(nextManual);
                        activeManualRequest.markApplied(now, "APPLIED_AFTER_SAFE_YELLOW_AND_ALL_RED");
                        System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                                + "] >>> CONTROLO MANUAL APLICADO (" + activeManualRequest.requestId
                                + "): Fase " + nextManual + " efetivada após transição segura YELLOW -> ALL_RED.");
                        fireManualControlStateChanged(activeManualRequest);
                    } else {
                        // Ciclo adaptativo normal ou coordenado (Fase 3 / Fase 5 / Fase 6.5-A)
                        Phase next = targetNextPhase;
                        if (next == null && activePolicy != null && activePolicy.getPolicyState() == PolicyState.SCHEDULED) {
                            next = (activePolicy.targetAxis == 0) ? Phase.NS_GREEN : Phase.EW_GREEN;
                        }
                        if (next == null) {
                            next = Phase.EW_GREEN;
                        }
                        targetNextPhase = null;

                        if (next == Phase.EW_GREEN) {
                            if (controlMode == ControlMode.FIXED_TIME) {
                                this.currentGreenDuration = fixedGreenDuration;
                            } else if (activePolicy != null && !activePolicy.isExpired() && activePolicy.targetAxis == 1) {
                                this.currentGreenDuration = activePolicy.plannedDurationMs;
                                activePolicy.markApplied(now, "APPLIED_AFTER_SAFE_YELLOW_AND_ALL_RED");
                                this.lastAppliedPolicy = activePolicy;
                                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                                        + "] Diretiva de coordenação " + activePolicy.cmdId 
                                        + " aplicada com sucesso ao abrir fase EW_GREEN (" + currentGreenDuration + "ms, dev="
                                        + activePolicy.getTemporalDeviationMs() + "ms).");
                                metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                                fireCoordinationApplied(activePolicy);
                            } else {
                                this.currentGreenDuration = calcularTempoVerdeAdaptativo(waitingVehiclesEW);
                            }
                            applyPhaseAtomic(Phase.EW_GREEN);
                        } else {
                            if (controlMode == ControlMode.FIXED_TIME) {
                                this.currentGreenDuration = fixedGreenDuration;
                            } else if (activePolicy != null && !activePolicy.isExpired() && activePolicy.targetAxis == 0) {
                                this.currentGreenDuration = activePolicy.plannedDurationMs;
                                activePolicy.markApplied(now, "APPLIED_AFTER_SAFE_YELLOW_AND_ALL_RED");
                                this.lastAppliedPolicy = activePolicy;
                                System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1) 
                                        + "] Diretiva de coordenação " + activePolicy.cmdId 
                                        + " aplicada com sucesso ao abrir fase NS_GREEN (" + currentGreenDuration + "ms, dev="
                                        + activePolicy.getTemporalDeviationMs() + "ms).");
                                metrics.TrafficMetricsCollector.getInstance().recordCoordinationApplied(activePolicy.cmdId, intersectionIndex);
                                fireCoordinationApplied(activePolicy);
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
                } else if (currentlyServingEmergency == null && activeManualRequest != null) {
                    if (activeManualRequest.getState() == ManualRequestState.ACCEPTED) {
                        if (activeManualRequest.targetAxis == 0) {
                            if (elapsed >= minManualSwitchGreenMs) {
                                applyPhaseAtomic(Phase.EW_YELLOW);
                                targetNextPhase = Phase.NS_GREEN;
                            }
                        } else {
                            this.currentGreenDuration = activeManualRequest.maxHoldDurationMs;
                            this.manualHoldUntilMs = now + activeManualRequest.maxHoldDurationMs;
                            activeManualRequest.markApplied(now, "APPLIED_ON_EW_GREEN");
                            fireManualControlStateChanged(activeManualRequest);
                        }
                    } else if (activeManualRequest.getState() == ManualRequestState.APPLIED) {
                        if (now >= manualHoldUntilMs) {
                            activeManualRequest.markExpired(now, "EXPIRED_MAX_HOLD_AUTO_RETURN");
                            System.out.println("[IntersectionSafetyAuthority C" + (intersectionIndex + 1)
                                    + "] Controlo manual " + activeManualRequest.requestId
                                    + " expirou. A regressar ao MODO AUTOMÁTICO.");
                            fireManualControlStateChanged(activeManualRequest);
                            this.activeManualRequest = null;
                            this.manualModeActive = false;
                            this.manualHoldUntilMs = 0L;
                            fireManualControlReturnedToAuto("EXPIRED_MAX_HOLD");
                            applyPhaseAtomic(Phase.EW_YELLOW);
                            targetNextPhase = Phase.NS_GREEN;
                        }
                    }
                } else if (currentlyServingEmergency == null && elapsed >= currentGreenDuration) {
                    if (activePolicy != null && activePolicy.isApplied()) {
                        this.activePolicy = null; // Liberta a diretiva concluída prevenindo inanição transversal
                    }
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
