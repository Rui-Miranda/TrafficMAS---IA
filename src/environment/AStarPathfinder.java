package environment;

import environment.CitySimulationGUI.ManeuverIntent;
import environment.RoadNetworkGraph.CostMetric;
import environment.RoadNetworkGraph.IntersectionNode;
import environment.RoadNetworkGraph.RoadSegment;

import java.util.*;

/**
 * Motor de Navegação e Procura de Caminhos Ótimos com Algoritmo A* e Referência Dijkstra (Fase 6.1 — R7, R8).
 *
 * Implementa de forma completa e verificável:
 * 1. Algoritmo A* ({@code f(n) = g(n) + h(n)}) sobre o {@link RoadNetworkGraph}, utilizando a
 *    heurística admissível de {@link ManhattanHeuristic}.
 * 2. Algoritmo de Dijkstra independente ({@code f(n) = g(n)}) como referência formal para validação
 *    cruzada da otimalidade do custo das rotas.
 * 3. Respeito estrito por vias bloqueadas, nós bloqueados, restrições de direção e custos positivos.
 * 4. Tradução automática do caminho de nós/arestas numa sequência de manobras cinemáticas
 *    ({@link ManeuverIntent#GO_STRAIGHT}, {@link ManeuverIntent#TURN_RIGHT}, {@link ManeuverIntent#TURN_LEFT})
 *    para execução física pelos agentes de veículos nos cruzamentos.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.1)
 */
public final class AStarPathfinder {

    /** Resultado imutável de um cálculo de rota (A* ou Dijkstra) */
    public static final class RouteResult {
        public final String algorithmName;
        public final boolean reachable;
        public final int originNodeId;
        public final int destinationNodeId;
        public final double totalCost;
        public final List<Integer> nodePath;
        public final List<RoadSegment> segmentPath;
        public final List<ManeuverIntent> maneuversAtNodes;
        public final int expandedNodesCount;
        public final long graphVersion;

        public RouteResult(String algorithmName, boolean reachable,
                           int originNodeId, int destinationNodeId,
                           double totalCost,
                           List<Integer> nodePath,
                           List<RoadSegment> segmentPath,
                           List<ManeuverIntent> maneuversAtNodes,
                           int expandedNodesCount,
                           long graphVersion) {
            this.algorithmName = algorithmName;
            this.reachable = reachable;
            this.originNodeId = originNodeId;
            this.destinationNodeId = destinationNodeId;
            this.totalCost = totalCost;
            this.nodePath = Collections.unmodifiableList(new ArrayList<>(nodePath));
            this.segmentPath = Collections.unmodifiableList(new ArrayList<>(segmentPath));
            this.maneuversAtNodes = Collections.unmodifiableList(new ArrayList<>(maneuversAtNodes));
            this.expandedNodesCount = expandedNodesCount;
            this.graphVersion = graphVersion;
        }

        public static RouteResult unreachable(String algorithmName, int originNodeId, int destinationNodeId,
                                              int expandedNodesCount, long graphVersion) {
            return new RouteResult(
                    algorithmName, false, originNodeId, destinationNodeId,
                    Double.POSITIVE_INFINITY,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    expandedNodesCount, graphVersion
            );
        }

        /**
         * Devolve a manobra planeada para um determinado passo do caminho de nós.
         */
        public ManeuverIntent getManeuverAtStep(int stepIndex) {
            if (stepIndex >= 0 && stepIndex < maneuversAtNodes.size()) {
                return maneuversAtNodes.get(stepIndex);
            }
            return ManeuverIntent.GO_STRAIGHT;
        }

        /**
         * Devolve a manobra planeada ao abordar um cruzamento específico presente na rota.
         */
        public ManeuverIntent getManeuverForIntersection(int intersectionId) {
            for (int i = 0; i < nodePath.size(); i++) {
                if (nodePath.get(i) == intersectionId) {
                    return getManeuverAtStep(i);
                }
            }
            return ManeuverIntent.GO_STRAIGHT;
        }

        /**
         * Verifica se algum segmento pertencente a esta rota ficou entretanto bloqueado no grafo.
         */
        public boolean hasAnyBlockedSegment(RoadNetworkGraph graph) {
            if (!reachable || graph == null) return true;
            for (int nodeId : nodePath) {
                IntersectionNode n = graph.getNode(nodeId);
                if (n == null || n.isBlocked()) return true;
            }
            for (RoadSegment seg : segmentPath) {
                RoadSegment liveSeg = graph.getSegment(seg.fromNodeId, seg.toNodeId);
                if (liveSeg == null || liveSeg.isBlocked()) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            if (!reachable) {
                return String.format(Locale.US, "%s[UNREACHABLE %d -> %d, expanded=%d]",
                        algorithmName, originNodeId, destinationNodeId, expandedNodesCount);
            }
            return String.format(Locale.US, "%s[path=%s, maneuvers=%s, cost=%.2f, expanded=%d, v=%d]",
                    algorithmName, nodePath, maneuversAtNodes, totalCost, expandedNodesCount, graphVersion);
        }
    }

