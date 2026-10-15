# AUDITORIA HISTÓRICA: DATASET E MODELO DA FASE 4 (SEMÁFOROS CONGELADOS)

## 1. Identificação e Metadados dos Ficheiros Históricos
- **Dataset Histórico:** `data/historical_phase4/traffic_disruption_dataset_frozen_phase4.csv`
  - **SHA-256:** `89EFA713970803AE7644864924C8E05DBDFAEA8B82C160F018A62A335BBB9BCE`
  - **Data de Consolidação:** Fase 4 - Etapa 4 (Commit `ee2cba2`)
  - **Total de Amostras:** 3.072 registos (16 corridas x 4 cruzamentos x 48 timesteps)
  - **Divisão:** TRAIN (2.112), VAL (384), TEST (576)

- **Modelo Histórico:** `data/historical_phase4/traffic_model_frozen_phase4.bin`
  - **SHA-256:** `3B192EB91C93D4802B5C140660B25A4C1866AE0335BEFDDDA719F91BBBBA2429`
  - **Algoritmo:** Regressão Logística L2 (C = 1.0, StandardScaler, threshold tau = 0.40)
  - **Accuracy Reportada no TEST Histórico:** 96,88%

## 2. Diagnóstico Metodológico e Causa Raiz
Durante a geração original do dataset na Fase 4 (via `TrafficDatasetGenerator.java`), a classe `HeadlessTrafficSimulator` avançava o tempo simulado em passos cinemáticos de 33.3 ms. Contudo, a classe `IntersectionSafetyAuthority` avaliava os tempos de transição semafórica consultando o relógio de parede do sistema operativo (`System.currentTimeMillis() - phaseStartTime`).

Como cada corrida de 310 segundos simulados executava em apenas ~25 a 50 milissegundos de tempo real de CPU:
1. O tempo decorrido no relógio de parede (`elapsed < 50ms`) permaneceu sempre abaixo do tempo mínimo de verde (4.000 ms).
2. Como consequência direta, **os semáforos nunca alternaram de fase**:
   - Cruzamentos C0 e C2 permaneceram 100% do tempo em `NS_GREEN` (eixo EW permanentemente em vermelho).
   - Cruzamentos C1 e C3 permaneceram 100% do tempo em `EW_GREEN` (eixo NS permanentemente em vermelho).
3. As vias bloqueadas acumularam filas estáticas contínuas, enquanto as vias com verde escoaram totalmente o fluxo.
4. O target de congestionamento a $t + 60\text{ s}$ refletiu esse estrangulamento estático de rede.
5. A acurácia de 96,88% reflete a previsibilidade da congestão estática sob semáforos congelados, **não constituindo evidência de desempenho em ambiente dinâmico corrigido**.

## 3. Diretiva de Preservação e Separação
1. Os artefactos neste diretório preservam a rastreabilidade e a reprodutibilidade integral dos testes históricos da Fase 4.
2. A nova versão operacional (Fase 5 - Etapa 2) utiliza o `SimulationClock` determinístico, semáforos efetivamente dinâmicos e um modelo retreinado sob condições físicas válidas.
