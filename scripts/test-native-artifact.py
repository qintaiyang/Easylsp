#!/usr/bin/env python3
"""Exercise the artifact boundary against a genuinely compiled Android plugin."""
import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest

artifact = Path(sys.argv.pop(1)).read_bytes()
spec = importlib.util.spec_from_file_location('validate_plugin', Path(__file__).parent / 'native-sdk/validate-plugin.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

class ArtifactBoundaryTest(unittest.TestCase):
    def inspect(self, content):
        with tempfile.NamedTemporaryFile(suffix='.so') as file:
            file.write(content)
            file.flush()
            return validator.inspect(file.name)

    def test_wrong_architecture(self):
        data = bytearray(artifact)
        struct.pack_into('<H', data, 18, 62)
        with self.assertRaisesRegex(ValueError, 'AArch64'):
            self.inspect(data)

    def test_unsupported_dependency(self):
        self.assertIn(b'libm.so\0', artifact)
        data = artifact.replace(b'libm.so\0', b'evil.so\0')
        with self.assertRaisesRegex(ValueError, 'Unsupported dynamic dependencies'):
            self.inspect(data)

    def test_missing_sdk_entry(self):
        data = artifact.replace(b'easylsp_plugin_init_v1\0', b'easylsp_plugin_init_v9\0')
        with self.assertRaisesRegex(ValueError, 'SDK function entry'):
            self.inspect(data)

    def test_page_alignment(self):
        data = bytearray(artifact)
        phoff = struct.unpack_from('<Q', data, 32)[0]
        stride, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            offset = phoff + i * stride
            if struct.unpack_from('<I', data, offset)[0] == 1:
                struct.pack_into('<Q', data, offset + 48, 4096)
                break
        with self.assertRaisesRegex(ValueError, '16 KB'):
            self.inspect(data)

if __name__ == '__main__':
    unittest.main()
