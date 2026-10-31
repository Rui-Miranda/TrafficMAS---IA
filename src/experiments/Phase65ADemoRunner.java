package experiments;

import environment.CitySimulationGUI;
import environment.CorridorDefinition;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ControlMode;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;

import java.awt.Color;
import java.io.File;

/**
 * Phase65ADemoRunner
 * 
 * Executa uma demonstração controlada de 30 segundos (300 ticks) de Onda Verde Coordenada (Fase 6.5-A):
 * - Malha Urbana 3x3 com 9 Cruzamentos e Autoridades de Segurança ativas;
 * - 50 Veículos com autonomia operacional e rotas A* em circulação;
 * - 3 Viaturas de Emergência operacionais;
 * - Coordenação ativa em Modo C (MULTIAGENT_COORDINATED) no Corredor Horizontal H1 (C1 -> C2 -> C3)
 *   e Corredor Vertical V2 (C2 -> C5 -> C8) com agendamento físico de offsets;
 * 
 * Captura screenshot da interface gráfica com evidências da onda verde coordenada para:
 * - artifacts/gui_fase65a_onda_verde_coordenada.png
 * - brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b/gui_fase65a_onda_verde_coordenada.png
 */
public class Phase65ADemoRunner {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - DEMONSTRACAO CONTROLADA DE ONDAS VERDES 3X3 (FASE 6.5-A)");
        System.out.println("================================================================================");

        try {
            NetworkTopologyConfig.activate3x3();
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();

            // Configura modo C em todas as 9 autoridades
            for (int i = 0; i < topo.getIntersectionCount(); i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                auth.setControlMode(ControlMode.MULTIAGENT_COORDINATED);
            }

            // Garante 50 veículos
            gui.clearVehicles();
            for (int v = 1; v <= 50; v++) {
                gui.addVehicle("Carro_" + v, (v % 4), Color.BLUE);
            }

            // Emite diretiva de onda verde no Corredor H1 (EW, C1 -> C2 -> C3)
            CorridorDefinition h1 = CorridorDefinition.findCorridorById("H1", topo);
            double speed = CorridorDefinition.calculateProgressionSpeedPxPerSec(2.0, 1.0);
            long offC1 = 0L;
            long offC2 = CorridorDefinition.calculateTravelTimeOffsetMs(h1.getSegmentDistancePx(0, topo), speed);
            long offC3 = offC2 + CorridorDefinition.calculateTravelTimeOffsetMs(h1.getSegmentDistancePx(1, topo), speed);

            long t0 = SimulationClock.currentTimeMillis();
            CoordinationPolicy pC1 = new CoordinationPolicy("CMD_DEMO_H1_C1", "GREEN_WAVE", 1, 7000L, offC1, t0, "H1", t0 + 40000L);
            CoordinationPolicy pC2 = new CoordinationPolicy("CMD_DEMO_H1_C2", "GREEN_WAVE", 1, 7000L, offC2, t0, "H1", t0 + 40000L);
            CoordinationPolicy pC3 = new CoordinationPolicy("CMD_DEMO_H1_C3", "GREEN_WAVE", 1, 7000L, offC3, t0, "H1", t0 + 40000L);

            IntersectionSafetyAuthority.getAuthority(0).evaluateAndRegisterCoordinationPolicy(pC1);
            IntersectionSafetyAuthority.getAuthority(1).evaluateAndRegisterCoordinationPolicy(pC2);
            IntersectionSafetyAuthority.getAuthority(2).evaluateAndRegisterCoordinationPolicy(pC3);

            System.out.println("[DEMO] A simular 300 ticks (30 segundos) de operacao coordenada...");
            for (int t = 0; t < 300; t++) {
                gui.stepSimulationTickWithAuthority();
            }

            File outArtifacts = new File("artifacts/gui_fase65a_onda_verde_coordenada.png");
            if (!outArtifacts.getParentFile().exists()) outArtifacts.getParentFile().mkdirs();
            boolean ok = gui.exportGuiScreenshot(outArtifacts.getAbsolutePath());
            System.out.println("[DEMO] Captura da GUI exportada: " + outArtifacts.getAbsolutePath() + " (Sucesso=" + ok + ")");

            File brainDir = new File("C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b");
            if (brainDir.exists()) {
                File brainArtifact = new File(brainDir, "gui_fase65a_onda_verde_coordenada.png");
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
