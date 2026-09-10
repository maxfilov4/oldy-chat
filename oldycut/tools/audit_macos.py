"""Check every bundled Mach-O dependency against the user's Intel macOS 13.7.8."""
from pathlib import Path
from macholib.MachO import MachO
from macholib.mach_o import LC_BUILD_VERSION,LC_VERSION_MIN_MACOSX,LC_LOAD_DYLIB
import json
def audit(app,report):
    seen=set();binaries=[];max_version=0
    for candidate in Path(app).rglob('*'):
        if not candidate.is_file():continue
        path=candidate.resolve()
        if path in seen:continue
        seen.add(path)
        with path.open('rb') as f:magic=f.read(4)
        if magic not in [b'\xfe\xed\xfa\xce',b'\xce\xfa\xed\xfe',b'\xfe\xed\xfa\xcf',b'\xcf\xfa\xed\xfe',b'\xca\xfe\xba\xbe',b'\xbe\xba\xfe\xca']:continue
        macho=MachO(str(path));intel=[h for h in macho.headers if h.header.cputype==0x1000007]
        if not intel:raise RuntimeError('Missing x86_64 architecture: '+str(path))
        minimum=0
        for h in intel:
            for lc,cmd,data in h.commands:
                if lc.cmd==LC_BUILD_VERSION:minimum=max(minimum,cmd.minos)
                elif lc.cmd==LC_VERSION_MIN_MACOSX:minimum=max(minimum,cmd.version)
                elif lc.cmd==LC_LOAD_DYLIB:
                    name=data.split(b'\0',1)[0].decode(errors='replace')
                    if name.startswith('/') and not name.startswith(('/usr/lib/','/System/Library/')):
                        raise RuntimeError('External dependency: '+name+' in '+str(path))
        if minimum>((13<<16)|(7<<8)|8):raise RuntimeError('Requires newer than macOS 13.7.8: '+str(path))
        max_version=max(max_version,minimum);binaries.append({'file':str(candidate.relative_to(app)),'minimum':f'{minimum>>16}.{(minimum>>8)&255}.{minimum&255}'})
    Path(report).parent.mkdir(parents=True,exist_ok=True)
    data={'intel_binaries':len(binaries),'highest_minimum_macos':f'{max_version>>16}.{(max_version>>8)&255}.{max_version&255}','binaries':binaries}
    Path(report).write_text(json.dumps(data,indent=2));print('Compatibility audit:',len(binaries),'Intel binaries; maximum required macOS',data['highest_minimum_macos'],flush=True)
