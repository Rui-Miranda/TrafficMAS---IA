import csv
from collections import defaultdict

data = defaultdict(lambda: {'wait': [], 'tp': [], 'q': []})
with open('results/experiments/fase7_ablation_study_corrected.csv', 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        c = row['AblationConfig']
        data[c]['wait'].append(float(row['AvgWaitSec']))
        data[c]['tp'].append(float(row['ThroughputPerHour']))
        data[c]['q'].append(float(row['MeanQueue']))

print(f"{'Config':<24} | {'AvgWaitSec':<10} | {'Throughput':<10} | {'MeanQueue':<10}")
print("-" * 62)
for c in ['BASE_NO_COORD', 'COORD_OFFSET_ZERO', 'COORD_OFFSET_PHYSICAL', 'COORD_ML_ALERT_ONLY', 'COORD_ML_FULL_ACTION']:
    w = sum(data[c]['wait']) / len(data[c]['wait'])
    tp = sum(data[c]['tp']) / len(data[c]['tp'])
    q = sum(data[c]['q']) / len(data[c]['q'])
    print(f"{c:<24} | {w:<10.3f} | {tp:<10.1f} | {q:<10.3f}")

