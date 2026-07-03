#!/usr/bin/env python3
# Generates a self-contained cross-backend comparison report (HTML + inline SVG, no JS).
import csv, math, os

OUT = "/Users/guilliano/workspace/personal/skycloak/repos/locke/benchmark/k8s/report.html"

# ---------------------------------------------------------------- data
# Parity full distributions. Each: ups -> dict(p50,mean,p95,p99,max,rps)
AWS = {
  "ElastiCache (AWS managed, 3-shard OSS cluster)": {
     80:dict(p50=53,mean=67,p95=156,p99=181,mx=245,rps=273.7),
     160:dict(p50=58,mean=89,p95=207,p99=588,mx=943,rps=547.6),
     250:dict(p50=124,mean=2411,p95=11749,p99=12391,mx=13289,rps=639.6)},
  "Co-located single redis (AWS, shared infra node)": {
     80:dict(p50=50,mean=70,p95=207,p99=241,mx=357,rps=273.4),
     160:dict(p50=53,mean=203,p95=789,p99=1042,mx=1352,rps=547.9),
     250:dict(p50=68,mean=2914,p95=13810,p99=15536,mx=16784,rps=600.6)},
  "Upstream Infinispan (AWS, in-process)": {
     80:dict(p50=5,mean=20,p95=77,p99=84,mx=144,rps=273.4),
     160:dict(p50=5,mean=21,p95=79,p99=125,mx=276,rps=548.1),
     250:dict(p50=662,mean=1158,p95=3962,p99=4626,mx=5383,rps=760.0)},
}
AZURE = {
  "Co-located single redis (Azure, dedicated E8 nodes)": {
     80:dict(p50=20,mean=22,p95=44,p99=50,mx=192,rps=271.0),
     160:dict(p50=20,mean=24,p95=47,p99=56,mx=200,rps=541.9),
     250:dict(p50=22,mean=28,p95=60,p99=103,mx=216,rps=846.9)},
  "Upstream Infinispan (Azure, in-process)": {
     80:dict(p50=5,mean=12,p95=41,p99=49,mx=489,rps=271.6),
     160:dict(p50=4,mean=11,p95=42,p99=47,mx=81,rps=543.5),
     250:dict(p50=4,mean=14,p95=53,p99=91,mx=268,rps=850.8)},
}
# p99-only summary points (no full dist captured)
AZURE_CLUSTER = {80:60,160:None,250:63}      # 6-node co-located cluster
AMR = {80:4940,160:None,250:18609}           # Azure Managed Redis (proxy), B1 tier; rps@250=601

# Resilience (KC pod kill during 80 ups): p50/p99/rps/failed%
RESIL = [
  ("Locke · co-located redis (Azure)", 20, 268, 281.8, 0.01, "#22c55e"),
  ("Locke · ElastiCache (AWS)",         75, 522, 281.9, 0.0,  "#3b82f6"),
  ("Locke · 6-node cluster (Azure)",    None, 53, None, None, "#10b981"),
  ("Upstream Infinispan (Azure)",       19, 37068, 278.7, 0.68, "#ef4444"),
]

def load_timeline(p):
    rows=[]
    with open(p) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["sec"]), float(r["rps"]), float(r["p99_ms"]), int(r["ko"])))
    return rows
BASE="/Users/guilliano/workspace/personal/skycloak/repos/locke/benchmark/k8s/results/full-distribution"
TL_LOCKE=load_timeline(f"{BASE}/timeline-locke.csv")
TL_ISPN=load_timeline(f"{BASE}/timeline-infinispan.csv")

# ---------------------------------------------------------------- svg helpers
def ylog(v, vmin, vmax, h, pad_top, pad_bot):
    v=max(v,vmin)
    lo,hi=math.log10(vmin),math.log10(vmax)
    frac=(math.log10(v)-lo)/(hi-lo)
    return (h-pad_bot) - frac*(h-pad_top-pad_bot)

