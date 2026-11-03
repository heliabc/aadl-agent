"""Bounded, read-only views of a rejected candidate for a repair prompt.

This module selects existing source, never a proposed correction. Grammar names
and binding fields come from the installed rule bundle; no language constructs
or reference answers are built into the context selector.
"""
from __future__ import annotations

from bisect import bisect_right
from copy import deepcopy
import hashlib
import json


def _size(value: dict) -> int:
    # Count UTF-8 JSON, including escaping and ordinary JSON spacing. The
    # pipeline's compact JSON serialization is no larger than this measurement.
    return len(json.dumps(value, ensure_ascii=False).encode("utf-8"))


def _ranges(values: list[tuple[int, int]]) -> list[list[int]]:
    result = []
    for first, last in sorted(values):
        if result and first <= result[-1][1] + 1:
            result[-1][1] = max(last, result[-1][1])
        else:
            result.append([first, last])
    return result


def _refresh(source: dict) -> None:
    represented = _ranges([(f["start_line"], f["end_line"]) for f in source["fragments"]])
    source["selected_ranges"] = represented
    missing, previous = [], 1
    for first, last in represented:
        if previous < first:
            missing.append([previous, first - 1])
        previous = last + 1
    if previous <= source["original_lines"]:
        missing.append([previous, source["original_lines"]])
    source["omitted_ranges"] = missing


def _fragment(lines: list[str], first: int, last: int, kind: str,
              *, limit: int | None = None, **metadata) -> dict:
    records, index = [], first
    while index <= last:
        text = lines[index - 1]
        shown = text if limit is None else text[:limit]
        item = {"line": index, "text": shown}
        if shown != text:
            item.update(original_characters=len(text), omitted_character_range=[len(shown), len(text)])
        records.append(item)
        # Only exact consecutive repetitions are compressed. No semantic
        # equivalence, whitespace normalization, or source editing is involved.
        end = index
        while end < last and lines[end] == text:
            end += 1
        if end - index >= 2:
            records.append({"repeat_of_line": index, "start_line": index + 1,
                            "end_line": end, "omitted_line_count": end - index})
            index = end + 1
        else:
            index += 1
    return {"kind": kind, "start_line": first, "end_line": last,
            **metadata, "lines": records}


def _leaf_values(node, *, limit: int = 256) -> set[str]:
    """Read identifier-shaped leaves of declared fields, without linking them.

    A shared leaf can be a keyword, name, or a reference. Consequently callers
    label the result as a syntactic association, never resolved name lookup.
    """
    values, waiting, visited = set(), [], 0
    for field in getattr(node, "bindings", {}):
        waiting.extend(node.field(field))
    while waiting and visited < limit:
        current = waiting.pop()
        visited += 1
        children = getattr(current, "children", ())
        if children:
            waiting.extend(children)
        else:
            text = getattr(current, "text", "")
            if text.isidentifier() and len(text) <= 128:
                values.add(text)
    return values


