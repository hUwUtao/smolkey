#!/usr/bin/env python3
"""Prepare Smolkey's bundled piano and percussion for the Ensemble bank compiler."""

import argparse
from array import array
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import subprocess
import sys

from build_sampler_bank import MAX_ENCODED_BYTES, compile_bank

ROOT = Path(__file__).resolve().parents[1]
DRUMS = ("kick", "snare", "hi_hat", "low_tom", "tambourine", "tambourine_shake")
FFMPEG = ["ffmpeg", "-nostdin", "-v", "error"]
PERCUSSION_KEYS = (36, 38, 42, 45, 54, 127)
PROGRAMS = (
    (0, 0.6, 0.75, 0.5, 35, 40, 1000, 15000, 1),
    (128, 1, 2, 0.5, 0, 0, 1000, 15000, 0),
)
VELOCITIES = (1, 68, 85, 101, 128)
OUTPUT = ROOT / "app/src/main/assets/instruments.smb"


def bundled_zone(low, high, root, layer, release, gain):
    if low >= 128:
        key = PERCUSSION_KEYS[low - 128]
        return 128, key, key, key, 1, 127, release, gain
    return 0, low, high, root, VELOCITIES[layer], VELOCITIES[layer + 1] - 1, release, gain


def piano_mapping(source):
    patch = (source / "Splendid Grand Piano.sfz").read_text()
    patch = "\n".join(line.split("//", 1)[0] for line in patch.splitlines())
    sections = re.split(r"<(control|global|curve|group)>", patch)
    control = {}
    global_settings = {}
    groups = []
    curves = {}
    for name, text in zip(sections[1::2], sections[2::2]):
        values = dict(re.findall(r"([\w$]+)=([^\s]+)", text))
        if name == "control":
            control = values
        elif name == "global":
            global_settings = values
        elif name == "curve":
            curves[values["curve_index"]] = [
                (int(key[1:]), float(value)) for key, value in values.items() if re.fullmatch(r"v[0-9]+", key)
            ]
        elif name == "group" and "locc64" in values:
            include = re.search(r'#include "\$DIR/([^"/]+)\.txt"', text)
            if include is None:
                raise ValueError("Missing sample layer in piano pedal group")
            groups.append((include[1], values))
        elif name == "group" and "lovel" in values:
            layer = re.search(r"#define\s+\$DYN\s+(PP|MP|MF|FF)\b", text)
            if layer is None:
                raise ValueError("Missing sample layer in piano velocity group")
            groups.append((layer[1], values))
    if not groups:
        raise ValueError("No velocity groups in piano patch")
    attack_ms = float(global_settings["ampeg_attack"]) * 1000
    release_add = float(control["set_hdcc$RELEASE"]) * float(global_settings["ampeg_release_oncc$RELEASE"])
    pedal = {
        "program": 0,
        "release_seconds": float(global_settings["ampeg_releasecc64"]),
        "curve": curves[global_settings["ampeg_release_curvecc64"]],
    }
    return attack_ms, release_add, groups, pedal


def piano_jobs(source, groups, release_add):
    recordings = defaultdict(list)
    for name, group in groups:
        text = (source / "Data" / f"{name}.txt").read_text()
        inherited = dict(re.findall(r"([\w]+)=([^\s]+)", text.split("<region>", 1)[0]))
        group = group | inherited
        for line in text.splitlines():
            if not line.startswith("<region>"):
                continue
            before, sample = line.split(" sample=", 1)
            file, after = sample.split(".$EXT", 1)
            values = dict(field.split("=", 1) for field in (before[8:] + after).split())
            unknown = values.keys() - {
                "region_label", "lokey", "hikey", "pitch_keycenter", "offset",
                "ampeg_release", "volume", "pan", "tune", "transpose",
            }
            if unknown:
                raise ValueError(f"Unsupported piano region attributes: {', '.join(sorted(unknown))}")
            zone = {
                "program": 0,
                "lokey": int(values["lokey"]),
                "hikey": int(values["hikey"]),
                "pitch_keycenter": int(values["pitch_keycenter"]),
                "lovel": int(group.get("lovel", 1)),
                "hivel": int(group.get("hivel", 127)),
                "ampeg_release": float(values.get("ampeg_release", 0.001)) + release_add,
                "volume": float(values.get("volume", 0)) + float(group.get("group_volume", 0)),
            }
            for opcode, parse in (("pan", float), ("tune", int), ("transpose", int)):
                if opcode in values:
                    zone[opcode] = parse(values[opcode])
            if "cutoff" in group:
                zone["cutoff"] = float(group["cutoff"])
            if "ampeg_attack" in group:
                zone["ampeg_attack"] = float(group["ampeg_attack"])
            if "locc64" in group:
                zone["locc64"] = int(group["locc64"])
            root = zone["pitch_keycenter"]
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
    return data, rate, zones


def read_smb3(path):
    with path.open("rb") as bank:
        if bank.readline() != b"SMB3\n":
            raise ValueError("Expected the original Smolkey SMB3 bank")
        while (entry := bank.readline()) != b"\n":
            if not entry:
                raise ValueError("Incomplete SMB3 playback header")
        while header := bank.readline():
            kind, rate, length = header.split()
            if kind != b"clip":
                raise ValueError("Invalid SMB3 clip")
            zones = []
            while (entry := bank.readline()) != b"\n":
                kind, *fields = entry.split()
                if kind != b"zone":
                    raise ValueError("Invalid SMB3 zone")
                zones.append(bundled_zone(*map(int, fields)))
            length = int(length)
            if not 0 < length <= MAX_ENCODED_BYTES:
                raise ValueError("Invalid SMB3 clip length")
            data = bank.read(length)
            if len(data) != length:
                raise ValueError("Truncated SMB3 clip")
            yield data, int(rate), zones


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("splendid", type=Path, nargs="?", help="Path to the Splendid Grand Piano source")
    source.add_argument("--reindex-smb3", type=Path, help="Convert the original Smolkey bank without re-encoding")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if args.reindex_smb3:
        compile_bank(args.output, PROGRAMS, read_smb3(args.reindex_smb3))
        return
    attack_ms, release_add, groups, pedal = piano_mapping(args.splendid)
    piano = (0, 0.6, 0.75, attack_ms, 35, 0, 1000, 15000, 1)
    jobs = [
        (ROOT / "tools" / "percussion" / f"{name}.ogg",
         [{"program": 128, "key": key, "loop_mode": "one_shot"}], 48_000, None, 0)
        for key, name in zip(PERCUSSION_KEYS, DRUMS)
    ]
    jobs.extend(piano_jobs(args.splendid, groups, release_add))
    with ThreadPoolExecutor(max_workers=4) as pool:
        compile_bank(args.output, (piano, PROGRAMS[1]), pool.map(encode, jobs), (pedal,))


if __name__ == "__main__":
    main()
