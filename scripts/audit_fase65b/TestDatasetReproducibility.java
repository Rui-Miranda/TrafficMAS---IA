package scratch;

import environment.NetworkTopologyConfig;
import ml.TrafficDatasetGenerator3x3;
import ml.TrafficDatasetGenerator3x3.DatasetRecord3x3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.List;

public class TestDatasetReproducibility {
    public static void main(String[] args) throws Exception {
        System.out.println("=== REPRODUCIBILITY AUDIT: RUN_01_NORMAL ===");
        NetworkTopologyConfig topo = NetworkTopologyConfig.CONFIG_3X3;

        Method method = TrafficDatasetGenerator3x3.class.getDeclaredMethod(
                "executeSingleSimulationRun", String.class, String.class, String.class, long.class, NetworkTopologyConfig.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<DatasetRecord3x3> generated = (List<DatasetRecord3x3>) method.invoke(
                null, "RUN_01_NORMAL", "NORMAL", "TRAIN", 1055L, topo);

        System.out.println("Generated records for RUN_01: " + generated.size());

        // Compare against data/3x3/dataset_traffic_3x3.csv
        BufferedReader br = new BufferedReader(new FileReader("data/3x3/dataset_traffic_3x3.csv"));
        String header = br.readLine();
        int lineCount = 0;
        int matchCount = 0;
        int diffCount = 0;

        for (int i = 0; i < generated.size(); i++) {
            String originalLine = br.readLine();
            String genLine = generated.get(i).toCsvLine();
            lineCount++;
            if (originalLine.trim().equals(genLine.trim())) {
                matchCount++;
            } else {
                diffCount++;
                if (diffCount <= 5) {
                    System.out.println("[DIFF at record " + (i + 1) + " (Line " + (lineCount + 1) + ")]");
                    System.out.println("  ORIGINAL:  " + originalLine);
                    System.out.println("  GENERATED: " + genLine);
                }
            }
        }
        br.close();

        System.out.println("Comparison Summary for RUN_01 (531 records):");
        System.out.println("  Total compared: " + lineCount);
        System.out.println("  Exact matches:  " + matchCount + " (" + (matchCount * 100.0 / lineCount) + "%)");
        System.out.println("  Differences:    " + diffCount);

        if (diffCount == 0) {
            System.out.println("RESULT: BIT-EXACT REPRODUCIBILITY CONFIRMED FOR RUN_01!");
        } else {
            System.out.println("RESULT: DIFFERENCES DETECTED.");
        }
    }
}