def _structure(bundle, text: str, name: str, complete: bool, line: int) -> tuple[dict, list[dict]]:
    """Inspect bounded alternative trees; return selections, not edited nodes."""
    report = {"status": "unavailable", "association_scope": "shared_bound_leaf_text_not_reference_resolution"}
    if len(text) > 32768:
        return {**report, "reason": "source_exceeds_parse_observation_limit", "character_limit": 32768}, []
    starts = [0] + [index + 1 for index, char in enumerate(text) if char == "\n"]
    low = starts[line - 1]
    high = starts[line] if line < len(starts) else len(text) + 1

    def span(node):
        first = bisect_right(starts, max(0, node.start))
        last = bisect_right(starts, max(node.start, node.end - 1))
        return first, last

    def intersects_focus(node):
        # Parser offsets are half-open. A preceding declaration ending exactly
        # at this line's start does not own the error. A zero-width prediction
        # belongs to the line containing its insertion point, including EOF.
        if node.start == node.end:
            return low <= node.start < high
        return node.start < high and node.end > low

    try:
        frontier = (bundle.frontend.complete(text, name) if complete else
                    bundle.frontend.prefix(text, name))
        alternatives = frontier.alternatives
        report.update(status="observed" if alternatives else "unavailable",
                      alternative_count=len(alternatives), inspected_alternatives=min(8, len(alternatives)),
                      ambiguous=len(alternatives) > 1, alternatives_omitted=max(0, len(alternatives) - 8),
                      nodes_omitted=False)
        if not alternatives:
            report["reason"] = "no_parse_alternatives"
            return report, []
        requests = []
        for branch, root in enumerate(alternatives[:8]):
            waiting, nodes, paths = [(root, ())], [], []
            while waiting and len(nodes) < 8192:
                node, ancestors = waiting.pop()
                nodes.append(node)
                if getattr(node, "bindings", {}) and intersects_focus(node):
                    paths.append((node, ancestors))
                waiting.extend((child, ancestors + (node,)) for child in reversed(node.children))
            report["nodes_omitted"] |= bool(waiting)
            if not paths:
                continue
            # The smallest bound production intersecting the error line gives
            # local context. Its ancestors retain the enclosing structure even
            # when the selected field is merely a name or an expression.
            selected, parents = min(paths, key=lambda pair: (pair[0].end - pair[0].start, -len(pair[1])))
            bound_parents = [node for node in parents if getattr(node, "bindings", {})]
            owners = [selected] + list(reversed(bound_parents[-1:]))
            # Reserve short ancestor headers before proposing a potentially
            # large body. An outer container is useful as scope context, but
            # copying its first dozens of unrelated declarations is not.
            for node in bound_parents:
                first, last = span(node)
                requests.append({"kind": "ancestor_header", "first": first, "last": min(first + 1, last),
                                 "branch": branch, "production": node.production,
                                 "node_complete": node.complete, "node_range": [first, last]})
            for node in owners:
                first, last = span(node)
                requests.append({"kind": "focus_structure", "first": first, "last": last,
                                 "branch": branch, "production": node.production,
                                 "node_complete": node.complete, "node_range": [first, last]})
            # Fields on the local construct supply lexical candidates. We do
            # not infer which fields declare names or resolve references.
            seed = set().union(*(_leaf_values(node) for node in owners[:2]))
            related = []
            for node in nodes:
                if not getattr(node, "bindings", {}) or intersects_focus(node):
                    continue
                common = seed & _leaf_values(node)
                if common:
                    first, last = span(node)
                    related.append((len(common), last - first, first, node, sorted(common)))
            for _, _, first, node, common in sorted(related, key=lambda item: (-item[0], item[1], item[2]))[:8]:
                _, last = span(node)
                requests.append({"kind": "syntactic_association", "first": first, "last": last,
                                 "branch": branch, "production": node.production,
                                 "shared_bound_leaves": common[:8], "node_complete": node.complete,
                                 "node_range": [first, last]})
        return report, requests
    except Exception as exc:
        # Optional prompt context must not turn a parser resource limit into a
        # model error or a claim that the candidate is invalid. Actual validators
        # retain responsibility for the acceptance decision.
        return {**report, "status": "unavailable", "reason": type(exc).__name__}, []


