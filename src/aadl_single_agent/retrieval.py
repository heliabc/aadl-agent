"""Deterministic, prompt-only retrieval from a frozen local knowledge corpus.

Retrieved examples are evidence, never project files or symbol definitions.
This module deliberately has no model, dependency-loader or network imports;
one ordinary generation therefore remains one model call when RAG is enabled.
"""
from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
from importlib.resources import files
import json
import math
from pathlib import Path, PurePosixPath
import re
from time import perf_counter


_POLICY = {
    "version": "2", "max_entries": 3, "default_max_characters": 6000,
    "max_query_characters": 16000, "bm25_k1": 1.2, "bm25_b": 0.75,
    "tie_break": "entry_id_ascending", "chunking": "whole_entry_only",
    "query_expansion": "corpus_bilingual_terms_v1", "diagnostic_term_weight": 2,
    "scope": "prompt_evidence_only", "online_learning": False,
    "model_calls": 0, "network_calls": 0,
}
_STOP = frozenset("a an the to of and or in out is are be for from as by this that with "
                  "please generate create model aadl project requirement requirements "
                  "must should shall source code file files use using it its on at".split())
_NOTICE = (
    "REFERENCE EVIDENCE ONLY — these excerpts are not project dependencies or declarations.\n"
    "Apply only relevant language rules; preserve the user's requested names, categories, "
    "interfaces and quantities. Example identifiers and architectures are illustrative. "
    "Every referenced declaration must still exist in the generated source or its explicit "
    "dependency snapshot. A standard/document name is not an importable package.\n"
)


def _json_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_json_bytes(value)).hexdigest()


def _terms(text, expansions):
    lowered = text.casefold()
    tokens = [token for token in re.findall(r"[a-z][a-z0-9_]*", lowered)
              if token not in _STOP]
    # CJK bigrams retain useful words without downloading a segmentation model.
    for span in re.findall(r"[\u3400-\u9fff]+", lowered):
        tokens.extend(span[i:i + 2] for i in range(len(span) - 1))
    for concept, triggers in expansions.items():
        if any(trigger in lowered for trigger in triggers):
            tokens.extend(concept.split())
    return tokens


def _default_corpus():
    source = Path(__file__).resolve().parents[2] / "resources" / "retrieval" / "aadl.json"
    return source if source.is_file() else Path(str(
        files("aadl_single_agent").joinpath("_resources", "retrieval", "aadl.json")))


def _validate_entry(entry, generation_scope):
    if not isinstance(entry, dict):
        raise ValueError("Each retrieval entry must be an object")
    for field in ("id", "title", "applicability", "explanation", "family", "origin"):
        if not isinstance(entry.get(field), str) or not entry[field].strip():
            raise ValueError(f"Retrieval entry requires nonempty {field}")
    if not re.fullmatch(r"[a-z0-9_-]+", entry["id"]):
        raise ValueError("Retrieval entry id must be stable lowercase ASCII")
    for field in ("keywords", "sources"):
        if not isinstance(entry.get(field), list) or not entry[field]:
            raise ValueError(f"Retrieval entry {entry['id']} requires {field}")
    if any(not isinstance(word, str) or not word.strip() for word in entry["keywords"]):
        raise ValueError("Retrieval keywords must be nonempty strings")
    for source in entry["sources"]:
        if not isinstance(source, dict) or any(not isinstance(source.get(key), str)
                or not source[key].strip() for key in ("document", "version", "locator")):
            raise ValueError("Retrieval sources require document/version/locator")
    examples = entry.get("examples", [])
    if not isinstance(examples, list):
        raise ValueError("Retrieval examples must be a list")
    for example in examples:
        if not isinstance(example, dict) or not isinstance(example.get("files"), dict) or not example["files"]:
            raise ValueError("Each retrieval example must contain complete file text")
        # Knowledge examples must obey the same declared output scope as the
        # task. Otherwise a relevant hit can teach an unavailable file layout.
        if len(example["files"]) != generation_scope["source_files"]:
            raise ValueError("Retrieval example file count differs from the corpus generation scope")
        for name, source in example["files"].items():
            if not isinstance(name, str):
                raise ValueError("Retrieval example file name must be text")
            path = PurePosixPath(name)
            if ("\\" in name or path.is_absolute()
                    or ".." in path.parts or ":" in name or path.suffix != ".aadl"
                    or not isinstance(source, str) or not source.strip()):
                raise ValueError("Unsafe or incomplete retrieval example file")
            if "```" in source:
                raise ValueError("Retrieval source must not break evidence code fences")


