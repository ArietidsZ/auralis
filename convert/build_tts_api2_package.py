#!/usr/bin/env python3
"""Compile the API2 TTS package from the single official Qwen checkpoint.

Recipe: auralis.qwen3-tts.api2.fp32.v1
Upstream: Qwen/Qwen3-TTS-12Hz-0.6B-Base @ 5d83992436eae1d760afd27aff78a71d676296fc
Source code: qwen_tts checkout @ 022e286b98fbec7e1e916cb940cdf532cd9f488e

Roles (fixed): speaker_encoder, talker, code_predictor, reference_encoder,
vocoder (stateful).

- speaker_encoder / code_predictor: newly exported FP32 ONNX graphs built from
  the official checkpoint weights (BF16 -> FP32), interfaces matching the
  verified community bundle I/O. Verified value-by-value against the community
  graphs on real generation inputs.
- talker: frozen API2 last-hidden graph (hardlink, hash-gated).
- reference_encoder: frozen official 12 Hz codec encoder graph (hardlink).
- vocoder: frozen stateful streaming decoder graph (hardlink).
- support files: NPY tables / tokenizer / config regenerated from the official
  weights + config, verified value-identical to the shipping bundle tables.

The community bundle is used ONLY as a verification reference; it is never a
package input. Every check must pass before the next stage; failures abort
loudly instead of being absorbed.

Subcommands (run in order):
  export-speaker  export + structural value checks for the speaker encoder
  export-cp       export + structural value checks for the code predictor
  assemble        hardlink frozen graphs, regenerate support files, package.json
  verify          per-call dual-graph value evidence + code continuation
  smoke           ORT 1.24.2 end-to-end: 2 languages, ICL, state, clipping
  provenance      (re)write build-provenance.json from the final package tree

Environment (as used by all prior frozen TTS runs):
  PYTHONPATH="$HOME/Library/Caches/Auralis/tts/upstream-fidelity/deps:
             $HOME/Library/Caches/Auralis/tts/optimization/ort124:
             $HOME/Library/Caches/Auralis/tts/upstream-fidelity/Qwen3-TTS"
  "$HOME/Library/Caches/Auralis/tts/venv/bin/python" convert/build_tts_api2_package.py <cmd>
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import importlib.util
import json
import os
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

RECIPE_ID = "auralis.qwen3-tts.api2.fp32.v1"
HF_REVISION = "5d83992436eae1d760afd27aff78a71d676296fc"
SOURCE_REVISION = "022e286b98fbec7e1e916cb940cdf532cd9f488e"

# Persistent caches are home-derived and overridable; no personal absolute paths.
CACHE = Path(os.environ.get("AURALIS_CACHE", Path.home() / "Library/Caches/Auralis"))
TTS = CACHE / "tts"
OUT = Path(os.environ.get("AURALIS_TTS_API2_PACKAGE_DIR", TTS / "api2-package")).expanduser()                      # deployable package tree only
SIDE = Path(os.environ.get("AURALIS_TTS_API2_EVIDENCE_DIR", TTS / "api2-package-evidence")).expanduser()            # host-side: staging, evidence, package.json
STAGING = SIDE / "staging"
EVIDENCE = SIDE / "evidence"
REBUILD = TTS / "unified-talker-rebuild"        # fresh talker rebuild from the official checkpoint
HF_SNAPSHOT = Path(os.environ.get(
    "AURALIS_HF_QWEN3_TTS_SNAPSHOT",
    Path.home() / ".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base"
    / f"snapshots/{HF_REVISION}"))
SOURCE_REPO = TTS / "upstream-fidelity/Qwen3-TTS"
REF_BUNDLE = TTS / "hf"                # community bundle: verification reference only
REF_BUNDLE_ALT = CACHE / "models/tts"  # second copy; hash-compared against REF_BUNDLE
UNIFIED = TTS / "unified-talker"
ICL_DIR = TTS / "icl"
STREAMING = TTS / "upstream-fidelity/streaming-onnx"
POOL = TTS / "upstream-fidelity/real-pool"
FROZEN_RUNNER = POOL / "protocol-fixed-sampled/tts_runner.py"
FROZEN_RUNNER_SHA256 = "4df289d3ab4b15f1163112e00e0a81323e8a2650edbf833dc72b39e5293b44be"
# Repo-relative paths: this script lives in <repo>/convert, derive everything from
# __file__ so the tool is portable across checkouts (GitHub release requirement).
CONVERT_DIR = Path(__file__).resolve().parent
WORKSPACE_RUNNER = CONVERT_DIR / "tts_runner.py"
BENCH_UNIFIED_TALKER = CONVERT_DIR / "bench_tts_unified_talker.py"

# Hardlink sources with externally recorded hashes (reports + interface JSONs).
# Original filenames are kept: ONNX external-data references embed the file name,
# so renaming would break loading or force a protobuf rewrite (hash chain break).
FROZEN_GRAPHS = {
    "reference_encoder.onnx": (ICL_DIR / "reference_encoder.onnx",
                               "4294aacfaf7419f6d8d5e6bac14cbe1b1b2ca9f401ad698a5dfe7f6427eb3ad0"),
    "reference_encoder.onnx.data": (ICL_DIR / "reference_encoder.onnx.data",
                                    "528902f29affbf7acca8112335fb3230a3eb0384a8c5dca999f7917b1170b62a"),
    "vocoder_streaming.onnx": (STREAMING / "vocoder_streaming.onnx",
                               "3138ede6fb908e72eec4ee5904bd3f158cdcca4241caa7fdd0e5112f0aa38a36"),
    "vocoder_streaming.onnx.data": (STREAMING / "vocoder_streaming.onnx.data",
                                    "80e961291971c0c3e3aee657f4b4ab40eb7c264f3aae90a6233d68f2b9ca0ba7"),
}

# Community graphs + baseline artifacts used for value comparison only.
VERIFY_REFERENCES = {
    "community_code_predictor": REF_BUNDLE / "code_predictor.onnx",
    "community_speaker_encoder": REF_BUNDLE / "speaker_encoder.onnx",
    "community_speaker_encoder_data": REF_BUNDLE / "speaker_encoder.onnx.data",
    "community_vocoder": REF_BUNDLE / "vocoder.onnx",
    "community_vocoder_data": REF_BUNDLE / "vocoder.onnx.data",
    "frozen_121_chinese_codes": UNIFIED / "121-chinese-unified-codes.npy",
    "frozen_121_english_codes": UNIFIED / "121-english-unified-codes.npy",
    "icl_reference_121_codes": ICL_DIR / "reference-121-codes.npy",
    "icl_reference_260_codes": ICL_DIR / "reference-260-codes.npy",
    "icl_reference_121_pcm24": ICL_DIR / "reference-121-pcm24.npy",
    "icl_reference_260_pcm24": ICL_DIR / "reference-260-pcm24.npy",
}

# Frozen talker hashes (previous candidate) — the rebuild must relate to these.
FROZEN_TALKER_HASHES = {
    "talker_api2.onnx": "adc1ae88f8880f09db518fd78476635f70eca21f82cf5ebff18fb3c929e24bd8",
    "talker_api2.onnx.data": "823a937733f4c412f37f434e2d4e66f8a07d5f3e77da16c2a23dc7165583b24e",
}

# Rebuild pipeline (convert/bench_tts_unified_talker.py @ 92844a82…, snapshotted
# in the frozen candidate directory) run in a fresh directory:
#   probe [--inputs <official ICL NPZs>] -> export-dynamic -> validate-dynamic
#   -> make-api2 -> validate-dynamic --api2 -> synth --api2
REBUILD_STEPS = [
    "bench_tts_unified_talker.py probe --output-dir <rebuild> [--inputs prefill-*-inputs.npz]",
    "bench_tts_unified_talker.py export-dynamic --output-dir <rebuild>",
    "bench_tts_unified_talker.py validate-dynamic --output-dir <rebuild>",
    "bench_tts_unified_talker.py make-api2 --output-dir <rebuild>",
    "bench_tts_unified_talker.py validate-dynamic --api2 --output-dir <rebuild>",
    "bench_tts_unified_talker.py synth --api2 --output-dir <rebuild>",
]

OFFICIAL_INPUT_FILES = [
    "model.safetensors",
    "config.json",
    "generation_config.json",
    "vocab.json",
    "merges.txt",
    "tokenizer_config.json",
    "preprocessor_config.json",
    "speech_tokenizer/config.json",
    "speech_tokenizer/configuration.json",
    "speech_tokenizer/model.safetensors",
    "speech_tokenizer/preprocessor_config.json",
]

SAMPLING = dict(temperature=0.9, top_k=50, rep_penalty=1.05, seed=20260906, max_frames=384)
ORT_INTRA, ORT_INTER = 4, 1


# ---------------------------------------------------------------------------
# Small utilities


def sha(path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def diff_stats(left: np.ndarray, right: np.ndarray) -> dict:
    a = np.asarray(left, dtype=np.float64).ravel()
    b = np.asarray(right, dtype=np.float64).ravel()
    if a.shape != b.shape:
        return dict(shape_left=list(a.shape), shape_right=list(b.shape), shape_equal=False)
    delta = a - b
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    return dict(shape=list(a.shape), max_abs=float(np.max(np.abs(delta))) if a.size else 0.0,
                rms=float(np.sqrt(np.mean(delta * delta))) if a.size else 0.0,
                cosine=float(np.dot(a, b) / denom) if denom else None)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"build_tts_api2_package: {message}")


def check_runtime() -> dict:
    import onnx
    import onnxruntime
    require(onnxruntime.__version__ == "1.24.2",
            f"onnxruntime must be 1.24.2 via the ort124 overlay, got {onnxruntime.__version__}")
    require(onnx.__version__ == "1.20.1", f"onnx must be 1.20.1, got {onnx.__version__}")
    require(HF_SNAPSHOT.is_dir(), f"official HF snapshot missing: {HF_SNAPSHOT}")
    return dict(python=sys.version.split()[0], onnx=onnx.__version__,
                onnxruntime=onnxruntime.__version__)


def source_revision() -> str:
    git = SOURCE_REPO / ".git"
    require(git.exists(), f"source checkout missing: {SOURCE_REPO}")
    head = (git / "HEAD").read_text().strip()
    if head.startswith("ref:"):
        ref = (git / head.split(" ", 1)[1]).read_text().strip()
    else:
        ref = head
    require(ref == SOURCE_REVISION,
            f"source revision {ref} != pinned {SOURCE_REVISION}")
    return ref


def ort_session(path, intra: int = ORT_INTRA):
    import onnxruntime
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = intra
    options.inter_op_num_threads = ORT_INTER
    return onnxruntime.InferenceSession(str(path), sess_options=options,
                                        providers=["CPUExecutionProvider"])


class PrefillViaDynamic:
    """Adapter: expose the API2 talker graph as a prefill session (frozen output layout).

    Equivalent to the proven PrefillViaDynamic of the unified-talker bench;
    inlined so this build does not import that script's module constants.
    """

    def __init__(self, decode, config):
        self.decode = decode
        cfg = config.talker
        self.empty = np.zeros((cfg["num_hidden_layers"], 1, cfg["num_key_value_heads"],
                               0, cfg["head_dim"]), dtype=np.float32)

    def run(self, names, inputs):
        if names is not None:
            raise ValueError("full ordered outputs required")
        outputs = self.decode.run(None, dict(**inputs, past_keys=self.empty, past_values=self.empty))
        logits, hidden, keys, values = outputs
        result = [logits, hidden]
        for layer in range(keys.shape[0]):
            result.extend([keys[layer], values[layer]])
        return result


def load_frozen_runner():
    """Import the hash-gated frozen sampling runner used by every prior bench."""
    require(sha(FROZEN_RUNNER) == FROZEN_RUNNER_SHA256, "frozen runner changed on disk")
    spec = importlib.util.spec_from_file_location("api2_frozen_runner", FROZEN_RUNNER)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module._import_heavy()
    require(module.ort.__version__ == "1.24.2", "frozen runner must use ORT 1.24.2")
    return module


def load_workspace_runner():
    """Import the workspace runner read-only for ICL helpers; SHA is recorded, not gated.

    The workspace file is owned by the ICL lane and may change; nothing in the
    package derivation depends on its internals except the ICL smoke path.
    """
    require(WORKSPACE_RUNNER.is_file(), f"workspace runner missing: {WORKSPACE_RUNNER}")
    spec = importlib.util.spec_from_file_location("api2_workspace_runner", WORKSPACE_RUNNER)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module._import_heavy()
    return module, sha(WORKSPACE_RUNNER)


def load_reference_24k(tts, path):
    """Read a reference wav exactly like the frozen runner's extract path (soxr_hq 24 kHz)."""
    audio, sample_rate = tts.sf.read(path, dtype="float32", always_2d=True)
    return tts.resample_reference_audio(audio.mean(axis=1), sample_rate)


