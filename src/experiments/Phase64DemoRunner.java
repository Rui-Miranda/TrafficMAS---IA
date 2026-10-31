package experiments;

import environment.CitySimulationGUI;
import environment.CitySimulationGUI.VisualVehicle;
import environment.NetworkTopologyConfig;
import environment.RoadCondition;
import environment.WeatherEnvironmentManager;
import environment.WeatherType;

import java.awt.Color;
import java.io.File;

/**
 * Phase64DemoRunner
 * 
 * Executa uma demonstração controlada de 30 segundos (300 ticks) com meteorologia dinâmica:
 * - 0 a 10s: Céu Limpo / Piso Seco (CLEAR / DRY)
 * - 10 a 20s: Chuva com transição automática para Piso Molhado (RAIN / WET)
 * - 20 a 30s: Céu Limpo com Histerese de Piso Molhado persistente (CLEAR / WET com secagem ativa)
 * 
 * Captura screenshot da interface gráfica para:
 * artifacts/gui_fase64_meteorologia_dinamica.png
 */
public class Phase64DemoRunner {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - DEMONSTRACAO CONTROLADA DE METEOROLOGIA DINAMICA (FASE 6.4)");
        System.out.println("================================================================================");

        try {
            NetworkTopologyConfig.activate3x3();
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            WeatherEnvironmentManager env = WeatherEnvironmentManager.getInstance();

            env.reset();
            env.setWeather(WeatherType.CLEAR);
            env.returnToAutomaticRoadMode();

            gui.clearVehicles();
            for (int v = 1; v <= 50; v++) {
                gui.addVehicle("Carro_" + v, (v % 4), Color.BLUE);
            }

            System.out.println("[DEMO] Fase 1: CLEAR + DRY (Ticks 0 a 100)...");
            for (int t = 0; t < 100; t++) {
                gui.stepSimulationTickWithAuthority();
            }

            System.out.println("[DEMO] Fase 2: Ativacao de CHUVA -> WET (Ticks 100 a 200)...");
            env.setWeather(WeatherType.RAIN);
            for (int t = 100; t < 200; t++) {
                gui.stepSimulationTickWithAuthority();
            }

            System.out.println("[DEMO] Fase 3: Paragem de Chuva (CLEAR) com Histerese de Piso Molhado (Ticks 200 a 300)...");
            env.setWeather(WeatherType.CLEAR);
            for (int t = 200; t < 300; t++) {
                gui.stepSimulationTickWithAuthority();
            }

            File outArtifacts = new File("artifacts/gui_fase64_meteorologia_dinamica.png");
            boolean ok = gui.exportGuiScreenshot(outArtifacts.getAbsolutePath());
            System.out.println("[DEMO] Captura da GUI exportada: " + outArtifacts.getAbsolutePath() + " (Sucesso=" + ok + ")");

            // Copia para o diretório de artifacts do brain se existir
            File brainDir = new File("C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b");
            if (brainDir.exists()) {
                File brainArtifact = new File(brainDir, "gui_fase64_meteorologia_dinamica.png");
                gui.exportGuiScreenshot(brainArtifact.getAbsolutePath());
                System.out.println("[DEMO] Captura copiada para o brain: " + brainArtifact.getAbsolutePath());
            }

            System.out.println("================================================================================");
            System.out.println("   DEMONSTRACAO 30s CONCLUIDA COM SUCESSO!");
            System.out.println("================================================================================");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}

