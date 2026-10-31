import csv
import json
import math
import numpy as np

# Load dataset
with open('data/3x3/dataset_traffic_3x3.csv', 'r') as f:
    reader = csv.DictReader(f)
    rows = list(reader)

# Load metadata / weights
with open('models/3x3/model_metadata_3x3.json', 'r') as f:
    meta = json.load(f)

# Filter partitions
train_rows = [r for r in rows if r['split'] == 'TRAIN']
val_rows = [r for r in rows if r['split'] == 'VAL']
test_rows = [r for r in rows if r['split'] == 'TEST']

feature_cols = meta['features']

def get_matrix(r_list):
    X = np.array([[float(r[col]) for col in feature_cols] for r in r_list])
    y = np.array([int(r['target_congestion']) for r in r_list])
    return X, y

X_train, y_train = get_matrix(train_rows)
X_val, y_val = get_matrix(val_rows)
X_test, y_test = get_matrix(test_rows)

# Standard scaler fitted strictly on TRAIN
mean_train = np.mean(X_train, axis=0)
std_train = np.std(X_train, axis=0)
std_train[std_train < 1e-12] = 1.0

# Verify training weights and bias
bias = float(meta['bias'])
# Let's read weights from Java model or train Logistic Regression with same params to verify
# In TrafficModelBundle3x3, weights are stored in the bundle
# Let's extract weights using Java helper
print(f"TRAIN size: {len(X_train)}, VAL size: {len(X_val)}, TEST size: {len(X_test)}")
print(f"TEST positives: {sum(y_test)} ({sum(y_test)*100.0/len(y_test):.2f}%), negatives: {len(y_test)-sum(y_test)}")

