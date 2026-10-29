package environment;

import environment.RoadNetworkGraph.CostMetric;
import environment.RoadNetworkGraph.IntersectionNode;
import environment.RoadNetworkGraph.RoadSegment;

/**
 * Serviço de Cálculo da Distância de Manhattan e Heurística Admissível para o A* (Fases 6.1–6.2 — R8).
 *
 * <h3>1. Definição Matemática</h3>
 * <pre>
 *   d(P1, P2) = |x1 - x2| + |y1 - y2|
 * </pre>
 *
 * <h3>2. Significado das Coordenadas e Unidades</h3>
 * <ul>
 *   <li><b>Coordenadas Físicas Espaciais {@code (centerX, centerY)}</b>: expressas em <b>píxeis (px)</b>
 *       no referencial cartesiano 2D da malha urbana (origem no canto superior esquerdo, eixo X para Este,
 *       eixo Y para Sul). Na topologia 3×3, os centros dos cruzamentos estão espaçados de {@code 310.0 px}
 *       horizontal e verticalmente; na topologia 2×2, estão espaçados de {@code 360.0 px}.</li>
 *   <li><b>Coordenadas Topológicas de Grelha {@code (row, col)}</b>: índices inteiros adimensionais
 *       da linha ({@code 0 .. rows-1}) e coluna ({@code 0 .. cols-1}), onde {@code |r1 - r2| + |c1 - c2|}
 *       representa o número mínimo de segmentos rodoviários (saltos/hops) entre dois nós na grelha ortogonal.</li>
 *   <li><b>Unidades de Tempo de Viagem</b>: quando o custo do grafo é expresso em tempo de viagem
 *       ({@link CostMetric#EXPECTED_TRAVEL_TIME}, em ticks de simulação a 30 Hz), a distância física em píxeis
 *       é escalada pelo limite inferior válido de tempo por unidade de distância ({@code 1.0 / v_max_admissivel}),
 *       isto é: {@code h_tempo = (|x1 - x2| + |y1 - y2|) / v_max}.</li>
 * </ul>
 *
 * <h3>3. Garantia de Admissibilidade e Consistência</h3>
 * <ul>
 *   <li>No modo {@link CostMetric#PHYSICAL_DISTANCE_PX}, cada aresta ortogonal {@code (u, v)} tem custo
 *       {@code c(u, v) = lengthPx + congestionPenalty >= |x_u - x_v| + |y_u - y_v|}, pelo que
 *       {@code h(n) = |x_n - x_goal| + |y_n - y_goal|} nunca sobrestima o custo real ({@code h(n) <= h*(n)}).</li>
 *   <li>No modo {@link CostMetric#EXPECTED_TRAVEL_TIME}, a heurística utiliza a maior velocidade máxima
 *       permitida em toda a rede ({@code maxNetworkSpeed}), garantindo {@code h(n) = d_px / maxNetworkSpeed <= c*(n)}.</li>
 *   <li>Se a verificação de admissibilidade detetar qualquer aresta onde a heurística possa sobrestimar o custo
 *       ou se for solicitado o modo seguro, é utilizada a <b>heurística nula ({@code h = 0.0})</b> como alternativa segura.</li>
 * </ul>
 *
 * <h3>4. Separação Estrita face à Segurança Física e Colisões</h3>
 * A distância de Manhattan é utilizada exclusivamente para guiar a procura de rotas no grafo de cruzamentos
 * e <b>nunca substitui</b> a distância longitudinal física na faixa, a verificação de faixa nem as regras
 * de travagem e anti-colisão entre veículos.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.1)
 */
public final class ManhattanHeuristic {

    /** Modos de operação da heurística do A* */
    public enum HeuristicMode {
        /** Adapta automaticamente a escala da distância de Manhattan à métrica de custo do grafo (com fallback seguro) */
        ADAPTIVE_ADMISSIBLE,
        /** Heurística nula (h = 0.0): alternativa segura equivalente ao algoritmo de Dijkstra */
        NULL_FALLBACK
    }

    private final HeuristicMode mode;

    public ManhattanHeuristic() {
        this(HeuristicMode.ADAPTIVE_ADMISSIBLE);
    }

    public ManhattanHeuristic(HeuristicMode mode) {
        this.mode = (mode != null) ? mode : HeuristicMode.ADAPTIVE_ADMISSIBLE;
    }

    public HeuristicMode getMode() {
        return mode;
    }

