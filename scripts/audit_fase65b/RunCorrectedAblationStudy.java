package audit;

import experiments.Phase65BComparativeCampaign;
import ml.TrafficModelBundle3x3;

public class RunCorrectedAblationStudy {
    public static void main(String[] args) throws Exception {
        System.out.println("A carregar modelo 3x3...");
        TrafficModelBundle3x3 mlBundle = TrafficModelBundle3x3.loadFromFile(TrafficModelBundle3x3.DEFAULT_MODEL_PATH);
        
        String pathCorrected = "results/experiments/fase7_ablation_study_corrected.csv";
        System.out.println("A executar Ablation Study corrigido com 50 corridas emparelhadas...");
        Phase65BComparativeCampaign.runAblationStudy(mlBundle, pathCorrected);
        
        System.out.println("Ablation Study corrigido concluído!");
    }
}
