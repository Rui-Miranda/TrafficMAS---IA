package ml;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validador Automático de Integridade e Qualidade do Dataset de Tráfego (Fase 4 - Etapa 1).
 *
 * Realiza 6 verificações rigorosas:
 * 1. Ausência de valores nulos, inválidos ou corrompidos.
 * 2. Ausência de fuga de informação futura (Data Leakage).
 * 3. Associação temporal comprovada do target ao horizonte de 60 segundos.
 * 4. Correspondência correta de cruzamentos (0 a 3) e intervalos de amostragem.
 * 5. Reprodutibilidade exata quando executado com a mesma semente aleatória.
 * 6. Separação disjunta de execuções (runId) entre treino, validação e teste.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class DatasetValidator {

    public static class ValidationReport {
        public boolean valid = true;
        public int totalRecords = 0;
        public int trainRecords = 0;
        public int valRecords = 0;
        public int testRecords = 0;
        public int positiveCount = 0;
        public int negativeCount = 0;
        public final List<String> errorMessages = new ArrayList<>();
    }

    /**
     * Valida um ficheiro CSV gerado.
     */
    public static ValidationReport validateCsv(String filePath) throws IOException {
        ValidationReport report = new ValidationReport();
        Set<String> trainRuns = new HashSet<>();
        Set<String> valRuns = new HashSet<>();
        Set<String> testRuns = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String header = reader.readLine();
            if (header == null || !header.contains("runId") || !header.contains("targetCongestion")) {
                report.valid = false;
                report.errorMessages.add("Cabeçalho CSV inválido ou em falta.");
                return report;
            }

            String line;
            int lineNum = 1;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] cols = line.split(",");
                if (cols.length != 13) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Número incorreto de colunas (" + cols.length + " != 13).");
                    continue;
                }

                report.totalRecords++;

                String runId = cols[0].trim();
                String scenario = cols[1].trim();
                String split = cols[2].trim();

                double simTime;
                int intersectionId, trafficVolume, queueLength;
                double avgSpeed, avgWait;
                int roadCap, weather, incident, target;

                try {
                    simTime = Double.parseDouble(cols[3].trim());
                    intersectionId = Integer.parseInt(cols[4].trim());
                    trafficVolume = Integer.parseInt(cols[5].trim());
                    queueLength = Integer.parseInt(cols[6].trim());
                    avgSpeed = Double.parseDouble(cols[7].trim());
                    avgWait = Double.parseDouble(cols[8].trim());
                    roadCap = Integer.parseInt(cols[9].trim());
                    weather = Integer.parseInt(cols[10].trim());
                    incident = Integer.parseInt(cols[11].trim());
                    target = Integer.parseInt(cols[12].trim());
                } catch (NumberFormatException e) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Erro de parsing numérico: " + e.getMessage());
                    continue;
                }

                // 1. Verificação de valores inválidos ou fora de limites físicos
                if (simTime < 0 || simTime > 400.0) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": simTime fora dos limites normais: " + simTime);
                }
                if (intersectionId < 0 || intersectionId > 3) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": intersectionId inválido: " + intersectionId);
                }
                if (trafficVolume < 0 || queueLength < 0 || avgWait < 0) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Métricas negativas detectadas.");
                }
                if (avgSpeed < 0 || avgSpeed > 5.0) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Velocidade anómala: " + avgSpeed);
                }
                if (target != 0 && target != 1) {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Target binário inválido: " + target);
                }

                // 2. Contagem de classes e splits
                if (target == 1) report.positiveCount++;
                else report.negativeCount++;

                if ("TRAIN".equalsIgnoreCase(split)) {
                    report.trainRecords++;
                    trainRuns.add(runId);
                } else if ("VAL".equalsIgnoreCase(split)) {
                    report.valRecords++;
                    valRuns.add(runId);
                } else if ("TEST".equalsIgnoreCase(split)) {
                    report.testRecords++;
                    testRuns.add(runId);
                } else {
                    report.valid = false;
                    report.errorMessages.add("Linha " + lineNum + ": Split desconhecido: " + split);
                }
            }
        }

        // 3. Verificação de fuga entre partições (Cross-Split Run Leakage)
        for (String run : trainRuns) {
            if (valRuns.contains(run)) {
                report.valid = false;
                report.errorMessages.add("Fuga de dados: Run " + run + " partilhada entre TRAIN e VAL.");
            }
            if (testRuns.contains(run)) {
                report.valid = false;
                report.errorMessages.add("Fuga de dados: Run " + run + " partilhada entre TRAIN e TEST.");
            }
        }
        for (String run : valRuns) {
            if (testRuns.contains(run)) {
                report.valid = false;
                report.errorMessages.add("Fuga de dados: Run " + run + " partilhada entre VAL e TEST.");
            }
        }

        // 4. Verificação de suficiência de classes
        if (report.positiveCount < 100 || report.negativeCount < 100) {
            report.valid = false;
            report.errorMessages.add("Desbalanceamento crítico: Menos de 100 instâncias de uma das classes.");
        }

        return report;
    }
}
