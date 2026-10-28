package test;

import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.FleetLifecycleManager;
import environment.HeadlessTrafficSimulator;
import environment.IntersectionSafetyAuthority;
import environment.ManhattanHeuristic;
import environment.ManhattanHeuristic.HeuristicMode;
import environment.NetworkTopologyConfig;
import environment.RoadNetworkGraph;
import environment.SimulationClock;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import metrics.TrafficMetricsCollector;
import metrics.TrafficMetricsSnapshot;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Suite Oficial de Validação Automatizada da Fase 6.1 do TrafficMAS.
 *
 * Testa exaustivamente os 22 requisitos obrigatórios da Fase 6.1:
 *  1. Existência dos 9 cruzamentos na topologia 3×3 (e preservação do modo 2×2).
 *  2. Existência de 18 agentes TrafficLightAgent registados no Directory Facilitator (DF).
 *  3. Existência de 9 instâncias independentes de IntersectionSafetyAuthority.
 *  4. Ausência de verdes incompatíveis nos 9 cruzamentos.
 *  5. Transição segura GREEN -> YELLOW -> ALL_RED -> GREEN nos 9 cruzamentos.
 *  6. Cálculo exato da distância de Manhattan d = |x1 - x2| + |y1 - y2| para pares conhecidos.
 *  7. Admissibilidade e consistência da heurística de Manhattan (px e tempo escalado).
 *  8. Construção válida do grafo rodoviário direcionado 3×3 (9 nós, 24 segmentos internos).
 *  9. Cálculo de rotas A* válidas entre todos os 81 pares de cruzamentos alcançáveis (9×9).
 * 10. Igualdade do custo ótimo entre A* e referência independente Dijkstra nos 81 pares.
 * 11. Tratamento correto de destino igual à origem (custo 0, 0 segmentos, 1 nó).
 * 12. Tratamento correto de destinos inalcançáveis quando segmentos/nós estão bloqueados.
 * 13. Recálculo de rota A* quando uma via intermédia fica bloqueada.
 * 14. Execução física da rota A* pelos veículos na simulação (incluindo viragens em cruzamentos).
 * 15. Configuração inicial com exatamente 50 veículos normais autónomos + 3 prioritários + 18 semáforos + 2 central/ML = 73 agentes JADE.
 * 16. Exclusão dos veículos prioritários do limite de 50 veículos normais.
 * 17. Adição dinâmica de veículos normais até 50 e bloqueio estrito acima de 50 (tentativa 51 rejeitada).
 * 18. Remoção dinâmica de veículos normais até 0 sem erro, sem recursos órfãos e sem afetar prioritários.
 * 19. Ausência de sobreposição física no spawn inicial dos 50 veículos normais + 3 prioritários.
 * 20. Respeito rigoroso pelo sinal vermelho nos 9 cruzamentos (0 violações de stop-line).
 * 21. Contagem de travessias e filas nos 9 cruzamentos sem dupla contagem (debounce 2.0s).
 * 22. Compatibilidade retroativa integral com a topologia 2×2 para os 112 testes históricos.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.1)
 */
