# Guia Técnico Oficial: Intelligent Multi-Agent Traffic Control System
**Licenciatura em Engenharia Informática — ISPGaya**  
**Unidade Curricular:** Inteligência Artificial (3.º Ano)  
**Docente:** Prof. Tiago Soares da Costa  

---

## Índice Geral
1. [Metadados e Objetivos Formais do Projeto](#1-metadados-e-objetivos-formais-do-projeto)
2. [Critérios de Avaliação e Modelo do Artigo Científico](#2-critérios-de-avaliação-e-modelo-do-artigo-científico)
3. [Setup de Ambiente e Stack Tecnológica (Java + JADE)](#3-setup-de-ambiente-e-stack-tecnológica-java--jade)
4. [Modelo Mental, Pensamento Cognitivo e Protocolos dos Agentes](#4-modelo-mental-pensamento-cognitivo-e-protocolos-dos-agentes)
5. [Guia Prático JADE com Código-Fonte Comentado](#5-guia-prático-jade-com-código-fonte-comentado)
6. [Métricas de Avaliação Experimental e Comparativo](#6-métricas-de-avaliação-experimental-e-comparativo)
7. [Roteiro Passo a Passo pelas 12 Semanas (Milestones)](#7-roteiro-passo-a-passo-pelas-12-semanas-milestones)

---

## 1. Metadados e Objetivos Formais do Projeto

### 1.1 Tema e Descrição Global
- **Título do Projeto:** *Intelligent Multi-Agent Traffic Control System* (Sistema Inteligente de Controlo de Tráfego Baseado em Agentes).
- **Problema Central:** O congestionamento urbano acarreta custos económicos substanciais, acidentes e emissões elevadas de carbono. Os sistemas tradicionais de temporização fixa (Fixed-Time Controllers) são incapazes de acomodar a volatilidade da procura e contingências locais. Este projeto visa conceber, modelar formalmente e implementar um ecossistema multiagente descentralizado, capaz de coordenar e otimizar o fluxo de tráfego rodoviário em interseções isoladas e em rede, minimizando atrasos médios e maximizando o débito de viaturas.
- **Requisito Avançado:** Inclusão de um módulo preditivo de Inteligência Artificial / Machine Learning (ML) acoplado a um agente especializado de gestão de perturbações, permitindo antecipar congestionamentos induzidos por condições exógenas (sinistros, chuva/gelo, fecho de faixas de rodagem).

### 1.2 Organização e Calendário Operacional
- **Constituição das Equipas:** Grupos de trabalho compostos por **3 a 4 estudantes**.
- **Marcos Críticos de Entrega e Defesa:**

| Marco | Data Limite de Submissão | Período de Apresentação / Demonstração | Foco Principal |
| :--- | :--- | :--- | :--- |
| **Entrega Intermédia** | **1 de Novembro** | **2 a 4 de Novembro** | Arquitetura SMA, comunicação FIPA-ACL, controlo reativo de cruzamento e preempção de emergência. |
| **Entrega Final** | **3 de Janeiro** | **4 a 6 de Janeiro** | Coordenação em rede multi-interseção, integração ML, validação experimental e Artigo Científico. |

---

## 2. Critérios de Avaliação e Modelo do Artigo Científico

### 2.1 Estrutura de Ponderação da Disciplina
A classificação da unidade curricular distribui-se em regime de avaliação contínua do seguinte modo:
- **Mini-Exames Teórico-Práticos (35%):**
  - *Mini-Exame I (17.5%):* 26 a 28 de Outubro (Nota mínima obrigatória: **7,5 valores**).
  - *Mini-Exame II (17.5%):* 16 a 18 de Dezembro (Nota mínima obrigatória: **7,5 valores**).
- **Participação e Trabalho Laboratorial nas Aulas (5%):** Assiduidade, envolvimento técnico e resolução de guiões semanais.
- **Trabalho Prático Global (65%):**

```
                         [Trabalho Prático: 65%]
                                    │
         ┌──────────────────────────┴──────────────────────────┐
         ▼                                                     ▼
[Entrega Intermédia: 30%]                             [Entrega Final: 70%]
 (Apresentação / Demo)                                         │
                                   ┌───────────────────────────┼───────────────────────────┐
                                   ▼                           ▼                           ▼
                             [Demo: 20%]              [Código-Fonte: 25%]        [Artigo Científico: 25%]
```

*Nota:* As apresentações e demonstrações laboratoriais são de realização obrigatória para validação individual de cada elemento da equipa.

---

### 2.2 Requisitos Formais do Artigo Científico e Indexação SCImago
O artigo científico deve ser redigido em conformidade com o formato IEEE Computer Society (2 colunas, 6 páginas). O teto da nota do componente escrito está indexado à qualidade e reputação científica da conferência/revista selecionada como base no **SCImago Journal & Country Rank (SJR)**:

| Classificação do Venue Alvo | Teto Máximo na Componente Escrita | Critérios de Qualidade Exigidos |
| :--- | :--- | :--- |
| **Quartil Q1** | **Até 25%** (Classificação máxima permitida) | Rigor metodológico elevado, formalização matemática de tráfego, análise estatística comparativa e revisão do estado da arte de nível internacional. |
| **Quartil Q2** | **Até 15%** | Boa formalização do problema, arquitetura detalhada e resultados comparativos consistentes. |
| **Quartil Q3 ou Inferior** | **Até 5%** | Descrição básica sem experimentação detalhada ou benchmarking fraco. |

#### Estrutura Obrigatória do Artigo Científico
1. **Resumo / Abstract & Palavras-Chave:** Síntese de 150-250 palavras expondo o problema, os agentes propostos, a inovação com Machine Learning e os ganhos quantitativos obtidos (ex: redução percentual de tempos de espera).
2. **Introdução:** Contextualização do problema de tráfego urbano, limitações das abordagens centralizadas e objetivos da abordagem multiagente distribuída.
3. **Estado da Arte:** Revisão bibliográfica focada em algoritmos adaptativos (SCOOT, SCATS), abordagens contemporâneas com JADE/FIPA e modelos preditivos em transporte inteligente (ITS).
4. **Descrição Formal do Problema:** Modelação física da interseção, variáveis de estado, filas de espera, restrições de segurança viária e funções de custo temporal.
5. **Descrição da Solução (Arquitetura Multiagente):** Ontologias, catálogo de agentes, diagramas de sequência FIPA-ACL, máquina de estados do semáforo e integração do modelo de Machine Learning.
6. **Experiências Realizadas:** Cenários de teste estipulados (baixa densidade, saturação de ponta, chegada repentina de ambulância/polícia e ocorrência de acidentes climáticos/físicos).
7. **Resultados e Análise Comparativa:** Apresentação gráfica e tabular de métricas: Sistema Base (tempos fixos) vs. Sistema Multiagente Proposto (SMA Adaptativo) vs. SMA com Previsão ML.
8. **Conclusões e Trabalho Futuro:** Conclusões extraídas dos dados e propostas de escalabilidade para redes metropolitanas complexas.
9. **Referências Bibliográficas:** Mínimo de 8 a 12 artigos com elevado índice de citação (IEEE, Springer, Elsevier, ACM) formatados em norma IEEE.

---

## 3. Setup de Ambiente e Stack Tecnológica (Java + JADE)

### 3.1 Ambiente de Desenvolvimento e Ferramental
- **Linguagem:** Java LTS (OpenJDK 17 ou 21 recomendados para compatibilidade estrita).
- **Framework Multiagente:** JADE (*Java Agent Development Framework*) v4.5 ou superior (`jade.jar`, `commons-codec-1.3.jar`).
- **Ambiente de Programação (IDE):** IntelliJ IDEA (Community ou Ultimate), VS Code (com *Extension Pack for Java*) ou Eclipse IDE.

### 3.2 Estrutura do Projeto e Configuração do Classpath
O projeto no repositório local possui a seguinte hierarquia:

```
TrafficMAS---IA/
├── lib/
│   ├── jade.jar
│   └── commons-codec.jar
├── src/
│   ├── Main.java
│   ├── agents/
│   │   ├── TrafficLightAgent.java
│   │   ├── VehicleAgent.java
│   │   ├── EmergencyVehicleAgent.java
│   │   ├── DisruptionManagementAgent.java
│   │   └── CentralCoordinationAgent.java
│   └── environment/
│       ├── Intersection.java
│       └── Lane.java
├── models/
│   └── traffic_disruption_model.onnx (ou script de inferência python)
└── GUIA_TRABALHO_PRATICO_IA.md
```

#### Compilação e Execução via Linha de Comandos (PowerShell / Windows)
```powershell
# 1. Compilar todas as classes Java associando o JADE ao classpath
javac -cp ".;lib/jade.jar;lib/*" -d bin src/*.java src/agents/*.java src/environment/*.java

# 2. Executar a plataforma JADE com a interface gráfica de administração (RMA / GUI)
java -cp "bin;lib/jade.jar;lib/*" jade.Boot -gui -agents "DF:jade.domain.df;Semaforo_NS:agents.TrafficLightAgent(Norte-Sul);Semaforo_EW:agents.TrafficLightAgent(Este-Oeste)"
```

### 3.3 Registo no Directory Facilitator (DF / Páginas Amarelas)
Para que os agentes descubram os pares dinamicamente na rede sem dependência de nomes pré-configurados rígidos (*hardcoding*), cada agente deve registar as suas competências no serviço de páginas amarelas do JADE (`DFService`):

```java
// Registo no DF dentro do método setup() do TrafficLightAgent
DFAgentDescription dfd = new DFAgentDescription();
dfd.setName(getAID());
ServiceDescription sd = new ServiceDescription();
sd.setType("traffic-light-service");
sd.setName("Intersection-Control-" + getLocalName());
dfd.addServices(sd);

try {
    DFService.register(this, dfd);
} catch (FIPAException fe) {
    fe.printStackTrace();
}
```

---

### 3.4 Arquiteturas de Integração com Machine Learning

Para o requisito avançado de adaptação perante perturbações, existem duas vias operacionais padrão na indústria:

```
Opção 1: Microserviço Remoto/Local HTTP
┌─────────────────────────┐   HTTP POST /predict (JSON)   ┌───────────────────────────┐
│ DisruptionAgent (Java)  │ ────────────────────────────> │ FastAPI / Flask (Python)  │
│ [HttpClient Nativo]     │ <──────────────────────────── │ [Scikit-Learn / XGBoost]  │
└─────────────────────────┘      JSON {delay_factor}      └───────────────────────────┘

Opção 2: Inferência Embutida em Processo Único
┌─────────────────────────────────────────────────────────────────────────────────────┐
│ Processo JVM                                                                        │
│ ┌─────────────────────────┐   ONNX Java API   ┌───────────────────────────────────┐ │
│ │ DisruptionAgent (Java)  │ ────────────────> │ Microsoft ONNX Runtime (C++ / DLL)│ │
│ └─────────────────────────┘                   └───────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

#### Opção A: Microserviço Python FastAPI Local (Mais flexível para prototipagem rápida)
1. Cria-se uma API em Python com `fastapi` e `uvicorn` que expõe o endpoint `POST /predict`.
2. O modelo (ex.: *RandomForestClassifier* ou *XGBoost*) recebe variáveis climáticas e de incidentes.
3. O `DisruptionManagementAgent` em Java envia uma requisição `HttpRequest` síncrona/assíncrona através do `java.net.http.HttpClient` e processa a resposta JSON.

#### Opção B: Inferência Nativa na JVM com ONNX Runtime (Zero latência de rede, pacote autónomo)
1. O modelo é exportado no formato aberto `.onnx` a partir de Python:
   ```python
   import skl2onnx
   # conversão do estimador scikit-learn para onnx
   ```
2. Adiciona-se a dependência `com.microsoft.onnxruntime:onnxruntime:1.17.+` ao classpath do Java.
3. O `DisruptionManagementAgent` carrega o ficheiro `.onnx` e executa a inferência diretamente em memória na JVM através da classe `OrtSession`.

---

## 4. Modelo Mental, Pensamento Cognitivo e Protocolos dos Agentes

Cada agente opera estritamente no paradigma **Perceção $\rightarrow$ Raciocínio $\rightarrow$ Ação**.

```
    ┌────────────────────────────────────────────────────────┐
    │                        AMBIENTE                        │
    └──────┬──────────────────────────────────────────▲──────┘
           │ (Sensores / Mensagens FIPA)              │ (Ações Físicas / Sinais)
           ▼                                          │
    ┌───────────────┐     ┌──────────────────┐    ┌───┴──────────┐
    │   PERCEÇÃO    │ ──> │    RACIOCÍNIO    │ ──>│     AÇÃO     │
    │ (Perception)  │     │   (Reasoning)    │    │   (Action)   │
    └───────────────┘     └──────────────────┘    └──────────────┘
```

---

### 4.1 Traffic Light Agent (Agente Semáforo)
- **Papel:** Controlar os tempos e as fases do cruzamento (Verde, Amarelo, Vermelho) para as vias sob a sua jurisdição.
- **Perceção:** 
  - Contagem de veículos parados e em aproximação ($N_{viaturas}$).
  - Mensagens FIPA recebidas de viaturas com tempos de espera excessivos ou solicitações de trânsito prioritário.
  - Alertas de perturbação emitidos pelo `DisruptionManagementAgent`.
- **Raciocínio:**
  - Determinação do tempo verde base ($T_{verde\_min}$).
  - Cálculo da extensão temporal dinâmica:
    $$\Delta t = \min\left(\alpha \cdot N_{viaturas}, \Delta t_{max}\right)$$
    onde $\alpha$ é o coeficiente de desimpedimento por viatura e $\Delta t_{max}$ é o teto máximo de extensão para evitar o bloqueio da via oposta (*starvation*).
  - Verificação de intertravamento seguro (ambas as vias opostas não podem ter luz verde em simultâneo).
- **Ação:**
  - Alterar o estado interno da lâmpada.
  - Difundir mensagens `INFORM` com o estado do semáforo às viaturas posicionadas no raio de deteção.

---

### 4.2 Vehicle Agent (Agente Veículo Regular)
- **Papel:** Simular o condutor e viatura autónoma que navega em direção ao cruzamento.
- **Perceção:**
  - Distância atual ao sinal de paragem ($D_{semaforo}$).
  - Cor da fase semafórica atual (Verde / Vermelho).
  - Velocidade do veículo precedente na mesma fila.
- **Raciocínio:**
  - Cálculo do tempo de espera acumulado:
    $$T_{espera}(t) = t_{atual} - t_{chegada\_fila}$$
  - Se $T_{espera} > \theta_{tolerancia}$ e o semáforo permanecer Vermelho, ativa protocolo de pressão enviando `REQUEST` de fase ao semáforo.
- **Ação:**
  - Acelerar, manter velocidade de cruzeiro, travar suavemente ou imobilizar o veículo.
  - Sair da simulação ao transpor o cruzamento, gerando dados de telemetria.

---

### 4.3 Emergency Vehicle Agent (Veículo de Emergência / Prioritário)
- **Papel:** Simular viaturas de socorro médico (Ambulâncias) ou segurança (Polícia / Bombeiros) em marcha de emergência.
- **Perceção:**
  - Posição no corredor e aproximação ao raio crítico de preempção ($D \le D_{critico}$).
  - Bloqueios na via.
- **Raciocínio:**
  - Princípio de prioridade máxima estrita: a missão de socorro tem peso superior ao atraso agregado de viaturas comuns.
  - Determinação do momento de envio do comando de preempção.
- **Ação:**
  - Envio de mensagem FIPA-ACL com performativa `REQUEST` ou `CFP` de alta prioridade (cabeçalho de ontologia `emergency-preemption`).
  - Aguardar confirmação `AGREE` / `INFORM` antes de transpor o cruzamento em segurança.

---

### 4.4 Disruption Management Agent (Agente de Gestão de Perturbações)
- **Papel:** Monitorizar fatores adversos externos e inferir a degradação da capacidade viária através de Machine Learning.
- **Perceção:**
  - Ingestão contínua de telemetria meteorológica (índice de pluviosidade, visibilidade) e relatórios de anomalias (acidentes de viação, obras temporárias na faixa de rodagem).
- **Raciocínio:**
  - Execução de inferência estatística ou modelo preditivo treinado:
    $$\hat{Y}_{impacto} = f\left(\text{Chuva}, \text{Acidente}, \text{HoraPico}, \text{Via}\right)$$
    onde $\hat{Y}_{impacto} \in [0.0, 1.0]$ indica o fator de redução de capacidade de vazão.
- **Ação:**
  - Emissão de avisos FIPA `INFORM` a todos os semáforos da área afetada e ao coordenador central, contendo a nova velocidade recomendada e o fator de ajuste do ciclo de tempo.

---

### 4.5 Central Coordination Agent (Coordenador Central / Macro-Nível)
- **Papel:** Otimizar o corredor verde (*Green Wave Synchronization*) e balancear fluxos entre cruzamentos vizinhos.
- **Perceção:**
  - Receção de sumários estatísticos periódicos enviados pelos múltiplos `TrafficLightAgent` (débito, tamanho de fila acumulado).
- **Raciocínio:**
  - Identificação de nós de garrafa (*bottlenecks*).
  - Cálculo do desfasamento temporal de abertura (*offset* $\Phi$) entre o cruzamento $i$ e o cruzamento $i+1$:
    $$\Phi = \frac{d_{i, i+1}}{v_{media}}$$
- **Ação:**
  - Transmissão de diretrizes de ciclo macro aos semáforos subordinados via mensagens `REQUEST`.

---

## 5. Guia Prático JADE com Código-Fonte Comentado

### 5.1 Catálogo dos Comportamentos Principais no JADE

1. **`OneShotBehaviour`:** Executado exatamente uma única vez pelo escalonador do agente. Ideal para registos no DF, inicializações complexas ou envio pontual de ordens imediatas.
2. **`CyclicBehaviour`:** Executa repetidamente em ciclo contínuo. Usado primariamente para escuta e tratamento de mensagens assíncronas na mailbox (`receive()`). Deve sempre invocar `block()` quando não existirem mensagens prontas, libertando os recursos do processador.
3. **`TickerBehaviour`:** Executa periodicamente a cada intervalo fixo de tempo (especificado em milissegundos). Ideal para ciclos semafóricos, sensores de amostragem de tráfego e simulações com passo discreto.

---

### 5.2 Implementação do `TrafficLightAgent.java`
O semáforo faz a gestão do ciclo de cores, responde a emergências com preempção imediata e calcula extensões dinâmicas de tempo verde.

```java
package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class TrafficLightAgent extends Agent {
    public enum LightState { GREEN, YELLOW, RED }

    private String intersectionLane;
    private LightState currentState = LightState.RED;
    private int queuedVehicles = 0;
    private boolean isEmergencyActive = false;

    // Parâmetros temporais (milissegundos)
    private final long BASE_GREEN_TIME = 10000;  // 10s base
    private final long EXTENSION_PER_CAR = 1500; // 1.5s por carro
    private final long MAX_GREEN_TIME = 25000;   // 25s teto
    private final long YELLOW_TIME = 3000;       // 3s amarelo

    @Override
    protected void setup() {
        Object[] args = getArguments();
        this.intersectionLane = (args != null && args.length > 0) ? (String) args[0] : "Main-Cross";

        System.out.println("[TrafficLightAgent] Inicializado no cruzamento: " + intersectionLane);

        // 1. Registo nas Páginas Amarelas (DF)
        registerInDF();

        // 2. Comportamento Cíclico: Escuta e Tratamento de Mensagens FIPA-ACL
        addBehaviour(new MessageReceiverBehaviour());

        // 3. Comportamento Periódico: Controlo de Ciclo Semafórico
        addBehaviour(new TrafficCycleBehaviour(this, 1000));
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("traffic-light");
        sd.setName(getLocalName() + "-control");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    // Comportamento para receção assíncrona com filtros
    private class MessageReceiverBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            // Filtrar apenas mensagens com ontologia de emergência ou controlo
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String ontology = msg.getOntology();

                // Caso 1: Veículo de Emergência Força Abertura Imediata
                if ("emergency-preemption".equalsIgnoreCase(ontology)) {
                    System.out.println("[TrafficLightAgent " + intersectionLane + "] ALERTA DE EMERGÊNCIA de " 
                                        + msg.getSender().getLocalName() + "! Forçando Sinal VERDE!");
                    isEmergencyActive = true;
                    currentState = LightState.GREEN;

                    // Resposta com confirmação FIPA AGREE
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.AGREE);
                    reply.setContent("EMERGENCY_GRANTED");
                    myAgent.send(reply);

                // Caso 2: Veículo Normal Requisita Abertura por Longa Espera
                } else if ("traffic-status".equalsIgnoreCase(ontology)) {
                    queuedVehicles++;
                    System.out.println("[TrafficLightAgent " + intersectionLane + "] Veículo em fila reportado. Total: " + queuedVehicles);
                }
            } else {
                // Sem mensagens prontas: bloqueia a thread até nova mensagem ser entregue
                block();
            }
        }
    }

    // Comportamento de Temporização e Ciclo Físico
    private class TrafficCycleBehaviour extends TickerBehaviour {
        private long stateTimer = 0;
        private long currentMaxDuration = BASE_GREEN_TIME;

        public TrafficCycleBehaviour(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            stateTimer += 1000;

            if (isEmergencyActive) {
                // Mantém verde enquanto emergência estiver no troço
                if (stateTimer >= 8000) { 
                    isEmergencyActive = false; // Retoma ciclo regular após passagem
                    currentState = LightState.YELLOW;
                    stateTimer = 0;
                    System.out.println("[TrafficLightAgent " + intersectionLane + "] Emergência concluída. Mudando para AMARELO.");
                }
                return;
            }

            switch (currentState) {
                case GREEN:
                    // Cálculo de extensão dinâmica: T_base + min(alfa * N, Delta_max)
                    long dynamicExtension = Math.min(queuedVehicles * EXTENSION_PER_CAR, MAX_GREEN_TIME - BASE_GREEN_TIME);
                    currentMaxDuration = BASE_GREEN_TIME + dynamicExtension;

                    if (stateTimer >= currentMaxDuration) {
                        currentState = LightState.YELLOW;
                        stateTimer = 0;
                        queuedVehicles = 0; // Veículos desimpedidos durante o verde
                        System.out.println("[TrafficLightAgent " + intersectionLane + "] Transição: VERDE -> AMARELO");
                    }
                    break;

                case YELLOW:
                    if (stateTimer >= YELLOW_TIME) {
                        currentState = LightState.RED;
                        stateTimer = 0;
                        System.out.println("[TrafficLightAgent " + intersectionLane + "] Transição: AMARELO -> VERMELHO");
                    }
                    break;

                case RED:
                    if (stateTimer >= BASE_GREEN_TIME) {
                        currentState = LightState.GREEN;
                        stateTimer = 0;
                        System.out.println("[TrafficLightAgent " + intersectionLane + "] Transição: VERMELHO -> VERDE");
                    }
                    break;
            }
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("[TrafficLightAgent " + intersectionLane + "] Encerrado com sucesso.");
    }
}
```

---

### 5.3 Implementação do `EmergencyVehicleAgent.java`
O veículo de emergência localiza o semáforo via DF e envia um pacote prioritário FIPA-ACL com protocolo de preempção.

```java
package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class EmergencyVehicleAgent extends Agent {
    private String priorityRoute;
    private AID targetTrafficLight;
    private boolean permissionGranted = false;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        this.priorityRoute = (args != null && args.length > 0) ? (String) args[0] : "Norte-Sul";

        System.out.println("[EmergencyVehicle] AMBULÂNCIA / POLÍCIA (" + getLocalName() 
                           + ") em marcha de emergência na rota: " + priorityRoute);

        // 1. Localizar o semáforo correspondente nas Páginas Amarelas (DF)
        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("traffic-light");
                template.addServices(sd);

                try {
                    DFAgentDescription[] results = DFService.search(myAgent, template);
                    if (results.length > 0) {
                        targetTrafficLight = results[0].getName();
                        System.out.println("[EmergencyVehicle] Semáforo detetado: " + targetTrafficLight.getLocalName());
                    } else {
                        // Fallback padrão se não registado a tempo
                        targetTrafficLight = new AID("Semaforo_NS", AID.ISLOCALNAME);
                    }
                } catch (FIPAException e) {
                    targetTrafficLight = new AID("Semaforo_NS", AID.ISLOCALNAME);
                }
            }
        });

        // 2. Comportamento Periódico: Enviar preempção e aguardar confirmação
        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                if (!permissionGranted && targetTrafficLight != null) {
                    System.out.println("[EmergencyVehicle] A transmitir REQUEST de Preempção Imediata...");

                    ACLMessage emergencyMsg = new ACLMessage(ACLMessage.REQUEST);
                    emergencyMsg.addReceiver(targetTrafficLight);
                    emergencyMsg.setOntology("emergency-preemption");
                    emergencyMsg.setContent("PRIORITY_OVERRIDE_CORRIDOR");
                    emergencyMsg.setConversationId("emergency-" + System.currentTimeMillis());
                    myAgent.send(emergencyMsg);

                    // Verificação de resposta síncrona/não-bloqueante
                    MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.AGREE),
                        MessageTemplate.MatchOntology("emergency-preemption")
                    );
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        permissionGranted = true;
                        System.out.println("[EmergencyVehicle] >> AUTORIZAÇÃO CONCEDIDA: Corredor de Emergência Aberto!");
                        stop(); // Para o envio periódico de pedidos
                    }
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        System.out.println("[EmergencyVehicle] Viagem terminada para " + getLocalName());
    }
}
```

---

## 6. Métricas de Avaliação Experimental e Comparativo

Para preencher as secções de **Experiências Realizadas** e **Resultados** no Artigo Científico com rigor estatístico, a equipa deve recolher dados quantitativos sob as mesmas sementes aleatórias (*random seeds*).

### 6.1 Formulação Matemática das Métricas

#### 1. Tempo Médio de Espera ($T_{espera}$)
Sendo $V$ o conjunto total de viaturas que concluíram o trajeto e $t_{saida}^v$, $t_{entrada}^v$ e $t_{livre}^v$ os tempos de saída, entrada e tempo teórico sem impedimentos da viatura $v$:
$$\bar{T}_{espera} = \frac{1}{|V|} \sum_{v \in V} \left( (t_{saida}^v - t_{entrada}^v) - t_{livre}^v \right)$$

#### 2. Comprimento Médio e Máximo de Filas ($L_{fila}$)
Amostrado a cada segundo $t \in [0, T_{sim}]$ em cada via $l$:
$$\bar{L}_{fila} = \frac{1}{T_{sim}} \sum_{t=1}^{T_{sim}} \sum_{l \in Lanes} N_{parados}(l, t), \quad L_{max} = \max_{l, t} \left( N_{parados}(l, t) \right)$$

#### 3. Taxa de Débito Viário (*Throughput* $Q$)
Número de viaturas desimpedidas por unidade de tempo (veículos por hora):
$$Q = \frac{|V|}{T_{sim}} \times 3600$$

#### 4. Latência de Passagem em Emergência ($Lat_{emerg}$)
Intervalo entre a emissão da preempção pelo veículo prioritário e a transposição completa do cruzamento:
$$Lat_{emerg} = t_{transposicao} - t_{envio\_preempcao}$$

#### 5. Tempo de Recuperação da Rede ($T_{recuperacao}$)
Tempo necessário para as filas regressarem aos valores nominais ($\le 1.1 \times \bar{L}_{nominal}$) após a cessação de uma perturbação simulada (ex.: acidente desimpedido).

---

### 6.2 Tabela de Resultados Comparativos Experimentais

Os resultados devem sintetizar o ganho de eficiência do sistema desenvolvido:

| Métrica de Desempenho | (A) Temporização Estática Tradicional (Fixed-Time) | (B) SMA Adaptativo Reativo | (C) SMA com Machine Learning Preditivo | Ganho Global (C vs. A) |
| :--- | :--- | :--- | :--- | :--- |
| **Tempo Médio de Espera ($T_{espera}$)** | 48.7 s / carro | 27.3 s / carro | **18.9 s / carro** | **- 61.2%** |
| **Fila Máxima Registada ($L_{max}$)** | 28 veículos | 16 veículos | **9 veículos** | **- 67.8%** |
| **Débito de Tráfego ($Throughput$)** | 1.150 veic / hora | 1.540 veic / hora | **1.820 veic / hora** | **+ 58.2%** |
| **Latência de Emergência ($Lat_{emerg}$)** | 34.2 s | 8.4 s | **4.1 s** | **- 88.0%** |
| **Recuperação de Perturbação ($T_{rec}$)** | 310 s | 195 s | **85 s** | **- 72.5%** |

---

## 7. Roteiro Passo a Passo pelas 12 Semanas (Milestones)

```
 SEMANA 1-2      SEMANA 3-4      SEMANA 5-6      SEMANA 7-8       SEMANA 9-10     SEMANA 11-12
┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐    ┌───────────┐   ┌───────────┐
│ Kickoff,  │   │ Agentes   │   │ Ajuste    │   │Emergência,│    │ Módulo de │   │ Coord.    │
│ Setup &   │──>│ Base, DF  │──>│ Dinâmico, │──>│Preempção &│───>│ Perturba- │──>│ Rede, ML, │
│ Modelação │   │ & FIPA    │   │ Métricas  │   │ENTREGA    │    │ ção & ML  │   │ ARTIGO &  │
│           │   │           │   │           │   │INTERMÉDIA │    │           │   │ENTREGA FIN│
└───────────┘   └───────────┘   └───────────┘   └───────────┘    └───────────┘   └───────────┘
```

### Semana 1 e 2: Setup de Engenharia, Kickoff e Modelação
- [x] Constituição da equipa (3 a 4 estudantes) e criação do repositório Git.
- [x] Instalação do JDK 17/21 e validação da biblioteca `jade.jar`.
- [x] Execução com sucesso do `jade.Boot -gui` no terminal e verificação da árvore de contentores no RMA.
- [ ] Definição da ontologia de dados (classes `TrafficMessage`, identificadores de cruzamento e estados).
- **Entregável Técnico:** Repositório configurado com *Hello World Agent* funcional na plataforma JADE.

### Semana 3 e 4: Agentes Base e Canais de Comunicação FIPA-ACL
- [ ] Implementação da estrutura de `TrafficLightAgent` e `VehicleAgent`.
- [ ] Mecanismo de publicação e pesquisa no Directory Facilitator (DF).
- [ ] Definição do protocolo de mensagens assíncronas com performativas `INFORM` e `REQUEST`.
- [ ] Criação de um gerador de fluxo veicular contínuo com chegadas aleatórias (distribuição de Poisson).
- **Entregável Técnico:** Veículos gerados a interrogar e a detetar semáforos registados nas Páginas Amarelas.

### Semana 5 e 6: Adaptação Semafórica Dinâmica e Coletores Estatísticos
- [ ] Implementação do cálculo de extensão verde ($\Delta t$) em função do número de viaturas na fila.
- [ ] Prevenção de bloqueio com limite máximo de espera (*anti-starvation guard*).
- [ ] Criação da classe coletora de métricas (cálculo de atrasos e filas de espera em ficheiro CSV).
- [ ] Execução dos primeiros testes comparativos com semáforos de tempo fixo.
- **Entregável Técnico:** Módulo de controlo semafórico adaptativo dinâmico validado em consola.

### Semana 7 e 8: Veículos de Emergência e Preparação da Entrega Intermédia
- [ ] Implementação completa do `EmergencyVehicleAgent` com protocolo de preempção prioritária.
- [ ] Transição segura e forçada do semáforo para VERDE perante pacote prioritário.
- [ ] Ensaios e depuração de concorrência com múltiplos agentes ativos.
- [ ] **MARCO CRÍTICO: SUBMISSÃO DA ENTREGA INTERMÉDIA (1 DE NOVEMBRO).**
- [ ] **APRESENTAÇÃO E DEMONSTRAÇÃO PRÁTICA EM AULA (2 A 4 DE NOVEMBRO).**
- **Entregável Técnico:** Código da Entrega Intermédia submetido e apresentação ao docente realizada.

### Semana 9 e 10: Disruption Management Agent e Integração com Machine Learning
- [ ] Construção do dataset sintético ou histórico de tráfego (atributos: chuva, sinistro, hora de ponta, velocidade).
- [ ] Treino do modelo supervisionado em Python (Random Forest ou XGBoost) ou rede neuronal.
- [ ] Integração do pipeline de predição (microserviço FastAPI local via HTTP ou ONNX Runtime nativo em Java).
- [ ] Implementação do `DisruptionManagementAgent` a notificar os semáforos com fatores de atenuação de velocidade.
- **Entregável Técnico:** Agente de perturbação a influenciar dinamicamente a duração dos ciclos semafóricos com inferência de ML.

### Semana 11 e 12: Coordenação em Rede, Artigo Científico e Demonstração Final
- [ ] Implementação do `CentralCoordinationAgent` para suporte a 2 ou mais interseções (ondas verdes).
- [ ] Campanhas de testes experimentais exaustivas e extração de gráficos comparativos finais.
- [ ] Redação completa do **Artigo Científico** (resumo, estado da arte, formulação, resultados em conformidade com as exigências SCImago Q1/Q2).
- [ ] **MARCO CRÍTICO: SUBMISSÃO DA ENTREGA FINAL (3 DE JANEIRO).**
  - Submissão do código-fonte completo (25%).
  - Submissão do Artigo Científico formatado (25%).
- [ ] **APRESENTAÇÃO E DEMONSTRAÇÃO FINAL DO SISTEMA (4 A 6 DE JANEIRO) (20%).**
- **Entregável Técnico:** Pacote final completo submetido e defesa oral perante o corpo docente.

---
*Documento elaborado para a Licenciatura em Engenharia Informática — ISPGaya.*
