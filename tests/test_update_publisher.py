import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publisher = load_module('update_publisher', ROOT / 'tools/update-publisher.py')


class UpdatePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        folder = Path(self.temp.name)
        self.previous_data = os.environ.get('OLDY_DATA')
        os.environ['OLDY_DATA'] = str(folder / 'data')
        self.relay = load_module('publication_relay', ROOT / 'server/server.py')
        self.relay.init_db()
        self.server = self.relay.Relay(('127.0.0.1', 0), self.relay.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.source = folder / 'release'
        self.source.mkdir()
        self.destination = self.relay.ROOT / 'releases'
        actual = os.environ.get('OLDY_TEST_RELEASE')
        if actual:
            for name in ('OldyChat-latest.apk', 'release.json'):
                shutil.copyfile(Path(actual) / name, self.source / name)
        else:
            apk = b'APK publication fixture' * 1000
            (self.source / 'OldyChat-latest.apk').write_bytes(apk)
            (self.source / 'release.json').write_text(json.dumps({
                'package': 'chat.oldy', 'version_code': 7, 'version_name': '0.5.0-beta',
                'size': len(apk), 'sha256': hashlib.sha256(apk).hexdigest(),
            }))
        self.info = publisher.validate_release(self.source)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.relay.DB.close()
        self.temp.cleanup()
        if self.previous_data is None:
            os.environ.pop('OLDY_DATA', None)
        else:
            os.environ['OLDY_DATA'] = self.previous_data

    def test_publication_changes_real_update_endpoint_and_is_repeatable(self):
        with self.assertRaisesRegex(ValueError, 'не предлагает'):
            publisher.verify_update(self.url, self.info)
        for _ in range(2):
            publisher.publish_release(self.source, self.destination)
            reply = publisher.verify_update(self.url, self.info)
            self.assertEqual(reply['version_code'], 7)
            self.assertTrue(reply['available'])

    def test_corrupt_source_cannot_replace_the_published_release(self):
        publisher.publish_release(self.source, self.destination)
        with (self.source / 'OldyChat-latest.apk').open('r+b') as stream:
            stream.write(b'BAD')
        with self.assertRaisesRegex(ValueError, 'Проверка APK'):
            publisher.publish_release(self.source, self.destination)
        publisher.verify_update(self.url, self.info)

    def test_success_requires_downloaded_apk_hash_not_just_manifest(self):
        publisher.publish_release(self.source, self.destination)
        with (self.destination / 'OldyChat-latest.apk').open('r+b') as stream:
            stream.write(b'BAD')
        publisher.verify_update(self.url, self.info, download=False)
        with self.assertRaisesRegex(ValueError, 'не прошёл проверку'):
            publisher.verify_update(self.url, self.info)

    def test_old_command_cannot_downgrade_a_newer_release(self):
        publisher.publish_release(self.source, self.destination)
        newer = dict(self.info, version_code=8)
        (self.destination / 'release.json').write_text(json.dumps(newer))
        with self.assertRaisesRegex(ValueError, 'более новая'):
            publisher.publish_release(self.source, self.destination)
        publisher.verify_update(self.url, newer)


if __name__ == '__main__':
    unittest.main()
