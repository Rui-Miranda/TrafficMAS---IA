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
}
