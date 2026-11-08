#!/usr/bin/env python3
"""Build the bundled Ogg Opus bank from Splendid Grand Piano and Smolkey's percussion."""

import argparse
from array import array
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DRUMS = ("kick", "snare", "hi_hat", "low_tom", "tambourine", "tambourine_shake")
FFMPEG = ["ffmpeg", "-nostdin", "-v", "error"]
PLAYBACK = b"""SMB3
piano_velocity_max 67 84 100
piano_gain 0.6
piano_velocity_exponent 0.75
piano_attack_ms 0.5
piano_restrike_ms 35
piano_soft_filter 40 1000 15000
drum_velocity_exponent 2
drum_attack_ms 0.5

"""


def piano_jobs(source):
    recordings = defaultdict(list)
    for layer, name in enumerate(("PP", "MP", "MF", "FF")):
        for line in (source / "Data" / f"{name}.txt").read_text().splitlines():
            if not line.startswith("<region>"):
                continue
            before, sample = line.split(" sample=", 1)
            file, after = sample.split(".$EXT", 1)
            values = dict(field.split("=") for field in (before[8:] + after).split())
            low, high, root = (int(values[key]) for key in ("lokey", "hikey", "pitch_keycenter"))
            release = float(values.get("ampeg_release", 0))
            # The patch adds 0.7 seconds by default. Its 10s regions model undamped treble.
            release_ms = 0 if release >= 10 else round((release + 0.7) * 1000)
            gain = round(100 * float(values.get("volume", 0)))
            zone = (low, high, root, layer, release_ms, gain)
            duration = 6.5 if root < 60 else 4.5 if root < 84 else 3
            rate = 24_000 if root < 84 else 48_000
            recordings[file, int(values.get("offset", 0)), duration, rate].append(zone)
    for (file, offset, duration, rate), zones in recordings.items():
        yield source / "Samples" / f"{file}.flac", zones, rate, duration, offset


def percussion_gain(source):
    data = subprocess.check_output(FFMPEG + [
        "-i", str(source), "-ar", "48000", "-ac", "1", "-f", "f32le", "pipe:1",
    ])
    pcm = array("f", data)
    if sys.byteorder != "little":
        pcm.byteswap()
    window = 960  # 20 ms at 48 kHz, with half-window hops.
    rms = max(sum(value * value for value in pcm[i:i + window]) / window
              for i in range(0, len(pcm), window // 2)) ** 0.5
    peak = max(map(abs, pcm))
    if not rms or not peak:
        raise ValueError(f"Silent percussion source: {source}")
    return min(10 ** (-18 / 20) / rms, 10 ** (-4 / 20) / peak)


def encode(job):
    source, zones, rate, duration, offset = job
    args = FFMPEG + ["-i", str(source)]
    if duration:
        args += ["-ac", "2", "-af",
                 f"atrim=start_sample={offset},asetpts=PTS-STARTPTS,"
                 f"afade=t=out:st={duration - 0.45}:d=0.45", "-t", str(duration)]
    else:
        args += ["-ac", "1", "-af", f"volume={percussion_gain(source):.8f}"]
    data = subprocess.check_output(args + [
        "-map_metadata", "-1", "-ar", str(rate), "-c:a", "libopus",
        "-b:a", "96k" if duration else "64k", "-f", "opus", "pipe:1",
    ])
    header = f"clip {rate} {len(data)}\n"
    header += "".join("zone " + " ".join(map(str, zone)) + "\n" for zone in zones)
    return header.encode("ascii") + b"\n" + data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("splendid", type=Path)
    args = parser.parse_args()
    jobs = []
    for key, name in enumerate(DRUMS, 128):
        path = ROOT / "tools" / "percussion" / f"{name}.ogg"
        jobs.append((path, [(key, key, key, 0, 0, 0)], 48_000, None, 0))
    jobs.extend(piano_jobs(args.splendid))
    output = ROOT / "app/src/main/assets/instruments.smb"
    with ThreadPoolExecutor(max_workers=4) as pool:
        output.write_bytes(PLAYBACK + b"".join(pool.map(encode, jobs)))
    print(f"{len(jobs)} samples, {sum(len(job[1]) for job in jobs)} zones, {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
