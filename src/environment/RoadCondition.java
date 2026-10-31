package environment;

/**
 * Estado Físico da Superfície da Estrada (Fase 6.4).
 *
 * Cada estado possui parâmetros físicos documentados e calibrados:
 * - Coeficiente de atrito / aderência relativo (1.0 = seco ideal)
 * - Fator multiplicador de velocidade máxima segura
 * - Fator multiplicador da distância de seguimento segura
 * - Aceleração máxima segura (px/tick^2)
 * - Desaceleração de serviço recomendada (px/tick^2)
 * - Desaceleração de travagem de emergência disponível (px/tick^2)
 * - Aceleração lateral máxima em manobras de cedência/viragem (px/tick)
 */
public enum RoadCondition {
    DRY("Piso Seco", "DRY", 1.00, 1.00, 1.00, 0.15, 0.12, 0.35, 0.75),
    WET("Piso Molhado", "WET", 0.70, 0.80, 1.40, 0.10, 0.08, 0.22, 0.50),
    SNOW_COVERED("Piso com Neve", "SNOW_COVERED", 0.45, 0.60, 1.90, 0.07, 0.05, 0.14, 0.35),
    ICY("Piso com Gelo", "ICY", 0.25, 0.40, 2.60, 0.04, 0.03, 0.08, 0.20);

    private final String displayName;
    private final String code;
    private final double frictionCoefficient;     // Coeficiente de atrito relativo (mu)
    private final double safeSpeedFactor;          // Multiplicador da velocidade segura (0.25 a 1.0)
    private final double safeDistanceFactor;       // Multiplicador da margem de seguimento (>= 1.0)
    private final double maxAcceleration;          // px/tick^2
    private final double serviceDeceleration;      // px/tick^2
    private final double emergencyDeceleration;    // px/tick^2
    private final double maxLateralShiftRate;      // px/tick max em mudança de faixa

    RoadCondition(String displayName, String code, double frictionCoefficient, double safeSpeedFactor,
                  double safeDistanceFactor, double maxAcceleration, double serviceDeceleration,
                  double emergencyDeceleration, double maxLateralShiftRate) {
        this.displayName = displayName;
        this.code = code;
        this.frictionCoefficient = frictionCoefficient;
        this.safeSpeedFactor = safeSpeedFactor;
        this.safeDistanceFactor = safeDistanceFactor;
        this.maxAcceleration = maxAcceleration;
        this.serviceDeceleration = serviceDeceleration;
        this.emergencyDeceleration = emergencyDeceleration;
        this.maxLateralShiftRate = maxLateralShiftRate;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    public double getFrictionCoefficient() {
        return frictionCoefficient;
    }

    public double getSafeSpeedFactor() {
        return safeSpeedFactor;
    }

    public double getSafeDistanceFactor() {
        return safeDistanceFactor;
    }

    public double getMaxAcceleration() {
        return maxAcceleration;
    }

    public double getServiceDeceleration() {
        return serviceDeceleration;
    }

    public double getEmergencyDeceleration() {
        return emergencyDeceleration;
    }

    public double getMaxLateralShiftRate() {
        return maxLateralShiftRate;
    }

    /**
     * Calcula a distância teórica de paragem (em px) para uma dada velocidade (px/tick),
     * considerando tempo de reação tr (em ticks) e desaceleração de serviço a (px/tick^2):
     * d_paragem = v * tr + (v^2) / (2 * a)
     */
    public double calculateStoppingDistance(double speedPxPerTick, double reactionTicks) {
        if (speedPxPerTick <= 0.0) return 0.0;
        double reactionDist = speedPxPerTick * Math.max(0.0, reactionTicks);
        double brakingDist = (speedPxPerTick * speedPxPerTick) / (2.0 * Math.max(0.01, serviceDeceleration));
        return reactionDist + brakingDist;
    }
}

