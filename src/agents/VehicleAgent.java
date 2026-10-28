package agents;

import environment.CitySimulationGUI;
import environment.FleetLifecycleManager;
import environment.NetworkTopologyConfig;
import environment.TrafficEnvironment;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import metrics.TrafficMetricsCollector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agente Veículo Regular Autónomo (Feature 3 / Fase 6.1 - Vehicle Agents).
 *
 * Simula uma viatura convencional em circulação na malha urbana (2×2 ou 3×3):
 * - Perceciona dinamicamente o cruzamento mais próximo e o eixo atual (suportando viragens A*).
 * - Resolve o semáforo competente via Directory Facilitator (DF) do JADE com cache local.
 * - Monitoriza o tempo de espera acumulado em filas de retenção.
 * - Envia mensagens FIPA-ACL (REQUEST) quando o tempo de imobilização ultrapassa o limiar de tolerância.
 * - Regista-se no {@link FleetLifecycleManager} e liberta todos os recursos físicos/visuais em {@code takeDown()}.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class VehicleAgent extends Agent {

    /** Eixo viário inicial de circulação: "NS" (Norte-Sul) ou "EW" (Este-Oeste) */
    private String road;

    /** Direção visual inicial de circulação (0: N->S, 1: S->N, 2: W->E, 3: E->W) */
    private int visualDirection;

    /** Eixo numérico atual para pesquisa no DF (0=NS, 1=EW) */
    private int axis;

    /** Limiar de espera acima do qual o veículo emite REQUEST (em segundos) */
    private static final double LIMIAR_ESPERA_SEGUNDOS = 6.0;

    /** Intervalo mínimo entre envios de REQUEST ao mesmo cruzamento (ms) */
    private static final long INTERVALO_MINIMO_REQUEST_MS = 6000;

    /** Milissegundo do último REQUEST enviado, indexado por cruzamento (até 9 cruzamentos em 3×3) */
    private final long[] lastRequestPerIntersection = new long[9];

    /** Cache estática de resolução DF para reduzir sobrecarga com 50 veículos */
    private static final Map<String, String> dfSemaphoreCache = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            this.road = args[0].toString();
        } else {
            this.road = "NS";
        }

        this.axis = road.equalsIgnoreCase("NS") ? 0 : 1;
        this.visualDirection = (axis == 0) ? 0 : 2;

        if (args != null && args.length > 1 && args[1] instanceof Integer) {
            this.visualDirection = (Integer) args[1];
            this.axis = (this.visualDirection == 0 || this.visualDirection == 1) ? 0 : 1;
        }

        // 1. Registo no Gestor de Ciclo de Vida da Frota (Fase 6.1)
        FleetLifecycleManager.getInstance().registerLiveJadeAgent(getLocalName(), "VehicleAgent");

        // 2. Registo no Directory Facilitator
        registarNoDF();

        // 3. Integração visual na cidade (se ainda não foi inserido pelo FleetLifecycleManager)
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CitySimulationGUI gui = CitySimulationGUI.getInstance();
            if (gui.getVisualVehicle(getLocalName()) == null) {
                java.awt.Color carColor = new java.awt.Color(30, 160, 230);
                gui.addVehicle(getLocalName(), visualDirection, carColor);
            }
        }

        System.out.println("[VehicleAgent] Viatura regular " + getLocalName() 
                + " inserida na via " + road + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");

        // 4. Comportamento Periódico: Monitorização de tempo de espera e emissão de REQUEST condicional
        addBehaviour(new TickerBehaviour(this, 1500) {
            @Override
            protected void onTick() {
                verificarTempoDeEspera();
            }
        });

        // 5. Comportamento Cíclico: Escuta e tratamento de respostas FIPA dos semáforos
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage reply = receive();
                if (reply != null) {
                    TrafficMetricsCollector.getInstance().recordFipaMessage();
                    processarRespostaFIPA(reply);
                } else {
                    block();
                }
            }
        });
    }

    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("vehicle-service");
        sd.setName("Viatura-" + getLocalName());
        sd.addProperties(new Property("axis", axis));
        sd.addProperties(new Property("road", road));

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[VehicleAgent] Erro ao registar veículo " + getLocalName() + " no DF: " + fe.getMessage());
        }
    }

    private void verificarTempoDeEspera() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        double waitSeconds = env.getVehicleWaitSeconds(getLocalName());
        long now = System.currentTimeMillis();

        if (waitSeconds < LIMIAR_ESPERA_SEGUNDOS) {
            return;
        }

        atualizarEixoVisualAtual();

        NetworkTopologyConfig topo = env.getTopology();
        int maxIntersections = topo.getIntersectionCount();

        int targetIntersection = determinarCruzamentoMaisProximo();
        if (targetIntersection < 0 || targetIntersection >= maxIntersections || targetIntersection >= lastRequestPerIntersection.length) {
            return;
        }

        if (now - lastRequestPerIntersection[targetIntersection] < INTERVALO_MINIMO_REQUEST_MS) {
            return;
        }

        lastRequestPerIntersection[targetIntersection] = now;

        String crossingName = topo.getIntersectionName(targetIntersection);
        String targetSemName = resolverSemaforoViaDF(targetIntersection, crossingName);

        System.out.println("[VehicleAgent - " + getLocalName() + "] Tempo de espera crítico atingido (" 
                + String.format("%.1f", waitSeconds) + "s) no Cruzamento " + crossingName 
                + ". A emitir REQUEST a " + targetSemName + "...");

        ACLMessage requestMsg = new ACLMessage(ACLMessage.REQUEST);
        requestMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        requestMsg.setOntology("traffic-management");
        requestMsg.setConversationId("req-wait-" + getLocalName() + "-" + crossingName + "-" + now);
        requestMsg.setContent("PEDIDO_PASSAGEM: Viatura " + getLocalName() + " retida ha " 
                + String.format("%.1f", waitSeconds) + "s na via " + (axis == 0 ? "NS" : "EW") 
                + " junto ao Cruzamento " + crossingName);

        send(requestMsg);
        TrafficMetricsCollector.getInstance().recordFipaMessage();
    }

    private void atualizarEixoVisualAtual() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
            if (v != null) {
                this.visualDirection = v.direction;
                this.axis = (v.direction == 0 || v.direction == 1) ? 0 : 1;
            }
        }
    }

    private int determinarCruzamentoMaisProximo() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) {
            return 0;
        }
        CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
        if (v == null) {
            return 0;
        }
        return gui.getTargetIntersection(v);
    }

    private String resolverSemaforoViaDF(int intersectionIndex, String crossingName) {
        String cacheKey = intersectionIndex + "_" + axis + "_" + crossingName;
        String cached = dfSemaphoreCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("traffic-light-service");
            sd.addProperties(new Property("intersection", intersectionIndex));
            sd.addProperties(new Property("axis", axis));
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(this, template);
            if (results != null && results.length > 0) {
                String dfName = results[0].getName().getLocalName();
                dfSemaphoreCache.put(cacheKey, dfName);
                return dfName;
            }
        } catch (FIPAException fe) {
            System.err.println("[VehicleAgent - " + getLocalName() 
                    + "] Aviso: Pesquisa no DF falhou. A utilizar nome canónico. Erro: " + fe.getMessage());
        }

        String axisLabel = (axis == 0) ? "NS" : "EW";
        return "Semaforo_" + axisLabel + "_" + crossingName;
    }

    private void processarRespostaFIPA(ACLMessage reply) {
        String senderName = reply.getSender().getLocalName();
        String perfName = ACLMessage.getPerformative(reply.getPerformative());
        String content = reply.getContent();

        System.out.println("[VehicleAgent - " + getLocalName() + "] Resposta FIPA recebida de " 
                + senderName + " (" + perfName + "): " + content);
    }

    @Override
    protected void takeDown() {
        FleetLifecycleManager.getInstance().unregisterLiveJadeAgent(getLocalName());
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.removeVehicle(getLocalName());
        }
        try {
            DFService.deregister(this);
            System.out.println("[VehicleAgent] Viatura " + getLocalName() + " desregistada do DF e terminada.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
