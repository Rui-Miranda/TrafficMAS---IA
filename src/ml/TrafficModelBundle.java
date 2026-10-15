package ml;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;

/**
 * Pacote serializável autónomo contendo o modelo treinado, normalizador, metadados
 * e garantias de integridade para inferência em produção no TrafficMAS.
 */
public class TrafficModelBundle implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String modelName;
    private final String modelVersion;
    private final Classifier model;
    private final StandardScaler scaler;
    private final String[] featureNames;
    private final double decisionThreshold;
    private final double predictionHorizonSeconds;
    private final String datasetSha256;
    private final long trainingTimestamp;
    private final String trainingSummary;

    public TrafficModelBundle(String modelName, String modelVersion, Classifier model, 
                              StandardScaler scaler, String[] featureNames, double decisionThreshold, 
                              double predictionHorizonSeconds, String datasetSha256, 
                              long trainingTimestamp, String trainingSummary) {
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.model = model;
        this.scaler = scaler;
        this.featureNames = featureNames != null ? Arrays.copyOf(featureNames, featureNames.length) : null;
        this.decisionThreshold = decisionThreshold;
        this.predictionHorizonSeconds = predictionHorizonSeconds;
        this.datasetSha256 = datasetSha256;
        this.trainingTimestamp = trainingTimestamp;
        this.trainingSummary = trainingSummary;
    }

    /**
     * Executa inferência completa sobre o vetor bruto de features (7 variáveis físicas em t).
     */
    public int predict(double[] rawFeatures) {
        validateFeatures(rawFeatures);
        return model.predict(rawFeatures);
    }

    /**
     * Devolve a probabilidade estimada da classe positiva (1 = Congestionamento).
     */
    public double predictProbability(double[] rawFeatures) {
        validateFeatures(rawFeatures);
        return model.predictProbability(rawFeatures);
    }

    private void validateFeatures(double[] rawFeatures) {
        if (rawFeatures == null) {
            throw new IllegalArgumentException("Vetor de features nulo fornecido ao modelo serializado.");
        }
        if (featureNames != null && rawFeatures.length != featureNames.length) {
            throw new IllegalArgumentException(String.format(
                    "Dimensão incorreta de features: recebido %d, esperado %d (%s)",
                    rawFeatures.length, featureNames.length, Arrays.toString(featureNames)));
        }
    }

    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public Classifier getModel() { return model; }
    public StandardScaler getScaler() { return scaler; }
    public String[] getFeatureNames() { return Arrays.copyOf(featureNames, featureNames.length); }
    public double getDecisionThreshold() { return decisionThreshold; }
    public double getPredictionHorizonSeconds() { return predictionHorizonSeconds; }
    public String getDatasetSha256() { return datasetSha256; }
    public long getTrainingTimestamp() { return trainingTimestamp; }
    public String getTrainingSummary() { return trainingSummary; }

    @Override
    public String toString() {
        return String.format("TrafficModelBundle [%s v%s | Horizon=%.0fs | Threshold=%.2f | Features=%d | Trained: %s]",
                modelName, modelVersion, predictionHorizonSeconds, decisionThreshold,
                featureNames != null ? featureNames.length : 0, new Date(trainingTimestamp));
    }

    /**
     * Valida se um hash SHA-256 fornecido é compatível com o hash do dataset congelado no bundle.
     */
    public boolean isDatasetCompatible(String csvSha256) {
        if (datasetSha256 == null || csvSha256 == null) return false;
        return datasetSha256.trim().equalsIgnoreCase(csvSha256.trim());
    }

    /**
     * Calcula o hash criptográfico SHA-256 real de um ficheiro em disco.
     */
    public static String computeFileSha256(java.io.File file) throws java.io.IOException {
        if (file == null || !file.exists() || !file.canRead()) {
            throw new java.io.FileNotFoundException("Ficheiro inacessível para cálculo de SHA-256: " + file);
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream fis = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Algoritmo SHA-256 não disponível no ambiente Java", e);
        }
    }
}
