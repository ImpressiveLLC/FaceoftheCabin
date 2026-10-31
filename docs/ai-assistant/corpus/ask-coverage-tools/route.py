import json, re, sys
fx = json.load(open(sys.argv[1]))
cats = fx['categories']
def compile_cat(c):
    pats=[]
    for k in c.get('keywordPatterns') or []:
        pats.append((re.compile(r'(?<![a-z0-9_])'+re.escape(k.lower().strip())+r'(?:s|es)?(?![a-z0-9_])'), len(k.strip().split())))
    return pats
comp=[(c,compile_cat(c)) for c in cats]
def route(q):
    n=re.sub(r'\s+',' ',q.lower())
    best=None;bs=0;allsc=[]
    for c,pats in comp:
        s=0;m=False;hits=[]
        for p,w in pats:
            if p.search(n): s+=w;m=True;hits.append(p.pattern.split('(?<![a-z0-9_])')[1].split('(?:s|es)')[0])
        if m: s+= c.get('boost') or 0
        if s>0: allsc.append((c['id'],s,hits))
        if s>bs: best=c;bs=s
    return best,bs,allsc
for line in open(sys.argv[2]):
    line=line.rstrip('\n')
    if not line or line.startswith('#'): continue
    qid,q=line.split('|',1)
    b,s,a=route(q)
    if b is None:
        print(f"{qid}: NO CATEGORY -> word-overlap over KB nodes")
    else:
        docs=[(d['section'][:40],d.get('audience','administrator')) for d in b.get('contextDocuments') or []]
        print(f"{qid}: {b['id']} score={s} docs={docs} | all={a}")
