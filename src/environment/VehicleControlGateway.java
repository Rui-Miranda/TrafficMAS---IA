package environment;

import jade.core.Agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway de Controlo e Mediação entre os VehicleAgents JADE e o Motor Físico (Fase 6.2-D).
 *
 * Princípios Arquiteturais:
 * 1. Desacoplamento Estrito: O motor físico fornece perceções sensoriais; o agente JADE
 *    delibera e formula decisões autónomas (A*, velocidade, faixa, semáforo); o motor físico
 *    aplica a cinemática validando restrições de colisão e segurança.
 * 2. Segurança Vital do Agente: Se um agente for suspenso, morrer ou deixar de emitir decisões
 *    frescas (staleness > timeout), o gateway impõe automaticamente uma Paragem Controlada (STOP).
 * 3. Thread-Safety: Acesso concorrente não bloqueante entre threads de agentes JADE e a EDT/Timer do Swing.
 */
public class VehicleControlGateway {

    private static final VehicleControlGateway INSTANCE = new VehicleControlGateway();

    public static VehicleControlGateway getInstance() {
        return INSTANCE;
    }

    /** Tempo máximo de validade de uma decisão antes de ser considerada obsoleta (ms) */
    public static final long DEFAULT_MAX_STALENESS_MS = 600L;

    // Registo de agentes JADE ativos
    private final Map<String, Agent> registeredAgents = new ConcurrentHashMap<>();
    private final Map<String, Boolean> agentSuspendedState = new ConcurrentHashMap<>();

    // Última perceção sensorial produzida pelo ambiente para cada viatura
    private final Map<String, VehiclePerception> latestPerceptions = new ConcurrentHashMap<>();

    // Última decisão deliberada e publicada pelo VehicleAgent
    private final Map<String, VehicleDecision> latestDecisions = new ConcurrentHashMap<>();

    // Contador de decisões globais processadas
    private final AtomicLong totalDecisionsProcessed = new AtomicLong(0);

    private VehicleControlGateway() {}

    /**
     * Regista um VehicleAgent no gateway ao inicializar o agente.
     */
    public void registerAgent(String vehicleId, Agent agent) {
        if (vehicleId != null) {
            registeredAgents.put(vehicleId, agent);
            agentSuspendedState.put(vehicleId, Boolean.FALSE);
        }
    }

    /**
     * Remove o registo de um agente (ao executar takeDown).
     */
    public void unregisterAgent(String vehicleId) {
        if (vehicleId != null) {
            registeredAgents.remove(vehicleId);
            agentSuspendedState.remove(vehicleId);
            latestPerceptions.remove(vehicleId);
            latestDecisions.remove(vehicleId);
        }
    }

    public boolean isAgentRegistered(String vehicleId) {
        return vehicleId != null && registeredAgents.containsKey(vehicleId);
    }

    public Agent getAgent(String vehicleId) {
        return (vehicleId != null) ? registeredAgents.get(vehicleId) : null;
    }

    /**
     * Marca explicitamente o estado de suspensão de um agente (para diagnóstico e controlo).
     */
    public void setAgentSuspended(String vehicleId, boolean suspended) {
        if (vehicleId != null) {
            agentSuspendedState.put(vehicleId, suspended);
            if (suspended) {
                // Ao suspender, invalida de imediato as decisões em cache
                latestDecisions.remove(vehicleId);
            }
        }
    }

    /**
     * Verifica se o agente associado ao veículo está suspenso.
     * Consulta o estado explícito e o ciclo de vida nativo do JADE (se disponível).
     */
    public boolean isAgentSuspended(String vehicleId) {
        if (vehicleId == null) return false;
        if (Boolean.TRUE.equals(agentSuspendedState.get(vehicleId))) {
            return true;
        }
        Agent ag = registeredAgents.get(vehicleId);
        if (ag != null) {
            try {
                if (ag.getAgentState() != null) {
                    String stateName = ag.getAgentState().getName();
                    if ("Suspended".equalsIgnoreCase(stateName)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Publica uma nova perceção sensorial proveniente do motor físico.
     */
    public void publishPerception(String vehicleId, VehiclePerception perception) {
        if (vehicleId != null && perception != null) {
            latestPerceptions.put(vehicleId, perception);
        }
    }

    /**
     * Obtém a última perceção sensorial registada para um veículo (consultada pelo VehicleAgent).
     */
    public VehiclePerception getLatestPerception(String vehicleId) {
        return (vehicleId != null) ? latestPerceptions.get(vehicleId) : null;
    }

    /**
     * Publica uma decisão formulada autonomamente pelo VehicleAgent.
     */
    public void publishDecision(VehicleDecision decision) {
        if (decision != null && decision.getVehicleId() != null) {
            String id = decision.getVehicleId();
            if (isAgentSuspended(id)) {
                return; // Rejeita publicações se o agente estiver suspenso
            }
            latestDecisions.put(id, decision);
            totalDecisionsProcessed.incrementAndGet();
        }
    }

    /**
     * Obtém a última decisão publicada pelo veículo (sem validação de expiração).
     */
    public VehicleDecision getLatestDecision(String vehicleId) {
        return (vehicleId != null) ? latestDecisions.get(vehicleId) : null;
    }

    /**
     * Obtém uma decisão válida, consistente e recente para ser aplicada pelo motor físico.
     *
     * Se o agente estiver suspenso, se não existir decisão, ou se a decisão tiver expirado
     * (idade > maxStalenessMs), impõe uma Paragem Controlada Segura (STOP).
     *
     * @param vehicleId Identificador da viatura
     * @param nowMillis Tempo atual de referência (ms)
     * @param maxStalenessMs Idade máxima tolerada para a decisão (ms)
     * @return Decisão válida do agente ou decisão de Paragem Controlada
     */
    public VehicleDecision getValidDecision(String vehicleId, long nowMillis, long maxStalenessMs) {
        if (vehicleId == null) {
            return null;
        }

        if (isAgentSuspended(vehicleId)) {
            return null;
        }

        if (!registeredAgents.containsKey(vehicleId)) {
            return null; // Sem agente registado
        }

        VehicleDecision decision = latestDecisions.get(vehicleId);
        if (decision == null) {
            return null;
        }

        long age = nowMillis - decision.getTimestampMillis();
        if (age > maxStalenessMs || decision.isExpired(nowMillis)) {
            return null;
        }

        return decision;
    }

    public long getTotalDecisionsProcessed() {
        return totalDecisionsProcessed.get();
    }

    public void clear() {
        registeredAgents.clear();
        agentSuspendedState.clear();
        latestPerceptions.clear();
        latestDecisions.clear();
        totalDecisionsProcessed.set(0);
    }
}

