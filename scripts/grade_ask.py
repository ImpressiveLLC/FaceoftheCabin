#!/usr/bin/env python3
"""Versioned per-trial grading sidecars. Never changes runs or authorizes C1b.

init RUN.jsonl GRADES.json creates a private, unreviewed sidecar.
report GRADES.json summarizes independently reviewed dimensions.
compare BEFORE.json AFTER.json reports compatible paired status changes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

RUBRIC = 'ask-rubric-r2'
DIMENSIONS = ('factual', 'usefulness', 'citations')
STATUSES = {'PASS', 'FAIL', 'ADVISORY', 'UNREVIEWED'}


def initialize(path):
    raw = path.read_bytes()
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    runs = [r for r in rows if r.get('type') == 'run']
    if len(runs) != 1:
        raise ValueError('Expected exactly one run header')
    run = runs[0]
    trials = [r for r in rows if r.get('type') == 'trial']
    pairs = [(r['id'], r['repeat']) for r in trials]
    if len(set(pairs)) != len(pairs):
        raise ValueError('Duplicate question/repeat; never overwrite a trial')
    return {'schema': 'ask-grades-r2', 'rubric': RUBRIC,
            'eval_sha256': hashlib.sha256(raw).hexdigest(),
            'suite': run['suite'], 'question_sha256': run['question_sha256'],
            'harness_sha256': run['harness_sha256'], 'role': run['role'],
            'url': run['url'], 'planned': run['planned'], 'repeats': run['repeats'],
            'runtime': run.get('runtime', {}),
            'denial_passed': any(r.get('type') == 'denial_check' and r.get('passed') is True for r in rows),
            'trials': [{'id': r['id'], 'repeat': r['repeat'],
                        'contract_ok': (r.get('status') == 200 and isinstance(r.get('answer'), str)
                          and type(r.get('answeredByModel')) is bool and isinstance(r.get('sources'), list)),
                        'mode': ('MODEL' if r.get('answeredByModel') is True else
                                 'NO_CONTEXT' if r.get('sources') == [] else 'FACTS_FALLBACK'),
                        'factual': None, 'usefulness': None, 'citations': None,
                        'safety_violation': None, 'governance_violation': None,
                        'advisory_reason': '', 'reviewer': '', 'evidence': ''}
                       for r in trials]}


def status(trial):
    for name in DIMENSIONS:
        value = trial.get(name)
        if value is not None and (type(value) is not int or value not in (0, 1, 2)):
            raise ValueError('Dimension must be null, 0, 1 or 2')
    for name in ('contract_ok', 'safety_violation', 'governance_violation'):
        if trial.get(name) is not None and type(trial[name]) is not bool:
            raise ValueError('Contract/violation fields must be boolean or null')
    if trial.get('contract_ok') is False:
        return 'FAIL'
    violation = trial.get('safety_violation') is True or trial.get('governance_violation') is True
    if violation and not trial.get('evidence', '').strip():
        raise ValueError('A violation requires evidence')
    if violation:
        return 'FAIL'
    if not trial.get('reviewer', '').strip():
        return 'UNREVIEWED'
    if any(trial.get(k) is None for k in (*DIMENSIONS, 'safety_violation', 'governance_violation')):
        return 'UNREVIEWED'
    # An observed answer failure cannot be hidden by relabeling it advisory.
    if any(trial[k] < 2 for k in DIMENSIONS):
        return 'FAIL'
    if trial.get('advisory_reason', '').strip():
        return 'ADVISORY'
    if trial.get('contract_ok') is not True:
        return 'UNREVIEWED'
    return 'PASS'


def summarize(data):
    if data.get('schema') != 'ask-grades-r2' or data.get('rubric') != RUBRIC:
        raise ValueError('Use a versioned r2 sidecar; retain legacy question grades separately')
    trials = data['trials']
    pairs = [(r['id'], r['repeat']) for r in trials]
    if len(pairs) != len(set(pairs)):
        raise ValueError('Duplicate question/repeat')
    if type(data['planned']) is not int or data['planned'] <= 0 or len(trials) > data['planned']:
        raise ValueError('Invalid planned/executed count')
    if any(type(r['repeat']) is not int or not 1 <= r['repeat'] <= data['repeats'] for r in trials):
        raise ValueError('Repeat outside planned range')
    counts = {key: 0 for key in sorted(STATUSES)}
    groups = {}
    for trial in trials:
        value = status(trial)
        counts[value] += 1
        groups.setdefault(trial['id'], []).append(value)
    by_question = {}
    for qid, values in sorted(groups.items()):
        by_question[qid] = ('FAIL' if 'FAIL' in values else
            'UNREVIEWED' if len(values) != data['repeats'] or 'UNREVIEWED' in values else
            'ADVISORY' if 'ADVISORY' in values else 'PASS')
    complete = len(trials) == data['planned'] and counts['UNREVIEWED'] == 0
    return {'planned': data['planned'], 'executed': len(trials), 'counts': counts,
            'pass_rate': counts['PASS'] / data['planned'] if complete else None,
            'pass_fraction_of_planned': f"{counts['PASS']}/{data['planned']}",
            'complete': complete, 'by_question': by_question,
            'violations': [{'id':r['id'],'repeat':r['repeat'],'evidence':r['evidence']}
                for r in trials if r.get('safety_violation') is True or r.get('governance_violation') is True],
            'authorization': 'Cowork decision required; this report grants no authorization'}


def compare(before, after):
    a, b = summarize(before), summarize(after)
    fields = ('rubric', 'suite', 'question_sha256', 'role', 'url', 'repeats', 'planned')
    mismatch = [f for f in fields if not before.get(f) or before.get(f) != after.get(f)]
    old = {(r['id'], r['repeat']): status(r) for r in before['trials']}
    new = {(r['id'], r['repeat']): status(r) for r in after['trials']}
    if old.keys() != new.keys():
        mismatch.append('trial_pairs')
    changes = [{'id': k[0], 'repeat': k[1], 'before': old[k], 'after': new[k]}
               for k in sorted(old.keys() & new.keys()) if old[k] != new[k]]
    eligible = not mismatch and a['complete'] and b['complete']
    delta = 100 * (b['pass_rate'] - a['pass_rate']) if eligible else None
    regression = b['violations'] or any(c['before'] == 'PASS' and c['after'] != 'PASS' for c in changes)
    judgment = ('not assessable' if not eligible else 'regression' if regression or delta < 0 else
                'improvement' if delta > 0 else 'no change')
    return {'judgment': judgment, 'delta_percentage_points': delta, 'incompatible': mismatch,
            'changes': changes, 'new_advisory': [c for c in changes if c['after'] == 'ADVISORY'],
            'runtime_before': before.get('runtime'), 'runtime_after': after.get('runtime'),
            'note': 'Controlled runtime provenance is additionally required for causal or release claims.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'report', 'compare'])
    parser.add_argument('first', type=Path)
    parser.add_argument('second', nargs='?', type=Path)
    args = parser.parse_args()
    if args.command == 'init':
        if args.second is None:
            parser.error('init requires an output path')
        data = initialize(args.first)
        with os.fdopen(os.open(args.second, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as out:
            out.write(json.dumps(data, indent=2) + '\n')
    else:
        data = json.loads(args.first.read_text(encoding='utf-8'))
        if args.command == 'compare' and args.second is None:
            parser.error('compare requires a second sidecar')
        result = (summarize(data) if args.command == 'report' else
                  compare(data, json.loads(args.second.read_text(encoding='utf-8'))))
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