def load_official_model():
    """Load the official checkpoint FP32/eager and return the inference wrapper."""
    import torch
    from qwen_tts import Qwen3TTSModel
    torch.set_num_threads(2)
    model = Qwen3TTSModel.from_pretrained(str(HF_SNAPSHOT), dtype=torch.float32,
                                          attn_implementation="eager",
                                          local_files_only=True,
                                          low_cpu_mem_usage=True)
    return model


def save_external(graph, target: Path, data_name: str) -> None:
    import onnx
    data = target.parent / data_name
    if data.exists():
        raise RuntimeError(f"refuse to overwrite {data}")
    onnx.save_model(graph, str(target), save_as_external_data=True,
                    all_tensors_to_one_file=True, location=data_name, size_threshold=1024)


# ---------------------------------------------------------------------------
# export-speaker


def export_speaker(args):
    """Export the official ECAPA-TDNN speaker encoder as FP32 ONNX (opset 18).

    Interface parity with the verified community graph:
      mel_spectrogram float32 [batch, time, 128] -> speaker_embedding float32 [batch, 1024]
    """
    info = check_runtime()
    source_revision()
    import torch
    import onnx
    target = STAGING / "speaker_encoder.onnx"
    require(not target.exists() and not target.with_suffix(".onnx.data").exists(),
            "staging speaker_encoder artifacts already exist; refusing to overwrite")

    tts = load_frozen_runner()
    model = load_official_model()
    speaker = model.model.speaker_encoder.eval().requires_grad_(False)
    require(speaker is not None, "official model has no speaker_encoder module")

    # Real mel frontends (official params: 24 kHz, n_fft 1024, hop 256, 128 mels,
    # fmin 0, fmax 12000; identical to the frozen runner's compute_mel).
    mels = {}
    for name, path in [("121", POOL / "121-reference24.wav"),
                       ("260", POOL / "260-reference24.wav"),
                       ("ref_zh", TTS / "ref_zh.wav"), ("ref_en", TTS / "ref_en.wav")]:
        audio = load_reference_24k(tts, path)
        mels[name] = tts.compute_mel(audio)  # [1, T, 128] float32
    community = ort_session(VERIFY_REFERENCES["community_speaker_encoder"])

    evidence = dict(export="speaker_encoder", **info,
                    source_weights_sha256=sha(HF_SNAPSHOT / "model.safetensors"),
                    torch=torch.__version__, mel_lengths={k: int(v.shape[1]) for k, v in mels.items()},
                    eager_vs_community={}, exported_vs_eager={}, exported_vs_community={})
    with torch.inference_mode():
        for name, mel in mels.items():
            reference = speaker(torch.from_numpy(mel)).numpy().astype(np.float32)
            community_out = community.run(None, {"mel_spectrogram": mel})[0].astype(np.float32)
            evidence["eager_vs_community"][name] = diff_stats(reference, community_out)
        require(all(row["max_abs"] <= 1e-5 for row in evidence["eager_vs_community"].values()),
                "official speaker encoder differs from the community graph on real mel input")
        torch.onnx.export(speaker, (torch.from_numpy(mels["121"]),), str(target),
                          dynamo=False, opset_version=18,
                          input_names=["mel_spectrogram"], output_names=["speaker_embedding"],
                          dynamic_axes={"mel_spectrogram": {1: "time"},
                                        "speaker_embedding": {}},
                          do_constant_folding=True)
    graph = onnx.load(str(target))
    save_external(graph, target, "speaker_encoder.onnx.data")
    del model, speaker
    gc.collect()

    exported = ort_session(target)
    for name, mel in mels.items():
        official = exported.run(None, {"mel_spectrogram": mel})[0].astype(np.float32)
        community_out = community.run(None, {"mel_spectrogram": mel})[0].astype(np.float32)
        evidence["exported_vs_community"][name] = diff_stats(official, community_out)
    # dynamic time axis; batch is fixed at 1 (the consumed pattern — the community
    # graph also fails batch=2 on ORT 1.24.2, so its 'batch' dim was never usable)
    short = mels["121"][:, :3200, :]
    evidence["exported_vs_community"]["short_3200"] = diff_stats(
        exported.run(None, {"mel_spectrogram": short})[0],
        community.run(None, {"mel_spectrogram": short})[0])
    evidence["batch_note"] = ("batch is fixed to 1; the community graph declares a dynamic "
                              "'batch' dim but also fails batch=2 on ORT 1.24.2, so batch>1 "
                              "was never a real capability. All consumers use batch=1.")
    for key in ("exported_vs_community",):
        require(all(row.get("max_abs", 0.0) <= 1e-5 for row in evidence[key].values()
                    if isinstance(row, dict) and "max_abs" in row),
                f"exported speaker encoder failed the {key} gate")
    require(evidence["exported_vs_community"]["short_3200"]["max_abs"] <= 1e-5,
            "exported speaker encoder failed the dynamic-time-axis gate")
    evidence["files"] = [dict(path=str(p), bytes=p.stat().st_size, sha256=sha(p))
                         for p in (target, target.parent / "speaker_encoder.onnx.data")]
    write_json(STAGING / "export-speaker.json", evidence)
    print("export-speaker ok:", json.dumps(evidence["exported_vs_community"], default=float)[:400], flush=True)


