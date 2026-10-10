import contextlib
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location('weknora_local', Path(__file__).with_name('prepare-local.py'))
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
prepare = _module.prepare


class PrepareLocalTest(unittest.TestCase):
    def test_private_configuration_and_idempotent_secret_preservation(self):
        with tempfile.TemporaryDirectory(prefix='pcie-weknora-env-test-') as temporary:
            directory = Path(temporary) / 'config'
            with contextlib.redirect_stdout(io.StringIO()):
                path = prepare(directory)
                original = path.read_bytes()
                prepare(directory)
            self.assertEqual(original, path.read_bytes())
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            values = dict(line.split('=', 1) for line in original.decode().splitlines()
                          if line and not line.startswith('#'))
            self.assertEqual('v0.8.2', values['WEKNORA_VERSION'])
            self.assertEqual(32, len(values['SYSTEM_AES_KEY'].encode('ascii')))
            self.assertEqual(64, len(values['JWT_SECRET']))
            self.assertNotEqual(values['DB_PASSWORD'], values['REDIS_PASSWORD'])
            self.assertEqual('false', values['WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS'])

    def test_existing_user_configuration_is_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='pcie-weknora-env-test-') as temporary:
            directory = Path(temporary)
            path = directory / 'runtime.env'
            original = b'# Existing local values\nWEKNORA_UI_PORT=9991\n'
            path.write_bytes(original)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(path, prepare(directory))
            self.assertEqual(original, path.read_bytes())


if __name__ == '__main__':
    unittest.main()
