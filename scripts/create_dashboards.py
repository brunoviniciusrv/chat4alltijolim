#!/usr/bin/env python3
"""
Script to generate complete Grafana dashboards for Chat4All monitoring
"""

import json
import os

DASHBOARD_DIR = "monitoring/grafana/dashboards"

def create_overview_dashboard():
    """Create main system overview dashboard"""
    return {
        "annotations": {"list": []},
        "editable": True,
        "fiscalYearStartMonth": 0,
        "graphTooltip": 1,
        "id": None,
        "links": [],
        "liveNow": True,
        "panels": [
            # Service Status Panels
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "thresholds"},
                        "mappings": [
                            {"options": {"0": {"color": "red", "index": 0, "text": "DOWN"}, 
                                        "1": {"color": "green", "index": 1, "text": "UP"}}, 
                             "type": "value"}
                        ],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "green", "value": 1}]}
                    }
                },
                "gridPos": {"h": 4, "w": 3, "x": 0, "y": 0},
                "id": 1,
                "options": {"colorMode": "background", "graphMode": "none", "justifyMode": "center", 
                           "orientation": "auto", "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, 
                           "textMode": "auto"},
                "targets": [{"datasource": {"type": "prometheus", "uid": "prometheus"}, "expr": "up{job=\"api-service\"}", "refId": "A"}],
                "title": "API Service",
                "type": "stat"
            },
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "thresholds"},
                        "mappings": [{"options": {"0": {"color": "red", "index": 0, "text": "DOWN"}, 
                                                 "1": {"color": "green", "index": 1, "text": "UP"}}, "type": "value"}],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "green", "value": 1}]}
                    }
                },
                "gridPos": {"h": 4, "w": 3, "x": 3, "y": 0},
                "id": 2,
                "options": {"colorMode": "background", "graphMode": "none", "justifyMode": "center", "orientation": "auto", 
                           "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "textMode": "auto"},
                "targets": [{"datasource": {"type": "prometheus", "uid": "prometheus"}, "expr": "up{job=\"router-worker\"}", "refId": "A"}],
                "title": "Router Worker",
                "type": "stat"
            },
            # Success Rate Gauge
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "thresholds"},
                        "mappings": [],
                        "max": 100,
                        "min": 0,
                        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, 
                                                                     {"color": "yellow", "value": 95}, 
                                                                     {"color": "red", "value": 99}]},
                        "unit": "percent"
                    }
                },
                "gridPos": {"h": 4, "w": 6, "x": 6, "y": 0},
                "id": 3,
                "options": {"orientation": "auto", "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, 
                           "showThresholdLabels": False, "showThresholdMarkers": True},
                "targets": [{"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                            "expr": "(1 - (rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m]))) * 100", 
                            "refId": "A"}],
                "title": "Success Rate (5m)",
                "type": "gauge"
            },
            # P99 Latency
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "thresholds"},
                        "mappings": [],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, 
                                                                     {"color": "yellow", "value": 0.1}, 
                                                                     {"color": "red", "value": 0.2}]},
                        "unit": "s"
                    }
                },
                "gridPos": {"h": 4, "w": 6, "x": 12, "y": 0},
                "id": 4,
                "options": {"colorMode": "value", "graphMode": "area", "justifyMode": "auto", "orientation": "auto", 
                           "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "textMode": "auto"},
                "targets": [{"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                            "expr": "histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))", 
                            "refId": "A"}],
                "title": "P99 Latency (Target < 200ms)",
                "type": "stat"
            },
            # Total Requests
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "thresholds"},
                        "mappings": [],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}]},
                        "unit": "short"
                    }
                },
                "gridPos": {"h": 4, "w": 3, "x": 18, "y": 0},
                "id": 5,
                "options": {"colorMode": "value", "graphMode": "area", "justifyMode": "auto", "orientation": "auto", 
                           "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "textMode": "auto"},
                "targets": [{"datasource": {"type": "prometheus", "uid": "prometheus"}, "expr": "grpc_requests_total", "refId": "A"}],
                "title": "Total Requests",
                "type": "stat"
            },
            # Request Rate Graph
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "palette-classic"},
                        "custom": {
                            "axisCenteredZero": False,
                            "axisColorMode": "text",
                            "axisLabel": "requests/sec",
                            "axisPlacement": "auto",
                            "barAlignment": 0,
                            "drawStyle": "line",
                            "fillOpacity": 10,
                            "gradientMode": "none",
                            "hideFrom": {"tooltip": False, "viz": False, "legend": False},
                            "lineInterpolation": "smooth",
                            "lineWidth": 2,
                            "pointSize": 5,
                            "scaleDistribution": {"type": "linear"},
                            "showPoints": "never",
                            "spanNulls": True,
                            "stacking": {"group": "A", "mode": "none"},
                            "thresholdsStyle": {"mode": "off"}
                        },
                        "mappings": [],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}]},
                        "unit": "reqps"
                    }
                },
                "gridPos": {"h": 8, "w": 12, "x": 0, "y": 4},
                "id": 6,
                "options": {
                    "legend": {"calcs": ["mean", "last", "max"], "displayMode": "table", "placement": "bottom", "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "desc"}
                },
                "targets": [
                    {"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                     "expr": "rate(grpc_requests_total[1m])", 
                     "legendFormat": "Total Requests/s", 
                     "refId": "A"},
                    {"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                     "expr": "rate(grpc_requests_failed_total[1m])", 
                     "legendFormat": "Failed Requests/s", 
                     "refId": "B"}
                ],
                "title": "gRPC Request Rate",
                "type": "timeseries"
            },
            # Latency Percentiles
            {
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "fieldConfig": {
                    "defaults": {
                        "color": {"mode": "palette-classic"},
                        "custom": {
                            "axisCenteredZero": False,
                            "axisColorMode": "text",
                            "axisLabel": "seconds",
                            "axisPlacement": "auto",
                            "barAlignment": 0,
                            "drawStyle": "line",
                            "fillOpacity": 10,
                            "gradientMode": "none",
                            "hideFrom": {"tooltip": False, "viz": False, "legend": False},
                            "lineInterpolation": "smooth",
                            "lineWidth": 2,
                            "pointSize": 5,
                            "scaleDistribution": {"type": "linear"},
                            "showPoints": "never",
                            "spanNulls": True,
                            "stacking": {"group": "A", "mode": "none"},
                            "thresholdsStyle": {"mode": "line"}
                        },
                        "mappings": [],
                        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 0.2}]},
                        "unit": "s"
                    }
                },
                "gridPos": {"h": 8, "w": 12, "x": 12, "y": 4},
                "id": 7,
                "options": {
                    "legend": {"calcs": ["mean", "last", "max"], "displayMode": "table", "placement": "bottom", "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "none"}
                },
                "targets": [
                    {"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                     "expr": "histogram_quantile(0.50, rate(grpc_request_duration_seconds_bucket[5m]))", 
                     "legendFormat": "P50 Latency", 
                     "refId": "A"},
                    {"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                     "expr": "histogram_quantile(0.95, rate(grpc_request_duration_seconds_bucket[5m]))", 
                     "legendFormat": "P95 Latency", 
                     "refId": "B"},
                    {"datasource": {"type": "prometheus", "uid": "prometheus"}, 
                     "expr": "histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))", 
                     "legendFormat": "P99 Latency (Target < 200ms)", 
                     "refId": "C"}
                ],
                "title": "gRPC Latency Percentiles",
                "type": "timeseries"
            }
        ],
        "refresh": "5s",
        "schemaVersion": 38,
        "style": "dark",
        "tags": ["chat4all", "overview"],
        "templating": {"list": []},
        "time": {"from": "now-15m", "to": "now"},
        "timepicker": {},
        "timezone": "",
        "title": "Chat4All - System Overview",
        "uid": "chat4all-overview",
        "version": 1,
        "weekStart": ""
    }

def save_dashboard(filename, dashboard):
    """Save dashboard JSON to file"""
    filepath = os.path.join(DASHBOARD_DIR, filename)
    os.makedirs(DASHBOARD_DIR, exist_ok=True)
    with open(filepath, 'w') as f:
        json.dump(dashboard, f, indent=2)
    print(f"✅ Created: {filepath}")

if __name__ == "__main__":
    save_dashboard("1-overview.json", create_overview_dashboard())
    print("✅ All dashboards created successfully!")
