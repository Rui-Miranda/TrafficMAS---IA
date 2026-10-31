package environment;

/**
 * Tipo de Condição Meteorológica Atmosférica (Fase 6.4).
 *
 * Distinção fundamental:
 * - {@link WeatherType} descreve o fenómeno meteorológico que ocorre no ar (céu).
 * - {@link RoadCondition} descreve o estado físico da superfície do asfalto (piso).
 */
public enum WeatherType {
    CLEAR("Céu Limpo", "CLEAR", 1.0, 10.0),
    RAIN("Chuva", "RAIN", 0.75, 4.0),
    SNOW("Neve", "SNOW", 0.55, 2.0);

    private final String displayName;
    private final String code;
    private final double defaultPrecipitationIntensity; // 0.0 a 1.0
    private final double visibilityKm;

    WeatherType(String displayName, String code, double defaultPrecipitationIntensity, double visibilityKm) {
        this.displayName = displayName;
        this.code = code;
        this.defaultPrecipitationIntensity = defaultPrecipitationIntensity;
        this.visibilityKm = visibilityKm;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    public double getDefaultPrecipitationIntensity() {
        return defaultPrecipitationIntensity;
    }

    public double getVisibilityKm() {
        return visibilityKm;
    }
}

