import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

# Estilo gráfico elegante para artigo científico (estilo IEEE/ACM)
plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
plt.rcParams['font.sans-serif'] = 'Arial'
plt.rcParams['font.family'] = 'sans-serif'
plt.rcParams['figure.dpi'] = 300
plt.rcParams['axes.titlesize'] = 14
plt.rcParams['axes.labelsize'] = 12
plt.rcParams['xtick.labelsize'] = 11
plt.rcParams['ytick.labelsize'] = 11
plt.rcParams['legend.fontsize'] = 10

OUT_DIR = os.path.join("results", "experiments", "final", "plots")
os.makedirs(OUT_DIR, exist_ok=True)

# 1. Carregar dados
csv_mode = os.path.join("results", "experiments", "final", "mode_comparison.csv")
csv_scenario = os.path.join("results", "experiments", "final", "scenario_summary.csv")
csv_raw = os.path.join("results", "experiments", "final", "raw_runs.csv")
csv_stat = os.path.join("results", "experiments", "final", "statistical_analysis.csv")

df_mode = pd.read_csv(csv_mode)
df_scenario = pd.read_csv(csv_scenario)
df_raw = pd.read_csv(csv_raw)
df_stat = pd.read_csv(csv_stat)

mode_labels = {
    'FIXED_TIME': 'Modo A\n(Fixed-Time)',
    'LOCAL_ADAPTIVE': 'Modo B\n(Local Adaptive)',
    'MULTIAGENT_COORDINATED': 'Modo C\n(Multi-Agent Coord)',
    'MULTIAGENT_ML': 'Modo D\n(Coord + ML)'
}
colors = ['#4A5568', '#3182CE', '#38A169', '#805AD5']

# -------------------------------------------------------------
# Gráfico 1: Espera Média por Modo com IC 95%
# -------------------------------------------------------------
fig, ax = plt.subplots(figsize=(8, 5))
x = np.arange(len(df_mode))
bars = ax.bar(x, df_mode['meanWaitTime'], color=colors, width=0.55, edgecolor='black', linewidth=0.8, zorder=3)
err_low = df_mode['meanWaitTime'] - df_mode['ci95WaitTimeLower']
err_high = df_mode['ci95WaitTimeUpper'] - df_mode['meanWaitTime']
ax.errorbar(x, df_mode['meanWaitTime'], yerr=[err_low, err_high], fmt='none', ecolor='black', capsize=5, capthick=1.2, zorder=4)

ax.set_xticks(x)
ax.set_xticklabels([mode_labels.get(m, m) for m in df_mode['mode']])
ax.set_ylabel('Tempo Médio de Espera (segundos)')
ax.set_title('Tempo Médio de Espera por Modo de Controlo (160 Corridas — IC 95%)', pad=15, fontweight='bold')
ax.set_ylim(0, max(df_mode['ci95WaitTimeUpper']) * 1.3)
ax.grid(axis='y', linestyle='--', alpha=0.7, zorder=0)

for bar in bars:
    yval = bar.get_height()
    ax.text(bar.get_x() + bar.get_width()/2.0, yval + 0.02, f'{yval:.2f}s', ha='center', va='bottom', fontsize=11, fontweight='bold')

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "waiting_time_by_mode.png"))
plt.close(fig)

# -------------------------------------------------------------
# Gráfico 2: Throughput Médio por Modo com IC 95%
# -------------------------------------------------------------
fig, ax = plt.subplots(figsize=(8, 5))
bars = ax.bar(x, df_mode['meanThroughput'], color=colors, width=0.55, edgecolor='black', linewidth=0.8, zorder=3)
err_low_th = df_mode['meanThroughput'] - df_mode['ci95ThroughputLower']
err_high_th = df_mode['ci95ThroughputUpper'] - df_mode['meanThroughput']
ax.errorbar(x, df_mode['meanThroughput'], yerr=[err_low_th, err_high_th], fmt='none', ecolor='black', capsize=5, capthick=1.2, zorder=4)

ax.set_xticks(x)
ax.set_xticklabels([mode_labels.get(m, m) for m in df_mode['mode']])
ax.set_ylabel('Débito de Tráfego (veículos / minuto)')
ax.set_title('Throughput Global por Modo de Controlo (Janela Ativa de 540s)', pad=15, fontweight='bold')
ax.set_ylim(0, 60)
ax.grid(axis='y', linestyle='--', alpha=0.7, zorder=0)

for bar in bars:
    yval = bar.get_height()
    ax.text(bar.get_x() + bar.get_width()/2.0, yval + 1.2, f'{yval:.1f} v/min', ha='center', va='bottom', fontsize=11, fontweight='bold')

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "throughput_by_mode.png"))
plt.close(fig)

# -------------------------------------------------------------
# Gráfico 3: Comprimento Médio da Fila por Modo
# -------------------------------------------------------------
fig, ax = plt.subplots(figsize=(8, 5))
bars = ax.bar(x, df_mode['meanQueueLength'], color=colors, width=0.55, edgecolor='black', linewidth=0.8, zorder=3)
ax.errorbar(x, df_mode['meanQueueLength'], yerr=df_mode['stdQueueLength']/np.sqrt(df_mode['replications'])*1.96,
            fmt='none', ecolor='black', capsize=5, capthick=1.2, zorder=4)

ax.set_xticks(x)
ax.set_xticklabels([mode_labels.get(m, m) for m in df_mode['mode']])
ax.set_ylabel('Comprimento Médio da Fila (veículos)')
ax.set_title('Comprimento Médio de Fila por Cruzamento e Modo de Controlo', pad=15, fontweight='bold')
ax.set_ylim(0, 0.6)
ax.grid(axis='y', linestyle='--', alpha=0.7, zorder=0)

