package environment;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Camada de Abstração e Fachada Oficial do Ambiente de Tráfego (Feature 1 - Traffic Environment).
 *
 * Esta classe representa o ambiente no qual os agentes inteligentes vivem,
 * disponibilizando sensores e atuadores para que os agentes possam:
 * - Percecionar o estado do tráfego (densidade de aproximação, filas de espera, viaturas de emergência).
 * - Atuar sobre a infraestrutura rodoviária (comutação semafórica, preempção de socorro).
 * - Consultar métricas de desempenho agregadas em tempo real (Feature 8).
 * - Propagar alertas preditivos de perturbações e condições meteorológicas (Feature 5).
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya
 */
public class TrafficEnvironment {

    private static TrafficEnvironment instance;

    // Filas legadas mantidas para compatibilidade retroativa
    private final ConcurrentLinkedQueue<String> queueNS = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> queueEW = new ConcurrentLinkedQueue<>();

    /**
     * Construtor privado (Padrão Singleton).
     */
    private TrafficEnvironment() {
        // Assegura a instanciação e arranque da interface visual apenas se não for headless
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CitySimulationGUI.getInstance();
        }
    }

    /**
     * Obtém a instância única e global do ambiente de tráfego.
     *
     * @return Instância de {@link TrafficEnvironment}
     */
    public static synchronized TrafficEnvironment getInstance() {
        if (instance == null) {
            instance = new TrafficEnvironment();
        }
        return instance;
    }

    /* =========================================================================
     * SENSORES: PERCEÇÃO DO AMBIENTE (Features 1, 2, 3, 6)
     * ========================================================================= */

    /**
     * Perceciona o número de viaturas em aproximação a um cruzamento ao longo de um eixo viário.
     *
     * @param intersectionIndex Índice do cruzamento (0=NW, 1=NE, 2=SW, 3=SE)
     * @param axis Eixo de circulação (0=Norte-Sul, 1=Este-Oeste)
     * @return Quantidade de veículos detetados no raio sensorial
     */
    public int getApproachingVehiclesCount(int intersectionIndex, int axis) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getApproachingCount(intersectionIndex, axis) : 0;
    }

    /**
     * Perceciona o número de veículos imobilizados ou em fila de espera num determinado cruzamento e eixo.
     *
     * @param intersectionIndex Índice do cruzamento (0=NW, 1=NE, 2=SW, 3=SE)
     * @param axis Eixo de circulação (0=Norte-Sul, 1=Este-Oeste)
     * @return Quantidade de veículos com velocidade residual (< 0.2 px/tick)
     */
    public int getWaitingVehiclesCount(int intersectionIndex, int axis) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getWaitingCount(intersectionIndex, axis) : 0;
    }

    /**
     * Deteta se existe alguma viatura de socorro (ambulância, polícia, bombeiros)
     * em aproximação prioritária ao cruzamento no eixo especificado.
     *
     * @param intersectionIndex Índice do cruzamento
     * @param axis Eixo viário
     * @return {@code true} se existir viatura de emergência a requerer passagem prioritária
     */
    public boolean hasEmergencyApproaching(int intersectionIndex, int axis) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) && gui.hasEmergencyApproaching(intersectionIndex, axis);
    }

    /**
     * Obtém o tempo de espera acumulado (em segundos) de uma determinada viatura.
     *
     * @param vehicleId Identificador da viatura
     * @return Duração da imobilização atual em segundos
     */
    public double getVehicleWaitSeconds(String vehicleId) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getVehicleWaitSeconds(vehicleId) : 0.0;
    }

    /* =========================================================================
     * ATUADORES: AÇÕES SOBRE O AMBIENTE (Features 2, 5, 6, 7)
     * ========================================================================= */

    /**
     * Atualiza o estado da sinalização luminosa de um semáforo específico.
     *
     * @param intersectionIndex Índice do cruzamento
     * @param axis Eixo viário (0=NS, 1=EW)
     * @param state Novo estado luminoso (GREEN, YELLOW, RED)
     */
    public void setTrafficLight(int intersectionIndex, int axis, CitySimulationGUI.LightState state) {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        if (gui != null) {
            gui.setTrafficLight(intersectionIndex, axis, state);
        }
    }

    /**
     * Executa a preempção imediata de emergência (Feature 6):
     * Coloca o eixo de emergência a VERDE e o eixo transversal a VERMELHO.
     *
     * @param intersectionIndex Cruzamento em causa
     * @param axis Eixo pelo qual circula a viatura de socorro
     */
    public void triggerEmergencyPreemption(int intersectionIndex, int axis) {
        IntersectionSafetyAuthority.getAuthority(intersectionIndex).requestEmergency("MANUAL_EMERGENCY", axis, null);
    }

    /**
     * Obtém a autoridade única de segurança e controlo de fases do cruzamento.
     *
     * @param intersectionIndex Índice do cruzamento (0 a 3)
     * @return Instância de {@link IntersectionSafetyAuthority}
     */
    public IntersectionSafetyAuthority getIntersectionAuthority(int intersectionIndex) {
        return IntersectionSafetyAuthority.getAuthority(intersectionIndex);
    }

    /**
     * Atualiza a condição climática / perturbação inferida por Machine Learning (Feature 5).
     *
     * @param notice Mensagem explicativa da perturbação
     * @param speedMultiplier Fator multiplicador de velocidade (ex: 0.70 para piso molhado)
     */
    public void setDisruptionNotice(String notice, double speedMultiplier) {
        CitySimulationGUI.getInstance().setDisruptionNotice(notice, speedMultiplier);
    }

    /**
     * Obtém o estado atual das perturbações rodoviárias.
     *
     * @return Texto informativo da perturbação
     */
    public String getDisruptionNotice() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getDisruptionNotice() : "Condições Normais";
    }

    /* =========================================================================
     * MÉTRICAS DE DESEMPENHO E TELEMETRIA (Feature 8)
     * ========================================================================= */

    /**
     * Obtém o tempo médio global de espera por viatura na rede urbana.
     *
     * @return Média de espera em segundos
     */
    public double getAverageWaitSeconds() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getAverageWaitSeconds() : 0.0;
    }

    /**
     * Obtém o total acumulado de viaturas que concluíram a travessia de cruzamentos.
     *
     * @return Contagem de passagens efetuadas com sucesso
     */
    public long getTotalCompletedPasses() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getTotalCompletedPasses() : 0L;
    }

    /**
     * Obtém o número total de eventos de preempção de socorro ativados com sucesso.
     *
     * @return Total de preempções
     */
    public int getPreemptionCount() {
        CitySimulationGUI gui = CitySimulationGUI.peekInstance();
        return (gui != null) ? gui.getPreemptionCount() : 0;
    }

    /* =========================================================================
     * MÉTODOS DE COMPATIBILIDADE RETROATIVA
     * ========================================================================= */

    public synchronized void setLights(CitySimulationGUI.LightState ns, CitySimulationGUI.LightState ew) {
        setTrafficLight(0, 0, ns);
        setTrafficLight(0, 1, ew);
        System.out.println("\n[AMBIENTE] Semáforos do Cruzamento C1_NW atualizados -> NS: " + ns + " | EW: " + ew);
    }

    public void addVehicle(String road, String vehicleId) {
        if (road.equalsIgnoreCase("NS")) {
            queueNS.add(vehicleId);
        } else {
            queueEW.add(vehicleId);
        }
        System.out.println("[AMBIENTE] Viatura " + vehicleId + " registada na via " + road.toUpperCase());
    }

    public int getQueueLength(String road) {
        return road.equalsIgnoreCase("NS") ? queueNS.size() : queueEW.size();
    }
}