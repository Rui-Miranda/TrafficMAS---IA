package agents;

import environment.CitySimulationGUI;
import environment.TrafficEnvironment;
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
 * Agente Semáforo Inteligente e Adaptativo (Features 2, 6 e 7).
 *
 * Cada interseção é regulada por agentes semáforo que:
 * - Adaptam em tempo real a duração dos tempos de verde e vermelho consoante o volume de tráfego (Feature 7).
 * - Garantem a preempção estrita e prioritária para viaturas de socorro (Feature 6).
 * - Registam as suas competências no Directory Facilitator (DF / Páginas Amarelas do JADE).
 * - Coordenam as fases com garantia de intertravamento seguro (sem conflito de verdes simultâneos).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficLightAgent extends Agent {

    private String direction;
    private int intersectionIndex = 0; // 0=NW, 1=NE, 2=SW, 3=SE
    private int axis = 0; // 0=Norte-Sul, 1=Este-Oeste
    private CitySimulationGUI.LightState currentState = CitySimulationGUI.LightState.RED;

    // Estados do ciclo semafórico adaptativo (apenas o eixo coordenador NS gere o ciclo do cruzamento)
    private enum Phase {
        NS_GREEN, NS_YELLOW, ALL_RED_1, EW_GREEN, EW_YELLOW, ALL_RED_2
    }

    private Phase currentPhase = Phase.NS_GREEN;
    private long phaseStartMillis = 0;
    private long currentGreenDuration = 4000; // Duração base adaptativa (ms)
    private boolean emergencyOverride = false;
    private long emergencyReleaseMillis = 0;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            this.direction = (String) args[0];
            if (args.length > 1 && args[1] instanceof Integer) {
                this.intersectionIndex = (Integer) args[1];
            }
            this.axis = direction.toLowerCase().contains("este") || direction.toLowerCase().contains("ew") ? 1 : 0;
        } else {
            this.direction = "Desconhecida";
        }

        // 1. Registo formal no Directory Facilitator (DFService - Páginas Amarelas JADE)
        registarNoDF();

        // 2. Configurar estado luminoso inicial no ambiente de tráfego
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        if (axis == 0) {
            currentState = CitySimulationGUI.LightState.GREEN;
            currentPhase = Phase.NS_GREEN;
        } else {
            currentState = CitySimulationGUI.LightState.RED;
        }
        env.setTrafficLight(intersectionIndex, axis, currentState);
        phaseStartMillis = System.currentTimeMillis();

        System.out.println("[TrafficLightAgent] Semáforo " + direction + " (Cruzamento C" + (intersectionIndex + 1) 
                + " | Eixo " + (axis == 0 ? "NS" : "EW") + ") registado e em funcionamento adaptativo.");

        // 3. Comportamento Adaptativo: O agente do eixo NS (axis == 0) assume a coordenação das fases da interseção
        if (axis == 0) {
            addBehaviour(new TickerBehaviour(this, 500) {
                @Override
                protected void onTick() {
                    gerirCicloSemaforicoAdaptativo();
                }
            });
        }

        // 4. Comportamento Cíclico: Receção de pedidos FIPA-ACL (Veículos e Socorro)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    processarMensagemFIPA(msg);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista o semáforo no serviço de páginas amarelas do JADE.
     */
    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("traffic-light-service");
        sd.setName("Semaforo-" + direction + "-C" + (intersectionIndex + 1));
        sd.addProperties(new Property("intersection", intersectionIndex));
        sd.addProperties(new Property("axis", axis));

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[TrafficLightAgent] Erro ao registar semáforo " + getLocalName() + " no DF: " + fe.getMessage());
        }
    }

    /**
     * Gere as fases do cruzamento com temporização adaptativa e preempção de socorro.
     */
    private void gerirCicloSemaforicoAdaptativo() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();
        long now = System.currentTimeMillis();

        // A. Se estiver ativo modo de preempção de socorro
        if (emergencyOverride) {
            if (now >= emergencyReleaseMillis) {
                emergencyOverride = false;
                System.out.println("[TrafficLightAgent C" + (intersectionIndex + 1) + "] Preempção de emergência concluída. A restaurar ciclo normal.");
                transitarFase(Phase.ALL_RED_1);
            }
            return;
        }

        // B. Verificação contínua de sensores: aproximação de socorro (Feature 6)
        if (env.hasEmergencyApproaching(intersectionIndex, 0)) {
            ativarPreempcaoSocorro(0);
            return;
        } else if (env.hasEmergencyApproaching(intersectionIndex, 1)) {
            ativarPreempcaoSocorro(1);
            return;
        }

        // C. Máquina de Estados com Temporização Adaptativa (Feature 7)
        long elapsed = now - phaseStartMillis;

        switch (currentPhase) {
            case NS_GREEN:
                if (elapsed >= currentGreenDuration) {
                    transitarFase(Phase.NS_YELLOW);
                }
                break;

            case NS_YELLOW:
                if (elapsed >= 1500) {
                    transitarFase(Phase.ALL_RED_1);
                }
                break;

            case ALL_RED_1:
                if (elapsed >= 600) {
                    // Calcula duração de verde para o eixo EW consoante a fila acumulada
                    int queueEW = env.getWaitingVehiclesCount(intersectionIndex, 1);
                    currentGreenDuration = calcularVerdeAdaptativo(queueEW);
                    transitarFase(Phase.EW_GREEN);
                }
                break;

            case EW_GREEN:
                if (elapsed >= currentGreenDuration) {
                    transitarFase(Phase.EW_YELLOW);
                }
                break;

            case EW_YELLOW:
                if (elapsed >= 1500) {
                    transitarFase(Phase.ALL_RED_2);
                }
                break;

            case ALL_RED_2:
                if (elapsed >= 600) {
                    // Calcula duração de verde para o eixo NS consoante a fila acumulada
                    int queueNS = env.getWaitingVehiclesCount(intersectionIndex, 0);
                    currentGreenDuration = calcularVerdeAdaptativo(queueNS);
                    transitarFase(Phase.NS_GREEN);
                }
                break;
        }
    }

    /**
     * Calcula o tempo de verde ótimo em função do número de viaturas em fila (Feature 7).
     *
     * @param waitingVehicles Quantidade de veículos à espera na via
     * @return Duração calculada do sinal verde em milissegundos
     */
    private long calcularVerdeAdaptativo(int waitingVehicles) {
        long tempoBase = 3500;
        long extensaoPorViatura = 800;
        long tempoMaximo = 7500;

        long verde = tempoBase + (waitingVehicles * extensaoPorViatura);
        return Math.min(verde, tempoMaximo);
    }

    /**
     * Transita de fase semafórica e atualiza os semáforos no ambiente gráfico.
     */
    private void transitarFase(Phase novaFase) {
        this.currentPhase = novaFase;
        this.phaseStartMillis = System.currentTimeMillis();
        TrafficEnvironment env = TrafficEnvironment.getInstance();

        switch (novaFase) {
            case NS_GREEN:
                env.setTrafficLight(intersectionIndex, 0, CitySimulationGUI.LightState.GREEN);
                env.setTrafficLight(intersectionIndex, 1, CitySimulationGUI.LightState.RED);
                break;
            case NS_YELLOW:
                env.setTrafficLight(intersectionIndex, 0, CitySimulationGUI.LightState.YELLOW);
                env.setTrafficLight(intersectionIndex, 1, CitySimulationGUI.LightState.RED);
                break;
            case ALL_RED_1:
            case ALL_RED_2:
                env.setTrafficLight(intersectionIndex, 0, CitySimulationGUI.LightState.RED);
                env.setTrafficLight(intersectionIndex, 1, CitySimulationGUI.LightState.RED);
                break;
            case EW_GREEN:
                env.setTrafficLight(intersectionIndex, 0, CitySimulationGUI.LightState.RED);
                env.setTrafficLight(intersectionIndex, 1, CitySimulationGUI.LightState.GREEN);
                break;
            case EW_YELLOW:
                env.setTrafficLight(intersectionIndex, 0, CitySimulationGUI.LightState.RED);
                env.setTrafficLight(intersectionIndex, 1, CitySimulationGUI.LightState.YELLOW);
                break;
        }
    }

    /**
     * Ativa a preempção imediata para viaturas de socorro (Feature 6).
     */
    public void ativarPreempcaoSocorro(int axisEmergencia) {
        this.emergencyOverride = true;
        this.emergencyReleaseMillis = System.currentTimeMillis() + 5000; // 5 segundos de janela prioritária

        TrafficEnvironment env = TrafficEnvironment.getInstance();
        env.triggerEmergencyPreemption(intersectionIndex, axisEmergencia);

        System.out.println("[TrafficLightAgent C" + (intersectionIndex + 1) 
                + "] *** PREEMPÇÃO ATIVADA ***: Eixo " + (axisEmergencia == 0 ? "NS" : "EW") 
                + " prioritário a VERDE para passagem de emergência!");
    }

    /**
     * Processa mensagens formais FIPA-ACL recebidas de viaturas ou agentes de coordenação.
     */
    private void processarMensagemFIPA(ACLMessage msg) {
        String ontology = msg.getOntology();

        // 1. Pedido de preempção prioritária de socorro (Feature 6)
        if ("emergency-preemption".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Mensagem de SOCORRO recebida de " 
                    + msg.getSender().getLocalName() + ": " + msg.getContent());

            ativarPreempcaoSocorro(axis);

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.AGREE);
            reply.setContent("CONFIRMACAO_PREEMPCAO: Corredor de socorro desimpedido a VERDE no Cruzamento C" + (intersectionIndex + 1));
            send(reply);
            return;
        }

        // 2. Pedido de viatura regular com tempo de espera excessivo (Feature 3)
        if ("traffic-management".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Pedido de passagem recebido de " 
                    + msg.getSender().getLocalName() + " (Espera reportada). A priorizar comutação.");

            // Reduz o tempo restante para acelerar a abertura do verde nesta via
            if (currentGreenDuration > 2000) {
                currentGreenDuration = Math.max(1500, currentGreenDuration - 1500);
            }

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("PEDIDO_ACEITE: Semáforo " + direction + " antecipou a abertura de fase verde.");
            send(reply);
            return;
        }

        // 3. Alertas de perturbação / condições climáticas de Machine Learning (Feature 5)
        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[TrafficLightAgent " + direction + "] Alerta de perturbação acolhido: " + msg.getContent());
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[TrafficLightAgent] Semáforo " + direction + " desregistado do DF e encerrado com sucesso.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}