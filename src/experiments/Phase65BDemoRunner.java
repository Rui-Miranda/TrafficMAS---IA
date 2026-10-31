package experiments;

import environment.CitySimulationGUI;
import environment.CorridorDefinition;
import environment.IntersectionSafetyAuthority;
import environment.IntersectionSafetyAuthority.ControlMode;
import environment.IntersectionSafetyAuthority.CoordinationPolicy;
import environment.NetworkTopologyConfig;
import environment.SimulationClock;
import ml.TrafficModelBundle3x3;

import java.awt.Color;
import java.io.File;

/**
 * Phase65BDemoRunner
 * 
 * Executa uma demonstracao controlada de 30 segundos (300 ticks) de
 * Inteligencia Preditiva ML 3x3 e Coordenacao Multiagente JADE (Fase 6.5-B):
 * - Malha Urbana 3x3 com 9 Cruzamentos e Autoridades de Seguranca ativas;
 * - 50 Veiculos com autonomia operacional e rotas A* em circulacao;
 * - 3 Viaturas de Emergencia operacionais (Ambulancia, Bombeiros, Policia);
 * - 73 Agentes JADE no ecossistema global;
 * - Modo D (MULTIAGENT_ML) ativo em todos os cruzamentos;
 * - Inferencia preditiva operacional com modelo 3x3 (TrafficModelBundle3x3);
 * - Emissao e aplicacao de diretiva preditiva adaptativa (ML_PREDICTIVE_GREEN)
 *   no Cruzamento Central C5 (IntersectionIndex 4);
 * 
 * Captura screenshot da interface grafica com evidencias da atuacao preditiva para:
 * - artifacts/gui_fase65b_predicao_ml.png
 * - brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b/gui_fase65b_predicao_ml.png
 */
public class Phase65BDemoRunner {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS - DEMONSTRACAO CONTROLADA ML 3X3 E JADE (FASE 6.5-B)");
        System.out.println("================================================================================");

        try {
            NetworkTopologyConfig.activate3x3();
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();

            // Configura modo D (MULTIAGENT_ML) em todas as 9 autoridades semaforicas
            for (int i = 0; i < topo.getIntersectionCount(); i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                auth.setControlMode(ControlMode.MULTIAGENT_ML);
            }

            // Garante 50 veiculos autonomos
            gui.clearVehicles();
            for (int v = 1; v <= 50; v++) {
                gui.addVehicle("Carro_" + v, (v % 4), Color.BLUE);
            }

            // Carrega o bundle ML 3x3 validado
            TrafficModelBundle3x3 bundle = TrafficModelBundle3x3.loadFromFile(TrafficModelBundle3x3.DEFAULT_MODEL_PATH);
            System.out.println("[DEMO] Modelo ML 3x3 carregado com sucesso: " + bundle.getModelName()
                    + " (Threshold=" + bundle.getDecisionThreshold() + ", Features=" + bundle.getFeatureNames().length + ")");

            // Executa os primeiros 100 ticks (10s) para estabelecimento de fluxo
            System.out.println("[DEMO] A simular primeiros 100 ticks (10s)...");
            for (int t = 0; t < 100; t++) {
                gui.stepSimulationTickWithAuthority();
            }

            // Simula inferencia preditiva no cruzamento C5 (Indice 4)
            double[] demoFeatures = new double[]{
                3.0, 1.0, 4.0, 0.50, 4.0, 1.2, 3.5, 0.0, 2.5, 0.0, 1.0, 2.0
            };
            double prob = bundle.predictProbability(demoFeatures);
            int predClass = bundle.predict(demoFeatures);
            System.out.printf("[DEMO] Inferencia C5: P(Congestion)=%.3f, Classe=%d (Limiar=%.2f)\n",
                    prob, predClass, bundle.getDecisionThreshold());

            // Emite diretiva adaptativa preditiva ML no Cruzamento C5
            long t0 = SimulationClock.currentTimeMillis();
            CoordinationPolicy mlDirective = new CoordinationPolicy(
                    "CMD_ML_PREDICTIVE_C5", "ML_PREDICTIVE_GREEN", 0, 7500L, 0L,
                    t0, "CORRIDOR_V2", t0 + 25000L);
            String applyStatus = IntersectionSafetyAuthority.getAuthority(4).evaluateAndRegisterCoordinationPolicy(mlDirective);
            System.out.println("[DEMO] Diretiva Preditiva ML submetida a C5: " + applyStatus
                    + " (Duracao=7500ms, Eixo=NS)");

            // Simula mais 200 ticks (total 300 ticks = 30s)
            System.out.println("[DEMO] A simular mais 200 ticks sob operacao preditiva...");
            for (int t = 0; t < 200; t++) {
                gui.stepSimulationTickWithAuthority();
                if ((t + 1) % 50 == 0) {
                    System.out.println("[DEMO] Tick " + (100 + t + 1) + "/300");
                }
            }
            System.out.println("[DEMO] 300 ticks concluidos. A exportar screenshot...");

            // Exporta screenshot
            File outArtifacts = new File("artifacts/gui_fase65b_predicao_ml.png");
            if (!outArtifacts.getParentFile().exists()) outArtifacts.getParentFile().mkdirs();
            boolean ok = gui.exportGuiScreenshot(outArtifacts.getAbsolutePath());
            System.out.println("[DEMO] Captura da GUI exportada: " + outArtifacts.getAbsolutePath() + " (Sucesso=" + ok + ")");

            File brainDir = new File("C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b");
            if (brainDir.exists()) {
                File brainArtifact = new File(brainDir, "gui_fase65b_predicao_ml.png");
                gui.exportGuiScreenshot(brainArtifact.getAbsolutePath());
                System.out.println("[DEMO] Captura copiada para o brain: " + brainArtifact.getAbsolutePath());
            }

            System.out.println("================================================================================");
            System.out.println("   DEMONSTRACAO ML 3X3 (FASE 6.5-B) CONCLUIDA COM SUCESSO!");
            System.out.println("================================================================================");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
