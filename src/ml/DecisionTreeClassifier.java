package ml;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Modelo B — Árvore de Decisão CART (Classification and Regression Trees) em Java puro.
 * 
 * Propriedades Técnicas:
 * 1. Critério de divisão baseado na minimização da Impureza de Gini.
 * 2. Avaliação exaustiva de limiares contínuos nos pontos médios dos valores observados.
 * 3. Construção recursiva com restrições explícitas contra overfitting:
 *    - Profundidade máxima da árvore (maxDepth);
 *    - Número mínimo de observações para tentar divisão (minSamplesSplit);
 *    - Número mínimo de observações admissível numa folha (minSamplesLeaf).
 * 4. Inferência de probabilidades com base na distribuição empírica das classes na folha terminal.
 */
public class DecisionTreeClassifier implements Classifier, Serializable {

    private static final long serialVersionUID = 1L;

    // Hiperparâmetros de regularização e controlo
    private int maxDepth = 5;
    private int minSamplesSplit = 10;
    private int minSamplesLeaf = 5;
    private double classificationThreshold = 0.5;

    // Estrutura interna da árvore
    private Node root;
    private int numFeatures;
    private boolean isTrained = false;

    /**
     * Nó da Árvore de Decisão (interno ou folha).
     */
    public static class Node implements Serializable {
        private static final long serialVersionUID = 1L;

        // Se interno:
        public int featureIndex = -1;
        public double splitValue = 0.0;
        public Node left;
        public Node right;

        // Se folha:
        public boolean isLeaf = false;
        public int predictedClass = 0;
        public double probabilityClass1 = 0.0;
        public int sampleCount = 0;
        public int class0Count = 0;
        public int class1Count = 0;
        public double gini = 0.0;

        public Node() {
        }
    }

    public DecisionTreeClassifier() {
    }

