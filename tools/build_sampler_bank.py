#!/usr/bin/env python3
"""Compile an indexed Ensemble sample bank from Ogg Opus clips and zone metadata."""

import argparse
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

MAX_ENCODED_BYTES = 32 * 1024 * 1024


def opus_dimensions(data, rate):
    # Standard, unchained Ogg Opus stores the final sample position in its EOS page.
    if type(rate) is not int or rate not in (24_000, 48_000):
        raise ValueError(f"Unsupported decode rate: {rate}")
    page = 0
    head = None
    serial = None
    while page < len(data):
        if page + 27 > len(data) or data[page:page + 5] != b"OggS\0":
            raise ValueError("Invalid Ogg page")
        flags = data[page + 5]
        granule, stream = struct.unpack_from("<QI", data, page + 6)
        body = page + 27 + data[page + 26]
        end = body + sum(data[page + 27:body])
        if end > len(data):
            raise ValueError("Truncated Ogg page")
        if head is None:
            head = data[body:end]
            serial = stream
            if flags != 2 or len(head) != 19 or not head.startswith(b"OpusHead\x01") or head[18] != 0:
                raise ValueError("Expected a mono or stereo Ogg Opus identification page")
        elif stream != serial or flags & 2:
            raise ValueError("Chained or multiplexed Ogg streams are unsupported")
        if flags & 4:
            channels = head[9]
            skip = struct.unpack_from("<H", head, 10)[0]
            divisor = 48_000 // rate
            frames = (granule + divisor - 1) // divisor - (skip + divisor - 1) // divisor
            if end != len(data) or channels not in (1, 2) or not 0 < frames <= rate * 300:
                raise ValueError("Invalid Opus duration or channel count")
            if frames * channels * 2 > 32 * 1024 * 1024:
                raise ValueError("Clip exceeds Ensemble's PCM cache")
            return channels, frames
        page = end
    raise ValueError("Missing Ogg end-of-stream page")


def line(kind, fields):
    return f"{kind} " + " ".join(map(str, fields)) + "\n"


def valid_row(row, limits):
    return len(row) == len(limits) and all(
        type(value) is int and low <= value <= high
        if type(low) is int else type(value) in (int, float) and low <= value <= high
        for value, (low, high) in zip(row, limits)
    )


REGION_OPCODES = {
    "program", "key", "lokey", "hikey", "pitch_keycenter", "lovel", "hivel",
    "ampeg_release", "volume", "tune", "transpose", "pan", "loop_mode", "cutoff", "ampeg_attack", "locc64",
}


def region_fields(region):
    unknown = region.keys() - REGION_OPCODES
    if unknown:
        raise ValueError(f"Unsupported region attributes: {', '.join(sorted(unknown))}")
    if "program" not in region:
        raise ValueError("A named region needs a program ID")
    if "key" in region and not valid_row([region["key"]], [(0, 127)]):
        raise ValueError("key must be an integer from 0 to 127")
    release = region.get("ampeg_release", 0.001)
    volume = region.get("volume", 0)
    tune = region.get("tune", 0)
    transpose = region.get("transpose", 0)
    pan = region.get("pan", 0)
    if not valid_row((release, volume, tune, transpose, pan), (
        (0.0, 65.535), (-96.0, 12), (-100, 100), (-127, 127), (-100.0, 100),
    )):
        raise ValueError(f"Invalid region playback settings: {region}")
    cutoff = region.get("cutoff")
    if cutoff is not None and not valid_row((cutoff,), ((20.0, 24_000),)):
        raise ValueError("cutoff must be between 20 and 24000 Hz")
    attack = region.get("ampeg_attack", 0)
    pedal = region.get("locc64", 0)
    if not valid_row((attack, pedal), ((0.0, 1), (0, 127))):
        raise ValueError("Invalid ampeg_attack or locc64")
    loop_mode = region.get("loop_mode", "no_loop")
    if loop_mode not in ("no_loop", "one_shot"):
        raise ValueError("loop_mode must be no_loop or one_shot")
    # SMB4 reserves zero for play-through. A zero-second envelope gets a 1 ms fade.
    release_ms = 0 if loop_mode == "one_shot" else max(1, round(release * 1000))
    return (
        region["program"],
        region.get("lokey", region.get("key", 0)),
        region.get("hikey", region.get("key", 127)),
        region.get("pitch_keycenter", region.get("key", 60)),
        region.get("lovel", 1),
        region.get("hivel", 127),
        release_ms,
        round(volume * 100, 6),
    ), (transpose * 100 + tune, pan, cutoff or 0, attack * 1000, pedal)


