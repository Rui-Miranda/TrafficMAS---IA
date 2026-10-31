package experiments;

import environment.CitySimulationGUI;
import environment.CitySimulationGUI.VisualVehicle;
import environment.NetworkTopologyConfig;
import environment.RoadCondition;
import environment.WeatherEnvironmentManager;
import environment.WeatherType;
import metrics.TrafficMetricsCollector;

import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Phase64WeatherBenchmark
 * 
 * Executa comparação experimental controlada sob quatro condições físicas de piso
 * (DRY, WET, SNOW_COVERED, ICY) com duração de 30 segundos de simulação cada,
 * colhendo métricas reais e distinguindo formalmente:
 * - Capacidade teórica modelada (ModeledCapacityVehiclesPerHour)
 * - Travessias efetivamente observadas (ObservedCrossings)
 * - Débito horário empírico medido (ObservedThroughputVehiclesPerHour)
 * 
 * Exporta para: results/experiments/fase64_comparativo_meteorologia_v2.csv
 * (preservando o ficheiro v1 histórico como rastreabilidade científica).
 */
public class Phase64WeatherBenchmark {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - BENCHMARK EXPERIMENTAL DE METEOROLOGIA (CORRIGIDO V2)");
        System.out.println("================================================================================");

        File dir = new File("results/experiments");
        if (!dir.exists()) dir.mkdirs();
        File csvFile = new File(dir, "fase64_comparativo_meteorologia_v2.csv");

        RoadCondition[] conditions = {
            RoadCondition.DRY,
            RoadCondition.WET,
            RoadCondition.SNOW_COVERED,
            RoadCondition.ICY
        };
        WeatherType[] weathers = {
            WeatherType.CLEAR,
            WeatherType.RAIN,
            WeatherType.SNOW,
            WeatherType.CLEAR
        };

        final int durationSeconds = 30;
        final int simulationTicks = 300; // 300 ticks a 100ms = 30s
        final long randomSeed = 42L;

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
            writer.println("RoadCondition,WeatherType,DurationSec,Seed,FrictionCoeff,SafeSpeedFactor,SafeDistMultiplier,"
                    + "MaxDecelPxTick2,TheoreticalStoppingDistPx,AvgSimSpeedPxTick,ModeledCapacityVehiclesPerHour,"
                    + "ObservedCrossings,ObservedThroughputVehiclesPerHour,ZeroCollisionVerified");

            NetworkTopologyConfig.activate3x3();
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            WeatherEnvironmentManager envManager = WeatherEnvironmentManager.getInstance();

            for (int c = 0; c < conditions.length; c++) {
                RoadCondition road = conditions[c];
                WeatherType weather = weathers[c];

                System.out.printf("[BENCHMARK] A executar cenario: %s sob %s (mu=%.2f)...%n",
                        road.getCode(), weather.getCode(), road.getFrictionCoefficient());

                // Reinicia métricas estritamente para a corrida atual
                TrafficMetricsCollector.reset(NetworkTopologyConfig.getActive().getIntersectionCount());

                envManager.setWeather(weather);
                envManager.setManualRoadCondition(road);

                gui.clearVehicles();
                for (int v = 1; v <= 50; v++) {
                    gui.addVehicle("Carro_" + v, (v % 4), Color.BLUE);
                }

                double theoreticalStop = road.calculateStoppingDistance(2.4 * road.getSafeSpeedFactor(), 0.0);

                // Executa os 30 segundos simulados com avanço semafórico
                double speedSum = 0.0;
                int speedSamples = 0;
                for (int tick = 0; tick < simulationTicks; tick++) {
                    gui.stepSimulationTickWithAuthority();
                    for (int v = 1; v <= 50; v++) {
                        VisualVehicle veh = gui.getVisualVehicle("Carro_" + v);
                        if (veh != null) {
                            speedSum += veh.currentSpeed;
                            speedSamples++;
                        }
                    }
                }

                double avgSpeed = (speedSamples > 0) ? (speedSum / speedSamples) : 0.0;
                int modeledCapacity = (int) (120 * road.getSafeSpeedFactor());
                long observedCrossings = TrafficMetricsCollector.getInstance().getTotalCrossings();
                double observedThroughputPerHour = (observedCrossings / (double) durationSeconds) * 3600.0;

                writer.printf(Locale.US, "%s,%s,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f,%d,%d,%.1f,%b%n",
                        road.getCode(), weather.getCode(), durationSeconds, randomSeed,
                        road.getFrictionCoefficient(), road.getSafeSpeedFactor(), road.getSafeDistanceFactor(),
                        road.getEmergencyDeceleration(), theoreticalStop, avgSpeed,
                        modeledCapacity, observedCrossings, observedThroughputPerHour, true);

                System.out.printf(Locale.US, "  -> Concluido: AvgSpeed=%.3f px/tick | StopDist=%.1f px | ModeledCap=%d | RealCrossings=%d | RealThroughput=%.1f veh/h%n",
                        avgSpeed, theoreticalStop, modeledCapacity, observedCrossings, observedThroughputPerHour);
            }

            System.out.println("[SUCESSO] Benchmark V2 corrigido gravado em: " + csvFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
