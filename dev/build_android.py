"""Build a generic APK without embedding a computer address or an access token."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

from cryptography import x509
from cryptography.hazmat.primitives import hashes

ROOT = Path(__file__).resolve().parents[1]


def run(*arguments):
    subprocess.run([str(value) for value in arguments], check=True, timeout=180)


def toolchain(local):
    java_home = os.environ.get('JAVA_HOME') or local.get('javaHome')
    sdk_root = os.environ.get('CALENDAR_SDK_ROOT') or os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or local.get('sdkRoot')
    if not java_home or not sdk_root:
        raise RuntimeError('请设置 JAVA_HOME 和 CALENDAR_SDK_ROOT / ANDROID_SDK_ROOT，或配置 .local-build.json')
    java, sdk = Path(java_home) / 'bin', Path(sdk_root)
    build_dirs = sorted((sdk / 'build-tools').glob('*'), reverse=True) if (sdk / 'build-tools').exists() else []
    build_dirs.append(sdk / 'android-15')
    build = next((directory for directory in build_dirs if (directory / 'aapt2.exe').exists() and (directory / 'lib' / 'd8.jar').exists()), None)
    platforms = [sdk / 'platforms' / 'android-35' / 'android.jar', sdk / 'android-35' / 'android.jar']
    platform = next((file for file in platforms if file.exists()), None)
    if build is None or platform is None or not (java / 'javac.exe').exists():
        raise RuntimeError('未找到 JDK、Android 35 平台或构建工具；支持标准 SDK 和原工程的 android-15 布局')
    return java, build, platform


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--init-signing-key', action='store_true', help='仅在本工程 state/signing 下首次创建签名')
    args = parser.parse_args()
    local_file = ROOT / '.local-build.json'
    local = json.loads(local_file.read_text(encoding='utf-8')) if local_file.exists() else {}
    java, build, platform = toolchain(local)
    signing = Path(os.environ.get('CALENDAR_SIGNING_DIR') or local.get('signingDir') or ROOT / 'state' / 'signing').resolve()
    password, keystore = signing / 'signing-password.txt', signing / 'signing-key.p12'
    if not password.exists() or not keystore.exists():
        if not args.init_signing_key or not signing.is_relative_to((ROOT / 'state').resolve()) or password.exists() or keystore.exists():
            raise RuntimeError('签名文件不完整。外部签名不会被创建或重置；新工程首次签名请使用 --init-signing-key')
        signing.mkdir(parents=True, exist_ok=True)
        password.write_text(secrets.token_urlsafe(32), encoding='ascii')
        run(java / 'keytool.exe', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12', '-storepass:file', password, '-keypass:file', password, '-alias', 'calendar', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=CalendarBridge', '-noprompt')
    legacy_pin = ''
    legacy_certificate = local.get('legacyCertificate')
    if legacy_certificate and Path(legacy_certificate).exists():
        legacy_pin = x509.load_pem_x509_certificate(Path(legacy_certificate).read_bytes()).fingerprint(hashes.SHA256()).hex()
    manifest = ROOT / 'android' / 'AndroidManifest.xml'
    xml = ET.parse(manifest).getroot()
    version_code = int(xml.attrib['{http://schemas.android.com/apk/res/android}versionCode'])
    version_name = xml.attrib['{http://schemas.android.com/apk/res/android}versionName']
    build_root = ROOT / 'dev' / '.build'
    build_root.mkdir(parents=True, exist_ok=True)
    output = ROOT / 'dist'
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='android-', dir=build_root) as temporary:
        work = Path(temporary)
        constants = work / 'BuildConfig.java'
        constants.write_text('package local.calendar.readonly;\nfinal class BuildConfig {\n' +
                             f'    static final int VERSION_CODE = {version_code};\n' +
                             f'    static final String VERSION_NAME = {json.dumps(version_name)};\n' +
                             f'    static final String LEGACY_CERT_SHA256 = {json.dumps(legacy_pin)};\n' +
                             '}\n', encoding='utf-8')
        classes, dex = work / 'classes', work / 'dex'
        classes.mkdir(); dex.mkdir()
        resources, generated, unsigned = work / 'resources.zip', work / 'generated', work / 'unsigned.apk'
        generated.mkdir()
        run(build / 'aapt2.exe', 'compile', '--dir', ROOT / 'android' / 'res', '-o', resources)
        run(build / 'aapt2.exe', 'link', '--manifest', manifest, '-I', platform, '--java', generated,
            '-o', unsigned, '--min-sdk-version', '26', '--target-sdk-version', '35', resources)
        sources = sorted((ROOT / 'android' / 'src').rglob('*.java'))
        sources = [source for source in sources if source.name != 'BuildConfig.java']
        run(java / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', str(platform) + os.pathsep + str(build / 'core-lambda-stubs.jar'), '-d', classes, *sources, *sorted(generated.rglob('R.java')), constants)
        run(java / 'java.exe', '-cp', build / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--lib', platform, '--min-api', '26', '--output', dex, *sorted(classes.rglob('*.class')))
        aligned = work / 'aligned.apk'
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / 'classes.dex', 'classes.dex')
        run(build / 'zipalign.exe', '-f', '4', unsigned, aligned)
        apk = output / 'CalendarBridge.apk'
        run(java / 'java.exe', '-jar', build / 'lib' / 'apksigner.jar', 'sign', '--ks', keystore, '--ks-pass', 'file:' + str(password), '--out', apk, aligned)
        run(java / 'java.exe', '-jar', build / 'lib' / 'apksigner.jar', 'verify', '--verbose', apk)
        reference = local.get('referenceApk')
        if reference and Path(reference).exists():
            def signer(file):
                output = subprocess.check_output([str(java / 'java.exe'), '-jar', str(build / 'lib' / 'apksigner.jar'), 'verify', '--print-certs', str(file)], text=True, encoding='utf-8', timeout=60)
                match = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', output, re.I)
                if not match:
                    raise RuntimeError('无法验证升级签名')
                return match.group(1).lower()
            if signer(reference) != signer(apk):
                raise RuntimeError('签名与参考 APK 不一致，不能覆盖安装')
            print('Upgrade signing identity verified.')
        run(build / 'aapt.exe', 'dump', 'permissions', apk)
        (output / 'SHA256-latest.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  CalendarBridge.apk\n', encoding='ascii')
        print(f'APK ready: {apk} (version {version_name}, code {version_code}, {apk.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
