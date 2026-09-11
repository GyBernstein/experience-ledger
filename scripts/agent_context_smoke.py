#!/usr/bin/env python3
"""Writes data and changes bindings. Run only in an isolated test space."""
import json,os,uuid,urllib.request,urllib.error
from datetime import datetime,timezone
base=os.environ.get('LEDGER_URL','http://localhost:8080');human=os.environ['LEDGER_TOKEN'];agent=os.environ['LEDGER_AGENT_TOKEN'];checks=[]
def call(path,body=None,token=human,status=200):
 req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=35) as r:code=r.status;raw=r.read()
 except urllib.error.HTTPError as e:code=e.code;raw=e.read()
 data=json.loads(raw) if raw else None
 assert code==status,(path,code,status,data)
 return data
now=lambda:datetime.now(timezone.utc).isoformat()
domain='test_'+uuid.uuid4().hex[:8]
rules=dict(domains=[domain],taskTypes=['equipment_diagnosis'],allowedOrigins=['OBSERVED','HUMAN_ASSERTED','AGENT_DERIVED','SYSTEM_DERIVED'],maxContextTokens=8000,maxEvidence=5,maxItems=4,minConfidence=.8,minEvidenceReliability=.5,maxAgeDays=3650,requireEvidence=True,requireNegativeCases=True,allowUnknownScope=False,allowDeepSearch=False,candidateLimit=50,deepCandidateLimit=200)
policy=call('/api/v2/policies',dict(name=domain,enabled=True,rules=rules,reason='test'))
for kind,who in [('HUMAN',os.environ.get('LEDGER_HUMAN_ID','demo-reviewer')),('AGENT',os.environ.get('LEDGER_AGENT_ID','demo-agent'))]:
 old=next((x for x in call('/api/v2/bindings') if x['actor_type']==kind and x['actor_id']==who),None)
 b=dict(actorType=kind,actorId=who,policyId=policy['id'],enabled=True,expectedRevision=old['revision'] if old else 0,reason='test')
 first=call('/api/v2/bindings',b);second=call('/api/v2/bindings',{**b,'expectedRevision':first['revision']});assert second['revision']==first['revision']+1
assert call('/api/v2/me',token=agent)['policy']['id']==policy['id']
call('/api/v1/experiences/search',{'query':''},agent,403);call('/api/v2/policies',token=agent,status=403)
checks.append('identity binding CAS and old API bypass prevention')
def publish(title,negative=False):
 e=call('/api/v1/evidence',dict(evidenceType='TEST_RESULT',snapshot={'text':title},observedAt=now(),reliability=.9))
 c=call('/api/v1/candidates/capture',dict(content=title,dedupKey=uuid.uuid4().hex))
 draft=dict(title=title,summary='pump case',decision='inspect safely',action='measure vibration',outcomeSummary='observed result',lesson=title,validFrom=now(),applicability={'assetType':'pump','loadMin':50,'loadMax':90},constraints={'maintenanceWindow':True},claims=[dict(claimType='OBSERVATION',originType='OBSERVED',content=title,evidence=[dict(evidenceId=e['id'],supportType='SUPPORTS')])])
 for attempt in range(5):
  c=call('/api/v1/candidates/'+c['id'])
  try:c=call('/api/v1/candidates/'+c['id']+'/review',dict(expectedRevision=c['revision'],draft=draft,reason='test'));break
  except AssertionError as error:
   if 'CANDIDATE_REVISION_CONFLICT' not in str(error) or attempt==4:raise
 v=call('/api/v1/candidates/'+c['id']+'/verify',dict(mode='CREATE_NEW_FAMILY',experienceKey=uuid.uuid4().hex,domain=domain,experienceType='FAILURE' if negative else 'BEST_PRACTICE',expectedRevision=c['revision'],reason='test'))
 call('/api/v2/versions/'+v['id']+'/validations',dict(status='VERIFIED',assessedConfidence=.9,reason='engineer assessment'));return v,e