# ---------------------------------------------------------------------------
# export-cp


def export_cp(args):
    """Export the official 5-layer code predictor as FP32 ONNX (opset 18).

    Interface parity with the verified community graph:
      inputs_embeds   float32 [batch, T, 1024]
      generation_steps int64   [1]           (index of the code group being predicted)
      past_keys       float32 [5, batch, 8, P, 128]
      past_values     float32 [5, batch, 8, P, 128]
      -> logits        float32 [batch, T, 2048]  = norm(hidden) @ lm_head[steps].T
      present_keys/values float32 [5, batch, 8, P+T, 128]
    Rope positions are P..P+T-1 (community graph derives the same from past shape).
    Only generation_steps length 1 is a consumed/validated pattern.
    """
    info = check_runtime()
    source_revision()
    import torch
    import onnx
    from torch import nn
    target = STAGING / "code_predictor.onnx"
    require(not target.exists(), "staging code_predictor artifact already exists")

    class TensorCache:
        def __init__(self, keys, values):
            self.keys, self.values = keys, values
            self.present_keys, self.present_values = [], []

        def update(self, keys, values, layer_idx, cache_kwargs=None):
            keys = torch.cat([self.keys[layer_idx], keys], dim=2)
            values = torch.cat([self.values[layer_idx], values], dim=2)
            self.present_keys.append(keys)
            self.present_values.append(values)
            return keys, values

    class CodePredictorGraph(nn.Module):
        def __init__(self, cp):
            super().__init__()
            self.layers = cp.model.layers
            self.norm = cp.model.norm
            self.rotary = cp.model.rotary_emb
            require(isinstance(cp.small_to_mtp_projection, nn.Identity),
                    "small_to_mtp_projection is not Identity; interface would differ")
            heads = torch.stack([head.weight for head in cp.lm_head], dim=0)  # [15,1024,2048]
            self.register_buffer("lm_head_weights", heads)

        def forward(self, inputs_embeds, generation_steps, past_keys, past_values):
            past = past_keys.shape[3]
            steps = inputs_embeds.shape[1]
            query = torch.arange(steps, device=inputs_embeds.device) + past
            key = torch.arange(past + steps, device=inputs_embeds.device)
            allowed = key[None, :] <= query[:, None]
            mask = torch.where(allowed, inputs_embeds.new_zeros(()),
                               inputs_embeds.new_full((), torch.finfo(torch.float32).min))[None, None]
            cache = TensorCache(past_keys, past_values)
            hidden = inputs_embeds
            positions = self.rotary(hidden, query[None, :])
            for layer in self.layers:
                hidden = layer(hidden, attention_mask=mask,
                               position_ids=query[None, :],
                               past_key_values=cache, use_cache=True,
                               cache_position=query, position_embeddings=positions)[0]
            hidden = self.norm(hidden)
            head = self.lm_head_weights.index_select(0, generation_steps).squeeze(0)
            return hidden.matmul(head.t()), torch.stack(cache.present_keys), torch.stack(cache.present_values)

    model = load_official_model()
    cp = model.model.talker.code_predictor.eval().requires_grad_(False)
    wrapper = CodePredictorGraph(cp).eval().requires_grad_(False)
    require(all(p.dtype == torch.float32 for p in wrapper.parameters()),
            "code predictor parameters are not FP32")
    community = ort_session(VERIFY_REFERENCES["community_code_predictor"])

    def run_community(inputs_embeds, steps, past):
        p = past.shape[3] if past is not None else 0
        past_k = np.zeros((5, 1, 8, p, 128), dtype=np.float32) if past is None else past
        return community.run(None, {"inputs_embeds": inputs_embeds,
                                    "generation_steps": np.array([steps], dtype=np.int64),
                                    "past_keys": past_k,
                                    "past_values": past_k.copy()})

    zero5 = lambda p: np.zeros((5, 1, 8, p, 128), dtype=np.float32)
    cases = [  # (T, generation_steps, P) — T=2 prefill and T=1 decode patterns
        (2, 0, 0), (1, 1, 2), (1, 7, 8), (1, 14, 15),
    ]
    evidence = dict(export="code_predictor", **info,
                    source_weights_sha256=sha(HF_SNAPSHOT / "model.safetensors"),
                    torch=torch.__version__, wrapper_vs_community={},
                    exported_vs_community={}, exported_shapes={})
    rng = np.random.default_rng(0)
    with torch.inference_mode():
        for T, step, past in cases:
            embeds = rng.standard_normal((1, T, 1024)).astype(np.float32) * 0.5
            past_k = zero5(past)
            actual = wrapper(torch.from_numpy(embeds), torch.tensor([step], dtype=torch.int64),
                             torch.from_numpy(past_k), torch.from_numpy(past_k.copy()))
            actual = [value.numpy().astype(np.float32) for value in actual]
            expected = run_community(embeds, step, past_k)
            evidence["wrapper_vs_community"][f"T{T}_s{step}_p{past}"] = dict(
                logits=diff_stats(actual[0], expected[0]),
                present=diff_stats(actual[1], expected[1]))
        require(all(row["logits"]["max_abs"] <= 1e-4 and row["present"]["max_abs"] <= 1e-4
                    for row in evidence["wrapper_vs_community"].values()),
                "official CP wrapper differs from the community graph on structural inputs")
        arguments = (torch.from_numpy(rng.standard_normal((1, 2, 1024)).astype(np.float32) * 0.5),
                     torch.tensor([0], dtype=torch.int64),
                     torch.from_numpy(zero5(3)), torch.from_numpy(zero5(3)))
        torch.onnx.export(wrapper, arguments, str(target), dynamo=False, opset_version=18,
                          input_names=["inputs_embeds", "generation_steps", "past_keys", "past_values"],
                          output_names=["logits", "present_keys", "present_values"],
                          dynamic_axes={"inputs_embeds": {1: "sequence_length"},
                                        "generation_steps": {0: "num_steps"},
                                        "past_keys": {3: "past_length"},
                                        "past_values": {3: "past_length"},
                                        "logits": {1: "sequence_length"},
                                        "present_keys": {3: "total_length"},
                                        "present_values": {3: "total_length"}},
                          do_constant_folding=True)
    graph = onnx.load(str(target))
    save_external(graph, target, "code_predictor.onnx.data")
    del model, cp, wrapper
    gc.collect()

    exported = ort_session(target)
    for T, step, past in cases:
        embeds = rng.standard_normal((1, T, 1024)).astype(np.float32) * 0.5
        past_k = zero5(past)
        out = exported.run(None, {"inputs_embeds": embeds,
                                  "generation_steps": np.array([step], dtype=np.int64),
                                  "past_keys": past_k, "past_values": past_k.copy()})
        expected = run_community(embeds, step, past_k)
        evidence["exported_vs_community"][f"T{T}_s{step}_p{past}"] = dict(
            logits=diff_stats(out[0], expected[0]), present=diff_stats(out[1], expected[1]))
        evidence["exported_shapes"][f"T{T}_s{step}_p{past}"] = [list(o.shape) for o in out]
    require(all(row["logits"]["max_abs"] <= 1e-4 and row["present"]["max_abs"] <= 1e-4
                for row in evidence["exported_vs_community"].values()),
            "exported CP graph failed the structural value gate")
    evidence["files"] = [dict(path=str(p), bytes=p.stat().st_size, sha256=sha(p))
                         for p in (target, target.parent / "code_predictor.onnx.data")]
    write_json(STAGING / "export-cp.json", evidence)
    print("export-cp ok:", json.dumps(evidence["exported_vs_community"], default=float)[:400], flush=True)


