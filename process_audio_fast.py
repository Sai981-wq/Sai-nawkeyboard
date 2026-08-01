import os
import subprocess
import math
from concurrent.futures import ThreadPoolExecutor, as_completed

WAV_DIR = "storage/shared/my_wavs"
OUTPUT_DIR = "storage/shared/processed_audio"
OPUS_DIR = os.path.join(OUTPUT_DIR, "opus")
# TTS Engine အများစုအတွက် အသင့်တော်ဆုံး Sample Rate (လိုအပ်ပါက 22050 သို့မဟုတ် 16000 သို့ပြောင်းနိုင်သည်)
TARGET_SAMPLE_RATE = 24000  
TARGET_RMS_DB = -20.0
MAX_WORKERS = 4

def get_wav_rms(filepath):
    try:
        result = subprocess.run(["sox", filepath, "-c", "1", "-n", "stat"], capture_output=True, text=True, timeout=10)
        for line in result.stderr.split('\n'):
            if 'RMS     amplitude:' in line:
                return float(line.split(':')[1].strip())
    except:
        pass
    return None

def process_wav(wav_file):
    input_path = os.path.join(WAV_DIR, wav_file)
    name = wav_file[:-4]
    output_opus_path = os.path.join(OPUS_DIR, f"{name}.opus")
    
    try:
        rms = get_wav_rms(input_path)
        gain_db = 0 if not rms else max(-25, min(20, TARGET_RMS_DB - (20 * math.log10(rms))))
        
        # Sinc filter မသုံးဘဲ အသံကို သဘာဝအတိုင်းထားရှိခြင်း
        sox_cmd = [
            "sox", input_path, "-t", "wav", "-",
            "channels", "1",
            "gain", f"{gain_db:.2f}",
            "rate", "-v", str(TARGET_SAMPLE_RATE)
        ]
        
        # စကားပြောအသံ (Voice) အတွက် 48 kbps သည် အလွန်ကြည်လင်ပြီး ဖိုင်ဆိုဒ်ကိုပါ သက်သာစေသည်
        opus_cmd = [
            "opusenc",
            "--bitrate", "48",
            "--comp", "10",
            "--framesize", "20",
            "--vbr",
            "-", output_opus_path
        ]
        
        sox_p = subprocess.Popen(sox_cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        opus_p = subprocess.Popen(opus_cmd, stdin=sox_p.stdout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
        sox_p.stdout.close()
        opus_p.communicate()
        sox_p.wait()
        
        return os.path.exists(output_opus_path) and os.path.getsize(output_opus_path) > 0
    except Exception as e:
        return False

def pack_audio_bin(opus_dir, output_bin, output_index):
    opus_files = sorted([f for f in os.listdir(opus_dir) if f.endswith('.opus')])
    offset = 0
    with open(output_bin, 'wb') as bin_file, open(output_index, 'w') as idx_file:
        for opus_file in opus_files:
            name = opus_file[:-5]
            filepath = os.path.join(opus_dir, opus_file)
            with open(filepath, 'rb') as f:
                data = f.read()
            length = len(data)
            bin_file.write(data)
            idx_file.write(f"{name}:{offset}:{length}\n")
            offset += length

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(OPUS_DIR, exist_ok=True)
    
    if not os.path.exists(WAV_DIR):
        print(f"Error: {WAV_DIR} မတွေ့ပါ။")
        return

    wav_files = sorted([f for f in os.listdir(WAV_DIR) if f.endswith('.wav')])
    total = len(wav_files)
    print(f"ဖိုင်စုစုပေါင်း {total} ခုကို TTS Engine အတွက် (24kHz Mono) ဖြင့် ပြောင်းလဲနေပါပြီ...")
    
    if total == 0: return

    success = 0
    failed = 0
    
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(process_wav, wav): wav for wav in wav_files}
        
        for i, future in enumerate(as_completed(futures)):
            if future.result():
                success += 1
            else:
                failed += 1
            
            if (i + 1) % 100 == 0:
                print(f"Processing {i+1}/{total}...")

    print(f"\nProcessing complete: {success} success, {failed} failed out of {total}")
    pack_audio_bin(OPUS_DIR, os.path.join(OUTPUT_DIR, "audio.din"), os.path.join(OUTPUT_DIR, "index.txt"))
    print(f"\nပြီးပါပြီ! processed_audio ဖိုဒါထဲမှ audio.din နှင့် index.txt ကို TTS App တွင် အသစ်လဲ၍ ထည့်သုံးနိုင်ပါပြီ။")

if __name__ == "__main__":
    main()
