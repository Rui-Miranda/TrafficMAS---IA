package environment;

/**
 * Configuração Central e Parametrizável da Topologia da Rede Viária (Fase 6.1 — R1).
 *
 * Suporta de forma unificada e sem coordenadas ou índices mágicos espalhados pelo código:
 * - {@link TopologyMode#GRID_2X2}: Malha 2×2 retrocompatível (4 cruzamentos, 8 agentes semáforo).
 * - {@link TopologyMode#GRID_3X3}: Malha 3×3 expandida (9 cruzamentos, 18 agentes semáforo).
 *
 * Responsabilidades:
 * 1. Definir número de linhas, colunas, cruzamentos e semáforos.
 * 2. Definir coordenadas físicas das vias verticais (X) e horizontais (Y), larguras de via e faixa.
 * 3. Fornecer identificadores canónicos e etiquetas visuais para todos os cruzamentos.
 * 4. Fornecer funções geométricas parametrizadas para deteção do próximo cruzamento,
 *    distância à linha de paragem (stop line), centro de faixa e transposição de linha de saída.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fases 6.1–6.2)
 */
public final class NetworkTopologyConfig {

    /** Modos topológicos oficiais suportados pelo TrafficMAS */
    public enum TopologyMode {
        GRID_2X2(2, 2, 4, 8),
        GRID_3X3(3, 3, 9, 18);

        public final int rows;
        public final int cols;
        public final int intersectionCount;
        public final int trafficLightAgentCount;

        TopologyMode(int rows, int cols, int intersectionCount, int trafficLightAgentCount) {
            this.rows = rows;
            this.cols = cols;
            this.intersectionCount = intersectionCount;
            this.trafficLightAgentCount = trafficLightAgentCount;
        }
    }

    public static final int DEFAULT_ROAD_WIDTH = 96;
    public static final int DEFAULT_LANE_WIDTH = DEFAULT_ROAD_WIDTH / 4; // 24 px

    /** Configuração oficial 2×2 (4 cruzamentos — Compatibilidade Fases 1 a 5.2) */
    public static final NetworkTopologyConfig CONFIG_2X2 = new NetworkTopologyConfig(
            TopologyMode.GRID_2X2,
            2, 2,
            DEFAULT_ROAD_WIDTH, DEFAULT_LANE_WIDTH,
            new int[] { 280, 640 },
            new int[] { 280, 640 },
            new String[] { "C1_NW", "C2_NE", "C3_SW", "C4_SE" },
            new String[] { "C1: NW", "C2: NE", "C3: SW", "C4: SE" },
            new String[] { "NW", "NE", "SW", "SE" },
            980, 1010
    );

    /** Configuração oficial 3×3 (9 cruzamentos — Fase 6) */
    public static final NetworkTopologyConfig CONFIG_3X3 = new NetworkTopologyConfig(
            TopologyMode.GRID_3X3,
            3, 3,
            DEFAULT_ROAD_WIDTH, DEFAULT_LANE_WIDTH,
            new int[] { 180, 490, 800 },
            new int[] { 190, 500, 810 },
            new String[] {
                "C1_NW", "C2_N", "C3_NE",
                "C4_W",  "C5_C", "C6_E",
                "C7_SW", "C8_S", "C9_SE"
            },
            new String[] {
                "C1: NW", "C2: N", "C3: NE",
                "C4: W",  "C5: C", "C6: E",
                "C7: SW", "C8: S", "C9: SE"
            },
            new String[] {
                "C1", "C2", "C3",
                "C4", "C5", "C6",
                "C7", "C8", "C9"
            },
            1100, 1080
    );

    /** Topologia ativa global (por defeito 2×2 em modo headless legado; 3×3 no arranque gráfico/Fase 6) */
    private static volatile NetworkTopologyConfig activeConfig = CONFIG_2X2;
    private static volatile boolean explicitlyConfigured = false;

    private final TopologyMode mode;
    private final int rows;
    private final int cols;
    private final int roadWidth;
    private final int laneWidth;
    private final int[] roadX;
    private final int[] roadY;
    private final String[] intersectionNames;
    private final String[] intersectionDisplayLabels;
    private final String[] intersectionShortLabels;
    private final int worldWidth;
    private final int worldHeight;