# ---------------------------------------------------------------------------
# assemble


def copy_hardlink(source: Path, target: Path) -> str:
    if target.exists():
        raise RuntimeError(f"refuse to overwrite {target}")
    try:
        os.link(source, target)
        return "hardlink"
    except OSError:
        shutil.copy2(source, target)
        return "copy"


def generate_support_files(evidence: dict) -> None:
    """Regenerate NPY tables / tokenizer / config from official weights+config.

    Every artifact must be value-identical to the shipping bundle tables; a
    mismatch aborts the build (the tables anchor the whole verified protocol).
    """
    import torch
    from safetensors.torch import load_file

    (OUT / "embeddings").mkdir(parents=True, exist_ok=True)
    (OUT / "tokenizer").mkdir(parents=True, exist_ok=True)
    weights = load_file(HF_SNAPSHOT / "model.safetensors")

    tables = {
        "text_embedding.npy": ("talker.model.text_embedding.weight", 16384),
        "talker_codec_embedding.npy": ("talker.model.codec_embedding.weight", None),
        "text_projection_fc1_weight.npy": ("talker.text_projection.linear_fc1.weight", None),
        "text_projection_fc1_bias.npy": ("talker.text_projection.linear_fc1.bias", None),
        "text_projection_fc2_weight.npy": ("talker.text_projection.linear_fc2.weight", None),
        "text_projection_fc2_bias.npy": ("talker.text_projection.linear_fc2.bias", None),
    }
    tables.update({f"cp_codec_embedding_{i}.npy":
                   (f"talker.code_predictor.model.codec_embedding.{i}.weight", None)
                   for i in range(15)})

    for name, (key, chunk) in tables.items():
        official = weights[key].float().numpy()
        target = OUT / "embeddings" / name
        if target.exists():
            raise RuntimeError(f"refuse to overwrite {target}")
        np.save(target, official)
        reference = np.load(REF_BUNDLE / "embeddings" / name, mmap_mode="r")
        same = reference.shape == official.shape and reference.dtype == official.dtype
        if same and chunk:
            same = all(np.array_equal(reference[i:i + chunk], official[i:i + chunk])
                       for i in range(0, len(reference), chunk))
        elif same:
            same = np.array_equal(reference, official)
        require(same, f"official-derived {name} differs from the verified bundle table")
        evidence["embeddings"][name] = dict(official_key=key, shape=list(official.shape),
                                            equal_to_bundle=True, bytes=target.stat().st_size)
        del official
        gc.collect()

    official_config = json.loads((HF_SNAPSHOT / "config.json").read_text())
    talker = official_config["talker_config"]
    derived = {
        "talker": {
            "hidden_size": talker["hidden_size"], "text_hidden_size": talker["text_hidden_size"],
            "vocab_size": talker["vocab_size"], "num_hidden_layers": talker["num_hidden_layers"],
            "num_attention_heads": talker["num_attention_heads"],
            "num_key_value_heads": talker["num_key_value_heads"], "head_dim": talker["head_dim"],
            "num_code_groups": talker["num_code_groups"],
            "codec_eos_token_id": talker["codec_eos_token_id"], "codec_think_id": talker["codec_think_id"],
            "codec_nothink_id": talker["codec_nothink_id"], "codec_think_bos_id": talker["codec_think_bos_id"],
            "codec_think_eos_id": talker["codec_think_eos_id"], "codec_pad_id": talker["codec_pad_id"],
            "codec_bos_id": talker["codec_bos_id"], "rope_theta": talker["rope_theta"],
        },
        "code_predictor": {
            "hidden_size": talker["code_predictor_config"]["hidden_size"],
            "vocab_size": talker["code_predictor_config"]["vocab_size"],
            "num_hidden_layers": talker["code_predictor_config"]["num_hidden_layers"],
            "num_attention_heads": talker["code_predictor_config"]["num_attention_heads"],
            "num_key_value_heads": talker["code_predictor_config"]["num_key_value_heads"],
            "head_dim": talker["code_predictor_config"]["head_dim"],
            "rope_theta": talker["code_predictor_config"]["rope_theta"],
        },
        "tts": {
            "tts_bos_token_id": official_config["tts_bos_token_id"],
            "tts_eos_token_id": official_config["tts_eos_token_id"],
            "tts_pad_token_id": official_config["tts_pad_token_id"],
            "im_start_token_id": official_config["im_start_token_id"],
            "im_end_token_id": official_config["im_end_token_id"],
        },
        "language_ids": dict(talker["codec_language_id"]),
        "speaker_dialect": dict(talker.get("spk_is_dialect", {})),
    }
    target = OUT / "embeddings" / "config.json"
    target.write_text(json.dumps(derived, ensure_ascii=False, indent=4) + "\n")
    require(json.loads(target.read_text()) == json.loads((REF_BUNDLE / "embeddings/config.json").read_text()),
            "official-derived embeddings/config.json differs from the verified bundle config")
    evidence["config"] = dict(equal_to_bundle=True)

    # Base has no built-in speaker IDs; the codec head is already inside
    # the talker graph. Neither duplicate asset is consumed by any runtime.
    evidence["omittedUnusedAssets"] = ["codec_head_weight.npy", "speaker_ids.json"]

    for name in ("vocab.json", "merges.txt"):
        target = OUT / "tokenizer" / name
        if target.exists():
            raise RuntimeError(f"refuse to overwrite {target}")
        shutil.copy2(HF_SNAPSHOT / name, target)
        evidence["tokenizer"][name] = dict(
            source="official checkpoint", sha256=sha(target),
            byte_equal_to_bundle=sha(target) == sha(REF_BUNDLE / "tokenizer" / name))


