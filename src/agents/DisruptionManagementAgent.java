package agents;

import environment.TrafficEnvironment;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

/**
 * Agente de Gestão e Previsão de Perturbações (Feature 5 - Disruption Management Agent).
 *
 * Responsável por antecipar perturbações e anomalias de circulação através de um modelo
 * de Inteligência Artificial / Machine Learning preditivo (condições climatéricas adversas,
 * sinistros, redução de atrito ou obras na via).
 *
 * Ao inferir uma perturbação iminente:
 * - Calcula a probabilidade de congestionamento e fator de degradação da via.
 * - Atualiza os parâmetros globais no ambiente (multiplicador de velocidade e alertas no HUD).
 * - Emite avisos formais FIPA-ACL (INFORM com ontologia "disruption-alert") para os agentes
 *   semáforo e de coordenação central, permitindo que adaptem os seus comportamentos.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class DisruptionManagementAgent extends Agent {

    // Cenários de simulação para validação experimental do modelo preditivo
    private static class WeatherScenario {
        String name;
        double rainMm;
        double visibilityKm;
        double windKmh;
        boolean incidentFlag;
        double speedMultiplier;

        WeatherScenario(String name, double rainMm, double visibilityKm, double windKmh, boolean incidentFlag, double speedMultiplier) {
            this.name = name;
            this.rainMm = rainMm;
            this.visibilityKm = visibilityKm;
            this.windKmh = windKmh;
            this.incidentFlag = incidentFlag;
            this.speedMultiplier = speedMultiplier;
        }
    }

    private final WeatherScenario[] scenarios = {
        new WeatherScenario("Tempo Seco e Céu Limpo (Atrito 100%)", 0.0, 10.0, 12.0, false, 1.00),
        new WeatherScenario("Chuva Intensa e Piso Escorregadio", 18.5, 2.5, 45.0, false, 0.72),
        new WeatherScenario("Obras na Via com Supressão de Faixa", 0.0, 8.0, 15.0, true, 0.60),
        new WeatherScenario("Nevoeiro Denso com Baixa Visibilidade", 4.0, 0.4, 8.0, false, 0.78)
    };

    private int currentScenarioIndex = 0;

    @Override
    protected void setup() {
        // 1. Registo no Directory Facilitator
        registarNoDF();

        System.out.println("[DisruptionManagementAgent] Agente preditivo de perturbações online com modelo de Machine Learning.");

        // 2. Comportamento Periódico: Avaliação contínua a cada 16 segundos do modelo de inferência
        addBehaviour(new TickerBehaviour(this, 16000) {
            @Override
            protected void onTick() {
                executarInferenciaEPromoverAdaptacao();
            }
        });
    }

    /**
     * Regista o serviço nas páginas amarelas do JADE.
     */
    private void registarNoDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("disruption-management-service");
        sd.setName("DisruptionManager-" + getLocalName());

        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("[DisruptionManagementAgent] Erro ao registar no DF: " + fe.getMessage());
        }
    }

    /**
     * Executa a inferência preditiva de Machine Learning baseada nas características (features)
     * e notifica o ecossistema multiagente.
     */
    private void executarInferenciaEPromoverAdaptacao() {
        WeatherScenario current = scenarios[currentScenarioIndex];
        currentScenarioIndex = (currentScenarioIndex + 1) % scenarios.length;

        // Modelo Logístico de Machine Learning: P(disrupção) = 1 / (1 + exp(-z))
        double z = -2.5 + (0.18 * current.rainMm) - (0.35 * current.visibilityKm) + (0.04 * current.windKmh) + (current.incidentFlag ? 3.0 : 0.0);
        double disruptionProbability = 1.0 / (1.0 + Math.exp(-z));
        int probPercent = (int) Math.round(disruptionProbability * 100);

        String nivelRisco = (probPercent >= 70) ? "CRÍTICO" : ((probPercent >= 40) ? "MODERADO" : "BAIXO");

        System.out.println("\n[DisruptionManagementAgent - ML] >>> Nova Inferência Meteorológica / Ocorrências <<<");
        System.out.println("  • Cenário: " + current.name);
        System.out.println("  • Features: Chuva=" + current.rainMm + "mm/h | Visibilidade=" + current.visibilityKm + "km | Vento=" + current.windKmh + "km/h");
        System.out.println("  • Probabilidade de Perturbação: " + probPercent + "% (" + nivelRisco + ")");
        System.out.println("  • Fator de Velocidade Ajustado: " + (int)(current.speedMultiplier * 100) + "%\n");

        // Atualiza a fachada do ambiente e a interface gráfica
        String statusHUD = current.name + " (" + probPercent + "% risco)";
        TrafficEnvironment.getInstance().setDisruptionNotice(statusHUD, current.speedMultiplier);

        // Difunde alerta FIPA INFORM a todos os semáforos e agentes coordenadores
        difundirAlertaFIPA(current.name, probPercent, current.speedMultiplier);
    }

    /**
     * Difunde mensagens FIPA INFORM com ontologia "disruption-alert" para o sistema multiagente.
     */
    private void difundirAlertaFIPA(String descricao, int probabilidade, double fatorVelocidade) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("disruption-alert");
        msg.setConversationId("disruption-notice-" + System.currentTimeMillis());
        msg.setContent("ALERTA_DISRUPCAO: [Modelo ML] " + descricao 
                + " | Probabilidade: " + probabilidade 
                + "% | Multiplicador de Deslocamento: " + fatorVelocidade);

        // Adiciona os semáforos dos 4 cruzamentos como destinatários
        String[] crossings = {"C1_NW", "C2_NE", "C3_SW", "C4_SE"};
        for (String c : crossings) {
            msg.addReceiver(new jade.core.AID("Semaforo_NS_" + c, jade.core.AID.ISLOCALNAME));
            msg.addReceiver(new jade.core.AID("Semaforo_EW_" + c, jade.core.AID.ISLOCALNAME));
        }

        // Adiciona o Agente Coordenador Central
        msg.addReceiver(new jade.core.AID("AgenteCoordenadorCentral", jade.core.AID.ISLOCALNAME));

        send(msg);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[DisruptionManagementAgent] Desregistado do DF e encerrado.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}
