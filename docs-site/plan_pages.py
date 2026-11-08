"""Build hook: the Project section, rendered from docs/project/plan.yaml.

Each page under docs/project/ is a stub holding a marker, `<!-- plan:NAME -->`.
This hook validates the plan once per build, then swaps each marker for the
Markdown that RENDERERS[NAME] writes. It runs before hooks.py, so the links it
writes get the same treatment as hand-written ones: strict link and anchor
checks, and the GitHub rewrite for files the site doesn't render.

A plan that fails its schema or any check in _check fails the build.

Times are stored in UTC and shown in America/New_York. Estimates are shown only
as labelled ranges with their basis; nothing open is drawn on a dated axis.
"""

import graphlib
import html
import json
import os
import re
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import jsonschema
import yaml
from mkdocs.exceptions import PluginError

ZONE = ZoneInfo("America/New_York")
ZONE_NOTE = "Times are in America/New_York (ET)."
_MARKER = re.compile(r"<!--\s*plan:([a-z-]+)\s*-->")

_plan: dict = {}


# ── Loading and validation ───────────────────────────────────────────────


class _UniqueKeyLoader(yaml.SafeLoader):
    """SafeLoader that refuses a key repeated in one mapping, which YAML would let the last win."""

    def construct_mapping(self, node, deep=False):
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in seen:
                raise yaml.constructor.ConstructorError(
                    "while constructing a mapping", node.start_mark, f"found duplicate key {key!r}", key_node.start_mark
                )
            seen.add(key)
        return super().construct_mapping(node, deep)


def _parse_time(stamp: str) -> datetime:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(timezone.utc)


def _texts(plan: dict):
    """Every free-text field, with where it is, for the one-line check."""
    for status, column in plan["columns"].items():
        yield f"columns.{status}.title", column["title"]
    for link in plan.get("sources", []):
        yield "sources.title", link["title"]
    for id, item in plan["items"].items():
        yield f"{id}.title", item["title"]
        if "summary" in item:
            yield f"{id}.summary", item["summary"]
        if "estimate" in item:
            yield f"{id}.estimate.basis", item["estimate"]["basis"]
        if "ruling" in item:
            yield f"{id}.ruling.summary", item["ruling"]["summary"]
        for link in item.get("links", []):
            yield f"{id}.links.title", link["title"]


def _check(plan: dict) -> list[str]:
    """What the schema can't say. Runs only on a plan that passed the schema."""
    items, order, problems = plan["items"], plan["order"], []

    if set(plan["column_order"]) != set(plan["columns"]):
        problems.append(f"column_order {sorted(plan['column_order'])} and columns {sorted(plan['columns'])} name different statuses")
    for id in order:
        if id not in items:
            problems.append(f"order names {id!r}, which is not an item")
    for id in items:
        if id not in order:
            problems.append(f"item {id!r} is missing from order")

    for where, text in _texts(plan):
        if "\n" in text or "\r" in text:
            problems.append(f"{where} spans more than one line")

    try:
        _parse_time(plan["as_of"])
    except ValueError as e:
        problems.append(f"as_of {plan['as_of']!r} is not a real time: {e}")

    for id, item in items.items():
        for dep in item.get("depends_on", []):
            if dep not in items:
                problems.append(f"{id}.depends_on names {dep!r}, which is not an item")
        parent = item.get("parent")
        if parent is not None:
            if parent not in items:
                problems.append(f"{id}.parent names {parent!r}, which is not an item")
            elif id in items[parent].get("depends_on", []):
                problems.append(f"{parent}.depends_on lists its own child {id!r}; a parent depends on its children by definition")
        if item["status"] not in plan["columns"]:
            problems.append(f"{id}.status {item['status']!r} has no column")
        if "landed" in item and item["status"] != "done":
            problems.append(f"{id} has landed but its status is {item['status']!r}, not 'done'")

        times = {}
        for field in ("started", "landed"):
            if field in item:
                try:
                    times[field] = _parse_time(item[field])
                except ValueError as e:
                    problems.append(f"{id}.{field} {item[field]!r} is not a real time: {e}")
        if len(times) == 2 and times["landed"] < times["started"]:
            problems.append(f"{id} landed ({item['landed']}) before it started ({item['started']})")
        if "ruling" in item:
            try:
                date.fromisoformat(item["ruling"]["date"])
            except ValueError as e:
                problems.append(f"{id}.ruling.date {item['ruling']['date']!r} is not a real date: {e}")
        estimate = item.get("estimate")
        if estimate is not None and estimate["low"] > estimate["high"]:
            problems.append(f"{id}.estimate runs from {estimate['low']:g} down to {estimate['high']:g}")

    graph = {id: [d for d in item.get("depends_on", []) if d in items] for id, item in items.items()}
    try:
        graphlib.TopologicalSorter(graph).prepare()
    except graphlib.CycleError as e:
        problems.append("depends_on has a cycle: " + " → ".join(reversed(e.args[1])))

    reported = set()
    for id in items:
        chain, seen = [id], {id}
        while (parent := items[chain[-1]].get("parent")) in items:
            if parent in seen:
                cycle = chain[chain.index(parent):] + [parent]
                if frozenset(cycle) not in reported:
                    reported.add(frozenset(cycle))
                    problems.append("parent has a cycle: " + " → ".join(cycle))
                break
            chain.append(parent)
            seen.add(parent)
    return problems