def compile_bank(output, programs, clips, pedals=()):
    index = []
    program_ids = set()
    for fields in programs:
        if not valid_row(fields, (
            (0, 65535), (0.0, 2), (0.1, 8), (0.1, 1000), (0.0, 1000),
            (0, 127), (20.0, 24000), (20.0, 24000), (0, 1),
        )):
            raise ValueError(f"Invalid program row: {fields}")
        program, _, _, _, _, threshold, low_hz, high_hz, _ = fields
        if program in program_ids or threshold == 1 or low_hz > high_hz:
            raise ValueError(f"Invalid program row: {fields}")
        program_ids.add(program)
        index.append(line("program", fields))
    pedal_programs = set()
    for pedal in pedals:
        program = pedal["program"]
        seconds = pedal["release_seconds"]
        curve = pedal["curve"]
        if (not valid_row((program, seconds), ((0, 65535), (0.0, 100)))
                or program not in program_ids or program in pedal_programs
                or not 2 <= len(curve) <= 128
                or not all(valid_row(point, ((0, 127), (0.0, 1))) for point in curve)):
            raise ValueError(f"Invalid pedal curve: {pedal}")
        keys = [key for key, _ in curve]
        if keys[0] != 0 or keys[-1] != 127 or keys != sorted(set(keys)):
            raise ValueError("Pedal curve must span 0 to 127 in ascending order")
        pedal_programs.add(program)
        index.append(line("pedal", (program, seconds, *(value for point in curve for value in point))))
    zones = 0
    count = 0
    with tempfile.TemporaryFile() as payload:
        for count, (data, rate, mappings) in enumerate(clips, 1):
            if len(data) > MAX_ENCODED_BYTES:
                raise ValueError("Encoded clip exceeds Ensemble's clip size limit")
            channels, frames = opus_dimensions(data, rate)
            index.append(line("clip", (rate, channels, frames, len(data))))
            for region in mappings:
                row, modifiers = region_fields(region) if isinstance(region, dict) else (region, (0, 0, 0, 0, 0))
                if not valid_row(row, (
                    (0, 65535), (0, 127), (0, 127), (0, 127), (1, 127), (1, 127),
                    (0, 65535), (-9600.0, 1200),
                )):
                    raise ValueError(f"Invalid zone row: {row}")
                program, low, high, _, velocity_low, velocity_high, *_ = row
                if program not in program_ids or low > high or velocity_low > velocity_high:
                    raise ValueError(f"Invalid zone row: {row}")
                fields = (program, count - 1, *row[1:])
                pitch, pan, cutoff, attack, pedal = modifiers
                if attack or pedal:
                    fields += modifiers
                elif cutoff:
                    fields += (pitch, pan, cutoff)
                elif pitch or pan:
                    fields += (pitch, pan)
                index.append(line("zone", fields))
                zones += 1
            payload.write(data)
        metadata = "".join(index).encode("ascii")
        if len(metadata) > 8 * 1024 * 1024 or not zones:
            raise ValueError("Bank index is empty or too large")
        payload.seek(0)
        # Write beside the target and publish only after the complete bank is available.
        with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as bank:
            temp = Path(bank.name)
            try:
                bank.write(b"SMB4" + struct.pack("<I", len(metadata)) + metadata)
                shutil.copyfileobj(payload, bank)
                bank.close()
                temp.chmod(0o644)
                temp.replace(output)
            finally:
                temp.unlink(missing_ok=True)
    print(f"{count} clips, {zones} zones, {output.stat().st_size:,} bytes")


def read_clip(path, rate, encoding):
    if encoding is None:
        with path.open("rb") as source:
            return source.read(MAX_ENCODED_BYTES + 1)
    if not isinstance(encoding, dict) or encoding.keys() - {"channels", "bitrate_kbps"}:
        raise ValueError("encode accepts channels and bitrate_kbps")
    channels = encoding.get("channels", 2)
    bitrate = encoding.get("bitrate_kbps", 96)
    if type(channels) is not int or channels not in (1, 2):
        raise ValueError("encode.channels must be 1 or 2")
    if type(bitrate) is not int or not 6 <= bitrate <= 256:
        raise ValueError("encode.bitrate_kbps must be between 6 and 256")
    if type(rate) is not int or rate not in (24_000, 48_000):
        raise ValueError(f"Unsupported decode rate: {rate}")
    with tempfile.TemporaryFile() as encoded:
        subprocess.run([
            "ffmpeg", "-nostdin", "-v", "error", "-i", str(path),
            "-map", "0:a:0", "-map_metadata", "-1", "-t", "301",
            "-ac", str(channels), "-ar", str(rate), "-c:a", "libopus",
            "-b:a", f"{bitrate}k", "-f", "opus", "pipe:1",
        ], stdout=encoded, check=True)
        encoded.seek(0)
        return encoded.read(MAX_ENCODED_BYTES + 1)


def manifest_clips(directory, clips):
    for clip in clips:
        path = directory / clip["path"]
        rate = clip.get("rate", 48_000)
        yield read_clip(path, rate, clip.get("encode")), rate, clip["zones"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True, help="JSON programs, zones, and clip sources")
    parser.add_argument("--output", type=Path, required=True, help="Destination SMB4 file")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    compile_bank(args.output, manifest["programs"],
                 manifest_clips(args.manifest.parent, manifest["clips"]), manifest.get("pedals", ()))


if __name__ == "__main__":
    main()