for bar in bars:
    yval = bar.get_height()
    ax.text(bar.get_x() + bar.get_width()/2.0, yval + 0.02, f'{yval:.2f} veíc', ha='center', va='bottom', fontsize=11, fontweight='bold')

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "queue_length_by_mode.png"))
plt.close(fig)

# -------------------------------------------------------------
# Gráfico 4: Tempo de Resposta a Emergências (Cenário DISRUPTION_EMERGENCY)
# -------------------------------------------------------------
df_emerg = df_scenario[df_scenario['scenario'] == 'DISRUPTION_EMERGENCY']
fig, ax = plt.subplots(figsize=(8, 5))
x_em = np.arange(len(df_emerg))
bars = ax.bar(x_em, df_emerg['meanEmergencyGreenDelay'], color=colors, width=0.55, edgecolor='black', linewidth=0.8, zorder=3)

ax.set_xticks(x_em)
ax.set_xticklabels([mode_labels.get(m, m) for m in df_emerg['mode']])
ax.set_ylabel('Tempo até Verde de Socorro (segundos)')
ax.set_title('Latência de Atendimento a Emergências (60 Ambulâncias Injetadas)', pad=15, fontweight='bold')
ax.set_ylim(0, 2.5)
ax.grid(axis='y', linestyle='--', alpha=0.7, zorder=0)

for bar in bars:
    yval = bar.get_height()
    ax.text(bar.get_x() + bar.get_width()/2.0, yval + 0.08, f'{yval:.2f}s', ha='center', va='bottom', fontsize=11, fontweight='bold')

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "emergency_response.png"))
plt.close(fig)

# -------------------------------------------------------------
# Gráfico 5: Desempenho Desagregado por Cenário
# -------------------------------------------------------------
fig, ax = plt.subplots(figsize=(11, 6))
scenarios = ['NORMAL', 'HEAVY_TRAFFIC', 'ASYMMETRIC', 'DISRUPTION_EMERGENCY']
scen_labels = ['Normal', 'Tráfego Elevado', 'Assimétrico', 'Emergência/Disrupção']
width = 0.18
x_scen = np.arange(len(scenarios))

modes = ['FIXED_TIME', 'LOCAL_ADAPTIVE', 'MULTIAGENT_COORDINATED', 'MULTIAGENT_ML']
mode_short = ['Modo A (Fixed)', 'Modo B (Adapt)', 'Modo C (Coord)', 'Modo D (ML)']

for i, m in enumerate(modes):
    subset = df_scenario[df_scenario['mode'] == m].set_index('scenario').reindex(scenarios)
    ax.bar(x_scen + i * width - (width * 1.5), subset['meanWaitTime'], width, label=mode_short[i], color=colors[i], edgecolor='black', linewidth=0.6)

ax.set_xticks(x_scen)
ax.set_xticklabels(scen_labels)
ax.set_ylabel('Tempo Médio de Espera (segundos)')
ax.set_title('Tempo Médio de Espera por Cenário e Modo de Controlo (10 Sementes/Cenário)', pad=15, fontweight='bold')
ax.legend(frameon=True, facecolor='white', framealpha=0.9)
ax.grid(axis='y', linestyle='--', alpha=0.7)

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "scenario_breakdown.png"))
plt.close(fig)

# -------------------------------------------------------------
# Gráfico 6: Diferenças Pareadas Modo C vs Modo D (N=40 pares)
# -------------------------------------------------------------
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))

# Paridade direta de esperas C vs D
df_c = df_raw[df_raw['mode'] == 'MULTIAGENT_COORDINATED'].sort_values(['scenario', 'seed'])
df_d = df_raw[df_raw['mode'] == 'MULTIAGENT_ML'].sort_values(['scenario', 'seed'])

wait_c = df_c['averageWaitingTimeSeconds'].values
wait_d = df_d['averageWaitingTimeSeconds'].values
diff_wait = wait_d - wait_c

ax1.scatter(wait_c, wait_d, color='#805AD5', alpha=0.7, edgecolors='black', s=50, zorder=3)
min_v = 0.0
max_v = max(wait_c.max(), wait_d.max()) * 1.15
ax1.plot([min_v, max_v], [min_v, max_v], 'r--', label='Linha de Paridade Perfeita (C = D)', zorder=2)
ax1.set_xlabel('Tempo Médio de Espera — Modo C (s)')
ax1.set_ylabel('Tempo Médio de Espera — Modo D (s)')
ax1.set_title('Correlação Pareada C vs D (40 Sementes)', fontweight='bold')
ax1.legend(loc='upper left')
ax1.grid(True, linestyle='--', alpha=0.6)

# Histograma / Boxplot das diferenças
ax2.hist(diff_wait, bins=15, color='#38A169', edgecolor='black', alpha=0.8, zorder=3)
ax2.axvline(0.0, color='red', linestyle='--', linewidth=1.5, label='Diferença Nula (Delta = 0.0s)')
ax2.set_xlabel('Diferença Pareada de Espera: Modo D - Modo C (s)')
ax2.set_ylabel('Frequência de Ensaios')
ax2.set_title('Distribuição das Diferenças Pareadas (D - C)', fontweight='bold')
ax2.legend(loc='upper right')
ax2.grid(True, linestyle='--', alpha=0.6)

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "paired_differences_c_vs_d.png"))
plt.close(fig)

print("SUCESSO: 6 gráficos gerados com alta resolução em:", OUT_DIR)
