package agents;

import environment.CitySimulationGUI;
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

/**
 * Agente Veículo Regular Autónomo (Feature 3 - Vehicle Agents).
 *
 * Simula uma viatura convencional em circulação na malha urbana:
 * - Perceciona dinamicamente o cruzamento mais próximo através dos sensores do simulador.
 * - Resolve o semáforo competente via Directory Facilitator (DF) do JADE,
 *   em vez de utilizar nomes hardcoded.
 * - Monitoriza o tempo de espera acumulado em filas de retenção.
 * - Envia mensagens FIPA-ACL (REQUEST) quando o tempo de imobilização ultrapassa o limiar de tolerância.
 * - Regista a sua presença no DF para descoberta automática por outros agentes.
 *
 * Correções da Fase 2:
 * 1. Eliminação do semáforo hardcoded — agora resolvido via DF + perceção do cruzamento.
 * 2. Suporte completo para os 4 cruzamentos da malha urbana.
 * 3. Deteção do cruzamento mais próximo com base na posição real no simulador.
 * 4. Anti-debouncing: não reenvia se já enviou REQUEST para o mesmo cruzamento recentemente.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class VehicleAgent extends Agent {

    /** Eixo viário de circulação: "NS" (Norte-Sul) ou "EW" (Este-Oeste) */
    private String road;

    /** Direção visual de circulação (0: N->S, 1: S->N, 2: W->E, 3: E->W) */
    private int visualDirection;

    /** Eixo numérico para pesquisa no DF (0=NS, 1=EW) */
    private int axis;

    /** Limiar de espera acima do qual o veículo emite REQUEST (em segundos) */
    private static final double LIMIAR_ESPERA_SEGUNDOS = 6.0;

    /** Intervalo mínimo entre envios de REQUEST ao mesmo cruzamento (ms) */
    private static final long INTERVALO_MINIMO_REQUEST_MS = 6000;

    /** Nomes canónicos dos cruzamentos, indexados de 0 a 3 */
    private static final String[] CROSSING_NAMES = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    /** Milissegundo do último REQUEST enviado, indexado por cruzamento */
    private final long[] lastRequestPerIntersection = new long[4];

    /** Índice do último cruzamento para o qual foi enviado um pedido */
    private int lastTargetIntersection = -1;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.road = (String) args[0];
        } else {
            this.road = "NS";
        }

        // Determinação do eixo e direção visual a partir do parâmetro "road"
        this.axis = road.equalsIgnoreCase("NS") ? 0 : 1;
        this.visualDirection = (axis == 0) ? 0 : 2; // 0: N->S, 2: W->E por omissão

        // 1. Registo no Directory Facilitator
        registarNoDF();

        // 2. Integração visual na cidade com cor distintiva de viatura regular
        java.awt.Color carColor = new java.awt.Color(30, 160, 230);
        CitySimulationGUI.getInstance().addVehicle(getLocalName(), visualDirection, carColor);

        System.out.println("[VehicleAgent] Viatura regular " + getLocalName() 
                + " inserida na via " + road + " (Eixo " + (axis == 0 ? "NS" : "EW") + ").");

        // 3. Comportamento Periódico: Monitorização de tempo de espera e emissão de REQUEST condicional
        addBehaviour(new TickerBehaviour(this, 1500) {
            @Override
            protected void onTick() {
                verificarTempoDeEspera();
            }
        });

        // 4. Comportamento Cíclico: Escuta e tratamento de respostas FIPA dos semáforos
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage reply = receive();
                if (reply != null) {
                    processarRespostaFIPA(reply);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista a viatura no serviço de páginas amarelas do JADE,
     * incluindo propriedades de eixo para descoberta por outros agentes.
     */
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

    /**
     * Inspeciona os sensores do ambiente; se o tempo de imobilização exceder o limiar estipulado,
     * descobre dinamicamente o cruzamento mais próximo e emite um pedido FIPA REQUEST
     * ao semáforo competente desse cruzamento.
     *
     * Melhorias da Fase 2:
     * - Deteta o cruzamento mais próximo via {@link CitySimulationGUI#getTargetIntersection(CitySimulationGUI.VisualVehicle)}.
     * - Resolve o semáforo via Directory Facilitator em vez de nome hardcoded.
     * - Anti-debouncing por cruzamento: não reenvia se o último REQUEST ao mesmo cruzamento foi há menos de 6s.
     */
    private void verificarTempoDeEspera() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        double waitSeconds = env.getVehicleWaitSeconds(getLocalName());
        long now = System.currentTimeMillis();

        // Verificar se o limiar de espera foi ultrapassado
        if (waitSeconds < LIMIAR_ESPERA_SEGUNDOS) {
            return;
        }

        // Determinar dinamicamente o cruzamento mais próximo
        int targetIntersection = determinarCruzamentoMaisProximo();
        if (targetIntersection < 0 || targetIntersection > 3) {
            return; // Veículo fora do alcance de qualquer cruzamento
        }

        // Anti-debouncing: verificar intervalo mínimo entre pedidos ao mesmo cruzamento
        if (now - lastRequestPerIntersection[targetIntersection] < INTERVALO_MINIMO_REQUEST_MS) {
            return;
        }

        // Registar o timestamp do pedido
        lastRequestPerIntersection[targetIntersection] = now;
        lastTargetIntersection = targetIntersection;

        // Resolver o nome do semáforo competente via DF, com fallback para nome canónico
        String targetSemName = resolverSemaforoViaDF(targetIntersection);

        System.out.println("[VehicleAgent - " + getLocalName() + "] Tempo de espera crítico atingido (" 
                + String.format("%.1f", waitSeconds) + "s) no Cruzamento " + CROSSING_NAMES[targetIntersection] 
                + ". A emitir REQUEST a " + targetSemName + "...");

        ACLMessage requestMsg = new ACLMessage(ACLMessage.REQUEST);
        requestMsg.addReceiver(new AID(targetSemName, AID.ISLOCALNAME));
        requestMsg.setOntology("traffic-management");
        requestMsg.setConversationId("req-wait-" + getLocalName() + "-" + CROSSING_NAMES[targetIntersection] + "-" + now);
        requestMsg.setContent("PEDIDO_PASSAGEM: Viatura " + getLocalName() + " retida ha " 
                + String.format("%.1f", waitSeconds) + "s na via " + road 
                + " junto ao Cruzamento " + CROSSING_NAMES[targetIntersection]);

        send(requestMsg);
    }

    /**
     * Determina o índice do cruzamento mais próximo com base na posição
     * real do veículo no simulador gráfico.
     *
     * @return Índice do cruzamento (0=NW, 1=NE, 2=SW, 3=SE) ou -1 se indeterminado
     */
    private int determinarCruzamentoMaisProximo() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui == null) {
            return 0; // Fallback seguro em modo headless
        }

        CitySimulationGUI.VisualVehicle v = gui.getVisualVehicle(getLocalName());
        if (v == null) {
            return 0; // Veículo ainda não registado visualmente
        }

        return gui.getTargetIntersection(v);
    }

    /**
     * Resolve o nome do agente semáforo competente para o cruzamento e eixo indicados,
     * consultando primeiro o Directory Facilitator (DF) do JADE.
     *
     * Se a pesquisa no DF falhar (ex: semáforo ainda não registado), utiliza o nome
     * canónico como fallback seguro: "Semaforo_{eixo}_{cruzamento}".
     *
     * @param intersectionIndex Índice do cruzamento (0 a 3)
     * @return Nome local do agente semáforo
     */
    private String resolverSemaforoViaDF(int intersectionIndex) {
        // Tentativa de resolução via DF (Páginas Amarelas)
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
                return dfName;
            }
        } catch (FIPAException fe) {
            System.err.println("[VehicleAgent - " + getLocalName() 
                    + "] Aviso: Pesquisa no DF falhou. A utilizar nome canónico. Erro: " + fe.getMessage());
        }

        // Fallback: nome canónico do semáforo
        String axisLabel = (axis == 0) ? "NS" : "EW";
        return "Semaforo_" + axisLabel + "_" + CROSSING_NAMES[intersectionIndex];
    }

    /**
     * Processa as respostas FIPA-ACL recebidas dos semáforos.
     * Loga a performativa e o conteúdo para rastreabilidade completa.
     */
    private void processarRespostaFIPA(ACLMessage reply) {
        String senderName = reply.getSender().getLocalName();
        String perfName = ACLMessage.getPerformative(reply.getPerformative());
        String content = reply.getContent();

        System.out.println("[VehicleAgent - " + getLocalName() + "] Resposta FIPA recebida de " 
                + senderName + " (" + perfName + "): " + content);

        // Tratamento específico de confirmações de passagem
        if (content != null && content.contains("PEDIDO_REGISTADO")) {
            System.out.println("[VehicleAgent - " + getLocalName() 
                    + "] Pedido de passagem confirmado pelo semáforo " + senderName + ".");
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[VehicleAgent] Viatura " + getLocalName() + " desregistada do DF e terminada.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}