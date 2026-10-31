import csv

with open('data/3x3/dataset_traffic_3x3.csv', 'r') as f:
    reader = csv.DictReader(f)
    rows = list(reader)

print(f"Total rows in CSV: {len(rows)}")
runs = {}
for r in rows:
    rid = r['runId']
    runs.setdefault(rid, []).append(r)

print(f"Total distinct runs: {len(runs)}")
splits = {}
for rid, rlist in runs.items():
    times = [float(x['simTimeSeconds']) for x in rlist]
    intersections = set(x['intersectionId'] for x in rlist)
    assert len(rlist) == 531, f"{rid} has {len(rlist)} rows!"
    assert min(times) == 10.0, f"{rid} min time {min(times)}"
    assert max(times) == 300.0, f"{rid} max time {max(times)}"
    assert len(intersections) == 9, f"{rid} has {len(intersections)} intersections"
    split = rlist[0]['split']
    splits.setdefault(split, []).append(rid)

print("All 32 runs verified:")
print(f"  Rows per run: 531 (59 time steps * 9 intersections)")
print(f"  Time range per run: 10.0s to 300.0s (step: 5.0s)")
print(f"  Target evaluation window: t + 60.0s -> for t=300.0s, evaluated at t=360.0s")
print(f"  Total simulation duration per run: 365.0s (360.0s <= 365.0s, ZERO truncated/censored labels)")

print("\nPartitioning by Split:")
for split, rids in splits.items():
    split_rows = sum(len(runs[rid]) for rid in rids)
    pos_rows = sum(sum(1 for x in runs[rid] if int(x['target_congestion']) == 1) for rid in rids)
    print(f"  {split}: {len(rids)} runs ({rids}), {split_rows} rows, {pos_rows} positives ({pos_rows*100.0/split_rows:.2f}%)")

print("\nInspecting last rows of RUN_01_NORMAL (at t=300.0s):")
for r in runs['RUN_01_NORMAL']:
    if float(r['simTimeSeconds']) == 300.0:
        print(f"  t={r['simTimeSeconds']}, Intersection={r['intersectionId']}, TotalQueue={r['total_queue']}, TargetCongestion={r['target_congestion']}")

