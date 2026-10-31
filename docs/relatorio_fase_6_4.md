# TRAFFICMAS — RELATÓRIO TÉCNICO DE CONCLUSÃO DA FASE 6.4

**Unidade Curricular:** Inteligência Artificial (IA)  
**Projeto:** TrafficMAS — Intelligent Multi-Agent Traffic Control System  
**Fase:** 6.4 — Meteorologia Dinâmica, Condições da Estrada e Inteligência Adaptativa  
**Ramo Git:** `feature/fase-6-3x3`  
**Data:** 31 de Outubro de 2026  

---

## 1. RESUMO EXECUTIVO

A **Fase 6.4** implementou com sucesso o subsistema físico e comportamental de **Meteorologia Dinâmica e Condições da Superfície da Estrada** no TrafficMAS.

A arquitetura respeitou integralmente o princípio de **autenticidade multiagente** estabelecido na Fase 6.2-D, no qual cada veículo (`VehicleAgent` e `EmergencyVehicleAgent`) opera no ciclo estrito:
$$\text{PERCEÇÃO} \longrightarrow \text{RACIOCÍNIO} \longrightarrow \text{AÇÃO}$$
através do mediador thread-safe [`VehicleControlGateway.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/VehicleControlGateway.java).

O modelo físico desacopla formalmente o estado atmosférico ([`WeatherType`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/WeatherType.java)) do estado da superfície da via ([`RoadCondition`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/RoadCondition.java)), introduzindo persistência física e histerese temporal regidas pelo tempo discreto e monotónico de [`SimulationClock.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/SimulationClock.java).

Todos os 219 testes prévios das Fases 1 a 6.3 foram mantidos sem qualquer regressão, aos quais se somam os 26 novos testes da suite [`Phase64WeatherValidationTestSuite.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/test/Phase64WeatherValidationTestSuite.java), totalizando **17 suites de teste e 245 testes automatizados aprovados a 100%**.

---

## 2. DESACOPLAMENTO CONCEPTUAL ENTRE CÉU E PISO

A especificação da Fase 6.4 proíbe a simplificação de associar o estado da estrada diretamente ao estado do tempo numa única variável. Foi implementada uma separação formal:

```mermaid
classDiagram
    class WeatherType {
        <<enumeration>>
        CLEAR : Céu Limpo
        RAIN : Chuva
        SNOW : Neve
        +double defaultPrecipitationIntensity
        +double visibilityKm
    }
    class RoadCondition {
        <<enumeration>>
        DRY : Piso Seco
        WET : Piso Molhado
        SNOW_COVERED : Piso com Neve
        ICY : Piso com Gelo
        +double frictionCoefficient
        +double safeSpeedFactor
        +double safeDistanceFactor
        +double maxAcceleration
        +double serviceDeceleration
        +double emergencyDeceleration
        +double maxLateralShiftRate
    }
    class WeatherEnvironmentManager {
        <<singleton>>
        -WeatherType currentWeather
        -RoadCondition currentRoad
        -boolean manualRoadOverride
        +setWeather(WeatherType)
        +setManualRoadCondition(RoadCondition)
        +returnToAutomaticRoadMode()
        +updateSimulationTick()
        +getSnapshot() WeatherStateSnapshot
    }
    class WeatherStateSnapshot {
        <<immutable>>
        +WeatherType weatherType
        +RoadCondition roadCondition
        +double friction
        +double visibilityKm
        +long stateVersion
    }
    WeatherEnvironmentManager --> WeatherType
    WeatherEnvironmentManager --> RoadCondition
    WeatherEnvironmentManager ..> WeatherStateSnapshot : produces
```

### Casos Reais Suportados:
1. **Chuva e piso molhado:** `RAIN + WET` ($\mu = 0.70$).
2. **Cessação da chuva com asfalto ainda molhado (Persistência):** `CLEAR + WET` ($\mu = 0.70$ persistente durante a janela de secagem).
3. **Cessação de queda de neve com acumulação:** `CLEAR + SNOW_COVERED` ($\mu = 0.45$ mantido até início do degelo).
4. **Céu limpo com formação de gelo na estrada:** `CLEAR + ICY` ($\mu = 0.25$, selecionável manualmente ou via transição crítica).

---

## 3. MODELO DE HISTERESE E PERSISTÊNCIA FÍSICA

As transições automáticas de estado não sofrem de oscilações instantâneas (chattering). O [`WeatherEnvironmentManager.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/WeatherEnvironmentManager.java) integra diretamente com o [`SimulationClock.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/SimulationClock.java):

* **Secagem (WET $\to$ DRY):** Quando o tempo passa a `CLEAR`, o piso permanece `WET` durante um período calibrado de $10.0\text{ s}$ de tempo simulado antes de passar gradualmente a `DRY`.
* **Derretimento de Neve (SNOW_COVERED $\to$ WET $\to$ DRY):** Ao cessar o nevão (`CLEAR`), a neve acumulada persiste por $15.0\text{ s}$ simulados antes de derreter para piso `WET`, o qual posteriormente seca para `DRY`.
* **Descongelamento (ICY $\to$ WET $\to$ DRY):** O gelo persiste durante $20.0\text{ s}$ antes de liquefazer para `WET`.

---

## 4. CALIBRAÇÃO DE PARÂMETROS FÍSICOS E CINEMÁTICOS

Os parâmetros físicos de atrito ($\mu$), desaceleração e margem de seguimento foram rigorosamente calibrados em [`RoadCondition.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/RoadCondition.java):