    /** Entrada na fila de prioridade do A* / Dijkstra */
    private static final class SearchEntry implements Comparable<SearchEntry> {
        final int nodeId;
        final double gScore;
        final double fScore;

        SearchEntry(int nodeId, double gScore, double fScore) {
            this.nodeId = nodeId;
            this.gScore = gScore;
            this.fScore = fScore;
        }

        @Override
        public int compareTo(SearchEntry other) {
            int cmp = Double.compare(this.fScore, other.fScore);
            if (cmp != 0) return cmp;
            cmp = Double.compare(this.gScore, other.gScore);
            if (cmp != 0) return cmp;
            return Integer.compare(this.nodeId, other.nodeId);
        }
    }

    private AStarPathfinder() {}

    /**
     * Calcula a rota ótima entre {@code originNodeId} e {@code destinationNodeId} usando o algoritmo A*
     * com a heurística de Manhattan admissível por defeito.
     */
    public static RouteResult findPathAStar(RoadNetworkGraph graph, int originNodeId, int destinationNodeId) {
        return findPathAStar(graph, originNodeId, destinationNodeId, -1,
                new ManhattanHeuristic(ManhattanHeuristic.HeuristicMode.ADAPTIVE_ADMISSIBLE));
    }

    /**
     * Calcula a rota ótima usando o algoritmo A* considerando a direção de entrada inicial no primeiro cruzamento.
     */
    public static RouteResult findPathAStar(RoadNetworkGraph graph, int originNodeId, int destinationNodeId,
                                            int initialEntryDirection) {
        return findPathAStar(graph, originNodeId, destinationNodeId, initialEntryDirection,
                new ManhattanHeuristic(ManhattanHeuristic.HeuristicMode.ADAPTIVE_ADMISSIBLE));
    }

    /**
     * Implementação completa do algoritmo A* (f(n) = g(n) + h(n)).
     *
     * @param graph Grafo rodoviário dirigido
     * @param originNodeId Índice do cruzamento de origem
     * @param destinationNodeId Índice do cruzamento de destino
     * @param initialEntryDirection Direção de aproximação ao nó de origem (0..3) ou -1 se livre
     * @param heuristic Instância de {@link ManhattanHeuristic}
     * @return {@link RouteResult} contendo o percurso, segmentos, manobras e custo ótimo
     */
    public static RouteResult findPathAStar(RoadNetworkGraph graph, int originNodeId, int destinationNodeId,
                                            int initialEntryDirection, ManhattanHeuristic heuristic) {
        if (graph == null) {
            throw new IllegalArgumentException("O grafo rodoviário não pode ser nulo.");
        }
        ManhattanHeuristic activeHeuristic = (heuristic != null)
                ? heuristic
                : new ManhattanHeuristic(ManhattanHeuristic.HeuristicMode.ADAPTIVE_ADMISSIBLE);
        return executeBestFirstSearch("A*", graph, originNodeId, destinationNodeId, initialEntryDirection, activeHeuristic);
    }

    /**
     * Referência independente de caminho mais curto usando o Algoritmo de Dijkstra (custo uniforme sem heurística).
     * Utilizada para validar formalmente a otimalidade do custo encontrado pelo A*.
     */
    public static RouteResult findPathDijkstra(RoadNetworkGraph graph, int originNodeId, int destinationNodeId) {
        return findPathDijkstra(graph, originNodeId, destinationNodeId, -1);
    }

    /**
     * Referência independente de Dijkstra com direção de entrada inicial.
     */
    public static RouteResult findPathDijkstra(RoadNetworkGraph graph, int originNodeId, int destinationNodeId,
                                               int initialEntryDirection) {
        if (graph == null) {
            throw new IllegalArgumentException("O grafo rodoviário não pode ser nulo.");
        }
        return executeBestFirstSearch("DIJKSTRA", graph, originNodeId, destinationNodeId, initialEntryDirection, null);
    }

