package environment;

/**
 * Ações cinemáticas e operacionais que um VehicleAgent decide autonomamente (Fase 6.2-D).
 *
 * Representa as intenções de ação deliberadas pelo agente no paradigma:
 * PERCEÇÃO -> RACIOCÍNIO -> AÇÃO.
 */
public enum VehicleAction {
    /** Acelerar em direção à velocidade alvo permitida */
    ACCELERATE,
    /** Manter velocidade de cruzeiro estável */
    CRUISE,
    /** Desacelerar / travar de forma suave por aproximação a obstáculo ou sinal */
    BRAKE,
    /** Imobilização completa na linha de paragem ou em fila */
    STOP,
    /** Retomar a marcha após período de paragem */
    RESUME,
    /** Paragem controlada de emergência (ex: agente suspenso ou perda de controlo) */
    EMERGENCY_STOP
}

