import re,subprocess,pathlib,zipfile,json
mc=str(pathlib.Path.home()/'.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar'); import glob; jei=':'.join(glob.glob(str(pathlib.Path.home()/'.gradle/caches/fabric-loom/minecraftMaven/net/fabricmc/fabric-api/*/0.155.3+26.1.2/*.jar')) or glob.glob('/home/wasabi/.claude/jobs/2af09772/tmp/fapi/META-INF/jars/*.jar'))
zips=[zipfile.ZipFile(mc)]+[zipfile.ZipFile(j) for j in jei.split(':') if j]; cp=f'{mc}:{jei}'
def exists(c): return any(c+'.class' in z.namelist() for z in zips)
memo={}
def members(cls, own_only=False):
    """all field+method names of cls and its superclasses (within MC/JEI/JDK)"""
    key=(cls,own_only)
    if key in memo: return memo[key]
    names=set(); c=cls; seen=0
    while c and seen<(1 if own_only else 12):
        seen+=1
        out=subprocess.run(['javap','-p','-cp',cp,c.replace('/','.')],capture_output=True,text=True).stdout
        if not out: break
        for l in out.splitlines()[1:]:
            m=re.search(r'([\w$]+)\(',l)
            if m: names.add(m.group(1))
            f=re.search(r'\s([\w$]+);\s*$',l)
            if f and '(' not in l: names.add(f.group(1))
        sup=re.search(r'extends ([\w.$]+)',out.splitlines()[1] if len(out.splitlines())>1 else '')
        hdr=[l for l in out.splitlines() if ' class ' in l or ' interface ' in l]
        sup=re.search(r'extends ([\w.$<>]+)',hdr[0]) if hdr else None
        c=sup.group(1).split('<')[0].replace('.','/') if sup else None
    memo[key]=names; return names
active=set()
for p in [p for r in ('src/main/resources','src/client/resources') for p in pathlib.Path(r).glob('*mixins*.json')]:
    d=json.load(open(p))
    for side in ('mixins','client','server'):
        for m in d.get(side,[]) or []: active.add((d['package']+'.'+m).replace('.','/'))
probs=[]; n=0
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
        base=imp.group(1) if imp else c
        tgt.append((base+('$'+'$'.join(c.split('.')[1:]) if '.' in c and imp else '')).replace('.','/'))
    tgt+= [t.replace('.','/') for t in re.findall(r'targets\s*=\s*\{?\s*"([^"]+)"',mm.group(1))]
    tgt=[t for t in tgt if exists(t)]
    if not tgt: continue
    names=set().union(*[members(t) for t in tgt])
    # @Shadow members: next declaration after annotation
    code=re.sub(r'/\*.*?\*/','',s,flags=re.S); code=re.sub(r'//[^\n]*','',code)
    for m in re.finditer(r'@Shadow\b',code):
        rest=code[m.end():]
        stop=min([i for i in (rest.find(';'),rest.find('(')) if i>=0] or [0])
        decl=re.sub(r'@\w+(\([^)]*\))?','',rest[:stop])
        ids=re.findall(r'[\w$]+',decl)
        if not ids: continue
        n+=1; nm=ids[-1]
        own=set().union(*[members(t,True) for t in tgt])
        if nm not in names: probs.append(f'{rel}: @Shadow {nm} not in {tgt}')
        elif nm not in own: probs.append(f'{rel}: @Shadow {nm} only in a SUPERCLASS of {tgt}')
    for kind,expl,decl in re.findall(r'@(Accessor|Invoker)(?:\(\s*(?:value\s*=\s*)?"([^"]+)"[^)]*\))?\s*(?:@\w+\s*)*[\w<>?,\[\] .$]*?\b([\w$]+)\s*\(',code):
        n+=1
        nm=expl or re.sub(r'^(get|set|is|call|invoke)','',decl); nm=nm[:1].lower()+nm[1:] if not expl else nm
        if nm not in names and expl=='' and decl not in names: probs.append(f'{rel}: @{kind} {decl} -> {nm} not in {tgt}')
        elif expl and expl not in names: probs.append(f'{rel}: @{kind}("{expl}") not in {tgt}')
print('shadow/accessor members checked:',n)
print('\n'.join(sorted(set(probs))) or 'no problems')
