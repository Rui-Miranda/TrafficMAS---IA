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

    /** Interface de callback para notificar os agentes semáforo JADE */
    public interface EmergencyPhaseListener {
        void onRequestReceived(EmergencyRequest req);
        void onRequestAccepted(EmergencyRequest req);
        void onGreenGranted(EmergencyRequest req);
        void onEmergencyCleared(EmergencyRequest req);
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

    // Listener para despacho FIPA
    private EmergencyPhaseListener phaseListener;

    /**
     * Construtor da autoridade do cruzamento.
     */
    public IntersectionSafetyAuthority(int intersectionIndex) {
        this.intersectionIndex = intersectionIndex;
        this.phaseStartMillis = System.currentTimeMillis();
        // Inicializa com segurança (cruzamentos pares começam em NS_GREEN, ímpares em EW_GREEN)
        if (intersectionIndex % 2 == 0) {
            this.currentPhase = Phase.NS_GREEN;
        } else {
            this.currentPhase = Phase.EW_GREEN;
        }
        applyPhaseAtomic(this.currentPhase);
    }

    public synchronized void setPhaseListener(EmergencyPhaseListener listener) {
        this.phaseListener = listener;
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
        this.phaseStartMillis = System.currentTimeMillis();

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
        long now = System.currentTimeMillis();

        // 1. Idempotência / Anti-debouncing: se o mesmo veículo já tem pedido pendente ou ativo
        EmergencyRequest existing = activeRequestsByVehicle.get(vehicleId);
        if (existing != null && existing.getStatus() != EmergencyStatus.CONCLUIDO) {
            // Pedido repetido: não reinicia o ciclo nem duplica na fila; apenas confirma receção
            if (phaseListener != null) {
                phaseListener.onRequestReceived(existing);
                if (existing.getStatus() == EmergencyStatus.VERDE_CONCEDIDO) {
                    phaseListener.onGreenGranted(existing);
                } else if (existing.getStatus() == EmergencyStatus.ACEITE) {
                    phaseListener.onRequestAccepted(existing);
                }
            }
            return existing;
        }

        // 2. Novo pedido: cria e regista
        EmergencyRequest newReq = new EmergencyRequest(vehicleId, axis, now, senderAID);
        activeRequestsByVehicle.put(vehicleId, newReq);
        emergencyQueue.add(newReq);

        // Notifica confirmação 1: PEDIDO RECEBIDO
        if (phaseListener != null) {
            phaseListener.onRequestReceived(newReq);
        }

        // 3. Avalia aceitação imediata
        newReq.setStatus(EmergencyStatus.ACEITE);
        if (phaseListener != null) {
            phaseListener.onRequestAccepted(newReq);
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
            if (phaseListener != null) {
                phaseListener.onEmergencyCleared(currentlyServingEmergency);
            }
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
            this.phaseStartMillis = System.currentTimeMillis();
        }
    }

    /**
     * Avalia a fila de emergências e orienta as transições da máquina de estados.
     */
    private synchronized void avaliaEFazProgressoEmergencia() {
        if (currentlyServingEmergency == null && !emergencyQueue.isEmpty()) {
            currentlyServingEmergency = emergencyQueue.poll();
            emergencyMaxHoldUntil = System.currentTimeMillis() + 8000; // Timeout de segurança máxima (8s)
        }

        if (currentlyServingEmergency == null) return;

        int reqAxis = currentlyServingEmergency.axis;

        // Se o eixo pretendido JÁ está verde:
        if ((reqAxis == 0 && currentPhase == Phase.NS_GREEN) || (reqAxis == 1 && currentPhase == Phase.EW_GREEN)) {
            if (currentlyServingEmergency.getStatus() != EmergencyStatus.VERDE_CONCEDIDO) {
                currentlyServingEmergency.setStatus(EmergencyStatus.VERDE_CONCEDIDO);
                if (phaseListener != null) {
                    phaseListener.onGreenGranted(currentlyServingEmergency);
                }
            }
            // Estende o verde para garantir passagem contínua da viatura
            this.phaseStartMillis = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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
                                if (phaseListener != null) {
                                    phaseListener.onGreenGranted(currentlyServingEmergency);
                                }
                            }
                        } else {
                            applyPhaseAtomic(Phase.NS_GREEN);
                            targetNextPhase = null;
                            if (currentlyServingEmergency.getStatus() != EmergencyStatus.VERDE_CONCEDIDO) {
                                currentlyServingEmergency.setStatus(EmergencyStatus.VERDE_CONCEDIDO);
                                if (phaseListener != null) {
                                    phaseListener.onGreenGranted(currentlyServingEmergency);
                                }
                            }
                        }
                    } else {
                        // Ciclo adaptativo normal
                        Phase next = (targetNextPhase != null) ? targetNextPhase : Phase.EW_GREEN;
                        targetNextPhase = null;

                        if (next == Phase.EW_GREEN) {
                            this.currentGreenDuration = calcularTempoVerdeAdaptativo(waitingVehiclesEW);
                            applyPhaseAtomic(Phase.EW_GREEN);
                        } else {
                            this.currentGreenDuration = calcularTempoVerdeAdaptativo(waitingVehiclesNS);
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
