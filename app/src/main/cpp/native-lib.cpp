cat > process_audio_raw_opus.py << 'EOF'
import os
import subprocess
import math
import shutil
from concurrent.futures import ThreadPoolExecutor, as_completed

WAV_DIR = "storage/shared/my_wavs"
OUTPUT_DIR = "storage/shared/processed_audio"
RAW_DIR = os.path.join(OUTPUT_DIR, "raw_opus")
TEMP_DIR = os.path.join(OUTPUT_DIR, "temp_ogg")

TARGET_SAMPLE_RATE = 24000
TARGET_RMS_DB = -20.0
PEAK_LIMIT_DB = -3.0
MAX_WORKERS = os.cpu_count() or 4

def extract_raw_opus(ogg_path, raw_path):
    try:
        with open(ogg_path, 'rb') as f:
            data = f.read()

        pos = 0
        packets = []
        curr_packet = bytearray()

        while pos < len(data) - 27:
            if data[pos:pos+4] != b'OggS':
                pos += 1
                continue
            
            segments = data[pos+26]
            pos += 27
            seg_table = data[pos:pos+segments]
            pos += segments

            for seg_len in seg_table:
                curr_packet.extend(data[pos:pos+seg_len])
                pos += seg_len
                if seg_len < 255:
                    packets.append(curr_packet)
                    curr_packet = bytearray()

        if len(packets) > 2:
            with open(raw_path, 'wb') as f:
                for p in packets[2:]:
                    f.write(len(p).to_bytes(2, byteorder='little'))
                    f.write(p)
            return True
        return False
    except:
        return False

def get_stats(filepath):
    try:
        res = subprocess.run(["sox", filepath, "-c", "1", "-n", "stat"], capture_output=True, text=True)
        rms, peak = None, None
        for line in res.stderr.split('\n'):
            if 'RMS     amplitude:' in line: rms = float(line.split(':')[1])
            elif 'Maximum amplitude:' in line: peak = float(line.split(':')[1])
        return rms, peak
    except:
        return None, None

def process(wav_file):
    in_path = os.path.join(WAV_DIR, wav_file)
    name = wav_file[:-4]
    raw_path = os.path.join(RAW_DIR, f"{name}.rawopus")
    ogg_path = os.path.join(TEMP_DIR, f"{name}.ogg")
    
    if os.path.exists(raw_path) and os.path.getsize(raw_path) > 0:
        return True
        
    rms, peak = get_stats(in_path)
    if rms and peak and peak > 0:
        gain = TARGET_RMS_DB - (20 * math.log10(rms))
        max_gain = PEAK_LIMIT_DB - (20 * math.log10(peak))
        final_gain = max(-20.0, min(20.0, min(gain, max_gain)))
    else:
        final_gain = 0.0
        
    cmd = f"sox '{in_path}' -t wav - channels 1 highpass 80 gain {final_gain:.2f} rate -v {TARGET_SAMPLE_RATE} | opusenc --speech --bitrate 32 --comp 10 --framesize 20 --vbr - '{ogg_path}'"
    
    try:
        subprocess.run(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if os.path.exists(ogg_path):
            success = extract_raw_opus(ogg_path, raw_path)
            os.remove(ogg_path)
            return success
        return False
    except:
        return False

def pack():
    files = sorted([f for f in os.listdir(RAW_DIR) if f.endswith('.rawopus')])
    offset = 0
    with open(os.path.join(OUTPUT_DIR, "audio.bin"), 'wb') as b_out, open(os.path.join(OUTPUT_DIR, "index.txt"), 'w') as i_out:
        for f_name in files:
            path = os.path.join(RAW_DIR, f_name)
            with open(path, 'rb') as f: data = f.read()
            length = len(data)
            b_out.write(data)
            i_out.write(f"{f_name[:-8]}:{offset}:{length}\n")
            offset += length

def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(TEMP_DIR, exist_ok=True)
    if not os.path.exists(WAV_DIR): return
    
    wavs = sorted([f for f in os.listdir(WAV_DIR) if f.endswith('.wav')])
    if not wavs: return

    print(f"Processing {len(wavs)} files (Raw Opus Packets Extractor)...")
    success = 0
    with ThreadPoolExecutor(MAX_WORKERS) as exe:
        futures = [exe.submit(process, w) for w in wavs]
        for i, f in enumerate(as_completed(futures), 1):
            if f.result(): success += 1
            if i % 100 == 0: print(f"Processed {i}/{len(wavs)}...")

    pack()
    shutil.rmtree(TEMP_DIR, ignore_errors=True)
    print(f"Done! {success}/{len(wavs)} successful.")

if __name__ == "__main__":
    main()
EOF

python process_audio_raw_opus.py

