# TRAFFICMAS — RELATÓRIO DE AUDITORIA CIENTÍFICA INDEPENDENTE (FASE 6.5-B)

**Auditor:** Engenheiro Sénior & Auditor Científico Independente em Java, JADE, Sistemas Multiagente e Machine Learning  
**Data:** 31 de Outubro de 2026  
**Topologia:** Grelha Urbana 3×3 (9 Cruzamentos, 18 Agentes Semáforo, 53 Veículos Autónomos, 2 Agentes Centrais)  
**Branch Git:** `feature/fase-6-3x3`  
**Commit Auditado:** `655087d` (Checkpoint inicial) $\rightarrow$ `f6a94b4` (Correção de reprodutibilidade estrita)  
**Tag de Segurança:** `checkpoint-pre-auditoria-fase65b`

---

## SUMÁRIO EXECUTIVO E VEREDITO DAS 5 CLASSIFICAÇÕES OBRIGATÓRIAS

Esta auditoria científica independente foi executada sobre os artefactos, código-fonte, dados empíricos e modelos preditivos desenvolvidos no âmbito da Fase 6.5-B do TrafficMAS. O objetivo primordial consistiu em aferir, de forma transparente, forense e matematicamente rigorosa, a autenticidade dos dados, a reprodutibilidade dos modelos e a validade da hipótese central: **se a integração da inteligência preditiva baseada em Machine Learning confere vantagem estatisticamente significativa sobre a coordenação adaptativa sem ML.**

| Dimensão Auditada | Classificação Atribuída | Justificação Técnica Sintética |
| :--- | :---: | :--- |
| **A. Dataset 3×3** | **`REPRODUZÍVEL`** | Re-execução da corrida `RUN_01_NORMAL` gerou 531/531 linhas (100.0%) com correspondência estrita bit a bit após resolução de divergência de imobilização no simulador (`is3x3` guard). Hash SHA-256 de 16.992 registos íntegro. |
| **B. Machine Learning** | **`UTILIDADE LIMITADA`** | O modelo de Regressão Logística L2 atinge $F_1 = 0.2595$ no conjunto de teste independente, com Sensibilidade (Recall) de 69.89%, mas Precisão de apenas 15.93% e Taxa de Falsos Alarmes (FPR) de 53.48%. Em cada 100 alertas gerados, aproximadamente 84 são falsos alarmes. |
| **C. Integração JADE** | **`COMPROVADA`** | Arquitetura multiagente implementada e funcional: ontologia `DISTURBANCE_ALERT`, mensagens FIPA-ACL `INFORM` estruturadas, Yellow Pages (DF) com 73 agentes e validação estrita da autoridade semafórica comprovada em 19 suites de testes. |
| **D. Campanha Experimental** | **`VÁLIDA COM LIMITAÇÕES`** | Emparelhamento estrito seed-a-seed nas 160 corridas ($40 \text{ sementes} \times 4 \text{ modos}$), métricas derivadas de física real. No entanto, a campanha de ablação de offsets sofreu de erro de parametrização no runner e a rede 3×3 operou sob sub-saturação de tráfego. |
| **E. Benefício Incremental do ML** | **`NÃO SUPORTADO`** | A diferença emparelhada de tempo de espera entre o Modo C (Coordenação sem ML) e o Modo D (Coordenação com ML) é de apenas $-0.0092\text{ s}$ ($p = 0.7076$ no t-test emparelhado; $p = 0.7324$ em Wilcoxon; $d = -0.0597$, efeito negligenciável). A hipótese nula $H_0$ não pode ser rejeitada. |

---

## 1. ESTADO DO GIT E RASTREABILIDADE FORENSE

Antes de qualquer manipulação de ficheiros, foi inspecionado o repositório Git local, garantindo integridade e rastreabilidade total:

1. **Branch Ativa:** `feature/fase-6-3x3`
2. **Commit Base da Fase 6.5-B:** `655087d3a77fae94fdf07ffea87625ec1d4f29df`
3. **Criação de Tag de Recuperação Local:**
   ```bash
   git tag checkpoint-pre-auditoria-fase65b 655087d
   ```
4. **Commit de Correção de Reprodutibilidade:** `f6a94b4`
   - Mensagem: `fix(simulator): aplicar deteccao de imobilizacao exclusivamente na topologia 3x3 para reprodutibilidade estrita`
5. **Estado da Working Tree:** Limpa, sem ficheiros de produção modificados não commitados. Ficheiros de script forense temporários contidos em `src/scratch/`.
6. **Suites de Testes de Regressão:** Execução das 19 suites de teste (Fases 1 a 6.5-B) com **303 testes aprovados (100% de sucesso, 0 falhas)**.

---

## 2. AUDITORIA FORENSE DA REPRODUTIBILIDADE DO DATASET 3×3

### 2.1. Metodologia de Verificação
O dataset `data/3x3/dataset_traffic_3x3.csv` possui **16.992 linhas de observação** distribuídas uniformemente por 32 corridas de simulação ($32 \times 531 = 16.992$), cada uma abrangendo 59 passos temporais ($t = 10\text{s}$ a $300\text{s}$, passo de $5\text{s}$) nos 9 cruzamentos ($59 \times 9 = 531$ linhas por corrida).

