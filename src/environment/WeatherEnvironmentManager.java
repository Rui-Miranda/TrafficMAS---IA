package environment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestor Central de Meteorologia e Condições da Estrada (Fase 6.4).
 *
 * Responsabilidades:
 * 1. Manter o estado meteorológico central (WeatherType) e o estado do asfalto (RoadCondition).
 * 2. Suportar modo automático (regras físicas com histerese e persistência) e modo manual (override explícito).
 * 3. Histerese de secagem e derretimento baseada em {@link SimulationClock}:
 *    - Quando a chuva cessa (CLEAR), o piso WET persiste por {@link #DEFAULT_DRYING_DURATION_SIM_SEC} segundos simulados.
 *    - Quando a neve cessa (CLEAR), o piso SNOW_COVERED persiste por {@link #DEFAULT_SNOW_MELT_DURATION_SIM_SEC} segundos simulados antes de passar a WET.
 *    - Não oscila rapidamente nem gera gelo espúrio sem ordem do utilizador.
 * 4. Fornecer snapshots imutáveis thread-safe {@link WeatherStateSnapshot} com controlo de versão e timestamp.
 * 5. Notificar ouvintes (GUI, autoridades semafóricas, controladores de tráfego) sem bloquear a EDT do Swing.
 */
public final class WeatherEnvironmentManager {

    public static final double DEFAULT_DRYING_DURATION_SIM_SEC = 10.0;    // 10s simulados de piso molhado após chuva
    public static final double DEFAULT_SNOW_MELT_DURATION_SIM_SEC = 15.0; // 15s simulados de neve antes de passar a molhado
    public static final double DEFAULT_ICY_THAW_DURATION_SIM_SEC = 20.0;  // 20s simulados de gelo antes de passar a molhado

    private static final WeatherEnvironmentManager INSTANCE = new WeatherEnvironmentManager();

    private WeatherType currentWeather = WeatherType.CLEAR;
    private RoadCondition currentRoad = RoadCondition.DRY;
    private boolean manualRoadOverride = false;
    private double weatherIntensity = 1.0;

    // Temporizadores de histerese baseados em SimulationClock
    private double dryingDurationSimSec = DEFAULT_DRYING_DURATION_SIM_SEC;
    private double snowMeltDurationSimSec = DEFAULT_SNOW_MELT_DURATION_SIM_SEC;
    private double icyThawDurationSimSec = DEFAULT_ICY_THAW_DURATION_SIM_SEC;

    private double weatherChangeSimTimeSec = 0.0;
    private double transitionStartSimTimeSec = -1.0;
    private RoadCondition transitionTargetRoad = null;

    private final AtomicLong stateVersion = new AtomicLong(1L);

    private WeatherEnvironmentManager() {
        this.weatherChangeSimTimeSec = SimulationClock.currentTimeSeconds();
    }

    public static WeatherEnvironmentManager getInstance() {
        return INSTANCE;
    }

    public synchronized void reset() {
        this.currentWeather = WeatherType.CLEAR;
        this.currentRoad = RoadCondition.DRY;
        this.manualRoadOverride = false;
        this.weatherIntensity = 1.0;
        this.dryingDurationSimSec = DEFAULT_DRYING_DURATION_SIM_SEC;
        this.snowMeltDurationSimSec = DEFAULT_SNOW_MELT_DURATION_SIM_SEC;
        this.icyThawDurationSimSec = DEFAULT_ICY_THAW_DURATION_SIM_SEC;
        this.weatherChangeSimTimeSec = SimulationClock.currentTimeSeconds();
        this.transitionStartSimTimeSec = -1.0;
        this.transitionTargetRoad = null;
        this.stateVersion.incrementAndGet();
    }

    /**
     * Atualiza a meteorologia (céu). Se o piso estiver em modo automático,
     * desencadeia as transições físicas apropriadas com histerese.
     */
    public synchronized void setWeather(WeatherType newWeather) {
        if (newWeather == null) newWeather = WeatherType.CLEAR;
        if (this.currentWeather == newWeather) return;

        double nowSim = SimulationClock.currentTimeSeconds();
        this.currentWeather = newWeather;
        this.weatherChangeSimTimeSec = nowSim;

        if (!manualRoadOverride) {
            applyAutomaticWeatherTransitions(nowSim);
        }
        stateVersion.incrementAndGet();
    }

    /**
     * Aplica transições físicas automáticas com base na meteorologia e no tempo simulado.
     */
    private void applyAutomaticWeatherTransitions(double nowSim) {
        switch (currentWeather) {
            case RAIN:
                // Chuva molha o piso imediatamente
                this.currentRoad = RoadCondition.WET;
                this.transitionStartSimTimeSec = -1.0;
                this.transitionTargetRoad = null;
                break;

            case SNOW:
                // Neve cobre o piso imediatamente
                this.currentRoad = RoadCondition.SNOW_COVERED;
                this.transitionStartSimTimeSec = -1.0;
                this.transitionTargetRoad = null;
                break;

            case CLEAR:
                // Céu limpo: inicia histerese de secagem/derretimento se piso estiver molhado/neve/gelo
                if (currentRoad == RoadCondition.WET) {
                    this.transitionStartSimTimeSec = nowSim;
                    this.transitionTargetRoad = RoadCondition.DRY;
                } else if (currentRoad == RoadCondition.SNOW_COVERED) {
                    this.transitionStartSimTimeSec = nowSim;
                    this.transitionTargetRoad = RoadCondition.WET;
                } else if (currentRoad == RoadCondition.ICY) {
                    this.transitionStartSimTimeSec = nowSim;
                    this.transitionTargetRoad = RoadCondition.WET;
                }
                break;
        }
    }

    /**
     * Atualização contínua chamada em cada tick da simulação para processar a histerese de secagem.
     */
    public synchronized void updateSimulationTick() {
        if (manualRoadOverride) return;

        double nowSim = SimulationClock.currentTimeSeconds();
        if (transitionStartSimTimeSec >= 0.0 && transitionTargetRoad != null) {
            double elapsed = nowSim - transitionStartSimTimeSec;

            if (currentRoad == RoadCondition.WET && transitionTargetRoad == RoadCondition.DRY) {
                if (elapsed >= dryingDurationSimSec) {
                    this.currentRoad = RoadCondition.DRY;
                    this.transitionStartSimTimeSec = -1.0;
                    this.transitionTargetRoad = null;
                    stateVersion.incrementAndGet();
                }
            } else if (currentRoad == RoadCondition.SNOW_COVERED && transitionTargetRoad == RoadCondition.WET) {
                if (elapsed >= snowMeltDurationSimSec) {
                    this.currentRoad = RoadCondition.WET;
                    // Inicia subsequente secagem WET -> DRY se continuar CLEAR
                    if (currentWeather == WeatherType.CLEAR) {
                        this.transitionStartSimTimeSec = nowSim;
                        this.transitionTargetRoad = RoadCondition.DRY;
                    } else {
                        this.transitionStartSimTimeSec = -1.0;
                        this.transitionTargetRoad = null;
                    }
                    stateVersion.incrementAndGet();
                }
            } else if (currentRoad == RoadCondition.ICY && transitionTargetRoad == RoadCondition.WET) {
                if (elapsed >= icyThawDurationSimSec) {
                    this.currentRoad = RoadCondition.WET;
                    if (currentWeather == WeatherType.CLEAR) {
                        this.transitionStartSimTimeSec = nowSim;
                        this.transitionTargetRoad = RoadCondition.DRY;
                    } else {
                        this.transitionStartSimTimeSec = -1.0;
                        this.transitionTargetRoad = null;
                    }
                    stateVersion.incrementAndGet();
                }
            }
        }
    }

    /**
     * Força manualmente o estado do piso (override do utilizador).
     */
    public synchronized void setManualRoadCondition(RoadCondition road) {
        if (road == null) road = RoadCondition.DRY;
        this.manualRoadOverride = true;
        this.currentRoad = road;
        this.transitionStartSimTimeSec = -1.0;
        this.transitionTargetRoad = null;
        stateVersion.incrementAndGet();
    }

    /**
     * Restaura o modo automático de gestão do piso com histerese.
     */
    public synchronized void returnToAutomaticRoadMode() {
        this.manualRoadOverride = false;
        double nowSim = SimulationClock.currentTimeSeconds();
        applyAutomaticWeatherTransitions(nowSim);
        stateVersion.incrementAndGet();
    }

    public synchronized void setDryingDurationSimSec(double sec) {
        this.dryingDurationSimSec = Math.max(0.01, sec);
    }

    public synchronized double getDryingDurationSimSec() {
        return dryingDurationSimSec;
    }

    public synchronized void setSnowMeltDurationSimSec(double sec) {
        this.snowMeltDurationSimSec = Math.max(0.01, sec);
    }

    public synchronized double getSnowMeltDurationSimSec() {
        return snowMeltDurationSimSec;
    }

    public synchronized void setIcyThawDurationSimSec(double sec) {
        this.icyThawDurationSimSec = Math.max(1.0, sec);
    }

    public synchronized double getIcyThawDurationSimSec() {
        return icyThawDurationSimSec;
    }

    public synchronized void setWeatherIntensity(double intensity) {
        this.weatherIntensity = Math.max(0.1, Math.min(1.0, intensity));
        stateVersion.incrementAndGet();
    }

    public synchronized double getWeatherIntensity() {
        return weatherIntensity;
    }

    public synchronized boolean isTransitionInProgress() {
        return !manualRoadOverride && transitionStartSimTimeSec >= 0.0 && transitionTargetRoad != null;
    }

    public synchronized double getRemainingTransitionSec() {
        if (!isTransitionInProgress()) return 0.0;
        double nowSim = SimulationClock.currentTimeSeconds();
        double elapsed = nowSim - transitionStartSimTimeSec;
        double targetDur = (currentRoad == RoadCondition.WET) ? dryingDurationSimSec
                : ((currentRoad == RoadCondition.SNOW_COVERED) ? snowMeltDurationSimSec : icyThawDurationSimSec);
        return Math.max(0.0, targetDur - elapsed);
    }

    public synchronized RoadCondition getTransitionTargetRoad() {
        return transitionTargetRoad;
    }

    /**
     * Obtém o snapshot imutável representativo do estado atual.
     */
    public synchronized WeatherStateSnapshot getSnapshot() {
        return new WeatherStateSnapshot(
                currentWeather,
                currentRoad,
                manualRoadOverride,
                weatherIntensity,
                stateVersion.get(),
                SimulationClock.currentTimeMillis()
        );
    }
}