    /**
     * Calcula a distância de Manhattan exata entre dois pontos reais:
     * {@code d = |x1 - x2| + |y1 - y2|}.
     *
     * @param x1 Coordenada X do ponto 1 (px)
     * @param y1 Coordenada Y do ponto 1 (px)
     * @param x2 Coordenada X do ponto 2 (px)
     * @param y2 Coordenada Y do ponto 2 (px)
     * @return Distância de Manhattan em píxeis (sempre >= 0.0)
     */
    public static double computePhysicalManhattanDistance(double x1, double y1, double x2, double y2) {
        if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) {
            throw new IllegalArgumentException("As coordenadas para cálculo de Manhattan não podem ser NaN.");
        }
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    /**
     * Calcula a distância de Manhattan entre dois nós do grafo em píxeis (px).
     */
    public static double computeNodeManhattanDistancePx(IntersectionNode a, IntersectionNode b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Os nós de origem e destino não podem ser nulos.");
        }
        return computePhysicalManhattanDistance(a.centerX, a.centerY, b.centerX, b.centerY);
    }

    /**
     * Calcula a distância de Manhattan em unidades de grelha topológica (número mínimo de segmentos/hops):
     * {@code d_grid = |row1 - row2| + |col1 - col2|}.
     */
    public static int computeGridManhattanDistance(int row1, int col1, int row2, int col2) {
        return Math.abs(row1 - row2) + Math.abs(col1 - col2);
    }

    /**
     * Calcula a distância de Manhattan em unidades de grelha entre dois nós do grafo.
     */
    public static int computeNodeGridManhattanDistance(IntersectionNode a, IntersectionNode b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Os nós de origem e destino não podem ser nulos.");
        }
        return computeGridManhattanDistance(a.row, a.col, b.row, b.col);
    }

    /**
     * Calcula a distância de Manhattan escalada para tempo mínimo de viagem:
     * {@code h_time = (|x1 - x2| + |y1 - y2|) / maxSpeed}.
     *
     * @param x1 Coordenada X1 (px)
     * @param y1 Coordenada Y1 (px)
     * @param x2 Coordenada X2 (px)
     * @param y2 Coordenada Y2 (px)
     * @param maxAdmissibleSpeed Limite superior válido da velocidade (px/unidade de tempo, > 0)
     * @return Limite inferior válido para o tempo de viagem restante
     */
    public static double computeScaledTravelTimeHeuristic(double x1, double y1, double x2, double y2,
                                                          double maxAdmissibleSpeed) {
        if (maxAdmissibleSpeed <= 0.0 || Double.isNaN(maxAdmissibleSpeed) || Double.isInfinite(maxAdmissibleSpeed)) {
            return 0.0; // Alternativa segura (heurística nula) se a velocidade limite for inválida
        }
        double distPx = computePhysicalManhattanDistance(x1, y1, x2, y2);
        return distPx / maxAdmissibleSpeed;
    }

    /**
     * Estima o custo restante {@code h(current, goal)} de forma garantidamente admissível para o grafo dado.
     * Se a topologia ou os custos não garantirem admissibilidade, devolve {@code 0.0} (heurística nula).
     */
    public double estimateRemainingCost(IntersectionNode current, IntersectionNode goal, RoadNetworkGraph graph) {
        if (mode == HeuristicMode.NULL_FALLBACK || current == null || goal == null || graph == null) {
            return 0.0;
        }
        if (current.id == goal.id) {
            return 0.0;
        }

        double rawManhattanPx = computeNodeManhattanDistancePx(current, goal);
        CostMetric metric = graph.getActiveCostMetric();

        if (metric == CostMetric.PHYSICAL_DISTANCE_PX) {
            // Verifica o fator mínimo de custo por píxel de Manhattan nas arestas ativas
            double minCostRatio = computeMinEdgeToManhattanRatio(graph, CostMetric.PHYSICAL_DISTANCE_PX);
            if (minCostRatio <= 0.0) {
                return 0.0; // Fallback seguro para heurística nula
            }
            // Se minCostRatio < 1.0, escala por minCostRatio para nunca sobrestimar; se >= 1.0, escala por 1.0
            double safeScale = Math.min(1.0, minCostRatio);
            return rawManhattanPx * safeScale;
        } else if (metric == CostMetric.EXPECTED_TRAVEL_TIME) {
            double maxSpeed = computeMaxSpeedInGraph(graph);
            if (maxSpeed <= 0.0) {
                return 0.0;
            }
            // Verifica que nenhuma aresta tem custo inferior a d_Manhattan / maxSpeed
            double candidate = rawManhattanPx / maxSpeed;
            double minTimeRatio = computeMinEdgeToManhattanRatio(graph, CostMetric.EXPECTED_TRAVEL_TIME);
            if (minTimeRatio <= 0.0) {
                return 0.0;
            }
            return Math.min(candidate, rawManhattanPx * minTimeRatio);
        }

        return 0.0;
    }

    /**
     * Verifica formalmente se a heurística configurada é consistente (monótona) e admissível
     * em todas as arestas ativas do grafo: {@code h(u, goal) <= cost(u, v) + h(v, goal)}.
     */
    public boolean isConsistentOnGraph(RoadNetworkGraph graph) {
        if (graph == null) return true;
        for (IntersectionNode goal : graph.getNodes()) {
            for (RoadSegment seg : graph.getAllSegments()) {
                if (seg.isBlocked()) continue;
                IntersectionNode u = graph.getNode(seg.fromNodeId);
                IntersectionNode v = graph.getNode(seg.toNodeId);
                if (u == null || v == null || u.isBlocked() || v.isBlocked()) continue;

                double hU = estimateRemainingCost(u, goal, graph);
                double hV = estimateRemainingCost(v, goal, graph);
                double edgeCost = seg.getEffectiveCost(graph.getActiveCostMetric());

                if (hU > edgeCost + hV + 1e-7) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double computeMaxSpeedInGraph(RoadNetworkGraph graph) {
        double maxSpeed = 0.0;
        for (RoadSegment seg : graph.getAllSegments()) {
            if (!seg.isBlocked() && seg.maxAllowedSpeed > maxSpeed) {
                maxSpeed = seg.maxAllowedSpeed;
            }
        }
        return maxSpeed;
    }

    private static double computeMinEdgeToManhattanRatio(RoadNetworkGraph graph, CostMetric metric) {
        double minRatio = Double.POSITIVE_INFINITY;
        for (RoadSegment seg : graph.getAllSegments()) {
            if (seg.isBlocked()) continue;
            IntersectionNode u = graph.getNode(seg.fromNodeId);
            IntersectionNode v = graph.getNode(seg.toNodeId);
            if (u == null || v == null) continue;
            double dManhattan = computeNodeManhattanDistancePx(u, v);
            if (dManhattan <= 1e-9) continue;
            double cost = seg.getEffectiveCost(metric);
            if (cost <= 0.0) return 0.0;
            double ratio = cost / dManhattan;
            if (ratio < minRatio) {
                minRatio = ratio;
            }
        }
        return Double.isInfinite(minRatio) ? 0.0 : minRatio;
    }
}

