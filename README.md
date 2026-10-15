# TrafficMAS — Intelligent Multi-Agent Traffic Control System

[![Java](https://img.shields.io/badge/Java-LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![JADE](https://img.shields.io/badge/Multi--Agent-JADE%204.5-007396?style=for-the-badge&logo=eclipse-ide&logoColor=white)](https://jade.tilab.com)
[![ISPGaya](https://img.shields.io/badge/Instituição-ISPGaya-red?style=for-the-badge)](http://www.ispgaya.pt/)

> **Unidade Curricular:** Inteligência Artificial (1.º Semestre — 2026/2027)  
> **Licenciatura em Engenharia Informática** — **ISPGaya**  
> **Docente Responsável:** Prof. Doutor Tiago Soares da Costa  

---

## 1. Visão Geral do Projeto

O **TrafficMAS** é um ecossistema multiagente descentralizado concebido para otimizar o fluxo de tráfego rodoviário numa rede urbana com **4 cruzamentos inteligentes** e artérias largas de 4 faixas (2 por sentido com manobras de mudança de direção e cinemática contínua).

O sistema substitui os controladores semafóricos tradicionais de temporização fixa (*Fixed-Time*) por uma arquitetura autónoma e distribuída assente na plataforma **JADE** (*Java Agent Development Framework*), em estrita conformidade com as normas **FIPA-ACL** (*Foundation for Intelligent Physical Agents*).

```
                      ┌──────────────────────────────────────────────┐
                      │    Agente de Coordenação Central (Macro)     │
                      └──────────────────────┬───────────────────────┘
                                             │ FIPA-ACL (Diretivas)
         ┌───────────────────────────────────┼───────────────────────────────────┐
         ▼                                   ▼                                   ▼
┌──────────────────┐               ┌──────────────────┐               ┌──────────────────┐
│ Semáforos C1_NW  │ ◄─FIPA-ACL─►  │ Semáforos C2_NE  │ ◄─FIPA-ACL─►  │ Semáforos C3/C4  │
└────────▲─────────┘               └────────▲─────────┘               └────────▲─────────┘
         │                                  │                                  │
         │ FIPA-ACL (Socorro/Espera)        │                                  │
┌────────┴─────────┐                        │                                  │
│ Viaturas Socorro │                        │                                  │
│  (Ambulâncias)   │                        │                                  │
└──────────────────┘                        │                                  │
                                   ┌────────┴─────────┐               ┌────────┴─────────┐
                                   │ Viaturas Comuns  │               │ Agente Preditivo │
                                   │ (Carros 01 a 05) │               │   ML Disrupção   │
                                   └──────────────────┘               └──────────────────┘
```

---

## 2. Conformidade Rigorosa com a Folha de Requisitos (Aula_01)

| Funcionalidade Oficial | Componente no Código | Estado | Descrição da Implementação |
| :--- | :--- | :---: | :--- |
| **1. Traffic Environment** | `TrafficEnvironment.java` | ✅ Concluído | Camada unificada de sensores e atuadores acoplada à simulação cinemática contínua (`CitySimulationGUI`). |
| **2. Traffic Light Agents** | `TrafficLightAgent.java` | ✅ Concluído | 8 agentes semáforo coordenados com gestão segura de fases (intertravamento sem colisões) e registo no `DFService`. |
| **3. Vehicle Agents** | `VehicleAgent.java` | ✅ Concluído | Viaturas regulares que monitorizam o tempo de imobilização e emitem mensagens `REQUEST` caso a espera exceda o limiar de 6s. |
| **4. Central Coordination** | `CentralCoordinationAgent.java` | ✅ Concluído | Monitorização holística da rede urbana, deteção de estrangulamentos (*bottlenecks*) e sincronismo de Onda Verde (*Green Wave*). |
| **5. Disruption Management** | `DisruptionManagementAgent.java` | ✅ Concluído | Módulo preditivo de Machine Learning que infere risco de perturbação climática/acidentes e ajusta a dinâmica da rede. |
| **6. Emergency Priority** | `EmergencyVehicleAgent.java` | ✅ Concluído | Preempção imediata de socorro (ambulâncias e polícia): forçam a abertura de verde e encerramento seguro das vias transversais. |
| **7. Real-Time Adjustments** | `TrafficLightAgent.java` | ✅ Concluído | Adaptação dinâmica da duração do sinal verde em função das filas em espera: $T_{verde} = T_{base} + \min(800 \times N_{carros}, 4000\text{ms})$. |
| **8. Performance Metrics** | `CitySimulationGUI.java` | ✅ Concluído | Telemetria visual no HUD em tempo real: tempo médio de espera, débito total de viaturas, número de socorros e estado meteorológico. |

---

## 3. Estrutura do Repositório

```
TrafficMAS---IA/
├── lib/
│   ├── jade.jar                     # Runtime da plataforma JADE 4.5
│   └── commons-codec-1.3.jar        # Codificação e utilitários FIPA
├── src/
│   ├── Main.java                    # Ponto de entrada e arranque do ecossistema JADE
│   ├── agents/
│   │   ├── TrafficLightAgent.java          # Semáforos adaptativos com telemetria FIPA
│   │   ├── VehicleAgent.java               # Viaturas regulares com deteção dinâmica de cruzamento
│   │   ├── EmergencyVehicleAgent.java      # Viaturas prioritárias com preempção de socorro
│   │   ├── DisruptionManagementAgent.java  # Agente preditivo de perturbações e atrito viário
│   │   └── CentralCoordinationAgent.java   # Coordenador Central (Onda Verde e Equilíbrio FIPA)
│   ├── environment/
│   │   ├── IntersectionSafetyAuthority.java # Autoridade única de segurança e controlo atómico de fase
│   │   ├── TrafficEnvironment.java         # Fachada oficial de sensores/atuadores (compatível com headless)
│   │   └── CitySimulationGUI.java          # Interface visual com cinemática contínua de 4 cruzamentos
│   └── test/
│       ├── SafetyTestSuite.java            # Suite Fase 1: Segurança de fases e emergências (7 testes)
│       ├── VehicleTestSuite.java           # Suite Fase 2: Deteção de cruzamentos e DF dos veículos (7 testes)
│       └── CoordinationTestSuite.java      # Suite Fase 3: Coordenação multiagente e Onda Verde (11 testes)
├── bin/                             # Binários compilados (.class)
├── run.bat                          # Execução rápida da simulação (Windows)
├── run.ps1                          # Execução rápida da simulação (PowerShell)
├── run_tests.bat                    # Execução das 3 suites de testes automatizados (Batch)
├── run_tests.ps1                    # Execução das 3 suites de testes automatizados (PowerShell)
├── GUIA_TRABALHO_PRATICO_IA.md      # Manual técnico formal e especificações do artigo IEEE
└── README.md                        # Documentação principal
```

---

## 4. Instruções de Execução da Simulação

### Opção A: Execução Imediata (Recomendada no Windows)
Faça duplo clique no ficheiro `run.bat` ou execute no terminal:
```powershell
.\run.bat
```

### Opção B: Execução via PowerShell
```powershell
.\run.ps1
```

### Opção C: Compilação Manual via Linha de Comandos
```powershell
# 1. Compilar todas as classes do projeto
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java

# 2. Executar a plataforma multiagente
java -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main
```

---

## 5. Execução dos Testes Automatizados (Fases 1, 2 e 3)

O projeto dispõe de **25 testes automatizados com 100% de aprovação**, cobrindo o invariante anti-colisão, a preempção de socorro, a resolução de nós viários e o protocolo completo de coordenação e onda verde.

### Executar Todas as Suites de Testes (Recomendado):
```powershell
.\run_tests.ps1
# ou em linha de comandos Windows:
.\run_tests.bat
```

### Suites Individuais:
```powershell
# Fase 1: Segurança e Invariante de Fases (7 testes)
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

# Fase 2: Agentes de Veículos e Deteção Espacial (7 testes)
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

# Fase 3: Coordenação Multiagente e Onda Verde (11 testes)
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite
```

---

## 6. Calendário Operacional e Entregas

* **Entrega Intermédia (30%):** **1 de Novembro** (Apresentação e Demonstração Laboratorial Obrigatória: **2 a 4 de Novembro**).
* **Entrega Final (70%):** **3 de Janeiro** (Demonstração: 20%, Código-Fonte: 25%, Artigo Científico SCImago Q1/Q2/Q3: 25%).

Para detalhes adicionais sobre o modelo mental dos agentes e a redação do Artigo Científico em formato IEEE, consulte o documento [GUIA_TRABALHO_PRATICO_IA.md](GUIA_TRABALHO_PRATICO_IA.md).