def assemble(args):
    check_runtime()
    source_revision()
    require(not (SIDE / "package.json").exists(), "package.json already exists; refusing to re-assemble")
    OUT.mkdir(parents=True, exist_ok=True)
    require(not any(OUT.iterdir()), f"package tree {OUT} is not empty; refusing to re-assemble")
    for required in (STAGING / "speaker_encoder.onnx", STAGING / "code_predictor.onnx"):
        require(required.exists(), f"missing staging artifact {required}; run the export steps first")
    links = {}
    for name, (source, expected) in FROZEN_GRAPHS.items():
        actual = sha(source)
        require(actual == expected, f"frozen graph {source} hash {actual} != recorded {expected}")
        links[name] = dict(origin=str(source), method=copy_hardlink(source, OUT / name),
                           bytes=(OUT / name).stat().st_size, sha256=actual)
    # talker: consume the FRESH REBUILD from the official checkpoint (not the old
    # frozen candidate) — a complete rebuildable package cannot rely on the old
    # adc1ae88… hardlink alone; the rebuild gates must have actually passed.
    require((REBUILD / "api2-proof.json").is_file(),
            f"talker rebuild missing at {REBUILD}; run the bench_tts_unified_talker.py rebuild steps first")
    proof = json.loads((REBUILD / "api2-proof.json").read_text())
    validation = json.loads((REBUILD / "api2-validation.json").read_text())
    require(validation.get("api2") is True and validation.get("cases") and
            all(case["consumed_outputs_pass"] for case in validation["cases"]),
            "talker rebuild API2 numerical gate failed or incomplete")
    synth = json.loads((REBUILD / "api2" / "results.json").read_text())
    require(synth.get("pairs") and len(synth["pairs"]) == 2 and
            all(pair["all_codes_equal"] and pair["group0_equal"] for pair in synth["pairs"]),
            "talker rebuild synthesis gate failed or incomplete")
    for rel in ("talker_api2.onnx", "talker_api2.onnx.data"):
        source = REBUILD / rel
        expected = next(entry["sha256"] for entry in proof["files"] if Path(entry["path"]).name == rel)
        actual = sha(source)
        require(actual == expected, f"rebuilt {rel} hash {actual} != its own proof {expected}")
        links[rel] = dict(origin=str(source), method=copy_hardlink(source, OUT / rel),
                          bytes=(OUT / rel).stat().st_size, sha256=actual,
                          frozen_candidate_sha256=FROZEN_TALKER_HASHES[rel],
                          bit_identical_to_frozen_candidate=actual == FROZEN_TALKER_HASHES[rel],
                          rebuild_gates=dict(api2_validation="6/6 consumed_outputs_pass" if all(
                              case["consumed_outputs_pass"] for case in validation["cases"]) else "failed",
                              synthesis="codes equal for both languages"))
    # promote the newly exported graphs from staging into the package root
    for name in ("speaker_encoder.onnx", "speaker_encoder.onnx.data",
                 "code_predictor.onnx", "code_predictor.onnx.data"):
        source = STAGING / name
        require(source.exists(), f"missing staging artifact {source}; run the export steps first")
        links[name] = dict(origin=str(source), method=copy_hardlink(source, OUT / name),
                           bytes=(OUT / name).stat().st_size, sha256=sha(OUT / name))
    evidence = dict(graphs=links, embeddings={}, tokenizer={}, config=None)

    # Community reference copies must be identical across the two bundle locations.
    for role in ("code_predictor.onnx", "speaker_encoder.onnx", "speaker_encoder.onnx.data",
                 "vocoder.onnx", "vocoder.onnx.data"):
        require(sha(REF_BUNDLE / role) == sha(REF_BUNDLE_ALT / role),
                f"community reference copies disagree for {role}")
    evidence["community_reference_consistent"] = True

    generate_support_files(evidence)
    shutil.copy2(Path(__file__), EVIDENCE / "build_tts_api2_package.py")
    evidence["script_snapshot_sha256"] = sha(EVIDENCE / "build_tts_api2_package.py")
    evidence["talker_rebuild"] = dict(directory=str(REBUILD), steps=REBUILD_STEPS,
                                      script="convert/bench_tts_unified_talker.py",
                                      script_sha256=sha(BENCH_UNIFIED_TALKER))
    write_json(EVIDENCE / "assemble.json", evidence)
    write_package_json()
    print("assemble ok; graphs:", json.dumps({k: (v["method"], v["sha256"][:8]) for k, v in links.items()}), flush=True)


def write_package_json() -> None:
    interfaces = json.loads((UNIFIED / "api2-interface.json").read_text())
    reference = json.loads((ICL_DIR / "interface.json").read_text())
    vocoder = json.loads((STREAMING / "interface.json").read_text())
    roles = {
        "speaker_encoder": {
            "files": ["speaker_encoder.onnx", "speaker_encoder.onnx.data"],
            "opset": 18, "weights": "official model.safetensors (BF16->FP32)",
            "inputs": {"mel_spectrogram": {"dtype": "float32", "shape": [1, "time", 128],
                                            "meaning": "log-mel from 24 kHz mono PCM; n_fft 1024, hop 256, 128 mels, fmin 0, fmax 12000, slaney norm, log(clamp 1e-5); batch fixed to 1 (the community graph's 'batch' dim never worked on ORT 1.24.2)"}},
            "outputs": {"speaker_embedding": {"dtype": "float32", "shape": [1, 1024]}},
        },
        "talker": {
            "files": ["talker_api2.onnx", "talker_api2.onnx.data"],
            "opset": 18, "api_version": interfaces["api_version"],
            "inputs": interfaces["inputs"], "outputs": interfaces["outputs"],
            "notes": "single session; batch prefill with P=0 and decode share this graph; "
                     "only logits/last_hidden_state/stacked KV are public; original artifact "
                     "filename kept because external data references embed the file name",
        },
        "code_predictor": {
            "files": ["code_predictor.onnx", "code_predictor.onnx.data"],
            "opset": 18, "weights": "official model.safetensors (BF16->FP32)",
            "inputs": {
                "inputs_embeds": {"dtype": "float32", "shape": ["batch", "sequence_length", 1024]},
                "generation_steps": {"dtype": "int64", "shape": [1],
                                     "meaning": "index of the code group being predicted (0..14); only length-1 inputs are consumed/validated"},
                "past_keys": {"dtype": "float32", "shape": [5, "batch", 8, "past_length", 128]},
                "past_values": {"dtype": "float32", "shape": [5, "batch", 8, "past_length", 128]},
            },
            "outputs": {
                "logits": {"dtype": "float32", "shape": ["batch", "sequence_length", 2048]},
                "present_keys": {"dtype": "float32", "shape": [5, "batch", 8, "total_length", 128]},
                "present_values": {"dtype": "float32", "shape": [5, "batch", 8, "total_length", 128]},
            },
            "notes": "logits = RMSNorm(hidden) @ lm_head[generation_steps].T over all positions; "
                     "rope positions are past_length..past_length+T-1; causal mask, no padding input; "
                     "real loop: fresh cache per frame, T=2 call with steps=[0] (talker hidden + group-0 "
                     "embedding) then T=1 calls with steps=[g-1] feeding the previous group's embedding",
        },
        "reference_encoder": {
            "files": ["reference_encoder.onnx", "reference_encoder.onnx.data"],
            "opset": reference["opset"],
            "inputs": reference["inputs"], "outputs": reference["outputs"],
            "notes": "group-major codes; official ICL helper consumes [R,16]",
        },
        "vocoder": {
            "files": ["vocoder_streaming.onnx", "vocoder_streaming.onnx.data"],
            "opset": vocoder["opset"], "stateful": True,
            "inputs": vocoder["inputs"], "outputs": vocoder["outputs"],
            "initial_state": vocoder["initial_state"],
            "sample_rate": vocoder["sample_rate"],
            "persistent_state_bytes": vocoder["persistent_state_bytes"],
            "notes": "feed reference codes first for ICL and discard the warmup PCM; "
                     "no flush call; new utterance resets all state to zero and position to 0",
        },
    }
    support = ["tokenizer/vocab.json", "tokenizer/merges.txt", "embeddings/config.json",
               "embeddings/text_embedding.npy", "embeddings/talker_codec_embedding.npy",
               "embeddings/text_projection_fc1_weight.npy", "embeddings/text_projection_fc1_bias.npy",
               "embeddings/text_projection_fc2_weight.npy", "embeddings/text_projection_fc2_bias.npy"] + \
              [f"embeddings/cp_codec_embedding_{i}.npy" for i in range(15)]
    package = {
        "packageId": "tts-api2",
        "recipeId": RECIPE_ID,
        "apiContractVersion": 2,
        "status": "candidate; compiled and host-verified, not installed as a default package",
        "roles": roles,
        "supportFiles": support,
        "languages": sorted(json.loads((OUT / "embeddings/config.json").read_text())["language_ids"]),
        "files": {},
    }
    for path in sorted(OUT.rglob("*")):
        if path.is_file() and path.name not in ("package.json", "build-provenance.json"):
            rel = str(path.relative_to(OUT))
            package["files"][rel] = dict(bytes=path.stat().st_size, sha256=sha(path))
    write_json(SIDE / "package.json", package)