def hbar_log_chart(title, items, vmax=50000, vmin=10, unit="ms"):
    # items: list of (label, value, color). horizontal log bars.
    W=860; rowh=46; H=len(items)*rowh+70
    x0=330; xw=W-x0-90
    def xlen(v):
        v=max(v,vmin)
        return xw*(math.log10(v)-math.log10(vmin))/(math.log10(vmax)-math.log10(vmin))
    s=[f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="{title}" class="chart">']
    s.append(f'<text x="{W/2}" y="26" class="ctitle" text-anchor="middle">{title}</text>')
    # gridlines at 10,100,1000,10000
    for gv in [10,100,1000,10000]:
        gx=x0+xlen(gv)
        s.append(f'<line x1="{gx:.0f}" y1="44" x2="{gx:.0f}" y2="{H-22}" class="grid"/>')
        s.append(f'<text x="{gx:.0f}" y="{H-8}" class="axis" text-anchor="middle">{gv:,}</text>')
    for i,(lab,val,col) in enumerate(items):
        y=54+i*rowh
        s.append(f'<text x="{x0-12}" y="{y+rowh/2-4}" class="blabel" text-anchor="end">{lab}</text>')
        bw=xlen(val)
        s.append(f'<rect x="{x0}" y="{y}" width="{bw:.1f}" height="{rowh-18}" rx="4" fill="{col}"/>')
        s.append(f'<text x="{x0+bw+8:.1f}" y="{y+rowh/2-4}" class="bval">{val:,} {unit}</text>')
    s.append('</svg>')
    return "\n".join(s)

def line_chart(title, series, ymax=20000, ymin=1, ylab="p99 latency (ms, log)"):
    # series: list of (label, color, [(x,y)...]) over ups 80/160/250
    W=860; H=420; padl=70; padr=210; padt=50; padb=60
    xs={80:padl+0,160:padl+(W-padl-padr)*0.5,250:padl+(W-padl-padr)*1.0}
    s=[f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="{title}" class="chart">']
    s.append(f'<text x="{(W-padr)/2}" y="28" class="ctitle" text-anchor="middle">{title}</text>')
    for gv in [1,10,100,1000,10000]:
        gy=ylog(gv,ymin,ymax,H,padt,padb)
        s.append(f'<line x1="{padl}" y1="{gy:.0f}" x2="{W-padr}" y2="{gy:.0f}" class="grid"/>')
        s.append(f'<text x="{padl-10}" y="{gy+4:.0f}" class="axis" text-anchor="end">{gv:,}</text>')
    for u in (80,160,250):
        s.append(f'<text x="{xs[u]:.0f}" y="{H-padb+24}" class="axis" text-anchor="middle">{u} ups</text>')
    s.append(f'<text x="18" y="{H/2}" class="axis" text-anchor="middle" transform="rotate(-90 18 {H/2})">{ylab}</text>')
    for j,(lab,col,pts) in enumerate(series):
        d=[]
        for (u,v) in pts:
            if v is None: continue
            d.append(f"{xs[u]:.1f},{ylog(v,ymin,ymax,H,padt,padb):.1f}")
        s.append(f'<polyline points="{" ".join(d)}" fill="none" stroke="{col}" stroke-width="2.5"/>')
        for (u,v) in pts:
            if v is None: continue
            cy=ylog(v,ymin,ymax,H,padt,padb)
            s.append(f'<circle cx="{xs[u]:.1f}" cy="{cy:.1f}" r="4" fill="{col}"/>')
        ly=padt+14+j*22
        s.append(f'<rect x="{W-padr+10}" y="{ly-9}" width="14" height="4" fill="{col}"/>')
        s.append(f'<text x="{W-padr+30}" y="{ly-4}" class="leg">{lab}</text>')
    s.append('</svg>')
    return "\n".join(s)

def resil_timeline(title, sa, sb):
    # sa,sb: (label,color,rows). rows: (sec,rps,p99,ko). log p99.
    W=860;H=420;padl=70;padr=200;padt=50;padb=60
    ymin,ymax=20,50000
    xmax=max(sa[2][-1][0], sb[2][-1][0])
    def X(sec): return padl+(W-padl-padr)*sec/xmax
    s=[f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="{title}" class="chart">']
    s.append(f'<text x="{(W-padr)/2}" y="28" class="ctitle" text-anchor="middle">{title}</text>')
    for gv in [100,1000,10000,40000]:
        gy=ylog(gv,ymin,ymax,H,padt,padb)
        s.append(f'<line x1="{padl}" y1="{gy:.0f}" x2="{W-padr}" y2="{gy:.0f}" class="grid"/>')
        s.append(f'<text x="{padl-10}" y="{gy+4:.0f}" class="axis" text-anchor="end">{gv:,}</text>')
    # kill marker ~sec 43
    kx=X(43); s.append(f'<line x1="{kx:.0f}" y1="{padt}" x2="{kx:.0f}" y2="{H-padb}" class="kill"/>')
    s.append(f'<text x="{kx+5:.0f}" y="{padt+14}" class="killt">pod killed</text>')
    for lab,col,rows in (sa,sb):
        d=[f"{X(sec):.1f},{ylog(max(p99,1),ymin,ymax,H,padt,padb):.1f}" for (sec,rps,p99,ko) in rows]
        s.append(f'<polyline points="{" ".join(d)}" fill="none" stroke="{col}" stroke-width="2"/>')
    for j,(lab,col,_) in enumerate((sa,sb)):
        ly=padt+14+j*22
        s.append(f'<rect x="{W-padr+10}" y="{ly-9}" width="14" height="4" fill="{col}"/>')
        s.append(f'<text x="{W-padr+30}" y="{ly-4}" class="leg">{lab}</text>')
    s.append(f'<text x="{(W-padr)/2}" y="{H-12}" class="axis" text-anchor="middle">seconds (160s run, kill at T+43s)</text>')
    s.append(f'<text x="18" y="{H/2}" class="axis" text-anchor="middle" transform="rotate(-90 18 {H/2})">p99 latency (ms, log)</text>')
    s.append('</svg>')
    return "\n".join(s)

# ---------------------------------------------------------------- charts
hero = hbar_log_chart("Auth p99 latency @ 80 logins/sec — the managed-Redis verdict (log scale)", [
  ("Infinispan · in-process (Azure)", 49, "#64748b"),
  ("Infinispan · in-process (AWS)", 84, "#64748b"),
  ("Co-located single redis (Azure)", 50, "#22c55e"),
  ("Co-located 6-node cluster (Azure)", 60, "#10b981"),
  ("ElastiCache · managed OSS (AWS)", 181, "#3b82f6"),
  ("Co-located single redis (AWS)", 241, "#3b82f6"),
  ("Azure Managed Redis · proxy (AMR)", 4940, "#ef4444"),
])

lat_aws = line_chart("p99 latency vs load — AWS EKS rig (250 ups is rig-capped)", [
  ("ElastiCache (managed)","#3b82f6",[(80,181),(160,588),(250,12391)]),
  ("Co-located single redis","#8b5cf6",[(80,241),(160,1042),(250,15536)]),
  ("Infinispan (in-process)","#64748b",[(80,84),(160,125),(250,4626)]),
])
lat_azure = line_chart("p99 latency vs load — Azure rig (separate nodes, holds 250 ups)", [
  ("Co-located single redis","#22c55e",[(80,50),(160,56),(250,103)]),
  ("Co-located 6-node cluster","#10b981",[(80,60),(160,None),(250,63)]),
  ("Infinispan (in-process)","#64748b",[(80,49),(160,47),(250,91)]),
  ("Azure Managed Redis (proxy)","#ef4444",[(80,4940),(160,None),(250,18609)]),
], ymax=50000)

resil = resil_timeline("Failover: p99 over time through a KC pod kill (Azure, 80 ups)",
  ("Locke · co-located redis","#22c55e",TL_LOCKE),
  ("Upstream Infinispan","#ef4444",TL_ISPN))

# ---------------------------------------------------------------- tables
def dist_table(data):
    rows=["<tr><th>Backend</th><th>ups</th><th>rps</th><th>p50</th><th>mean</th><th>p95</th><th>p99</th><th>max</th></tr>"]
    for be,byu in data.items():
        first=True
        for u in (80,160,250):
            d=byu[u]
            label=f'<td rowspan="3" class="be">{be}</td>' if first else ""
            rps=f'{d["rps"]:.0f}'
            cls=' class="knee"' if (u==250 and d["p99"]>2000) else ''
            rows.append(f'<tr{cls}>{label}<td>{u}</td><td>{rps}</td><td>{d["p50"]}</td><td>{d["mean"]}</td><td>{d["p95"]}</td><td><b>{d["p99"]:,}</b></td><td>{d["mx"]:,}</td></tr>')
            first=False
    return '<table class="dist">'+''.join(rows)+'</table>'

verdict_rows = """
<tr><th>Backend</th><th>Architecture</th><th>p99 @ 80 ups</th><th>p99 @ 250 ups</th><th>Throughput @ 250</th><th>Pod-loss p99</th><th>Verdict</th></tr>
<tr><td>Upstream Infinispan</td><td>In-process (JGroups)</td><td>49 ms</td><td>91 ms</td><td>857 rps</td><td class="bad">37,068 ms</td><td class="bad">fast, fails HA</td></tr>
<tr><td>Co-located single redis</td><td>Same-VM, 1 node</td><td>50 ms</td><td>103 ms</td><td>847 rps</td><td class="good">268 ms</td><td class="good">resilient</td></tr>
<tr><td>Co-located 6-node cluster</td><td>Same-VM, 3+3 shards</td><td>60 ms</td><td>63 ms</td><td>856 rps</td><td class="good">53 ms</td><td class="good">best</td></tr>
<tr class="hl"><td>AWS ElastiCache</td><td>Managed OSS, direct-to-shard</td><td>181 ms</td><td>rig-capped*</td><td>rig-capped*</td><td class="good">522 ms</td><td class="good">viable, +latency</td></tr>
<tr><td>Azure Managed Redis</td><td>Managed, single proxy endpoint</td><td class="bad">4,940 ms</td><td class="bad">18,609 ms</td><td class="bad">601 rps</td><td>—</td><td class="bad">avoid (proxy)</td></tr>
"""

# ---------------------------------------------------------------- assemble
html=f"""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Locke — Cross-Backend Cache Comparison</title>
<style>
:root{{--blue:#3b82f6;--bg:#0f172a;--bg2:#1e293b;--card:rgba(255,255,255,.05);--bd:rgba(255,255,255,.1);
--tx:#e2e8f0;--mut:#94a3b8;--good:#22c55e;--bad:#ef4444;--amber:#f59e0b}}
*{{box-sizing:border-box}}
body{{margin:0;font-family:system-ui,-apple-system,Segoe UI,Inter,sans-serif;background:var(--bg);color:var(--tx);line-height:1.6}}
.wrap{{max-width:960px;margin:0 auto;padding:0 20px}}
header{{background:linear-gradient(135deg,#0f172a,#1e293b);padding:64px 0 48px;border-bottom:1px solid var(--bd)}}
.badge{{display:inline-block;font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--blue);
border:1px solid var(--bd);border-radius:999px;padding:5px 14px;margin-bottom:18px}}
h1{{font-size:38px;line-height:1.15;margin:0 0 14px;color:#fff}}
h2{{font-size:26px;margin:48px 0 8px;color:#fff}}
h3{{font-size:18px;margin:28px 0 6px;color:#fff}}
.sub{{color:var(--mut);font-size:18px;max-width:740px}}
.lead{{color:var(--tx);font-size:17px}}
section{{padding:8px 0 12px}}
.card{{background:var(--card);border:1px solid var(--bd);border-radius:14px;padding:22px 24px;margin:16px 0;backdrop-filter:blur(10px)}}
.grid3{{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:18px 0}}
.stat{{background:var(--card);border:1px solid var(--bd);border-radius:14px;padding:20px;backdrop-filter:blur(10px)}}
.stat .n{{font-size:30px;font-weight:700;color:#fff}}
.stat .l{{color:var(--mut);font-size:14px;margin-top:4px}}
.chart{{width:100%;height:auto;background:rgba(0,0,0,.18);border:1px solid var(--bd);border-radius:12px;margin:14px 0}}
.ctitle{{fill:#fff;font:600 16px system-ui}} .axis{{fill:#94a3b8;font:12px system-ui}}
.grid{{stroke:rgba(255,255,255,.08);stroke-width:1}} .blabel{{fill:#cbd5e1;font:13px system-ui}}
.bval{{fill:#fff;font:600 13px system-ui}} .leg{{fill:#cbd5e1;font:12px system-ui}}
.kill{{stroke:var(--amber);stroke-width:1.5;stroke-dasharray:5 4}} .killt{{fill:var(--amber);font:11px system-ui}}
table{{width:100%;border-collapse:collapse;margin:14px 0;font-size:14px}}
th,td{{text-align:left;padding:9px 10px;border-bottom:1px solid var(--bd)}}
th{{color:var(--mut);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.04em}}
td.be{{font-weight:600;color:#fff;vertical-align:top;max-width:230px}}
tr.knee td{{background:rgba(239,68,68,.08)}} tr.hl td{{background:rgba(59,130,246,.10)}}
.good{{color:var(--good)}} .bad{{color:var(--bad)}} b{{color:#fff}}
.note{{border-left:3px solid var(--amber);padding:10px 16px;background:rgba(245,158,11,.07);border-radius:0 8px 8px 0;margin:14px 0;color:#e2e8f0}}
ul{{padding-left:20px}} li{{margin:6px 0}}
code{{background:rgba(255,255,255,.08);padding:2px 6px;border-radius:5px;font-size:13px}}
footer{{color:var(--mut);font-size:13px;padding:34px 0;border-top:1px solid var(--bd);margin-top:40px}}
.foot-rule{{height:3px;background:linear-gradient(90deg,var(--blue),#1e40af);border:none;margin:0}}
</style></head><body>
<header><div class="wrap">
<span class="badge">Locke · Benchmark Report · 2026-06-20</span>
<h1>Where do you run Redis for Locke?</h1>
<p class="sub">A cross-backend comparison of cache options for the Locke (Keycloak-on-Redis) distribution: in-process Infinispan, co-located Redis (single &amp; cluster), and two managed services — AWS ElastiCache and Azure Managed Redis — across throughput, full latency distributions, and failover.</p>
</div></header>
<div class="wrap">

<section>
<h2>The one-paragraph answer</h2>
<p class="lead">Managed Redis is <b>not</b> all the same. AWS ElastiCache (an OSS Redis cluster you talk to <em>directly, shard-by-shard</em>) is a perfectly viable Locke backend — p99 <b>181&nbsp;ms</b> at 80 logins/sec and a clean <b>522&nbsp;ms</b> through a node kill with zero errors. Azure Managed Redis (which funnels every request through a <em>single managed proxy endpoint</em>) is not — p99 <b>4,940&nbsp;ms</b> at the same load, ~27× worse, for this write-heavy, multi-round-trip-per-login workload. Co-located Redis is fastest of the Redis options; in-process Infinispan has the lowest latency of all but stalls for <b>37&nbsp;seconds</b> on node loss — the failure mode Locke exists to remove.</p>
<div class="grid3">
  <div class="stat"><div class="n">~27×</div><div class="l">ElastiCache vs Azure Managed Redis p99 @ 80 ups (181 ms vs 4,940 ms)</div></div>
  <div class="stat"><div class="n">522 ms</div><div class="l">ElastiCache p99 through a KC pod kill — 0 errors</div></div>
  <div class="stat"><div class="n">37,068 ms</div><div class="l">Infinispan failover p99 — the JGroups rebalance stall Locke removes</div></div>
</div>
</section>

<section>
<h2>1 · The managed-Redis verdict</h2>
<p>The motivating question: <em>is a managed Redis service as slow as Azure Managed Redis was?</em> No — and the reason is architecture, not "managed vs self-hosted."</p>
{hero}
<p class="lead" style="margin-top:18px">Everything except Azure Managed Redis sits under ~250&nbsp;ms. AMR is alone at ~5&nbsp;seconds. The difference is the data path: <b>ElastiCache cluster-mode and co-located Redis are direct-to-shard OSS Redis</b> — Locke's client opens one connection per shard and talks straight to it. <b>AMR's working mode funnels all traffic through a single managed proxy endpoint</b>, which amplifies badly under the concurrency of 850 logins/sec each doing several Redis round-trips. Per-operation, AMR is fine (~0.6&nbsp;ms); it's the proxy funnel under load that hurts.</p>
<table>{verdict_rows}</table>
<p class="axis">* AWS ElastiCache 250-ups figures are rig-capped on the test EKS cluster (see §4) and are omitted from the verdict; its 80/160-ups numbers are clean.</p>
</section>

<section>
<h2>2 · Full latency distributions</h2>
<p>Every backend, every load point — not just the worst case. Captured from Gatling's Global Information block (50–60s steady-state per point, 3 KC pods). p50/mean/p95/p99/max in milliseconds, rps = achieved throughput.</p>
<h3>AWS EKS rig</h3>
{dist_table(AWS)}
<h3>Azure rig</h3>
{dist_table(AZURE)}
<p class="axis">Rows shaded red are past the rig's saturation knee — see §4. Azure 6-node cluster (p99 60/–/63 ms) and AMR (p99 4,940/–/18,609 ms) were captured as p99-only summary points, not full distributions.</p>
</section>

<section>
<h2>3 · Latency vs load</h2>
<p>At 80 and 160 logins/sec every backend scales linearly to full throughput (273→547 rps) — the difference is purely latency. The shapes diverge as load climbs.</p>
{lat_aws}
{lat_azure}
<p class="lead">On the Azure rig (postgres and Redis on separate dedicated nodes) both Locke and Infinispan hold 250 ups at ~850 rps and p99 ≈ 100 ms. On the AWS rig they knee at 250 — which is the rig, not the backend (next section).</p>
</section>

<section>
<h2>4 · Apples-to-apples honesty</h2>
<div class="note"><b>What's clean and what isn't.</b> The two rigs are not identical, so we only draw backend conclusions where the rig isn't the bottleneck.</div>
<ul>
<li><b>The AWS 250-ups cliff is a rig artifact, not a backend property.</b> On the AWS EKS cluster, postgres shares one node with the loadgen/infra tier and its write-ahead log sits on EBS gp3. At 250 ups (~850 session-persist writes/sec) <em>every</em> backend knees — including in-process Infinispan (rps 760, p99 4.6&nbsp;s), which touches no Redis at all. On the Azure rig, with postgres on its own dedicated node, the identical software holds 250 ups at ~850 rps / 91&nbsp;ms. Same code, different storage IOPS. We therefore compare backends at <b>80 and 160 ups</b>, below that knee.</li>
<li><b>Topologies differ in size.</b> "Co-located single redis" is one Redis thread; ElastiCache here is a 3-shard cluster; the Azure cluster is 6 nodes. A bigger deployment naturally absorbs more — that's why the 3-shard ElastiCache beats the single co-located Redis at 160 ups. It's a "what you'd actually deploy" comparison, not a same-size topology shoot-out.</li>
<li><b>Cross-cloud, and not the same CPU.</b> ElastiCache ran on AWS; AMR and the Azure co-located reference ran on Azure. Both clouds used x86-64 with matched vCPU counts (16 KC / 8 aux) and the identical amd64 images, but <em>different silicon</em>: Azure E-series <code>as_v7</code> is <b>AMD EPYC</b>, AWS <code>c6i</code> is <b>Intel Xeon (Ice Lake)</b>. So cross-cloud absolute latencies carry three confounds at once — CPU vendor, storage (Azure premium SSD vs AWS EBS gp3), and node placement (separate vs shared infra node). The managed-proxy-vs-direct-shard contrast is architectural and dwarfs these; the clean same-CPU backend comparison is the AWS-only set at 80/160 ups.</li>
<li><b>Workload.</b> keycloak-benchmark Gatling AuthorizationCode (full login → code → token). Steady-state authentication only — the harness does <em>not</em> continuously create users while authenticating. 3 KC pods, <code>start --optimized</code>, Keycloak 26.6.x.</li>
</ul>
</section>

<section>
<h2>5 · Resilience — the reason Locke exists</h2>
<p>Throughput parity and a few ms of latency are the price; resilience is what you buy. A single KC pod is killed mid-run at T+43s.</p>
{resil}
<p class="lead">Infinispan's in-process cache must rebalance its JGroups cluster on membership change: p99 jumps to <b>37&nbsp;seconds</b> and stays elevated for ~65s before recovering — a 0.68% error window. Locke holds p99 at <b>268&nbsp;ms</b> (Azure co-located) / <b>522&nbsp;ms</b> (AWS ElastiCache) with effectively zero errors: cache state lives in Redis, so losing a stateless KC pod is a non-event. That's a ~140× smaller failover impact.</p>
</section>

<section>
<h2>6 · Recommendations</h2>
<ul>
<li><b>Best latency + resilience:</b> co-located Redis (a small cluster on the same nodes/VPC). Sub-100&nbsp;ms p99 to 250 ups and sub-100&nbsp;ms failover.</li>
<li><b>Already run managed Redis?</b> AWS ElastiCache (or any OSS cluster-mode service you talk to directly) is viable. Budget the network-hop latency (p50 ~5→55&nbsp;ms vs in-process) and size the tier for your peak — and keep Redis in the same VPC/AZ as Keycloak.</li>
<li><b>Avoid single-proxy-endpoint managed Redis</b> (Azure Managed Redis in its OSS/Enterprise-cluster working mode) for this workload — the proxy funnel amplifies multi-round-trip logins into seconds of p99.</li>
<li><b>Don't keep Infinispan for HA.</b> It's the latency floor but a 37-second failover stall is disqualifying for zero-downtime operation — which is the whole point of Locke.</li>
</ul>
</section>

</div>
<hr class="foot-rule">
<footer><div class="wrap">Locke · Skycloak · Apache-2.0 · keycloak-benchmark Gatling AuthorizationCode · Azure (E16/E8) &amp; AWS EKS (c6i.4xlarge/c6i.2xlarge) · figures are from the runs in <code>benchmark/k8s/results/</code>.</div></footer>
</body></html>"""

with open(OUT,"w") as f: f.write(html)
print("wrote", OUT, os.path.getsize(OUT), "bytes")
