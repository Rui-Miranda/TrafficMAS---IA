package environment;

import java.io.Serializable;

/**
 * Snapshot Imutável do Estado Ambiental (Fase 6.4).
 *
 * Fornecido aos agentes e à GUI garantindo consistência temporal estrita:
 * - Tipo meteorológico (WeatherType)
 * - Estado da superfície da via (RoadCondition)
 * - Modo de gestão do piso (automático com histerese vs manual)
 * - Intensidade (0.0 a 1.0)
 * - Parâmetros físicos derivados (atrito, visibilidade, fator de velocidade segura)
 * - Versão e timestamp determinístico (SimulationClock)
 */
public final class WeatherStateSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final WeatherType weatherType;
    private final RoadCondition roadCondition;
    private final boolean manualRoadOverride;
    private final double intensity;
    private final double friction;
    private final double visibilityKm;
    private final double safeSpeedFactor;
    private final double safeDistanceFactor;
    private final long stateVersion;
    private final long timestampMillis;
    private final String description;

    public WeatherStateSnapshot(WeatherType weatherType, RoadCondition roadCondition,
                                boolean manualRoadOverride, double intensity,
                                long stateVersion, long timestampMillis) {
        this.weatherType = (weatherType != null) ? weatherType : WeatherType.CLEAR;
        this.roadCondition = (roadCondition != null) ? roadCondition : RoadCondition.DRY;
        this.manualRoadOverride = manualRoadOverride;
        this.intensity = Math.max(0.0, Math.min(1.0, intensity));
        this.friction = this.roadCondition.getFrictionCoefficient();
        this.visibilityKm = this.weatherType.getVisibilityKm();
        this.safeSpeedFactor = this.roadCondition.getSafeSpeedFactor();
        this.safeDistanceFactor = this.roadCondition.getSafeDistanceFactor();
        this.stateVersion = stateVersion;
        this.timestampMillis = timestampMillis;
        this.description = String.format("%s | %s%s (Atrito=%.2f, Visib=%.1fkm)",
                this.weatherType.getDisplayName(),
                this.roadCondition.getDisplayName(),
                this.manualRoadOverride ? " [Manual]" : " [Auto]",
                this.friction, this.visibilityKm);
    }

    public WeatherType getWeatherType() {
        return weatherType;
    }

    public RoadCondition getRoadCondition() {
        return roadCondition;
    }

    public boolean isManualRoadOverride() {
        return manualRoadOverride;
    }

    public double getIntensity() {
        return intensity;
    }

    public double getFriction() {
        return friction;
    }

    public double getVisibilityKm() {
        return visibilityKm;
    }

    public double getSafeSpeedFactor() {
        return safeSpeedFactor;
    }

    public double getSafeDistanceFactor() {
        return safeDistanceFactor;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }
}

