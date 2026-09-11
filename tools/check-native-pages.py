"""Validate 16 KiB ELF LOAD alignment in shipped 64-bit native libraries."""
import struct,zipfile,sys
with zipfile.ZipFile(sys.argv[1]) as apk:
 count=0
 for name in apk.namelist():
  if not name.startswith(('lib/arm64-v8a/','lib/x86_64/')) or not name.endswith('.so'):continue
  data=apk.read(name)
  if data[:5]!=b'\x7fELF\x02':raise SystemExit('Unexpected ELF: '+name)
  offset=struct.unpack_from('<Q',data,32)[0];size,number=struct.unpack_from('<HH',data,54)
  for i in range(number):
   at=offset+i*size
   if struct.unpack_from('<I',data,at)[0]==1:
    align=struct.unpack_from('<Q',data,at+48)[0]
    if align<16384:raise SystemExit('Native library needs 16 KiB rebuild: '+name)
  count+=1
 if count<4:raise SystemExit('Expected native libraries missing')
 print('NATIVE_16KB_PASS:',count,'64-bit libraries checked')