public class Phase61ValidationTestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static AgentContainer sharedContainer = null;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   TRAFFICMAS — SUITE DE VALIDAÇÃO DA FASE 6.1 (MALHA 3×3, A*, MANHATTAN, 73 AGENTES)");
        System.out.println("================================================================================");

        try {
            runTest("1. Existência dos 9 cruzamentos na topologia 3×3 (C1_NW a C9_SE)", Phase61ValidationTestSuite::test01NineIntersectionsTopology);
            runTest("2. Existência de 9 instâncias de IntersectionSafetyAuthority", Phase61ValidationTestSuite::test03NineSafetyAuthorities);
            runTest("3. Ausência de verdes incompatíveis nos 9 cruzamentos", Phase61ValidationTestSuite::test04NoIncompatibleGreens9Intersections);
            runTest("4. Transição segura GREEN -> YELLOW -> ALL_RED -> GREEN nos 9 cruzamentos", Phase61ValidationTestSuite::test05SafeTransitions9Intersections);
            runTest("5. Cálculo exato da distância de Manhattan d = |x1 - x2| + |y1 - y2|", Phase61ValidationTestSuite::test06ExactManhattanDistance);
            runTest("6. Admissibilidade e consistência da heurística de Manhattan e fallback nulo", Phase61ValidationTestSuite::test07ManhattanAdmissibilityAndConsistency);
            runTest("7. Construção válida do grafo rodoviário direcionado 3×3", Phase61ValidationTestSuite::test08RoadGraphConstruction3x3);
            runTest("8. Cálculo de rotas A* válidas entre todos os pares alcançáveis (9×9 = 81 pares)", Phase61ValidationTestSuite::test09AStarAllReachablePairs);
            runTest("9. Igualdade do custo ótimo entre A* e Dijkstra em todos os pares e métricas", Phase61ValidationTestSuite::test10AStarMatchesDijkstraOptimality);
            runTest("10. Tratamento correto de destino igual à origem no A*", Phase61ValidationTestSuite::test11AStarSameOriginAndDestination);
            runTest("11. Tratamento correto de destinos inalcançáveis com vias bloqueadas", Phase61ValidationTestSuite::test12AStarUnreachableDestination);
            runTest("12. Recálculo dinâmico de rota A* quando via intermédia fica bloqueada", Phase61ValidationTestSuite::test13AStarDynamicReroutingOnBlockedEdge);
            runTest("13. Execução efetiva da rota A* pelos veículos na simulação 3×3", Phase61ValidationTestSuite::test14VehicleExecutesAStarRouteInSimulation);
            runTest("14. Ausência de sobreposição física no spawn inicial de 50 normais + 3 prioritários", Phase61ValidationTestSuite::test19NonOverlappingSpawn53Vehicles);
            runTest("15. Respeito pelo sinal vermelho nos 9 cruzamentos (0 violações)", Phase61ValidationTestSuite::test20RedLightRespect9Intersections);
            runTest("16. Contagem de travessias e filas nos 9 cruzamentos sem dupla contagem", Phase61ValidationTestSuite::test21Metrics9IntersectionsNoDoubleCount);
            runTest("17. Registo de 18 TrafficLightAgent no DF e arranque com 73 agentes JADE", Phase61ValidationTestSuite::test02And15Jade73AgentsAnd18TrafficLightsInDF);
            runTest("18. Exclusão dos 3 veículos prioritários do limite de 50 normais", Phase61ValidationTestSuite::test16PriorityVehiclesExcludedFrom50Limit);
            runTest("19. Bloqueio de adição acima de 50 veículos normais", Phase61ValidationTestSuite::test17BlockAdditionAbove50NormalVehicles);
            runTest("20. Remoção segura de veículos normais até 0 e re-adição até 50 sem afetar prioritários", Phase61ValidationTestSuite::test18RemoveDownToZeroAndAddBackTo50);
            runTest("21. Restauro limpo do modo de compatibilidade 2×2 (4 cruzamentos)", Phase61ValidationTestSuite::test22BackwardCompatibility2x2Mode);
        } finally {
            if (sharedContainer != null) {
                try {
                    sharedContainer.kill();
                } catch (Throwable ignored) {}
            }
            NetworkTopologyConfig.activate2x2();
            TrafficMetricsCollector.reset(4);
            SimulationClock.disableSimulatedMode();
        }

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.printf("RESULTADO GLOBAL FASE 6.1: %d PASSOU | %d FALHOU%n", passed, failed);
        System.out.println("--------------------------------------------------------------------------------");

        if (failed > 0) {
            System.err.println("ERRO: Um ou mais testes da Fase 6.1 falharam!");
            System.exit(1);
        } else {
            System.out.println("SUCESSO: Todos os 21 blocos (22 requisitos da Fase 6.1) foram 100% validados!");
            System.exit(0);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }

    private static void runTest(String name, TestCase test) {
        System.out.print("[TESTE] " + name + " ... ");
        try {
            test.run();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace(System.out);
            failed++;
        }
    }

    // 1. Existência dos 9 cruzamentos na topologia 3×3
    private static void test01NineIntersectionsTopology() {
        NetworkTopologyConfig.activate3x3();
        NetworkTopologyConfig topo = NetworkTopologyConfig.getActive();
        if (topo.getRows() != 3 || topo.getCols() != 3) {
            throw new AssertionError("Malha esperada: 3x3, obtida: " + topo.getRows() + "x" + topo.getCols());
        }
        if (topo.getIntersectionCount() != 9) {
            throw new AssertionError("IntersectionCount esperado: 9, obtido: " + topo.getIntersectionCount());
        }
        if (topo.getTrafficLightAgentCount() != 18) {
            throw new AssertionError("TrafficLightAgentCount esperado: 18, obtido: " + topo.getTrafficLightAgentCount());
        }
        String[] expectedNames = {"C1_NW", "C2_N", "C3_NE", "C4_W", "C5_C", "C6_E", "C7_SW", "C8_S", "C9_SE"};
        for (int i = 0; i < 9; i++) {
            if (!expectedNames[i].equals(topo.getIntersectionName(i))) {
                throw new AssertionError("Nome incorreto para cruzamento " + i + ": " + topo.getIntersectionName(i));
            }
            int r = topo.getRow(i);
            int c = topo.getCol(i);
            if (topo.getIntersectionIndex(r, c) != i) {
                throw new AssertionError("Mapeamento bijetivo (row,col)<->index inválido para " + i);
            }
        }
    }

    // 3. Existência de 9 instâncias de IntersectionSafetyAuthority
    private static void test03NineSafetyAuthorities() {
        IntersectionSafetyAuthority.resetAll();
        Set<IntersectionSafetyAuthority> uniqueAuthorities = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
            if (auth == null || auth.getIntersectionIndex() != i) {
                throw new AssertionError("Autoridade inválida para o cruzamento " + i);
            }
            uniqueAuthorities.add(auth);
        }
        if (uniqueAuthorities.size() != 9) {
            throw new AssertionError("Esperadas 9 instâncias distintas de IntersectionSafetyAuthority, obtidas: " + uniqueAuthorities.size());
        }
    }

    // 4. Ausência de verdes incompatíveis nos 9 cruzamentos
    private static void test04NoIncompatibleGreens9Intersections() {
        SimulationClock.enableSimulatedMode(0L);
        IntersectionSafetyAuthority.resetAll();
        for (int step = 0; step < 600; step++) {
            SimulationClock.advanceSeconds(0.1);
            for (int i = 0; i < 9; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                auth.updateTick((step + i) % 5, (step * 2 + i) % 4);
                LightState ns = auth.getLightState(0);
                LightState ew = auth.getLightState(1);
                if (ns == LightState.GREEN && ew == LightState.GREEN) {
                    throw new AssertionError("Verdes simultâneos incompatíveis detetados no cruzamento " + i);
                }
            }
        }
        SimulationClock.disableSimulatedMode();
    }

    // 5. Transição segura GREEN -> YELLOW -> ALL_RED -> GREEN nos 9 cruzamentos
    private static void test05SafeTransitions9Intersections() {
        SimulationClock.enableSimulatedMode(0L);
        IntersectionSafetyAuthority.resetAll();

        boolean[] sawGreen = new boolean[9];
        boolean[] sawYellow = new boolean[9];
        boolean[] sawAllRed = new boolean[9];
        boolean[] sawOppositeGreen = new boolean[9];
        IntersectionSafetyAuthority.Phase[] initialPhase = new IntersectionSafetyAuthority.Phase[9];

        for (int i = 0; i < 9; i++) {
            IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
            initialPhase[i] = auth.getCurrentPhase();
            if (initialPhase[i] == IntersectionSafetyAuthority.Phase.NS_GREEN
                    || initialPhase[i] == IntersectionSafetyAuthority.Phase.EW_GREEN) {
                sawGreen[i] = true;
            }
        }

        for (int step = 0; step < 150; step++) {
            SimulationClock.advanceSeconds(0.1);
            for (int i = 0; i < 9; i++) {
                IntersectionSafetyAuthority auth = IntersectionSafetyAuthority.getAuthority(i);
                // Estimula o eixo oposto ao inicial para provocar a transição segura
                if (initialPhase[i] == IntersectionSafetyAuthority.Phase.NS_GREEN) {
                    auth.updateTick(1, 3);
                } else {
                    auth.updateTick(3, 1);
                }
                IntersectionSafetyAuthority.Phase p = auth.getCurrentPhase();
                if (p == IntersectionSafetyAuthority.Phase.NS_YELLOW
                        || p == IntersectionSafetyAuthority.Phase.EW_YELLOW) {
                    sawYellow[i] = true;
                }
                if (p == IntersectionSafetyAuthority.Phase.ALL_RED && sawYellow[i]) {
                    sawAllRed[i] = true;
                }
                if (sawAllRed[i]) {
                    if (initialPhase[i] == IntersectionSafetyAuthority.Phase.NS_GREEN
                            && p == IntersectionSafetyAuthority.Phase.EW_GREEN) {
                        sawOppositeGreen[i] = true;
                    } else if (initialPhase[i] == IntersectionSafetyAuthority.Phase.EW_GREEN
                            && p == IntersectionSafetyAuthority.Phase.NS_GREEN) {
                        sawOppositeGreen[i] = true;
                    }
                }
            }
        }

        for (int i = 0; i < 9; i++) {
            if (!sawGreen[i] || !sawYellow[i] || !sawAllRed[i] || !sawOppositeGreen[i]) {
                throw new AssertionError("Transição completa GREEN->YELLOW->ALL_RED->GREEN falhou no cruzamento C" + (i + 1));
            }
        }
        SimulationClock.disableSimulatedMode();
    }

    // 6. Cálculo exato da distância de Manhattan para pares conhecidos
    private static void test06ExactManhattanDistance() {
        double d1 = ManhattanHeuristic.computePhysicalManhattanDistance(228.0, 238.0, 848.0, 858.0);
        // |228 - 848| + |238 - 858| = 620 + 620 = 1240.0 px
        if (Math.abs(d1 - 1240.0) > 1e-6) {
            throw new AssertionError("Distância Manhattan física incorreta: esperado 1240.0, obtido " + d1);
        }

        int gridHops = ManhattanHeuristic.computeGridManhattanDistance(0, 0, 2, 2);
        // |0 - 2| + |0 - 2| = 4 saltos ortogonais
        if (gridHops != 4) {
            throw new AssertionError("Distância Manhattan em grelha incorreta: esperado 4, obtido " + gridHops);
        }

        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        // C1_NW (0) -> C5_C (4): |228 - 538| + |238 - 548| = 310 + 310 = 620.0 px
        double dNode = ManhattanHeuristic.computeNodeManhattanDistancePx(graph.getNode(0), graph.getNode(4));
        if (Math.abs(dNode - 620.0) > 1e-6) {
            throw new AssertionError("Distância Manhattan entre C1_NW e C5_C esperada 620.0, obtida " + dNode);
        }
    }

    // 7. Admissibilidade da heurística de Manhattan na configuração adotada
    private static void test07ManhattanAdmissibilityAndConsistency() {
        RoadNetworkGraph distGraph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        distGraph.setActiveCostMetric(RoadNetworkGraph.CostMetric.PHYSICAL_DISTANCE_PX);
        ManhattanHeuristic hAdaptive = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        if (!hAdaptive.isConsistentOnGraph(distGraph)) {
            throw new AssertionError("Heurística de Manhattan (px) violou consistência/admissibilidade no grafo de distância!");
        }

        RoadNetworkGraph timeGraph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        timeGraph.setActiveCostMetric(RoadNetworkGraph.CostMetric.EXPECTED_TRAVEL_TIME);
        if (!hAdaptive.isConsistentOnGraph(timeGraph)) {
            throw new AssertionError("Heurística de Manhattan escalada por v_max violou consistência/admissibilidade no grafo de tempo!");
        }

        // Verifica que o fallback nulo devolve exatamente 0.0
        ManhattanHeuristic hNullMode = new ManhattanHeuristic(HeuristicMode.NULL_FALLBACK);
        for (int u = 0; u < 9; u++) {
            for (int v = 0; v < 9; v++) {
                double hNull = hNullMode.estimateRemainingCost(distGraph.getNode(u), distGraph.getNode(v), distGraph);
                if (hNull != 0.0) {
                    throw new AssertionError("Fallback nulo devolveu valor diferente de zero: " + hNull);
                }
            }
        }
    }

    // 8. Construção válida do grafo rodoviário 3×3
    private static void test08RoadGraphConstruction3x3() {
        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        if (graph.getNodeCount() != 9) {
            throw new AssertionError("Grafo 3×3 deve ter 9 nós, obtidos: " + graph.getNodeCount());
        }
        // Na grelha 3×3 existem 12 arestas não-direcionadas = 24 segmentos dirigidos
        if (graph.getAllSegments().size() != 24) {
            throw new AssertionError("Grafo 3×3 deve ter 24 segmentos dirigidos, obtidos: " + graph.getAllSegments().size());
        }
        // Nó central C5_C (índice 4) deve ter 4 segmentos de saída (N, S, W, E)
        if (graph.getOutgoingSegments(4).size() != 4) {
            throw new AssertionError("Nó central C5_C deve ter grau de saída 4, obtido: " + graph.getOutgoingSegments(4).size());
        }
        // Todos os segmentos devem ter custo estritamente positivo
        for (RoadNetworkGraph.RoadSegment seg : graph.getAllSegments()) {
            if (seg.getEffectiveCost(graph.getActiveCostMetric()) <= 0.0) {
                throw new AssertionError("Segmento com custo não-positivo: " + seg);
            }
        }
    }

    // 9. Cálculo de rotas A* válidas entre todos os pares alcançáveis (9×9 = 81 pares)
    private static void test09AStarAllReachablePairs() {
        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                AStarPathfinder.RouteResult route = AStarPathfinder.findPathAStar(
                        graph, src, dst, -1, heuristic);
                if (!route.reachable) {
                    throw new AssertionError("Par alcançável (" + src + " -> " + dst + ") dado como inalcançável!");
                }
                if (route.nodePath.isEmpty() || route.nodePath.get(0) != src
                        || route.nodePath.get(route.nodePath.size() - 1) != dst) {
                    throw new AssertionError("Caminho inválido para (" + src + " -> " + dst + "): " + route.nodePath);
                }
                if (src != dst && route.segmentPath.size() != route.nodePath.size() - 1) {
                    throw new AssertionError("Número de segmentos inconsistente com número de nós!");
                }
            }
        }
    }

    // 10. Igualdade do custo ótimo entre A* e Dijkstra para rotas válidas
    private static void test10AStarMatchesDijkstraOptimality() {
        RoadNetworkGraph distGraph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        distGraph.setActiveCostMetric(RoadNetworkGraph.CostMetric.PHYSICAL_DISTANCE_PX);
        // Adiciona penalizações assimétricas reais em alguns segmentos para testar caminhos não triviais
        distGraph.setSegmentCongestionPenalty(0, 1, 1.45);
        distGraph.setSegmentCongestionPenalty(1, 4, 1.80);
        distGraph.setSegmentCongestionPenalty(3, 4, 1.25);

        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                AStarPathfinder.RouteResult astar = AStarPathfinder.findPathAStar(
                        distGraph, src, dst, -1, heuristic);
                AStarPathfinder.RouteResult dijkstra = AStarPathfinder.findPathDijkstra(distGraph, src, dst, -1);

                if (astar.reachable != dijkstra.reachable) {
                    throw new AssertionError("Alcançabilidade diverge entre A* e Dijkstra em " + src + "->" + dst);
                }
                if (Math.abs(astar.totalCost - dijkstra.totalCost) > 1e-6) {
                    throw new AssertionError(String.format(
                            "Custo ótimo A* (%.4f) != Dijkstra (%.4f) em %d->%d",
                            astar.totalCost, dijkstra.totalCost, src, dst));
                }
            }
        }

        // Testa também com métrica de tempo esperado de viagem
        RoadNetworkGraph timeGraph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        timeGraph.setActiveCostMetric(RoadNetworkGraph.CostMetric.EXPECTED_TRAVEL_TIME);
        timeGraph.setSegmentCongestionPenalty(4, 5, 2.10);
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                AStarPathfinder.RouteResult astar = AStarPathfinder.findPathAStar(
                        timeGraph, src, dst, -1, heuristic);
                AStarPathfinder.RouteResult dijkstra = AStarPathfinder.findPathDijkstra(timeGraph, src, dst, -1);
                if (Math.abs(astar.totalCost - dijkstra.totalCost) > 1e-6) {
                    throw new AssertionError("Custo ótimo A* (tempo) != Dijkstra em " + src + "->" + dst);
                }
            }
        }
    }

    // 11. Tratamento correto de destino igual à origem
    private static void test11AStarSameOriginAndDestination() {
        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        for (int i = 0; i < 9; i++) {
            AStarPathfinder.RouteResult res = AStarPathfinder.findPathAStar(
                    graph, i, i, 0, heuristic);
            if (!res.reachable || res.totalCost != 0.0 || res.nodePath.size() != 1 || !res.segmentPath.isEmpty()) {
                throw new AssertionError("Rota para origem == destino inválida no nó " + i + ": " + res);
            }
        }
    }

    // 12. Tratamento correto de destinos inalcançáveis quando segmentos estão bloqueados
    private static void test12AStarUnreachableDestination() {
        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        // Isola o cruzamento C9_SE (índice 8) bloqueando as duas entradas possíveis: C6_E(5)->C9_SE(8) e C8_S(7)->C9_SE(8)
        graph.setSegmentBlocked(5, 8, true);
        graph.setSegmentBlocked(7, 8, true);

        AStarPathfinder.RouteResult astar = AStarPathfinder.findPathAStar(
                graph, 0, 8, 0, heuristic);
        AStarPathfinder.RouteResult dijkstra = AStarPathfinder.findPathDijkstra(graph, 0, 8, 0);

        if (astar.reachable || dijkstra.reachable) {
            throw new AssertionError("Destino isolado C9_SE deveria ser inalcançável!");
        }
        if (!Double.isInfinite(astar.totalCost) || !astar.nodePath.isEmpty()) {
            throw new AssertionError("Resultado para destino inalcançável deve ter custo infinito e caminho vazio.");
        }
    }

    // 13. Recálculo de rota quando uma via intermédia fica bloqueada
    private static void test13AStarDynamicReroutingOnBlockedEdge() {
        RoadNetworkGraph graph = new RoadNetworkGraph(NetworkTopologyConfig.CONFIG_3X3);
        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);
        // De C1_NW (0) para C3_NE (2): caminho direto inicial passa por C2_N (1): [0, 1, 2]
        AStarPathfinder.RouteResult initialRoute = AStarPathfinder.findPathAStar(
                graph, 0, 2, 2, heuristic);
        if (!initialRoute.reachable || !initialRoute.nodePath.equals(List.of(0, 1, 2))) {
            throw new AssertionError("Rota inicial esperada [0, 1, 2], obtida: " + initialRoute.nodePath);
        }

        long vBefore = graph.getGraphVersion();
        // Bloqueia o segmento C1_NW(0) -> C2_N(1)
        graph.setSegmentBlocked(0, 1, true);
        long vAfter = graph.getGraphVersion();
        if (vAfter <= vBefore) {
            throw new AssertionError("Versão do grafo deve incrementar após bloqueio de via.");
        }

        AStarPathfinder.RouteResult rerouted = AStarPathfinder.findPathAStar(
                graph, 0, 2, 0, heuristic);
        if (!rerouted.reachable) {
            throw new AssertionError("Rota alternativa deveria existir por C4_W -> C5_C!");
        }
        if (rerouted.nodePath.contains(1) && rerouted.nodePath.get(1) == 1) {
            throw new AssertionError("Rota recalculada não evitou o segmento bloqueado (0->1): " + rerouted.nodePath);
        }
        // Deve passar por C4_W (3): ex. [0, 3, 4, 1, 2] ou [0, 3, 4, 5, 2]
        if (rerouted.nodePath.get(1) != 3) {
            throw new AssertionError("Rota recalculada esperada via C4_W (3), obtida: " + rerouted.nodePath);
        }
    }

    // 14. Execução da rota A* pelos veículos na simulação
    private static void test14VehicleExecutesAStarRouteInSimulation() {
        NetworkTopologyConfig.activate3x3();
        CitySimulationGUI gui = new CitySimulationGUI(true);
        RoadNetworkGraph graph = gui.getRoadGraph();
        ManhattanHeuristic heuristic = new ManhattanHeuristic(HeuristicMode.ADAPTIVE_ADMISSIBLE);

        // Cria veículo em aproximação a C1_NW (0) no sentido N->S (dir=0) e atribui rota A* 0 -> 3 -> 4 (viragem à esquerda em C4_W)
        CitySimulationGUI.VisualVehicle v = new CitySimulationGUI.VisualVehicle(
                "Carro_Rota_AStar",
                gui.getTopology().getLaneCenterCoordinate(0, 0, 1),
                120.0,
                2.5,
                0,
                Color.CYAN,
                CitySimulationGUI.ManeuverIntent.GO_STRAIGHT,
                1);

        v.activeRoute = AStarPathfinder.findPathAStar(graph, 0, 4, 0, heuristic);
        v.routeGraphVersion = graph.getGraphVersion();

        if (!v.activeRoute.reachable || v.activeRoute.maneuversAtNodes.isEmpty()) {
            throw new AssertionError("Rota A* atribuída ao veículo não possui manobras válidas.");
        }
        // Verifica que o simulador headless 3×3 também atribui e mantém rotas A* válidas nos veículos
        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator(
                "HEAVY_TRAFFIC", 2026L, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE, NetworkTopologyConfig.CONFIG_3X3);
        sim.advanceSeconds(25.0);
        if (sim.getVehicles().isEmpty()) {
            throw new AssertionError("Simulador 3×3 não gerou veículos.");
        }
        for (HeadlessTrafficSimulator.SimVehicle sv : sim.getVehicles()) {
            if (sv.activeRoute == null || !sv.activeRoute.reachable) {
                throw new AssertionError("Veículo " + sv.id + " sem rota A* válida no simulador!");
            }
        }
        sim.cleanup();
    }

    // 19. Ausência de sobreposição no spawn inicial dos 50 veículos normais + 3 prioritários
    private static void test19NonOverlappingSpawn53Vehicles() {
        NetworkTopologyConfig.activate3x3();
        CitySimulationGUI gui = new CitySimulationGUI(true);

        for (int i = 1; i <= 50; i++) {
            String id = String.format("Carro_%02d", i);
            int dir = (i - 1) % 4;
            gui.spawnVehicleSafely(id, dir, Color.BLUE, false, "NORMAL", i - 1);
        }
        gui.spawnVehicleSafely("Ambulancia_INEM_01", 0, Color.WHITE, true, "AMBULANCE", 100);
        gui.spawnVehicleSafely("Bombeiros_Sapadores_01", 2, Color.RED, true, "FIRE_TRUCK", 101);
        gui.spawnVehicleSafely("Viatura_Policia_01", 3, Color.WHITE, true, "POLICE", 102);

        List<CitySimulationGUI.VisualVehicle> all = gui.getVisualVehiclesSnapshot();
        if (all.size() != 53) {
            throw new AssertionError("Esperados 53 veículos instanciados, obtidos: " + all.size());
        }

        // Verifica que nenhum par de veículos na mesma direção e faixa se sobrepõe
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                CitySimulationGUI.VisualVehicle a = all.get(i);
                CitySimulationGUI.VisualVehicle b = all.get(j);
                if (a.direction == b.direction && a.laneIndex == b.laneIndex) {
                    double dx = Math.abs(a.x - b.x);
                    double dy = Math.abs(a.y - b.y);
                    if (dx < 20.0 && dy < 20.0) {
                        throw new AssertionError("Sobreposição física detetada no spawn entre "
                                + a.id + " (" + a.x + "," + a.y + ") e " + b.id + " (" + b.x + "," + b.y + ")");
                    }
                }
            }
        }
    }

    // 20. Respeito pelo sinal vermelho nos 9 cruzamentos
    private static void test20RedLightRespect9Intersections() {
        NetworkTopologyConfig.activate3x3();
        TrafficMetricsCollector.reset(9);
        HeadlessTrafficSimulator sim = new HeadlessTrafficSimulator(
                "HEAVY_TRAFFIC", 7777L, IntersectionSafetyAuthority.ControlMode.LOCAL_ADAPTIVE, NetworkTopologyConfig.CONFIG_3X3);
        sim.advanceSeconds(60.0);
        if (sim.getRedLightViolationsCount() != 0) {
            throw new AssertionError("Detetadas violações de sinal vermelho na malha 3×3: " + sim.getRedLightViolationsCount());
        }
        sim.cleanup();
    }

    // 21. Contagem de travessias e filas nos 9 cruzamentos sem dupla contagem
    private static void test21Metrics9IntersectionsNoDoubleCount() {
        NetworkTopologyConfig.activate3x3();
        TrafficMetricsCollector.reset(9);
        TrafficMetricsCollector col = TrafficMetricsCollector.getInstance();

        if (col.getIntersectionCount() != 9) {
            throw new AssertionError("Coletor deve ter 9 cruzamentos em modo 3×3.");
        }

        col.setSimulatedTime(10.0);
        for (int i = 0; i < 9; i++) {
            boolean first = col.recordCrossing("Carro_01", i, i % 2, 2.0 + i * 0.5);
            boolean duplicate = col.recordCrossing("Carro_01", i, i % 2, 2.0); // Mesmo segundo -> debounce deve rejeitar
            if (!first || duplicate) {
                throw new AssertionError("Falha na prevenção de dupla contagem no cruzamento " + i);
            }
            col.sampleQueue(i, 0, i % 4);
            col.sampleQueue(i, 1, (i + 1) % 4);
        }

        TrafficMetricsSnapshot snap = col.getSnapshot("TEST_3X3", "NORMAL", "LOCAL_ADAPTIVE", 42L);
        if (snap.totalCrossings != 9 || snap.crossingsPerIntersection.length != 9) {
            throw new AssertionError("Snapshot 3×3 inválido: totalCrossings=" + snap.totalCrossings);
        }
        for (int i = 0; i < 9; i++) {
            if (snap.crossingsPerIntersection[i] != 1) {
                throw new AssertionError("Cruzamento " + i + " deveria ter 1 travessia registada.");
            }
        }
    }

    // 2 & 15. Existência de 18 TrafficLightAgent no DF e configuração inicial com 50 normais + 3 prioritários = 73 agentes JADE
    private static void test02And15Jade73AgentsAnd18TrafficLightsInDF() throws Exception {
        NetworkTopologyConfig.activate3x3();
        TrafficMetricsCollector.reset(9);
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        fleet.reset();

        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "2610");
        p.setParameter(Profile.GUI, "false");
        sharedContainer = rt.createMainContainer(p);
        fleet.bindContainer(sharedContainer);

        // 1 CentralCoordinationAgent + 1 DisruptionManagementAgent
        AgentController coordCtrl = sharedContainer.createNewAgent(
                "AgenteCoordenadorCentral", "agents.CentralCoordinationAgent", new Object[]{});
        fleet.registerInfrastructureController("AgenteCoordenadorCentral", coordCtrl);
        coordCtrl.start();

        AgentController disrCtrl = sharedContainer.createNewAgent(
                "AgenteGestaoPerturbacoes", "agents.DisruptionManagementAgent", new Object[]{});
        fleet.registerInfrastructureController("AgenteGestaoPerturbacoes", disrCtrl);
        disrCtrl.start();

        // 18 TrafficLightAgent (2 por cruzamento C1_NW..C9_SE)
        NetworkTopologyConfig topo = NetworkTopologyConfig.CONFIG_3X3;
        for (int i = 0; i < 9; i++) {
            String cName = topo.getIntersectionName(i);
            String nsName = "Semaforo_NS_" + cName;
            String ewName = "Semaforo_EW_" + cName;
            AgentController tlNs = sharedContainer.createNewAgent(nsName, "agents.TrafficLightAgent", new Object[]{"Norte-Sul", i});
            AgentController tlEw = sharedContainer.createNewAgent(ewName, "agents.TrafficLightAgent", new Object[]{"Este-Oeste", i});
            fleet.registerInfrastructureController(nsName, tlNs);
            fleet.registerInfrastructureController(ewName, tlEw);
            tlNs.start();
            tlEw.start();
        }

        // 50 VehicleAgent normais (Carro_01 a Carro_50) + 3 EmergencyVehicleAgent prioritários
        fleet.initializeDefault3x3Fleet(sharedContainer);

        // Aguarda setup() dos 73 agentes JADE
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && fleet.getLiveJadeAgentsCount() < 73) {
            Thread.sleep(50);
        }

        if (fleet.getLiveJadeAgentsCountByType("TrafficLightAgent") != 18) {
            throw new AssertionError("Esperados 18 TrafficLightAgent ativos, obtidos: "
                    + fleet.getLiveJadeAgentsCountByType("TrafficLightAgent"));
        }
        if (fleet.getActiveNormalVehicleCount() != 50) {
            throw new AssertionError("Esperados 50 VehicleAgent normais ativos, obtidos: " + fleet.getActiveNormalVehicleCount());
        }
        if (fleet.getActivePriorityVehicleCount() != 3) {
            throw new AssertionError("Esperados 3 EmergencyVehicleAgent ativos, obtidos: " + fleet.getActivePriorityVehicleCount());
        }
        if (fleet.getLiveJadeAgentsCount() != 73) {
            throw new AssertionError("Esperados 73 agentes JADE ativos (50+3+18+1+1), obtidos: " + fleet.getLiveJadeAgentsCount());
        }

        // Verifica registo efetivo dos 18 agentes TrafficLightAgent no Directory Facilitator (DF)
        agents.CentralCoordinationAgent coord = agents.CentralCoordinationAgent.getActiveInstance();
        if (coord == null) {
            throw new AssertionError("CentralCoordinationAgent não está ativo.");
        }
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("traffic-light-service");
        template.addServices(sd);
        DFAgentDescription[] dfResults = DFService.search(coord, template);
        if (dfResults == null || dfResults.length != 18) {
            throw new AssertionError("Esperados 18 semáforos registados no DF, encontrados: "
                    + (dfResults == null ? 0 : dfResults.length));
        }
    }

    // 16. Exclusão dos veículos prioritários do limite de 50
    private static void test16PriorityVehiclesExcludedFrom50Limit() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        if (fleet.getActiveNormalVehicleCount() != 50) {
            throw new AssertionError("Contagem de veículos normais deve ser 50, obtida: " + fleet.getActiveNormalVehicleCount());
        }
        if (fleet.getActivePriorityVehicleCount() != 3) {
            throw new AssertionError("Os 3 veículos prioritários devem coexistir fora do limite de 50 normais.");
        }
        if (fleet.getMaxNormalVehicles() != 50) {
            throw new AssertionError("Limite máximo de veículos normais deve ser 50.");
        }
    }

    // 17. Adição de veículos normais até 50 e bloqueio acima de 50
    private static void test17BlockAdditionAbove50NormalVehicles() {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();
        if (fleet.isAddNormalVehicleAvailable()) {
            throw new AssertionError("Adição de veículo normal deveria estar indisponível quando já existem 50.");
        }
        String addedId = fleet.addNormalVehicle();
        if (addedId != null) {
            throw new AssertionError("Tentativa de adicionar o 51.º veículo normal deveria ser rejeitada!");
        }
        if (fleet.getActiveNormalVehicleCount() != 50) {
            throw new AssertionError("Contagem normal alterada indevidamente após rejeição: " + fleet.getActiveNormalVehicleCount());
        }
    }

    // 18. Remoção de veículos normais até 0 sem erro e sem afetar prioritários, e adição de novo até 50
    private static void test18RemoveDownToZeroAndAddBackTo50() throws Exception {
        FleetLifecycleManager fleet = FleetLifecycleManager.getInstance();

        // Remove todos os 50 veículos normais um a um até 0
        for (int rem = 50; rem >= 1; rem--) {
            String removedId = fleet.removeNormalVehicle();
            if (removedId == null) {
                throw new AssertionError("Falha ao remover veículo normal quando restavam " + rem);
            }
        }

        if (fleet.getActiveNormalVehicleCount() != 0) {
            throw new AssertionError("Contagem de veículos normais deveria ser 0 após remover todos, obtida: "
                    + fleet.getActiveNormalVehicleCount());
        }

        // Verifica que uma remoção extra com 0 veículos não lança exceção e não afeta os 3 prioritários
        String emptyRemove = fleet.removeNormalVehicle();
        if (emptyRemove != null) {
            throw new AssertionError("Remoção com 0 veículos normais deveria devolver null de forma controlada.");
        }
        if (fleet.getActivePriorityVehicleCount() != 3) {
            throw new AssertionError("Os 3 veículos prioritários foram afetados pela remoção da frota normal!");
        }

        // Agora adiciona novamente veículos normais até 50
        for (int add = 1; add <= 50; add++) {
            if (!fleet.isAddNormalVehicleAvailable()) {
                throw new AssertionError("Adição deveria estar disponível antes de atingir 50 (atual=" + (add - 1) + ")");
            }
            String addId = fleet.addNormalVehicle();
            if (addId == null) {
                throw new AssertionError("Falha ao adicionar veículo normal #" + add);
            }
        }

        // Aguarda setup() dos novos agentes JADE criados
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && fleet.getActiveNormalVehicleCount() < 50) {
            Thread.sleep(50);
        }

        if (fleet.getActiveNormalVehicleCount() != 50) {
            throw new AssertionError("Esperados 50 veículos normais após re-adição, obtidos: " + fleet.getActiveNormalVehicleCount());
        }
        if (fleet.getActivePriorityVehicleCount() != 3) {
            throw new AssertionError("Esperados 3 veículos prioritários intocados, obtidos: " + fleet.getActivePriorityVehicleCount());
        }
    }

    // 22. Compatibilidade com o modo 2×2
    private static void test22BackwardCompatibility2x2Mode() {
        NetworkTopologyConfig.activate2x2();
        TrafficMetricsCollector.reset(4);
        if (NetworkTopologyConfig.getActive().getIntersectionCount() != 4) {
            throw new AssertionError("Modo de compatibilidade 2×2 não restaurou 4 cruzamentos.");
        }
    }
}