    public DecisionTreeClassifier(int maxDepth, int minSamplesSplit, int minSamplesLeaf) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("Profundidade máxima deve ser positiva: " + maxDepth);
        }
        if (minSamplesSplit < 2) {
            throw new IllegalArgumentException("minSamplesSplit deve ser no mínimo 2: " + minSamplesSplit);
        }
        if (minSamplesLeaf < 1) {
            throw new IllegalArgumentException("minSamplesLeaf deve ser no mínimo 1: " + minSamplesLeaf);
        }
        this.maxDepth = maxDepth;
        this.minSamplesSplit = minSamplesSplit;
        this.minSamplesLeaf = minSamplesLeaf;
    }

    /**
     * Calcula a Impureza de Gini para um conjunto de rótulos binários:
     *   Gini = 1 - (p0^2 + p1^2)
     */
    public static double computeGini(int c0, int c1) {
        int total = c0 + c1;
        if (total == 0) {
            return 0.0;
        }
        double p0 = (double) c0 / total;
        double p1 = (double) c1 / total;
        return 1.0 - (p0 * p0 + p1 * p1);
    }

    @Override
    public void train(double[][] X, int[] y) {
        if (X == null || y == null || X.length == 0) {
            throw new IllegalArgumentException("Dados de treino nulos ou vazios para Árvore de Decisão.");
        }
        if (X.length != y.length) {
            throw new IllegalArgumentException("Dimensões incompatíveis entre X (" + X.length + ") e y (" + y.length + ").");
        }

        this.numFeatures = X[0].length;
        List<Integer> sampleIndices = new ArrayList<>(X.length);
        for (int i = 0; i < X.length; i++) {
            sampleIndices.add(i);
        }

        this.root = buildTree(X, y, sampleIndices, 0);
        this.isTrained = true;
    }

    /**
     * Construção recursiva da árvore CART.
     */
    private Node buildTree(double[][] X, int[] y, List<Integer> sampleIndices, int depth) {
        Node node = new Node();
        node.sampleCount = sampleIndices.size();

        // Contagem de classes no nó
        for (int idx : sampleIndices) {
            if (y[idx] == 1) {
                node.class1Count++;
            } else {
                node.class0Count++;
            }
        }

        node.probabilityClass1 = (double) node.class1Count / node.sampleCount;
        node.predictedClass = node.probabilityClass1 >= classificationThreshold ? 1 : 0;
        node.gini = computeGini(node.class0Count, node.class1Count);

        // Critérios de Paragem (Criação de Folha):
        // 1. Atingiu a profundidade máxima permitida
        // 2. Amostras insuficientes para divisão
        // 3. Nó perfeitamente puro (Gini == 0)
        if (depth >= maxDepth || node.sampleCount < minSamplesSplit || node.gini == 0.0) {
            node.isLeaf = true;
            return node;
        }

        // Procura da melhor divisão (menor Gini ponderado)
        double bestGain = 0.0;
        int bestFeature = -1;
        double bestThreshold = 0.0;
        List<Integer> bestLeftIndices = null;
        List<Integer> bestRightIndices = null;

        for (int f = 0; f < numFeatures; f++) {
            // Extrair valores únicos ordenados da feature f
            List<Double> values = new ArrayList<>();
            for (int idx : sampleIndices) {
                values.add(X[idx][f]);
            }
            Collections.sort(values);

            // Avaliar limiares nos pontos médios entre valores consecutivos distintos
            for (int i = 0; i < values.size() - 1; i++) {
                double valA = values.get(i);
                double valB = values.get(i + 1);
                if (Math.abs(valA - valB) < 1e-9) {
                    continue; // Valores idênticos, não dividem
                }

                double threshold = (valA + valB) / 2.0;

                int leftC0 = 0, leftC1 = 0;
                int rightC0 = 0, rightC1 = 0;
                List<Integer> leftIdx = new ArrayList<>();
                List<Integer> rightIdx = new ArrayList<>();

                for (int idx : sampleIndices) {
                    if (X[idx][f] <= threshold) {
                        leftIdx.add(idx);
                        if (y[idx] == 1) leftC1++; else leftC0++;
                    } else {
                        rightIdx.add(idx);
                        if (y[idx] == 1) rightC1++; else rightC0++;
                    }
                }

                // Respeitar o mínimo de amostras por folha
                if (leftIdx.size() < minSamplesLeaf || rightIdx.size() < minSamplesLeaf) {
                    continue;
                }

                double leftGini = computeGini(leftC0, leftC1);
                double rightGini = computeGini(rightC0, rightC1);
                double splitGini = ((double) leftIdx.size() / node.sampleCount) * leftGini
                                 + ((double) rightIdx.size() / node.sampleCount) * rightGini;

                double gain = node.gini - splitGini;

                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeature = f;
                    bestThreshold = threshold;
                    bestLeftIndices = leftIdx;
                    bestRightIndices = rightIdx;
                }
            }
        }

        // Se nenhum ganho significativo for encontrado, torna-se folha
        if (bestGain <= 1e-7 || bestLeftIndices == null || bestRightIndices == null) {
            node.isLeaf = true;
            return node;
        }

        // Criar nó interno de decisão e ramificar recursivamente
        node.isLeaf = false;
        node.featureIndex = bestFeature;
        node.splitValue = bestThreshold;
        node.left = buildTree(X, y, bestLeftIndices, depth + 1);
        node.right = buildTree(X, y, bestRightIndices, depth + 1);

        return node;
    }

    @Override
    public double predictProbability(double[] x) {
        if (!isTrained || root == null) {
            throw new IllegalStateException("A Árvore de Decisão deve ser treinada antes da inferência.");
        }
        if (x == null || x.length != numFeatures) {
            throw new IllegalArgumentException("Vetor de features com dimensão incompatível.");
        }

        Node current = root;
        while (!current.isLeaf) {
            if (x[current.featureIndex] <= current.splitValue) {
                current = current.left;
            } else {
                current = current.right;
            }
        }
        return current.probabilityClass1;
    }

    @Override
    public int predict(double[] x) {
        return predictProbability(x) >= classificationThreshold ? 1 : 0;
    }

    @Override
    public String getModelName() {
        return "Decision Tree CART (Gini Index)";
    }

    /**
     * Calcula a profundidade máxima real da árvore construída.
     */
    public int getTreeDepth() {
        return getNodeDepth(root);
    }

    private int getNodeDepth(Node n) {
        if (n == null || n.isLeaf) {
            return 0;
        }
        return 1 + Math.max(getNodeDepth(n.left), getNodeDepth(n.right));
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMinSamplesSplit() {
        return minSamplesSplit;
    }

    public int getMinSamplesLeaf() {
        return minSamplesLeaf;
    }

    public boolean isTrained() {
        return isTrained;
    }

    public Node getRoot() {
        return root;
    }
}
