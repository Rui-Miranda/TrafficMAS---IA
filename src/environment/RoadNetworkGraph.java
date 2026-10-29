package environment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Grafo Rodoviário Dirigido da Rede Urbana para Navegação A* e Dijkstra (Fase 6.1 — R7).
 *
 * Modela de forma explícita e parametrizável:
 * 1. Nós ({@link IntersectionNode}) representando os cruzamentos (com coordenadas espaciais em píxeis e grelha).
 * 2. Arestas dirigidas ({@link RoadSegment}) representando os segmentos de via entre cruzamentos adjacentes.
 * 3. Direções de circulação permitidas (0=N->S, 1=S->N, 2=W->E, 3=E->W).
 * 4. Custos estritamente positivos (distância física em píxeis ou tempo estimado de viagem em ticks/segundos,
 *    acrescidos de penalizações dinâmicas não-negativas de fila/congestionamento).
 * 5. Restrições de circulação: bloqueio individual ou bidirecional de vias, bloqueio de cruzamentos e
 *    restrições de mudança de direção.
 * 6. Controlo de versão ({@link #getGraphVersion()}) para que os veículos recalculem rotas A* apenas quando
 *    ocorrerem bloqueios, alterações de custo relevantes ou novas missões, evitando recálculos em todos os ticks.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fases 6.1–6.2)
 */
public class RoadNetworkGraph {

    /** Métrica de custo utilizada nas arestas do grafo rodoviário */
    public enum CostMetric {
        /** Custo base = distância física centro-a-centro (px) + penalização de congestionamento (>= 0) */
        PHYSICAL_DISTANCE_PX,
        /** Custo base = tempo de viagem em fluxo livre (dist_px / v_max) + atraso de fila (>= 0) */
        EXPECTED_TRAVEL_TIME
    }

    /** Nó do grafo representando um cruzamento da malha urbana */
    public static final class IntersectionNode {
        public final int id;
        public final String name;
        public final int row;
        public final int col;
        public final double centerX; // Unidade: píxeis (px) no plano físico da simulação
        public final double centerY; // Unidade: píxeis (px) no plano físico da simulação
        private volatile boolean blocked = false;

        public IntersectionNode(int id, String name, int row, int col, double centerX, double centerY) {
            this.id = id;
            this.name = name;
            this.row = row;
            this.col = col;
            this.centerX = centerX;
            this.centerY = centerY;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s(id=%d, grid=[%d,%d], pos=[%.1f,%.1f]px%s)",
                    name, id, row, col, centerX, centerY, blocked ? ", BLOCKED" : "");
        }
    }

    /** Segmento de estrada dirigido entre dois cruzamentos adjacentes */
    public static final class RoadSegment {
        public final String id;
        public final int fromNodeId;
        public final int toNodeId;
        public final int direction; // 0: N->S, 1: S->N, 2: W->E, 3: E->W
        public final double lengthPx; // Distância física centro-a-centro em píxeis (> 0)
        public final double maxAllowedSpeed; // Velocidade máxima nominal em px/tick (> 0)
        private volatile double congestionPenalty = 0.0; // Penalização adicional não-negativa (>= 0)
        private volatile boolean blocked = false;
        private final Set<Integer> forbiddenNextDirections = ConcurrentHashMap.newKeySet();

        public RoadSegment(String id, int fromNodeId, int toNodeId, int direction,
                           double lengthPx, double maxAllowedSpeed) {
            if (lengthPx <= 0.0) {
                throw new IllegalArgumentException("O comprimento do segmento deve ser estritamente positivo: " + lengthPx);
            }
            if (maxAllowedSpeed <= 0.0) {
                throw new IllegalArgumentException("A velocidade máxima deve ser estritamente positiva: " + maxAllowedSpeed);
            }
            this.id = id;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.direction = direction;
            this.lengthPx = lengthPx;
            this.maxAllowedSpeed = maxAllowedSpeed;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }

        public double getCongestionPenalty() {
            return congestionPenalty;
        }

        public void setCongestionPenalty(double congestionPenalty) {
            if (congestionPenalty < 0.0 || Double.isNaN(congestionPenalty)) {
                throw new IllegalArgumentException("A penalização de custo não pode ser negativa: " + congestionPenalty);
            }
            this.congestionPenalty = congestionPenalty;
        }

        public void setNextDirectionForbidden(int nextDirection, boolean forbidden) {
            if (forbidden) {
                forbiddenNextDirections.add(nextDirection);
            } else {
                forbiddenNextDirections.remove(nextDirection);
            }
        }

        public boolean isNextDirectionAllowed(int nextDirection) {
            // Proíbe inversão de marcha (U-turn 180 graus) na mesma via e direções interditas
            if (isOppositeDirection(this.direction, nextDirection)) {
                return false;
            }
            return !forbiddenNextDirections.contains(nextDirection);
        }

        /**
         * Calcula o custo efetivo (estritamente positivo) de percorrer este segmento de estrada.
         */
        public double getEffectiveCost(CostMetric metric) {
            if (metric == CostMetric.EXPECTED_TRAVEL_TIME) {
                double baseTimeTicks = lengthPx / maxAllowedSpeed;
                return baseTimeTicks + congestionPenalty;
            }
            return lengthPx + congestionPenalty;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s[%d->%d, dir=%d, len=%.1fpx, pen=%.1f%s]",
                    id, fromNodeId, toNodeId, direction, lengthPx, congestionPenalty, blocked ? ", BLOCKED" : "");
        }
    }

    public static final double DEFAULT_NOMINAL_MAX_SPEED_PX_PER_TICK = 2.4;

    private final NetworkTopologyConfig topology;
    private final Map<Integer, IntersectionNode> nodes = new LinkedHashMap<>();
    private final Map<Integer, List<RoadSegment>> adjacencyList = new HashMap<>();
    private final Map<String, RoadSegment> segmentByPair = new ConcurrentHashMap<>();
    private volatile CostMetric activeCostMetric = CostMetric.PHYSICAL_DISTANCE_PX;
    private final AtomicLong graphVersion = new AtomicLong(1L);

    public RoadNetworkGraph(NetworkTopologyConfig topology) {
        if (topology == null) {
            throw new IllegalArgumentException("A topologia da rede não pode ser nula.");
        }
        this.topology = topology;
        buildGridGraph();
    }

    /** Constrói os nós e segmentos dirigidos a partir da topologia parametrizada (2×2 ou 3×3). */
    private void buildGridGraph() {
        int count = topology.getIntersectionCount();
        for (int i = 0; i < count; i++) {
            IntersectionNode node = new IntersectionNode(
                    i,
                    topology.getIntersectionName(i),
                    topology.getRow(i),
                    topology.getCol(i),
                    topology.getIntersectionCenterX(i),
                    topology.getIntersectionCenterY(i)
            );
            nodes.put(i, node);
            adjacencyList.put(i, new ArrayList<>());
        }

        int rows = topology.getRows();
        int cols = topology.getCols();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int u = topology.getIntersectionIndex(r, c);

                // Ligação para Sul (r + 1, c) -> Direção 0 (Norte->Sul)
                if (r + 1 < rows) {
                    int v = topology.getIntersectionIndex(r + 1, c);
                    addDirectedSegment(u, v, 0);
                }
                // Ligação para Norte (r - 1, c) -> Direção 1 (Sul->Norte)
                if (r - 1 >= 0) {
                    int v = topology.getIntersectionIndex(r - 1, c);
                    addDirectedSegment(u, v, 1);
                }
                // Ligação para Este (r, c + 1) -> Direção 2 (Oeste->Este)
                if (c + 1 < cols) {
                    int v = topology.getIntersectionIndex(r, c + 1);
                    addDirectedSegment(u, v, 2);
                }
                // Ligação para Oeste (r, c - 1) -> Direção 3 (Este->Oeste)
                if (c - 1 >= 0) {
                    int v = topology.getIntersectionIndex(r, c - 1);
                    addDirectedSegment(u, v, 3);
                }
            }
        }
    }

    private void addDirectedSegment(int fromId, int toId, int direction) {
        IntersectionNode from = nodes.get(fromId);
        IntersectionNode to = nodes.get(toId);
        double distPx = ManhattanHeuristic.computePhysicalManhattanDistance(
                from.centerX, from.centerY, to.centerX, to.centerY);

        String segId = "SEG_" + from.name + "_TO_" + to.name;
        RoadSegment seg = new RoadSegment(segId, fromId, toId, direction, distPx, DEFAULT_NOMINAL_MAX_SPEED_PX_PER_TICK);
        adjacencyList.get(fromId).add(seg);
        segmentByPair.put(pairKey(fromId, toId), seg);
    }

    private static String pairKey(int fromId, int toId) {
        return fromId + "->" + toId;
    }

    public static boolean isOppositeDirection(int dirA, int dirB) {
        return (dirA == 0 && dirB == 1) || (dirA == 1 && dirA == 0)
            || (dirA == 2 && dirB == 3) || (dirA == 3 && dirB == 2);
    }

    public NetworkTopologyConfig getTopology() {
        return topology;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getSegmentCount() {
        return segmentByPair.size();
    }

    public Collection<IntersectionNode> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public IntersectionNode getNode(int nodeId) {
        return nodes.get(nodeId);
    }

    public List<RoadSegment> getOutgoingSegments(int nodeId) {
        List<RoadSegment> list = adjacencyList.get(nodeId);
        return (list != null) ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public Collection<RoadSegment> getAllSegments() {
        return Collections.unmodifiableCollection(segmentByPair.values());
    }

    public RoadSegment getSegment(int fromNodeId, int toNodeId) {
        return segmentByPair.get(pairKey(fromNodeId, toNodeId));
    }

    public CostMetric getActiveCostMetric() {
        return activeCostMetric;
    }

    public void setActiveCostMetric(CostMetric metric) {
        if (metric != null && metric != this.activeCostMetric) {
            this.activeCostMetric = metric;
            graphVersion.incrementAndGet();
        }
    }

    public long getGraphVersion() {
        return graphVersion.get();
    }

    /** Bloqueia ou desbloqueia um segmento dirigido específico e incrementa a versão do grafo. */
    public boolean setSegmentBlocked(int fromNodeId, int toNodeId, boolean blocked) {
        RoadSegment seg = getSegment(fromNodeId, toNodeId);
        if (seg == null) return false;
        if (seg.isBlocked() != blocked) {
            seg.setBlocked(blocked);
            graphVersion.incrementAndGet();
        }
        return true;
    }

    /** Bloqueia ou desbloqueia a ligação em ambos os sentidos entre dois cruzamentos adjacentes. */
    public boolean setBidirectionalRoadBlocked(int nodeA, int nodeB, boolean blocked) {
        boolean a = setSegmentBlocked(nodeA, nodeB, blocked);
        boolean b = setSegmentBlocked(nodeB, nodeA, blocked);
        return a || b;
    }

    /** Bloqueia ou desbloqueia um nó (cruzamento inteiro) e incrementa a versão do grafo. */
    public boolean setNodeBlocked(int nodeId, boolean blocked) {
        IntersectionNode node = nodes.get(nodeId);
        if (node == null) return false;
        if (node.isBlocked() != blocked) {
            node.setBlocked(blocked);
            graphVersion.incrementAndGet();
        }
        return true;
    }

    /** Define a penalização de custo (ex: fila/atraso) num segmento dirigido. */
    public boolean setSegmentCongestionPenalty(int fromNodeId, int toNodeId, double penalty) {
        RoadSegment seg = getSegment(fromNodeId, toNodeId);
        if (seg == null) return false;
        double old = seg.getCongestionPenalty();
        seg.setCongestionPenalty(penalty);
        if (Math.abs(old - penalty) >= 5.0) {
            graphVersion.incrementAndGet();
        }
        return true;
    }

    /** Limpa todos os bloqueios e penalizações, restaurando o grafo ao estado nominal. */
    public void resetRestrictionsAndPenalties() {
        for (IntersectionNode n : nodes.values()) {
            n.setBlocked(false);
        }
        for (RoadSegment s : segmentByPair.values()) {
            s.setBlocked(false);
            s.setCongestionPenalty(0.0);
            s.forbiddenNextDirections.clear();
        }
        graphVersion.incrementAndGet();
    }

    /**
     * Determina a manobra física ({@link CitySimulationGUI.ManeuverIntent}) necessária num cruzamento
     * para passar da direção de entrada {@code incomingDirection} para a direção de saída {@code outgoingDirection}.
     *
     * Convenção de direções:
     * - 0: Norte -> Sul (Desce, +Y)
     * - 1: Sul -> Norte (Sobe, -Y)
     * - 2: Oeste -> Este (Direita, +X)
     * - 3: Este -> Oeste (Esquerda, -X)
     */
    public static CitySimulationGUI.ManeuverIntent determineManeuver(int incomingDirection, int outgoingDirection) {
        if (incomingDirection == outgoingDirection) {
            return CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
        }
        switch (incomingDirection) {
            case 0: // N->S
                if (outgoingDirection == 3) return CitySimulationGUI.ManeuverIntent.TURN_RIGHT; // Vira para Oeste
                if (outgoingDirection == 2) return CitySimulationGUI.ManeuverIntent.TURN_LEFT;  // Vira para Este
                break;
            case 1: // S->N
                if (outgoingDirection == 2) return CitySimulationGUI.ManeuverIntent.TURN_RIGHT; // Vira para Este
                if (outgoingDirection == 3) return CitySimulationGUI.ManeuverIntent.TURN_LEFT;  // Vira para Oeste
                break;
            case 2: // W->E
                if (outgoingDirection == 0) return CitySimulationGUI.ManeuverIntent.TURN_RIGHT; // Vira para Sul
                if (outgoingDirection == 1) return CitySimulationGUI.ManeuverIntent.TURN_LEFT;  // Vira para Norte
                break;
            case 3: // E->W
                if (outgoingDirection == 1) return CitySimulationGUI.ManeuverIntent.TURN_RIGHT; // Vira para Norte
                if (outgoingDirection == 0) return CitySimulationGUI.ManeuverIntent.TURN_LEFT;  // Vira para Sul
                break;
        }
        return CitySimulationGUI.ManeuverIntent.GO_STRAIGHT;
    }
}

