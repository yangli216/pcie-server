#!/usr/bin/env python3
"""Exercise generated deployment scripts in temporary directories, without SSH/JVM.

Linux-only tools are simulated so these checks also run on macOS. Production
functions are extracted without running the build/upload entry point.
"""
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('publish-xiaoshan.sh')
SOURCE = SCRIPT.read_text()
FUNCTIONS = SOURCE[SOURCE.index('run_expect() {'):SOURCE.index('\nneed_cmd mvn')]


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='pcie-publish-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.values = {
            'APP_NAME': 'floating-ball-server-testing',
            'DEPLOY_DIR': str(self.root),
            'JAR_FILE': str(self.root / 'service.jar'),
            'START_SCRIPT': str(self.root / 'start.sh'),
            'PID_FILE': str(self.root / 'service.pid'),
            'LOG_DIR': str(self.root / 'logs'),
            'RELEASE_DIR': str(self.root / 'releases'),
            'SPEECH_DIR': str(self.root / 'speech'),
            'SPRING_PROFILE': 'xiaoshan-test', 'SERVER_PORT': '9090',
            'OTHER_PID_FILE': str(self.root / 'production.pid'),
            'LEGACY_JAR_FILE': '', 'LEGACY_START_SCRIPT': '',
            'LEGACY_PID_FILE': '', 'LEGACY_RELEASE_DIR': '',
            'LEGACY_SPEECH_DIR': '', 'HEALTH_TIMEOUT_SECONDS': '1',
        }
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'])
        self.shim('stat', '#!' + shutil.which('python3') + '\n'
                  'import os,sys\ns=os.stat(sys.argv[3]); '
                  'print(s.st_uid if sys.argv[2]=="%u" else oct(s.st_mode & 0o777)[2:])\n')
        self.shim('flock', '#!/bin/bash\nexit "${MOCK_LOCK_BUSY:-0}"\n')
        self.shim('ss', '#!/bin/bash\nexit 0\n')
        self.shim('sleep', '#!/bin/bash\nexit 0\n')
        self.shim('curl', '#!/bin/bash\necho \'{"status":"DOWN"}\'\n')
        self.shim('jar', '#!/bin/bash\nprintf "%s\\n" '
                  'BOOT-INF/classes/application-xiaoshan-test.yml '
                  'BOOT-INF/classes/logback-spring.xml\n')
        self.shim('sha256sum', '#!' + shutil.which('python3') + '\n'
                  'import hashlib,sys\nprint(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())\n')

    def shim(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o700)
        return path

    def bash(self, code):
        return subprocess.run(['bash', '-c', code], env=self.env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def generate(self, expression):
        assignments = '\n'.join(k + '=' + shlex.quote(v) for k, v in self.values.items())
        result = self.bash('set -Eeuo pipefail\n' + assignments + '\n' + FUNCTIONS + '\n' + expression)
        self.assertEqual(result.returncode, 0, result.stdout)

    def start_script(self):
        path = self.root / 'generated-start.sh'
        self.generate('build_start_script ' + shlex.quote(str(path)))
        return path

    def run_config(self, text=None, mode=0o600):
        if text is not None:
            config = self.root / 'service.env'
            config.write_text(text)
            config.chmod(mode)
        return self.bash('bash ' + shlex.quote(str(self.start_script())) + ' check-config')

    def test_immutable_testing_mapping(self):
        result = subprocess.run(['bash', str(SCRIPT), 'testing', '--dry-run'],
                                env=dict(self.env, SERVER_PORT='8080', DEPLOY_DIR='/wrong'),
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn('9090', result.stdout)
        self.assertIn('/data/floating-ball-server-testing', result.stdout)
        self.assertNotIn('/wrong', result.stdout)

    def test_production_requires_explicit_confirmation(self):
        result = subprocess.run(['bash', str(SCRIPT), 'production'],
                                env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--confirm-production', result.stderr)

    def test_optional_config_and_secret_export(self):
        self.assertEqual(self.run_config().returncode, 0)
        result = self.run_config('FB_PRIVATE_TEST="value with spaces"\n'
                                 '[[ "$(bash -c \'printf %s "$FB_PRIVATE_TEST"\')" == "value with spaces" ]]\n')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn('value with spaces', result.stdout)

    def test_config_rejects_insecure_permissions(self):
        result = self.run_config('FB_PRIVATE_TEST=secret\n', 0o644)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('secret', result.stdout)

    def test_config_rejects_symlink(self):
        target = self.root / 'private.env'
        target.write_text('FB_PRIVATE_TEST=secret\n')
        target.chmod(0o600)
        (self.root / 'service.env').symlink_to(target)
        self.assertNotEqual(self.run_config().returncode, 0)

    def test_config_cannot_override_fixed_port(self):
        self.assertNotEqual(self.run_config('SERVER_PORT=8080\n').returncode, 0)

    def test_config_syntax_error_does_not_print_secrets(self):
        result = self.run_config('FB_PRIVATE_TEST="secret\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('secret', result.stdout)

    def deployment(self, start_failure=False, invalid_config=False, busy=False, running=False):
        old_jar = self.root / 'service.jar'
        old_start = self.root / 'start.sh'
        old_jar.write_bytes(b'previous release')
        old_start.write_text('#!/bin/bash\necho "old-$1" >> "' + str(self.root / 'events') + '"\n')
        old_start.chmod(0o700)
        production = self.root / 'production.jar'
        production.write_bytes(b'production unchanged')
        (self.root / 'production.pid').write_text(str(os.getpid()))
        old_contents = old_start.read_bytes()
        upload = self.root / 'upload.jar'
        upload.write_bytes(b'new release')
        new_start = self.start_script()
        # Keep the real configuration loader and immutable mapping checks. Replace
        # only process management; no JVM or persistent process is launched.
        fixture = new_start.read_text().split('CONSOLE_LOG=', 1)[0]
        fixture += 'echo "new-$1" >> "' + str(self.root / 'events') + '"\n'
        fixture += 'if [[ "$1" == start && -e /dev/fd/9 ]]; then exit 8; fi\n'
        fixture += 'if [[ "$1" == start ]]; then exit ' + ('7' if start_failure else '0') + '; fi\n'
        new_start.write_text(fixture)
        deployer = self.root / 'deployer.sh'
        digest = hashlib.sha256(upload.read_bytes()).hexdigest()
        args = [str(deployer), 'fixture', digest, str(upload), str(new_start)]
        self.generate('build_remote_deployer ' + ' '.join(map(shlex.quote, args)))
        if running:
            (self.root / 'service.pid').write_text(str(os.getpid()))
            cmdline = self.root / 'current-cmdline'
            cmdline.write_text('java -jar ' + str(old_jar) + ' -Dspring.profiles.active=xiaoshan-test')
            # Model Linux /proc for the existing process on macOS as well.
            deployer.write_text(deployer.read_text().replace('/proc/${current_pid}/cmdline', str(cmdline)))
        if invalid_config:
            (self.root / 'service.env').write_text('SERVER_PORT=8080\n')
            (self.root / 'service.env').chmod(0o600)
        if busy:
            self.env['MOCK_LOCK_BUSY'] = '1'
        result = self.bash('bash ' + shlex.quote(str(deployer)))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(old_jar.read_bytes(), b'previous release', result.stdout)
        self.assertEqual(old_start.read_bytes(), old_contents, result.stdout)
        self.assertEqual(production.read_bytes(), b'production unchanged')
        self.assertEqual((self.root / 'production.pid').read_text(), str(os.getpid()))
        self.assertFalse(upload.exists())
        self.assertFalse(deployer.exists())
        if not invalid_config and not busy:
            self.assertIn('rolling back', result.stdout)
            self.assertIn('new-stop', (self.root / 'events').read_text())
        else:
            self.assertFalse((self.root / 'events').exists())
        return result

    def test_explicit_readiness_failure_rolls_back(self):
        result = self.deployment()
        self.assertIn('Readiness/admin check failed', result.stdout)

    def test_command_failure_rolls_back_with_original_exit_status(self):
        self.assertEqual(self.deployment(start_failure=True).returncode, 7)

    def test_running_previous_service_is_restarted_on_rollback(self):
        self.deployment(running=True)
        events = (self.root / 'events').read_text().splitlines()
        self.assertEqual(events, ['old-stop', 'new-start', 'new-status', 'new-stop', 'old-start'])

    def test_invalid_config_fails_before_mutation(self):
        self.deployment(invalid_config=True)

    def test_concurrent_switch_fails_before_mutation(self):
        self.deployment(busy=True)

    @unittest.skipUnless(shutil.which('expect'), 'expect not installed')
    def test_expect_preserves_arguments_and_exit_status(self):
        capture = self.root / 'args'
        command = self.shim('capture', '#!/bin/bash\nprintf "%s\\n" "$@" > "' + str(capture) + '"\nexit 7\n')
        result = self.bash(FUNCTIONS + '\nSSH_PASS=unused\nrun_expect ' + shlex.quote(str(command))
                           + ' -o ControlMaster=auto -o "ControlPath=/tmp/a b" "remote command with spaces"')
        self.assertEqual(result.returncode, 7, result.stdout)
        self.assertEqual(capture.read_text().splitlines(),
                         ['-o', 'ControlMaster=auto', '-o', 'ControlPath=/tmp/a b', 'remote command with spaces'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