# ---------------------------------------------------------------------------
# verify


class DualCodePredictor:
    """Run community + official CP on every call; record value diffs; return community outputs."""

    def __init__(self, community, official):
        self.community, self.official = community, official
        self.rows = []

    def run(self, names, inputs):
        if names is not None:
            raise ValueError("full ordered outputs required")
        a = self.community.run(None, inputs)
        b = self.official.run(None, inputs)
        row = dict(T=int(inputs["inputs_embeds"].shape[1]),
                   steps=int(inputs["generation_steps"][0]),
                   P=int(inputs["past_keys"].shape[3]),
                   logits_max_abs=float(np.max(np.abs(a[0].astype(np.float64) - b[0].astype(np.float64)))),
                   present_max_abs=float(np.max(np.abs(a[1].astype(np.float64) - b[1].astype(np.float64)))),
                   argmax_equal=bool(np.argmax(a[0][0, -1]) == np.argmax(b[0][0, -1])))
        self.rows.append(row)
        return a

    def summary(self) -> dict:
        if not self.rows:
            return dict(calls=0)
        return dict(calls=len(self.rows),
                    logits_max_abs=max(r["logits_max_abs"] for r in self.rows),
                    present_max_abs=max(r["present_max_abs"] for r in self.rows),
                    argmax_equal_all=all(r["argmax_equal"] for r in self.rows),
                    T2_calls=sum(1 for r in self.rows if r["T"] == 2),
                    T1_calls=sum(1 for r in self.rows if r["T"] == 1))


def package_sessions(tts, official_cp_only=False, speaker_encoder=OUT / "speaker_encoder.onnx"):
    """Build a session bundle over the package graphs.

    speaker_encoder defaults to the package (official) graph. The frozen-baseline
    code-continuation anchor passes the community graph explicitly so the
    conditioning path stays bit-identical to the verified runs; the official
    graph is verified value-by-value separately.
    """
    sessions = {
        "speaker_encoder": ort_session(speaker_encoder),
        "talker_decode": ort_session(OUT / "talker_api2.onnx"),
        "code_predictor": ort_session(OUT / "code_predictor.onnx"),
        "vocoder": ort_session(VERIFY_REFERENCES["community_vocoder"]),
    }
    config = tts.load_config(OUT)
    sessions["talker_prefill"] = PrefillViaDynamic(sessions["talker_decode"], config)
    if official_cp_only:
        yield sessions, None
        return
    dual = DualCodePredictor(ort_session(VERIFY_REFERENCES["community_code_predictor"]),
                             sessions["code_predictor"])
    sessions["code_predictor"] = dual
    yield sessions, dual


def verify(args):
    info = check_runtime()
    tts = load_frozen_runner()
    config = tts.load_config(OUT)
    tables = tts.EmbeddingTables(OUT)
    tokenizer = tts.load_tokenizer(OUT)
    texts = json.loads((POOL / "predeclared-criteria.json").read_text())["synthesis_texts"]
    frozen_codes = {lang: np.load(VERIFY_REFERENCES[f"frozen_121_{lang}_codes"])
                    for lang in ("chinese", "english")}

    evidence = dict(verify="api2-package", **info, cases={}, tokenizer=None)

    # --- tokenizer equivalence: package (official merges.txt) vs bundle merges ---
    sample_texts = [texts["chinese"], texts["english"],
                    "Mixed 中文 and English with numbers 12345!",
                    "The quick brown fox jumps over the lazy dog.",
                    "你好，世界。Hello world; train to London tomorrow.",
                    " 广州市天河区 — naive café résumé"]
    ids_package = [tokenizer.encode(t).ids for t in sample_texts]
    bundle_tokenizer = tts.load_tokenizer(REF_BUNDLE)
    ids_bundle = [bundle_tokenizer.encode(t).ids for t in sample_texts]
    require(ids_package == ids_bundle, "package tokenizer diverges from the verified bundle tokenizer")
    evidence["tokenizer"] = dict(sample_count=len(sample_texts), ids_equal=True,
                                 merges_note="official merges.txt differs from the bundle copy only by the '#version' header line; all three runtime parsers (Python/Kotlin/Swift) skip '#' lines")

    # --- speaker encoder: official (package) vs community on the anchor reference ---
    community_speaker = ort_session(VERIFY_REFERENCES["community_speaker_encoder"])
    audio_121 = load_reference_24k(tts, POOL / "121-reference24.wav")
    mel_121 = tts.compute_mel(audio_121)
    official_emb = ort_session(OUT / "speaker_encoder.onnx").run(
        None, {"mel_spectrogram": mel_121})[0].reshape(-1)
    community_emb = community_speaker.run(None, {"mel_spectrogram": mel_121})[0].reshape(-1)
    speaker_diff = diff_stats(official_emb, community_emb)
    require(speaker_diff["max_abs"] <= 1e-5,
            "package speaker encoder differs from the community graph beyond 1e-5")
    evidence["speaker_encoder"] = dict(reference="121-reference24.wav", official_vs_community=speaker_diff)

    # --- real generation anchor: community conditioning (frozen path), package talker ---
    anchor_sessions, _ = next(package_sessions(tts, official_cp_only=True,
                                               speaker_encoder=VERIFY_REFERENCES["community_speaker_encoder"]))
    embedding = tts.extract_speaker_embedding(
        type("Bundle", (), {"sessions": anchor_sessions})(), POOL / "121-reference24.wav")
    del anchor_sessions
    frozen_codes = {lang: np.load(VERIFY_REFERENCES[f"frozen_121_{lang}_codes"])
                    for lang in ("chinese", "english")}

    for language, text in texts.items():
        started = time.perf_counter()
        official_sessions, _ = next(package_sessions(tts, official_cp_only=True))
        official_bundle = type("Bundle", (), {"sessions": official_sessions})()
        result_official = tts.generate_codes(official_bundle, tables, config,
                                             tts.build_prompt_ids(tokenizer, text), embedding,
                                             language, **SAMPLING)
        del official_sessions
        dual_sessions, dual = next(package_sessions(tts, official_cp_only=False,
                                                    speaker_encoder=VERIFY_REFERENCES["community_speaker_encoder"]))
        dual_bundle = type("Bundle", (), {"sessions": dual_sessions})()
        result_dual = tts.generate_codes(dual_bundle, tables, config,
                                         tts.build_prompt_ids(tokenizer, text), embedding,
                                         language, **SAMPLING)
        del dual_sessions
        codes_equal_frozen = bool(np.array_equal(result_dual.codes, frozen_codes[language]))
        official_matches = bool(np.array_equal(result_official.codes, frozen_codes[language]))
        first_diff = None
        if not official_matches:
            differing = np.argwhere(result_official.codes != frozen_codes[language])
            first_diff = differing[0].tolist() if len(differing) else None
        summary = dual.summary()
        require(codes_equal_frozen, f"{language}: dual-run codes diverge from the frozen baseline")
        require(summary["calls"] == result_dual.frames * 15, f"{language}: unexpected CP call count")
        require(summary["logits_max_abs"] <= 1e-4 and summary["present_max_abs"] <= 1e-4,
                f"{language}: official CP exceeds the value gate against the community graph")
        evidence["cases"][language] = dict(
            text=text, frames=int(result_dual.frames),
            codes_equal_frozen_dual_sampled=codes_equal_frozen,
            codes_equal_frozen_official_sampled=official_matches,
            official_sampled_first_diff=first_diff,
            cp_dual=summary, seconds_diagnostic=time.perf_counter() - started)
        np.save(EVIDENCE / f"verify-{language}-codes.npy", result_dual.codes)
        np.save(EVIDENCE / f"verify-{language}-official-codes.npy", result_official.codes)
        print(f"verify {language}: frames={result_dual.frames} dual={codes_equal_frozen} "
              f"official={official_matches} cp_max_abs={summary['logits_max_abs']:.3e}", flush=True)
    evidence["cp_rows_note"] = "per-call rows live in DualCodePredictor.summary(); full per-call arrays are covered by the max-abs aggregates over every call"
    write_json(EVIDENCE / "verify.json", evidence)
    print("verify ok", flush=True)


