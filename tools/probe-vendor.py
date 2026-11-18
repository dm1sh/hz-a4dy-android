#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Optional interoperability probe. Does not download or redistribute proprietary code.
Run ./gradlew :core:jar first. Requires JDK 21, enjarify and a caller-supplied base.apk.
Only the translated M2.a class is loaded; every Android/Bluetooth/bridge class is an inert stub.
"""
import argparse, hashlib, json, os, subprocess, sys, tempfile, zipfile
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('--stdlib', type=Path, required=True, help='Path to kotlin-stdlib-2.2.21.jar')
p.add_argument('--python', default='/usr/bin/python3', help='Python with enjarify installed')
p.add_argument('--translated-jar', type=Path, help='Reuse a local enjarify output; not distributed')
p.add_argument('--output', type=Path, default=Path('research/vendor-packet-probe.json'))
a=p.parse_args(); root=Path(__file__).resolve().parents[1]
expected_apk='d3ed4870762173fed497b7e6ada2a4f66a95bd9f1e1849420369dbb17c41b45c'
expected_class='34ab7f3d1419bce0dc0cfad60024f4913fab86e8dc1b6a7ee4531cdf973a0529'
with a.apk.open('rb') as f: apk_sha=hashlib.file_digest(f,'sha256').hexdigest()
if apk_sha != expected_apk: sys.exit('Unknown APK hash. Refusing to execute bytecode from an unanalysed file.')
core=root/'core/build/libs/core.jar'
if not core.exists(): sys.exit('First run ./gradlew :core:jar')
def run(args, **kw): return subprocess.run(list(map(str,args)), check=True, **kw)
with tempfile.TemporaryDirectory(prefix='a4dy-vendor-probe-') as t:
 temp=Path(t); jar=a.translated_jar
 if jar is None:
  jar=temp/'vendor.jar'; run([a.python,'-m','enjarify.main',a.apk,'--fast','-o',jar],stdout=subprocess.DEVNULL)
 classes=temp/'classes'; (classes/'M2').mkdir(parents=True)
 with zipfile.ZipFile(jar) as z: bytecode=z.read('M2/a.class')
 if hashlib.sha256(bytecode).hexdigest()!=expected_class:
  sys.exit('Unexpected translated class. Use enjarify 1.0.3 with --fast; only the analysed translation is accepted.')
 (classes/'M2/a.class').write_bytes(bytecode)
 cp=os.pathsep.join(map(str,[classes,core,a.stdlib]))
 sources=sorted((root/'tools/vendor-probe').rglob('*.java'))
 run(['javac','-J-Xmx128m','-cp',cp,'-d',classes,*sources])
 result=run(['java','-Xmx128m','-cp',cp,'Probe'],stdout=subprocess.PIPE,text=True)
 data=json.loads(result.stdout)
 data['base_apk_sha256']=apk_sha
 with core.open('rb') as f: data['core_jar_sha256']=hashlib.file_digest(f,'sha256').hexdigest()
 data['java_version']=subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr.splitlines()[0]
 data['translator']='enjarify 1.0.3 --fast'
 data['translated_class_sha256']=hashlib.sha256(bytecode).hexdigest()
 data['note']='JVM fixture comparison only. Does not validate Android image APIs, RFCOMM, reply timing, printer firmware or physical output.'
 a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(json.dumps(data,indent=2)+'\n')
 print(f"{data['passed']} exact vendor/original packet comparisons passed → {a.output}")
