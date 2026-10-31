"""
Script para geracao de graficos cientificos de alta resolucao da Fase 7:
11_comparativo_abcd_sub_saturacao.png
12_ablation_corrigida_zero_vs_fisico.png
"""

import os
import shutil
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

os.makedirs("docs/evidencias_fase7", exist_ok=True)
BRAIN_DIR = "C:/Users/etien/.gemini/antigravity/brain/8e70ea93-be67-4250-98f3-a72f34a8bd5b"

plt.rcParams.update({
    'font.size': 11,
    'font.family': 'sans-serif',
    'axes.labelsize': 12,
    'axes.titlesize': 13,
    'xtick.labelsize': 11,
    'ytick.labelsize': 11,
    'legend.fontsize': 11,
    'figure.titlesize': 14
})

# -------------------------------------------------------------
# 11: Comparativo ABCD em Sub-saturacao
# -------------------------------------------------------------
df_stats = pd.read_csv("results/experiments/fase65b_estatisticas_modos.csv")
# Mode,Runs,ThroughputPerHour_Mean,ThroughputPerHour_Std,AvgWaitSec_Mean,AvgWaitSec_Std,MeanQueue_Mean,MeanQueue_Std...
order_map = {
    "FIXED_TIME": "Modo A\n(Tempos Fixos)",
    "LOCAL_ADAPTIVE": "Modo B\n(Atuado Local)",
    "MULTIAGENT_COORDINATED": "Modo C\n(Coord. Multiagente)",
    "MULTIAGENT_ML": "Modo D\n(Coord. + ML Preditivo)"
}
df_stats['Order'] = df_stats['Mode'].map({"FIXED_TIME": 1, "LOCAL_ADAPTIVE": 2, "MULTIAGENT_COORDINATED": 3, "MULTIAGENT_ML": 4})
df_stats = df_stats.sort_values('Order')

modes = [order_map[m] for m in df_stats['Mode']]
wait_means = df_stats['AvgWaitSec_Mean'].values
wait_stds = df_stats['AvgWaitSec_Std'].values
tp_means = df_stats['ThroughputPerHour_Mean'].values
tp_stds = df_stats['ThroughputPerHour_Std'].values

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5.5), dpi=300)
colors = ['#4A90E2', '#E67E22', '#2ECC71', '#9B59B6']

# Subplot 1: Tempo Medio de Espera
bars1 = ax1.bar(modes, wait_means, yerr=wait_stds, capsize=5, color=colors, alpha=0.88, edgecolor='black', width=0.55)
ax1.set_ylabel("Tempo Médio de Espera (segundos)")
ax1.set_title("Tempo Médio de Espera por Modo (160 Corridas)", fontweight='bold')
ax1.grid(axis='y', linestyle='--', alpha=0.5)
ax1.set_ylim(0, 5.0)

for bar in bars1:
    h = bar.get_height()
    ax1.annotate(f'{h:.2f} s', xy=(bar.get_x() + bar.get_width() / 2, h / 2),
                 xytext=(0, 0), textcoords="offset points", ha='center', va='center',
                 color='white', fontweight='bold', fontsize=11,
                 bbox=dict(boxstyle="round,pad=0.2", fc="black", alpha=0.6))

# Anotacao de Sub-saturacao e p-value
ax1.text(0.5, 0.92, "Regime Sub-saturado (50 veic. / 9 cruzamentos)\nModo C (3.35s) vs Modo D (3.34s): Δ = -0.010s (p = 0.722, n.s.)\nModo A apresenta menor espera devido à ausência de penalizações de comutação",
         transform=ax1.transAxes, ha='center', va='top', fontsize=9,
         bbox=dict(boxstyle="round,pad=0.4", fc="#FFF3CD", ec="#856404", alpha=0.9))

# Subplot 2: Throughput da Rede
bars2 = ax2.bar(modes, tp_means, yerr=tp_stds, capsize=5, color=colors, alpha=0.88, edgecolor='black', width=0.55)
ax2.set_ylabel("Throughput da Rede (veículos/hora)")
ax2.set_title("Capacidade e Vazão Efetiva (Throughput)", fontweight='bold')
ax2.grid(axis='y', linestyle='--', alpha=0.5)
ax2.set_ylim(3000, 5000)

for bar in bars2:
    h = bar.get_height()
    ax2.annotate(f'{h:.1f} v/h', xy=(bar.get_x() + bar.get_width() / 2, 3600),
                 xytext=(0, 0), textcoords="offset points", ha='center', va='center',
                 color='white', fontweight='bold', fontsize=11,
                 bbox=dict(boxstyle="round,pad=0.2", fc="black", alpha=0.6))

fig.suptitle("Avaliação Experimental TrafficMAS 3×3 — Comparação dos Modos A/B/C/D", fontsize=15, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0.03, 1, 0.95])

