from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
import torch
import numpy as np

from gnn_model import TinyGCN, build_adj_from_hours, build_features

app = FastAPI()

model = TinyGCN(in_dim=2, hidden=32, out_dim=1)
model.eval()

class SuggestReq(BaseModel):
    userId: str
    recentEventTitles: List[str]
    recentEventLocations: List[str]
    recentEventHours: List[int]
    topK: int = 5

class SuggestResp(BaseModel):
    suggestedHours: List[int]
    confidences: List[float]

@app.post("/gnn/suggest_time", response_model=SuggestResp)
def suggest_time(req: SuggestReq):
    hours = req.recentEventHours[-50:]  # cap
    adj = build_adj_from_hours(hours)
    x = build_features(hours)

    with torch.no_grad():
        scores = model(x, adj)  # [24]

    # softmax to confidence
    prob = torch.softmax(scores, dim=0).cpu().numpy()

    k = max(1, min(req.topK, 24))
    idx = np.argsort(-prob)[:k].tolist()
    conf = [float(prob[i]) for i in idx]

    return SuggestResp(suggestedHours=idx, confidences=conf)