def _render(entry):
    source_lines = [f"{s['document']} ({s['version']}), {s['locator']}" for s in entry["sources"]]
    parts = [f"[{entry['id']}] {entry['title']}",
             "Applies when: " + entry["applicability"],
             "Source: " + "; ".join(source_lines), entry["explanation"]]
    for example in entry.get("examples", []):
        parts.append("Illustrative complete source; do not copy its names as requirements:")
        # Source text stays intact, including declaration order for same-file
        # forward references. An example is never materialized as a dependency.
        for name, source in example["files"].items():
            parts.extend(("Example file: " + name, "```aadl\n" + source.rstrip("\n") + "\n```"))
    return "\n".join(parts)


class RetrievalIndex:
    """BM25 over one immutable corpus snapshot, with stable evidence hashes."""

    def __init__(self, corpus_path: str | Path | None = None):
        self.path = (Path(corpus_path) if corpus_path is not None else _default_corpus()).resolve()
        raw = self.path.read_bytes()
        try:
            corpus = json.loads(raw.decode("utf-8-sig"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"Invalid retrieval corpus: {self.path}") from exc
        if (not isinstance(corpus, dict) or corpus.get("schema_version") != 1
                or not isinstance(corpus.get("version"), str) or not corpus["version"]
                or not isinstance(corpus.get("entries"), list) or not corpus["entries"]):
            raise ValueError("Retrieval corpus requires schema_version=1, version and entries")
        generation_scope = corpus.get("generation_scope")
        if (not isinstance(generation_scope, dict)
                or set(generation_scope) != {"name", "source_files", "model_units_per_file"}
                or generation_scope.get("name") != "single_source"
                or type(generation_scope.get("source_files")) is not int
                or generation_scope["source_files"] != 1
                or type(generation_scope.get("model_units_per_file")) is not int
                or generation_scope["model_units_per_file"] != 1):
            raise ValueError("Retrieval corpus must declare one source file with one model unit")
        self._entries = deepcopy(corpus["entries"])
        for entry in self._entries:
            _validate_entry(entry, generation_scope)
        if len({entry["id"] for entry in self._entries}) != len(self._entries):
            raise ValueError("Duplicate retrieval entry id")
        # Domain terminology belongs to the knowledge corpus, not the search
        # implementation. Updating a language vocabulary therefore changes the
        # corpus fingerprint without requiring a new generic retrieval engine.
        expansions = corpus.get("query_expansions", {})
        if not isinstance(expansions, dict) or any(
                not isinstance(concept, str) or not concept.strip()
                or not isinstance(triggers, list) or not triggers
                or any(not isinstance(trigger, str) or not trigger.strip() for trigger in triggers)
                for concept, triggers in expansions.items()):
            raise ValueError("query_expansions must map concepts to nonempty trigger lists")
        self._expansions = {concept.casefold(): tuple(trigger.casefold() for trigger in triggers)
                            for concept, triggers in sorted(expansions.items())}
        self._entries.sort(key=lambda entry: entry["id"])
        self._identity = {
            "corpus_sha256": hashlib.sha256(raw).hexdigest(), "corpus_version": corpus["version"],
            "schema_version": 1, "entry_count": len(self._entries),
            "retriever": "bm25", "retriever_version": "2", "policy": deepcopy(_POLICY),
            "generation_scope": deepcopy(generation_scope),
            "query_expansion_sha256": _digest(self._expansions),
        }
        self._documents = []
        self._df = Counter()
        for entry in self._entries:
            # Index prose and keywords, not arbitrary example variable names.
            text = " ".join((entry["title"], " ".join(entry["keywords"]),
                             entry["applicability"], entry["explanation"]))
            counts = Counter(_terms(text, self._expansions))
            self._documents.append(counts)
            self._df.update(counts.keys())
        self._lengths = [sum(doc.values()) for doc in self._documents]
        self._average_length = sum(self._lengths) / len(self._lengths)

    def identity(self):
        # Callers may serialize or annotate the return value without corrupting
        # the index used by another condition in the same paired experiment.
        return deepcopy(self._identity)

    def retrieve(self, requirements: str, *, diagnostics=None, max_characters: int = 6000):
        started = perf_counter()
        if not isinstance(requirements, str):
            raise ValueError("Retrieval requirements must be text")
        if type(max_characters) is not int or max_characters < 0:
            raise ValueError("max_characters must be a nonnegative integer")
        if diagnostics is None:
            diagnostic_text = ""
        elif isinstance(diagnostics, str):
            diagnostic_text = diagnostics
        else:
            try:
                diagnostic_text = _json_bytes(diagnostics).decode("utf-8")
            except (TypeError, ValueError) as exc:
                raise ValueError("Retrieval diagnostics must be JSON-serializable") from exc
        # Reserve room for the latest failure, instead of dropping it when a
        # long unchanged requirement has consumed the whole query allowance.
        maximum = _POLICY["max_query_characters"]
        diagnostic_limit = maximum // 2 if diagnostic_text else 0
        selected_diagnostics = diagnostic_text[:diagnostic_limit]
        separator = "\nDIAGNOSTIC:\n" if selected_diagnostics else ""
        selected_requirements = requirements[:maximum - len(selected_diagnostics) - len(separator)]
        query = selected_requirements + ((separator + selected_diagnostics)
                                         if selected_diagnostics else "")
        terms = Counter(_terms(selected_requirements, self._expansions))
        for word, count in Counter(_terms(selected_diagnostics, self._expansions)).items():
            terms[word] += count * _POLICY["diagnostic_term_weight"]
        ranked = []
        for entry, doc, length in zip(self._entries, self._documents, self._lengths):
            score = 0.0
            for term, query_count in terms.items():
                count = doc.get(term, 0)
                if not count:
                    continue
                frequency = self._df[term]
                idf = math.log(1 + (len(self._entries) - frequency + 0.5) / (frequency + 0.5))
                k1, b = _POLICY["bm25_k1"], _POLICY["bm25_b"]
                score += min(query_count, 3) * idf * count * (k1 + 1) / (
                    count + k1 * (1 - b + b * length / self._average_length))
            if score > 0:
                ranked.append((score, entry))
        ranked.sort(key=lambda pair: (-pair[0], pair[1]["id"]))
        chosen, rendered = [], ""
        skipped = []
        for score, entry in ranked:
            evidence = _render(entry)
            candidate = (rendered + "\n\n" + evidence) if rendered else (_NOTICE + "\n" + evidence)
            if len(candidate) > max_characters:
                # Never cut a classifier declaration or a code fence halfway.
                # An oversized hit may be skipped in favour of a shorter hit.
                skipped.append(entry["id"])
                continue
            rendered = candidate
            selected = deepcopy(entry)
            selected.update(score=round(score, 12), content_sha256=_digest(entry),
                            rendered_evidence=evidence)
            chosen.append(selected)
            if len(chosen) == _POLICY["max_entries"]:
                break
        result = {
            "query": query, "query_sha256": hashlib.sha256(query.encode("utf-8")).hexdigest(),
            "query_truncated": (len(selected_requirements) < len(requirements)
                                or len(selected_diagnostics) < len(diagnostic_text)),
            "input_requirements_sha256": hashlib.sha256(requirements.encode("utf-8")).hexdigest(),
            "input_diagnostics_sha256": hashlib.sha256(diagnostic_text.encode("utf-8")).hexdigest(),
            "entries": chosen, "rendered_context": rendered, "characters": len(rendered),
            "ranked_match_count": len(ranked), "budget_skipped_ids": skipped,
            "identity": self.identity(), "policy": {**deepcopy(_POLICY), "max_characters": max_characters},
        }
        # Wall-clock measurements are intentionally outside the evidence hash.
        # Both groups must receive identical initial evidence despite timing noise.
        result["sha256"] = _digest(result)
        result["elapsed_seconds"] = perf_counter() - started
        return result