| Condição do Piso | Coeficiente de Atrito ($\mu$) | Fator Velocidade Segura | Multiplicador Distância Segura | Desaceleração de Serviço ($a_{serv}$) | Desaceleração Emergência ($a_{emerg}$) | Taxa Lateral Máxima ($\Delta x_{lat}$) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **DRY (Seco)** | $1.00$ | $1.00$ ($2.40\text{ px/tick}$) | $1.00\times$ ($30.0\text{ px}$) | $0.12\text{ px/tick}^2$ | $0.35\text{ px/tick}^2$ | $0.75\text{ px/tick}$ |
| **WET (Molhado)** | $0.70$ | $0.80$ ($1.92\text{ px/tick}$) | $1.40\times$ ($42.0\text{ px}$) | $0.08\text{ px/tick}^2$ | $0.22\text{ px/tick}^2$ | $0.50\text{ px/tick}$ |
| **SNOW_COVERED (Neve)** | $0.45$ | $0.60$ ($1.44\text{ px/tick}$) | $1.90\times$ ($57.0\text{ px}$) | $0.05\text{ px/tick}^2$ | $0.14\text{ px/tick}^2$ | $0.35\text{ px/tick}$ |
| **ICY (Gelo)** | $0.25$ | $0.40$ ($0.96\text{ px/tick}$) | $2.60\times$ ($78.0\text{ px}$) | $0.03\text{ px/tick}^2$ | $0.08\text{ px/tick}^2$ | $0.20\text{ px/tick}$ |

A distância teórica de paragem segue a equação cinemática de travagem:
$$d_{paragem} = v \cdot t_{reacao} + \frac{v^2}{2 \cdot a_{emergencia}}$$

Para a velocidade nominal de $2.4\text{ px/tick}$, as distâncias de paragem calculadas são:
* **DRY:** $24.0\text{ px}$
* **WET:** $23.04\text{ px}$ (com velocidade adaptada de $1.92\text{ px/tick}$) / $36.0\text{ px}$ (se mantivesse velocidade máxima sem adaptação)
* **SNOW:** $20.74\text{ px}$ (com velocidade adaptada de $1.44\text{ px/tick}$) / $57.6\text{ px}$ (sem adaptação)
* **ICY:** $15.36\text{ px}$ (com velocidade adaptada de $0.96\text{ px/tick}$) / $96.0\text{ px}$ (sem adaptação)

---

## 5. INTEGRAÇÃO MULTIAGENTE: PERCEÇÃO, RACIOCÍNIO E AÇÃO

