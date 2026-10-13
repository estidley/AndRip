import subprocess
from pathlib import Path
report = Path('device-download-report')
report.mkdir(exist_ok=True)
subprocess.run(['adb', 'logcat', '-c'], check=True)
try:
    subprocess.run(['./gradlew', 'connectedDebugAndroidTest', '--stacktrace'], check=True)
finally:
    with (report / 'logcat.txt').open('wb') as output:
        subprocess.run(['adb', 'logcat', '-d'], stdout=output)
    subprocess.run(['adb', 'pull', '/sdcard/Android/data/com.andrip.browser/files/download-test-evidence', str(report)])
