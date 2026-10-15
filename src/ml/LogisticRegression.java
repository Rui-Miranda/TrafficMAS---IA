package ml;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modelo A — Regressão Logística Binária com Regularização L2 e Otimização por Gradient Descent.
 * 
 * Propriedades Técnicas:
 * 1. Sigmoid numericamente estável para evitar overflow em exponenciais negativas.
 * 2. Função de perda Binary Cross-Entropy (Log-Loss) com penalidade L2 (Ridge) nos pesos.
 * 3. O termo de bias (intercept) não sofre penalização de regularização L2.
 * 4. Normalização Z-score integrada (StandardScaler) ajustada exclusivamente sobre os dados de treino.
 * 5. Critério de convergência por variação mínima de custo ou gradiente norm.
 */
public class LogisticRegression implements Classifier, Serializable {

    private static final long serialVersionUID = 1L;
    private static final double EPSILON = 1e-15;

    // Hiperparâmetros
    private double learningRate = 0.1;
    private int maxEpochs = 1500;
    private double lambda = 0.01; // Coeficiente de regularização L2
    private double tolerance = 1e-6; // Critério de convergência antecipada
    private double threshold = 0.5; // Limiar de decisão para classificação binária

    // Parâmetros aprendidos
    private double bias = 0.0;
    private double[] weights;
    private StandardScaler scaler = new StandardScaler();
    private boolean isTrained = false;

    // Telemetria do treino
    private List<Double> costHistory = new ArrayList<>();

    public LogisticRegression() {
    }

    public LogisticRegression(double learningRate, int maxEpochs, double lambda, double tolerance, double threshold) {
        if (learningRate <= 0.0) {
            throw new IllegalArgumentException("Taxa de aprendizagem deve ser positiva: " + learningRate);
        }
        if (maxEpochs <= 0) {
            throw new IllegalArgumentException("Máximo de épocas deve ser positivo: " + maxEpochs);
        }
        if (lambda < 0.0) {
            throw new IllegalArgumentException("Lambda L2 não pode ser negativo: " + lambda);
        }
        if (threshold <= 0.0 || threshold >= 1.0) {
            throw new IllegalArgumentException("Limiar de decisão deve estar no intervalo (0, 1): " + threshold);
        }
        this.learningRate = learningRate;
        this.maxEpochs = maxEpochs;
        this.lambda = lambda;
        this.tolerance = tolerance;
        this.threshold = threshold;
    }

    /**
     * Função Sigmoid Numericamente Estável:
     * Para z >= 0:  1 / (1 + exp(-z))
     * Para z < 0:   exp(z) / (1 + exp(z))
     */
    public static double stableSigmoid(double z) {
        if (z >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-z));
        } else {
            double expZ = Math.exp(z);
            return expZ / (1.0 + expZ);
        }
    }

    @Override
    public void train(double[][] X, int[] y) {
        if (X == null || y == null || X.length == 0) {
            throw new IllegalArgumentException("Dados de treino nulos ou vazios para Regressão Logística.");
        }
        if (X.length != y.length) {
            throw new IllegalArgumentException("Dimensões incompatíveis entre X (" + X.length + ") e y (" + y.length + ").");
        }

        int m = X.length;
        int n = X[0].length;

        // 1. Ajustar e aplicar normalização StandardScaler estritamente sobre TRAIN
        scaler.fit(X);
        double[][] XNorm = scaler.transform(X);

        // 2. Inicialização dos parâmetros (pesos a zero)
        weights = new double[n];
        bias = 0.0;
        costHistory.clear();

        double prevCost = Double.MAX_VALUE;

        // 3. Otimização por Gradient Descent
        for (int epoch = 0; epoch < maxEpochs; epoch++) {
            double[] predictions = new double[m];
            double[] errors = new double[m];

            // Forward pass: cálculo das probabilidades
            for (int i = 0; i < m; i++) {
                double linearComb = bias;
                for (int j = 0; j < n; j++) {
                    linearComb += weights[j] * XNorm[i][j];
                }
                predictions[i] = stableSigmoid(linearComb);
                errors[i] = predictions[i] - y[i];
            }

            // Cálculo do custo Binary Cross-Entropy com L2
            double cost = computeCost(y, predictions, weights, m, lambda);
            costHistory.add(cost);

            // Verificação do critério de convergência
            if (Math.abs(prevCost - cost) < tolerance) {
                break;
            }
            prevCost = cost;

            // Backward pass: gradientes
            double gradBias = 0.0;
            double[] gradWeights = new double[n];

            for (int i = 0; i < m; i++) {
                gradBias += errors[i];
                for (int j = 0; j < n; j++) {
                    gradWeights[j] += errors[i] * XNorm[i][j];
                }
            }

            gradBias /= m;
            for (int j = 0; j < n; j++) {
                // Gradiente com regularização L2: grad_j = (1/m)*sum(...) + (lambda/m)*w_j
                gradWeights[j] = (gradWeights[j] / m) + ((lambda / m) * weights[j]);
            }

            // Atualização dos parâmetros
            bias -= learningRate * gradBias;
            for (int j = 0; j < n; j++) {
                weights[j] -= learningRate * gradWeights[j];
            }
        }

        isTrained = true;
    }

    /**
     * Calcula a perda Binary Cross-Entropy com regularização L2.
     */
    private static double computeCost(int[] y, double[] preds, double[] weights, int m, double lambda) {
        double bceLoss = 0.0;
        for (int i = 0; i < m; i++) {
            double p = Math.max(EPSILON, Math.min(1.0 - EPSILON, preds[i]));
            bceLoss += -(y[i] * Math.log(p) + (1 - y[i]) * Math.log(1.0 - p));
        }
        bceLoss /= m;

        // Regularização L2 nos pesos (excluindo bias)
        double l2Reg = 0.0;
        for (double w : weights) {
            l2Reg += w * w;
        }
        l2Reg = (lambda / (2.0 * m)) * l2Reg;

        return bceLoss + l2Reg;
    }

    @Override
    public double predictProbability(double[] x) {
        if (!isTrained) {
            throw new IllegalStateException("O modelo de Regressão Logística deve ser treinado antes da inferência.");
        }
        if (x == null || x.length != weights.length) {
            throw new IllegalArgumentException("Vetor de features com dimensão incorreta.");
        }

        // Aplica a normalização congelada do treino
        double[] xNorm = scaler.transform(x);

        double linearComb = bias;
        for (int j = 0; j < weights.length; j++) {
            linearComb += weights[j] * xNorm[j];
        }
        return stableSigmoid(linearComb);
    }

    @Override
    public int predict(double[] x) {
        return predictProbability(x) >= threshold ? 1 : 0;
    }

    @Override
    public String getModelName() {
        return "Logistic Regression (L2 Regularized)";
    }

    public double[] getWeights() {
        return weights != null ? Arrays.copyOf(weights, weights.length) : null;
    }

    public double getBias() {
        return bias;
    }

    public List<Double> getCostHistory() {
        return new ArrayList<>(costHistory);
    }

    public double getInitialCost() {
        return costHistory.isEmpty() ? 0.0 : costHistory.get(0);
    }

    public double getFinalCost() {
        return costHistory.isEmpty() ? 0.0 : costHistory.get(costHistory.size() - 1);
    }

    public double getLambda() {
        return lambda;
    }

    public double getLearningRate() {
        return learningRate;
    }

    public int getMaxEpochs() {
        return maxEpochs;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public boolean isTrained() {
        return isTrained;
    }
}
