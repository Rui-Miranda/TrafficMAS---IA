package environment;

/**
 * Estados da Máquina de Estados Finita de Cedência a Viaturas de Emergência (Fase 6.3 — R3).
 *
 * Descreve formalmente o ciclo cognitivo do VehicleAgent no paradigma:
 * PERCEÇÃO -> AVALIAÇÃO -> CEDÊNCIA FÍSICA -> PASSAGEM -> RETOMA.
 *
 * @author Equipa de Desenvolvimento TrafficMAS - ISPGaya (Fase 6.3)
 */
public enum VehicleYieldState {
    /** Condução regular segundo a rota A* planeada e limites de velocidade */
    NORMAL_DRIVING,

    /** Viatura de socorro em missão prioritária detetada em aproximação no mesmo corredor */
    EMERGENCY_DETECTED,

    /** Avaliação deliberativa das condições físicas de segurança para manobra lateral */
    EVALUATING_YIELD,

    /** Execução física gradual de mudança de faixa para a direita (lane 1 -> lane 0) */
    YIELDING_RIGHT,

    /** Encostamento suave ou desaceleração defensiva para libertar a linha de avanço */
    PULLING_OVER,

    /** Imobilização segura com corredor prioritário completamente desimpedido */
    YIELDING_STOPPED,

    /** Em espera passiva segura enquanto a viatura prioritária ultrapassa e transpõe o segmento */
    WAITING_FOR_EMERGENCY_PASSAGE,

    /** Transposição da emergência concluída: retoma progressiva da rota A* e da marcha */
    RESUMING_ROUTE,

    /** Manobra de cedência inviável no momento (faixa ocupada, cruzamento, obstáculo); condução defensiva */
    YIELD_NOT_SAFE;

    /**
     * Indica se o estado atual representa uma fase ativa do processo de cedência.
     */
    public boolean isYieldingActive() {
        return this != NORMAL_DRIVING;
    }

    /**
     * Indica se o veículo concluiu a cedência e libertou fisicamente a trajetória prioritária.
     */
    public boolean isCorridorFreed() {
        return this == YIELDING_STOPPED || this == WAITING_FOR_EMERGENCY_PASSAGE;
    }
}
