"""Build hook: the Project section, rendered from docs/project/plan.yaml.

Each page under docs/project/ is a stub holding a marker, `<!-- plan:NAME -->`.
This hook validates the plan once per build, then swaps each marker for the
Markdown that RENDERERS[NAME] writes. It runs before hooks.py, so the links it
writes get the same treatment as hand-written ones: strict link and anchor
checks, and the GitHub rewrite for files the site doesn't render.

A plan that fails its schema, or names an id that isn't an item, fails the build.

Times are stored in UTC and shown in America/New_York. Estimates are shown only
as labelled ranges with their basis; nothing open is drawn on a dated axis.
"""

import json
import os
import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import jsonschema
import yaml
from mkdocs.exceptions import PluginError

ZONE = ZoneInfo("America/New_York")
ZONE_NOTE = "Times are in America/New_York (ET)."
_MARKER = re.compile(r"<!--\s*plan:([a-z-]+)\s*-->")

_plan: dict = {}


# ── Loading and validation ───────────────────────────────────────────────


def _plan_dir(config) -> str:
    return os.path.join(config["docs_dir"], "project")


def _check_ids(plan: dict) -> list[str]:
    """What the schema can't say: every id the plan names is an item."""
    items, problems = plan["items"], []
    order = plan["order"]
    for id in order:
        if id not in items:
            problems.append(f"order names {id!r}, which is not an item")
    for id in items:
        if id not in order:
            problems.append(f"item {id!r} is missing from order")
    for id, item in items.items():
        for dep in item.get("depends_on", []):
            if dep not in items:
                problems.append(f"{id}.depends_on names {dep!r}, which is not an item")
        parent = item.get("parent")
        if parent is not None and parent not in items:
            problems.append(f"{id}.parent names {parent!r}, which is not an item")
        if item["status"] not in plan["columns"]:
            problems.append(f"{id}.status {item['status']!r} has no column")
    for status in plan["column_order"]:
        if status not in plan["columns"]:
            problems.append(f"column_order names {status!r}, which is not a column")
    return problems


def on_pre_build(config, **kwargs) -> None:
    plan_dir = _plan_dir(config)
    with open(os.path.join(plan_dir, "plan.schema.json"), encoding="utf-8") as f:
        schema = json.load(f)
    with open(os.path.join(plan_dir, "plan.yaml"), encoding="utf-8") as f:
        plan = yaml.safe_load(f)

    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(plan), key=lambda e: list(e.absolute_path))
    problems = [f"{'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}" for e in errors]
    if not problems:
        problems = _check_ids(plan)
    if problems:
        raise PluginError("docs/project/plan.yaml is invalid:\n  " + "\n  ".join(problems))
    _plan.clear()
    _plan.update(plan)


# ── Formatting ───────────────────────────────────────────────────────────


def _time(stamp: str | None) -> datetime | None:
    if stamp is None:
        return None
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(timezone.utc)


def _et(stamp: str | None) -> str:
    t = _time(stamp)
    return t.astimezone(ZONE).strftime("%Y-%m-%d %H:%M %Z") if t else "—"


def _duration(item: dict) -> str:
    start, end = _time(item.get("started")), _time(item.get("landed"))
    if start is None or end is None:
        return "—"
    minutes = (end - start).total_seconds() / 60
    if minutes < 60:
        return f"{minutes:.0f} min"
    if minutes < 48 * 60:
        return f"{minutes / 60:.1f} h"
    return f"{minutes / 60 / 24:.1f} days"


def _estimate(item: dict) -> str:
    e = item.get("estimate")
    if e is None:
        return "—"
    low, high = f"{e['low']:g}", f"{e['high']:g}"
    text = f"Estimate: {low}–{high} {e['unit']}"
    if "confidence" in e:
        text += f", {e['confidence']} confidence"
    return text


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _link(title: str, url: str) -> str:
    return f"[{title}]({url})"


def _item_link(id: str) -> str:
    """The item's title, linked to its first link if it has one."""
    item = _plan["items"][id]
    links = item.get("links", [])
    return _link(item["title"], links[0]["url"]) if links else item["title"]


def _links(item: dict) -> str:
    return ", ".join(_link(l["title"], l["url"]) for l in item.get("links", [])) or "—"


def _status(status: str) -> str:
    return _plan["columns"][status]["title"]


def _ordered(predicate=lambda id, item: True) -> list[str]:
    return [id for id in _plan["order"] if predicate(id, _plan["items"][id])]


def _mermaid_id(id: str) -> str:
    return id.replace("-", "_")


def _mermaid_text(text: str) -> str:
    return text.replace('"', "#quot;")


# ── Pages ────────────────────────────────────────────────────────────────


