#!/usr/bin/env python3
"""Only managed timestamp-named Oldi snapshots; no arbitrary path or symlink following."""
from pathlib import Path
import datetime,re,shutil,time
root=Path('/var/backups/oldy-chat')
if root.is_dir() and not root.is_symlink():
 for path in root.iterdir():
  if path.is_symlink() or not path.is_dir() or not re.fullmatch(r'\d{8}-\d{6}',path.name):continue
  try:created=datetime.datetime.strptime(path.name,'%Y%m%d-%H%M%S').replace(tzinfo=datetime.timezone.utc).timestamp()
  except ValueError:continue
  if created<time.time()-30*86400:shutil.rmtree(path)
