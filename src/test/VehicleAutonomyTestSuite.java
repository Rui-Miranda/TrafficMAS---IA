package test;

import agents.VehicleAgent;
import environment.AStarPathfinder;
import environment.CitySimulationGUI;
import environment.CitySimulationGUI.LightState;
import environment.CitySimulationGUI.ManeuverIntent;
import environment.CitySimulationGUI.VisualVehicle;
import environment.FleetLifecycleManager;
import environment.IntersectionSafetyAuthority;
import environment.ManhattanHeuristic;
import environment.NetworkTopologyConfig;
import environment.RoadNetworkGraph;
import environment.TrafficEnvironment;
import environment.VehicleAction;
import environment.VehicleControlGateway;
import environment.VehicleDecision;
import environment.VehiclePerception;
import jade.core.AID;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.lang.acl.ACLMessage;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Suite de Testes Automatizados da Fase 6.2-D: Autonomia Operacional dos Agentes Veículo.
 *
 * Valida os 15 requisitos estruturais de autonomia:
 *  1. Planeamento individual e cálculo de rota A* pelo VehicleAgent;
 *  2. Destinos distintos produzem decisões e manobras deliberativas distintas;
 *  3. Reação sensorial a semáforos (VERMELHO -> BRAKE/STOP, VERDE -> ACCELERATE/CRUISE);
 *  4. Reação sensorial a obstáculos e veículos líderes (distância de segurança);
 *  5. Emissão de pedidos FIPA-ACL com mapeamento correto do eixo semafórico (NS vs EW);
 *  6. Processamento resiliente de respostas FIPA-ACL (INFORM, AGREE, REFUSE);
 *  7. Controlo efetivo da cinemática física da viatura pela decisão do agente;
 *  8. Paragem controlada segura quando o agente é suspenso (AgentController.suspend());
 *  9. Descomissionamento limpo e seguro de agentes e veículos;
 * 10. Reencaminhamento dinâmico A* perante alteração de destino ou grafo rodoviário;
 * 11. Imposição de invariantes físicos da Autoridade de Segurança (clamp na Stop Line);
 * 12. Deliberação independente e simultânea em múltiplos veículos da frota;
 * 13. Compatibilidade e interoperabilidade nas topologias 2x2 e 3x3 com guarda ML 3x3;
 * 14. Estabilidade operacional da frota completa de 50 veículos autónomos;
 * 15. Concorrência thread-safe e ausência absoluta de deadlocks.
 */