def build_source_context(bundle, sources: list[dict], focus: dict | None, *, max_bytes: int = 8192) -> dict:
    """Return a traceable source view whose UTF-8 JSON is at most ``max_bytes``.

    Line ranges are one-based and inclusive; omitted character ranges are
    zero-based, half-open within their original line. All hashes cover unchanged
    UTF-8 source text. No files, retrieval corpus, or external models are read.
    """
    if type(max_bytes) is not int or max_bytes < 512:
        raise ValueError("Source context requires a UTF-8 JSON budget of at least 512 bytes")
    originals, metadata = [], []
    for index, source in enumerate(sources):
        if not isinstance(source, dict) or not isinstance(source.get("name"), str) or not isinstance(source.get("text"), str):
            raise ValueError("Candidate sources require string name and text fields")
        text = source["text"]
        lines = text.split("\n")
        item = {"source_index": index, "name": source["name"],
                "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "complete": bool(source.get("complete", False)), "original_lines": len(lines),
                "original_characters": len(text), "selected_ranges": [],
                "omitted_ranges": [[1, len(lines)]], "fragments": []}
        originals.append((source, lines))
        metadata.append(item)
    identity = [{key: item[key] for key in ("source_index", "name", "sha256", "complete")} for item in metadata]
    result = {"schema_version": 1, "scope": "candidate_source_view_not_correction",
              "selection": "full_sources", "source_count": len(sources),
              "source_manifest_sha256": hashlib.sha256(json.dumps(identity, ensure_ascii=False,
                  separators=(",", ":")).encode("utf-8")).hexdigest(),
              "sources": deepcopy(metadata), "structure": {"status": "not_needed_for_full_view"},
              "truncation": {"utf8_byte_limit": max_bytes, "source_metadata_omitted": 0,
                             "content_truncated": False}}
    for item, (_, lines) in zip(result["sources"], originals):
        item["fragments"] = [_fragment(lines, 1, len(lines), "whole_source")]
        _refresh(item)
    if _size(result) <= max_bytes:
        return result

    result["sources"] = deepcopy(metadata)
    result["selection"] = "bounded_overview"
    result["truncation"]["content_truncated"] = True
    source_name = (focus or {}).get("source")
    matching = [index for index, (source, _) in enumerate(originals) if source["name"] == source_name]
    focus_index = matching[0] if len(matching) == 1 else None
    focus_line = (focus or {}).get("line")
    if (focus_index is None or type(focus_line) is not int
            or not 1 <= focus_line <= len(originals[focus_index][1])):
        focus_index, focus_line = None, None
        result["structure"] = {"status": "not_requested", "reason": "no_unique_located_focus"}
    else:
        source, _ = originals[focus_index]
        result["structure"], requests = _structure(bundle, source["text"], source["name"],
                                                   bool(source.get("complete", False)), focus_line)
        result["selection"] = "structured_focus" if result["structure"]["status"] == "observed" else "fallback_near_focus"

    # Even metadata has a cost. Keep the focus source first when the caller
    # supplies more sources than the budget can describe, and retain a hash of
    # the complete manifest so omissions are explicit rather than invisible.
    if focus_index is not None:
        result["sources"].sort(key=lambda item: item["source_index"] != focus_index)
    while result["sources"] and _size(result) > max_bytes:
        result["sources"].pop()
        result["truncation"]["source_metadata_omitted"] += 1
    if _size(result) > max_bytes:
        result["structure"] = {"status": "omitted_for_budget"}
    if _size(result) > max_bytes:
        raise ValueError("Source context budget cannot represent minimum provenance")

    def add(index, first, last, kind, **details):
        item = next((entry for entry in result["sources"] if entry["source_index"] == index), None)
        if item is None:
            return
        lines = originals[index][1]
        first, last = max(1, first), min(len(lines), last)
        if first > last or any(a <= first and last <= b for a, b in item["selected_ranges"]):
            return
        # Bound the amount proposed by a very large enclosing production. Its
        # full range remains in node_range; omitted lines stay in omitted_ranges.
        last = min(last, first + 31)
        for limit in (None, 512, 128, 32):
            fragment = _fragment(lines, first, last, kind, limit=limit, **details)
            item["fragments"].append(fragment)
            _refresh(item)
            if _size(result) <= max_bytes:
                return
            item["fragments"].pop()
            _refresh(item)

    if focus_index is not None:
        add(focus_index, focus_line - 1, focus_line + 1, "error_neighborhood")
        if result["structure"].get("status") == "observed":
            for request in requests:
                request = dict(request)
                first, last, kind = request.pop("first"), request.pop("last"), request.pop("kind")
                add(focus_index, first, last, kind, **request)
    for item in result["sources"]:
        index, count = item["source_index"], item["original_lines"]
        add(index, 1, min(3, count), "source_start")
        add(index, max(1, count - 2), count, "source_tail")
    # No synthetic delimiters or reconstructed declarations are inserted, even
    # when every available parse describes an unfinished structure.
    return result