v1,e1=publish('Inspect pump vibration');v2,e2=publish('Repeat pump measurement');v3,e3=publish('Avoid bearing replacement before eliminating sensor error',True)
c=call('/api/v2/compacts',dict(title='Pump checks',summary='Verify sensor first. Repeat stable-load measurements.',domain=domain,taskType='equipment_diagnosis',representativeId=v1['id'],versionIds=[v1['id'],v2['id']],reason='reviewed cluster'))
call('/api/v2/compacts/'+c['id']+'/approve',{'reason':'reviewed sources'})
req=dict(domain=domain,taskType='equipment_diagnosis',assetType='pump',context={'load':80,'maintenanceWindow':True},maxContextTokens=8000,maxEvidence=5,minConfidence=.8,needNegativeCases=True,needEvidence=True)
r=call('/api/v2/context',req,agent)
assert r['status']=='READY' and r['budget']['contextUnits']==len(r['contextText'].encode())<=8000
assert any(s['compactId']==c['id'] for s in r['selected']) and any(s['negative'] for s in r['selected'])
assert len({e['evidenceId'] for s in r['selected'] for e in s['evidence']})<=5
assert 'HUMAN_ASSERTED' in r['contextText'] and 'OBSERVED' in r['contextText']
checks.append('Compact provenance, negative reservation and context/evidence budgets')
assert call('/api/v2/context',req,agent)['diagnostics']['hotCacheHits']>=1
observed=call('/api/v2/policies',dict(name='observed only',enabled=True,rules={**rules,'allowedOrigins':['OBSERVED']},reason='test'))
assert all(s['compactId'] is None for s in call('/api/v2/context',{**req,'policyId':observed['id']})['selected'])
checks.append('cache reuse and summary origin policy')
for patch in [{'policyId':policy['id']},{'domain':'other'},{'deepSearch':True}]:call('/api/v2/context',{**req,**patch},agent,403)
for patch in [{'context':{'load':80}},{'context':{'load':95,'maintenanceWindow':True}},{'minConfidence':.95}]:assert not call('/api/v2/context',{**req,**patch},agent)['selected']
assert call('/api/v2/context',{**req,'maxContextTokens':256},agent)['status']=='ESCALATE_HUMAN'
checks.append('scope/confidence enforcement and budget escalation')
eid=r['selected'][0]['evidence'][0]['evidenceId'];call('/api/v2/evidence/'+eid+'?runId='+r['runId'],token=agent)
call('/api/v1/evidence/'+eid,token=agent,status=403)
h=call('/api/v2/context',{**req,'policyId':policy['id']});call('/api/v2/evidence/'+eid+'?runId='+h['runId'],token=agent,status=404)
checks.append('evidence ownership and bypass prevention')
f=dict(runId=r['runId'],versionId=v1['id'],eventKey=uuid.uuid4().hex,adopted=True,outcomeType='SUCCESS',evaluation='diagnosis worked',actualInputTokens=100,actualOutputTokens=30,reportedCost=.02,currency='CNY')
a=call('/api/v2/feedback',f,agent);assert call('/api/v2/feedback',f,agent)['id']==a['id']
call('/api/v2/feedback',{**f,'evaluation':'different'},agent,409)
b=call('/api/v2/feedback',{**f,'eventKey':uuid.uuid4().hex,'outcomeType':'FAILURE'},agent);assert a['usage_id']==b['usage_id']
assert len(call('/api/v1/usages/'+a['usage_id']+'/outcomes'))==2
call('/api/v2/feedback/'+a['id']+'/review',dict(verdict='REJECTED',reason='human rejected interpretation'))
ops=call('/api/v2/operations');assert any(x['id']==a['id'] and x['verdict']=='REJECTED' for x in ops['feedback']);assert any(x['reported_cost']>=.04 for x in ops['costs'])
checks.append('feedback idempotency, one Usage multiple Outcomes, costs and human veto')
call('/api/v2/versions/'+v1['id']+'/validations',dict(status='DISPUTED',assessedConfidence=.9,reason='new assessment'))
assert not next(x for x in call('/api/v2/compacts') if x['id']==c['id'])['fresh']
assert all(s['compactId']!=c['id'] for s in call('/api/v2/context',req,agent)['selected'])
checks.append('validation invalidates hot Compact')
call('/api/v1/evidence/'+e3['id']+'/correct?reason=test',dict(evidenceType='TEST_RESULT',snapshot={'text':'correction'},observedAt=now(),reliability=.9))
assert call('/api/v2/context',req,agent)['status']=='ESCALATE_HUMAN'
checks.append('corrected evidence and mandatory negative case enforcement')
payload=dict(name=domain,enabled=False,rules=rules,expectedRevision=policy['revision'],reason='disable')
call('/api/v2/policies/'+policy['id'],payload);call('/api/v2/context',req,agent,403);call('/api/v2/policies/'+policy['id'],payload,status=409)
checks.append('policy disabling and revision CAS')
print(json.dumps(dict(checks='PASS',count=len(checks),scenarios=checks),indent=2))