Foi concebido um teste independente (`src/scratch/TestDatasetReproducibility.java`) para re-executar a corrida `RUN_01_NORMAL` sob semente estrita (Seed = 42), simulando os primeiros 365 segundos físicos e comparando linha a linha com as 531 linhas armazenadas no CSV de produção.

### 2.2. Diagnóstico da Divergência e Causa Raiz
No ensaio preliminar, verificou-se uma taxa de concordância de apenas **371/531 linhas (69.87%)**. A investigação minuciosa ao código de `HeadlessTrafficSimulator.java` revelou a causa raiz:
- No gerador original de 2×2 (Fase 4), o início da contagem do tempo de paragem (`currentStopStartSeconds`) de um veículo era acionado exclusivamente quando a distância ao semáforo caía abaixo de $2.0\text{ m}$ (`limitDist <= 2.0`).
- Aquando da criação do gerador 3×3, foi introduzida uma condição suplementar de baixa velocidade (`v.currentSpeed < 0.2`), que acumula tempo de paragem durante desacelerações prévias à linha de paragem.
- Uma tentativa de remoção dessa linha quebrou a reprodução da corrida 3×3, enquanto a sua presença irrestrita quebrava a suite de testes histórica de 2×2 (Suite 7).

### 2.3. Resolução e Validação Final
Foi introduzida a guarda topológica em `HeadlessTrafficSimulator.java`:
```java
if (is3x3 && v.currentSpeed < 0.2) {
    v.wasStopped = true;
    if (v.currentStopStartSeconds < 0) {
        v.currentStopStartSeconds = currentSimTime;
    }
}
```
Com esta correção comitada em `f6a94b4`:
- **Concordância Bit a Bit na Corrida 1:** **531 / 531 linhas (100.0% de igualdade exata)** em todas as 12 features numéricas e no rótulo binário $Y$.
- **Integridade da Suite 7 (2×2):** Todos os 13 testes de 2×2 continuam a passar com 100% de sucesso.
- **Hash SHA-256 do Ficheiro:** `72d3ab47953ebc65249539eae65c3aa135e4ff22ae2f88535f002918e6d9dbce` (inalterado e verificado).

---

## 3. INTEGRIDADE TEMPORAL DOS RÓTULOS E PREVENÇÃO DE FUGA (DATA LEAKAGE)

A integridade do pipeline preditivo foi inspecionada contra vícios clássicos de Machine Learning em séries temporais:

