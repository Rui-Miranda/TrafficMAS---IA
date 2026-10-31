package environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representação Explícita e Canónica de um Corredor Viário na Rede (Fase 6.5-A).
 * 
 * Modela os 6 corredores principais da topologia 3x3:
 * - 3 Corredores Horizontais:
 *     H1: C1_NW -> C2_N  -> C3_NE (Eixo EW, sentido Oeste->Este e inverso)
 *     H2: C4_W  -> C5_C  -> C6_E  (Eixo EW, sentido Oeste->Este e inverso)
 *     H3: C7_SW -> C8_S  -> C9_SE (Eixo EW, sentido Oeste->Este e inverso)
 * - 3 Corredores Verticais:
 *     V1: C1_NW -> C4_W  -> C7_SW (Eixo NS, sentido Norte->Sul e inverso)
 *     V2: C2_N  -> C5_C  -> C8_S  (Eixo NS, sentido Norte->Sul e inverso)
 *     V3: C3_NE -> C6_E  -> C9_SE (Eixo NS, sentido Norte->Sul e inverso)
 * 
 * Compatível retroativamente com 2x2.
 */
public final class CorridorDefinition {

    public enum CorridorType {
        HORIZONTAL(1), // Eixo Este-Oeste (axis 1)
        VERTICAL(0);   // Eixo Norte-Sul (axis 0)

        public final int axis;

        CorridorType(int axis) {
            this.axis = axis;
        }
    }

    private final String id;
    private final String displayName;
    private final CorridorType type;
    private final List<Integer> intersectionIndices; // nós em sequência ordenada
    private final boolean forwardDirection; // true = W->E ou N->S; false = E->W ou S->N

