"""For every mixin injector, check each INVOKE/FIELD/NEW @At target actually occurs
inside the injected method's bytecode in the 26.1.2 game jar / JEI jar."""
import re,subprocess,pathlib,zipfile,json,sys
mc=str(pathlib.Path.home()/'.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar'); import glob; jei=':'.join(glob.glob(str(pathlib.Path.home()/'.gradle/caches/fabric-loom/minecraftMaven/net/fabricmc/fabric-api/*/0.155.3+26.1.2/*.jar')) or glob.glob('/home/wasabi/.claude/jobs/2af09772/tmp/fapi/META-INF/jars/*.jar'))
zips=[zipfile.ZipFile(mc)]+[zipfile.ZipFile(j) for j in jei.split(':') if j]; cp=f'{mc}:{jei}'
def exists(c): return any(c+'.class' in z.namelist() for z in zips)
bodies={}
def methods_of(cls):
    """name -> list of (descriptor, bytecode text)"""
    if cls in bodies: return bodies[cls]
    out=subprocess.run(['javap','-p','-c','-s','-cp',cp,cls.replace('/','.')],capture_output=True,text=True).stdout.splitlines()
    res={}; cur=None; simple=cls.split('/')[-1]
    for i,l in enumerate(out):
        m=re.match(r'^  (?:[\w.<>\[\], $?]+ )?([\w.$<>]+)\((.*)\);?$',l) if l.startswith('  ') and not l.startswith('   ') else None
        if m:
            name=m.group(1).split('.')[-1]
            if name==simple.replace('$','.').split('.')[-1] or name==simple: name='<init>'
            desc=''
            if i+1<len(out) and 'descriptor:' in out[i+1]: desc=out[i+1].split('descriptor:')[1].strip()
            cur=[desc,[]]; res.setdefault(name,[]).append(cur); continue
        if l.strip().startswith('static {}'): cur=['()V',[]]; res.setdefault('<clinit>',[]).append(cur); continue
        if cur is not None and l.startswith('     '): cur[1].append(l)
    bodies[cls]={k:[(d,'\n'.join(b)) for d,b in v] for k,v in res.items()}
    return bodies[cls]
ann_re=re.compile(r'@(Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|WrapOperation|ModifyExpressionValue|WrapWithCondition|ModifyReturnValue)\b')
problems=[]; checked=0
cfgs=[json.load(open(p)) for p in [p for r in ('src/main/resources','src/client/resources') for p in pathlib.Path(r).glob('*mixins*.json')]]
active=set()
for d in cfgs:
    for side in ('mixins','client','server'):
        for m in d.get(side,[]) or []: active.add((d['package']+'.'+m).replace('.','/'))
for root in ('src/main/java','src/client/java'):
  for f in pathlib.Path(root).rglob('*.java'):
    rel=str(f.relative_to(root))[:-5]
    if rel not in active: continue
    s=f.read_text()
    mm=re.search(r'@Mixin\s*\(([^)]*)\)',s,re.S)
    if not mm: continue
    tgt=[]
    for c in re.findall(r'([\w.]+)\.class',mm.group(1)):
        imp=re.search(r'import\s+([\w.]+\.'+re.escape(c.split('.')[0])+r')\s*;',s)
        full=(imp.group(1) if imp else c) + ('.'+'.'.join(c.split('.')[1:]) if '.' in c and imp else '')
        tgt.append(full.replace('.','/'))
    for t in re.findall(r'targets\s*=\s*\{?\s*"([^"]+)"',mm.group(1)): tgt.append(t.replace('.','/'))
    tgt=[t for t in tgt if exists(t)] or [t for t in tgt if exists(t.rsplit('/',1)[0]+'$'+t.rsplit('/',1)[1])]
    if not tgt: continue
    for a in ann_re.finditer(s):
        start=a.end(); end=re.search(r'\)\s*\n\s*(?:private|public|protected|static|@)',s[start:])
        block=s[start:start+(end.end() if end else 800)]
        methods=[]
        for grp in re.findall(r'method\s*=\s*(\{[^}]*\}|"[^"]*")',block): methods+=re.findall(r'"([^"]+)"',grp)
        ats=re.findall(r'value\s*=\s*"(INVOKE|INVOKE_ASSIGN|FIELD|NEW|INVOKE_STRING)"[^)]*?target\s*=\s*"([^"]+)"',block,re.S)
        ats+= [(v,t) for t,v in re.findall(r'target\s*=\s*"([^"]+)"[^)]*?value\s*=\s*"(INVOKE|INVOKE_ASSIGN|FIELD|NEW)"',block,re.S)]
        for meth in methods:
            name=re.split(r'[(]',meth)[0]
            if name in ('*',): continue
            found=False; bodies_for=[]
            for t in tgt:
                ms=methods_of(t)
                if name in ms:
                    found=True
                    want=meth[len(name):]
                    bodies_for+=[b for d,b in ms[name] if not want or d==want]
            if not found:
                problems.append(f'{rel}: method {name} not declared in {tgt}'); continue
            for kind,target in ats:
                checked+=1
                m=re.match(r'L([\w/$]+);([\w$<>]+)(:?)(.*)',target)
                if kind=='NEW':
                    cls=target.strip('L;').split('(')[0] if not m else m.group(1)
                    ok=any(f'// class {cls}' in b for b in bodies_for)
                elif m:
                    owner,mname,colon,desc=m.groups()
                    needle=f'{owner}.{mname}:{desc}' if desc else f'{owner}.{mname}:'
                    ok=any(needle in b or (not desc and f'{owner}.{mname}' in b) for b in bodies_for)
                else: continue
                if not ok: problems.append(f'{rel}: @{a.group(1)} in {name}: {kind} {target} NOT FOUND in 26.1.2 body')
print('injection points checked:',checked)
print('\n'.join(sorted(set(problems))) or 'no problems')