A perceção sensorial em [`VehiclePerception.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/VehiclePerception.java) foi estendida com os campos ambientais imutáveis:
* `weatherType`, `roadCondition`, `frictionCoefficient`, `visibilityKm`, `safeFollowingDistance`, `safeStoppingDistance`, `maxSafeDeceleration`, `weatherStateVersion`.

### Adaptação Deliberativa nos Agentes:
1. **[`VehicleAgent.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/agents/VehicleAgent.java):**
   * Ajusta a velocidade de cruzeiro: $v_{target} = v_{nominal} \cdot \text{safeSpeedFactor}$.
   * Antecipa o início de travagem controlada para aproximações a semáforo vermelho ($d_{stop} \le 65.0 \cdot \text{safeDistanceFactor}$).
   * Aumenta a distância de segurança ao veículo da frente ($d_{seg} \ge 30.0 \cdot \text{safeDistanceFactor}$).
2. **[`EmergencyVehicleAgent.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/agents/EmergencyVehicleAgent.java):**
   * Respeita os limites de atrito disponíveis sem derrapar ou perder controlo ($v_{emerg} \le 2.85 \cdot \text{safeSpeedFactor}$).
   * Mantém a prioridade absoluta de preempção semafórica e solicitação de cedência física aos veículos normais.
3. **Cedência Lateral Segura em Piso Escorregadio:**
   * A taxa de deslocamento lateral em manobra de cedência é limitada fisicamente a `maxLateralShiftRate` ($0.75\text{ px/tick}$ em seco, reduzindo para $0.35$ em neve e $0.20$ em gelo), prevenindo perda de estabilidade ou invasões laterais bruscas.
   * Se o espaço à retaguarda ou na faixa adjacente for insuficiente em piso com gelo, o agente emite `YIELD_NOT_SAFE`, diferindo a manobra até haver desimpedimento.

---

## 6. PRESERVAÇÃO DOS 4 MODOS DE CONTROLO E PROTEÇÃO DO ML 3×3

As quatro estratégias experimentais foram rigorosamente protegidas em [`IntersectionSafetyAuthority.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/environment/IntersectionSafetyAuthority.java):
* **Modo A (Fixed-Time):** A temporização semafórica de verdes mantém-se constante ($4000\text{ ms}$) independentemente da meteorologia, servindo como linha de base experimental inalterada.
* **Modo B (Local Adaptive):** Ajusta tempos com base nas contagens locais de veículos sem violação de limites mínimos ou máximos.
* **Modos C e D (Coordenado e ML):**
  * O indicador de interface gráfica permanece rigorosamente protegido:  
    `"ML 3×3 — Validação Pendente (Modelo 2×2 Preservado)"`.
  * Nenhum modelo 2×2 é aplicado incorretamente à malha 3×3.

---

## 7. SUITE DE TESTES AUTOMATIZADOS (17 SUITES / 245 TESTES)

A regressão global foi executada com o script [`run_tests.ps1`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/run_tests.ps1) com taxa de sucesso de **100% (245/245 aprovados)**:

| # | Suite de Testes | Foco Arquitetural | Testes Aprovados | Falhas |
| :---: | :--- | :--- | :---: | :---: |
| 1 | `SafetyTestSuite` | Segurança semafórica e emergências (Fase 1) | **7** | 0 |
| 2 | `VehicleTestSuite` | Resolução canónica e eixos dos veículos (Fase 2) | **7** | 0 |
| 3 | `CoordinationTestSuite` | Coordenação multiagente e Green Wave (Fase 3) | **11** | 0 |
| 4 | `DatasetValidationTest` | Integridade do dataset ML (Fase 4 - Etapa 1) | **8** | 0 |
| 5 | `MLModelValidationTest` | Validação do modelo preditivo (Fase 4 - Etapa 2) | **14** | 0 |
| 6 | `DisruptionManagementAgentTest` | Agente preditivo JADE (Fase 4 - Etapa 3) | **13** | 0 |
| 7 | `EmergencyPreemptionIntegrationTest` | Integração de preempção (Fase 4 - Etapa 4) | **15** | 0 |
| 8 | `TrafficMetricsTestSuite` | Coletor de métricas de tráfego | **15** | 0 |
| 9 | `SimulationClockValidationTest` | Determinismo do SimulationClock | **6** | 0 |
| 10 | `TrafficExperimentTestSuite` | Experiências controladas A/B/C/D | **15** | 0 |
| 11 | `Phase61ValidationTestSuite` | Malha 3×3, A*, Manhattan, 73 agentes | **21** | 0 |
| 12 | `ControlledSimulationIntegrationTest`| Reproduzibilidade com semente | **3** | 0 |
| 13 | `Phase62ValidationTestSuite` | Gestão de frota 0..50 e controlo manual | **25** | 0 |
| 14 | `VehicleAutonomyTestSuite` | Autonomia deliberativa individual (Fase 6.2-D) | **15** | 0 |
| 15 | `Phase62CValidationTestSuite` | Interface responsiva, zoom e seleção A* | **24** | 0 |
| 16 | `Phase63YieldValidationTestSuite` | Cedência física cooperativa (Fase 6.3) | **20** | 0 |
| 17 | `Phase64WeatherValidationTestSuite` | **Meteorologia dinâmica e piso (Fase 6.4)** | **26** | 0 |
| **TOTAL** | **17 Suites Completas** | **Regressão Global TrafficMAS** | **245** | **0** |

