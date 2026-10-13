"""Install the exact release APK and check its rendered foreground activity on a disposable emulator."""
import subprocess,sys,time,json
from pathlib import Path
report=Path('install-report');report.mkdir(exist_ok=True)
def adb(*args):
    result=subprocess.run(['adb',*args],text=True,encoding='utf-8',errors='replace',capture_output=True,timeout=90)
    with (report/'commands.txt').open('a') as log:log.write('adb '+' '.join(args)+'\n'+result.stdout+result.stderr+'\n')
    if result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return result.stdout
try:
    api=adb('shell','getprop','ro.build.version.sdk').strip()
    adb('logcat','-c')
    result=adb('install',sys.argv[1])
    assert 'Success' in result,result
    if int(api)>=33: adb('shell','pm','grant','com.andrip.browser','android.permission.POST_NOTIFICATIONS')
    launch=adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:9/','-n','com.andrip.browser/.MainActivity')
    assert 'Status: ok' in launch,launch
    # Rendering/async startup errors can occur after am returns success.
    time.sleep(8)
    processes=adb('shell','ps','-A') if int(api)>=26 else adb('shell','ps')
    assert 'com.andrip.browser' in processes,'App process exited after launch'
    adb('shell','uiautomator','dump','/sdcard/andrip-window.xml')
    adb('pull','/sdcard/andrip-window.xml',str(report/'window.xml'))
    window=(report/'window.xml').read_text()
    assert 'com.andrip.browser' in window and 'AndRip' in window,'Expected app screen not rendered'
    adb('shell','screencap','-p','/sdcard/andrip.png')
    adb('pull','/sdcard/andrip.png',str(report/'screenshot.png'))
    # Verify update install as well as a clean install, using the same signing identity.
    assert 'Success' in adb('install','-r',sys.argv[1])
    (report/'result.json').write_text(json.dumps({'api':int(api),'fresh_install':True,'launch_and_render':True,'reinstall':True}))
finally:
    (report/'logcat.txt').write_text(adb('logcat','-d'),encoding='utf-8')