def _waiting_on_owner() -> str:
    lines = []
    for status in _plan["column_order"]:
        owner = _plan["columns"][status].get("owner")
        if owner is None:
            continue
        ids = _ordered(lambda id, item: item["status"] == status)
        title = _plan["columns"][status]["title"]
        lines.append(f'!!! warning "{title} ({len(ids)})"\n')
        if not ids:
            lines.append("    Nothing.\n")
        for id in ids:
            summary = _plan["items"][id].get("summary")
            lines.append(f"    - {_item_link(id)}" + (f": {summary}" if summary else ""))
        lines.append("")
    return "\n".join(lines)


def render_overview() -> str:
    out = [_waiting_on_owner()]
    out.append(f"As of **{_et(_plan['as_of'])}**, when the plan was last reconciled with GitHub. {ZONE_NOTE}\n")
    sources = _plan.get("sources", [])
    if sources:
        out.append("Sources: " + ", ".join(_link(s["title"], s["url"]) for s in sources) + ".\n")

    out.append("## Counts\n")
    out.append("| Status | Items |\n|---|---:|")
    for status in _plan["column_order"]:
        n = len(_ordered(lambda id, item: item["status"] == status))
        out.append(f"| {_status(status)} | {n} |")
    out.append(f"| **All** | **{len(_plan['order'])}** |\n")

    out.append("## Board\n")
    out.append('<div class="grid cards" markdown>\n')
    for status in _plan["column_order"]:
        ids = _ordered(lambda id, item: item["status"] == status)
        out.append(f"-   **{_status(status)}** · {len(ids)}\n\n    ---\n")
        if not ids:
            out.append("    Nothing here.\n")
        for id in ids:
            out.append(f"    - {_item_link(id)}")
        out.append("")
    out.append("</div>\n")
    return "\n".join(out)


def render_timeline() -> str:
    out = [f"{ZONE_NOTE} Durations run from start to merge.\n"]

    done = _ordered(lambda id, item: item["status"] == "done")
    dated = [id for id in done if "started" in _plan["items"][id]]
    out.append("## Done\n")
    if dated:
        out.append("Only finished work, on the dates it ran. Open work has no bar: its estimates are below.\n")
        out.append("```mermaid\ngantt\n    dateFormat YYYY-MM-DD HH:mm\n    axisFormat %m-%d %H:%M\n    section Done")
        for id in dated:
            item = _plan["items"][id]
            start = _time(item["started"]).astimezone(ZONE).strftime("%Y-%m-%d %H:%M")
            end = _time(item["landed"]).astimezone(ZONE).strftime("%Y-%m-%d %H:%M")
            label = re.sub(r"[:;#]", " ", item["title"])
            out.append(f"    {label} : {_mermaid_id(id)}, {start}, {end}")
        out.append("```\n")
    out.append("| Item | Started | Landed | Took |\n|---|---|---|---|")
    for id in done:
        item = _plan["items"][id]
        out.append(f"| {_cell(_item_link(id))} | {_et(item.get('started'))} | {_et(item.get('landed'))} | {_duration(item)} |")
    out.append("")

    out.append("## Open\n")
    out.append("Each estimate is a range of wall-clock time from start to merge, leaving out time waiting on Iain.\n")
    for status in _plan["column_order"]:
        if status == "done":
            continue
        ids = _ordered(lambda id, item: item["status"] == status)
        if not ids:
            continue
        out.append(f"### {_status(status)}\n")
        for id in ids:
            item = _plan["items"][id]
            facts = [f"Started {_et(item['started'])}" if "started" in item else "Not started"]
            if "estimate" in item:
                facts.append(_estimate(item))
            out.append(f"- **{_item_link(id)}**. " + ". ".join(facts) + ".")
            if "estimate" in item:
                out.append(f"  Basis: {item['estimate']['basis']}")
        out.append("")
    return "\n".join(out)