def on_pre_build(config, **kwargs) -> None:
    plan_dir = os.path.join(config["docs_dir"], "project")
    with open(os.path.join(plan_dir, "plan.schema.json"), encoding="utf-8") as f:
        schema = json.load(f)
    try:
        with open(os.path.join(plan_dir, "plan.yaml"), encoding="utf-8") as f:
            plan = yaml.load(f, Loader=_UniqueKeyLoader)
    except yaml.YAMLError as e:
        raise PluginError(f"docs/project/plan.yaml does not load:\n{e}") from e

    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(plan), key=lambda e: list(map(str, e.absolute_path)))
    problems = [f"{'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}" for e in errors]
    if not problems:
        problems = _check(plan)
    if problems:
        raise PluginError("docs/project/plan.yaml is invalid:\n  " + "\n  ".join(problems))
    _plan.clear()
    _plan.update(plan)


# ── Formatting ───────────────────────────────────────────────────────────

# Python-Markdown's escapable punctuation, plus `|` for table cells.
_MARKDOWN_PUNCTUATION = re.compile(r"([\\`*_{}\[\]()#+\-.!|])")


def _text(text: str) -> str:
    """A title or summary as literal Markdown: no HTML, no emphasis, no links."""
    return _MARKDOWN_PUNCTUATION.sub(r"\\\1", html.escape(text, quote=False))


def _time(stamp: str | None) -> datetime | None:
    return _parse_time(stamp) if stamp is not None else None


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
    text = f"Estimate: {e['low']:g}–{e['high']:g} {e['unit']}"
    if "confidence" in e:
        text += f", {e['confidence']} confidence"
    return text


def _cell(text: str) -> str:
    return text.replace("|", "\\|")


def _link(title: str, url: str) -> str:
    target = f"<{url}>" if re.search(r"[\s()<>]", url) else url
    return f"[{_text(title)}]({target})"


def _item_link(id: str) -> str:
    """The item's title, linked to its first link if it has one."""
    item = _plan["items"][id]
    links = item.get("links", [])
    return _link(item["title"], links[0]["url"]) if links else _text(item["title"])


def _links(item: dict) -> str:
    return ", ".join(_link(l["title"], l["url"]) for l in item.get("links", [])) or "—"


def _status(status: str) -> str:
    return _text(_plan["columns"][status]["title"])


def _ordered(predicate) -> list[str]:
    return [id for id in _plan["order"] if predicate(id, _plan["items"][id])]


def _mermaid_id(id: str) -> str:
    return id.replace("-", "_")


def _mermaid_text(text: str) -> str:
    """A label inside Mermaid's double quotes, with its entity codes."""
    return text.replace("#", "#35;").replace('"', "#quot;").replace("<", "#lt;").replace(">", "#gt;")


# ── Pages ────────────────────────────────────────────────────────────────


def _waiting_on_owner() -> str:
    lines = []
    for status in _plan["column_order"]:
        if _plan["columns"][status].get("owner") is None:
            continue
        ids = _ordered(lambda id, item: item["status"] == status)
        lines.append(f'!!! warning "{_status(status)} ({len(ids)})"\n')
        if not ids:
            lines.append("    Nothing.\n")
        for id in ids:
            summary = _plan["items"][id].get("summary")
            lines.append(f"    - {_item_link(id)}" + (f": {_text(summary)}" if summary else ""))
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
        out.append(f"| {_cell(_status(status))} | {n} |")
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


