package ml;

import java.io.Serializable;

/**
 * Baseline de Persistência (Persistence / Naive Forecasting Baseline).
 * 
 * Regra:
 * Assume que o estado futuro em t + 60s será exatamente idêntico ao estado presente em t.
 * Ou seja, se o cruzamento já apresenta condições de congestionamento severo em t
 * (queueLength >= 6 OU (queueLength >= 5 E averageWaitingTime >= 14.0s)), prevê 1;
 * caso contrário, prevê 0.
 * 
 * Serve de teste crucial de valor acrescentado:
 * Permite quantificar a capacidade real do modelo de ML para ANTECIPAR novos congestionamentos
 * (situações onde o nó está fluido em t e colapsa em t + 60s).
 */
public class PersistenceBaseline implements Classifier, Serializable {

    private static final long serialVersionUID = 1L;

    public PersistenceBaseline() {
    }

    @Override
    public void train(double[][] X, int[] y) {
        // Modelo determinístico sem parâmetros aprendidos
    }

    @Override
    public int predict(double[] x) {
        // Índices das features conforme DatasetLoader:
        // 0: trafficVolume, 1: queueLength, 2: averageSpeed, 3: averageWaitingTime, ...
        double queueLength = x[1];
        double avgWait = x[3];

        boolean isCurrentlyCongested = (queueLength >= 6.0) || (queueLength >= 5.0 && avgWait >= 14.0);
        return isCurrentlyCongested ? 1 : 0;
    }

    @Override
    public double predictProbability(double[] x) {
        return predict(x) == 1 ? 1.0 : 0.0;
    }

    @Override
    public String getModelName() {
        return "Persistence Baseline (Status Quo at t)";
    }
}
