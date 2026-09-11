"""Publish a verified Android release and check the endpoints used by old clients."""
import hashlib
import json
import os
import shutil
import urllib.request
from pathlib import Path


def validate_release(source):
    source = Path(source)
    info = json.loads((source / 'release.json').read_text())
    apk = source / 'OldyChat-latest.apk'
    if (info.get('package') != 'chat.oldy'
            or type(info.get('version_code')) is not int
            or info['version_code'] < 1
            or not info.get('version_name')
            or info.get('size') != apk.stat().st_size
            or info.get('sha256') != hashlib.sha256(apk.read_bytes()).hexdigest()):
        raise ValueError('Проверка APK не пройдена. Выпуск не опубликован.')
    return info


def publish_release(source, destination):
    source, destination = Path(source), Path(destination)
    info = validate_release(source)
    current = destination / 'release.json'
    if current.exists():
        old = json.loads(current.read_text())
        if old.get('version_code', 0) > info['version_code']:
            raise ValueError('На сервере уже опубликована более новая версия.')
    destination.mkdir(mode=0o755, parents=True, exist_ok=True)
    # Stage both files before replacing either; publish the manifest last.
    staged = []
    try:
        for name in ('OldyChat-latest.apk', 'release.json'):
            temporary = destination / (name + '.new')
            staged.append(temporary)
            shutil.copyfile(source / name, temporary)
            temporary.chmod(0o644)
            with temporary.open('rb') as stream:
                os.fsync(stream.fileno())
        for temporary in staged:
            os.replace(temporary, destination / temporary.name.removesuffix('.new'))
    finally:
        for temporary in staged:
            temporary.unlink(missing_ok=True)
    return info


def verify_update(base_url, info, context=None, download=True):
    with urllib.request.urlopen(base_url + '/updates', context=context, timeout=15) as response:
        actual = json.loads(response.read(65537))
    if not actual.get('available'):
        raise ValueError('Сервер пока не предлагает обновление приложению.')
    for field in ('version_code', 'version_name', 'sha256', 'size'):
        if actual.get(field) != info[field]:
            raise ValueError('Сервер возвращает другой выпуск: ' + field)
    if actual.get('path') != '/download/OldyChat-latest.apk':
        raise ValueError('Неверный адрес загрузки APK.')
    if download:
        digest, size = hashlib.sha256(), 0
        with urllib.request.urlopen(base_url + actual['path'], context=context, timeout=30) as response:
            if response.status != 200:
                raise ValueError('APK недоступен для скачивания.')
            while True:
                chunk = response.read(1048576)
                if not chunk:
                    break
                size += len(chunk)
                if size > info['size']:
                    raise ValueError('Сервер отдал неверный размер APK.')
                digest.update(chunk)
        if size != info['size'] or digest.hexdigest() != info['sha256']:
            raise ValueError('Скачанный с сервера APK не прошёл проверку.')
    return actual
