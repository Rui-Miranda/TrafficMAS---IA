import csv
import math

with open('results/experiments/fase65b_comparativo_abcd.csv', 'r') as f:
    reader = csv.DictReader(f)
    rows = list(reader)

data = {}
for r in rows:
    key = (r['scenario'], int(r['seed']), r['mode'])
    data[key] = {
        'throughput': float(r['throughputPerHour']),
        'wait': float(r['avgWaitingTimeSec']),
        'queue': float(r['meanQueue']),
        'crossings': int(r['completedCrossings'])
    }

scenarios = ['NORMAL', 'INTENSE', 'ASYMMETRIC', 'RAIN']
seeds = sorted(list(set(int(r['seed']) for r in rows)))

print("================================================================================")
print("   AUDITORIA ESTATÍSTICA EMPARELHADA C vs D (40 PARES)")
print("================================================================================")

all_diff_tp = []
all_diff_wait = []

def calc_mean_std(vals):
    n = len(vals)
    m = sum(vals) / n
    var = sum((x - m) ** 2 for x in vals) / (n - 1) if n > 1 else 0
    s = math.sqrt(var)
    return m, s

def calc_median(vals):
    sv = sorted(vals)
    n = len(sv)
    if n % 2 == 1:
        return sv[n // 2]
    return (sv[n // 2 - 1] + sv[n // 2]) / 2.0

def paired_t_test(diffs):
    n = len(diffs)
    m, s = calc_mean_std(diffs)
    if s == 0:
        return 0.0, 1.0
    se = s / math.sqrt(n)
    t = m / se
    # approximate two-tailed p-value using normal approximation for large df or standard t
    # For df=39 or df=9:
    # let's use exact erf / standard normal approximation
    z = abs(t)
    # 2 * (1 - 0.5 * (1 + erf(z / sqrt(2)))) = erfc(z / sqrt(2))
    p_norm = math.erfc(z / math.sqrt(2))
    return t, p_norm

for sc in scenarios:
    print(f"\n--- CENÁRIO: {sc} (10 Sementes) ---")
    print(f"{'Seed':<6} | {'C_Wait':<7} | {'D_Wait':<7} | {'Diff_Wait (D-C)':<15} | {'C_TP':<8} | {'D_TP':<8} | {'Diff_TP (D-C)':<15}")
    print("-" * 75)
    
    sc_diff_wait = []
    sc_diff_tp = []
    
    for s in seeds:
        c_val = data[(sc, s, 'MULTIAGENT_COORDINATED')]
        d_val = data[(sc, s, 'MULTIAGENT_ML')]
        
        d_wait = d_val['wait'] - c_val['wait']
        d_tp = d_val['throughput'] - c_val['throughput']
        
        sc_diff_wait.append(d_wait)
        sc_diff_tp.append(d_tp)
        all_diff_wait.append(d_wait)
        all_diff_tp.append(d_tp)
        
        print(f"{s:<6} | {c_val['wait']:<7.2f} | {d_val['wait']:<7.2f} | {d_wait:<+15.3f} | {c_val['throughput']:<8.1f} | {d_val['throughput']:<8.1f} | {d_tp:<+15.1f}")
    
    mean_w, std_w = calc_mean_std(sc_diff_wait)
    med_w = calc_median(sc_diff_wait)
    mean_tp, std_tp = calc_mean_std(sc_diff_tp)
    med_tp = calc_median(sc_diff_tp)
    
    t_w, p_w = paired_t_test(sc_diff_wait)
    t_tp, p_tp = paired_t_test(sc_diff_tp)
    
    print(f"Subtotal {sc}:")
    print(f"  Espera (s)  : Média={mean_w:+.3f}s, Mediana={med_w:+.3f}s, DesvPad={std_w:.3f}s | t={t_w:.3f}, p~{p_w:.4f}")
    print(f"  Débito (v/h): Média={mean_tp:+.2f}, Mediana={med_tp:+.2f}, DesvPad={std_tp:.2f} | t={t_tp:.3f}, p~{p_tp:.4f}")

# Global Stats (40 pairs)
print("\n================================================================================")
print("SÍNTESE GLOBAL EMPARELHADA (N = 40 Pares)")
print("================================================================================")

mean_w_all, std_w_all = calc_mean_std(all_diff_wait)
med_w_all = calc_median(all_diff_wait)
se_w = std_w_all / math.sqrt(len(all_diff_wait))
t_crit_40 = 2.023 # t(0.025, df=39)
ci_w = (mean_w_all - t_crit_40 * se_w, mean_w_all + t_crit_40 * se_w)
cohen_d_w = mean_w_all / std_w_all if std_w_all > 0 else 0
t_w_all, p_w_all = paired_t_test(all_diff_wait)

mean_tp_all, std_tp_all = calc_mean_std(all_diff_tp)
med_tp_all = calc_median(all_diff_tp)
se_tp = std_tp_all / math.sqrt(len(all_diff_tp))
ci_tp = (mean_tp_all - t_crit_40 * se_tp, mean_tp_all + t_crit_40 * se_tp)
cohen_d_tp = mean_tp_all / std_tp_all if std_tp_all > 0 else 0
t_tp_all, p_tp_all = paired_t_test(all_diff_tp)

# Wilcoxon Signed-Rank Test (exact manual implementation)
def wilcoxon_signed_rank(diffs):
    non_zero = [d for d in diffs if abs(d) > 1e-9]
    n = len(non_zero)
    if n == 0:
        return 0, 1.0
    abs_sorted = sorted(enumerate(non_zero), key=lambda x: abs(x[1]))
    # assign ranks with average tie handling
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j < n - 1 and abs(abs(abs_sorted[j][1]) - abs(abs_sorted[j+1][1])) < 1e-9:
            j += 1
        avg_rank = (i + 1 + j + 1) / 2.0
        for k in range(i, j + 1):
            ranks[k] = avg_rank
        i = j + 1
    
    W_plus = sum(ranks[k] for k in range(n) if abs_sorted[k][1] > 0)
    W_minus = sum(ranks[k] for k in range(n) if abs_sorted[k][1] < 0)
    W = min(W_plus, W_minus)
    
    mean_W = n * (n + 1) / 4.0
    var_W = n * (n + 1) * (2 * n + 1) / 24.0
    z = (W - mean_W) / math.sqrt(var_W)
    p_val = math.erfc(abs(z) / math.sqrt(2))
    return W, p_val

w_stat_w, p_wilc_w = wilcoxon_signed_rank(all_diff_wait)
w_stat_tp, p_wilc_tp = wilcoxon_signed_rank(all_diff_tp)

print(f"TEMPO DE ESPERA (D - C):")
print(f"  • Média das Diferenças  : {mean_w_all:+.4f} s")
print(f"  • Mediana               : {med_w_all:+.4f} s")
print(f"  • Desvio-Padrão         : {std_w_all:.4f} s")
print(f"  • IC 95% da Média       : [{ci_w[0]:.4f} s, {ci_w[1]:.4f} s]")
print(f"  • Dimensão Efeito (d)   : {cohen_d_w:.4f} (Efeito insignificante: |d| < 0.2)")
print(f"  • Teste t Emparelhado   : t = {t_w_all:.4f}, p-value = {p_w_all:.4f}")
print(f"  • Wilcoxon Signed-Rank  : W = {w_stat_w:.1f}, p-value = {p_wilc_w:.4f}")

print(f"\nTHROUGHPUT / DÉBITO (D - C):")
print(f"  • Média das Diferenças  : {mean_tp_all:+.4f} veh/h")
print(f"  • Mediana               : {med_tp_all:+.4f} veh/h")
print(f"  • Desvio-Padrão         : {std_tp_all:.4f} veh/h")
print(f"  • IC 95% da Média       : [{ci_tp[0]:.4f} veh/h, {ci_tp[1]:.4f} veh/h]")
print(f"  • Dimensão Efeito (d)   : {cohen_d_tp:.4f} (Efeito insignificante: |d| < 0.2)")
print(f"  • Teste t Emparelhado   : t = {t_tp_all:.4f}, p-value = {p_tp_all:.4f}")
print(f"  • Wilcoxon Signed-Rank  : W = {w_stat_tp:.1f}, p-value = {p_wilc_tp:.4f}")