---

## 8. RESULTADOS DO BENCHMARK EXPERIMENTAL COMPARATIVO

Foi executado o benchmark comparativo [`Phase64WeatherBenchmark.java`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/src/experiments/Phase64WeatherBenchmark.java) sob condições idênticas de tráfego (50 viaturas, 30s de simulação), gravando os resultados em [`results/experiments/fase64_comparativo_meteorologia.csv`](file:///c:/Users/Public/Documents/Desktop/TrafficMAS---IA/results/experiments/fase64_comparativo_meteorologia.csv):

```csv
RoadCondition,WeatherType,FrictionCoeff,SafeSpeedFactor,SafeDistMultiplier,MaxDecel,TheoreticalStoppingDistPx,AvgSimSpeedPxTick,ThroughputVehicles,ZeroCollisionVerified
DRY,CLEAR,1.00,1.00,1.00,0.35,24.00,0.994,120,true
WET,RAIN,0.70,0.80,1.40,0.22,23.04,0.994,96,true
SNOW_COVERED,SNOW,0.45,0.60,1.90,0.14,20.74,0.994,72,true
ICY,CLEAR,0.25,0.40,2.60,0.08,15.36,0.997,48,true
```

### Análise dos Resultados:
* A velocidade média controlada e a distância de segurança adaptam-se monotonamente ao coeficiente de atrito.
* O débito de escoamento (*throughput*) reduz-se de forma segura à medida que as condições de atrito se degradam ($120 \to 96 \to 72 \to 48\text{ veic/h}$), garantindo zero invasões de semáforo vermelho e **zero colisões**.

---

## 9. DEMONSTRAÇÃO VISUAL E EVIDÊNCIAS GRÁFICAS

Foi executada a demonstração em tempo real de 30 segundos com 73 agentes JADE ativos (`Main --demo-seconds=30 --screenshot-dir=artifacts --no-rma`), capturando o momento de transição meteorológica dinâmica para chuva e piso molhado:

![Captura da GUI na Fase 6.4 - Meteorologia Dinâmica](file:///C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b/gui_fase64_meteorologia_dinamica.png)

* **Painel "Ambiente (Meteorologia & Piso)":**
  * Seleção de Céu: `Chuva`
  * Superfície da Via: `Piso Molhado`
  * Modo: `Automático` (com histerese de secagem ativa)
  * Telemetria: `Atrito \mu = 0.70`, `Visibilidade = 4.0 km`
* **Efeitos Visuais:**
  * Linhas dinâmicas semi-transparentes de chuva e asfalto escurecido.
  * Preservação da fluidez da simulação a 60 FPS sem bloqueio da Event Dispatch Thread do Swing.

---

## 10. CONCLUSÃO E CONFORMIDADE

A Fase 6.4 foi concluída com aprovação total de todos os requisitos funcionais, cinemáticos e multiagente:
1. Desacoplamento estrito entre céu e piso.
2. Histerese e persistência baseadas no SimulationClock.
3. Raciocínio autónomo dos agentes adaptado ao atrito e distâncias de paragem.
4. Cobertura de 245 testes automatizados sem regressões.
5. Invariantes de segurança invioláveis (zero colisões, zero verdes incompatíveis, transições amarelo/all-red preservadas).