def _gantt_time(t: datetime) -> str:
    return t.strftime("%Y-%m-%d %H:%M")


def render_timeline() -> str:
    out = [f"{ZONE_NOTE} Durations run from start to merge, or for a decision, to its ruling.\n"]

    done = _ordered(lambda id, item: item["status"] == "done")
    dated = [id for id in done if "started" in _plan["items"][id]]
    out.append("## Done\n")
    if dated:
        out.append("Only finished work, on the dates it ran. Open work has no bar: its estimates are below.\n")
        out.append("```mermaid\ngantt\n    dateFormat YYYY-MM-DD HH:mm\n    axisFormat %m-%d %H:%M\n    section Done")
        for id in dated:
            item = _plan["items"][id]
            started, landed = _time(item["started"]), _time(item["landed"])
            # Wall-clock ET start plus the real elapsed time, so a run across the
            # fall-back hour never ends before it starts.
            start = started.astimezone(ZONE).replace(tzinfo=None)
            end = start + (landed - started)
            label = re.sub(r"[:;#]", " ", item["title"])
            out.append(f"    {label} : {_mermaid_id(id)}, {_gantt_time(start)}, {_gantt_time(end)}")
        out.append("```\n")
    out.append("| Item | Started | Landed | Took | Links |\n|---|---|---|---|---|")
    for id in done:
        item = _plan["items"][id]
        out.append(
            f"| {_cell(_text(item['title']))} | {_et(item.get('started'))} | {_et(item.get('landed'))} "
            f"| {_duration(item)} | {_cell(_links(item))} |"
        )
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
            # No start time means none was recorded, not that work hasn't begun.
            facts = [f"Started {_et(item['started'])}"] if "started" in item else []
            if "estimate" in item:
                facts.append(_estimate(item))
            lines = [f"**{_text(item['title'])}**" + "".join(f". {f}" for f in facts) + ("." if facts else "")]
            if "estimate" in item:
                lines.append(f"Basis: {_text(item['estimate']['basis'])}")
            if item.get("links"):
                lines.append(f"Links: {_links(item)}")
            out.append("- " + "  \n  ".join(lines))
        out.append("")
    return "\n".join(out)


_STYLES = {
    "done": "fill:#c8e6c9,stroke:#2e7d32,color:#1b1b1b",
    "in-flight": "fill:#bbdefb,stroke:#1565c0,color:#1b1b1b",
    "waiting-on-iain": "fill:#ffe0b2,stroke:#ef6c00,color:#1b1b1b",
    "blocked": "fill:#ffcdd2,stroke:#c62828,color:#1b1b1b",
    "next": "fill:#fff9c4,stroke:#f9a825,color:#1b1b1b",
    "later": "fill:#eeeeee,stroke:#757575,color:#1b1b1b",
}


