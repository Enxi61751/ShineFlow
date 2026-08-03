import torch
import torch.nn as nn
import torch.nn.functional as F

class TinyGCN(nn.Module):
    """
    Nodes: 24 hour nodes (0..23)
    Edges: ring + extra edges learned from recent history
    Input feature: one-hot hour + recent frequency scalar
    Output: score for each hour
    """
    def __init__(self, in_dim=2, hidden=32, out_dim=1):
        super().__init__()
        self.fc1 = nn.Linear(in_dim, hidden)
        self.fc2 = nn.Linear(hidden, out_dim)

    def forward(self, x, adj):
        # simple GCN: H = A X W
        h = torch.matmul(adj, x)
        h = F.relu(self.fc1(h))
        h = torch.matmul(adj, h)
        out = self.fc2(h).squeeze(-1)  # [24]
        return out

def build_adj_from_hours(recent_hours):
    """
    Build a 24x24 adjacency matrix:
    - base ring connections
    - add edges between consecutive hours appearing in history
    """
    n = 24
    adj = torch.zeros((n, n), dtype=torch.float32)

    # ring
    for i in range(n):
        adj[i, i] = 1.0
        adj[i, (i - 1) % n] = 1.0
        adj[i, (i + 1) % n] = 1.0

    # history transitions
    for a, b in zip(recent_hours[:-1], recent_hours[1:]):
        if 0 <= a < 24 and 0 <= b < 24:
            adj[a, b] += 1.0
            adj[b, a] += 1.0

    # normalize D^{-1/2} A D^{-1/2}
    deg = adj.sum(dim=1).clamp(min=1.0)
    d_inv_sqrt = torch.pow(deg, -0.5)
    D = torch.diag(d_inv_sqrt)
    adj_norm = D @ adj @ D
    return adj_norm

def build_features(recent_hours):
    """
    x: [24, 2]
    feature0: one-hot is implicit, so we use:
      - sin/cos encoding simplified -> here use hour/23 scalar
      - recent frequency scalar
    """
    n = 24
    freq = torch.zeros((n,), dtype=torch.float32)
    for h in recent_hours:
        if 0 <= h < 24:
            freq[h] += 1.0
    if freq.max() > 0:
        freq = freq / freq.max()

    hour_scalar = torch.arange(n, dtype=torch.float32) / 23.0
    x = torch.stack([hour_scalar, freq], dim=1)  # [24,2]
    return x