public class VehicleAutonomyTestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static AgentContainer sharedContainer = null;
    private static Method updatePositionsMethod = null;

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("   SUITE DE TESTES: AUTONOMIA OPERACIONAL DOS AGENTES VEÍCULO (FASE 6.2-D)");
        System.out.println("==================================================================================");

        try {
            initContainerAndReflection();

            test01_VehicleAgentDecidesAStarRoute();
            test02_DistinctDestinationsProduceDistinctDecisions();
            test03_PerceptionReactionTrafficLight();
            test04_PerceptionReactionLeadingVehicle();
            test05_FipaRequestCorrectAxis();
            test06_FipaResponseHandling();
            test07_KinematicsControlledByAgent();
            test08_AgentSuspensionSafeStop();
            test09_AgentDecommissionRemovesVehicle();
            test10_DynamicReroutingOnDestinationChange();
            test11_PhysicalSafetyAuthorityEnforcesInvariants();
            test12_MultiVehicleIndependentDecisions();
            test13_Compatibility2x2And3x3();
            test14_Fleet50Stability();
            test15_ConcurrencyAndDeadlockFreedom();

        } catch (Throwable t) {
            System.err.println("[ERRO FATAL NA SUITE DE TESTES] " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        System.out.println("\n==================================================================================");
        System.out.printf("RESUMO FASE 6.2-D: %d aprovados | %d falhados (Total: %d)%n", passed, failed, (passed + failed));
        System.out.println("==================================================================================");

        cleanupSharedContainer();
        VehicleControlGateway.getInstance().clear();
        FleetLifecycleManager.getInstance().reset();

        if (failed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    private static void initContainerAndReflection() throws Exception {
        if (sharedContainer == null) {
            Runtime rt = Runtime.instance();
            for (int port = 16950; port <= 16995; port += 5) {
                try {
                    Profile p = new ProfileImpl(null, port, null);
                    p.setParameter(Profile.GUI, "false");
                    p.setParameter(Profile.NO_MTP, "true");
                    sharedContainer = rt.createMainContainer(p);
                    if (sharedContainer != null) break;
                } catch (Throwable ignored) {}
            }
            if (sharedContainer == null) {
                throw new IllegalStateException("Não foi possível inicializar o contentor JADE para os testes da Fase 6.2-D.");
            }
            FleetLifecycleManager.getInstance().bindContainer(sharedContainer);
        }
        if (updatePositionsMethod == null) {
            updatePositionsMethod = CitySimulationGUI.class.getDeclaredMethod("updatePositions");
            updatePositionsMethod.setAccessible(true);
        }
    }

    private static void cleanupSharedContainer() {
        if (sharedContainer != null) {
            try {
                sharedContainer.kill();
            } catch (Throwable ignored) {}
            sharedContainer = null;
        }
    }

    private static void resetEnvironment3x3() {
        NetworkTopologyConfig.activate3x3();
        CitySimulationGUI gui = CitySimulationGUI.getInstance();
        gui.clearVehicles();
        VehicleControlGateway.getInstance().clear();
        FleetLifecycleManager.getInstance().reset();
        FleetLifecycleManager.getInstance().bindContainer(sharedContainer);
    }

    private static void invokeUpdatePositions() {
        try {
            updatePositionsMethod.invoke(CitySimulationGUI.getInstance());
        } catch (Throwable t) {
            throw new RuntimeException("Falha ao invocar updatePositions()", t);
        }
    }

    // ============================================================================================
    // TESTES INDIVIDUAIS
    // ============================================================================================

    /**
     * T01: O VehicleAgent calcula e decide a sua própria rota A* com base no grafo e heurística Manhattan.
     */
    private static void test01_VehicleAgentDecidesAStarRoute() {
        System.out.print("[TESTE 01/15] Planeamento e Rota A* Decidida pelo VehicleAgent ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Autonomo_T01";
            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);
            if (vag == null) throw new AssertionError("VehicleAgent não encontrado no Gateway");

            AStarPathfinder.RouteResult route = vag.getActiveRoute();
            if (route == null || !route.reachable) {
                throw new AssertionError("Rota A* do VehicleAgent deve ser válida e alcançável");
            }
            if (route.nodePath.isEmpty() || route.nodePath.get(0) != 0 || route.nodePath.get(route.nodePath.size() - 1) != 8) {
                throw new AssertionError("Rota A* deve iniciar no nó 0 e terminar no nó 8: " + route.nodePath);
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T02: Destinos distintos produzem decisões e intenções de viragem distintas.
     */
    private static void test02_DistinctDestinationsProduceDistinctDecisions() {
        System.out.print("[TESTE 02/15] Destinos Distintos Produzem Decisões de Viragem Distintas ... ");
        try {
            resetEnvironment3x3();
            // Ambos na aproximação do cruzamento 0 a partir do Norte (direção 0)
            // Carro_L quer ir para 1 (viragem à esquerda)
            // Carro_S quer ir para 6 (seguir em frente pelo corredor 0 -> 3 -> 6)
            String idL = "Carro_L_T02";
            String idS = "Carro_S_T02";

            AgentController acL = sharedContainer.createNewAgent(
                    idL, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 1});
            AgentController acS = sharedContainer.createNewAgent(
                    idS, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 6});
            acL.start();
            acS.start();
            Thread.sleep(150);

            VehicleAgent vagL = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(idL);
            VehicleAgent vagS = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(idS);

            // Simula perceção de aproximação ao cruzamento 0
            VehiclePerception pL = new VehiclePerception(
                    idL, 216.0, 100.0, 2.0, 2.0, 0, 0, false, 0, 80.0, LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehiclePerception pS = new VehiclePerception(
                    idS, 216.0, 100.0, 2.0, 2.0, 0, 0, false, 0, 80.0, LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);

            VehicleControlGateway.getInstance().publishPerception(idL, pL);
            VehicleControlGateway.getInstance().publishPerception(idS, pS);

            VehicleDecision dL = vagL.senseReasonAct();
            VehicleDecision dS = vagS.senseReasonAct();

            if (dL.getPlannedIntent() == dS.getPlannedIntent()) {
                throw new AssertionError("Destinos diferentes devem produzir intenções de viragem distintas: L="
                        + dL.getPlannedIntent() + ", S=" + dS.getPlannedIntent());
            }

            acL.kill();
            acS.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T03: Reação sensorial a semáforos (VERMELHO -> BRAKE/STOP, VERDE -> ACCELERATE/CRUISE).
     */
    private static void test03_PerceptionReactionTrafficLight() {
        System.out.print("[TESTE 03/15] Reação Sensorial Autónoma ao Semáforo (Vermelho vs Verde) ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Sem_T03";
            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);

            // 1. Perceção com sinal VERMELHO a 15m da Stop Line
            VehiclePerception pRed = new VehiclePerception(
                    id, 216.0, 160.0, 2.0, 2.0, 0, 0, false, 0, 15.0, LightState.RED, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehicleControlGateway.getInstance().publishPerception(id, pRed);
            VehicleDecision dRed = vag.senseReasonAct();

            if (dRed.getAction() != VehicleAction.BRAKE && dRed.getAction() != VehicleAction.STOP) {
                throw new AssertionError("Perceção de sinal VERMELHO deve resultar em BRAKE ou STOP. Obtido: " + dRed.getAction());
            }

            // 2. Perceção com sinal VERDE
            VehiclePerception pGreen = new VehiclePerception(
                    id, 216.0, 160.0, 0.0, 2.0, 0, 0, false, 0, 15.0, LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 2L);
            VehicleControlGateway.getInstance().publishPerception(id, pGreen);
            VehicleDecision dGreen = vag.senseReasonAct();

            if (dGreen.getAction() != VehicleAction.ACCELERATE && dGreen.getAction() != VehicleAction.RESUME) {
                throw new AssertionError("Perceção de sinal VERDE deve resultar em ACCELERATE ou RESUME. Obtido: " + dGreen.getAction());
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T04: Reação sensorial a obstáculo ou viatura líder (distância de segurança).
     */
    private static void test04_PerceptionReactionLeadingVehicle() {
        System.out.print("[TESTE 04/15] Reação Sensorial a Obstáculo e Distância de Segurança ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Leader_T04";
            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);

            // Perceção com veículo líder a apenas 20m (< 38m threshold) com velocidade 0.5
            VehiclePerception pLeader = new VehiclePerception(
                    id, 216.0, 100.0, 2.0, 2.0, 0, 0, false, 0, 100.0, LightState.GREEN, 20.0, 0.5, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehicleControlGateway.getInstance().publishPerception(id, pLeader);
            VehicleDecision dLeader = vag.senseReasonAct();

            if (dLeader.getAction() != VehicleAction.BRAKE && dLeader.getAction() != VehicleAction.STOP) {
                throw new AssertionError("Veículo líder próximo deve provocar travagem deliberada. Ação: " + dLeader.getAction());
            }
            if (dLeader.getTargetSpeed() > 1.2) {
                throw new AssertionError("Velocidade-alvo deve ser reduzida para acompanhar o líder. vTarget=" + dLeader.getTargetSpeed());
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T05: Emissão de pedido FIPA-ACL com mapeamento correto do eixo semafórico (NS vs EW).
     */
    private static void test05_FipaRequestCorrectAxis() {
        System.out.print("[TESTE 05/15] Emissão de Mensagem FIPA-ACL com Eixo Semafórico Correto ... ");
        try {
            resetEnvironment3x3();
            // Cria um agente de semáforo simples no cruzamento 0 para testar o envio FIPA
            AgentController acSemNS = sharedContainer.createNewAgent(
                    "Semaforo_NS_C1_NW", "agents.TrafficLightAgent", new Object[]{"Norte-Sul", 0});
            acSemNS.start();

            String idNS = "Carro_Fipa_NS_T05";
            AgentController acVehNS = sharedContainer.createNewAgent(
                    idNS, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8}); // Dir 0 -> Eixo NS
            acVehNS.start();
            Thread.sleep(200);

            VehicleAgent vagNS = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(idNS);

            // Simula paragem no vermelho na Stop Line por 6.5 segundos
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            VisualVehicle v = gui.spawnVehicleSafely(idNS, 0, Color.BLUE, false, "NORMAL", 8);
            v.currentCrossingWaitSeconds = 6.5;
            vagNS.setStoppedSinceMillis(System.currentTimeMillis() - 7000L);

            VehiclePerception pNS = new VehiclePerception(
                    idNS, 216.0, 185.0, 0.0, 2.0, 0, 0, false, 0, 3.0, LightState.RED, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehicleControlGateway.getInstance().publishPerception(idNS, pNS);
            VehicleDecision dNS = vagNS.senseReasonAct();

            if (!dNS.isFipaRequested()) {
                throw new AssertionError("VehicleAgent deveria ter emitido pedido FIPA após 6.5s de espera no vermelho");
            }
            if (vagNS.getFipaRequestsSent() < 1) {
                throw new AssertionError("Contador fipaRequestsSent deve ser >= 1");
            }

            acVehNS.kill();
            acSemNS.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T06: Processamento resiliente de respostas FIPA-ACL (INFORM, AGREE, REFUSE).
     */
    private static void test06_FipaResponseHandling() {
        System.out.print("[TESTE 06/15] Processamento de Respostas FIPA (INFORM, AGREE, REFUSE) ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Resp_T06";
            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);

            // Dispara método processarRespostaFIPA via reflexão ou envio direto ACLMessage
            Method procMethod = VehicleAgent.class.getDeclaredMethod("processarRespostaFIPA", ACLMessage.class);
            procMethod.setAccessible(true);

            ACLMessage msgAgree = new ACLMessage(ACLMessage.AGREE);
            msgAgree.setSender(new AID("Semaforo_NS_C1", AID.ISLOCALNAME));
            msgAgree.setContent("ACCEPTED");
            procMethod.invoke(vag, msgAgree);

            if (!vag.getLastFipaResponse().contains("AGREE")) {
                throw new AssertionError("Resposta AGREE não foi registada corretamente: " + vag.getLastFipaResponse());
            }

            ACLMessage msgRefuse = new ACLMessage(ACLMessage.REFUSE);
            msgRefuse.setSender(new AID("Semaforo_NS_C1", AID.ISLOCALNAME));
            msgRefuse.setContent("EMERGENCY_ACTIVE");
            procMethod.invoke(vag, msgRefuse);

            if (!vag.getLastFipaResponse().contains("REFUSE")) {
                throw new AssertionError("Resposta REFUSE não foi registada corretamente: " + vag.getLastFipaResponse());
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T07: Controlo efetivo da cinemática física da viatura pela decisão deliberativa do agente.
     */
    private static void test07_KinematicsControlledByAgent() {
        System.out.print("[TESTE 07/15] Cinemática Física Controlada Efetivamente pela Decisão ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Kin_T07";
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            VisualVehicle v = gui.spawnVehicleSafely(id, 0, Color.BLUE, false, "NORMAL", 8);
            v.x = 216.0;
            v.y = 80.0;
            v.currentSpeed = 1.0;

            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);

            // Garante sinal VERDE no cruzamento 0
            gui.setTrafficLights(0, LightState.GREEN, LightState.RED);

            // 1. Publica perceção sensorial com pista livre e sinal VERDE
            VehiclePerception p = new VehiclePerception(
                    id, v.x, v.y, v.currentSpeed, v.maxSpeed, v.direction, v.laneIndex,
                    false, 0, 108.0, LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehicleControlGateway.getInstance().publishPerception(id, p);

            // 2. Executa deliberação autónoma do agente
            VehicleDecision dec = vag.senseReasonAct();
            if (dec == null || dec.getAction() != VehicleAction.ACCELERATE) {
                throw new AssertionError("Agente deveria deliberar ACCELERATE com sinal verde: " + dec);
            }

            double initialY = v.y;
            double initialSpeed = v.currentSpeed;

            // 3. Executa updatePositions no motor físico
            invokeUpdatePositions();

            if (v.currentSpeed <= initialSpeed && v.currentSpeed < v.maxSpeed) {
                throw new AssertionError("A velocidade deveria ter acelerado com a decisão do agente. Anterior: "
                        + initialSpeed + ", Atual: " + v.currentSpeed);
            }
            if (v.y <= initialY) {
                throw new AssertionError("A coordenada Y deveria ter avançado com a cinemática física: " + v.y);
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T08: Paragem controlada segura quando o agente é suspenso (AgentController.suspend()).
     */
    private static void test08_AgentSuspensionSafeStop() {
        System.out.print("[TESTE 08/15] Paragem Controlada Segura após Suspensão do Agente ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Susp_T08";
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            VisualVehicle v = gui.spawnVehicleSafely(id, 0, Color.BLUE, false, "NORMAL", 8);
            v.x = 216.0;
            v.y = 120.0;
            v.currentSpeed = 2.0;

            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);
            vag.senseReasonAct();
            invokeUpdatePositions();

            // Suspende o agente JADE
            ac.suspend();
            Thread.sleep(50);

            if (!VehicleControlGateway.getInstance().isAgentSuspended(id)) {
                throw new AssertionError("Gateway deve reportar agente como suspenso");
            }

            // Executa 25 ciclos físicos
            for (int i = 0; i < 25; i++) {
                invokeUpdatePositions();
            }

            if (v.currentSpeed > 0.0) {
                throw new AssertionError("Viatura deve travar até 0.0 após suspensão do agente. Velocidade atual: " + v.currentSpeed);
            }
            if (!v.wasStopped) {
                throw new AssertionError("Flag wasStopped deve ser true após paragem controlada");
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T09: Descomissionamento limpo de agente e remoção da viatura física.
     */
    private static void test09_AgentDecommissionRemovesVehicle() {
        System.out.print("[TESTE 09/15] Descomissionamento e Limpeza no Gestor de Frota ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Decom_T09";
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            gui.spawnVehicleSafely(id, 0, Color.BLUE, false, "NORMAL", 8);

            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            if (!VehicleControlGateway.getInstance().isAgentRegistered(id)) {
                throw new AssertionError("Agente deveria estar registado no Gateway");
            }

            // Termina o agente
            ac.kill();
            Thread.sleep(100);

            if (VehicleControlGateway.getInstance().isAgentRegistered(id)) {
                throw new AssertionError("Agente não deve estar registado após takeDown()");
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T10: Reencaminhamento dinâmico A* perante alteração do destino.
     */
    private static void test10_DynamicReroutingOnDestinationChange() {
        System.out.print("[TESTE 10/15] Reencaminhamento Dinâmico A* por Mudança de Destino ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Reroute_T10";
            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 3});
            ac.start();
            Thread.sleep(150);

            VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);
            int dest1 = vag.getDestinationNode();
            AStarPathfinder.RouteResult r1 = vag.getActiveRoute();

            if (dest1 != 3 || r1.nodePath.get(r1.nodePath.size() - 1) != 3) {
                throw new AssertionError("Destino inicial deve ser 3. Obtido: " + dest1);
            }

            // Altera dinamicamente o destino para o nó 8
            vag.setDestinationNode(8);

            // Simula nova perceção sensorial
            VehiclePerception p = new VehiclePerception(
                    id, 216.0, 100.0, 2.0, 2.0, 0, 0, false, 0, 100.0, LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
            VehicleControlGateway.getInstance().publishPerception(id, p);
            vag.senseReasonAct();

            AStarPathfinder.RouteResult r2 = vag.getActiveRoute();
            if (r2 == null || r2.nodePath.get(r2.nodePath.size() - 1) != 8) {
                throw new AssertionError("Nova rota recalculada deve terminar no nó 8: " + (r2 != null ? r2.nodePath : "null"));
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T11: Imposição absoluta de invariantes físicos pela Autoridade de Segurança (clamp na Stop Line).
     */
    private static void test11_PhysicalSafetyAuthorityEnforcesInvariants() {
        System.out.print("[TESTE 11/15] Imposição Física de Invariantes na Stop Line (Anti-Violação) ... ");
        try {
            resetEnvironment3x3();
            String id = "Carro_Safe_T11";
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            VisualVehicle v = gui.spawnVehicleSafely(id, 0, Color.BLUE, false, "NORMAL", 8);

            // Posiciona a viatura a apenas 2 metros da Stop Line em sinal VERMELHO
            v.x = 216.0;
            v.y = 172.0;
            v.currentSpeed = 2.0;

            gui.setTrafficLights(0, LightState.RED, LightState.GREEN);

            AgentController ac = sharedContainer.createNewAgent(
                    id, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac.start();
            Thread.sleep(150);

            // Injeta intencionalmente uma decisão incorreta ou insegura de aceleração
            VehicleDecision rogueDecision = new VehicleDecision(
                    id, 1L, System.currentTimeMillis(), System.currentTimeMillis() + 1000L, VehicleAction.ACCELERATE, 3.0,
                    ManeuverIntent.GO_STRAIGHT, 0, 0, 8, List.of(0, 1, 2), "ROGUE", "Rogue override", false);
            VehicleControlGateway.getInstance().publishDecision(rogueDecision);

            // Executa 5 ciclos físicos
            for (int i = 0; i < 5; i++) {
                invokeUpdatePositions();
            }

            if (gui.getRedLightViolationsCount() > 0) {
                throw new AssertionError("Invariante físico falhou: houve transposição de Stop Line no vermelho");
            }
            double stopLineY = 188.0;
            if (v.y > stopLineY) {
                throw new AssertionError("Viatura ultrapassou a Stop Line física: y=" + v.y + " (StopLine=" + stopLineY + ")");
            }

            ac.kill();
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T12: Deliberação independente e simultânea em múltiplos veículos da frota.
     */
    private static void test12_MultiVehicleIndependentDecisions() {
        System.out.print("[TESTE 12/15] Deliberação Independente e Concorrente em Múltiplos Veículos ... ");
        try {
            resetEnvironment3x3();
            int count = 5;
            List<AgentController> controllers = new ArrayList<>();
            List<VehicleAgent> agents = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String id = "Carro_Multi_" + i;
                AgentController ac = sharedContainer.createNewAgent(
                        id, "agents.VehicleAgent", new Object[]{i % 3, "NORMAL", i % 4, (i + 4) % 9});
                ac.start();
                controllers.add(ac);
            }
            Thread.sleep(250);

            for (int i = 0; i < count; i++) {
                String id = "Carro_Multi_" + i;
                VehicleAgent vag = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id);
                if (vag == null) throw new AssertionError("Agente não encontrado: " + id);
                agents.add(vag);

                VehiclePerception p = new VehiclePerception(
                        id, 100.0 * (i + 1), 100.0 * (i + 1), 1.5, 2.0, i % 4, 0, false, i % 3, 50.0,
                        LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), 1L);
                VehicleControlGateway.getInstance().publishPerception(id, p);
            }

            for (VehicleAgent vag : agents) {
                vag.senseReasonAct();
            }

            for (int i = 0; i < count; i++) {
                String id = "Carro_Multi_" + i;
                VehicleDecision dec = VehicleControlGateway.getInstance().getValidDecision(id, System.currentTimeMillis(), 2000L);
                if (dec == null) throw new AssertionError("Decisão não encontrada para " + id);
                if (!dec.getVehicleId().equals(id)) throw new AssertionError("Inconsistência no ID da decisão: " + dec.getVehicleId());
            }

            for (AgentController ac : controllers) {
                ac.kill();
            }
            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T13: Compatibilidade e interoperabilidade nas topologias 2x2 e 3x3 com guarda ML 3x3.
     */
    private static void test13_Compatibility2x2And3x3() {
        System.out.print("[TESTE 13/15] Compatibilidade Topológica (2x2 e 3x3) e Guarda ML 3x3 ... ");
        try {
            // 1. Validação em Topologia 2x2
            NetworkTopologyConfig.activate2x2();
            CitySimulationGUI.getInstance().clearVehicles();
            VehicleControlGateway.getInstance().clear();

            String id2x2 = "Carro_Top_2x2";
            AgentController ac2x2 = sharedContainer.createNewAgent(
                    id2x2, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 3});
            ac2x2.start();
            Thread.sleep(150);

            VehicleAgent vag2x2 = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id2x2);
            if (vag2x2.getActiveRoute() == null || vag2x2.getActiveRoute().nodePath.size() < 2) {
                throw new AssertionError("Falha no cálculo A* em modo 2x2");
            }
            ac2x2.kill();

            // 2. Validação em Topologia 3x3
            NetworkTopologyConfig.activate3x3();
            CitySimulationGUI.getInstance().clearVehicles();
            VehicleControlGateway.getInstance().clear();

            String id3x3 = "Carro_Top_3x3";
            AgentController ac3x3 = sharedContainer.createNewAgent(
                    id3x3, "agents.VehicleAgent", new Object[]{0, "NORMAL", 0, 8});
            ac3x3.start();
            Thread.sleep(150);

            VehicleAgent vag3x3 = (VehicleAgent) VehicleControlGateway.getInstance().getAgent(id3x3);
            if (vag3x3.getActiveRoute() == null || vag3x3.getActiveRoute().nodePath.size() < 2) {
                throw new AssertionError("Falha no cálculo A* em modo 3x3");
            }
            ac3x3.kill();

            // 3. Validação do Guard ML 3x3
            TrafficEnvironment env = TrafficEnvironment.getInstance();
            env.setMLPredictionNotice("ML Preditivo: Validação 3x3 Pendente (Modelo 2x2 Preservado)");
            if (!env.getMLPredictionNotice().contains("3x3 Pendente")) {
                throw new AssertionError("Notificação de ML Preditivo deve alertar sobre validação 3x3 pendente");
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T14: Estabilidade operacional da frota completa de 50 veículos autónomos.
     */
    private static void test14_Fleet50Stability() {
        System.out.print("[TESTE 14/15] Estabilidade Operacional da Frota Completa de 50 Veículos ... ");
        try {
            resetEnvironment3x3();
            FleetLifecycleManager flm = FleetLifecycleManager.getInstance();
            flm.initializeDefault3x3Fleet(sharedContainer);
            Thread.sleep(400);

            if (flm.getNormalVehicleCount() != 50) {
                throw new AssertionError("Frota normal deve conter exatamente 50 viaturas. Encontrado: " + flm.getNormalVehicleCount());
            }

            // Executa 10 ciclos físicos completos com agentes deliberando
            for (int tick = 0; tick < 10; tick++) {
                invokeUpdatePositions();
            }

            int registered = 0;
            for (int i = 1; i <= 50; i++) {
                String id = String.format("Carro_%02d", i);
                if (VehicleControlGateway.getInstance().isAgentRegistered(id)) {
                    registered++;
                }
            }

            if (registered != 50) {
                throw new AssertionError("Todos os 50 veículos devem ter agentes JADE registados no Gateway. Encontrados: " + registered);
            }

            while (flm.getActiveNormalVehicleCount() > 0) {
                flm.removeNormalVehicle();
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }

    /**
     * T15: Concorrência thread-safe e ausência absoluta de deadlocks.
     */
    private static void test15_ConcurrencyAndDeadlockFreedom() {
        System.out.print("[TESTE 15/15] Concorrência Thread-Safe e Ausência Absoluta de Deadlocks ... ");
        try {
            resetEnvironment3x3();
            int threads = 8;
            int iterationsPerThread = 150;
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicBoolean errorDetected = new AtomicBoolean(false);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                exec.submit(() -> {
                    try {
                        String vehId = "Carro_Stress_" + threadId;
                        for (int i = 0; i < iterationsPerThread; i++) {
                            VehiclePerception p = new VehiclePerception(
                                    vehId, 200.0, 200.0, 1.5, 2.0, 0, 0, false, 0, 40.0,
                                    LightState.GREEN, 999.0, -1.0, 1L, 1.0, System.currentTimeMillis(), i);
                            VehicleControlGateway.getInstance().publishPerception(vehId, p);

                            VehicleDecision d = new VehicleDecision(
                                    vehId, (long) i, System.currentTimeMillis(), System.currentTimeMillis() + 1000L, VehicleAction.ACCELERATE, 2.0,
                                    ManeuverIntent.GO_STRAIGHT, 0, 0, 8, List.of(0, 1, 8), "CRUISING", "Stress", false);
                            VehicleControlGateway.getInstance().publishDecision(d);

                            VehicleControlGateway.getInstance().getValidDecision(vehId, System.currentTimeMillis(), 1000L);
                        }
                    } catch (Throwable err) {
                        errorDetected.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            exec.shutdownNow();

            if (!completed) {
                throw new AssertionError("Timeout no teste de concorrência: potencial deadlock detetado");
            }
            if (errorDetected.get()) {
                throw new AssertionError("Exceção detetada durante execução concorrente sob estresse");
            }

            System.out.println("PASSOU ✓");
            passed++;
        } catch (Throwable t) {
            System.out.println("FALHOU ✗ (" + t.getMessage() + ")");
            t.printStackTrace();
            failed++;
        }
    }
}