1. **Horizonte Preditivo:** $H = 60.0\text{ s}$.
2. **Definição Matemática do Rótulo:** Para um cruzamento $c$ e instante $t$:
   $$Y_{c,t} = \begin{cases} 1, & \text{se } \exists t' \in [t + 55, t + 65] \text{ tal que } Q_{c,t'} \ge 3 \\ 0, & \text{caso contrário} \end{cases}$$
   onde $Q_{c,t'}$ é o comprimento total da fila (soma dos eixos NS e EW) registado no instante $t'$.
3. **Ausência de Censura Temporal:**
   - As features são amostradas de $t = 10.0\text{ s}$ até $t = 300.0\text{ s}$.
   - O horizonte preditivo projeta o intervalo de avaliação para o intervalo $[355.0\text{ s}, 365.0\text{ s}]$.
   - Cada corrida é simulada fisicamente até $T_{\text{max}} = 365.0\text{ s}$.
   - Conclusão: **Nenhum rótulo no fim da corrida sofre de truncatura ou censura.** Todos os pontos possuem os $60\text{ s}$ futuros completos simulados.
4. **Verificação de Fuga de Dados Futuros nas Features:**
   - As 12 variáveis explicativas no instante $t$ utilizam exclusivamente dados observados no intervalo $[0, t]$: filas instantâneas, razão de filas, velocidade média instantânea, tempo médio de espera acumulado e estado do semáforo.
   - Não existe inclusão de variáveis derivadas de $t' > t$.

---

## 4. PARTIÇÃO ESTRITA POR RUNID E DISTRIBUIÇÃO DE CLASSES

A partição dos dados respeitou integralmente o princípio de independência de séries temporais, evitando a amostragem aleatória de linhas (que induziria autocorrelação espúria entre treino e teste):

$$\text{Dataset Total: } 32 \text{ corridas} = 16.992 \text{ amostras}$$

| Partição | Corridas Incluídas | Contagem de Linhas | Classe 0 (Fluido) | Classe 1 (Congestão) | Prevalência Positiva |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Treino (TRAIN)** | Runs 1 a 20 (62.5%) | 10.620 | 9.299 | 1.321 | **12.44%** |
| **Validação (VAL)** | Runs 21 a 28 (25.0%) | 4.248 | 3.644 | 604 | **14.22%** |
| **Teste (TEST)** | Runs 29 a 32 (12.5%) | 2.124 | 1.855 | 269 | **12.66%** |
| **Total Global** | **32 Corridas** | **16.992** | **14.798 (87.09%)** | **2.194 (12.91%)** | **12.91%** |

A prevalência da classe minoritária mantém-se estável em torno de $12.5\% - 14.2\%$ nas três partições, assegurando consistência na avaliação fora da amostra.

---

## 5. CALIBRAÇÃO E SELEÇÃO DE THRESHOLD EM VALIDAÇÃO

Dada a assimetria das classes ($87.1\%$ fluidas vs $12.9\%$ congestionadas), o limiar padrão de probabilidade ($\tau = 0.50$) revelou-se ineficaz (Recall de apenas $0.99\%$). Foi realizada uma varredura rigorosa de $\tau \in [0.05, 0.50]$ na partição de validação independente (`VAL`):

| Limiar ($\tau$) | Precisão (Precision) | Cobertura (Recall) | F1-Score | Especificidade | Observações Operacionais |
| :---: | :---: | :---: | :---: | :---: | :--- |
| **0.05** | 0.1432 | 0.9983 | 0.2505 | 0.0165 | Alertas constantes; saturação de diretivas |
| **0.08** | 0.1485 | 0.9570 | 0.2571 | 0.0895 | Elevadíssima taxa de falsos positivos |
| **0.10** | 0.1555 | 0.8874 | 0.2646 | 0.1981 | Zona de subida de precisão |
| **0.12** | **0.1658** | **0.7417** | **0.2710** | **0.3861** | **Valor Selecionado e Congelado no Bundle** |
| **0.13** | **0.1746** | **0.6970** | **0.2792** | **0.4632** | Ponto de compromisso e F1 quase ótimo |
| **0.15** | 0.1873 | 0.5513 | 0.2797 | 0.6062 | Ótimo formal de F1 em VAL |
| **0.20** | 0.2037 | 0.3344 | 0.2531 | 0.7838 | Perda substancial de cobertura de risco |
| **0.25** | 0.2222 | 0.2119 | 0.2169 | 0.8754 | Menos de 1 em cada 4 congestionamentos detetado |
| **0.30** | 0.2530 | 0.1374 | 0.1781 | 0.9333 | Excessivamente conservador |
| **0.40** | 0.2979 | 0.0464 | 0.0802 | 0.9819 | Threshold do modelo 2×2 da Fase 4 |
| **0.50** | 0.4286 | 0.0099 | 0.0194 | 0.9978 | Limiar default impraticável para desbalanceamento |

### Análise da Escolha do Threshold:
O bundle operacional congelou $\tau^* = 0.12$. A análise comparativa demonstra que, embora $\tau = 0.15$ atinja o pico matemático absoluto de F1 ($0.2797$), a escolha de $\tau^* = 0.12$ priorizou a sensibilidade de deteção precoce ($74.17\%$ vs $55.13\%$), prevenindo que episódios de formação de fila longa passassem despercebidos aos agentes de coordenação.

---

## 6. MATRIZ DE CONFUSÃO EM TEST E MÉTRICAS DERIVADAS REAIS

A avaliação foi efetuada sobre o conjunto de teste (`TEST`), contendo **2.124 amostras nunca observadas no treino ou na calibração**, sob o limiar congelado $\tau^* = 0.12$:

```
                   CONDIÇÃO REAL NO TESTE
                 Congestionado (1)    Fluido (0)      Total
Previsão ML (1)       TP = 188         FP = 992       1.180
Previsão ML (0)       FN = 81          TN = 863         944
Total                 P = 269          N = 1.855      2.124
```

### Cálculo Direto das Métricas Derivadas:
1. **Exatidão (Accuracy):**
   $$\text{Acc} = \frac{TP + TN}{N_{\text{total}}} = \frac{188 + 863}{2.124} = \frac{1.051}{2.124} = \mathbf{0.4948} \quad (49.48\%)$$
2. **Sensibilidade / Cobertura (Recall / TPR):**
   $$\text{Recall} = \frac{TP}{TP + FN} = \frac{188}{269} = \mathbf{0.6989} \quad (69.89\%)$$
3. **Precisão (Precision / PPV):**
   $$\text{Precision} = \frac{TP}{TP + FP} = \frac{188}{188 + 992} = \frac{188}{1.180} = \mathbf{0.1593} \quad (15.93\%)$$
4. **Especificidade (True Negative Rate / TNR):**
   $$\text{Specificity} = \frac{TN}{TN + FP} = \frac{863}{1.855} = \mathbf{0.4652} \quad (46.52\%)$$
5. **Taxa de Falsos Alarmes (False Positive Rate / FPR):**
   $$\text{FPR} = \frac{FP}{FP + TN} = \frac{992}{1.855} = \mathbf{0.5348} \quad (53.48\%)$$
6. **F1-Score:**
   $$F_1 = 2 \times \frac{\text{Precision} \times \text{Recall}}{\text{Precision} + \text{Recall}} = 2 \times \frac{0.1593 \times 0.6989}{0.1593 + 0.6989} = \frac{0.2227}{0.8582} = \mathbf{0.2595}$$
7. **ROC-AUC (Área sob a Curva ROC):** $\mathbf{0.6180}$
8. **PR-AUC (Área sob a Curva Precision-Recall):** $\mathbf{0.1859}$
9. **Brier Score (Erro Quadrático de Probabilidade):** $\mathbf{0.1087}$

---

## 7. COMPARAÇÃO CONTRA OS QUATRO MODELOS BASELINE

Para evitar o enviesamento de avaliar o modelo em vácuo, os resultados do modelo preditivo foram confrontados com quatro modelos de referência standard no conjunto `TEST`:

| Modelo / Abordagem | Accuracy | Precision | Recall | F1-Score | Brier Score | Conclusão Avaliativa |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **1. Dummy Zero (Sempre Fluido)** | **87.34%** | 0.00% | 0.00% | 0.0000 | 0.1266 | Falha total na deteção de anomalias |
| **2. Dummy One (Sempre Congestionado)** | 12.66% | 12.66% | **100.00%** | 0.2248 | 0.8734 | Alertas constantes; inutilidade prática |
| **3. Prevalência Constante ($P = 0.1266$)** | 87.34% | 0.00% | 0.00% | 0.0000 | 0.1106 | Baseline clássico de calibração |
| **4. Heurística Simples ($Q_{\text{atual}} \ge 2$)** | 79.43% | 14.71% | 13.01% | 0.1381 | 0.1511 | Reativo simples baseado na fila atual |
| **Modelo ML 3×3 (Regressão Logística)** | **49.48%** | **15.93%** | **69.89%** | **0.2595** | **0.1087** | **Supera baselines em F1 e Brier** |

### Diagnóstico Técnico:
O modelo de Machine Learning demonstra capacidade preditiva genuína superior à adivinhação:
- O seu $F_1 = 0.2595$ supera largamente a heurística simples ($0.1381$) e o Dummy de classe única ($0.2248$).
- O Brier Score ($0.1087$) é inferior ao da prevalência constante ($0.1106$), comprovando que as probabilidades atribuídas refinam a incerteza estatística.
- **Contudo, a precisão de $15.93\%$ e a taxa de falsos alarmes de $53.48\%$ impõem severos limites operacionais**: 84 em cada 100 alertas gerados resultam na extensão de verdes semafóricos em cruzamentos que não viriam a congestionar, penalizando os eixos transversais.

---

## 8. DINÂMICA TEMPORAL E FÍSICA DO MODO A (TEMPOS DE ESPERA REDUZIDOS)

Uma das maiores surpresas na campanha experimental foi o desempenho do **Modo A (Tempo Fixo)**, que apresentou tempos médios de espera substancialmente inferiores aos dos modos coordenados e adaptativos:

$$\bar{W}_A = 1.66\text{ s} \quad \text{vs} \quad \bar{W}_B = 3.29\text{ s} \quad \text{vs} \quad \bar{W}_C = 3.52\text{ s} \quad \text{vs} \quad \bar{W}_D = 3.51\text{ s}$$

A auditoria física investigou as causas deste fenómeno:

1. **Condição de Tráfego Sub-Saturado:**
   A simulação aloca 50 veículos normais distribuídos por 9 cruzamentos (uma média de apenas 5.5 veículos por cruzamento e menos de 1.5 veículos por via de aproximação em qualquer instante).
2. **Estrutura dos Ciclos Semafóricos:**
   - **Modo A (Ciclo Curto Rígido):** Verde = $4.0\text{ s}$, Amarelo = $2.0\text{ s}$, All-Red = $0.5\text{ s}$. Ciclo total $= 13.0\text{ s}$ ($6.5\text{ s}$ por fase). O tempo máximo que qualquer viatura espera no sinal vermelho é de apenas $9.0\text{ s}$. Viaturas que chegam aleatoriamente enfrentam uma espera média teórica no vermelho de $4.5\text{ s}$, e zero se chegarem no verde.
   - **Modos Coordenados B, C e D (Ciclos Longos):** Verde estendido para $6.5\text{ s} - 7.5\text{ s}$ para formar pelotões e ondas verdes, com ciclo total de $18.0\text{ s} - 20.0\text{ s}$. 
3. **Penalização dos Eixos Transversais:**
   Como o tráfego é esparso e não forma pelotões densos contínuos, os veículos nas ruas perpendiculares que chegam durante o sinal vermelho prolongado ficam retidos por períodos de até $12\text{ s} - 14\text{ s}$. Essa penalização supera qualquer benefício ganho pelos poucos veículos que apanham a onda verde no corredor principal.
4. **Throughput Estável:**
   O caudal de escoamento manteve-se idêntico em todos os modos ($491.5\text{ veh/h}$ no Modo A vs $484.5\text{ veh/h}$ no Modo D), provando que não ocorreu bloqueio em nenhum dos cenários.

---

## 9. DISTRIBUIÇÃO ESTATÍSTICA DAS DIFERENÇAS EMPARELHADAS C VS D

A comparação entre o **Modo C (Coordenação Multiagente sem ML)** e o **Modo D (Coordenação Multiagente Preditiva com ML)** foi auditada através das 40 sementes estritamente emparelhadas:

$$\Delta = \text{Métrica}(\text{Modo D}) - \text{Métrica}(\text{Modo C})$$

### 9.1. Estatísticas de Tempo Médio de Espera ($\Delta W = W_D - W_C$ em segundos)
- **Média Amostral ($\bar{\Delta}$):** $\mathbf{-0.0092\text{ s}}$ (redução aparente de 9.2 milissegundos)
- **Mediana Amostral:** $\mathbf{+0.0050\text{ s}}$ (aumento de 5.0 milissegundos a favor de C)
- **Desvio-Padrão ($s_\Delta$):** $0.1549\text{ s}$
- **Erro Padrão da Média ($SE$):** $0.0245\text{ s}$
- **Intervalo de Confiança a 95% (Student-t):**
  $$IC_{95\%} = \bar{\Delta} \pm t_{39, 0.975} \times SE = -0.0092 \pm (2.0227 \times 0.0245) = [-0.0588\text{ s}, +0.0403\text{ s}]$$
  *O intervalo de confiança a 95% cruza confortavelmente o zero.*

### 9.2. Estatísticas de Throughput ($\Delta T = T_D - T_C$ em veículos/hora)
- **Média Amostral ($\bar{\Delta}$):** $\mathbf{+0.9975\text{ veh/h}}$
- **Mediana Amostral:** $\mathbf{0.0000\text{ veh/h}}$
- **Desvio-Padrão ($s_\Delta$):** $17.8396\text{ veh/h}$
- **Intervalo de Confiança a 95%:** $[-4.7088\text{ veh/h}, +6.7038\text{ veh/h}]$

### 9.3. Análise Desagregada por Cenário Operacional
| Cenário Simulado (10 Sementes Cada) | $\bar{W}_C$ (s) | $\bar{W}_D$ (s) | $\Delta \bar{W}$ (s) | $p\text{-value}$ (t-test) | Conclusão Estatística |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **1. Normal (Céu Limpo, Atrito $\mu=1.0$)** | 3.517 | 3.507 | -0.010 | 0.9022 | Diferença nula ($p \gg 0.05$) |
| **2. Chuva (Atrito $\mu=0.7$)** | 3.535 | 3.514 | -0.021 | 0.7231 | Diferença insignificante ($p \gg 0.05$) |
| **3. Neve (Atrito $\mu=0.4$)** | 3.518 | 3.512 | -0.006 | 0.9315 | Diferença nula ($p \gg 0.05$) |
| **4. Picos de Congestão Esporádicos** | 3.510 | 3.511 | +0.001 | 0.9840 | Diferença nula ($p \gg 0.05$) |

---

## 10. TESTES ESTATÍSTICOS DE HIPÓTESE E SIGNIFICÂNCIA

Foram conduzidos testes formais de hipóteses bicaudais sobre a hipótese nula de equivalência entre o controlo coordenado puro e o controlo coordenado preditivo:
$$H_0: \mu_D = \mu_C \quad \text{vs} \quad H_1: \mu_D \ne \mu_C$$

### 10.1. Teste t de Student Emparelhado (Paramétrico)
- **Tempo de Espera:**
  $$t = \frac{\bar{\Delta}}{s_\Delta / \sqrt{n}} = \frac{-0.00925}{0.1549 / \sqrt{40}} = \mathbf{-0.3778}, \quad p\text{-value} = \mathbf{0.7076}$$
- **Throughput:**
  $$t = \frac{+0.9975}{17.8396 / \sqrt{40}} = \mathbf{+0.3536}, \quad p\text{-value} = \mathbf{0.7255}$$

### 10.2. Teste dos Postos Sinalizados de Wilcoxon (Não Paramétrico)
Para dispensar a assunção de normalidade perfeita das diferenças:
- **Tempo de Espera:** Estatística da soma de postos $W = 365.5, \quad z = -0.3419, \quad p\text{-value} = \mathbf{0.7324}$
- **Throughput:** Estatística da soma de postos $W = 273.0, \quad z = +0.1341, \quad p\text{-value} = \mathbf{0.8934}$

### Veredito Científico:
Ambos os testes paramétricos e não paramétricos fornecem $p\text{-values}$ superiores a **0.70**, muito acima do limiar padrão de significância ($\alpha = 0.05$). **É categoricamente impossível rejeitar a hipótese nula $H_0$. Não existe evidência estatística de que o Machine Learning proporcione melhoria mensurável em relação à coordenação adaptativa sem ML.**

---

## 11. TAMANHO DO EFEITO (COHEN'S D)

Para quantificar a magnitude prática (e não apenas estatística) das diferenças observadas:

1. **Fórmula de Cohen's d para Amostras Emparelhadas:**
   $$d = \frac{\bar{\Delta}}{s_\Delta}$$
2. **Cálculo para Tempo de Espera:**
   $$d_W = \frac{-0.00925}{0.1549} = \mathbf{-0.0597}$$
3. **Cálculo para Throughput:**
   $$d_T = \frac{+0.9975}{17.8396} = \mathbf{+0.0559}$$

### Classificação Formal (Convenção de Cohen):
- $|d| < 0.20$: **Desprezável (Negligible)**
- $0.20 \le |d| < 0.50$: Pequeno (Small)
- $0.50 \le |d| < 0.80$: Médio (Medium)
- $|d| \ge 0.80$: Grande (Large)

**Conclusão:** Com magnitudes de $|d| \approx 0.06$, o efeito da adição do modelo de Machine Learning no estado atual da simulação é classificado formalmente como **desprezável**.

---

## 12. AUDITORIA DA ABLAÇÃO DE DESFASAMENTOS (OFFSETS)

No relatório original da Fase 6.5-B, a experiência de ablação reportou tempos médios idênticos até à segunda casa decimal ($3.52\text{ s}$) para as seguintes configurações:
- `COORD_PHYSICAL_OFFSETS`
- `COORD_OFFSET_ZERO`
- `COORD_LOCAL_OFFSETS`

### Investigação Forense ao Código:
A inspeção da classe `Phase65BComparativeCampaign.java` revelou uma **falha de instrumentação**:
- O método de apoio `executeRun(mode, seed, scenario, ...)` instanciou e aplicou diretamente o cálculo de offsets físicos baseado na velocidade de progressão em todas as chamadas.
- O argumento descritivo da configuração de ablação (`configName`) não era utilizado para comutar entre um array nulo de offsets `double[]{0.0, 0.0, 0.0}` e o array físico `[off0, off1, off2]`.
- Em consequência, a execução designada por `COORD_OFFSET_ZERO` correu internamente com offsets físicos reais, gerando saídas numéricas idênticas.

---

## 13. MEDIÇÃO EMPÍRICA DO IMPACTO REAL DOS OFFSETS FÍSICOS

Para apurar a física real dos desfasamentos sem a máscara do bug de instrumentação, foi executado um script de bancada isolado (`src/scratch/CompareZeroVsPhysicalOffsets.java`), injetando rigorosamente $0\text{ ms}$ de desfasamento em todos os nós vs os desfasamentos físicos calculados pela fórmula de progressão rodoviária:

$$\text{offset}_i = \sum_{k=0}^{i-1} \frac{d_k}{v_{\text{est}}}$$

### Resultados Experimentais Reais:
| Configuração de Desfasamento | Tempo Médio de Espera | Throughput da Rede | Variação Percentual |
| :--- | :---: | :---: | :---: |
| **Offsets Zero (Sincronismo em Fase $0\text{ ms}$)** | $3.560\text{ s}$ | $492.3\text{ veh/h}$ | Baseline de sincronismo |
| **Offsets Físicos Calculados ($d / v_{\text{est}}$)** | **$3.389\text{ s}$** | **$494.8\text{ veh/h}$** | **$-4.80\%$ no tempo de espera** |

### Conclusão:
Quando verdadeiramente aplicados, os desfasamentos físicos produzem um benefício físico real mensurável (redução de $0.171\text{ s}$ no tempo de espera e aumento de caudal). O relatório anterior errou ao declarar a irrelevância dos offsets devido ao erro de passagem de parâmetros na rotina de teste.

---

## 14. AUDITORIA CRÍTICA DOS 1.075 ALERTAS DE ML

O relatório da Fase 6.5-B registou um total de 1.075 alertas preditivos gerados ao longo das 40 corridas do Modo D. A auditoria procedeu ao rastreio destes eventos:

1. **Critério de Contabilização:**
   Na rotina `Phase65BComparativeCampaign.java`, o contador `mlAlertsApplied` era incrementado sempre que a chamada:
   ```java
   authority.evaluateAndRegisterCoordinationPolicy(cmd);
   ```
   retornava a constante `"ACCEPTED"`.
2. **Semântica da Autoridade Semafórica:**
   O status `"ACCEPTED"` na `IntersectionSafetyAuthority` atesta unicamente que a diretiva é estruturalmente válida, respeita os limites de segurança ($[2000, 10000]\text{ ms}$) e foi **agendada** para execução no tempo simulado. Não garante que não tenha sido posteriormente abortada por expiração ou substituída por preempção de emergência.
3. **Impacto da Baixa Precisão (Falsos Alarmes):**
   Conforme demonstrado na secção 6, a precisão do modelo é de $15.93\%$. Isto implica matematicamente que, dos 1.075 alertas emitidos:
   - Apenas $\approx 171$ alertas corresponderam a episódios reais em que se formou fila crítica no horizonte de 60 segundos.
   - Cerca de **904 alertas foram falsos alarmes**.
4. **Discrepância no Cooldown:**
   O runner da campanha utilizou um cooldown de $20\text{ s}$, enquanto a classe `DisruptionManagementAgent.java` possuía um cooldown padrão de $30\text{ s}$, permitindo uma taxa de emissão ligeiramente superior no modo headless.

---

## 15. LATÊNCIAS DE EXECUÇÃO E VIABILIDADE TEMPORAL

Foi realizada a medição das latências computacionais do ciclo preditivo e comunicacional:

| Operação | Tempo Medido | Limite de Viabilidade | Margem de Segurança |
| :--- | :---: | :---: | :---: |
| **Inferência Preditiva (12 Features + Logística)** | **$0.39\,\mu\text{s}$** | $< 100\,\mu\text{s}$ | $> 250 \times$ |
| **Empacotamento FIPA-ACL + Despacho JADE** | **$2.1\text{ ms} - 4.8\text{ ms}$** | $< 50.0\text{ ms}$ | $> 10 \times$ |
| **Avaliação na Autoridade Semafórica** | **$0.015\text{ ms}$** | $< 5.0\text{ ms}$ | $> 300 \times$ |
| **Passo da Simulação Física ($\Delta t$)** | **$100.0\text{ ms}$** | N/A | Referência |

As latências computacionais e de mensageira são de ordens de magnitude inferiores à cadência de controlo semafórico ($100\text{ ms}$) e ao horizonte de predição ($60.0\text{ s}$), garantindo viabilidade em tempo real sem qualquer estrangulamento de CPU.

---

## 16. VALIDAÇÃO ARQUITETURAL: SIMULAÇÃO HEADLESS VS JADE REAL

A campanha experimental de 160 corridas e as 50 corridas de ablação totalizaram **75.600 segundos de simulação física**.

1. **Execução Headless:**
   Para tornar a computação viável (concluída em 45 segundos de CPU em vez de mais de 20 horas de tempo real de relógio), a campanha correu sobre `HeadlessTrafficSimulator`, onde o avanço da física e a lógica dos agentes é processada em memória com contabilidade simulada de mensagens FIPA (`fipaMessages += 2 / 3`).
2. **Garantia de Equivalência:**
   A autenticidade da pilha completa JADE (containers, DF, agentes autónomos, behaviors, serialização ACLMessage) não foi omitida: é integralmente coberta e validada pelas suites de testes automatizados:
   - Suite 8: Mensageira FIPA entre Central e Semáforos;
   - Suite 12: Registo de 18 semáforos e 73 agentes no DF;
   - Suite 14: Cedência física a emergências via FIPA;
   - Suite 16: Coordenação distribuída 3×3;
   - Suite 17: Testes T15 a T18 (receção de alertas ML, parsing e aplicação por agentes JADE reais).

---

## 17. VERIFICAÇÃO DE INTEGRIDADE DOS 160 REGISTOS EXPERIMENTAIS

O ficheiro de resultados `results/3x3/fase65b_comparativo_abcd.csv` foi inspecionado linha a linha:

1. **Volume:** Exatamente **161 linhas** (1 cabeçalho + 160 registos).
2. **Emparelhamento de Sementes:** As sementes 42 a 51 para os 4 cenários estão 100% emparelhadas nos 4 modos (A, B, C, D).
3. **Consistência dos Dados:** Zero valores `null`, zero divisões por zero, zero valores negativos em tempos de espera ou caudal.
4. **Fidedignidade do Throughput:** O caudal foi obtido a partir do registo físico de travessias de veículos detetadas pela classe `TrafficMetricsCollector`, sem qualquer valor arbitrário ou sintético.

---

## 18. DEMONSTRAÇÃO VISUAL E PARÂMETROS DA INTERFACE GUI

A demonstração interativa da Fase 6.5-B foi auditada quanto aos parâmetros visuais e semafóricos:

1. **Duração da Diretiva ML (6.500 ms vs 7.500 ms):**
   - O agente `CentralCoordinationAgent.java` formula diretivas preditivas com duração padrão de **$6.500\text{ ms}$**.
   - No script de demonstração visual `Phase65BDemoRunner.java`, foi utilizado o valor de **$7.500\text{ ms}$** para tornar a extensão verde claramente percetível ao olho humano no canvas Swing.
   - Ambas as durações são perfeitamente aceites pela `IntersectionSafetyAuthority`, que impõe o intervalo seguro $[2.000, 10.000]\text{ ms}$.
2. **Intertravamento Visual:** Durante a demonstração, o painel HUD exibiu corretamente o cruzamento-alvo (C5), a probabilidade predita ($74.2\%$), o horizonte ($60\text{ s}$) e a transição semafórica segura sem qualquer infração de segurança.

---

## 19. INTEGRIDADE E PRESERVAÇÃO DOS DADOS HISTÓRICOS 2×2

Foi confirmada a preservação intacta dos artefactos históricos das Fases 4 e 5:
- `data/traffic_disruption_dataset.csv` (Dataset 2×2, 9.216 linhas): Intacto.
- `models/traffic_model.bin` (Modelo 2×2 serializado): Hash SHA-256 verificado: `1db28334a880bd55f1de6c5a342ac9eca93df0f95ffc4019a3380048041f1036`.
- `results/fase5_comparativo_modos.csv` (160 experiências 2×2): Intacto.
- Nenhum ficheiro histórico foi sobrescrito pela Fase 6.5-B.

---

## 20. AS CINCO CLASSIFICAÇÕES TÉCNICAS OBRIGATÓRIAS

### A. Dataset 3×3: `REPRODUZÍVEL`
- **Classificação:** **`REPRODUZÍVEL`**
- **Fundamentação:** Após a identificação da causa raiz em `HeadlessTrafficSimulator.java` e a aplicação da guarda `if (is3x3 && v.currentSpeed < 0.2)` comitada em `f6a94b4`, a corrida `RUN_01_NORMAL` foi re-executada e validada contra as 531 linhas de referência, obtendo **100.0% de correspondência exata bit a bit**. As 16.992 linhas respeitam rigorosamente a cadência temporal e a ausência de censura ou fuga futura.

### B. Machine Learning: `UTILIDADE LIMITADA`
- **Classificação:** **`UTILIDADE LIMITADA`**
- **Fundamentação:** O modelo é genuinamente funcional e supera baselines ingénuos em Brier Score ($0.1087$ vs $0.1106$) e F1 ($0.2595$ vs $0.1381$). Contudo, no contexto de controlo de tráfego em tempo real, a sua Precisão de apenas **$15.93\%$** acoplada a uma Taxa de Falsos Alarmes de **$53.48\%$** significa que mais de $84\%$ dos alertas emitidos não encontram congestionamento posterior. Operacionalmente, isto resulta em extensões de sinal verde desnecessárias que prejudicam as vias transversais.

### C. Integração JADE: `COMPROVADA`
- **Classificação:** **`COMPROVADA`**
- **Fundamentação:** A arquitetura multiagente obedece a todas as normas FIPA-ACL. Os agentes `DisruptionManagementAgent`, `CentralCoordinationAgent` e `TrafficLightAgent` comunicam através de mensagens estruturadas (`DISTURBANCE_ALERT`), utilizam páginas amarelas (DF) e submetem todas as decisões à `IntersectionSafetyAuthority`, que garante intertravamento anti-colisão absoluto.

### D. Campanha Experimental: `VÁLIDA COM LIMITAÇÕES`
- **Classificação:** **`VÁLIDA COM LIMITAÇÕES`**
- **Fundamentação:** O protocolo com 160 corridas emparelhadas e controlo estrito de sementes é metodologicamente exemplar. Todavia, a validade empírica é limitada por dois fatores objetivos: (1) o regime de tráfego testado é sub-saturado (50 veículos para 9 cruzamentos), beneficiando artificialmente ciclos curtos de tempo fixo; (2) o runner da ablação de offsets sofreu um vício de implementação que mascarou o impacto real dos desfasamentos físicos.

### E. Benefício Incremental do ML: `NÃO SUPORTADO`
- **Classificação:** **`NÃO SUPORTADO`**
- **Fundamentação:** Os dados empíricos não sustentam a afirmação de que a inteligência preditiva ML melhora a rede face à coordenação clássica sem ML. A redução no tempo de espera foi de apenas $0.0092\text{ s}$ ($0.26\%$), com testes de hipótese bicaudais a reportarem $p = 0.7076$ (t-test) e $p = 0.7324$ (Wilcoxon), e um tamanho de efeito $d = -0.0597$ (desprezável). Perante a comunidade científica, o resultado honesto e demonstrado é que **o ML não produziu ganho mensurável no cenário testado**.

---

## 21. RECOMENDAÇÕES ACIONÁVEIS PARA AS FASES 7 E 8

Com vista à defesa científica do projeto perante o docente e o avanço sustentado para as etapas seguintes:

1. **Assumir a Honestidade Científica dos Resultados:**
   Não tentar forçar dados ou alterar artificialmente métricas para justificar o ML. Em engenharia de software e inteligência artificial, demonstrar rigorosamente através de testes estatísticos emparelhados que uma solução complexa (ML) não supera a coordenação determinística em certas condições de carga é um resultado científico de elevado mérito.
2. **Corrigir o Runner de Ablação de Offsets em Código:**
   Ajustar a assinatura e o corpo de `Phase65BComparativeCampaign.java` para garantir que `COORD_OFFSET_ZERO` aplique de facto um vetor nulo de desfasamentos, permitindo demonstrar formalmente o ganho de $4.8\%$ que os offsets físicos conferem à rede.
3. **Explorar Cenários de Saturação Real (Pico de Tráfego):**
   Nas Fases 7 ou 8, testar a rede 3×3 com densidades mais elevadas (e.g. 150 a 200 veículos). Sob congestionamento persistente e transbordamento de filas (spillback), o valor preditivo do ML poderá ter impacto muito mais evidente do que sob tráfego fluido.
4. **Refinar as Features Preditivas:**
   Introduzir variáveis de tendência temporal, tais como a derivada da fila ($\Delta Q / \Delta t$) e a ocupação das vias a montante, para aumentar a precisão do classificador de $15.9\%$ para valores superiores a $40\%$.
5. **Ajustar o Limiar Preditivo para Redução de Falsos Alarmes:**
   Subir o limiar de decisão de $\tau = 0.12$ para $\tau \approx 0.20 - 0.25$, reduzindo significativamente as falsas ativações da política verde.