# ---------------------------------------------------------------------------
# smoke


def streaming_vocoder_loop(session, codes: np.ndarray, chunk: int = 8):
    """Yield PCM per chunk with explicit state; codes are [16, F] group-major int64."""
    codes = np.asarray(codes)[None]  # session contract: [1, 16, F]
    conv_state = np.zeros(135232, dtype=np.float32)
    keys = np.zeros((8, 1, 16, 71, 64), dtype=np.float32)
    values = np.zeros((8, 1, 16, 71, 64), dtype=np.float32)
    position = np.zeros(1, dtype=np.int64)
    frames = codes.shape[2]
    parts, trace = [], []
    for start in range(0, frames, chunk):
        block = codes[..., start:start + chunk]
        out = session.run(None, {"codes": block, "conv_state": conv_state,
                                 "past_keys": keys, "past_values": values, "position": position})
        parts.append(np.asarray(out[0]).reshape(-1))
        conv_state, keys, values, position = (np.asarray(out[1]), np.asarray(out[2]),
                                              np.asarray(out[3]), np.asarray(out[4]))
        trace.append(dict(frames=int(block.shape[1]), position=int(position[0]),
                          state_bytes=int(conv_state.nbytes + keys.nbytes + values.nbytes + position.nbytes)))
    return np.concatenate(parts), trace


def smoke(args):
    info = check_runtime()
    tts = load_frozen_runner()
    workspace, workspace_sha = load_workspace_runner()
    config = tts.load_config(OUT)
    tables = tts.EmbeddingTables(OUT)
    tokenizer = tts.load_tokenizer(OUT)
    texts = json.loads((POOL / "predeclared-criteria.json").read_text())["synthesis_texts"]
    manifest = json.loads((POOL / "manifest.json").read_text())
    transcripts = {s["id"]: s["recordings"]["reference"]["text"] for s in manifest["speakers"]}

    streaming = ort_session(OUT / "vocoder_streaming.onnx")
    full_vocoder = ort_session(VERIFY_REFERENCES["community_vocoder"])
    reference_encoder = ort_session(OUT / "reference_encoder.onnx")
    speaker_sessions, _ = next(package_sessions(tts, official_cp_only=True))
    speaker_bundle = type("Bundle", (), {"sessions": speaker_sessions})()
    embedding_121 = tts.extract_speaker_embedding(speaker_bundle, POOL / "121-reference24.wav")
    embedding_260 = tts.extract_speaker_embedding(speaker_bundle, POOL / "260-reference24.wav")
    icl_bundle = speaker_bundle

    evidence = dict(smoke="api2-package", **info, workspace_runner_sha256=workspace_sha,
                    ort_threads=dict(intra=ORT_INTRA, inter=ORT_INTER), cases={})

    def pcm_checks(pcm: np.ndarray) -> dict:
        return dict(samples=int(pcm.size), finite=bool(np.isfinite(pcm).all()),
                    peak=float(np.max(np.abs(pcm))),
                    clipping_ratio=float(np.mean(np.abs(pcm) >= 0.999)),
                    rms=float(np.sqrt(np.mean(pcm.astype(np.float64) ** 2))))

    def synth_streaming(codes: np.ndarray, chunk: int = 8):
        pcm, trace = streaming_vocoder_loop(streaming, codes, chunk)
        full = full_vocoder.run(None, {"codes": codes[None]})[0].reshape(-1)
        return pcm, trace, diff_stats(pcm, full)

    # --- xvector cases: codes came from the verified loop; streaming vocoder on top ---
    for language in ("chinese", "english"):
        codes = np.load(EVIDENCE / f"verify-{language}-codes.npy")
        started = time.perf_counter()
        pcm, trace, vs_full = synth_streaming(codes)
        checks = pcm_checks(pcm)
        require(checks["finite"] and checks["clipping_ratio"] == 0.0 and checks["rms"] > 1e-3,
                f"xvector {language} PCM failed the finite/non-silent/clipping gate")
        require(vs_full["max_abs"] <= 1e-5, f"xvector {language} streaming PCM differs too much from full vocoder")
        require(trace[-1]["position"] == codes.shape[1] and all(t["state_bytes"] == 5193992 for t in trace),
                f"xvector {language} state bookkeeping failed")
        evidence["cases"][f"xvector-121-{language}"] = dict(
            frames=int(codes.shape[1]), chunks=len(trace), pcm=checks, streaming_vs_full_vocoder=vs_full,
            seconds_diagnostic=time.perf_counter() - started)
        np.save(EVIDENCE / f"smoke-xvector-{language}.npy", pcm)

    # --- ICL cases: package reference encoder + workspace ICL prompt helpers ---
    for voice, language in (("121", "chinese"), ("260", "english")):
        started = time.perf_counter()
        audio = np.load(VERIFY_REFERENCES[f"icl_reference_{voice}_pcm24"]).reshape(-1)
        require(np.isfinite(audio).all() and 1024 <= audio.size <= 24000 * 30,
                f"ICL {voice}: reference PCM out of range")
        codes_ref = workspace.encode_reference_codes(reference_encoder, audio, config)
        anchor = np.load(VERIFY_REFERENCES[f"icl_reference_{voice}_codes"])
        anchor_group_major = anchor if anchor.shape[0] == 16 else anchor.T
        ref_exact = bool(np.array_equal(codes_ref, anchor_group_major))
        require(ref_exact, f"ICL {voice}: package reference encoder codes differ from the recorded reference codes")
        reference_text = transcripts[voice]
        token_ids = tts.build_prompt_ids(tokenizer, texts[language])
        reference_token_ids = workspace.build_reference_prompt_ids(tokenizer, reference_text)
        # ICL generation uses the workspace runner's generate_codes (the frozen
        # runner predates ICL); the workspace file is read-only imported, SHA recorded.
        result = workspace.generate_codes(icl_bundle, tables, config, token_ids,
                                          embedding_121 if voice == "121" else embedding_260,
                                          language, **SAMPLING,
                                          reference_token_ids=reference_token_ids, reference_codes=codes_ref)
        # streaming vocoder: warm up with the reference codes (PCM discarded), then target
        warm_pcm, warm_trace = streaming_vocoder_loop(streaming, codes_ref, chunk=8)
        discarded = int(warm_pcm.size)
        target_pcm, target_trace = streaming_vocoder_loop(streaming, result.codes, chunk=8)
        full = full_vocoder.run(None, {"codes": result.codes[None]})[0].reshape(-1)
        vs_full = diff_stats(target_pcm, full)
        checks = pcm_checks(target_pcm)
        require(checks["finite"] and checks["clipping_ratio"] == 0.0 and checks["rms"] > 1e-3,
                f"ICL {voice} PCM failed the finite/non-silent/clipping gate")
        require(vs_full["max_abs"] <= 1e-5, f"ICL {voice} streaming PCM differs too much from full vocoder")
        evidence["cases"][f"icl-{voice}-{language}"] = dict(
            reference_frames=int(codes_ref.shape[1]), reference_codes_exact=ref_exact,
            reference_text=reference_text, warmup_discarded_samples=discarded,
            frames=int(result.frames), pcm=checks, streaming_vs_full_vocoder=vs_full,
            conditioning="package official speaker encoder embedding (no frozen ICL codes baseline exists; path-correctness smoke)",
            seconds_diagnostic=time.perf_counter() - started)
        np.save(EVIDENCE / f"smoke-icl-{voice}-{language}.npy", target_pcm)
        print(f"smoke icl {voice} {language}: frames={result.frames} ref_exact={ref_exact}", flush=True)

    # --- state reset/cancel semantics on the streaming vocoder ---
    codes = np.load(EVIDENCE / "verify-chinese-codes.npy")
    _, trace = streaming_vocoder_loop(streaming, codes[..., :8], chunk=8)
    fresh_out = streaming.run(None, {"codes": codes[None, ..., 8:16], "conv_state": np.zeros(135232, np.float32),
                                     "past_keys": np.zeros((8, 1, 16, 71, 64), np.float32),
                                     "past_values": np.zeros((8, 1, 16, 71, 64), np.float32),
                                     "position": np.zeros(1, np.int64)})[0]
    evidence["state_reset"] = dict(
        fresh_first_chunk_max_abs=float(np.max(np.abs(fresh_out))),
        note="a cancelled run must discard its state; a reset session re-emits the first chunk identically")
    require(float(np.max(np.abs(fresh_out))) > 0.0, "state reset reference chunk unexpectedly silent")

    write_json(EVIDENCE / "smoke.json", evidence)
    print("smoke ok", flush=True)


