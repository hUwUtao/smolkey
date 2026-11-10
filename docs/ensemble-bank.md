# Ensemble sample banks

Ensemble uses DirectWave’s model of programs and zones. A program maps key and velocity ranges to clips. The first matching zone sets the root key, gain and release time. An optional program setting fills gaps with the closest velocity range and root key. [DirectWave sampler manual](https://www.image-line.com/fl-studio-learning/fl-studio-online-manual/html/plugins/DirectWave.htm)

Ensemble reads a bank from a seekable file descriptor. Android callers can use an uncompressed APK asset, a local file, or a content URI backed by a seekable file. Each engine instance reads one bank index before playing from that bank. A note or pad resolves its program and zone against that index. On a cache miss, a Smol executor runs up to two decoder tasks in parallel. Each task reads only its selected clip's Ogg Opus range using positional file reads. The tasks favor first chunks for new clips between bounded slices of ongoing decodes, with at most eight clips in progress. Voices can start as soon as their first PCM chunk is available. Android chooses where to run the executor threads. The mixer keeps up to 32 MiB of bank PCM across 512 clips, evicts the least recently triggered idle clips, and keeps clips in use by voices resident. When the queue is full or all space is pinned, a new cold trigger can be dropped. The separate standalone clip API loads individual clips explicitly.

The bundled bank assigns the keyboard to program 0 and pads to program 128. Pad keys follow their MIDI notes, with key 127 reserved for the shake loop. The engine accepts program IDs from 0 to 65535. Smolkey currently selects those two bundled programs in code.

## Rebuild the bundled bank

Smolkey’s bank recipe and its six percussion source clips are checked in under `tools/`. The piano source is the public-domain Akai sample set and SFZ maps from [Splendid Grand Piano](https://github.com/sfzinstruments/SplendidGrandPiano), pinned at commit `1c595d827b7fd7f0be3134aaed0f90b3662a09cf`. Check out that revision, then use Python 3 and an FFmpeg build with `libopus`:

```sh
git clone https://github.com/sfzinstruments/SplendidGrandPiano.git /tmp/SplendidGrandPiano
git -C /tmp/SplendidGrandPiano checkout 1c595d827b7fd7f0be3134aaed0f90b3662a09cf
python3 tools/build_smolkey_bank.py /tmp/SplendidGrandPiano
sha256sum --check tools/instruments.smb.sha256
```

`tools/instruments.smb.sha256` verifies the checked-in output. The source revision and builder are pinned here, but FFmpeg/libopus is not: different encoder builds may produce a different bank hash even when the decoded samples and zones are equivalent. The app build does not regenerate the bank; it packages the checked-in asset.

The recipe reads the source's five velocity groups and the pedal resonance group. It uses the SFZ's default release control setting, attack, velocity and key ranges, region gain, pitch and pan, and bakes source-frame offsets into the Opus clips. The lowest velocity group uses its authored 1 kHz cutoff. The bank stores the CC64 release curve and triggers resonance on note-on when the pedal reaches the source's threshold. Smolkey retains its own overall gain, velocity response, sample duration caps, and retrigger fades. Resonance uses the authored group gain. The patch's broader ARIA modulation, including its live resonance-level control, envelope shape, and hold and decay modulation, remains outside this recipe.

## Compile a custom bank

List programs and source clips in a JSON manifest. Paths are relative to the manifest. The compiler accepts mono or stereo, unchained Ogg Opus on a zero-origin timeline. For other audio formats, add an `encode` object to the clip. The compiler uses FFmpeg with libopus and accepts optional `channels` (default 2) and `bitrate_kbps` (default 96).

```json
{
  "programs": [[0, 1, 1, 1, 0, 0, 1000, 15000, 0]],
  "clips": [{
    "path": "pluck.opus",
    "zones": [{
      "program": 0,
      "lokey": 48,
      "hikey": 71,
      "pitch_keycenter": 60,
      "ampeg_release": 0.18,
      "tune": -8,
      "pan": -20
    }]
  }]
}
```

```sh
python3 tools/build_sampler_bank.py --manifest /path/to/bank.json --output /path/to/bank.smb
```

The optional clip `rate` is 48000 Hz by default and can also be 24000 Hz. Each clip must fit inside the PCM cache. A zone gets its sample from the enclosing clip's `path`.

### Region opcode reference

This is the complete list of named fields accepted in `clips[].zones[]`. The compiler rejects unknown fields. The compiled names match the [SMB4 row definitions](#compiled-index) below, so a field can be traced from the manifest to the sampler.

| Opcode | Accepted value and default | SMB4 field | Playback effect |
| --- | --- | --- | --- |
| `program` | Declared program ID, 0–65535; required | `program_id` | Selects the program that owns the zone. Ensemble field. |
| `key` | MIDI key 0–127; optional | `low_key high_key root_key` | Sets all three keys unless an individual field overrides it. |
| `lokey` | MIDI key 0–127; `key` or 0 | `low_key` | First matching key, inclusive. |
| `hikey` | MIDI key 0–127; `key` or 127 | `high_key` | Last matching key, inclusive. |
| `pitch_keycenter` | MIDI key 0–127; `key` or 60 | `root_key` | Reference key for the clip's original pitch. |
| `lovel` | Velocity 1–127; 1 | `low_velocity` | First matching note-on velocity, inclusive. |
| `hivel` | Velocity 1–127; 127 | `high_velocity` | Last matching note-on velocity, inclusive. |
| `ampeg_attack` | Seconds, 0–1; 0 | `attack_ms` | Positive values override the program's fade-in; 0 inherits it. |
| `ampeg_release` | Seconds, 0–65.535; 0.001 | `release_ms` | Sets the base note-off fade. The compiler rounds to milliseconds with a 1 ms minimum, except for one-shot zones. |
| `loop_mode` | `no_loop` (default) or `one_shot` | `release_ms` | One-shot stores 0 so MIDI note-off leaves the voice playing through the clip. Neither mode defines loop points. |
| `volume` | dB, −96 to +12; 0 | `gain_centidb` | Scales the zone's gain on top of program gain and velocity response. |
| `transpose` | Whole semitones, −127 to +127; 0 | `pitch_cents` | Adds 100 cents per semitone to the zone's pitch shift. |
| `tune` | Whole cents, −100 to +100; 0 | `pitch_cents` | Adds fine tuning on top of transpose. |
| `pan` | −100 (left) to +100 (right); 0 | `pan` | Sets left/right balance. |
| `cutoff` | Hz, 20–24000; omitted | `cutoff_hz` | Uses a two-pole low-pass filter in place of the program's velocity filter. |
| `locc64` | Pedal position 0–127; 0 | `pedal_min` | A positive value makes this a separate pedal layer. Note-on can add the first matching pedal layer when CC64 meets its threshold. Zero selects an ordinary zone. |

When several ordinary zones match, the first playable one wins. The program's optional gap filling applies only to ordinary zones. Key and velocity ranges must be ordered. These fields are a subset of the [SFZ opcode vocabulary](https://sfzformat.com/opcodes/). For example, `offset` is applied to the audio by the bundled piano recipe; it is not a generic region opcode. The recipe maps its source's CC64 release opcodes to a program pedal curve, described below.

The manifest also accepts the original positional zone array:
`[program_id, low_key, high_key, root_key, low_velocity, high_velocity, release_ms, gain_centidb]`.
It has no optional modifiers. Release 0 plays through note-off.

### Compiled index

SMB4 starts with the four-byte magic `SMB4`, a little-endian unsigned 32-bit index length, and a text index. The compiler writes ASCII records, one per line, with fields separated by whitespace. These are the field definitions for reading the rows:

```text
program program_id gain velocity_exponent attack_ms retrigger_ms filter_velocity filter_low_hz filter_high_hz fill_gaps
pedal   program_id release_seconds position amount [position amount ...]
clip    rate channels frames bytes
zone    program_id clip_index low_key high_key root_key low_velocity high_velocity release_ms gain_centidb
        [pitch_cents pan [cutoff_hz [attack_ms pedal_min]]]
```

- `program`: Gain is linear. A retrigger fade of 0 allows overlapping strikes. A filter velocity of 0 disables velocity filtering; otherwise the threshold is 2–127, with the low cutoff at velocity 1 and the high cutoff at the threshold. Set `fill_gaps` to 0 or 1. The manifest's program arrays use this same field order.
- `pedal`: The program must already be declared. Provide one curve per program at most. Positions are strictly increasing from 0 through 127, with amounts between 0 and 1. The release scale is 0–100 seconds.
- `clip`: Ogg Opus payloads follow the index in clip declaration order. The clip index is its zero-based declaration position. `frames` counts decoded sample frames at `rate`; `bytes` is the encoded size.
- `zone`: Both the program and clip must already be declared. The optional fields stay on the same line as the required fields. Omitted pitch, pan, and pedal threshold default to zero; omitted or zero cutoff and attack use the program settings. The compiler includes zero placeholders when a later field needs them.

The pluck example compiles to `zone 0 0 48 71 60 1 127 180 0 -8 -20`: 180 ms of release, −8 cents of tuning, and a pan of −20.

A manifest can supply a continuous release curve for a program. The curve adds release seconds scaled by its linearly interpolated value at the current CC64 position. Positions must be sorted and cover 0 through 127.

```json
{"pedals": [{"program": 0, "release_seconds": 15, "curve": [[0, 0], [64, 0.3], [127, 1]]}]}
```

The compiler stores each curve as a `pedal` row. Programs without a curve use standard on/off sustain at CC64 ≥ 64. `Ensemble.setPedal(channel, value)` accepts the full range, and `MidiSynth` and Smolkey MIDI input monitoring forward CC64 to it. The pedal interface uses JNI API version 4.

Convert an original bundled SMB3 bank with `tools/build_smolkey_bank.py --reindex-smb3`. The converter preserves the encoded recordings and translates the original bundled zone and playback settings.

## Android API

Add the `:ensemble` Android library to an app to use `work.stdpi.ensemble`. The library packages the native decoder and output engine. Store bundled `.smb` assets without APK compression so Android can open them by file descriptor.

- `Ensemble.open(context, versions)` negotiates the JNI API version and returns a typed result. Output opens asynchronously. `engine.output` reports its state and the PCM rate, channels, and encoding when ready.
- `engine.banks.inspect(source)` reads clip count and program IDs without installing the bank. `engine.banks.load(source, requiredPrograms)` checks required programs before installation. Both run on the calling worker and accept `BankSource.Asset`, `BankSource.LocalFile`, and `BankSource.ContentUri`. Content providers must supply a seekable file and grant read access. Each engine owns one installed bank. Keep the source contents stable while it is loaded. The descriptor interface uses JNI API version 3.
- `MidiDecoder` frames a live MIDI byte stream or BLE packets. `MidiSynth` plays channel messages using the installed bank. A patch map can route MIDI bank, program, and percussion addresses to bank program IDs. With the default map, melodic bank zero uses IDs 0–127 and the default channel 10 kit uses ID 128.
- `MidiSequence.read(input)` merges tracks and resolves tempo for Standard MIDI Files in formats 0 and 1. With a bank loaded and output ready, call `MidiPlayer(engine).play(sequence)` on a worker. The player handles program and bank changes, volume, pan, expression, sustain, pitch bend, and note release. Playback depends on the patches supplied by the bank. SysEx, pressure synthesis, and SMF format 2 are outside the current playback support.

### Output effects

An engine starts with an empty output rack. Append effects in playback order with `engine.fx.load`. Each instance has its own settings and keeps them when the output device reopens.

```kotlin
val room = checkNotNull(engine.fx.load(Fx.Reverb))
checkNotNull(engine.fx.load(Fx.MonoFold))
val limiter = checkNotNull(engine.fx.load(Fx.Limiter))
check(room.set(Fx.Reverb.Amount, 0.35f))
check(limiter.set(Fx.Limiter.ReleaseMs, 80f))
```

The rack supports up to 16 slots. Parameter setters validate the slot, parameter, and value before publishing it to the audio thread. Load the chain before starting playback. The FX interface was introduced in JNI API version 2.
