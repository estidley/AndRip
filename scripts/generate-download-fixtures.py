"""Generate our own 24-second audiovisual test pattern; no remote/copyrighted media."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/media'
root.mkdir(parents=True, exist_ok=True)
def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *args], check=True)
ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=30', '-f', 'lavfi', '-i',
       'sine=frequency=440:sample_rate=44100', '-t', '24', '-c:v', 'libx264', '-preset', 'ultrafast',
       '-pix_fmt', 'yuv420p', '-g', '60', '-keyint_min', '60', '-sc_threshold', '0',
       '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', str(root / 'movie.mp4'))
for folder in ['hls', 'dash']:
    (root / folder).mkdir(exist_ok=True)
ffmpeg('-i', str(root / 'movie.mp4'), '-c', 'copy', '-hls_time', '2', '-hls_list_size', '0',
       '-hls_segment_filename', str(root / 'hls/seg%03d.ts'), str(root / 'hls/media.m3u8'))
ffmpeg('-i', str(root / 'movie.mp4'), '-c', 'copy', '-seg_duration', '2', '-use_template', '1',
       '-use_timeline', '1', '-f', 'dash', str(root / 'dash/media.mpd'))
print('Generated fixture bytes:', sum(p.stat().st_size for p in root.rglob('*') if p.is_file()))