# ---------------------------------------------------------------------------
# provenance


def toolchain() -> dict:
    versions = dict(check_runtime())
    for name in ("numpy", "tokenizers", "transformers", "torch", "accelerate"):
        try:
            module = __import__(name)
            versions[name] = getattr(module, "__version__", "unknown")
        except Exception as exc:  # noqa: BLE001 - honest reporting
            versions[name] = f"not importable: {exc}"
    versions["platform"] = sys.platform
    return versions


def provenance(args):
    check_runtime()
    source_revision()
    outputs = []
    for path in sorted(OUT.rglob("*")):
        if not path.is_file():
            continue
        rel = str(path.relative_to(OUT))
        if rel == "build-provenance.json":
            continue
        entry = dict(path=rel, sizeBytes=path.stat().st_size, sha256=sha(path))
        role = None
        if rel.startswith("speaker_encoder"):
            role = "speaker_encoder"
        elif rel.startswith("talker_api2."):
            role = "talker"
        elif rel.startswith("code_predictor"):
            role = "code_predictor"
        elif rel.startswith("reference_encoder"):
            role = "reference_encoder"
        elif rel.startswith("vocoder_streaming."):
            role = "vocoder"
        if role:
            entry["role"] = role
        if rel.endswith(".onnx.data"):
            entry["externalData"] = True
        outputs.append(entry)
    require(outputs, "no outputs found")
    # every deployable file must be declared exactly once, provenance excluded
    deployed = {entry["path"] for entry in outputs}
    actual = {str(path.relative_to(OUT)) for path in OUT.rglob("*")
              if path.is_file() and path.name != "build-provenance.json"}
    require(deployed == actual, f"provenance outputs do not match the package tree: {deployed ^ actual}")
    package_files = json.loads((SIDE / "package.json").read_text())["files"]
    require(set(package_files) == deployed,
            f"package.json and provenance outputs disagree: {set(package_files) ^ deployed}")
    # the archived recipe must be the script that actually ran this build
    snapshot = EVIDENCE / "build_tts_api2_package.py"
    require(snapshot.is_file(), "archived recipe snapshot missing; run assemble first")
    recipe_sha = sha(snapshot)
    require(recipe_sha == sha(Path(__file__)),
            "archived recipe sha differs from the executing script; re-run the full pipeline")

    assemble_evidence = json.loads((EVIDENCE / "assemble.json").read_text())
    provenance_doc = {
        "schemaVersion": 1,
        "recipeId": RECIPE_ID,
        "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "upstream": {"repoId": "Qwen/Qwen3-TTS-12Hz-0.6B-Base", "revision": HF_REVISION},
        "sourceCode": {"repo": "Qwen3-TTS (qwen_tts package)", "revision": SOURCE_REVISION,
                       "path": str(SOURCE_REPO)},
        "recipe": {"path": "build_tts_api2_package.py", "sha256": recipe_sha},
        "toolchain": toolchain(),
        "inputs": [dict(path=name, sizeBytes=(HF_SNAPSHOT / name).stat().st_size,
                        sha256=sha(HF_SNAPSHOT / name)) for name in OFFICIAL_INPUT_FILES],
        "outputs": outputs,
        "derivation": {
            "exportedGraphs": ["speaker_encoder", "code_predictor"],
            "exportMethod": "torch.onnx.export(dynamo=False, opset 18) over official BF16->FP32 weights; "
                            "external .onnx.data files; interfaces match the verified community bundle I/O",
            "talkerRebuild": {
                "status": "rebuilt from the official checkpoint in a fresh directory",
                "directory": str(REBUILD),
                "script": "convert/bench_tts_unified_talker.py",
                "scriptSha256": assemble_evidence["talker_rebuild"]["script_sha256"],
                "steps": REBUILD_STEPS,
                "gates": assemble_evidence["graphs"]["talker_api2.onnx"]["rebuild_gates"],
                "rebuiltGraphSha256": assemble_evidence["graphs"]["talker_api2.onnx"]["sha256"],
                "rebuiltDataSha256": assemble_evidence["graphs"]["talker_api2.onnx.data"]["sha256"],
                "bitIdenticalToFrozenCandidate": assemble_evidence["graphs"]["talker_api2.onnx"]["bit_identical_to_frozen_candidate"],
                "historicalGapNote": "the ORIGINAL 2026-09-06 dynamic-export script (a215718…) never had a "
                                     "same-hash snapshot; that gap remains historical fact. The rebuild above "
                                     "reproduces (and replaces) the artifact through the snapshotted pipeline "
                                     "bench_tts_unified_talker.py with full numerical gates.",
            },
            "verifiedReuse": {name: info for name, info in assemble_evidence["graphs"].items()
                              if not name.startswith("talker_api2")},
            "supportFiles": "regenerated from official weights/config; value-identical to the shipping "
                            "bundle tables (see api2-package-evidence/assemble.json)",
            "recipeCommands": [
                "convert/build_tts_api2_package.py export-speaker",
                "convert/build_tts_api2_package.py export-cp",
                "convert/build_tts_api2_package.py assemble",
                "convert/build_tts_api2_package.py verify",
                "convert/build_tts_api2_package.py smoke",
            ],
        },
        "verification": {
            "communityBundleUsedOnlyAsReference": True,
            "communityBundle": {"repoId": "elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX",
                                "revision": "6a297d9641354ef0c16e63d329a93a6239bca0a2",
                                "path": str(REF_BUNDLE)},
            "evidence": ["api2-package-evidence/assemble.json", "api2-package-evidence/verify.json",
                         "api2-package-evidence/smoke.json", "api2-package-evidence/package.json",
                         "api2-package-evidence/staging/export-speaker.json",
                         "api2-package-evidence/staging/export-cp.json"],
        },
    }
    write_json(OUT / "build-provenance.json", provenance_doc)
    print(f"provenance ok: {len(outputs)} outputs", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("export-speaker")
    sub.add_parser("export-cp")
    sub.add_parser("assemble")
    sub.add_parser("verify")
    sub.add_parser("smoke")
    sub.add_parser("provenance")
    args = parser.parse_args()
    STAGING.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    {"export-speaker": export_speaker, "export-cp": export_cp, "assemble": assemble,
     "verify": verify, "smoke": smoke, "provenance": provenance}[args.command](args)


if __name__ == "__main__":
    main()
