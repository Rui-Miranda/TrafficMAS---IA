package ml;

import java.io.Serializable;

/**
 * Classificador Baseline de Classe Maioritária (Dummy Classifier).
 * Preve invariavelmente a classe mais frequente observada no conjunto de treino (TRAIN).
 * Serve de referência comparativa nula para avaliar o ganho estatístico real dos modelos de ML.
 */
public class MajorityClassBaseline implements Classifier, Serializable {

    private static final long serialVersionUID = 1L;

    private int majorityClass = 0;
    private double majorityProbability = 0.5;
    private int class0Count = 0;
    private int class1Count = 0;
    private boolean isTrained = false;

    public MajorityClassBaseline() {
    }

    @Override
    public void train(double[][] X, int[] y) {
        if (y == null || y.length == 0) {
            throw new IllegalArgumentException("Rótulos de treino nulos ou vazios para Baseline.");
        }

        class0Count = 0;
        class1Count = 0;

        for (int label : y) {
            if (label == 1) {
                class1Count++;
            } else {
                class0Count++;
            }
        }

        int total = class0Count + class1Count;
        majorityClass = (class1Count >= class0Count) ? 1 : 0;
        majorityProbability = (double) class1Count / total;
        isTrained = true;
    }

    @Override
    public int predict(double[] x) {
        if (!isTrained) {
            throw new IllegalStateException("O baseline deve ser treinado antes da inferência.");
        }
        return majorityClass;
    }

    @Override
    public double predictProbability(double[] x) {
        if (!isTrained) {
            throw new IllegalStateException("O baseline deve ser treinado antes da inferência.");
        }
        return majorityProbability;
    }

    @Override
    public String getModelName() {
        return "Majority Class Baseline (Dummy)";
    }

    public int getMajorityClass() {
        return majorityClass;
    }

    public double getMajorityProbability() {
        return majorityProbability;
    }

    public int getClass0Count() {
        return class0Count;
    }

    public int getClass1Count() {
        return class1Count;
    }
}