    /**
     * Motor formal de procura de custo mínimo (A* quando {@code heuristic != null}, Dijkstra quando {@code heuristic == null}).
     */
    private static RouteResult executeBestFirstSearch(String algorithmName,
                                                      RoadNetworkGraph graph,
                                                      int originNodeId,
                                                      int destinationNodeId,
                                                      int initialEntryDirection,
                                                      ManhattanHeuristic heuristic) {
        long version = graph.getGraphVersion();
        IntersectionNode startNode = graph.getNode(originNodeId);
        IntersectionNode goalNode = graph.getNode(destinationNodeId);

        if (startNode == null || goalNode == null || startNode.isBlocked() || goalNode.isBlocked()) {
            return RouteResult.unreachable(algorithmName, originNodeId, destinationNodeId, 0, version);
        }

        if (originNodeId == destinationNodeId) {
            return new RouteResult(
                    algorithmName, true, originNodeId, destinationNodeId, 0.0,
                    Collections.singletonList(originNodeId),
                    Collections.emptyList(),
                    Collections.singletonList(ManeuverIntent.GO_STRAIGHT),
                    1, version
            );
        }

        CostMetric costMetric = graph.getActiveCostMetric();
        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> cameFromNode = new HashMap<>();
        Map<Integer, RoadSegment> cameFromSegment = new HashMap<>();
        Set<Integer> closedSet = new HashSet<>();
        PriorityQueue<SearchEntry> openQueue = new PriorityQueue<>();

        gScore.put(originNodeId, 0.0);
        double hStart = (heuristic != null) ? heuristic.estimateRemainingCost(startNode, goalNode, graph) : 0.0;
        openQueue.add(new SearchEntry(originNodeId, 0.0, hStart));

        int expandedCount = 0;

        while (!openQueue.isEmpty()) {
            SearchEntry current = openQueue.poll();
            int u = current.nodeId;

            if (closedSet.contains(u)) {
                continue;
            }
            closedSet.add(u);
            expandedCount++;

            if (u == destinationNodeId) {
                return reconstructRoute(algorithmName, originNodeId, destinationNodeId,
                        initialEntryDirection, current.gScore, cameFromNode, cameFromSegment,
                        expandedCount, version);
            }

            RoadSegment incomingSeg = cameFromSegment.get(u);
            int incomingDir = (incomingSeg != null) ? incomingSeg.direction : initialEntryDirection;

            for (RoadSegment seg : graph.getOutgoingSegments(u)) {
                if (seg.isBlocked()) {
                    continue;
                }
                int v = seg.toNodeId;
                IntersectionNode targetNode = graph.getNode(v);
                if (targetNode == null || targetNode.isBlocked() || closedSet.contains(v)) {
                    continue;
                }

                // Verifica restrições de direção (proibição de inversão de marcha U-turn e viragens interditas)
                if (incomingSeg != null) {
                    if (!incomingSeg.isNextDirectionAllowed(seg.direction)) {
                        continue;
                    }
                } else if (incomingDir >= 0) {
                    if (RoadNetworkGraph.isOppositeDirection(incomingDir, seg.direction)) {
                        continue;
                    }
                }

                double edgeCost = seg.getEffectiveCost(costMetric);
                if (edgeCost <= 0.0 || Double.isNaN(edgeCost) || Double.isInfinite(edgeCost)) {
                    throw new IllegalStateException("Custo de aresta inválido (deve ser > 0): " + seg);
                }

                double tentativeG = current.gScore + edgeCost;
                double currentBestG = gScore.getOrDefault(v, Double.POSITIVE_INFINITY);

                if (tentativeG + 1e-12 < currentBestG) {
                    gScore.put(v, tentativeG);
                    cameFromNode.put(v, u);
                    cameFromSegment.put(v, seg);
                    double hVal = (heuristic != null) ? heuristic.estimateRemainingCost(targetNode, goalNode, graph) : 0.0;
                    openQueue.add(new SearchEntry(v, tentativeG, tentativeG + hVal));
                }
            }
        }

        return RouteResult.unreachable(algorithmName, originNodeId, destinationNodeId, expandedCount, version);
    }

    private static RouteResult reconstructRoute(String algorithmName,
                                                int originNodeId,
                                                int destinationNodeId,
                                                int initialEntryDirection,
                                                double totalCost,
                                                Map<Integer, Integer> cameFromNode,
                                                Map<Integer, RoadSegment> cameFromSegment,
                                                int expandedCount,
                                                long version) {
        LinkedList<Integer> nodePath = new LinkedList<>();
        LinkedList<RoadSegment> segmentPath = new LinkedList<>();

        int curr = destinationNodeId;
        nodePath.addFirst(curr);
        while (curr != originNodeId) {
            RoadSegment seg = cameFromSegment.get(curr);
            Integer prev = cameFromNode.get(curr);
            if (seg == null || prev == null) {
                return RouteResult.unreachable(algorithmName, originNodeId, destinationNodeId, expandedCount, version);
            }
            segmentPath.addFirst(seg);
            nodePath.addFirst(prev);
            curr = prev;
        }

        // Calcula a sequência de manobras em cada cruzamento do percurso
        List<ManeuverIntent> maneuvers = new ArrayList<>(nodePath.size());
        int currentDir = initialEntryDirection;

        for (int i = 0; i < nodePath.size(); i++) {
            if (i < segmentPath.size()) {
                int outDir = segmentPath.get(i).direction;
                if (currentDir < 0) {
                    maneuvers.add(ManeuverIntent.GO_STRAIGHT);
                } else {
                    maneuvers.add(RoadNetworkGraph.determineManeuver(currentDir, outDir));
                }
                currentDir = outDir;
            } else {
                // No nó final de destino, o veículo atravessa em frente ao concluir a rota
                maneuvers.add(ManeuverIntent.GO_STRAIGHT);
            }
        }

        return new RouteResult(
                algorithmName, true, originNodeId, destinationNodeId, totalCost,
                nodePath, segmentPath, maneuvers, expandedCount, version
        );
    }
}

