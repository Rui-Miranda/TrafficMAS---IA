package ml;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Normalizador estatístico (Z-score StandardScaler) em Java puro.
 * Transforma as features para média zero e desvio padrão unitário:
 *   z = (x - mean) / std
 * 
 * GARANTIA ANTI-LEAKAGE: As estatísticas de média e desvio padrão são aprendidas
 * ESTRITAMENTE a partir do conjunto de treino (TRAIN). Os conjuntos de validação
 * ou teste utilizam os parâmetros congelados do treino.
 */
public class StandardScaler implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final double EPSILON = 1e-9;

    private double[] mean;
    private double[] std;
    private boolean isFitted = false;

    public StandardScaler() {
    }

    /**
     * Ajusta (fit) a média e o desvio padrão exclusivamente sobre os dados de treino fornecidos.
     */
    public void fit(double[][] X) {
        if (X == null || X.length == 0 || X[0].length == 0) {
            throw new IllegalArgumentException("Matriz de treino nula ou vazia para StandardScaler.");
        }

        int m = X.length;
        int n = X[0].length;

        mean = new double[n];
        std = new double[n];

        // 1. Calcular média de cada atributo
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                mean[j] += X[i][j];
            }
        }
        for (int j = 0; j < n; j++) {
            mean[j] /= m;
        }

        // 2. Calcular variância e desvio padrão
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double diff = X[i][j] - mean[j];
                std[j] += diff * diff;
            }
        }
        for (int j = 0; j < n; j++) {
            std[j] = Math.sqrt(std[j] / m);
            // Proteção contra divisão por zero em features com variância nula
            if (std[j] < EPSILON) {
                std[j] = 1.0;
            }
        }

        isFitted = true;
    }

    /**
     * Aplica a transformação normalizadora Z-score a uma matriz de dados.
     */
    public double[][] transform(double[][] X) {
        if (!isFitted) {
            throw new IllegalStateException("O StandardScaler deve ser ajustado (fit) antes da transformação.");
        }
        if (X == null || X.length == 0) {
            return new double[0][0];
        }

        int m = X.length;
        int n = X[0].length;

        if (n != mean.length) {
            throw new IllegalArgumentException("Número de atributos incompatível com o scaler ajustado: " 
                    + n + " != " + mean.length);
        }

        double[][] XScaled = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                XScaled[i][j] = (X[i][j] - mean[j]) / std[j];
            }
        }
        return XScaled;
    }

    /**
     * Transforma um único vetor de features.
     */
    public double[] transform(double[] x) {
        if (!isFitted) {
            throw new IllegalStateException("O StandardScaler deve ser ajustado (fit) antes da transformação.");
        }
        if (x.length != mean.length) {
            throw new IllegalArgumentException("Vetor de features com dimensão incorreta: " + x.length);
        }

        double[] xScaled = new double[x.length];
        for (int j = 0; j < x.length; j++) {
            xScaled[j] = (x[j] - mean[j]) / std[j];
        }
        return xScaled;
    }

    /**
     * Executa fit e transform sequencialmente sobre a matriz de treino.
     */
    public double[][] fitTransform(double[][] X) {
        fit(X);
        return transform(X);
    }

    public double[] getMean() {
        return mean != null ? Arrays.copyOf(mean, mean.length) : null;
    }

    public double[] getStd() {
        return std != null ? Arrays.copyOf(std, std.length) : null;
    }

    public boolean isFitted() {
        return isFitted;
    }
}
