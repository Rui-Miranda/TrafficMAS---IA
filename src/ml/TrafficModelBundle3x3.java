package ml;

import environment.NetworkTopologyConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Pacote Serializável Autónomo do Modelo Preditivo 3×3 (Fase 6.5-B).
 *
 * Encapsula:
 * 1. Modelo de classificação treinado (Regressão Logística regularizada).
 * 2. Normalizador estatístico (médias e desvios padrão aprendidos estritamente em TRAIN).
 * 3. Schema estrito de 12 features.
 * 4. Validador explícito de topologia (GRID_3X3). Rejeita redes 2×2 ou outras dimensões.
 * 5. Limiar de decisão (threshold) calibrado exclusivamente no split de validação (VAL).
 * 6. Hash criptográfico SHA-256 do dataset de treino para rastreabilidade académica total.
 * 7. Métricas de validação e teste fora da amostra (out-of-sample).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficModelBundle3x3 implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_MODEL_PATH = "models/3x3/model_3x3_logistic_regression.ser";
    public static final String REQUIRED_TOPOLOGY = "GRID_3X3";
    public static final int REQUIRED_FEATURES_COUNT = 12;

    private final String modelName;
    private final String modelVersion;
    private final String topology;
    private final String[] featureNames;
    private final double[] scalerMean;
    private final double[] scalerStd;
    private final double[] weights;
    private final double bias;
    private final double decisionThreshold;
    private final double predictionHorizonSeconds;
    private final String datasetSha256;
    private final long trainingTimestamp;
    private final Map<String, Double> validationMetrics;
    private final Map<String, Double> testMetrics;

    public TrafficModelBundle3x3(String modelName, String modelVersion, String topology,
                                 String[] featureNames, double[] scalerMean, double[] scalerStd,
                                 double[] weights, double bias, double decisionThreshold,
                                 double predictionHorizonSeconds, String datasetSha256,
                                 long trainingTimestamp, Map<String, Double> validationMetrics,
                                 Map<String, Double> testMetrics) {
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.topology = topology;
        this.featureNames = (featureNames != null) ? Arrays.copyOf(featureNames, featureNames.length) : null;
        this.scalerMean = (scalerMean != null) ? Arrays.copyOf(scalerMean, scalerMean.length) : null;
        this.scalerStd = (scalerStd != null) ? Arrays.copyOf(scalerStd, scalerStd.length) : null;
        this.weights = (weights != null) ? Arrays.copyOf(weights, weights.length) : null;
        this.bias = bias;
        this.decisionThreshold = decisionThreshold;
        this.predictionHorizonSeconds = predictionHorizonSeconds;
        this.datasetSha256 = datasetSha256;
        this.trainingTimestamp = trainingTimestamp;
        this.validationMetrics = (validationMetrics != null) ? new HashMap<>(validationMetrics) : new HashMap<>();
        this.testMetrics = (testMetrics != null) ? new HashMap<>(testMetrics) : new HashMap<>();
    }

    /**
     * Valida se a topologia da simulação ativa é estritamente compatível com a malha 3×3.
     */
    public void validateTopology(NetworkTopologyConfig activeTopo) {
        if (activeTopo == null) {
            throw new IllegalArgumentException("[Model 3x3] Configuração topológica nula.");
        }
        if (activeTopo.getRows() != 3 || activeTopo.getCols() != 3 || activeTopo.getIntersectionCount() != 9) {
            throw new IllegalArgumentException(String.format(
                    "[Model 3x3] INCOMPATIBILIDADE TOPOLÓGICA: O modelo requer a topologia %s (9 cruzamentos), " +
                    "mas o ambiente ativo possui %dx%d (%d cruzamentos). Operação recusada.",
                    REQUIRED_TOPOLOGY, activeTopo.getRows(), activeTopo.getCols(), activeTopo.getIntersectionCount()));
        }
    }

    /**
     * Valida se o vetor de features fornecido em tempo de execução respeita o schema de 12 atributos.
     */
    public void validateFeatures(double[] rawFeatures) {
        if (rawFeatures == null) {
            throw new IllegalArgumentException("[Model 3x3] Vetor de features nulo fornecido à inferência.");
        }
        if (rawFeatures.length != REQUIRED_FEATURES_COUNT) {
            throw new IllegalArgumentException(String.format(
                    "[Model 3x3] Dimensão de features inválida: recebido %d, esperado %d (%s).",
                    rawFeatures.length, REQUIRED_FEATURES_COUNT, Arrays.toString(featureNames)));
        }
    }

    /**
     * Calcula a probabilidade de congestionamento futuro (em t + 60s) aplicando normalização Z-score e Sigmoid.
     */
    public double predictProbability(double[] rawFeatures) {
        validateFeatures(rawFeatures);

        double z = bias;
        for (int j = 0; j < rawFeatures.length; j++) {
            double normalizedX = (rawFeatures[j] - scalerMean[j]) / scalerStd[j];
            z += weights[j] * normalizedX;
        }

        // Sigmoid numericamente estável
        if (z >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-z));
        } else {
            double expZ = Math.exp(z);
            return expZ / (1.0 + expZ);
        }
    }

    /**
     * Classificação binária (0 = Fluido, 1 = Congestionamento Previsto) usando o threshold calibrado.
     */
    public int predict(double[] rawFeatures) {
        return predictProbability(rawFeatures) >= decisionThreshold ? 1 : 0;
    }

    // Getters
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getTopology() { return topology; }
    public String[] getFeatureNames() { return (featureNames != null) ? Arrays.copyOf(featureNames, featureNames.length) : null; }
    public double[] getScalerMean() { return (scalerMean != null) ? Arrays.copyOf(scalerMean, scalerMean.length) : null; }
    public double[] getScalerStd() { return (scalerStd != null) ? Arrays.copyOf(scalerStd, scalerStd.length) : null; }
    public double[] getWeights() { return (weights != null) ? Arrays.copyOf(weights, weights.length) : null; }
    public double getBias() { return bias; }
    public double getDecisionThreshold() { return decisionThreshold; }
    public double getPredictionHorizonSeconds() { return predictionHorizonSeconds; }
    public String getDatasetSha256() { return datasetSha256; }
    public long getTrainingTimestamp() { return trainingTimestamp; }
    public Map<String, Double> getValidationMetrics() { return Collections.unmodifiableMap(validationMetrics); }
    public Map<String, Double> getTestMetrics() { return Collections.unmodifiableMap(testMetrics); }

    public boolean isDatasetCompatible(String csvSha256) {
        if (datasetSha256 == null || csvSha256 == null) return false;
        return datasetSha256.trim().equalsIgnoreCase(csvSha256.trim());
    }

    public static String computeFileSha256(File file) throws IOException {
        if (file == null || !file.exists() || !file.canRead()) {
            throw new IOException("Ficheiro inacessível para SHA-256: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Falha no cálculo do SHA-256: " + e.getMessage(), e);
        }
    }

    public void saveToFile(String path) throws IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            oos.writeObject(this);
        }
    }

    public static TrafficModelBundle3x3 loadFromFile(String path) throws IOException, ClassNotFoundException {
        File f = new File(path);
        if (!f.exists()) {
            throw new IOException("Ficheiro de modelo não encontrado: " + path);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            Object obj = ois.readObject();
            if (!(obj instanceof TrafficModelBundle3x3)) {
                throw new IOException("O ficheiro serializado não corresponde a uma instância de TrafficModelBundle3x3: " + path);
            }
            return (TrafficModelBundle3x3) obj;
        }
    }

    @Override
    public String toString() {
        return String.format("TrafficModelBundle3x3 [%s v%s | Topologia=%s | Horizonte=%.0fs | Threshold=%.2f | Features=%d | Treinado: %s]",
                modelName, modelVersion, topology, predictionHorizonSeconds, decisionThreshold,
                (featureNames != null ? featureNames.length : 0), new Date(trainingTimestamp));
    }
}
