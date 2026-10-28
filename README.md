# TrafficMAS — Intelligent Multi-Agent Traffic Control System

[![Java](https://img.shields.io/badge/Java-LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![JADE](https://img.shields.io/badge/Multi--Agent-JADE%204.6-007396?style=for-the-badge&logo=eclipse-ide&logoColor=white)](https://jade.tilab.com)
[![Tests](https://img.shields.io/badge/Testes%20Automatizados-133%2F133%20PASSOU-2ea44f?style=for-the-badge)](#6-execução-das-12-suites-de-testes-automatizados-133-testes--fases-1-a-61)
[![ISPGaya](https://img.shields.io/badge/Instituição-ISPGaya-red?style=for-the-badge)](http://www.ispgaya.pt/)

> **Unidade Curricular:** Inteligência Artificial (1.º Semestre — 2026/2027)  
> **Licenciatura em Engenharia Informática** — **ISPGaya**  
> **Docente Responsável:** Prof. Doutor Tiago Soares da Costa  

---

## 1. Visão Geral do Projeto

O **TrafficMAS** é um ecossistema multiagente descentralizado concebido para otimizar o fluxo de tráfego rodoviário numa malha urbana parametrizável (**malha 3×3 com 9 cruzamentos inteligentes por omissão** e **modo de compatibilidade 2×2 com 4 cruzamentos**), composta por artérias de 4 faixas (`96 px` de largura, 2 faixas de `24 px` por sentido com curvas contínuas de Bézier e rotação cinemática suave).

O sistema substitui os controladores semafóricos tradicionais de temporização fixa (*Fixed-Time*) por uma arquitetura autónoma e distribuída assente na plataforma **JADE** (*Java Agent Development Framework*), em estrita conformidade com as normas **FIPA-ACL** (*Foundation for Intelligent Physical Agents*), combinando:
- **Segurança Semafórica Inviolável** ([`IntersectionSafetyAuthority`](src/environment/IntersectionSafetyAuthority.java)): máquina de estados atómica por cruzamento (`NS_GREEN` $\rightarrow$ `NS_YELLOW` $\rightarrow$ `ALL_RED` $\rightarrow$ `EW_GREEN` $\rightarrow$ `EW_YELLOW` $\rightarrow$ `ALL_RED`), garantindo zero verdes incompatíveis e zero violações de linha de paragem (*stop-line*);
- **Navegação Inteligente por Grafo Rodoviário e Algoritmo A\*** ([`RoadNetworkGraph`](src/environment/RoadNetworkGraph.java), [`ManhattanHeuristic`](src/environment/ManhattanHeuristic.java), [`AStarPathfinder`](src/environment/AStarPathfinder.java)): planeamento ótimo de rotas com heurística admissível e consistente de Manhattan ($d = |x_1 - x_2| + |y_1 - y_2|$), validado matematicamente face ao algoritmo de **Dijkstra** nos 81 pares origem-destino da rede $3 \times 3$;
- **Escala Multiagente Real (73 Agentes JADE Ativos no Arranque 3×3)**: **50** viaturas autónomas normais (`VehicleAgent`, limite dinâmico `0..50`) + **3** viaturas prioritárias excluídas do limite de 50 (`EmergencyVehicleAgent`: 1 Ambulância, 1 Bombeiros, 1 Polícia) + **18** semáforos inteligentes (`TrafficLightAgent`, 2 por cruzamento) + **1** Coordenador Central (`CentralCoordinationAgent`) + **1** Agente Preditivo de Perturbações com Machine Learning (`DisruptionManagementAgent`).

```
                       ┌────────────────────────────────────────────────────┐
                       │      Agente de Coordenação Central (Macro)         │
                       │   6 Corredores Arteriais (3 Horiz. + 3 Vert.)      │
                       └─────────────────────────┬──────────────────────────┘
                                                 │ FIPA-ACL (Onda Verde / Equilíbrio)
          ┌──────────────────────────────────────┼──────────────────────────────────────┐
          ▼                                      ▼                                      ▼
┌────────────────────┐                 ┌────────────────────┐                 ┌────────────────────┐
│  Semáforos Linha 1 │   ◄─FIPA-ACL─►  │  Semáforos Linha 2 │   ◄─FIPA-ACL─►  │  Semáforos Linha 3 │
│  C1_NW, C2_N, C3_NE│                 │  C4_W, C5_C, C6_E  │                 │  C7_SW, C8_S, C9_SE│
│   (6 Agentes TL)   │                 │   (6 Agentes TL)   │                 │   (6 Agentes TL)   │
└─────────▲──────────┘                 └─────────▲──────────┘                 └─────────▲──────────┘
          │                                      │                                      │
          │ FIPA-ACL (Preempção 3 Fases)         │ FIPA-ACL (Pressão / Rotas A*)        │ FIPA-ACL (DISTURBANCE_ALERT)
┌─────────┴──────────┐                 ┌─────────┴──────────┐                 ┌─────────┴──────────┐
│ 3 Viaturas Socorro │                 │ 50 Viaturas Normais│                 │ Agente Preditivo   │
│ Ambulância/Bombeir.│                 │  Carro_01..Carro_50│                 │   ML Disrupção     │
│     / Polícia      │                 │ (Navegação A* Real)│                 │ (Horizonte t+60s)  │
└────────────────────┘                 └────────────────────┘                 └────────────────────┘
```

---

## 2. 📌 MARCO DE INFLEXÃO ARQUITETURAL — FASE 6.1 (Chave de Inflexão para o Relatório e Artigo Científico)

> [!IMPORTANT]
> **Nota de Referência Histórica para o Relatório Final (IEEE):**  
> A **Fase 6.1** (branch `feature/fase-6-3x3`, tag Git `v6.1-marco-inflexao-3x3`) constitui o **ponto de inflexão estrutural** do projeto **TrafficMAS**. Até à **Fase 5.2** (commit de base `c4f2eb6` na branch `feature/fase-5-experiencias`), o sistema encontrava-se consolidado e validado experimentalmente numa grelha $2 \times 2$ (4 cruzamentos, 8 semáforos, 160 corridas experimentais controladas e 112 testes automatizados).  
> Na **Fase 6.1**, a arquitetura foi escalada sem rutura retroativa para uma **malha urbana parametrizável $3 \times 3$ (9 cruzamentos, 18 semáforos, 73 agentes JADE em simultâneo)**, introduzindo **procura informada em grafo com o algoritmo A\* e heurística de Manhattan** e **gestão dinâmica de ciclo de vida da frota (`0..50` veículos normais + prioritários independentes)**, mantendo **100% de compatibilidade com os 112 testes e resultados históricos das Fases 1 a 5.2**.

### Síntese Comparativa da Evolução no Ponto de Inflexão (Fase 5.2 $\rightarrow$ Fase 6.1)

| Dimensão Arquitetural / Experimental | Estado Consolidado até Fase 5.2 (`2×2`) | Novo Patamar a partir da Fase 6.1 (`3×3`) | Impacto para o Relatório Técnico / Artigo |
| :--- | :--- | :--- | :--- |
| **Topologia da Rede Viária** | Grelha fixa $2 \times 2$ (`4` cruzamentos: `C1_NW` a `C4_SE`) | Malha parametrizável $3 \times 3$ (`9` cruzamentos: `C1_NW` a `C9_SE`) + modo `--2x2` preservado | Demonstra escalabilidade espacial e desacoplamento geométrico ([`NetworkTopologyConfig`](src/environment/NetworkTopologyConfig.java)). |
| **Agentes Semáforo (`TrafficLightAgent`)** | `8` agentes JADE (2 por cruzamento) | **`18` agentes JADE** registados no `DFService` (2 por cruzamento) | Duplica a densidade de coordenação distribuída mantendo 9 autoridades `IntersectionSafetyAuthority` independentes. |
| **Corredores Arteriais Coordenados** | `4` corredores (2 horizontais + 2 verticais) | **`6` corredores** (3 horizontais + 3 verticais de 4 faixas cada) | Permite Onda Verde (*Green Wave*) multi-salto através do cruzamento central `C5_C`. |
| **Motor de Navegação e Rotas** | Movimentos locais por sorteio estocástico no cruzamento | **Grafo direcionado ponderado (`24` segmentos) + Algoritmo A\* + Referência Dijkstra** | Veículos seguem rotas ótimas reais ($9 \times 9 = 81$ pares Origem-Destino) com recálculo seletivo por versão do grafo (`graphVersion`). |
| **Heurística de Procura Informada** | Não aplicável | **Distância de Manhattan** ($d = \|x_1 - x_2\| + \|y_1 - y_2\|$) em `px` e escalada em tempo ($d/v_{\max}$) + fallback nulo ($h=0$) | Prova formal e empírica de admissibilidade e consistência ($h(u) \le c(u,v) + h(v)$) sem sobrestimação. |
| **Frota Autónoma Normal (`VehicleAgent`)** | 5 a 6 veículos visuais/JADE no arranque gráfico | **Exatamente `50` veículos autónomos normais no arranque** (limite dinâmico `0..50`, correspondência `1:1` Visual $\leftrightarrow$ JADE) | Escala a carga multiagente em $10\times$ com spawn seguro sem sobreposição física ([`FleetLifecycleManager`](src/environment/FleetLifecycleManager.java)). |
| **Frota Prioritária (`EmergencyVehicleAgent`)** | 2 viaturas (Ambulância + Polícia) | **`3` viaturas prioritárias excluídas do limite de 50** (`Ambulancia_INEM_01`, `Bombeiros_Sapadores_01`, `Viatura_Policia_01`) | Separa categoria, nível de urgência e estado ativo da missão; preempção dinâmica ao longo da rota A*. |
| **Total de Agentes JADE da Aplicação** | `12` a `18` agentes JADE | **`73` agentes JADE ativos confirmados no arranque** (`50 + 3 + 18 + 1 + 1`) | Evidência laboratorial sólida de concorrência JADE e troca de mensagens FIPA-ACL em tempo real (`~22.2 msgs/s`). |
| **Validação Automatizada de Regressão** | 11 suites (`112` testes) | **12 suites (`133` testes: `112` históricos + `21` novos blocos da Fase 6.1 — `0` falhas)** | Garantia integral de reprodutibilidade científica dos dados das Fases 1 a 5.2 e validação exaustiva da Fase 6.1. |

### Métricas Reais de Desempenho e Segurança Medidas no Marco da Fase 6.1 (Malha 3×3 com 73 Agentes JADE)

| Indicador Operacional Medido (`Main --no-rma --demo-seconds=15`) | Valor Observado |
| :--- | :--- |
| **Agentes JADE Ativos Confirmados no Contentor Principal** | **`73`** (`50 VehicleAgent + 3 EmergencyVehicleAgent + 18 TrafficLightAgent + 1 Central + 1 ML`) |
| **Correspondência 1:1 Veículo Gráfico $\leftrightarrow$ Agente JADE** | **`100% Íntegra`** (`53` veículos gráficos $\leftrightarrow$ `53` agentes JADE de veículos) |
| **Taxa Média de Atualização Gráfica (Swing Timer)** | `22.20 FPS` |
| **Consumo Máximo de Memória Heap (JVM)** | `33.68 MB` |
| **Carga Média de CPU do Processo** | `9.63 %` |
| **Volume de Tráfego FIPA-ACL (15 segundos)** | `333 mensagens` (`22.20 mensagens/s`) |
| **Travessias Concluídas nos 9 Cruzamentos (15 segundos)** | `81 travessias` |
| **Violações de Sinal Vermelho (*Stop-Line*)** | **`0`** |
| **Verdes Simultâneos Incompatíveis nos 9 Cruzamentos** | **`0`** |

---

## 3. Conformidade com os Requisitos Académicos e Arquiteturais

| Requisito / Funcionalidade | Componente Principal no Código | Estado | Descrição da Implementação |
| :--- | :--- | :---: | :--- |
| **1. Topologia Parametrizável 3×3 e 2×2** | [`NetworkTopologyConfig.java`](src/environment/NetworkTopologyConfig.java) | ✅ Concluído | Suporta 9 cruzamentos (`C1_NW` a `C9_SE`, `1080×1100 px`) por omissão e preserva os 4 cruzamentos (`2×2`, `920×940 px`) em modo de compatibilidade (`--2x2`). |
| **2. 18 Agentes Semáforo JADE** | [`TrafficLightAgent.java`](src/agents/TrafficLightAgent.java) | ✅ Concluído | 18 agentes semáforo (2 por cruzamento: eixos `NS` e `EW`) registados no `DFService` e governados por 9 instâncias de [`IntersectionSafetyAuthority`](src/environment/IntersectionSafetyAuthority.java). |
| **3. Grafo Rodoviário Direcionado** | [`RoadNetworkGraph.java`](src/environment/RoadNetworkGraph.java) | ✅ Concluído | 9 nós (`IntersectionNode`) e 24 segmentos dirigidos (`RoadSegment`) com custos estritamente positivos, penalizações de congestionamento, bloqueios de via e `graphVersion`. |
| **4. Distância e Heurística de Manhattan** | [`ManhattanHeuristic.java`](src/environment/ManhattanHeuristic.java) | ✅ Concluído | Cálculo exato $d = \|x_1 - x_2\| + \|y_1 - y_2\|$ em píxeis, saltos na grelha e tempo mínimo escalado ($d/v_{\max}$), com verificação de consistência e fallback nulo ($h=0$). |
| **5. Algoritmo A\* e Referência Dijkstra** | [`AStarPathfinder.java`](src/environment/AStarPathfinder.java) | ✅ Concluído | Procura ótima A* com reconstrução de sequência de manobras (`GO_STRAIGHT`, `TURN_RIGHT`, `TURN_LEFT`) e validação de custo ótimo face a Dijkstra nos 81 pares. |
| **6. 50 Veículos Normais + Ciclo de Vida** | [`FleetLifecycleManager.java`](src/environment/FleetLifecycleManager.java) | ✅ Concluído | Configuração inicial com 50 `VehicleAgent` (`Carro_01` a `Carro_50`), adição/remoção dinâmica entre `0` e `50` sem sobreposição de spawn e sem recursos órfãos. |
| **7. Veículos Prioritários Independentes** | [`EmergencyVehicleAgent.java`](src/agents/EmergencyVehicleAgent.java) | ✅ Concluído | 3 viaturas de emergência (`Ambulancia_INEM_01`, `Bombeiros_Sapadores_01`, `Viatura_Policia_01`) excluídas do limite de 50 normais, com protocolo FIPA de 3 confirmações. |
| **8. Coordenação Central Multi-Corredor** | [`CentralCoordinationAgent.java`](src/agents/CentralCoordinationAgent.java) | ✅ Concluído | Monitorização dos 9 cruzamentos e sincronismo de Onda Verde (*Green Wave*) nos 3 corredores horizontais e 3 corredores verticais. |
| **9. Gestão Preditiva de Perturbações (ML)** | [`DisruptionManagementAgent.java`](src/agents/DisruptionManagementAgent.java) | ✅ Concluído | Inferência online com modelos supervisionados validados sem *data leakage* (horizonte $t + 60\text{s}$) e proteção explícita contra extrapolação não validada entre topologias. |
| **10. Telemetria e Avaliação Experimental** | [`TrafficMetricsCollector.java`](src/metrics/TrafficMetricsCollector.java) | ✅ Concluído | Recolha parametrizada para 4 ou 9 cruzamentos com proteção anti-dupla-contagem (debounce de `2.0s`) e campanha experimental estatística completa (`160` corridas 2×2). |

---

## 4. Estrutura do Repositório

```
TrafficMAS---IA/
├── data/
│   ├── traffic_disruption_dataset.csv          # Dataset operacional de ML (9.216 instâncias, horizonte t+60s)
│   ├── selected_disruption_model.model         # Modelo supervisionado persistido (Logistic Regression L2 + Scaler)
│   └── selected_disruption_model.meta.properties # Metadados auditáveis (SHA-256, features, threshold=0.40)
├── lib/
│   ├── jade.jar                                # Runtime da plataforma JADE 4.6
│   └── commons-codec-1.3.jar                   # Codificação e utilitários FIPA
├── src/
│   ├── Main.java                               # Launcher principal (3×3 com 73 agentes por omissão; --2x2 opcional)
│   ├── agents/
│   │   ├── TrafficLightAgent.java              # Semáforos adaptativos (18 em 3×3; 8 em 2×2) com registo no DF
│   │   ├── VehicleAgent.java                   # Viaturas autónomas regulares (50 iniciais em 3×3) com rotas A*
│   │   ├── EmergencyVehicleAgent.java          # Viaturas prioritárias (Ambulância, Bombeiros, Polícia) com A*
│   │   ├── DisruptionManagementAgent.java      # Agente preditivo ML com inferência periódica e alertas FIPA
│   │   └── CentralCoordinationAgent.java       # Coordenador Central (6 corredores em 3×3; Onda Verde e Equilíbrio)
│   ├── environment/
│   │   ├── NetworkTopologyConfig.java          # [Fase 6.1] Topologia parametrizada 3×3 (9 nós) e 2×2 (4 nós)
│   │   ├── RoadNetworkGraph.java               # [Fase 6.1] Grafo rodoviário direcionado, custos e restrições
│   │   ├── ManhattanHeuristic.java             # [Fase 6.1] Distância de Manhattan admissível/consistente + fallback
│   │   ├── AStarPathfinder.java                # [Fase 6.1] Algoritmo A* completo + solver de referência Dijkstra
│   │   ├── FleetLifecycleManager.java          # [Fase 6.1] Gestão de ciclo de vida JADE (0..50 normais + prioritários)
│   │   ├── CitySimulationGUI.java              # Interface Swing 3×3 / 2×2 com cinemática contínua e rotas A*
│   │   ├── HeadlessTrafficSimulator.java       # Motor determinístico acelerado (suporta 2×2 e 3×3)
│   │   ├── IntersectionSafetyAuthority.java    # Autoridade única de segurança e fases semafóricas por cruzamento
│   │   ├── SimulationClock.java                # Relógio determinístico monotónico para reprodução experimental
│   │   └── TrafficEnvironment.java             # Fachada unificada de sensores, grafo, rotas A* e frota
│   ├── metrics/
│   │   ├── TrafficMetricsCollector.java        # Coletor centralizado de telemetria (4 ou 9 cruzamentos)
│   │   ├── TrafficMetricsSnapshot.java         # Snapshot imutável de telemetria agregada e por cruzamento
│   │   └── TrafficMetricsExporter.java         # Exportador CSV e gerador de relatórios experimentais
│   ├── ml/
│   │   ├── BinaryClassifier.java               # Interface comum de classificadores binários
│   │   ├── FeatureScaler.java                  # Normalizador Z-score ajustado exclusivamente em TRAIN
│   │   ├── LogisticRegressionModel.java        # Regressão Logística L2 com estabilidade numérica Sigmoid
│   │   ├── DecisionTreeStumpModel.java         # Árvore de Decisão CART (maxDepth=2) com impureza de Gini
│   │   ├── MajorityClassBaseline.java          # Baseline estatístico de classe maioritária
│   │   ├── ModelEvaluator.java                 # Cálculo analítico de Accuracy, Precision, Recall, F1 e Confusão
│   │   ├── ModelSelectionPipeline.java         # Pipeline anti-leakage (TRAIN/VAL/TEST por RunId)
│   │   ├── ModelPersistence.java               # Serialização/desserialização com verificação de SHA-256
│   │   ├── TrafficModelBundle.java             # Bundle imutável do modelo selecionado
│   │   └── TrafficDatasetGenerator.java        # Gerador determinístico do dataset temporal (t -> t+60s)
│   └── test/
│       ├── SafetyTestSuite.java                # Suite 1 (Fase 1): Segurança e emergências (7 testes)
│       ├── VehicleTestSuite.java               # Suite 2 (Fase 2): Agentes veículos e resolução espacial (7 testes)
│       ├── CoordinationTestSuite.java          # Suite 3 (Fase 3): Coordenação multiagente e Onda Verde (10 testes)
│       ├── DatasetTestSuite.java               # Suite 4 (Fase 4.1): Integridade e anti-leakage do dataset (8 testes)
│       ├── MLModelsTestSuite.java              # Suite 5 (Fase 4.2): Modelos de ML e métricas analíticas (14 testes)
│       ├── ModelSelectionTestSuite.java        # Suite 6 (Fase 4.3): Seleção, persistência e auditoria (13 testes)
│       ├── OnlineMLIntegrationTestSuite.java   # Suite 7 (Fase 4.4): Integração online ML + FIPA-ACL (11 testes)
│       ├── MetricsTestSuite.java               # Suite 8 (Fase 5.1): Telemetria e prevenção de dupla contagem (8 testes)
│       ├── ExperimentalEvaluationTestSuite.java # Suite 9 (Fase 5.2): Avaliação experimental base (10 testes)
│       ├── ExperimentalCorrectiveTestSuite.java # Suite 10 (Fase 5.2-A): Correção temporal SimulationClock (9 testes)
│       ├── ExperimentalFullCampaignTestSuite.java # Suite 11 (Fase 5.2-B): Campanha de 160 experiências (15 testes)
│       └── Phase61ValidationTestSuite.java     # Suite 12 (Fase 6.1): Malha 3×3, A*, Manhattan e 73 agentes (21 testes)
├── results/
│   ├── phase5_runs.csv                         # Registo integral das 160 experiências controladas (Fase 5.2)
│   ├── phase5_summary.csv                      # Estatísticas agregadas por cenário e modo (Média, SD, IC95%, IQR)
│   ├── phase5_paired_comparisons.csv           # Testes de hipóteses pareados (t-Student e Wilcoxon Signed-Rank)
│   └── phase5_report.md                        # Relatório técnico consolidado da Fase 5.2
├── run.bat                                     # Compilação e execução rápida da simulação (Windows Batch)
├── run.ps1                                     # Compilação e execução rápida da simulação (PowerShell)
├── run_tests.bat                               # Execução das 12 suites de testes automatizados (133 testes - Batch)
├── run_tests.ps1                               # Execução das 12 suites de testes automatizados (133 testes - PowerShell)
├── GUIA_TRABALHO_PRATICO_IA.md                 # Guia técnico formal e estrutura do Artigo Científico IEEE
└── README.md                                   # Documentação principal e registo de marcos do projeto
```

---

## 5. Instruções de Compilação e Execução da Simulação

### Opção A: Execução Gráfica Padrão 3×3 (9 Cruzamentos, 18 Semáforos, 50 Normais + 3 Prioritários = 73 Agentes JADE)
```powershell
.\run.ps1
# ou em linha de comandos Windows:
.\run.bat
```

### Opção B: Execução Direta via Linha de Comandos (com ou sem RMA GUI)
```powershell
# 1. Compilar todo o código-fonte e suites de teste
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java src/test/*.java

# 2. Executar em modo gráfico 3×3 completo (com janela JADE RMA)
java "-Dfile.encoding=UTF-8" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main

# 3. Executar em modo gráfico 3×3 sem janela JADE RMA
java "-Dfile.encoding=UTF-8" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main --no-rma

# 4. Executar auditoria gráfica cronometrada de 15 segundos com relatório de FPS, Memória, CPU e FIPA-ACL
java "-Dfile.encoding=UTF-8" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main --no-rma --demo-seconds=15

# 5. Executar em modo de compatibilidade histórica 2×2 (4 cruzamentos, 8 semáforos)
java "-Dfile.encoding=UTF-8" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main --2x2
```

---

## 6. Execução das 12 Suites de Testes Automatizados (133 Testes — Fases 1 a 6.1)

O projeto conta com **133 testes automatizados distribuídos por 12 suites independentes (100% de aprovação: 133 PASSOU | 0 FALHOU)**:

```powershell
powershell -ExecutionPolicy Bypass -File .\run_tests.ps1
# ou:
.\run_tests.bat
```

| # | Suite Automatizada | Fase | N.º de Testes | Escopo Validado |
| :-: | :--- | :---: | :---: | :--- |
| 1 | `test.SafetyTestSuite` | Fase 1 | `7` | Máquina de estados segura, transições `YELLOW`/`ALL_RED`, emergências simultâneas e invariante anti-colisão. |
| 2 | `test.VehicleTestSuite` | Fase 2 | `7` | Resolução canónica de semáforos, deteção espacial de cruzamentos e anti-debouncing. |
| 3 | `test.CoordinationTestSuite` | Fase 3 | `10` | Telemetria de corredores, Onda Verde (*Green Wave*), políticas `BALANCED` e precedência de socorro. |
| 4 | `test.DatasetTestSuite` | Fase 4.1 | `8` | Geração determinística de 9.216 observações ($t+60\text{s}$), ausência de *data leakage* e isolamento por `RunId`. |
| 5 | `test.MLModelsTestSuite` | Fase 4.2 | `14` | Regressão Logística L2, Árvore CART (`maxDepth=2`), `FeatureScaler`, matriz de confusão e métricas analíticas. |
| 6 | `test.ModelSelectionTestSuite` | Fase 4.3 | `13` | Seleção em `VAL`, persistência binária, verificação de integridade SHA-256 e avaliação cega em `TEST`. |
| 7 | `test.OnlineMLIntegrationTestSuite` | Fase 4.4 | `11` | Inferência periódica do `DisruptionManagementAgent`, envio FIPA `DISTURBANCE_ALERT` e reação do Coordenador. |
| 8 | `test.MetricsTestSuite` | Fase 5.1 | `8` | Contagem exata de travessias, debounce de `2.0s` contra dupla contagem e exportação CSV. |
| 9 | `test.ExperimentalEvaluationTestSuite` | Fase 5.2 | `10` | Comparação controlada dos 4 modos (`FIXED_TIME`, `LOCAL_ADAPTIVE`, `MULTIAGENT_COORDINATED`, `MULTIAGENT_ML`). |
| 10 | `test.ExperimentalCorrectiveTestSuite` | Fase 5.2-A | `9` | Sincronização determinística `SimulationClock`/JADE, *warm-up* de `120s` e janela de medição de `600s`. |
| 11 | `test.ExperimentalFullCampaignTestSuite` | Fase 5.2-B | `15` | Campanha fatorial completa ($4 \times 4 \times 10 = 160$ corridas), testes pareados $t$-Student e Wilcoxon. |
| 12 | `test.Phase61ValidationTestSuite` | **Fase 6.1** | **`21`** | **Topologia 3×3 (9 nós), 18 semáforos no DF, Manhattan exata/admissível, A\* vs. Dijkstra (81 pares), rotas bloqueadas/recalculadas, 73 agentes JADE, ciclo `0..50` normais + 3 prioritários e compatibilidade 2×2.** |
| **Total** | **12 Suites de Testes** | **Fases 1–6.1** | **`133`** | **`133 PASSOU | 0 FALHOU` (100% Aprovação)** |

---

## 7. Roteiro Evolutivo da Fase 6 (Estado Atual e Próximas Etapas)

- [x] **Fase 6.1 (Concluída — Marco de Inflexão `v6.1-marco-inflexao-3x3`)**: Topologia parametrizável `3×3` (9 cruzamentos), grafo rodoviário direcionado (`24` segmentos), heurística admissível de Manhattan, algoritmo A* validado face a Dijkstra, 18 agentes semáforo e arranque com 50 veículos normais autónomos + 3 veículos prioritários = **73 agentes JADE**.
- [ ] **Fase 6.2**: Controlos gráficos Swing na interface para adicionar/remover veículos em execução (`0..50`) e controlo manual seguro de semáforos por clique via `IntersectionSafetyAuthority`.
- [ ] **Fase 6.3**: Meteorologia e condição física do piso independentes e alteráveis em tempo real (céu limpo, chuva, piso molhado, neve, gelo/baixa aderência) com adaptação física prudente dos veículos e dos semáforos.
- [ ] **Fase 6.4**: Deteção e cedência física de passagem a veículos prioritários (*corredor de emergência*), mudanças de faixa seguras e política escalonada de prioridades sem *starvation*.
- [ ] **Fase 6.5**: Coordenação multiagente completa nos 6 corredores `3×3`, novo dataset e treino específico de Machine Learning para 9 cruzamentos, e campanha experimental comparativa final `3×3`.

---

## 8. Calendário Operacional e Entregas

* **Entrega Intermédia (30%):** **1 de Novembro** (Apresentação e Demonstração Laboratorial Obrigatória: **2 a 4 de Novembro**).
* **Entrega Final (70%):** **3 de Janeiro** (Demonstração: 20%, Código-Fonte: 25%, Artigo Científico SCImago Q1/Q2/Q3: 25%).

Para detalhes adicionais sobre o modelo mental dos agentes e a redação do Artigo Científico em formato IEEE, consulte o documento [GUIA_TRABALHO_PRATICO_IA.md](GUIA_TRABALHO_PRATICO_IA.md) e o relatório experimental consolidado da Fase 5.2 em [results/phase5_report.md](results/phase5_report.md).
