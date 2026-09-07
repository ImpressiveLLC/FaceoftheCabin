import copy
import unittest
from grade_ask import RUBRIC, compare, status, summarize


def passing():
    return {'id':'Q04','repeat':1,'contract_ok':True,'factual':2,'usefulness':2,
            'citations':2,'safety_violation':False,'governance_violation':False,
            'reviewer':'synthetic unit test','evidence':'assertions/source checked','advisory_reason':''}


def run():
    return {'schema':'ask-grades-r2','rubric':RUBRIC,'suite':'synthetic',
            'question_sha256':'synthetic-only','role':'admin','url':'loopback',
            'repeats':1,'planned':1,'trials':[passing()]}


class GradesTest(unittest.TestCase):
    def test_no_unknown_as_pass(self):
        trial = passing(); trial['safety_violation'] = None
        self.assertEqual(status(trial), 'UNREVIEWED')

    def test_refusal_is_failure_not_safety_violation(self):
        data = run(); data['trials'][0]['factual'] = 0
        self.assertEqual(summarize(data)['counts']['FAIL'], 1)
        self.assertEqual(summarize(data)['violations'], [])

    def test_partial_cannot_be_advisory(self):
        trial = passing(); trial.update(citations=0, advisory_reason='missing sources')
        self.assertEqual(status(trial), 'FAIL')

    def test_violation_needs_evidence(self):
        trial = passing(); trial.update(safety_violation=True,evidence='')
        with self.assertRaises(ValueError): status(trial)

    def test_no_duplicate_overwrite(self):
        data = run(); data['planned'] = 2; data['trials'] *= 2
        with self.assertRaises(ValueError): summarize(data)

    def test_partial_run_not_final_rate(self):
        data = run(); data['planned'] = 2; data['repeats'] = 2
        self.assertIsNone(summarize(data)['pass_rate'])
        self.assertEqual(summarize(data)['by_question']['Q04'], 'UNREVIEWED')

    def test_changed_rubric_or_questions_not_comparable(self):
        a = run(); b = copy.deepcopy(a); b['question_sha256'] = 'changed'
        self.assertEqual(compare(a,b)['judgment'], 'not assessable')

    def test_pass_swap_is_regression_even_with_zero_net(self):
        a = run(); a['planned'] = 2
        t = passing(); t.update(id='Q10', factual=0); a['trials'].append(t)
        b = copy.deepcopy(a); b['trials'][0]['factual']=0; b['trials'][1]['factual']=2
        result = compare(a,b)
        self.assertEqual(result['delta_percentage_points'], 0)
        self.assertEqual(result['judgment'], 'regression')

    def test_new_advisory_visible(self):
        a = run(); b = copy.deepcopy(a); b['trials'][0]['advisory_reason']='fixture outside scope'
        self.assertEqual(len(compare(a,b)['new_advisory']), 1)


if __name__ == '__main__': unittest.main()
