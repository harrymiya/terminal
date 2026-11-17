import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class DesktopCredentialsTest(unittest.TestCase):
    def test_password_storage_and_environment_for_each_platform(self):
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                spec = importlib.util.spec_from_file_location('credential_core_' + platform, ROOT / platform / 'core.py')
                core = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(core)
                with patch.object(core.subprocess, 'run') as run:
                    run.return_value.returncode = 0
                    core.save_host_password('host-id', 'secret value')
                    args, kwargs = run.call_args
                    self.assertNotIn('secret value', args[0])
                    self.assertEqual(kwargs['input'], 'secret value')
                    core.clear_host_password('host-id')
                    self.assertIn('clear', run.call_args.args[0])
                env = core.ssh_auth_environment({'credential_id': 'host-id'}, '/askpass')
                self.assertEqual(env['RDT_SSH_CREDENTIAL_ID'], 'host-id')
                self.assertEqual(env['SSH_ASKPASS_REQUIRE'], 'force')
                self.assertNotIn('secret value', env.values())
                with tempfile.TemporaryDirectory() as tmp:
                    config = Path(tmp) / 'config.json'
                    core.save_config({'hosts': [{'credential_id': 'host-id'}], 'agents': []}, config)
                    self.assertNotIn('secret value', config.read_text())
                    self.assertEqual(json.loads(config.read_text())['hosts'][0]['credential_id'], 'host-id')

    def test_keyring_failure_is_reported(self):
        spec = importlib.util.spec_from_file_location('credential_core_failure', ROOT / 'ubuntu' / 'core.py')
        core = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(core)
        with patch.object(core.subprocess, 'run', side_effect=FileNotFoundError):
            with self.assertRaisesRegex(RuntimeError, '密钥环'):
                core.save_host_password('id', 'password')


if __name__ == '__main__':
    unittest.main()