def render_dependencies() -> str:
    items, order = _plan["items"], _plan["order"]
    children = {id: [c for c in order if items[c].get("parent") == id] for id in order}
    waiters = {id: [w for w in order if id in items[w].get("depends_on", [])] for id in order}

    # A done item no open item waits on folds into one node; nothing is lost but its name.
    folded = {
        id for id in order
        if items[id]["status"] == "done" and not any(items[w]["status"] != "done" for w in waiters[id])
    }
    # An item with no edge at all, and no parent or child, is listed under the graph instead.
    isolated = [
        id for id in order
        if id not in folded and not items[id].get("depends_on") and not waiters[id]
        and "parent" not in items[id] and not children[id]
    ]
    shown = [id for id in order if id not in folded and id not in isolated]
    shown_set = set(shown)

    def node(id: str) -> str:
        return _mermaid_id(id) if id in shown_set else "done_folded"

    out = [
        "A solid arrow points from what must finish to what waits on it. A box holds an item's parts. "
        f"Colour shows status. Finished work that nothing open waits on is folded into one **Done ({len(folded)})** node; "
        "an item with a GitHub link opens it.\n",
        "```mermaid\nflowchart LR",
    ]
    if folded:
        out.append(f'    done_folded["Done ({len(folded)})"]:::done')

    def emit(id: str, indent: str) -> None:
        item = items[id]
        kids = [c for c in children[id] if c in shown_set]
        if kids:
            out.append(f'{indent}subgraph {_mermaid_id(id)}["{_mermaid_text(item["title"])}"]')
            for c in kids:
                emit(c, indent + "    ")
            out.append(f"{indent}end")
            out.append(f"{indent}style {_mermaid_id(id)} {_STYLES[item['status']]}")
        else:
            out.append(f'{indent}{_mermaid_id(id)}["{_mermaid_text(item["title"])}"]:::{_mermaid_id(item["status"])}')

    for id in shown:
        if items[id].get("parent") not in shown_set:
            emit(id, "    ")

    edges = []
    for id in shown:
        for dep in items[id].get("depends_on", []):
            edge = f"    {node(dep)} --> {_mermaid_id(id)}"
            if edge not in edges:
                edges.append(edge)
    out.extend(edges)
    for id in shown:
        if children[id] and any(c in shown_set for c in children[id]):
            continue
        for l in items[id].get("links", []):
            if l["url"].startswith("https://"):
                out.append(f'    click {_mermaid_id(id)} href "{l["url"]}" _blank')
                break
    for status in _plan["column_order"]:
        out.append(f"    classDef {_mermaid_id(status)} {_STYLES[status]}")
    out.append("```\n")

    out.append("| Colour | Status |\n|---|---|")
    for status in _plan["column_order"]:
        fill = _STYLES[status].split(",")[0].removeprefix("fill:")
        out.append(f'| <span style="display:inline-block;width:1em;height:1em;background:{fill};border:1px solid #888"></span> | {_cell(_status(status))} |')
    out.append("")

    if folded:
        out.append(f"**Done ({len(folded)})** holds: " + ", ".join(_item_link(id) for id in order if id in folded) + ".\n")
    if isolated:
        out.append("No recorded dependencies: " + ", ".join(f"{_item_link(id)} ({_status(items[id]['status'])})" for id in isolated) + ".\n")
    return "\n".join(out)


def _ruling(r: dict) -> str:
    text = f"Ruled {r['date']}: {_text(r['summary'])}"
    return text + (f" ({_link('ruling', r['url'])})" if "url" in r else "")


def render_decisions() -> str:
    out = []
    decisions = _ordered(lambda id, item: item["kind"] == "decision")
    out.append("## Decisions\n")
    for id in decisions:
        item = _plan["items"][id]
        out.append(f"### {_text(item['title'])}\n")
        out.append(f"{_status(item['status'])}. {_links(item)}\n")
        out.append((_ruling(item["ruling"]) if "ruling" in item else "Not ruled yet.") + "\n")
    others = _ordered(lambda id, item: item["kind"] != "decision" and "ruling" in item)
    if others:
        out.append("## Rulings on other items\n")
        for id in others:
            out.append(f"### {_text(_plan['items'][id]['title'])}\n")
            out.append(f"{_item_link(id)}. {_ruling(_plan['items'][id]['ruling'])}\n")
    return "\n".join(out)


def render_kuilt() -> str:
    items = _plan["items"]
    kuilt = _ordered(lambda id, item: item["kind"] == "kuilt")
    needed_by = {
        id: [w for w in _plan["order"] if id in items[w].get("depends_on", []) and items[w]["kind"] != "kuilt"]
        for id in kuilt
    }

    def row(id: str) -> str:
        item = items[id]
        waits = ", ".join(_item_link(w) for w in needed_by[id]) or "—"
        return f"| {_cell(_item_link(id))} | {_cell(_status(item['status']))} | {_cell(_text(item.get('summary', '')))} | {_cell(waits)} |"

    header = "| Item | Status | Summary | bee waits on it |\n|---|---|---|---|"
    out = ["kuilt work bee depends on, grouped under its epic. A gap in kuilt is a kuilt issue, never a local patch.\n"]
    epics = [id for id in kuilt if "parent" not in items[id] and any(items[c].get("parent") == id for c in kuilt)]
    for epic in epics:
        item = items[epic]
        out.append(f"## {_text(item['title'])}\n")
        out.append(f"{_item_link(epic)}. {_status(item['status'])}. {_text(item.get('summary', ''))}\n")
        if "estimate" in item:
            out.append(f"{_estimate(item)}. Basis: {_text(item['estimate']['basis'])}\n")
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
