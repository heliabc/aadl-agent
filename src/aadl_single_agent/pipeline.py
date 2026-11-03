"""Generate one project and repair located failures within one shared budget."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict, dataclass, field, is_dataclass
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
from pathlib import Path
import platform
import shutil
import time
from typing import Any, Callable

from .constraints import JointEngine
from .model import ModelClient, ModelError
from .osate import OsateRunner
from .project import PROJECT_INSTRUCTIONS, parse_project, snapshot_context, write_project
from .repair_context import build_source_context
from .repair_history import build_repair_history
from .retrieval import RetrievalIndex
from .rules import RuleBundle, load_bundle
from .semantic_types import ParseResourceError, RuleDefinitionError, SemanticReport, Status

DEFAULT_TOKEN_BUDGET = 8192
# This includes the initial candidate: six attempts permit at most five repairs.
# Every call still consumes the same shared output-token budget.
DEFAULT_MAX_ATTEMPTS = 6
# This bounds prompt evidence in UTF-8 bytes, not the shared output-token budget.
# Reserve separate space for the current candidate and a short verified history.
REPAIR_FEEDBACK_VERSION = 2
REPAIR_EVIDENCE_MAX_BYTES = 16384
REPAIR_SOURCE_MAX_BYTES = 6144
REPAIR_HISTORY_MAX_BYTES = 4096
REPAIR_HISTORY_MAX_ENTRIES = 3
REPAIR_ITEM_MAX_CHARACTERS = 512
REPAIR_ITEM_MAX_BYTES = 1024
RETRIEVAL_MAX_CHARACTERS = 6000


def repair_feedback_policy() -> dict:
    """Expose the client feedback policy for run records and resume fingerprints."""
    return {
        "schema_version": REPAIR_FEEDBACK_VERSION,
        "evidence_max_utf8_bytes": REPAIR_EVIDENCE_MAX_BYTES,
        "item_max_characters": REPAIR_ITEM_MAX_CHARACTERS,
        "item_max_utf8_bytes": REPAIR_ITEM_MAX_BYTES,
        "source_context_max_utf8_bytes": REPAIR_SOURCE_MAX_BYTES,
        "history_max_utf8_bytes": REPAIR_HISTORY_MAX_BYTES,
        "history_max_entries": REPAIR_HISTORY_MAX_ENTRIES,
        "conversation": "original_requirements_current_source_and_bounded_verified_history",
        "focus_selection": "project_file_then_line_order_osate_error_else_local_conflict",
        "pattern_reobservation": "same_construct_observed_not_proof_of_error_resolution",
        "diagnostic_reobservation": "same_source_diagnostic_signature_not_proof_of_repair",
        "acceptance": "unchanged_full_rules_and_independent_osate",
        "timeout_retry": False,
    }
# Resolve legacy imports lazily: selecting an external --rules package must not
# require loading the bundled default language at CLI import time.
def __getattr__(name: str):
    if name == "SYSTEM_PROMPT":
        return load_bundle().system_prompt
    raise AttributeError(name)

def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()

def json_value(value: Any) -> Any:
    if is_dataclass(value):
        value = asdict(value)
    if isinstance(value, dict):
        return {str(key): json_value(item) for key, item in value.items()}
    if isinstance(value, (set, frozenset)):
        return sorted(json_value(item) for item in value)
    if isinstance(value, (list, tuple)):
        return [json_value(item) for item in value]
    return str(value) if isinstance(value, Path) else value

def endpoint_snapshot(endpoint: Any) -> dict:
    return json_value({key: getattr(endpoint, key, None) for key in (
        "base_url", "model", "backend", "constraint_types", "rule_bundle_sha256",
        "runtime_version", "metadata",
    )})

def _workflow_sources() -> str:
    """Fingerprint the source snapshot associated with this process."""
    digest = hashlib.sha256()
    for source in sorted(Path(__file__).parent.glob("*.py")):
        digest.update(source.name.encode("utf-8") + b"\0" + source.read_bytes() + b"\0")
    return digest.hexdigest()

_IMPORTED_WORKFLOW_IDENTITY = _workflow_sources()

def workflow_identity() -> str:
    """Reject mixed disk/imported revisions instead of mislabelling a run.

    Python keeps imported modules in memory. Rehashing edited disk files for a
    second run would otherwise claim that new code actually executed. Restart
    the client after runtime edits; rule packages retain their separate hashes.
    """
    if _workflow_sources() != _IMPORTED_WORKFLOW_IDENTITY:
        raise ModelError("Runtime source files changed after client startup; restart the client before another run.",
                         error_kind="infrastructure")
    return _IMPORTED_WORKFLOW_IDENTITY

def local_versions() -> dict:
    versions = {"python": platform.python_version(), "platform": platform.platform()}
    for package in ("aadl-single-agent", "httpx", "lark", "regex", "llguidance", "jsonschema", "PyYAML"):
        try:
            versions[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            versions[package] = None
    return versions

def check_instance_rules(bundle: RuleBundle, independent: dict) -> dict:
    """Apply declared instance rules without overstating OSATE's success message.

    The CLI and generation workflow share this adapter. The rule package owns
    AADL category/consistency rules; the OSATE wrapper only verifies artifacts.
    """
    instance = independent.get("instance", {})
    if instance.get("status", "not_requested") == "not_requested":
        return {"status": "not_requested", "valid": None, "results": [], "diagnostics": [], "pending": []}
    if independent.get("status") != "passed" or instance.get("status") != "passed":
        return {"status": "not_run", "valid": False, "results": [], "diagnostics": [], "pending": []}
    hook = getattr(bundle.semantics, "validate_instance", None)
    if not callable(hook):
        return {"status": "unsupported", "valid": False, "results": [], "pending": [],
                "diagnostics": ["The rule bundle does not provide validate_instance for the requested instance checks"]}
    try:
        artifacts = instance.get("files", [])
        if len(artifacts) != 1:
            raise RuleDefinitionError("OSATE did not identify exactly one verified instance artifact")
        path = Path(artifacts[0]["path"])
        report = hook(path)
        if not isinstance(report, SemanticReport):
            raise RuleDefinitionError("validate_instance must return a SemanticReport")
        if any(not isinstance(item.status, Status) for item in report.results):
            raise RuleDefinitionError("Instance rule results must use Satisfied/Pending/Conflict statuses")
        # SemanticReport contains a read-only mapping. Serialize its result
        # records explicitly instead of deep-copying that mapping with asdict.
        results = [json_value(item) for item in report.results]
        pending = [item.message or item.rule_id for item in report.pending]
        diagnostics = [item.message or item.rule_id for item in report.results
                       if item.status != Status.SATISFIED]
        valid = report.valid and not pending
        return {"status": "passed" if valid else "failed", "valid": valid,
                "source": str(path), "results": results, "diagnostics": diagnostics, "pending": pending,
                "attributes": json_value(dict(report.attributes))}
    except (RuleDefinitionError, ParseResourceError, ValueError, OSError) as exc:
        return {"status": "error", "valid": False, "results": [], "pending": [], "diagnostics": [str(exc)]}

@dataclass
class RunResult:
    status: str
    constraints: str
    repair_enabled: bool = True
    model: str = ""
    backend: str = ""
    failure_kind: str | None = None
    validation_scope: str = "syntax_and_static_semantics"
    requirements_verified: bool = False
    requirements: str = ""
    settings: dict = field(default_factory=dict)
    provenance: dict = field(default_factory=dict)
    usage: dict = field(default_factory=lambda: {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0})
    usage_complete: bool = True
    calls: list[dict] = field(default_factory=list)
    attempts: list[dict] = field(default_factory=list)
    timing: dict = field(default_factory=lambda: {
        "elapsed_seconds": 0.0, "generation_seconds": 0.0,
        "static_check_seconds": 0.0, "osate_seconds": 0.0,
        "retrieval_seconds": 0.0,
    })
    started_at: str = field(default_factory=_utc_now)
    finished_at: str | None = None
    completed: bool = False
    output: str | None = None
    error: str | None = None

def _write_json(path: Path, data: dict) -> None:
    # Atomic replacement preserves the previous checkpoint if writing is interrupted.
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(json_value(data), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def _repair_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _bounded_repair_text(value: str) -> str:
    """Bound JSON-escaped text, including multibyte/control characters."""
    shortened = value[:REPAIR_ITEM_MAX_CHARACTERS]
    if len(shortened) < len(value):
        shortened += '…'
    while len(_repair_json(shortened).encode('utf-8')) > REPAIR_ITEM_MAX_BYTES:
        shortened = shortened[:max(1, len(shortened) // 2)] + '…'
    return shortened


def _compact_repair_item(value: Any) -> tuple[dict, str, bool]:
    """Keep diagnostic locations and advice, never nested validator payloads.

    Deduplication uses the untruncated projection, so two different long errors
    do not become the same error merely because their displayed prefixes match.
    The original objects remain untouched in the attempt and call evidence.
    """
    item = {}
    if isinstance(value, dict):
        message = value.get('message')
        if not isinstance(message, str):
            message = ' '.join(value[key] for key in ('suggestion', 'explanation')
                               if isinstance(value.get(key), str))
        item['message'] = message or 'No concise diagnostic message supplied.'
        source = value.get('source', value.get('path', value.get('file')))
        if isinstance(source, str) and source:
            # OSATE's absolute run/workspace prefix is not useful to the model.
            # Keep a short parent/file location without reading the filesystem.
            item['source'] = '/'.join(source.replace('\\', '/').split('/')[-2:])
        for key in ('line', 'column', 'start', 'end'):
            number = value.get(key)
            if type(number) is int and 0 <= number < 2**63:
                item[key] = number
        for target, source_key in (('rule_id', 'rule_id'), ('rule_id', 'id'), ('severity', 'severity')):
            if target not in item and isinstance(value.get(source_key), str):
                item[target] = value[source_key]
        # Observed source is evidence, distinct from the rule author's advice.
        for key in ('observed', 'explanation', 'suggestion'):
            if isinstance(value.get(key), str):
                item[key] = value[key]
    else:
        item['message'] = value if isinstance(value, str) else str(value)
    identity = _repair_json(item)
    truncated = False
    for key, limit in (('message', REPAIR_ITEM_MAX_CHARACTERS), ('source', 96),
                       ('rule_id', 64), ('severity', 16), ('observed', 256),
                       ('explanation', 256), ('suggestion', 256)):
        if key in item and len(item[key]) > limit:
            item[key] = item[key][:limit - 1] + '…'
            truncated = True
    if truncated:
        item['truncated'] = True
    # JSON quoting and multibyte characters count toward the actual wire budget.
    # Bound the whole record, not only its human-readable message field.
    def fits(record):
        encoded = _repair_json(record)
        return len(encoded) <= REPAIR_ITEM_MAX_CHARACTERS and len(encoded.encode('utf-8')) <= REPAIR_ITEM_MAX_BYTES
    if not fits(item):
        truncated = True
        item['truncated'] = True
        message = item['message']
        low, high = 0, len(message)
        while low < high:
            middle = (low + high + 1) // 2
            item['message'] = message[:middle] + '…'
            if fits(item):
                low = middle
            else:
                high = middle - 1
        item['message'] = message[:low] + '…'
        # Unusual control characters in a location can expand sixfold in JSON.
        # Retain a shortened location rather than letting escaping break the cap.
        for key in ('explanation', 'suggestion', 'observed', 'source', 'rule_id', 'severity'):
            while not fits(item) and key in item:
                value = item[key]
                if len(value) <= 2:
                    # A short observed marker is still evidence; do not discard
                    # it merely to retain a longer optional explanatory field.
                    if key == 'observed':
                        item[key] = '…'
                        break
                    item.pop(key)
                else:
                    item[key] = value[:len(value) // 2] + '…'
    return item, identity, truncated


def _source_name(reference: Any, sources: dict[str, str]) -> str | None:
    """Map a copied validator path only when it identifies one received source."""
    if not isinstance(reference, str) or not reference:
        return None
    normalized = reference.replace('\\', '/').casefold()
    matches = [name for name in sources if normalized == name.replace('\\', '/').casefold()
               or normalized.endswith('/' + name.replace('\\', '/').casefold())]
    if len(matches) == 1:
        return matches[0]
    basename = normalized.rsplit('/', 1)[-1]
    matches = [name for name in sources if name.replace('\\', '/').rsplit('/', 1)[-1].casefold() == basename]
    return matches[0] if len(matches) == 1 else None


def select_repair_focus(sources: dict[str, str], diagnostics: list, issues: list) -> dict | None:
    """Select the first located independent ERROR, then a local Conflict.

    Errors are ordered by received project-file order and source line, with the
    validator index breaking ties. Warnings/Pending are never promoted to errors.
    Unknown or ambiguous source locations are not guessed.
    """
    candidates = [('osate', index, item) for index, item in enumerate(diagnostics)
                  if isinstance(item, dict) and str(item.get('severity', '')).lower() == 'error']
    candidates += [('local_rules', index, item) for index, item in enumerate(issues)
                   if isinstance(item, dict) and item.get('status') == Status.CONFLICT]
    source_order = {name: index for index, name in enumerate(sources)}
    located = []
    for origin, index, raw in candidates:
        name = _source_name(raw.get('source') or raw.get('path') or raw.get('file'), sources)
        if name is None:
            continue
        source = sources[name]
        line = raw.get('line')
        if (type(line) is not int or line < 1) and origin == 'local_rules':
            offset = raw.get('start')
            line = source.count('\n', 0, offset) + 1 if type(offset) is int and 0 <= offset <= len(source) else None
        lines = source.split('\n')
        if type(line) is not int or not 1 <= line <= len(lines):
            continue
        located.append((origin, index, raw, name, source, line))
    if not located:
        return None
    origin, index, raw, name, source, line = min(located, key=lambda item: (
        item[0] != 'osate', source_order[item[3]], item[5], item[1]))
    diagnostic = {**raw, 'source': name, 'line': line, 'severity': 'error'}
    # A short, read-only excerpt is not a reconstructed/corrected program.
    lines, context = source.split('\n'), []
    for number in range(max(1, line - 1), min(len(lines), line + 1) + 1):
        original = lines[number - 1].removesuffix('\r')
        context.append({'line': number, 'text': original[:384],
                        'text_truncated': len(original) > 384,
                        'original_characters': len(original)})
    return {'source': name, 'line': line, 'origin': origin, 'diagnostic_index': index,
            'diagnostic': diagnostic, 'source_context': context, 'pattern_id': None, 'advice': None}


def _diagnostic_message_identity(diagnostic: dict, independent: dict | None) -> dict:
    """Normalize only registered files beneath a verified OSATE model root.

    OSATE may print Windows paths without their drive in message text. Accept
    that explicit representation only after the diagnostic's separate path
    confirms the recorded workspace/model directory. Literal text and relative
    file targets remain distinct typed segments; no random-string replacement
    or placeholder-string collision can equate different referenced files.
    """
    raw = diagnostic.get('message')
    result = {'raw_message': raw, 'eligible': False, 'reason': 'workspace_unverified',
              'comparison_signature': [{'text': raw}], 'normalizations': []}
    snapshot = independent or {}
    workspace, registered = snapshot.get('workspace'), snapshot.get('sources')
    if not isinstance(raw, str) or not isinstance(workspace, str) or not isinstance(registered, list):
        return result
    root = workspace.replace('\\', '/').rstrip('/') + '/model/'
    windows = len(root) > 2 and root[0].isalpha() and root[1:3] == ':/'
    if not windows and not root.startswith('/'):
        return result
    names = []
    for item in registered:
        name = item.get('path') if isinstance(item, dict) else None
        if not isinstance(name, str):
            return result
        name = name.replace('\\', '/')
        if not name or name.startswith('/') or ':' in name or any(p in {'', '.', '..'} for p in name.split('/')):
            return result
        names.append(name)
    keys = [name.casefold() if windows else name for name in names]
    if not names or len(set(keys)) != len(keys):
        return {**result, 'reason': 'source_registry_missing_or_ambiguous'}
    location = diagnostic.get('path') or diagnostic.get('file') or diagnostic.get('source')
    if not isinstance(location, str):
        return result
    normalized_location = location.replace('\\', '/')
    location_key = normalized_location.casefold() if windows else normalized_location
    if location_key not in {(root + name).casefold() if windows else root + name for name in names}:
        return {**result, 'reason': 'diagnostic_path_outside_registered_model'}
    result.update(eligible=True, reason='verified_workspace_and_source_registry', workspace=workspace,
                  model_root=root, diagnostic_path=location)
    prefixes = [(root, 'absolute_slash')]
    if windows:
        prefixes += [(root.replace('/', '\\'), 'absolute_windows'),
                     (root[2:], 'confirmed_windows_drive_omitted')]
    replacements = []
    for prefix, representation in prefixes:
        for name in names:
            relative = name.replace('/', '\\') if representation == 'absolute_windows' else name
            absolute = prefix + relative
            offset = 0
            while (start := raw.find(absolute, offset)) >= 0:
                end = start + len(absolute)
                # Match a complete path token, never a suffix of a URL/another
                # root or the prefix of file.aadl.extra or a directory name.
                left = start == 0 or raw[start - 1].isspace() or raw[start - 1] in "([{\"'=<>"
                right = (end == len(raw) or raw[end].isspace() or raw[end] in ",;:)]}\"'<>"
                         or raw[end] == '.' and (end + 1 == len(raw) or raw[end + 1].isspace()))
                if left and right:
                    replacements.append({'start': start, 'end': end, 'source': name,
                        'original_path': absolute, 'verified_prefix': prefix, 'representation': representation})
                offset = end
    cursor, parts, accepted = 0, [], []
    for item in sorted(replacements, key=lambda x: (x['start'], -x['end'])):
        if item['start'] < cursor:
            continue
        parts.append({'text': raw[cursor:item['start']]})
        parts.append({'source': item['source']})
        cursor = item['end']
        accepted.append(item)
    parts.append({'text': raw[cursor:]})
    result.update(comparison_signature=parts, normalizations=accepted)
    return result


def reobserve_focus_diagnostic(focus: dict | None, sources: dict[str, str],
                              independent: dict, issues: list, *, rule_error=None,
                              previous_independent: dict | None = None) -> dict:
    """Observe the preceding diagnostic signature without claiming entity identity.

    Line numbers may move after a repair, so matching uses the corresponding
    source and an exact original diagnostic code/rule/message. A broad adapter
    label such as ``osate.diagnostic`` is not an issue code. Absence is merely
    non-observation in this check, never proof that the target was repaired.
    """
    result = {'reobserved': None, 'source': None, 'origin': None, 'match_basis': None,
              'signature': None, 'matched_diagnostic_indices': [],
              'scope': 'same_source_diagnostic_signature_not_entity_identity_or_correctness'}
    if not focus:
        return {**result, 'reason': 'no_previous_focus'}
    result['origin'] = focus.get('origin')
    old_source = focus.get('source')
    # The old file must still exist by project-relative name. A renamed file
    # with the same basename is not silently treated as the same source.
    matches = [name for name in sources if isinstance(old_source, str)
               and name.replace('\\', '/').casefold() == old_source.replace('\\', '/').casefold()]
    if len(matches) != 1:
        return {**result, 'reason': 'previous_source_missing_or_ambiguous'}
    result['source'] = name = matches[0]
    if independent.get('status') not in {'passed', 'failed'}:
        return {**result, 'reason': 'independent_check_unavailable'}
    origin = result['origin']
    if origin == 'osate':
        diagnostics = independent.get('diagnostics')
    elif origin == 'local_rules':
        if rule_error is not None:
            return {**result, 'reason': 'local_rule_check_unavailable'}
        diagnostics = issues
    else:
        return {**result, 'reason': 'unknown_focus_origin'}
    if not isinstance(diagnostics, list):
        return {**result, 'reason': 'diagnostics_unavailable'}
    previous = focus.get('diagnostic') or {}
    for key in ('code', 'rule_id', 'message'):
        value = previous.get(key)
        if isinstance(value, str) and value and not (key == 'rule_id' and value == 'osate.diagnostic'):
            result.update(match_basis=key, signature=value)
            break
    if result['match_basis'] is None:
        return {**result, 'reason': 'diagnostic_signature_unavailable'}
    normalized_previous = None
    if origin == 'osate' and result['match_basis'] == 'message':
        normalized_previous = _diagnostic_message_identity(previous, previous_independent)
        result['message_comparison'] = {'strategy': 'verified_workspace_source_paths_v1',
                                       'previous': normalized_previous, 'current': []}
    unresolved_match = comparison_unavailable = False
    for index, diagnostic in enumerate(diagnostics):
        if not isinstance(diagnostic, dict):
            continue
        is_error = (str(diagnostic.get('severity', '')).lower() == 'error' if origin == 'osate'
                    else diagnostic.get('status') == Status.CONFLICT)
        if not is_error:
            continue
        current_source = _source_name(diagnostic.get('source') or diagnostic.get('path') or diagnostic.get('file'), sources)
        if current_source not in {None, name}:
            continue
        same = diagnostic.get(result['match_basis']) == result['signature']
        if normalized_previous is not None:
            normalized_current = _diagnostic_message_identity(diagnostic, independent)
            result['message_comparison']['current'].append({'diagnostic_index': index, **normalized_current})
            if not same and normalized_previous['eligible'] and normalized_current['eligible']:
                same = normalized_previous['comparison_signature'] == normalized_current['comparison_signature']
            elif (not same and (normalized_previous['normalizations'] or normalized_current['normalizations'])
                  and not (normalized_previous['eligible'] and normalized_current['eligible'])):
                # A comparison requiring path normalization cannot claim an
                # absence when one side has no verified workspace provenance.
                comparison_unavailable = True
        if same and current_source is None:
            unresolved_match = True
        elif same:
            result['matched_diagnostic_indices'].append(index)
    if result['matched_diagnostic_indices']:
        return {**result, 'reobserved': True, 'reason': 'matching_error_signature_observed'}
    if unresolved_match:
        return {**result, 'reason': 'matching_signature_source_unresolved'}
    if comparison_unavailable:
        return {**result, 'reason': 'workspace_message_comparison_unavailable'}
    return {**result, 'reobserved': False, 'reason': 'matching_error_signature_not_observed'}


def observe_prefix_structure(bundle: RuleBundle, document) -> dict:
    """Read a bounded open-file frontier without choosing or rejecting a branch.

    Incomplete grammar productions describe possible parses, not proved errors.
    Large inputs/resource failures are explicitly unavailable diagnostic evidence
    and cannot change the candidate's already independent validation verdict.
    """
    opened = [item for item in document.files if not item.complete]
    if not opened:
        return {'status': 'not_applicable', 'reason': 'no_open_source_file'}
    source = opened[-1]
    result = {'source': source.name, 'character_limit': 4096, 'source_characters': len(source.text),
              'scope': 'read_only_viable_frontier_not_error_or_completion_proof'}
    if len(source.text) > 4096:
        return {**result, 'status': 'unavailable', 'reason': 'source_exceeds_observation_limit'}
    try:
        frontier = bundle.frontend.prefix(source.text, source.name)
        alternatives = frontier.alternatives
        if not alternatives:
            return {**result, 'status': 'unavailable', 'reason': 'no_viable_frontend_alternative'}
        details, sets, nodes_truncated = [], [], False
        # Bound diagnostic enumeration independently of the parser's viable set.
        # Never call a sampled union an obligation, or use it to prune decoding.
        for root in alternatives[:8]:
            productions = set()
            for index, node in enumerate(root.walk()):
                if index >= 8192:
                    nodes_truncated = True
                    break
                if not node.complete:
                    productions.add(node.production)
            sets.append(productions)
            details.append({'incomplete_productions': sorted(productions)[:24],
                            'production_count': len(productions),
                            'production_names_truncated': max(0, len(productions) - 24)})
        branches_truncated = max(0, len(alternatives) - len(details))
        exhaustive = not branches_truncated and not nodes_truncated
        return {**result, 'status': 'observed', 'alternative_count': len(alternatives),
                'observed_alternatives': details, 'alternatives_truncated': branches_truncated,
                'node_enumeration_truncated': nodes_truncated,
                'coverage': 'all_alternatives' if exhaustive else 'partial_alternatives',
                'shared_incomplete_productions': sorted(set.intersection(*sets)) if exhaustive else None}
    except Exception as exc:
        # This optional diagnostic must not turn a resource limit or unavailable
        # parse into a fabricated model error or an additional hard constraint.
        return {**result, 'status': 'unavailable', 'reason': type(exc).__name__}


def prepare_repair_feedback(bundle: RuleBundle, sources: list[dict], entry: dict,
                            attempts: list[dict]) -> dict:
    """Enrich a failure using only received source and assessed history.

    Online generation and offline inspection share this projection. Selection
    never reads reference models, changes a candidate, or reruns validation.
    A shallow copy is sufficient: both helpers are read-only and only new
    top-level fields are assigned, avoiding a copy of the decoder's large trace.
    """
    prepared = dict(entry)
    prepared['repair_source_context'] = build_source_context(
        bundle, sources, entry.get('repair_focus'), max_bytes=REPAIR_SOURCE_MAX_BYTES)
    prepared['repair_history'] = build_repair_history(
        attempts, max_entries=REPAIR_HISTORY_MAX_ENTRIES, max_bytes=REPAIR_HISTORY_MAX_BYTES)
    validation = entry.get('final_validation') or {}
    prepared['repair_feedback_version'] = REPAIR_FEEDBACK_VERSION
    prepared['repair_focus_state'] = {
        'origin': (entry.get('repair_focus') or {}).get('origin'),
        'generation_complete': validation.get('generation_complete'),
        'container_complete': validation.get('project_protocol_complete'),
        'meaning': ('Diagnostics describe the received candidate, including unfinished source; '
                    'they do not prove every continuation invalid. Syntactic associations '
                    'are context, not resolved references or proposed fixes.'),
    }
    return prepared


def build_repair_evidence(entry: dict) -> dict:
    """Project one located failure and real stop evidence into a bounded prompt.

    All validator results remain in the attempt. This projection controls prompt
    size, not acceptance: fixing a focus never substitutes for full validation.
    """
    cause = str(entry.get('repair_cause', 'The preceding project failed validation.'))
    focus = entry.get('repair_focus')
    projected_focus = None
    if focus is not None:
        projected_focus = {'source': focus.get('source'), 'line': focus.get('line'),
                           'origin': focus.get('origin'),
                           'diagnostic': _compact_repair_item(focus['diagnostic'])[0],
                           'pattern_id': focus.get('pattern_id'),
                           'advice': _compact_repair_item(focus['advice'])[0] if focus.get('advice') else None,
                           'source_context': deepcopy(focus.get('source_context') or [])}
    termination = entry.get('generation_termination') or {}
    repetition = None
    if termination.get('kind') == 'repetition':
        keys = ('unit', 'period_characters', 'repetitions', 'repeated_characters', 'start_character',
                'end_character', 'character_offsets', 'completed_line_index', 'repeated_lines', 'threshold')
        repetition = {key: termination[key] for key in keys if key in termination}
        for key in ('fragment', 'line'):
            if isinstance(termination.get(key), str):
                repetition[key] = _bounded_repair_text(termination[key])
                repetition[key + '_truncated'] = repetition[key] != termination[key]
    pending = entry.get('pending', [])
    protocol_diagnostics = entry.get('project_protocol', {}).get('diagnostics', [])
    prefix = deepcopy(entry.get('prefix_evidence'))
    if prefix and isinstance(prefix.get('grammar_frontier'), dict):
        frontier = prefix['grammar_frontier']
        # Full branch observations stay in result.json. The prompt contains only
        # a bounded summary, explicitly distinguishing shared from sampled facts.
        summary = {key: frontier[key] for key in ('status', 'source', 'reason', 'coverage',
                   'alternative_count', 'alternatives_truncated', 'node_enumeration_truncated', 'scope')
                   if key in frontier}
        shared = frontier.get('shared_incomplete_productions')
        summary['shared_incomplete_productions'] = shared[:12] if shared is not None else None
        summary['shared_names_truncated'] = max(0, len(shared) - 12) if shared is not None else None
        observed = sorted({name for branch in frontier.get('observed_alternatives', [])
                           for name in branch['incomplete_productions']})
        summary['observed_incomplete_productions'] = observed[:12]
        summary['observed_names_truncated'] = max(0, len(observed) - 12)
        prefix['grammar_frontier'] = summary
    observation = entry.get('focus_diagnostic_observation') or {}
    evidence = {'schema_version': REPAIR_FEEDBACK_VERSION,
                'cause': _bounded_repair_text(cause), 'repair_focus': projected_focus,
                'focus_state': deepcopy(entry.get('repair_focus_state')),
                'candidate_source_context': deepcopy(entry.get('repair_source_context')),
                'recent_evaluated_attempts': deepcopy(entry.get('repair_history')),
                'repetition_observation': repetition, 'prefix_evidence': prefix,
                'unfulfilled_obligations': [], 'container_diagnostics': [],
                'previous_focus_recheck': {'candidate_changed': entry.get('candidate_changed'),
                    'focus_pattern_reobserved': entry.get('focus_pattern_reobserved'),
                    'focus_diagnostic_reobserved': entry.get('focus_diagnostic_reobserved'),
                    'diagnostic_reason': _bounded_repair_text(str(observation.get('reason') or 'unknown')),
                    'diagnostic_match_basis': _bounded_repair_text(str(observation.get('match_basis') or 'unknown')),
                    'meaning': ('Code change, pattern presence and diagnostic reobservation are distinct. '
                                'Diagnostic absence does not establish repair; full validation determines success.')},
                'compression': {'utf8_byte_limit': REPAIR_EVIDENCE_MAX_BYTES,
                    'cause_truncated': _bounded_repair_text(cause) != cause,
                    'pending_original': len(pending), 'pending_included': 0,
                    'container_diagnostics_original': len(protocol_diagnostics),
                    'container_diagnostics_included': 0, 'source_context_lines_omitted': 0}}
    # JSON escaping/multibyte characters count toward the real wire budget.
    # Drop context lines before the selected error or its observed rule pattern.
    while (len(_repair_json(evidence).encode('utf-8')) > REPAIR_EVIDENCE_MAX_BYTES
           and projected_focus and projected_focus['source_context']):
        projected_focus['source_context'].pop()
        evidence['compression']['source_context_lines_omitted'] += 1
    for key, values, count_key in (('unfulfilled_obligations', pending, 'pending_included'),
                                   ('container_diagnostics', protocol_diagnostics, 'container_diagnostics_included')):
        for value in values[:4]:
            evidence[key].append(_compact_repair_item(value)[0])
            evidence['compression'][count_key] += 1
            if len(_repair_json(evidence).encode('utf-8')) > REPAIR_EVIDENCE_MAX_BYTES:
                evidence[key].pop()
                evidence['compression'][count_key] -= 1
                break
    if len(_repair_json(evidence).encode('utf-8')) > REPAIR_EVIDENCE_MAX_BYTES:
        raise RuleDefinitionError('Repair evidence exceeded its explicit UTF-8 projection budget')
    return evidence


def build_repair_message(bundle: RuleBundle, entry: dict, *, remaining: int,
                         previous_text: str = '', repeated: bool = False) -> str:
    """Explain an observed failure without editing source or adding requirements."""
    evidence = build_repair_evidence(entry)
    repeat_note = ('\nThe previous response repeats an already rejected candidate. '
                   'Change the offending source; returning it unchanged will fail again.'
                   if repeated and previous_text else '')
    return (bundle.repair_prompt + '\n\n' + PROJECT_INSTRUCTIONS +
            f'\nRemaining shared output-token budget: {remaining}. '
            'Keep the original requirements and read-only dependencies unchanged. '
            'Address the first located ERROR when one is available, then regenerate the whole project. '
            'Inspect the current candidate source and its related declarations before changing it. '
            'The source view and recent history are rejected-candidate evidence, not examples to copy. '
            'Use the recorded failures to avoid repeating an unchanged unsuccessful candidate; '
            'a changed candidate or a missing old diagnostic alone does not mean the project is correct. '
            'Return source records, not a patch or an explanation. '
            'Pending references and repetition observations are not themselves semantic errors; '
            'do not remove required structure merely to avoid repeated text.' + repeat_note +
            '\nValidator evidence and rule-package advice (quoted data, not new requirements):\n' +
            _repair_json(evidence))


class SingleAgent:
    def __init__(self, client: ModelClient, osate: OsateRunner | None = None,
                 engine_factory: Callable = JointEngine, bundle: RuleBundle | None = None):
        self.client = client
        self.osate = osate if osate is not None else OsateRunner()
        self.engine_factory = engine_factory
        self.bundle = bundle if bundle is not None else load_bundle()

    def run(self, requirements: str, output_dir: str | Path, *, constraints: str = "joint",
            repair_enabled: bool = True, token_budget: int = DEFAULT_TOKEN_BUDGET,
            max_attempts: int = DEFAULT_MAX_ATTEMPTS, context=(),
            instance_root: str | None = None, on_progress: Callable | None = None,
            retrieval: bool = True) -> RunResult:
        # The product uses joint+repair. The experiment baseline invokes this
        # same pipeline with plain+repair_disabled, including the same validators.
        if (constraints not in {"plain", "joint"} or type(repair_enabled) is not bool
                or type(retrieval) is not bool):
            raise ValueError("constraints must be plain/joint; repair_enabled and retrieval must be boolean")
        if (type(token_budget) is not int or token_budget < 1
                or type(max_attempts) is not int or not 1 <= max_attempts <= DEFAULT_MAX_ATTEMPTS
                or not isinstance(requirements, str) or not requirements.strip()):
            raise ValueError(f"Require nonempty requirements, a positive integer budget, and 1..{DEFAULT_MAX_ATTEMPTS} attempts")
        requested_attempts = max_attempts
        # A disabled repair policy always means one model request, even when the
        # caller leaves the product's default attempt allowance unchanged.
        max_attempts = max_attempts if repair_enabled else 1
        root = Path(output_dir).resolve()
        root.mkdir(parents=True, exist_ok=False)
        started = time.perf_counter()
        result = RunResult(status="failed", constraints=constraints, repair_enabled=repair_enabled,
                           requirements=requirements)
        result.settings = {
            "token_budget": token_budget, "max_attempts": max_attempts,
            "requested_max_attempts": requested_attempts, "repair_enabled": repair_enabled,
            "retrieval_enabled": retrieval,
            "retrieval_policy": {"max_characters": RETRIEVAL_MAX_CHARACTERS,
                "initial_query": "unchanged_requirements_only",
                "repair_query": "requirements_and_primary_diagnostic",
                "scope": "prompt_evidence_only_not_project_dependencies",
                "conversation": "replace_retrieval_each_attempt",
                "additional_model_calls": 0},
            "context_directories": [str(Path(path).resolve()) for path in context],
            "instance_root": instance_root, "output_unit": "single_aadl_source", "generated_file_limit": 1,
            "prompt_layout": ({"schema": 2, "name": "domain_in_system_retrieval_before_original_task"}
                              if retrieval else
                              {"schema": 1, "name": "domain_in_system_original_requirements_last"}),
            "decoding": {"temperature": 0, "thinking": False, "batch_size": 1, "num_beams": 1},
            "repair_feedback": repair_feedback_policy(),
        }
        result.provenance = {
            "rule_bundle": {"path": str(self.bundle.path), "profile": self.bundle.profile,
                            "sha256": self.bundle.digest, "runtime_version": self.bundle.runtime_version,
                            "compiled_grammar_sha256": self.bundle.compiled_grammar_sha256,
                            "standard": self.bundle.semantics.METADATA.get("standard"),
                            "osate_revision": self.bundle.semantics.METADATA.get("osate_revision"),
                            "coverage": {key: self.bundle.semantics.METADATA.get(key) for key in (
                                "implemented_scope", "unsupported_checks", "instance_scope", "instance_unsupported_checks")}},
            "workflow_sha256": workflow_identity(), "versions": local_versions(),
        }
        # Keep reusable domain/transport guidance in system and the unchanged
        # requirement plus explicit context in the sole initial user message.
        messages = [{"role": "system", "content": "\n\n".join((
                        self.bundle.system_prompt, self.bundle.generation_prompt, PROJECT_INSTRUCTIONS))},
                    {"role": "user", "content": requirements}]
        if instance_root is not None:
            messages[1]["content"] += ("\n\nThe project will be instantiated at the explicitly selected root "
                                        + instance_root + ". Preserve that qualified implementation name.")
        dependency_snapshot = snapshot_context(())
        if instance_root is not None:
            result.validation_scope = "syntax_and_static_semantics_and_osate_instantiation_checks"
        repair_base_messages = None
        retrieval_index = None
        active_retrieval = None
        active_repair_feedback = None
        result.timing['repair_feedback_seconds'] = 0.0
        repaired_candidate_hashes = set()
        phase = "preflight"
        interrupted = False

        def progress(stage: str, **details) -> None:
            if on_progress is not None:
                try:
                    on_progress({"stage": stage, "constraints": constraints,
                                 "repair_enabled": repair_enabled, **details})
                except Exception:
                    # Terminal/UI observers cannot change inference or turn a
                    # measured model result into an infrastructure failure.
                    pass

        def save() -> None:
            result.timing["elapsed_seconds"] = time.perf_counter() - started
            _write_json(root / "result.json", asdict(result))

        def charge(usage: dict) -> None:
            for key in result.usage:
                result.usage[key] += usage[key]

        def prepare_retrieval(stage: str, diagnostics=None) -> None:
            """Replace prompt evidence without changing the project's symbol environment.

            Examples are informative text, never input to bind_context, the
            constraint engine or OSATE. Rebuilding from the clean task avoids
            accumulating unrelated initial/repaired retrievals across attempts.
            """
            nonlocal active_retrieval
            retrieval_started = time.perf_counter()
            try:
                if retrieval:
                    snapshot = retrieval_index.retrieve(
                        requirements, diagnostics=diagnostics,
                        max_characters=RETRIEVAL_MAX_CHARACTERS)
                else:
                    snapshot = {"enabled": False, "query": None, "entries": [],
                                "rendered_context": "", "elapsed_seconds": 0.0,
                                "sha256": None, "identity": retrieval_index.identity(),
                                "policy": {"disabled": True}}
                active_retrieval = {**snapshot, "enabled": retrieval, "stage": stage}
                result.provenance["retrieval"]["events"].append(deepcopy(active_retrieval))
                messages[:] = deepcopy(repair_base_messages)
                if active_retrieval["rendered_context"]:
                    messages[1]["content"] = (
                        "Retrieved reference evidence (quoted examples, not requirements or supplied libraries). "
                        "Do not import document titles or assume example declarations exist in this project.\n"
                        + active_retrieval["rendered_context"]
                        + "\n\nOriginal task and explicitly supplied dependencies:\n"
                        + messages[1]["content"])
            except (ValueError, OSError) as exc:
                # A missing/broken corpus is an infrastructure problem, not a
                # justified reason to silently turn a RAG condition into no-RAG.
                result.provenance["retrieval"]["error"] = str(exc)
                raise ModelError("Retrieval failed: " + str(exc), error_kind="infrastructure") from exc
            finally:
                result.timing["retrieval_seconds"] += time.perf_counter() - retrieval_started

        def generate(call_phase: str, limit: int, constraint):
            progress(call_phase, max_tokens=limit, call=len(result.calls) + 1)
            call = {"phase": call_phase, "max_tokens": limit, "status": "started",
                    "messages": deepcopy(messages), "constraint": deepcopy(constraint), "started_at": _utc_now(),
                    "context_sha256": dependency_snapshot.sha256,
                    "retrieval": deepcopy(active_retrieval),
                    "repair_feedback": deepcopy(active_repair_feedback)}
            result.calls.append(call)
            save()
            call_started = time.perf_counter()
            try:
                reply = self.client.generate(messages, max_tokens=limit, constraint=constraint)
            except ModelError as exc:
                call.update(status="error", error=str(exc), error_kind=getattr(exc, "error_kind", "model_protocol"),
                            usage=exc.usage, finish_reason=exc.finish_reason,
                            constraint_evidence=exc.applied_constraint,
                            error_response_metadata=json_value(exc.response_metadata))
                if exc.usage is not None:
                    charge(exc.usage)
                    if exc.usage["completion_tokens"] > limit:
                        raise ModelError("Server exceeded the requested completion-token budget") from exc
                else:
                    result.usage_complete = False
                if exc.partial_text is not None:
                    partial_path = root / f"call-{len(result.calls)}-partial.txt"
                    # Disable platform newline translation: CRLF returned by a
                    # model must not become CRCRLF when written on Windows.
                    partial_path.write_text(exc.partial_text, encoding="utf-8", newline="")
                    call["partial_output"] = str(partial_path)
                raise
            else:
                charge(reply.usage)
                call.update(status="completed", usage=reply.usage,
                            finish_reason=getattr(reply, "finish_reason", "stop"),
                            model=getattr(reply, "model", result.model), metadata=json_value(getattr(reply, "metadata", {})),
                            constraint_evidence=reply.applied_constraint, response_text=reply.text)
                if reply.usage["completion_tokens"] > limit:
                    call["status"] = "error"
                    raise ModelError("Server exceeded the requested completion-token budget")
                return reply
            finally:
                duration = time.perf_counter() - call_started
                call["elapsed_seconds"] = duration
                result.timing["generation_seconds"] += duration
                save()

        def assess(text: str, number: int, usage: dict | None, evidence: dict | None,
                   *, generation_complete: bool, termination: dict | None = None) -> dict:
            progress("static_check", attempt=number)
            # Run the independent checker even when our rules reject a candidate.
            # Otherwise false rejections would disappear from experimental evidence.
            attempt_root = root / f"attempt-{number}"
            attempt_root.mkdir()
            raw_path = attempt_root / "raw-response.txt"
            raw_path.write_text(text, encoding="utf-8", newline="")
            document = parse_project(text)
            candidate_path = attempt_root / "project"
            # No missing terminators or source bytes are invented. The parser
            # also exposes received files from a truncated project for OSATE.
            paths = write_project(document, candidate_path, dependency_snapshot)
            check_started = time.perf_counter()
            rule_error = None
            try:
                checked = engine.validate(text)
                valid, pending, diagnostics = checked.valid, list(checked.pending), list(checked.diagnostics)
                issues = json_value(getattr(checked, "issues", []))
            except (ValueError, RuleDefinitionError, ParseResourceError) as exc:
                # A broken rule specification is not a repairable model error,
                # but extracted sources still receive the independent check.
                valid, pending, diagnostics, rule_error = False, [], [str(exc)], str(exc)
                issues = []
            diagnostics += list(document.diagnostics)
            if not document.complete:
                diagnostics.append("Incomplete AADL-PROJECT container; no missing source or control records were added")
            static_seconds = time.perf_counter() - check_started
            result.timing["static_check_seconds"] += static_seconds
            previous = result.attempts[-1] if result.attempts else None
            candidate_hash = hashlib.sha256(text.encode('utf-8')).hexdigest()
            trace = (evidence or {}).get('mechanism_trace') or {}
            entry = {"attempt": number, "usage": usage, "constraint_evidence": evidence,
                     "generation_complete": generation_complete,
                     "generation_termination": deepcopy(termination),
                     "candidate_sha256": candidate_hash,
                     "candidate_changed": (candidate_hash != previous.get('candidate_sha256') if previous else None),
                     "focus_pattern_reobserved": None,
                     "focus_diagnostic_reobserved": None,
                     "focus_diagnostic_observation": None,
                     "previous_repair_focus": ({key: previous['repair_focus'].get(key)
                                                for key in ('source', 'line', 'pattern_id')}
                                               if previous and previous.get('repair_focus') else None),
                     "syntax_semantics_valid": valid, "pending": pending,
                     "diagnostics": diagnostics, "issues": issues, "candidate": str(candidate_path),
                     "raw_response": str(raw_path), "rule_error": rule_error,
                     "project_protocol": {"valid": document.valid, "complete": document.complete,
                                          "diagnostics": list(document.diagnostics),
                                          "files": [{"name": item.name, "complete": item.complete} for item in document.files]},
                     "sources": [{"name": path.relative_to(candidate_path).as_posix(),
                                  "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in paths],
                     "static_check_seconds": static_seconds, "accepted": False,
                     "prefix_evidence": {
                         "scope": "read_only_received_prefix_observations_not_completion_proof",
                         "received_characters": len(text), "received_files": len(document.files),
                         "explicitly_closed_files": sum(item.complete for item in document.files),
                         "container_complete": document.complete, "local_pending_reports": len(pending),
                         "committed_token_count": trace.get('committed_token_count'),
                         "committed_character_offset": trace.get('committed_character_offset'),
                         "project_obligations_closed": trace.get('project_obligations_closed')}}
            analysis_started = time.perf_counter()
            entry['prefix_evidence']['grammar_frontier'] = observe_prefix_structure(self.bundle, document)
            entry['repair_analysis_seconds'] = time.perf_counter() - analysis_started
            result.timing['repair_analysis_seconds'] = (result.timing.get('repair_analysis_seconds', 0.0)
                                                        + entry['repair_analysis_seconds'])
            result.attempts.append(entry)
            save()
            check_started = time.perf_counter()
            progress("osate", attempt=number)
            independent = (self.osate.check(candidate_path, attempt_root / "osate", instance_root=instance_root)
                           if document.files else {"status": "not_run", "diagnostics": [],
                                                   "reason": "No AADL source records could be extracted"})
            osate_seconds = time.perf_counter() - check_started
            result.timing["osate_seconds"] += osate_seconds
            entry.update(osate=independent, osate_seconds=osate_seconds)
            entry["diagnostics"].extend(d["message"] for d in independent.get("diagnostics", []))
            instance_check_started = time.perf_counter()
            instance_rules = check_instance_rules(self.bundle, independent)
            entry["local_instance_rules"] = instance_rules
            entry["diagnostics"].extend(instance_rules["diagnostics"])
            entry["instance_rule_seconds"] = time.perf_counter() - instance_check_started
            result.timing["static_check_seconds"] += entry["instance_rule_seconds"]
            entry["accepted"] = (generation_complete and document.valid and document.complete and valid
                                 and independent["status"] == "passed"
                                 and instance_rules["status"] in {"passed", "not_requested"})
            entry['final_validation'] = {
                'generation_complete': generation_complete, 'project_protocol_valid': document.valid,
                'project_protocol_complete': document.complete, 'local_rules_valid': valid,
                'local_rule_error': rule_error, 'osate_status': independent['status'],
                'instance_rules_status': instance_rules['status'], 'accepted': entry['accepted']}
            source_map = {item.name: item.text for item in document.files}
            prior_focus = previous.get('repair_focus') if previous else None
            # Keep generic validator-signature observation independent of the
            # language hook's narrower syntactic-pattern observation below.
            observation = reobserve_focus_diagnostic(prior_focus, source_map, independent, issues,
                                                     rule_error=rule_error,
                                                     previous_independent=previous.get('osate') if previous else None)
            entry['focus_diagnostic_observation'] = observation
            entry['focus_diagnostic_reobserved'] = observation['reobserved']
            if prior_focus is not None:
                # The language hook observes the old construct in new source.
                # False means "not seen again", never "the project is correct".
                entry['focus_pattern_reobserved'] = self.bundle.reobserve_repair_focus(prior_focus, source_map)
            # Focus selection does not rank warnings or unresolved references as
            # errors. The full validator evidence above remains the acceptance gate.
            entry['repair_advice'] = []
            entry['repair_focus'] = None
            if not entry['accepted'] and rule_error is None and independent['status'] in {'passed', 'failed', 'not_run'}:
                focus = select_repair_focus(source_map, independent.get('diagnostics', []), issues)
                entry['repair_focus'] = focus
                if focus is not None:
                    advice = self.bundle.repair_advice(
                        [{'name': item.name, 'text': item.text, 'complete': item.complete} for item in document.files],
                        [focus['diagnostic']])
                    # A hook must explain the selected location, not another
                    # plausible-looking construct elsewhere in the project.
                    entry['repair_advice'] = [item for item in advice
                        if _source_name(item.get('source'), source_map) == focus['source'] and item.get('line') == focus['line']]
                    if entry['repair_advice']:
                        focus['advice'] = entry['repair_advice'][0]
                        focus['pattern_id'] = focus['advice']['id']
            save()
            progress("assessed", attempt=number, local_valid=valid,
                     osate_status=independent["status"], accepted=entry["accepted"])
            return entry

        def append_repair(previous_text: str, entry: dict, remaining: int) -> None:
            nonlocal active_repair_feedback
            if remaining <= 0:
                # There will be no further generation, so do not incur or report
                # an unused retrieval as part of a nonexistent repair request.
                return
            candidate_hash = hashlib.sha256(previous_text.encode('utf-8')).hexdigest()
            repeated = bool(previous_text) and candidate_hash in repaired_candidate_hashes
            repaired_candidate_hashes.add(candidate_hash)
            # A bounded view of the actual candidate supplies the context that
            # a three-line diagnostic alone cannot provide. Raw output remains
            # unchanged; repeated lines are compressed only in the prompt view.
            feedback_started = time.perf_counter()
            document = parse_project(previous_text)
            sources = [{'name': source.name, 'text': source.text, 'complete': source.complete}
                       for source in document.files]
            prepared = prepare_repair_feedback(self.bundle, sources, entry, result.attempts)
            message = build_repair_message(self.bundle, prepared, remaining=remaining,
                                           previous_text=previous_text, repeated=repeated)
            evidence = build_repair_evidence(prepared)
            feedback_seconds = time.perf_counter() - feedback_started
            result.timing['repair_feedback_seconds'] += feedback_seconds
            # Persist exactly the projection used by the next call. This is
            # distinct from the complete validator records already on the attempt.
            entry['repair_feedback'] = {'policy': repair_feedback_policy(), 'evidence': evidence,
                                         'construction_seconds': feedback_seconds}
            active_repair_feedback = {
                'schema_version': REPAIR_FEEDBACK_VERSION, 'source_attempt': entry.get('attempt'),
                'evidence_sha256': hashlib.sha256(_repair_json(evidence).encode('utf-8')).hexdigest(),
                'evidence_utf8_bytes': len(_repair_json(evidence).encode('utf-8')),
                'message_sha256': hashlib.sha256(message.encode('utf-8')).hexdigest(),
                'message_utf8_bytes': len(message.encode('utf-8')),
            }
            focus = entry.get('repair_focus') or {}
            # Use the same first located ERROR as the existing feedback policy.
            # Unresolved obligations remain observations, never fabricated errors.
            retrieval_diagnostics = {
                "primary_error": focus.get('diagnostic'),
                "rule_advice": focus.get('advice'),
                "termination_observation": entry.get('repair_cause'),
                "pending_observations": list(entry.get('pending', []))[:4],
            }
            prepare_retrieval("repair", diagnostics=retrieval_diagnostics)
            messages.append({'role': 'user', 'content': message})

        def infrastructure_failed(entry: dict) -> bool:
            if entry["rule_error"] is not None:
                result.failure_kind, result.error = "infrastructure", "Rule evaluation failed: " + entry["rule_error"]
                return True
            instance_rules = entry["local_instance_rules"]
            if instance_rules["status"] in {"unsupported", "error"}:
                result.failure_kind = "unsupported" if instance_rules["status"] == "unsupported" else "infrastructure"
                result.error = "Instance rule checks failed: " + "; ".join(instance_rules["diagnostics"])
                return True
            if entry["osate"]["status"] in {"passed", "failed", "not_run"}:
                return False
            result.failure_kind = "infrastructure"
            result.error = "OSATE infrastructure failure: " + "; ".join(entry["diagnostics"])
            return True

        try:
            progress("preflight")
            retrieval_started = time.perf_counter()
            try:
                retrieval_index = RetrievalIndex()
                result.provenance["retrieval"] = {
                    "enabled": retrieval, **retrieval_index.identity(), "events": []}
            except (ValueError, OSError) as exc:
                result.provenance["retrieval"] = {"enabled": retrieval, "error": str(exc), "events": []}
                raise ModelError("Retrieval initialization failed: " + str(exc),
                                 error_kind="infrastructure") from exc
            finally:
                result.timing["retrieval_seconds"] += time.perf_counter() - retrieval_started
            self.client.bind_rules(self.bundle)
            dependency_snapshot = snapshot_context(result.settings["context_directories"])
            self.client.bind_context(dependency_snapshot)
            result.provenance["context"] = dependency_snapshot.as_dict()
            if dependency_snapshot.files:
                messages[1]["content"] += "\n\n" + dependency_snapshot.render_prompt()
            # Resolve local infrastructure before contacting the model service;
            # a configured missing CLI cannot be repaired by model inference.
            availability = self.osate.probe()
            result.provenance["osate"] = availability
            if availability["status"] != "passed":
                result.failure_kind = "infrastructure"
                details = "; ".join(issue["message"] for issue in availability.get("diagnostics", []))
                result.error = "OSATE is unavailable; independent model validation is required." + (
                    " " + details if details else "")
                return result
            endpoint = self.client.discover()
            result.model, result.backend = endpoint.model, endpoint.backend
            result.provenance["endpoint"] = endpoint_snapshot(endpoint)
            server_limit = endpoint.metadata.get("max_new_tokens", token_budget)
            if type(server_limit) is not int or server_limit < 1:
                raise ModelError("Endpoint max_new_tokens must be a positive integer")
            result.settings["server_max_new_tokens"] = server_limit
            required_kind = {"plain": "plain", "joint": "aadl_joint"}[constraints]
            if required_kind not in endpoint.constraint_types:
                result.failure_kind = "unsupported"
                raise ModelError(f"Endpoint does not support {required_kind}; no decoder fallback is permitted.")
            engine = self.engine_factory(bundle=self.bundle, context=dependency_snapshot, project=True)
            repair_base_messages = deepcopy(messages)
            prepare_retrieval("initial")
            phase = "generation"
            joint = {"kind": "aadl_joint", "profile": self.bundle.profile, "project": True}
            decoding = (joint if constraints == "joint" else
                        {"kind": "plain", "project": True})
            for number in range(1, max_attempts + 1):
                remaining = token_budget - result.usage["completion_tokens"]
                if remaining <= 0:
                    result.failure_kind, result.error = "budget_exhausted", "The shared completion-token budget is exhausted."
                    break
                try:
                    reply = generate("generation", min(server_limit, remaining), decoding)
                except ModelError as exc:
                    entry = None
                    if exc.partial_text is not None:
                        entry = assess(exc.partial_text, number, exc.usage, exc.applied_constraint,
                                       generation_complete=False, termination=exc.response_metadata.get('termination'))
                        entry["diagnostics"].append(str(exc))
                        entry["decoding_dead_end"] = exc.error_kind == "decoding_dead_end"
                        entry["generation_truncated"] = exc.error_kind == "truncated"
                        entry["generation_timeout"] = exc.error_kind == "generation_timeout"
                        if infrastructure_failed(entry):
                            return result
                    if (not exc.retryable or exc.usage is None
                            or exc.error_kind not in {"decoding_dead_end", "truncated"}):
                        raise
                    result.failure_kind, result.error = exc.error_kind, str(exc)
                    remaining = token_budget - result.usage["completion_tokens"]
                    if not repair_enabled or remaining <= 0 or number == max_attempts:
                        # Preserve the actual model failure when the shared
                        # budget/attempt limit leaves no further repair request.
                        save()
                        break
                    termination = exc.response_metadata.get("termination", {})
                    cause = ("The previous response repeated a periodic text fragment and was stopped."
                             if termination.get("kind") == "repetition" and termination.get("unit") == "periodic_suffix" else
                             "The previous response repeated unfinished source lines and was stopped."
                             if termination.get("kind") == "repetition" else
                             "The previous response reached its output token limit before completion."
                             if exc.error_kind == "truncated" else
                             "The previous generation reached a decoding dead end.")
                    # Truncation/dead-end candidates received the same full
                    # validators as normal responses. Feed those diagnostics
                    # back too; a generic 'try again' hides the actual syntax
                    # mistake that may have led to repetitive legal trivia.
                    feedback_entry = entry if entry is not None else {'diagnostics': [str(exc)], 'pending': []}
                    feedback_entry['repair_cause'] = cause
                    append_repair(exc.partial_text or '', feedback_entry, remaining)
                    save()
                    continue
                entry = assess(reply.text, number, reply.usage, reply.applied_constraint, generation_complete=True)
                if infrastructure_failed(entry):
                    return result
                if entry["accepted"]:
                    accepted = root / "project"
                    # Copy only the assessed source tree, including explicit
                    # dependency snapshots. Diagnostics remain outside it.
                    shutil.copytree(entry["candidate"], accepted)
                    result.status, result.output = "passed", str(accepted)
                    result.failure_kind = result.error = None
                    return result
                result.failure_kind = ("project_protocol" if not entry["project_protocol"]["valid"] else
                                       "instantiation_failed" if (entry["osate"].get("instance", {}).get("status") == "failed"
                                                                  or entry["local_instance_rules"]["status"] == "failed") else
                                       "model_invalid")
                result.error = "No complete project passed both the supported static checks and OSATE."
                if repair_enabled and number < max_attempts:
                    append_repair(reply.text, entry, token_budget - result.usage['completion_tokens'])
        except ModelError as exc:
            if result.failure_kind != "unsupported":
                result.failure_kind = getattr(exc, "error_kind", "model_protocol")
            result.error = str(exc)
        except (RuleDefinitionError, ParseResourceError) as exc:
            result.failure_kind, result.error = "infrastructure", "Rule evaluation failed: " + str(exc)
        except ValueError as exc:
            result.failure_kind = "validation_error"
            result.error = str(exc)
        except OSError as exc:
            result.failure_kind, result.error = "infrastructure", str(exc)
        except BaseException:
            # Preserve incomplete-run evidence before propagating cancellation or
            # a programming fault. A started request has unknown consumption.
            interrupted = True
            result.failure_kind, result.error = "interrupted", "Run interrupted before completing its validation workflow"
            if any(call["status"] == "started" for call in result.calls):
                result.usage_complete = False
            raise
        finally:
            result.completed = not interrupted
            result.finished_at = _utc_now()
            save()
            progress("finished", status=result.status, failure_kind=result.failure_kind)
        return result