out11 = "docs/evidencias_fase7/11_comparativo_abcd_sub_saturacao.png"
plt.savefig(out11, dpi=300)
plt.close()
print(f"Salvo: {out11}")
if os.path.isdir(BRAIN_DIR):
    shutil.copy2(out11, os.path.join(BRAIN_DIR, "11_comparativo_abcd_sub_saturacao.png"))


# -------------------------------------------------------------
# 12: Estudo de Ablacao Corrigido (Zero vs Fisico vs ML)
# -------------------------------------------------------------
df_abl = pd.read_csv("results/experiments/fase7_ablation_study_corrected.csv")
# Columns: AblationConfig,Scenario,Seed,ThroughputPerHour,AvgWaitSec,MeanQueue,CoordinationApplied,MLAlerts

order_abl = [
    "BASE_NO_COORD",
    "COORD_OFFSET_ZERO",
    "COORD_OFFSET_PHYSICAL",
    "COORD_ML_ALERT_ONLY",
    "COORD_ML_FULL_ACTION"
]

labels_abl = [
    "Base\n(Sem Coordenação)",
    "Offsets Zero\n(0 ms)",
    "Offsets Físicos\n(t = d / v)",
    "ML Alertas\n(Sem Ação Semaf.)",
    "ML Ação Total\n(Extensão Verde)"
]

summary_abl = df_abl.groupby("AblationConfig").agg(
    wait_mean=("AvgWaitSec", "mean"),
    wait_std=("AvgWaitSec", "std"),
    tp_mean=("ThroughputPerHour", "mean"),
    queue_mean=("MeanQueue", "mean")
).reindex(order_abl)

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6), dpi=300)
abl_colors = ['#95A5A6', '#E74C3C', '#2ECC71', '#3498DB', '#9B59B6']

# Subplot 1: Tempo Medio de Espera
bars1 = ax1.bar(labels_abl, summary_abl["wait_mean"], yerr=summary_abl["wait_std"], capsize=5, color=abl_colors, alpha=0.88, edgecolor='black', width=0.55)
ax1.set_ylabel("Tempo Médio de Espera (s)")
ax1.set_title("Impacto no Tempo de Espera por Componente (50 Corridas)", fontweight='bold')
ax1.grid(axis='y', linestyle='--', alpha=0.5)
ax1.set_ylim(0, 4.8)

for bar in bars1:
    h = bar.get_height()
    ax1.annotate(f'{h:.3f} s', xy=(bar.get_x() + bar.get_width() / 2, h / 2),
                 xytext=(0, 0), textcoords="offset points", ha='center', va='center',
                 color='white', fontweight='bold', fontsize=10,
                 bbox=dict(boxstyle="round,pad=0.2", fc="black", alpha=0.6))

# Subplot 2: Fila Media na Rede
bars2 = ax2.bar(labels_abl, summary_abl["queue_mean"], color=abl_colors, alpha=0.88, edgecolor='black', width=0.55)
ax2.set_ylabel("Comprimento Médio da Fila (veículos/cruzamento)")
ax2.set_title("Comprimento Médio da Fila por Cruzamento", fontweight='bold')
ax2.grid(axis='y', linestyle='--', alpha=0.5)
ax2.set_ylim(0, 0.45)

for bar in bars2:
    h = bar.get_height()
    ax2.annotate(f'{h:.3f}', xy=(bar.get_x() + bar.get_width() / 2, h / 2),
                 xytext=(0, 0), textcoords="offset points", ha='center', va='center',
                 color='white', fontweight='bold', fontsize=10,
                 bbox=dict(boxstyle="round,pad=0.2", fc="black", alpha=0.6))

# Caixa explicativa da ablacao corrigida
ax1.text(0.5, 0.93,
         "Offsets Físicos (3.496s) vs Offsets Zero (3.428s):\n"
         "Em malha sub-saturada, offsets zero mantêm sincronia simétrica.\n"
         "ML Alert-Only (3.496s) é rigorosamente idêntico a Offsets Físicos (isolamento perfeito).\n"
         "ML Full-Action (3.478s) reduz 0.018s em relação ao Físico (diferença n.s., p > 0.70).",
         transform=ax1.transAxes, ha='center', va='top', fontsize=9.2,
         bbox=dict(boxstyle="round,pad=0.4", fc="#E8F4F8", ec="#2980B9", alpha=0.9))

fig.suptitle("Estudo de Ablação Corrigido — Análise dos Componentes de Coordenação 3×3", fontsize=15, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0.03, 1, 0.95])

out12 = "docs/evidencias_fase7/12_ablation_corrigida_zero_vs_fisico.png"
plt.savefig(out12, dpi=300)
plt.close()
print(f"Salvo: {out12}")
if os.path.isdir(BRAIN_DIR):
    shutil.copy2(out12, os.path.join(BRAIN_DIR, "12_ablation_corrigida_zero_vs_fisico.png"))

print("Todas as figuras cientificas foram geradas com sucesso!")
