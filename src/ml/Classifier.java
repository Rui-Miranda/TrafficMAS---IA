package ml;

/**
 * Interface unificada para algoritmos de aprendizagem supervisionada no TrafficMAS.
 * Define os métodos canónicos de treino e inferência (classes e probabilidades).
 */
public interface Classifier {

    /**
     * Treina o modelo supervisionado utilizando exclusivamente a matriz de features X e rótulos y.
     * @param X Matriz de observações [amostras x features]
     * @param y Vetor de classes binárias [0 ou 1]
     */
    void train(double[][] X, int[] y);

    /**
     * Infere a classe predita para uma única observação.
     * @param x Vetor de features da observação
     * @return Classe inferida (0 ou 1)
     */
    int predict(double[] x);

    /**
     * Calcula a probabilidade estimada de a observação pertencer à classe positiva (1 = Congestionamento).
     * @param x Vetor de features da observação
     * @return Probabilidade no intervalo [0.0, 1.0]
     */
    double predictProbability(double[] x);

    /**
     * Infere a classe para um lote de observações.
     * @param X Matriz de observações
     * @return Vetor de classes inferidas
     */
    default int[] predict(double[][] X) {
        int[] preds = new int[X.length];
        for (int i = 0; i < X.length; i++) {
            preds[i] = predict(X[i]);
        }
        return preds;
    }

    /**
     * Calcula as probabilidades estimadas para um lote de observações.
     * @param X Matriz de observações
     * @return Vetor de probabilidades da classe 1
     */
    default double[] predictProbabilities(double[][] X) {
        double[] probs = new double[X.length];
        for (int i = 0; i < X.length; i++) {
            probs[i] = predictProbability(X[i]);
        }
        return probs;
    }

    /**
     * Retorna a designação do modelo de Machine Learning.
     */
    String getModelName();
}