    public CorridorDefinition(String id, String displayName, CorridorType type,
                              List<Integer> intersectionIndices, boolean forwardDirection) {
        if (id == null || intersectionIndices == null || intersectionIndices.size() < 2) {
            throw new IllegalArgumentException("Corredor requer ID e pelo menos 2 cruzamentos.");
        }
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.intersectionIndices = Collections.unmodifiableList(new ArrayList<>(intersectionIndices));
        this.forwardDirection = forwardDirection;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public CorridorType getType() {
        return type;
    }

    public int getAxis() {
        return type.axis;
    }

    public List<Integer> getIntersectionIndices() {
        return intersectionIndices;
    }

    public int getNodeCount() {
        return intersectionIndices.size();
    }

    public boolean isForwardDirection() {
        return forwardDirection;
    }

    public boolean containsIntersection(int intersectionIndex) {
        return intersectionIndices.contains(intersectionIndex);
    }

    public int getIndexOfIntersection(int intersectionIndex) {
        return intersectionIndices.indexOf(intersectionIndex);
    }

    /**
     * Calcula a distância física geométrica em pixels entre dois nós consecutivos do corredor
     * com base nas coordenadas parametrizadas de {@link NetworkTopologyConfig}.
     */
    public double getSegmentDistancePx(int stepIndex, NetworkTopologyConfig topology) {
        if (stepIndex < 0 || stepIndex >= intersectionIndices.size() - 1) {
            return 0.0;
        }
        int fromIdx = intersectionIndices.get(stepIndex);
        int toIdx = intersectionIndices.get(stepIndex + 1);

        double x1 = topology.getIntersectionCenterX(fromIdx);
        double y1 = topology.getIntersectionCenterY(fromIdx);
        double x2 = topology.getIntersectionCenterX(toIdx);
        double y2 = topology.getIntersectionCenterY(toIdx);

        return Math.hypot(x2 - x1, y2 - y1);
    }

    /**
     * Calcula a velocidade de progressão estimada em px/s a partir da velocidade em px/tick
     * e multiplicador meteorológico/aderência de piso.
     * 
     * @param speedPxPerTick Velocidade nominal em pixels/tick (ex: 2.0 px/tick)
     * @param speedMultiplier Multiplicador meteorológico (ex: 1.0 DRY, 0.8 WET, 0.6 SNOW, 0.4 ICY)
     * @return Velocidade efetiva em px/s (1 tick = 100ms -> 10 ticks/s)
     */
    public static double calculateProgressionSpeedPxPerSec(double speedPxPerTick, double speedMultiplier) {
        double effSpeedPxTick = Math.max(0.5, speedPxPerTick * Math.max(0.2, speedMultiplier));
        return effSpeedPxTick * 10.0; // 10 ticks por segundo no SimulationClock
    }

    /**
     * Calcula o tempo de viagem / offset físico em milissegundos:
     * travelTimeMs = (segmentDistancePx / progressionSpeedPxPerSec) * 1000.0
     * 
     * Hipóteses:
     * 1. A velocidade de progressão estimada é contínua e já reflete a influência do piso.
     * 2. Unidades estritamente coerentes: distância em px, velocidade em px/s, tempo em ms de SimulationClock.
     * 3. Limites de segurança: mínimo 500ms, máximo 60000ms.
     * 
     * @param segmentDistancePx Distância física do segmento em pixels
     * @param progressionSpeedPxPerSec Velocidade efetiva em pixels por segundo
     * @return Offset em milissegundos
     */
    public static long calculateTravelTimeOffsetMs(double segmentDistancePx, double progressionSpeedPxPerSec) {
        if (segmentDistancePx <= 0.0) return 0L;
        double safeSpeed = Math.max(1.0, progressionSpeedPxPerSec);
        double travelTimeSec = segmentDistancePx / safeSpeed;
        long travelTimeMs = Math.round(travelTimeSec * 1000.0);
        return Math.max(500L, Math.min(60000L, travelTimeMs));
    }

    /**
     * Encontra um corredor canónico pelo seu identificador (ex: "H1", "V2", "H1_REV").
     */
    public static CorridorDefinition findCorridorById(String id, NetworkTopologyConfig topology) {
        if (id == null) return null;
        for (CorridorDefinition cd : getCanonicalCorridors(topology)) {
            if (cd.getId().equalsIgnoreCase(id)) {
                return cd;
            }
        }
        return null;
    }

    /**
     * Gera os corredores canónicos para a topologia ativa do sistema.
     */
    public static List<CorridorDefinition> getCanonicalCorridors(NetworkTopologyConfig topology) {
        List<CorridorDefinition> list = new ArrayList<>();
        if (topology == null) return list;

        int rows = topology.getRows();
        int cols = topology.getCols();

        // Corredores Horizontais (um por cada linha)
        for (int r = 0; r < rows; r++) {
            List<Integer> forward = new ArrayList<>();
            List<Integer> reverse = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                int node = topology.getIntersectionIndex(r, c);
                forward.add(node);
            }
            for (int c = cols - 1; c >= 0; c--) {
                int node = topology.getIntersectionIndex(r, c);
                reverse.add(node);
            }

            String idH = "H" + (r + 1);
            String nameH = String.format("Corredor Horizontal %d (Linha %d)", (r + 1), (r + 1));
            list.add(new CorridorDefinition(idH, nameH, CorridorType.HORIZONTAL, forward, true));
            list.add(new CorridorDefinition(idH + "_REV", nameH + " [Inverso]", CorridorType.HORIZONTAL, reverse, false));
        }

        // Corredores Verticais (um por cada coluna)
        for (int c = 0; c < cols; c++) {
            List<Integer> forward = new ArrayList<>();
            List<Integer> reverse = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                int node = topology.getIntersectionIndex(r, c);
                forward.add(node);
            }
            for (int r = rows - 1; r >= 0; r--) {
                int node = topology.getIntersectionIndex(r, c);
                reverse.add(node);
            }

            String idV = "V" + (c + 1);
            String nameV = String.format("Corredor Vertical %d (Coluna %d)", (c + 1), (c + 1));
            list.add(new CorridorDefinition(idV, nameV, CorridorType.VERTICAL, forward, true));
            list.add(new CorridorDefinition(idV + "_REV", nameV + " [Inverso]", CorridorType.VERTICAL, reverse, false));
        }

        return list;
    }

    @Override
    public String toString() {
        return String.format("Corridor[%s: %s, axis=%d, nodes=%s]", id, displayName, getAxis(), intersectionIndices);
    }
}