def render_dependencies() -> str:
    styles = {
        "done": "fill:#c8e6c9,stroke:#2e7d32,color:#1b1b1b",
        "in-flight": "fill:#bbdefb,stroke:#1565c0,color:#1b1b1b",
        "waiting-on-iain": "fill:#ffe0b2,stroke:#ef6c00,color:#1b1b1b",
        "blocked": "fill:#ffcdd2,stroke:#c62828,color:#1b1b1b",
        "next": "fill:#fff9c4,stroke:#f9a825,color:#1b1b1b",
        "later": "fill:#eeeeee,stroke:#757575,color:#1b1b1b",
    }
    out = [
        "A solid arrow points from what must finish to what waits on it. A dotted arrow points from a part to the item it belongs to. "
        "Colour shows status; a node with a GitHub link opens it.\n",
        "```mermaid\nflowchart TD",
    ]
    # One box per group of ids, so the graph reads as plan, bee, kuilt, outside.
    groups = {"task": "The v1 plan", "bee": "bee PRs and issues", "kuilt": "kuilt", "out": "Outside the code"}
    for prefix, label in groups.items():
        ids = [id for id in _plan["order"] if id.split("-")[0] == prefix]
        if not ids:
            continue
        out.append(f'    subgraph {prefix}_group["{label}"]')
        for id in ids:
            item = _plan["items"][id]
            out.append(f'        {_mermaid_id(id)}["{_mermaid_text(item["title"])}"]:::{_mermaid_id(item["status"])}')
        out.append("    end")
    for id in _plan["order"]:
        item = _plan["items"][id]
        for dep in item.get("depends_on", []):
            out.append(f"    {_mermaid_id(dep)} --> {_mermaid_id(id)}")
        if "parent" in item:
            out.append(f"    {_mermaid_id(id)} -.-> {_mermaid_id(item['parent'])}")
    for id in _plan["order"]:
        for l in _plan["items"][id].get("links", []):
            if l["url"].startswith("https://"):
                out.append(f'    click {_mermaid_id(id)} href "{l["url"]}" _blank')
                break
    for status in _plan["column_order"]:
        out.append(f"    classDef {_mermaid_id(status)} {styles.get(status, '')}")
    out.append("```\n")

    out.append("| Colour | Status |\n|---|---|")
    for status in _plan["column_order"]:
        fill = styles.get(status, "").split(",")[0].removeprefix("fill:")
        out.append(f'| <span style="display:inline-block;width:1em;height:1em;background:{fill};border:1px solid #888"></span> | {_status(status)} |')
    out.append("")
    return "\n".join(out)


def _ruling(r: dict) -> str:
    text = f"Ruled {r['date']}: {r['summary']}"
    return text + (f" ({_link('ruling', r['url'])})" if "url" in r else "")


def render_decisions() -> str:
    out = []
    decisions = _ordered(lambda id, item: item["kind"] == "decision")
    out.append("## Decisions\n")
    for id in decisions:
        item = _plan["items"][id]
        out.append(f"### {item['title']}\n")
        out.append(f"{_status(item['status'])}. {_links(item)}\n")
        out.append((_ruling(item["ruling"]) if "ruling" in item else "Not ruled yet.") + "\n")
    others = _ordered(lambda id, item: item["kind"] != "decision" and "ruling" in item)
    if others:
        out.append("## Rulings on other items\n")
        for id in others:
            out.append(f"### {_plan['items'][id]['title']}\n")
            out.append(f"{_item_link(id)}. {_ruling(_plan['items'][id]['ruling'])}\n")
    return "\n".join(out)


def render_kuilt() -> str:
    items = _plan["items"]
    kuilt = _ordered(lambda id, item: item["kind"] == "kuilt")
    needed_by = {id: [w for w in _plan["order"] if id in items[w].get("depends_on", []) and items[w]["kind"] != "kuilt"] for id in kuilt}

    def row(id: str) -> str:
        item = items[id]
        waits = ", ".join(_item_link(w) for w in needed_by[id]) or "—"
        return f"| {_cell(_item_link(id))} | {_status(item['status'])} | {_cell(item.get('summary', ''))} | {_cell(waits)} |"

    header = "| Item | Status | Summary | bee waits on it |\n|---|---|---|---|"
    out = ["kuilt work bee depends on, grouped under its epic. A gap in kuilt is a kuilt issue, never a local patch.\n"]
    epics = [id for id in kuilt if "parent" not in items[id] and any(items[c].get("parent") == id for c in kuilt)]
    for epic in epics:
        item = items[epic]
        out.append(f"## {item['title']}\n")
        out.append(f"{_item_link(epic)}. {_status(item['status'])}. {item.get('summary', '')}\n")
        if "estimate" in item:
            out.append(f"{_estimate(item)}. Basis: {item['estimate']['basis']}\n")
        out.append(header)
        out.extend(row(c) for c in kuilt if items[c].get("parent") == epic)
        out.append("")
    loose = [id for id in kuilt if id not in epics and items[id].get("parent") not in epics]
    if loose:
        out.append("## Outside an epic\n")
        out.append(header)
        out.extend(row(id) for id in loose)
        out.append("")
    return "\n".join(out)


RENDERERS = {
    "overview": render_overview,
    "timeline": render_timeline,
    "dependencies": render_dependencies,
    "decisions": render_decisions,
    "kuilt": render_kuilt,
}


def on_page_markdown(markdown: str, page, config, files, **kwargs) -> str:
    def replace(m: re.Match) -> str:
        name = m.group(1)
        if name not in RENDERERS:
            raise PluginError(f"{page.file.src_uri}: no plan renderer named {name!r}")
        return RENDERERS[name]()

    return _MARKER.sub(replace, markdown)