    public NetworkTopologyConfig(TopologyMode mode, int rows, int cols,
                                 int roadWidth, int laneWidth,
                                 int[] roadX, int[] roadY,
                                 String[] intersectionNames,
                                 String[] intersectionDisplayLabels,
                                 String[] intersectionShortLabels,
                                 int worldWidth, int worldHeight) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("Dimensões da grelha devem ser positivas.");
        }
        if (roadX == null || roadX.length != cols || roadY == null || roadY.length != rows) {
            throw new IllegalArgumentException("Coordenadas das estradas incompatíveis com rows/cols.");
        }
        int count = rows * cols;
        if (intersectionNames == null || intersectionNames.length != count) {
            throw new IllegalArgumentException("Nomes dos cruzamentos incompatíveis com rows*cols.");
        }
        this.mode = mode;
        this.rows = rows;
        this.cols = cols;
        this.roadWidth = roadWidth;
        this.laneWidth = laneWidth;
        this.roadX = roadX.clone();
        this.roadY = roadY.clone();
        this.intersectionNames = intersectionNames.clone();
        this.intersectionDisplayLabels = intersectionDisplayLabels.clone();
        this.intersectionShortLabels = intersectionShortLabels.clone();
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
    }

    /** Obtém a configuração topológica ativa no sistema. */
    public static NetworkTopologyConfig getActive() {
        return activeConfig;
    }

    /** Define a configuração topológica ativa por modo (GRID_2X2 ou GRID_3X3). */
    public static synchronized void setActiveMode(TopologyMode mode) {
        if (mode == TopologyMode.GRID_3X3) {
            activeConfig = CONFIG_3X3;
        } else {
            activeConfig = CONFIG_2X2;
        }
        explicitlyConfigured = true;
    }

    /** Define uma instância específica de configuração topológica como ativa. */
    public static synchronized void setActive(NetworkTopologyConfig config) {
        if (config != null) {
            activeConfig = config;
            explicitlyConfigured = true;
        }
    }

    /** Ativa o modo 3×3 (9 cruzamentos, 18 semáforos). */
    public static synchronized void activate3x3() {
        setActiveMode(TopologyMode.GRID_3X3);
    }

    /** Ativa o modo de compatibilidade 2×2 (4 cruzamentos, 8 semáforos). */
    public static synchronized void activate2x2() {
        setActiveMode(TopologyMode.GRID_2X2);
    }

    /** Indica se a topologia já foi explicitamente selecionada. */
    public static boolean isExplicitlyConfigured() {
        return explicitlyConfigured;
    }

    /** Obtém a configuração correspondente ao modo pretendido sem alterar a global. */
    public static NetworkTopologyConfig forMode(TopologyMode mode) {
        return (mode == TopologyMode.GRID_3X3) ? CONFIG_3X3 : CONFIG_2X2;
    }

    public TopologyMode getMode() {
        return mode;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public int getIntersectionCount() {
        return rows * cols;
    }

    public int getTrafficLightAgentCount() {
        return getIntersectionCount() * 2;
    }

    public int getRoadWidth() {
        return roadWidth;
    }

    public int getLaneWidth() {
        return laneWidth;
    }

    public int getWorldWidth() {
        return worldWidth;
    }

    public int getWorldHeight() {
        return worldHeight;
    }

    public int[] getRoadX() {
        return roadX.clone();
    }

    public int[] getRoadY() {
        return roadY.clone();
    }

    public int getRoadX(int col) {
        return roadX[Math.max(0, Math.min(cols - 1, col))];
    }

    public int getRoadY(int row) {
        return roadY[Math.max(0, Math.min(rows - 1, row))];
    }

    public String[] getIntersectionNames() {
        return intersectionNames.clone();
    }

    public boolean isValidIntersection(int index) {
        return index >= 0 && index < (rows * cols);
    }

    public int getIntersectionIndex(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return -1;
        }
        return row * cols + col;
    }

    public int getRow(int intersectionIndex) {
        return intersectionIndex / cols;
    }

    public int getCol(int intersectionIndex) {
        return intersectionIndex % cols;
    }

    /** Coordenada X do canto superior esquerdo do cruzamento (px). */
    public int getIntersectionX(int intersectionIndex) {
        return roadX[getCol(intersectionIndex)];
    }

    /** Coordenada Y do canto superior esquerdo do cruzamento (px). */
    public int getIntersectionY(int intersectionIndex) {
        return roadY[getRow(intersectionIndex)];
    }

    /** Coordenada X do centro geométrico do cruzamento (px). */
    public double getIntersectionCenterX(int intersectionIndex) {
        return getIntersectionX(intersectionIndex) + (roadWidth / 2.0);
    }

    /** Coordenada Y do centro geométrico do cruzamento (px). */
    public double getIntersectionCenterY(int intersectionIndex) {
        return getIntersectionY(intersectionIndex) + (roadWidth / 2.0);
    }

    public String getIntersectionName(int intersectionIndex) {
        if (isValidIntersection(intersectionIndex)) {
            return intersectionNames[intersectionIndex];
        }
        return "UNKNOWN_C" + (intersectionIndex + 1);
    }

    public String getIntersectionDisplayLabel(int intersectionIndex) {
        if (isValidIntersection(intersectionIndex)) {
            return intersectionDisplayLabels[intersectionIndex];
        }
        return "C" + (intersectionIndex + 1);
    }

    public String getIntersectionShortLabel(int intersectionIndex) {
        if (isValidIntersection(intersectionIndex)) {
            return intersectionShortLabels[intersectionIndex];
        }
        return "C" + (intersectionIndex + 1);
    }

    /** Nome canónico do agente semáforo JADE para um cruzamento e eixo (0=NS, 1=EW). */
    public String getTrafficLightAgentName(int intersectionIndex, int axis) {
        String axisLabel = (axis == 0) ? "NS" : "EW";
        return "Semaforo_" + axisLabel + "_" + getIntersectionName(intersectionIndex);
    }

    /** Determina o índice da coluna vertical mais próxima da coordenada X. */
    public int getClosestCol(double x) {
        int bestCol = 0;
        double minDiff = Double.MAX_VALUE;
        for (int c = 0; c < cols; c++) {
            double center = roadX[c] + (roadWidth / 2.0);
            double diff = Math.abs(x - center);
            if (diff < minDiff) {
                minDiff = diff;
                bestCol = c;
            }
        }
        return bestCol;
    }

    /** Determina o índice da linha horizontal mais próxima da coordenada Y. */
    public int getClosestRow(double y) {
        int bestRow = 0;
        double minDiff = Double.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            double center = roadY[r] + (roadWidth / 2.0);
            double diff = Math.abs(y - center);
            if (diff < minDiff) {
                minDiff = diff;
                bestRow = r;
            }
        }
        return bestRow;
    }

    /**
     * Determina o índice do próximo cruzamento-alvo para um veículo na posição (x, y)
     * e direção de marcha (0: N->S, 1: S->N, 2: W->E, 3: E->W).
     */
    public int getTargetIntersection(double x, double y, int direction) {
        int col = getClosestCol(x);
        int row = getClosestRow(y);

        switch (direction) {
            case 0: // Norte -> Sul (Y crescente)
                for (int r = 0; r < rows - 1; r++) {
                    if (y < roadY[r] - 2) {
                        return getIntersectionIndex(r, col);
                    }
                }
                return getIntersectionIndex(rows - 1, col);

            case 1: // Sul -> Norte (Y decrescente)
                for (int r = rows - 1; r > 0; r--) {
                    if (y > roadY[r] + roadWidth + 2) {
                        return getIntersectionIndex(r, col);
                    }
                }
                return getIntersectionIndex(0, col);

            case 2: // Oeste -> Este (X crescente)
                for (int c = 0; c < cols - 1; c++) {
                    if (x < roadX[c] - 2) {
                        return getIntersectionIndex(row, c);
                    }
                }
                return getIntersectionIndex(row, cols - 1);

            case 3: // Este -> Oeste (X decrescente)
                for (int c = cols - 1; c > 0; c--) {
                    if (x > roadX[c] + roadWidth + 2) {
                        return getIntersectionIndex(row, c);
                    }
                }
                return getIntersectionIndex(row, 0);

            default:
                return 0;
        }
    }

    /**
     * Calcula a distância longitudinal (em píxeis) até à linha de paragem (stop line)
     * do próximo cruzamento na direção de marcha do veículo.
     */
    public double getDistanceToTargetStopLine(double x, double y, int direction) {
        switch (direction) {
            case 0: // Norte -> Sul
                for (int r = 0; r < rows - 1; r++) {
                    if (y < roadY[r] - 2) {
                        return (roadY[r] - 2) - y;
                    }
                }
                return (roadY[rows - 1] - 2) - y;

            case 1: // Sul -> Norte
                for (int r = rows - 1; r > 0; r--) {
                    if (y > roadY[r] + roadWidth + 2) {
                        return y - (roadY[r] + roadWidth + 2);
                    }
                }
                return y - (roadY[0] + roadWidth + 2);

            case 2: // Oeste -> Este
                for (int c = 0; c < cols - 1; c++) {
                    if (x < roadX[c] - 2) {
                        return (roadX[c] - 2) - x;
                    }
                }
                return (roadX[cols - 1] - 2) - x;

            case 3: // Este -> Oeste
                for (int c = cols - 1; c > 0; c--) {
                    if (x > roadX[c] + roadWidth + 2) {
                        return x - (roadX[c] + roadWidth + 2);
                    }
                }
                return x - (roadX[0] + roadWidth + 2);

            default:
                return 9999.0;
        }
    }

    /**
     * Devolve a coordenada transversal (X para vias verticais, Y para vias horizontais)
     * do centro da faixa especificada.
     *
     * @param corridorIndex Índice da coluna (para dir 0/1) ou linha (para dir 2/3)
     * @param direction Direção de marcha (0: N->S, 1: S->N, 2: W->E, 3: E->W)
     * @param laneIndex 0 = Faixa exterior/direita, 1 = Faixa interior/esquerda
     */
    public double getLaneCenterCoordinate(int corridorIndex, int direction, int laneIndex) {
        switch (direction) {
            case 0: // N->S (metade esquerda da via vertical)
                int rx0 = getRoadX(corridorIndex);
                return (laneIndex == 0) ? (rx0 + 0.5 * laneWidth) : (rx0 + 1.5 * laneWidth);
            case 1: // S->N (metade direita da via vertical)
                int rx1 = getRoadX(corridorIndex);
                return (laneIndex == 0) ? (rx1 + 3.5 * laneWidth) : (rx1 + 2.5 * laneWidth);
            case 2: // W->E (metade inferior da via horizontal)
                int ry2 = getRoadY(corridorIndex);
                return (laneIndex == 0) ? (ry2 + 3.5 * laneWidth) : (ry2 + 2.5 * laneWidth);
            case 3: // E->W (metade superior da via horizontal)
                int ry3 = getRoadY(corridorIndex);
                return (laneIndex == 0) ? (ry3 + 0.5 * laneWidth) : (ry3 + 1.5 * laneWidth);
            default:
                return 0.0;
        }
    }

    /**
     * Verifica se o deslocamento de (oldX, oldY) para (newX, newY) na direção indicada
     * acabou de transpor a linha de saída de algum cruzamento.
     *
     * @return Índice do cruzamento transposto (0 .. N-1) ou -1 se nenhum foi transposto neste passo.
     */
    public int detectCrossedIntersectionExitLine(double oldX, double oldY, double newX, double newY, int direction) {
        int col = getClosestCol(newX);
        int row = getClosestRow(newY);

        switch (direction) {
            case 0: // N->S: saída em roadY[r] + roadWidth
                for (int r = 0; r < rows; r++) {
                    double exitY = roadY[r] + roadWidth;
                    if (oldY < exitY && newY >= exitY) {
                        return getIntersectionIndex(r, col);
                    }
                }
                break;
            case 1: // S->N: saída em roadY[r]
                for (int r = 0; r < rows; r++) {
                    double exitY = roadY[r];
                    if (oldY > exitY && newY <= exitY) {
                        return getIntersectionIndex(r, col);
                    }
                }
                break;
            case 2: // W->E: saída em roadX[c] + roadWidth
                for (int c = 0; c < cols; c++) {
                    double exitX = roadX[c] + roadWidth;
                    if (oldX < exitX && newX >= exitX) {
                        return getIntersectionIndex(row, c);
                    }
                }
                break;
            case 3: // E->W: saída em roadX[c]
                for (int c = 0; c < cols; c++) {
                    double exitX = roadX[c];
                    if (oldX > exitX && newX <= exitX) {
                        return getIntersectionIndex(row, c);
                    }
                }
                break;
        }
        return -1;
    }

    /**
     * Verifica se uma posição (x, y) na direção indicada já ultrapassou a linha de saída
     * de um cruzamento específico.
     */
    public boolean hasExitedIntersection(int intersectionIndex, double x, double y, int direction) {
        if (!isValidIntersection(intersectionIndex)) return false;
        int ix = getIntersectionX(intersectionIndex);
        int iy = getIntersectionY(intersectionIndex);
        switch (direction) {
            case 0: return y >= (iy + roadWidth);
            case 1: return y <= iy;
            case 2: return x >= (ix + roadWidth);
            case 3: return x <= ix;
            default: return false;
        }
    }
}

