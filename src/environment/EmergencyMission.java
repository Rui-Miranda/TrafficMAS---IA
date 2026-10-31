package environment;

import java.io.Serializable;
import java.util.Locale;

/**
 * Representação Explícita e Parametrizável de Missão de Emergência (Fase 6.3 — R7, R8).
 *
 * Características essenciais:
 * 1. Prioridade Dinâmica por Missão: A urgência (1..5) resulta da ocorrência concreta,
 *    e NÃO de uma hierarquia rígida pré-definida (Ambulância > Bombeiros > Polícia).
 * 2. Cálculo de Prioridade de Serviço: Distingue a urgência declarada da prioridade
 *    efetiva de atendimento, adicionando bonificação temporal contra inanição (anti-starvation).
 * 3. Rastreabilidade Completa: Identificador de missão, veículo, categoria, timestamps,
 *    tempo de espera acumulado e estado de ciclo de vida.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.3)
 */
public class EmergencyMission implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum MissionState {
        IDLE,
        DISPATCHED,
        EN_ROUTE,
        ON_SCENE,
        RETURNING,
        COMPLETED,
        CANCELLED
    }

    private final String missionId;
    private final String vehicleId;
    private final String category; // AMBULANCE, FIRE_TRUCK, POLICE
    private volatile int urgencyLevel; // 1 (mínima) a 5 (crítica)
    private volatile MissionState state;
    private final long createdAtMillis;
    private volatile long dispatchedAtMillis;
    private volatile long completedAtMillis;
    private volatile long totalWaitTimeMillis;
    private final String objectiveDescription;

    public EmergencyMission(String missionId, String category, int urgencyLevel) {
        this(missionId, "VEHICLE_" + missionId, category, urgencyLevel, "Atendimento de Emergência");
    }

    public EmergencyMission(String missionId, String vehicleId, String category, int urgencyLevel, String objectiveDescription) {
        this.missionId = (missionId != null) ? missionId : ("MIS_" + vehicleId + "_" + System.currentTimeMillis());
        this.vehicleId = vehicleId;
        this.category = (category != null) ? category.toUpperCase(Locale.ROOT) : "AMBULANCE";
        this.urgencyLevel = Math.max(1, Math.min(5, urgencyLevel));
        this.state = MissionState.EN_ROUTE;
        this.createdAtMillis = SimulationClock.currentTimeMillis();
        this.dispatchedAtMillis = this.createdAtMillis;
        this.completedAtMillis = 0L;
        this.totalWaitTimeMillis = 0L;
        this.objectiveDescription = (objectiveDescription != null) ? objectiveDescription : "Atendimento de Emergência";
    }

    public String getMissionId() {
        return missionId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public String getCategory() {
        return category;
    }

    public int getUrgencyLevel() {
        return urgencyLevel;
    }

    public void setUrgencyLevel(int urgencyLevel) {
        this.urgencyLevel = Math.max(1, Math.min(5, urgencyLevel));
    }

    public MissionState getState() {
        return state;
    }

    public void setState(MissionState state) {
        this.state = state;
        if (state == MissionState.COMPLETED || state == MissionState.CANCELLED) {
            this.completedAtMillis = SimulationClock.currentTimeMillis();
        }
    }

    public boolean isActive() {
        return state == MissionState.DISPATCHED || state == MissionState.EN_ROUTE;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getDispatchedAtMillis() {
        return dispatchedAtMillis;
    }

    public long getCompletedAtMillis() {
        return completedAtMillis;
    }

    public long getTotalWaitTimeMillis() {
        return totalWaitTimeMillis;
    }

    public void addWaitTime(long deltaMillis) {
        if (deltaMillis > 0) {
            this.totalWaitTimeMillis += deltaMillis;
        }
    }

    public String getObjectiveDescription() {
        return objectiveDescription;
    }

    /**
     * Calcula a prioridade de serviço efetiva para arbitragem em interseções.
     *
     * Fórmula: Urgência Base * 100 + Bónus Anti-Inanição baseado no tempo de espera
     * A bonificação temporal garante que um veículo com urgência moderada retido há muito tempo
     * é atendido sem preterir injustificadamente situações críticas.
     *
     * @param nowMillis Tempo atual de simulação
     * @return Valor numérico de prioridade de serviço para ordenação
     */
    public double calculateServicePriority(long nowMillis) {
        if (!isActive()) {
            return 0.0;
        }
        long waitSeconds = Math.max(0L, (nowMillis - dispatchedAtMillis) / 1000L);
        double starvationBonus = Math.min(50.0, waitSeconds * 2.5);
        return (this.urgencyLevel * 100.0) + starvationBonus;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "EmergencyMission[id=%s, viatura=%s, cat=%s, urg=%d, estado=%s, obj='%s']",
                missionId, vehicleId, category, urgencyLevel, state, objectiveDescription);
    }
}
