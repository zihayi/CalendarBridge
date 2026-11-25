import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from pairing import verification_code

ROOT = Path(__file__).resolve().parents[1]


class AndroidProtocolTests(unittest.TestCase):
    def test_android_and_receiver_agree_on_committed_verification_code(self):
        home = os.environ.get('JAVA_HOME')
        java = Path(home) / 'bin' / 'java.exe' if home else shutil.which('java')
        javac = Path(home) / 'bin' / 'javac.exe' if home else shutil.which('javac')
        if not java or not javac:
            self.skipTest('JDK is required for Android protocol cross-check')
        build = ROOT / 'dev' / '.build'
        build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build) as directory:
            work = Path(directory)
            harness = work / 'ProtocolCheck.java'
            harness.write_text('package local.calendar.readonly; class ProtocolCheck { public static void main(String[] a) throws Exception { System.out.println(PairingProof.verificationCode(a[0],a[1],a[2],a[3])); System.out.println(PairingProof.digest(a[3])); }}', encoding='utf-8')
            subprocess.run([str(javac), '-d', directory, str(ROOT / 'android/src/local/calendar/readonly/PairingProof.java'), str(harness)], check=True, capture_output=True, timeout=30)
            for args in [('01' * 16, '23' * 32, '45' * 32, '67' * 32), ('ab' * 16, 'ff' * 32, '09' * 32, 'ef' * 32)]:
                result = subprocess.check_output([str(java), '-cp', directory, 'local.calendar.readonly.ProtocolCheck', *args], text=True, timeout=15).splitlines()
                self.assertEqual(result, [verification_code(*args), hashlib.sha256(args[3].encode()).hexdigest()])
