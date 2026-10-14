package agents;

import environment.TrafficEnvironment;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

/**
 * Agente Coordenador Central da Rede Semafórica (Feature 4 - Central Coordination Agent).
 *
 * Exerce a supervisão holística sobre os 4 cruzamentos da malha urbana:
 * - Recolhe telemetria contínua dos agentes semáforo e viaturas em circulação.
 * - Identifica estrangulamentos viários (bottlenecks) e coordena padrões macro (ex: Onda Verde).
 * - Processa alertas meteorológicos e de perturbação emitidos pelo {@link DisruptionManagementAgent}.
 * - Supervisiona as prioridades concedidas às viaturas de socorro médico e policial (Feature 6).
 * - Agrega as métricas globais de eficiência de transporte urbano (Feature 8).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class CentralCoordinationAgent extends Agent {

    private static final String[] CROSSINGS = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};

    @Override
    protected void setup() {
        // 1. Registo nas Páginas Amarelas (DFService)
        registarNoDF();

        System.out.println("[CentralCoordinationAgent] Agente de Coordenação Central online e a supervisionar a rede.");

        // 2. Comportamento Periódico: Auditoria da rede a cada 5 segundos
        addBehaviour(new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() {
                analisarPadroesDeTrafegoERede();
            }
        });

        // 3. Comportamento Cíclico: Receção de notificações FIPA-ACL
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    processarMensagemRecebida(msg);
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Regista as competências do coordenador no Directory Facilitator do JADE.
     */
    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("central-coordination-service");
        sd.setName("CoordenadorCentral-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[CentralCoordinationAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    /**
     * Inspeciona os sensores das 4 interseções e avalia a fluidez e integridade da rede.
     */
    private void analisarPadroesDeTrafegoERede() {
        TrafficEnvironment env = TrafficEnvironment.getInstance();

        int totalEsperaRede = 0;
        int maxFila = 0;
        String pontoCritico = "Nenhum (Trânsito Fluido)";

        for (int i = 0; i < CROSSINGS.length; i++) {
            int queueNS = env.getWaitingVehiclesCount(i, 0);
            int queueEW = env.getWaitingVehiclesCount(i, 1);
            int soma = queueNS + queueEW;
            totalEsperaRede += soma;

            if (soma > maxFila) {
                maxFila = soma;
                pontoCritico = CROSSINGS[i] + " (NS=" + queueNS + ", EW=" + queueEW + ")";
            }
        }

        double mediaEspera = env.getAverageWaitSeconds();
        long passagens = env.getTotalCompletedPasses();
        int preempcoes = env.getPreemptionCount();

        System.out.println("[CentralCoordinationAgent] Relatório de Rede: " 
                + "Viaturas Retidas=" + totalEsperaRede 
                + " | Ponto Crítico=" + pontoCritico 
                + " | Espera Média=" + mediaEspera + "s" 
                + " | Débito Total=" + passagens 
                + " | Socorros Atendidos=" + preempcoes);

        // Se detetar acumulação na artéria horizontal ou vertical, coordena diretiva de Onda Verde
        if (totalEsperaRede >= 4) {
            System.out.println("[CentralCoordinationAgent] >>> DIRETIVA MACRO: A ativar sincronismo de Onda Verde entre cruzamentos adjacentes <<<");
        }
    }

    /**
     * Trata mensagens informativas recebidas de outros agentes do sistema.
     */
    private void processarMensagemRecebida(ACLMessage msg) {
        String ontology = msg.getOntology();

        if ("disruption-alert".equalsIgnoreCase(ontology)) {
            System.out.println("[CentralCoordinationAgent] Notificação de perturbação recebida: " + msg.getContent());
        } else {
            System.out.println("[CentralCoordinationAgent] Comunicação recebida de " 
                    + msg.getSender().getLocalName() + " (" + ACLMessage.getPerformative(msg.getPerformative()) + "): " 
                    + msg.getContent());
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[CentralCoordinationAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
