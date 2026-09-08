#!/usr/bin/env python3
"""Host runner for the Qwen3-TTS-12Hz-0.6B-Base ONNX bundle.

Executes the real model graphs (no stubs, no fixture audio):

    reference wav -> resample 24k -> mel -> speaker_encoder.onnx -> speaker embedding
    text          -> byte-level BPE -> embeddings/*.npy + text projection
                  -> talker_prefill.onnx (logits/hidden/KV)
                  -> per frame: talker_decode.onnx + code_predictor.onnx (groups 1..15)
                  -> vocoder.onnx (codes [1,16,T] -> 24 kHz waveform)

The protocol is ported from the bundle author's reference implementation
(github.com/elbruno/ElBruno.QwenTTS, LanguageModel.cs / VoiceClonePipeline.cs)
and verified against the actual graph signatures of the pinned bundle.

CLI contract (mirrors convert/asr_runner.py):
  exit 0  success; --json-report (if given) contains a full success report
  exit 1  execution failure (graph error, silent or
          non-finite audio...); a failure JSON report is written, never a
          success-shaped one
  exit 2  missing runtime dependency or model/reference artifact
  exit 4  invalid arguments

Heavy third-party imports are lazy: importing this module (e.g. from the
unittest suite) requires only the standard library.

Voice cloning defaults to speaker-embedding-only conditioning. Experimental ICL
requires explicit reference text and a reference-encoder graph with its sibling
<graph>.data file; neither is inferred from the shipping bundle.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from file_integrity import sha256_of as sha256_file

SAMPLE_RATE = 24000
SAMPLES_PER_FRAME = 1920  # 12 Hz codec -> 24 kHz PCM
NUM_CODEBOOKS = 16        # RVQ groups sent to the vocoder
SPEAKER_EMBEDDING_DIM = 1024

REQUIRED_DEPENDENCIES = ("onnxruntime", "numpy", "tokenizers", "soundfile", "librosa", "soxr")

# Model graphs whose sha256 goes into every report (provenance evidence).
HASHED_FILES = (
    "speaker_encoder.onnx",
    "speaker_encoder.onnx.data",
    "talker_prefill.onnx",
    "talker_prefill.onnx.data",
    "talker_decode.onnx",
    "talker_decode.onnx.data",
    "code_predictor.onnx",
    "vocoder.onnx",
    "vocoder.onnx.data",
)

API2_GRAPHS = {"speaker_encoder": "speaker_encoder.onnx", "talker": "talker_api2.onnx",
               "code_predictor": "code_predictor.onnx", "reference_encoder": "reference_encoder.onnx",
               "vocoder": "vocoder_streaming.onnx"}
API2_HASHED_FILES = tuple(name for graph in API2_GRAPHS.values() for name in (graph, graph + ".data"))

# Canonical Qwen3-TTS special token ids (author's TextTokenizer.cs); they are
# NOT in the bundle vocab.json (Qwen keeps them as added tokens) so they must
# be registered explicitly with these ids.
SPECIAL_TOKEN_IDS = {
    "<|endoftext|>": 151643,
    "<|im_start|>": 151644,
    "<|im_end|>": 151645,
    "<|audio_start|>": 151669,
    "<|audio_end|>": 151670,
    "<tts_pad>": 151671,
    "<tts_text_bos>": 151672,
    "<tts_text_eod>": 151673,
    "<tts_text_bos_single>": 151674,
    "<|audio_pad|>": 151675,
}

# Language normalization: the shared manifest capabilities.languages are BCP-47
# short codes ("zh", "en", ...), the bundle config uses canonical names
# ("chinese", ...), and mobile engines accept both. The CLI accepts both plus
# common regional subtags, normalizes BEFORE any heavy work, and reports the
# requested value alongside the canonical one.
LANGUAGE_ALIASES = {
    "zh": "chinese", "zh-cn": "chinese", "zh-hans": "chinese", "zh-hans-cn": "chinese",
    "chinese": "chinese", "cmn": "chinese",
    "en": "english", "en-us": "english", "en-gb": "english", "english": "english",
    "de": "german", "de-de": "german", "german": "german",
    "it": "italian", "it-it": "italian", "italian": "italian",
    "pt": "portuguese", "pt-br": "portuguese", "pt-pt": "portuguese", "portuguese": "portuguese",
    "es": "spanish", "es-es": "spanish", "spanish": "spanish",
    "ja": "japanese", "ja-jp": "japanese", "japanese": "japanese",
    "ko": "korean", "ko-kr": "korean", "korean": "korean",
    "fr": "french", "fr-fr": "french", "french": "french",
    "ru": "russian", "ru-ru": "russian", "russian": "russian",
}


def normalize_language(value: str) -> str:
    """Map a language argument to the bundle's canonical name (or 'auto').

    Raises ValueError for anything unknown — callers must reject this before
    loading any model weight (CLI maps it to exit 4).
    """
    key = str(value).strip().lower().replace("_", "-")
    if key == "auto":
        return "auto"
    canonical = LANGUAGE_ALIASES.get(key)
    if canonical is None:
        raise ValueError(
            f"unsupported language '{value}'; use a canonical name "
            f"({', '.join(sorted(set(LANGUAGE_ALIASES.values())))}) or a "
            "BCP-47 code (zh, en, ...), or 'auto'")
    return canonical

# ---------------------------------------------------------------------------
# Lazy heavy imports


class MissingDependency(RuntimeError):
    pass


def missing_dependencies() -> list[str]:
    """Names of required third-party packages that are not importable."""
    import importlib.util
    missing = []
    for name in REQUIRED_DEPENDENCIES:
        try:
            if importlib.util.find_spec(name) is None:
                missing.append(name)
        except (ImportError, ValueError):
            missing.append(name)
    return missing


def _import_heavy():
    """Import heavy deps on first real use. Raises MissingDependency (exit 2)."""
    global np, ort, sf
    if getattr(_import_heavy, "_loaded", False):
        return
    try:
        import numpy
        import onnxruntime
        import soundfile
    except ImportError as e:
        raise MissingDependency(
            f"missing runtime dependency: {e.name or e}; install with "
            f"pip install {' '.join(REQUIRED_DEPENDENCIES)}") from e
    np = numpy
    ort = onnxruntime
    sf = soundfile
    _import_heavy._loaded = True


np = None
ort = None
sf = None


def _runtime_versions() -> dict:
    versions = {"python": sys.version.split()[0]}
    for name in REQUIRED_DEPENDENCIES:
        try:
            module = __import__(name)
            versions[name] = getattr(module, "__version__", "unknown")
        except Exception:  # noqa: BLE001 — version probe is best-effort
            versions[name] = "not importable"
    return versions


# ---------------------------------------------------------------------------
# Mel frontend (PyTorch-style, exact; verified vs torch+librosa, cos > 0.9999)


def build_mel_filterbank(sr: int, n_fft: int, n_mels: int, fmin: float, fmax: float):
    """librosa.filters.mel(sr, n_fft, n_mels, fmin, fmax) with slaney norm."""
    if np is None:
        raise MissingDependency("numpy not loaded")

    def hz_to_mel_slaney(f):
        f_sp = 200.0 / 3
        mels = (f - 0.0) / f_sp
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = math.log(6.4) / 27.0
        return np.where(
            f >= min_log_hz,
            min_log_mel + np.log(np.maximum(f, 1e-30) / min_log_hz) / logstep,
            mels,
        )

    def mel_to_hz_slaney(mels):
        f_sp = 200.0 / 3
        freqs = f_sp * mels
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = math.log(6.4) / 27.0
        return np.where(
            mels >= min_log_mel,
            min_log_hz * np.exp(logstep * (mels - min_log_mel)),
            freqs,
        )

    fftfreqs = np.arange(1 + n_fft // 2, dtype=np.float64) * (sr / n_fft)
    mel_f = mel_to_hz_slaney(
        np.linspace(hz_to_mel_slaney(np.array([fmin]))[0],
                    hz_to_mel_slaney(np.array([fmax]))[0], n_mels + 2)
    )
    ramps = np.subtract.outer(mel_f, fftfreqs)
    fdiff = np.diff(mel_f)
    weights = np.zeros((n_mels, 1 + n_fft // 2), dtype=np.float64)
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0.0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2 : n_mels + 2] - mel_f[:n_mels])  # slaney enorm
    weights *= enorm[:, None]
    return weights.astype(np.float32)


def compute_mel(audio, sr: int = 24000, n_fft: int = 1024, hop: int = 256,
                n_mels: int = 128, fmin: float = 0.0, fmax: float = 12000.0):
    """[T] float samples -> [1, T_mel, 128] log-mel matching the PyTorch reference."""
    y = np.asarray(audio, dtype=np.float32)
    pad = (n_fft - hop) // 2
    y = np.pad(y, (pad, pad), mode="reflect")
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(n_fft) / n_fft)  # periodic hann
    num_frames = 1 + (len(y) - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + hop * np.arange(num_frames)[:, None]
    frames = y[idx] * window[None, :]
    spec = np.abs(np.fft.rfft(frames, axis=1))
    magnitude = np.sqrt(spec * spec + 1e-9)
    basis = build_mel_filterbank(sr, n_fft, n_mels, fmin, fmax)
    mel = magnitude @ basis.T
    mel = np.log(np.maximum(mel, 1e-5))
    return mel[None].astype(np.float32)  # [1, T, n_mels]


# ---------------------------------------------------------------------------
# Bundle assets


@dataclass
class TtsConfig:
    talker: dict
    code_predictor: dict
    tts: dict
    language_ids: dict


def load_config(model_dir: Path) -> TtsConfig:
    cfg = json.loads((model_dir / "embeddings" / "config.json").read_text())
    return TtsConfig(cfg["talker"], cfg["code_predictor"], cfg["tts"], cfg["language_ids"])


class EmbeddingTables:
    """Embedding tables + text projection MLP shipped as .npy next to the graphs."""

    def __init__(self, model_dir: Path):
        if np is None:
            raise MissingDependency("numpy not loaded")
        emb = model_dir / "embeddings"
        # text_embedding is 1.2 GB: memory-map it, only touched rows are paged in.
        self.text_embedding = np.load(emb / "text_embedding.npy", mmap_mode="r")
        self.fc1_w = np.load(emb / "text_projection_fc1_weight.npy")
        self.fc1_b = np.load(emb / "text_projection_fc1_bias.npy")
        self.fc2_w = np.load(emb / "text_projection_fc2_weight.npy")
        self.fc2_b = np.load(emb / "text_projection_fc2_bias.npy")
        self.talker_codec = np.load(emb / "talker_codec_embedding.npy")
        self.cp_codecs = [np.load(emb / f"cp_codec_embedding_{i}.npy") for i in range(15)]
        self.hidden = int(self.fc2_w.shape[0])                # 1024
        self.text_hidden = int(self.text_embedding.shape[1])  # 2048
        for name, arr in [("fc1_w", self.fc1_w), ("fc2_w", self.fc2_w),
                          ("talker_codec", self.talker_codec)]:
            if not np.isfinite(arr).all():
                raise ValueError(f"non-finite values in {name}")
        if self.text_hidden != self.fc1_w.shape[1] or self.hidden != self.fc2_w.shape[0]:
            raise ValueError("text projection weight shapes inconsistent")

    def text_embed(self, token_id: int):
        return np.asarray(self.text_embedding[token_id], dtype=np.float32)

    def project(self, raw):
        h = raw @ self.fc1_w.T + self.fc1_b
        h = h / (1.0 + np.exp(-h))  # SiLU
        return h @ self.fc2_w.T + self.fc2_b

    def talker_codec_embedding(self, token_id: int):
        return self.talker_codec[token_id]

    def cp_codec_embedding(self, group_index: int, token_id: int):
        if not 0 <= group_index < 15:
            raise ValueError(f"cp group index must be 0..14, got {group_index}")
        return self.cp_codecs[group_index][token_id]


def load_tokenizer(model_dir: Path):
    """Qwen2-style byte-level BPE from the bundle's vocab.json + merges.txt."""
    from tokenizers import Tokenizer
    from tokenizers.models import BPE
    from tokenizers.pre_tokenizers import ByteLevel
    from tokenizers.decoders import ByteLevel as ByteLevelDecoder

    vocab = json.loads((model_dir / "tokenizer" / "vocab.json").read_text())
    for token, token_id in SPECIAL_TOKEN_IDS.items():
        vocab.setdefault(token, token_id)
    merges = []
    for line in (model_dir / "tokenizer" / "merges.txt").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and " " in line:
            merges.append(tuple(line.split(" ")))
    bpe = BPE(vocab=vocab, merges=merges, ignore_merges=True)
    tok = Tokenizer(bpe)
    tok.pre_tokenizer = ByteLevel(add_prefix_space=False, use_regex=True)
    tok.decoder = ByteLevelDecoder()
    tok.add_special_tokens(list(SPECIAL_TOKEN_IDS))
    return tok


def build_prompt_ids(tokenizer, text: str) -> list[int]:
    """Qwen3-TTS chat prompt: <|im_start|>assistant\\n{text}<|im_end|>\\n<|im_start|>assistant\\n"""
    prompt = "<|im_start|>assistant\n" + text + "<|im_end|>\n<|im_start|>assistant\n"
    return tokenizer.encode(prompt).ids


def build_reference_prompt_ids(tokenizer, text: str) -> list[int]:
    """Official reference wrapper, whose content is sliced with ids[3:-2]."""
    return tokenizer.encode("<|im_start|>assistant\n" + text + "<|im_end|>\n").ids


def validate_conditioning_options(conditioning_mode: str, reference_text, reference_encoder):
    """Validate the explicit mode before imports or model IO."""
    if conditioning_mode not in ("xvector", "icl"):
        raise ValueError("conditioning_mode must be xvector or icl")
    if conditioning_mode == "icl":
        if not isinstance(reference_text, str) or not reference_text.strip():
            raise ValueError("ICL requires nonempty --reference-text matching the reference audio")
        if reference_encoder is None:
            raise ValueError("ICL requires an explicit --reference-encoder graph")
    elif reference_text is not None or reference_encoder is not None:
        raise ValueError("--reference-text/--reference-encoder require --conditioning-mode icl")


def validate_reference_codes(codes, cfg: TtsConfig):
    """Return validated group-major int64 reference codes, never fabricated codes."""
    codes = np.asarray(codes)
    if (codes.dtype != np.int64 or codes.ndim != 2 or codes.shape[0] != NUM_CODEBOOKS
            or not 1 <= codes.shape[1] <= SAMPLE_RATE * 30 // SAMPLES_PER_FRAME):
        raise ValueError("reference codes must be int64 [16, frames] for a nonempty reference of at most 30 seconds")
    if np.any(codes < 0) or np.any(codes >= cfg.code_predictor["vocab_size"]):
        raise ValueError("reference encoder returned a reserved or out-of-range codec id")
    return codes


def build_icl_prompt(embs: EmbeddingTables, cfg: TtsConfig, token_ids: list[int],
                     reference_token_ids: list[int], reference_codes):
    """Official streaming-text ICL prefix/trailing tensors using existing tables.

    Accepts complete target/reference chat token sequences and group-major codes.
    The caller can prepare these tensors before starting target-frame generation.
    This returns the ICL body; generate_codes prepends the role/speaker prefix.
    """
    if len(token_ids) < 9 or len(reference_token_ids) < 6:
        raise ValueError("ICL requires nonempty wrapped target and reference token sequences")
    codes = validate_reference_codes(reference_codes, cfg)
    pad = embs.project(embs.text_embed(cfg.tts["tts_pad_token_id"]))
    eos = embs.project(embs.text_embed(cfg.tts["tts_eos_token_id"]))
    text_ids = list(reference_token_ids[3:-2]) + list(token_ids[3:-5])
    text_embeds = np.stack([embs.project(embs.text_embed(t)) for t in text_ids] + [eos])
    codec_embeds = np.stack([
        np.stack([embs.talker_codec_embedding(int(codes[0, f]))] +
                 [embs.cp_codec_embedding(g - 1, int(codes[g, f])) for g in range(1, NUM_CODEBOOKS)]).sum(axis=0)
        for f in range(codes.shape[1])])
    codec_embeds = np.concatenate([
        embs.talker_codec_embedding(cfg.talker["codec_bos_id"])[None], codec_embeds])
    length = len(codec_embeds)
    if len(text_embeds) > length:
        return text_embeds[:length] + codec_embeds, text_embeds[length:]
    padded = np.concatenate([text_embeds, np.repeat(pad[None], length - len(text_embeds), axis=0)])
    return padded + codec_embeds, pad[None]


# ---------------------------------------------------------------------------
# Generation loop (port of the author's LanguageModel.GenerateInternal)


def _softmax_sample(logits, temperature: float, top_k: int, rng) -> int:
    probs = logits.astype(np.float64)
    if (not math.isfinite(temperature) or temperature < 0
            or type(top_k) is not int or top_k < 0):
        raise ValueError("invalid sampling temperature or top_k")
    if (probs.ndim != 1 or probs.size == 0 or np.isnan(probs).any()
            or np.isposinf(probs).any() or not np.isfinite(probs).any()):
        raise ValueError("logits must contain a finite candidate and no NaN or +Inf")
    if temperature == 0 or top_k == 1:
        return int(np.argmax(probs))
    if top_k and top_k < len(probs):
        kth = np.partition(probs, -top_k)[-top_k]
        probs = np.where(probs < kth, -np.inf, probs)
    probs = probs - probs.max()
    # Negative overflow becomes zero probability; the best candidate stays 0.
    with np.errstate(over="ignore"):
        probs = probs / temperature
    exp = np.exp(probs)
    exp = exp / exp.sum()
    return int(rng.choice(len(exp), p=exp))


def sample_group0(logits_last, cfg: TtsConfig, temperature: float, top_k: int,
                  rep_penalty: float, generated: list[int], rng) -> int:
    vocab = cfg.talker["vocab_size"]             # 3072
    cp_vocab = cfg.code_predictor["vocab_size"]  # 2048
    eos = cfg.talker["codec_eos_token_id"]       # 2150
    probs = logits_last[-vocab:].astype(np.float64).copy()
    if not math.isfinite(rep_penalty) or rep_penalty <= 0:
        raise ValueError("repetition penalty must be finite and positive")
    # Match HF RepetitionPenaltyLogitsProcessor: one penalty per distinct
    # token, not a frequency penalty compounded over the generated history.
    for token in set(generated):
        if type(token) is not int or not 0 <= token < len(probs):
            raise ValueError("generated token is outside the codec vocabulary")
        if probs[token] > 0:
            probs[token] /= rep_penalty
        else:
            probs[token] *= rep_penalty
    for i in range(cp_vocab, vocab):
        if i != eos:
            probs[i] = -np.inf
    return _softmax_sample(probs, temperature, top_k, rng)


def sample_cp(logits_last, cfg: TtsConfig, temperature: float, top_k: int, rng) -> int:
    vocab = cfg.code_predictor["vocab_size"]
    return _softmax_sample(logits_last[-vocab:].astype(np.float64).copy(), temperature, top_k, rng)


@dataclass
class GenerationResult:
    codes: object            # [16, T] int64
    frames: int
    group0_tokens: list[int]
    prefill_s: float
    generate_s: float


def generate_codes(bundle, embs: EmbeddingTables, cfg: TtsConfig,
                   token_ids: list[int], speaker_embedding, language: str,
                   max_frames: int = 2048, temperature: float = 0.9, top_k: int = 50,
                   rep_penalty: float = 1.05, seed: int = 0, on_frame=None,
                   reference_token_ids: list[int] | None = None, reference_codes=None) -> GenerationResult:
    """Generate complete codec frames; an optional synchronous sink supplies backpressure.

    The sink receives an owned copy and may fail/cancel the operation. A frame
    callback is partial output, not an EOS or successful-synthesis indication.
    """
    t_generate = time.time()
    rng = np.random.default_rng(seed)
    H = embs.hidden
    if (reference_token_ids is None) != (reference_codes is None):
        raise ValueError("ICL generation requires both reference token ids and reference codes")

    # --- prefill embeddings (author's BuildPrefillEmbedding) -----------------
    if len(token_ids) < 9:
        raise ValueError(f"prompt too short ({len(token_ids)} tokens); text is unusable")

    role_embeds = np.stack([embs.project(embs.text_embed(t)) for t in token_ids[:3]])

    lang = language.lower()
    if lang == "auto":
        prefix = [cfg.talker["codec_nothink_id"], cfg.talker["codec_think_bos_id"],
                  cfg.talker["codec_think_eos_id"]]
    else:
        if lang not in cfg.language_ids:
            raise ValueError(f"unsupported language '{language}'; known: {sorted(cfg.language_ids)}")
        prefix = [cfg.talker["codec_think_id"], cfg.talker["codec_think_bos_id"],
                  int(cfg.language_ids[lang]), cfg.talker["codec_think_eos_id"]]
    speaker_pos = len(prefix)
    prefix.append(cfg.talker["codec_pad_id"])  # placeholder overridden by speaker embedding
    prefix.append(cfg.talker["codec_pad_id"])
    prefix.append(cfg.talker["codec_bos_id"])

    spk = np.asarray(speaker_embedding, dtype=np.float32).reshape(-1)
    if spk.shape[0] != H:
        raise ValueError(
            f"speaker embedding dim {spk.shape[0]} != talker hidden {H}; "
            "refusing to zero-pad a fabricated conditioning vector")

    tts_pad = embs.project(embs.text_embed(cfg.tts["tts_pad_token_id"]))
    tts_bos = embs.project(embs.text_embed(cfg.tts["tts_bos_token_id"]))
    tts_eos = embs.project(embs.text_embed(cfg.tts["tts_eos_token_id"]))

    talker_input = []
    for i in range(len(prefix) - 2):
        combined = tts_pad.copy()
        combined = combined + (spk if i == speaker_pos else embs.talker_codec_embedding(prefix[i]))
        talker_input.append(combined)
    talker_input.append(tts_bos + embs.talker_codec_embedding(prefix[-2]))

    all_embeds = [role_embeds, np.stack(talker_input)]
    if reference_codes is not None:
        icl_input, trailing = build_icl_prompt(embs, cfg, token_ids, reference_token_ids, reference_codes)
        all_embeds.append(icl_input)
    else:
        first_text = embs.project(embs.text_embed(token_ids[3])) + \
            embs.talker_codec_embedding(cfg.talker["codec_bos_id"])
        all_embeds.append(first_text[None])
        trailing_ids = token_ids[4 : len(token_ids) - 5]
        trailing = [embs.project(embs.text_embed(t)) for t in trailing_ids]
        trailing.append(tts_eos)
        trailing = np.stack(trailing)  # [Tt, H]

    prefill_embeds = np.concatenate(all_embeds, axis=0)[None]  # [1, T, H]
    prefill_len = prefill_embeds.shape[1]

    # --- talker prefill -------------------------------------------------------
    unified = "talker" in bundle.sessions
    prefill_feed = {
        "inputs_embeds": prefill_embeds,
        "attention_mask": np.ones((1, prefill_len), dtype=np.int64),
        "position_ids": np.tile(np.arange(prefill_len, dtype=np.int64), (3, 1, 1)),
    }
    if unified:
        empty = np.zeros((cfg.talker["num_hidden_layers"], 1, cfg.talker["num_key_value_heads"],
                          0, cfg.talker["head_dim"]), dtype=np.float32)
        outs = bundle.sessions["talker"].run(
            ["logits", "last_hidden_state", "present_keys", "present_values"],
            dict(prefill_feed, past_keys=empty, past_values=empty.copy()))
        logits, hidden, present_k, present_v = outs
    else:
        outs = bundle.sessions["talker_prefill"].run(None, prefill_feed)
        logits, hidden = outs[:2]
        present_k = np.stack([outs[2 + 2 * l] for l in range(cfg.talker["num_hidden_layers"])])
        present_v = np.stack([outs[3 + 2 * l] for l in range(cfg.talker["num_hidden_layers"])])
    del outs
    prefill_s = time.time() - t_generate

    cp_s = bundle.sessions["code_predictor"]
    dec_s = bundle.sessions["talker" if unified else "talker_decode"]

    group0_tokens: list[int] = []
    codes_per_frame: list = []

    for step in range(max_frames):
        logits_last = logits[0, -1]
        if step < 2:
            logits_last = logits_last.copy()
            logits_last[cfg.talker["codec_eos_token_id"]] = -np.inf
        g0 = sample_group0(logits_last, cfg, temperature, top_k, rep_penalty, group0_tokens, rng)
        if g0 == cfg.talker["codec_eos_token_id"]:
            break
        group0_tokens.append(g0)

        frame = np.zeros(NUM_CODEBOOKS, dtype=np.int64)
        frame[0] = g0

        # --- code predictor: groups 1..15, fresh cache per frame -------------
        cp_past_k = np.zeros((cfg.code_predictor["num_hidden_layers"], 1,
                              cfg.code_predictor["num_key_value_heads"], 0,
                              cfg.code_predictor["head_dim"]), dtype=np.float32)
        cp_past_v = cp_past_k.copy()
        cp_input = np.concatenate(
            [hidden[0, -1:].astype(np.float32),
             embs.talker_codec_embedding(g0).reshape(1, H)], axis=0)[None]
        for g in range(1, NUM_CODEBOOKS):
            cp_outs = cp_s.run(None, {
                "inputs_embeds": cp_input,
                "generation_steps": np.array([g - 1], dtype=np.int64),
                "past_keys": cp_past_k,
                "past_values": cp_past_v,
            })
            cp_logits = cp_outs[0][0, -1]
            cp_token = sample_cp(cp_logits, cfg, temperature, top_k, rng)
            frame[g] = cp_token
            cp_past_k, cp_past_v = cp_outs[1], cp_outs[2]
            if g < NUM_CODEBOOKS - 1:
                cp_input = embs.cp_codec_embedding(g - 1, cp_token).reshape(1, 1, H)
            del cp_outs
        codes_per_frame.append(frame)
        if on_frame is not None:
            on_frame(frame.copy())

        # --- next talker input: sum of all 16 group embeddings + text -------
        nxt = embs.talker_codec_embedding(frame[0]).astype(np.float32).copy()
        for g in range(1, NUM_CODEBOOKS):
            nxt = nxt + embs.cp_codec_embedding(g - 1, frame[g])
        if step < len(trailing):
            nxt = nxt + trailing[step]
        else:
            nxt = nxt + tts_pad

        total_len = prefill_len + step + 1
        dec_outs = dec_s.run(["logits", "last_hidden_state", "present_keys", "present_values"] if unified else None, {
            "inputs_embeds": nxt[None, None],
            "attention_mask": np.ones((1, total_len), dtype=np.int64),
            "position_ids": np.full((3, 1, 1), prefill_len + step, dtype=np.int64),
            "past_keys": present_k,
            "past_values": present_v,
        })
        logits = dec_outs[0]
        hidden = dec_outs[1]
        present_k, present_v = dec_outs[2], dec_outs[3]
        del dec_outs
    else:
        raise RuntimeError("frame budget exhausted before codec EOS; refusing truncated speech")

    if not codes_per_frame:
        raise RuntimeError("talker produced no audio frames (immediate codec EOS); "
                           "synthesis failed rather than emitting silence")
    codes = np.stack(codes_per_frame, axis=1)  # [16, T]
    return GenerationResult(codes=codes, frames=codes.shape[1],
                            group0_tokens=group0_tokens, prefill_s=prefill_s,
                            generate_s=time.time() - t_generate)


# ---------------------------------------------------------------------------
# Speaker embedding + synthesis


def resample_reference_audio(audio, sample_rate: int):
    """Use the official Qwen frontend's bandlimited soxr_hq reference path.

    Two-point interpolation changes speaker conditioning even when the output
    sounds plausible. Mobile uses the existing sherpa low-pass resampler; its
    numerical comparison with this reference is recorded separately.
    """
    if (not isinstance(sample_rate, int) or isinstance(sample_rate, bool)
            or not 8000 <= sample_rate <= 192000):
        raise ValueError("reference sample rate must be an integer in 8000..192000 Hz")
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim != 1 or audio.size == 0 or not np.isfinite(audio).all():
        raise ValueError("reference audio must contain finite mono samples")
    if audio.size > sample_rate * 30:
        raise ValueError("reference audio must not exceed 30 seconds")
    if sample_rate != SAMPLE_RATE:
        try:
            import librosa
            audio = librosa.resample(audio, orig_sr=sample_rate, target_sr=SAMPLE_RATE,
                                     res_type="soxr_hq", fix=True, scale=False)
        except ImportError as e:
            raise MissingDependency("librosa/soxr is required for reference resampling") from e
        if not np.isfinite(audio).all():
            raise ValueError("reference resampler produced non-finite samples")
    return audio


def load_reference_audio(reference_wav: Path):
    """Read valid reference PCM once and resample it to mono float32 24 kHz."""
    _import_heavy()
    if not Path(reference_wav).is_file():
        raise FileNotFoundError(f"missing reference audio: {reference_wav}")
    audio, sr = sf.read(reference_wav, dtype="float32", always_2d=True)
    audio = resample_reference_audio(audio.mean(axis=1), sr)
    return _validate_prepared_reference(audio)


def _validate_prepared_reference(audio):
    """Preparation gate; the standalone DSP resampler still permits silence."""
    audio = np.asarray(audio)
    if (audio.dtype != np.float32 or audio.ndim != 1 or audio.size < 1024
            or audio.size > SAMPLE_RATE * 30 or not np.isfinite(audio).all()):
        raise ValueError("reference preparation requires finite float32 mono 24 kHz PCM, 1024 samples..30 seconds")
    if np.max(np.abs(audio)) < np.float32(1e-4):
        raise ValueError("reference audio is silent or below the 1e-4 peak threshold")
    return np.ascontiguousarray(audio)


def speaker_embedding_from_audio(bundle, audio):
    _import_heavy()
    audio = _validate_prepared_reference(audio)
    mel = compute_mel(audio)
    out = bundle.sessions["speaker_encoder"].run(
        ["speaker_embedding"], {"mel_spectrogram": mel})[0]
    emb = out.reshape(-1).astype(np.float32)
    if emb.shape[0] != SPEAKER_EMBEDDING_DIM:
        raise ValueError(f"speaker encoder returned dim {emb.shape[0]}, expected {SPEAKER_EMBEDDING_DIM}")
    if not np.isfinite(emb).all():
        raise ValueError("speaker encoder produced non-finite embedding")
    return emb


def extract_speaker_embedding(bundle, reference_wav: Path):
    return speaker_embedding_from_audio(bundle, load_reference_audio(reference_wav))


def encode_reference_codes(session, audio_24k, cfg: TtsConfig):
    """Prepare owned reference codes before target generation or sink startup.

    This experimental export has one pcm float32[1,1,N] input and one codes
    int64[1,16,ceil(N/1920)] output. No resampling or transcript inference occurs.
    """
    _import_heavy()
    audio = _validate_prepared_reference(audio_24k)
    inputs, outputs = session.get_inputs(), session.get_outputs()
    if (len(inputs) != 1 or inputs[0].name != "pcm" or inputs[0].type != "tensor(float)"
            or len(inputs[0].shape) != 3 or inputs[0].shape[:2] != [1, 1]
            or len(outputs) != 1 or outputs[0].name != "codes" or outputs[0].type != "tensor(int64)"
            or len(outputs[0].shape) != 3 or outputs[0].shape[:2] != [1, NUM_CODEBOOKS]):
        raise ValueError("reference encoder graph does not match the pcm→codes experimental contract")
    result = session.run(["codes"], {"pcm": np.ascontiguousarray(audio)[None, None]})[0]
    expected_frames = (len(audio) + SAMPLES_PER_FRAME - 1) // SAMPLES_PER_FRAME
    if not isinstance(result, np.ndarray) or result.dtype != np.int64 or result.shape != (1, NUM_CODEBOOKS, expected_frames):
        raise ValueError(f"reference encoder output must be int64 [1,16,{expected_frames}]")
    return validate_reference_codes(result[0], cfg).copy()


def decode_waveform(session, generated_codes, reference_codes=None):
    """Decode reference context plus target, returning only target PCM."""
    codes = generated_codes if reference_codes is None else np.concatenate([reference_codes, generated_codes], axis=1)
    waveform = session.run(None, {"codes": codes[None]})[0].reshape(-1).astype(np.float32)
    expected = codes.shape[1] * SAMPLES_PER_FRAME
    if waveform.shape[0] != expected:
        raise RuntimeError(f"vocoder produced {waveform.shape[0]} samples, expected {expected}")
    if not np.isfinite(waveform).all():
        raise RuntimeError("vocoder produced non-finite samples")
    trim = 0 if reference_codes is None else reference_codes.shape[1] * SAMPLES_PER_FRAME
    return waveform[trim:].copy()


class StreamingVocoder:
    """One causal vocoder state; reference output is discarded before target PCM."""
    def __init__(self, session, reference_codes=None, on_audio_chunk=None):
        self.session, self.on_audio_chunk = session, on_audio_chunk
        self.state = {"conv_state": np.zeros(135232, np.float32),
                      "past_keys": np.zeros((8, 1, 16, 71, 64), np.float32),
                      "past_values": np.zeros((8, 1, 16, 71, 64), np.float32),
                      "position": np.zeros(1, np.int64)}
        self.pending, self.audio, self.chunks = [], [], []
        self.vocoder_s = 0.0
        if reference_codes is not None:
            self._step(reference_codes)  # discard reference PCM
        self.started_at = time.perf_counter()

    def _step(self, codes):
        count = codes.shape[1]
        if count <= 0:
            raise ValueError("vocoder step requires positive frames")
        started = time.perf_counter()
        values = self.session.run(["waveform", "conv_state_out", "present_keys", "present_values", "position_out"],
                                  dict(self.state, codes=codes[None]))
        wave = values[0]
        if wave.dtype != np.float32 or wave.shape != (1, 1, count * SAMPLES_PER_FRAME) or not np.isfinite(wave).all():
            raise RuntimeError("invalid streaming vocoder PCM")
        next_state = dict(zip(self.state, values[1:], strict=True))
        for name, value in next_state.items():
            if value.shape != self.state[name].shape or value.dtype != self.state[name].dtype or not np.isfinite(value).all():
                raise RuntimeError("invalid streaming vocoder state: " + name)
        if int(next_state["position"][0]) != int(self.state["position"][0]) + count:
            raise RuntimeError("invalid streaming vocoder position")
        self.state = next_state
        self.vocoder_s += time.perf_counter() - started
        return wave.reshape(-1).copy()

    def accept_frame(self, frame):
        self.pending.append(frame.copy())
        if len(self.pending) == 4:
            self._flush()

    def _flush(self):
        if not self.pending:
            return
        wave = self._step(np.stack(self.pending, axis=1))
        if self.on_audio_chunk is not None:
            self.on_audio_chunk(np.clip(wave, -1.0, 1.0).copy())
        self.audio.append(wave)
        self.chunks.append(dict(frames=len(self.pending), availableSeconds=time.perf_counter()-self.started_at))
        self.pending.clear()

    def finish_after_eos(self):
        self._flush()
        if not self.audio:
            raise RuntimeError("vocoder produced no target audio")
        return np.concatenate(self.audio)


def compute_model_hashes(model_dir: Path, files=HASHED_FILES) -> dict:
    """sha256 per graph file; hardlinked external data is hashed once."""
    hashes = {}
    inodes = {}
    for name in files:
        path = model_dir / name
        if not path.exists():
            hashes[name] = "MISSING"
            continue
        st = path.stat()
        if st.st_ino in inodes and st.st_nlink > 1:
            hashes[name] = inodes[st.st_ino] + " (hardlink-verified)"
        else:
            digest = sha256_file(path)
            hashes[name] = digest
            inodes[st.st_ino] = digest
    return hashes


def synthesize(model_dir: Path, text: str, language: str, reference_wav: Path,
               out_wav: Path, max_frames: int = 2048, temperature: float = 0.9,
               top_k: int = 50, rep_penalty: float = 1.05, seed: int = 0,
               threads: int = 4, conditioning_mode: str = "xvector",
               reference_text: str | None = None, reference_encoder: Path | None = None,
               api_contract_version: str = "1", on_audio_chunk=None) -> dict:
    t_start = time.time()
    if api_contract_version not in ("1", "2"):
        raise ValueError("unsupported TTS API contract version")
    if api_contract_version == "2":
        if reference_encoder is not None:
            raise ValueError("API2 uses the reference_encoder role inside the model package")
        if conditioning_mode == "icl":
            reference_encoder = model_dir / API2_GRAPHS["reference_encoder"]
        for filename in API2_HASHED_FILES:
            if not (model_dir / filename).is_file():
                raise FileNotFoundError(f"missing API2 bundle artifact: {filename}")
    language_requested = language
    # Normalize before ANY heavy work (hashing, embedding load, sessions).
    language = normalize_language(language)
    validate_conditioning_options(conditioning_mode, reference_text, reference_encoder)
    encoder_files = []
    if reference_encoder is not None:
        reference_encoder = Path(reference_encoder)
        encoder_files = [reference_encoder, Path(str(reference_encoder) + ".data")]
        for path in encoder_files:
            if not path.is_file():
                raise FileNotFoundError(f"missing experimental reference encoder artifact: {path}")
    _import_heavy()
    t_hash = time.time()
    hashes = compute_model_hashes(model_dir, API2_HASHED_FILES if api_contract_version == "2" else HASHED_FILES)
    encoder_hashes = {path.name: sha256_file(path) for path in encoder_files}
    reference_hash = sha256_file(reference_wav)
    hash_s = time.time() - t_hash

    cfg = load_config(model_dir)
    embs = EmbeddingTables(model_dir)
    tokenizer = load_tokenizer(model_dir)

    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    providers = ["CPUExecutionProvider"]
    audio = load_reference_audio(Path(reference_wav))
    reference_codes = None
    reference_token_ids = None
    reference_encode_s = 0.0
    if conditioning_mode == "icl":
        start = time.time()
        encoder_session = ort.InferenceSession(str(reference_encoder), sess_options=opts, providers=providers)
        reference_codes = encode_reference_codes(encoder_session, audio, cfg)
        del encoder_session
        reference_encode_s = time.time() - start
        reference_token_ids = build_reference_prompt_ids(tokenizer, reference_text)
    sessions = {}
    graph_paths = (API2_GRAPHS if api_contract_version == "2" else
                   {name: name + ".onnx" for name in ("speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder")})
    for role, filename in graph_paths.items():
        if role == "reference_encoder":
            continue  # reference preparation has already loaded and released it
        path = model_dir / filename
        if not path.exists():
            raise FileNotFoundError(f"missing bundle graph: {path}")
        sessions[role] = ort.InferenceSession(str(path), sess_options=opts, providers=providers)
    bundle = type("Bundle", (), {"sessions": sessions})()

    emb = speaker_embedding_from_audio(bundle, audio)
    token_ids = build_prompt_ids(tokenizer, text)
    stream = StreamingVocoder(sessions["vocoder"], reference_codes, on_audio_chunk) if api_contract_version == "2" else None
    gen = generate_codes(bundle, embs, cfg, token_ids, emb, language,
                         max_frames=max_frames, temperature=temperature, top_k=top_k,
                         rep_penalty=rep_penalty, seed=seed,
                         reference_token_ids=reference_token_ids, reference_codes=reference_codes,
                         on_frame=stream.accept_frame if stream else None)

    t_vocoder = time.time()
    waveform = stream.finish_after_eos() if stream else decode_waveform(sessions["vocoder"], gen.codes, reference_codes)
    expected = gen.frames * SAMPLES_PER_FRAME
    if waveform.shape[0] != expected:
        raise RuntimeError(f"vocoder produced {waveform.shape[0]} samples, expected {expected}")
    vocoder_s = stream.vocoder_s if stream else time.time() - t_vocoder

    finite = bool(np.isfinite(waveform).all())
    if not finite:
        raise RuntimeError("vocoder produced non-finite samples")
    peak = float(np.max(np.abs(waveform)))
    if peak < 1e-4:
        raise RuntimeError(f"waveform is silent (peak {peak:.2e}); synthesis failed")
    rms = float(np.sqrt(np.mean(waveform.astype(np.float64) ** 2)))
    clipping_ratio = float(np.mean(np.abs(waveform) >= 0.999))
    waveform = np.clip(waveform, -1.0, 1.0)
    if stream is None and on_audio_chunk is not None:
        on_audio_chunk(waveform.copy())
    out_wav.parent.mkdir(parents=True, exist_ok=True)
    sf.write(out_wav, waveform, SAMPLE_RATE)

    return {
        "status": "success",
        "api_contract_version": api_contract_version,
        "chunks": stream.chunks if stream else [],
        "text": text,
        "language": language,
        "language_requested": language_requested,
        "model_dir": str(model_dir),
        "reference_wav": str(reference_wav),
        "conditioningMode": conditioning_mode,
        "reference_text": reference_text,
        "reference_wav_sha256": reference_hash,
        "reference_pcm_sha256": hashlib.sha256(audio.tobytes()).hexdigest(),
        "reference_samples_24k": len(audio),
        "reference_encoder": str(reference_encoder) if reference_encoder is not None else None,
        "reference_encoder_sha256": encoder_hashes,
        "reference_frames": 0 if reference_codes is None else reference_codes.shape[1],
        "reference_pcm_trimmed_samples": 0 if reference_codes is None else reference_codes.shape[1] * SAMPLES_PER_FRAME,
        "audio_path": str(out_wav),
        "sample_rate": SAMPLE_RATE,
        "duration_s": round(gen.frames * SAMPLES_PER_FRAME / SAMPLE_RATE, 3),
        "frames": gen.frames,
        "group0_tokens": gen.group0_tokens,
        "termination": "codec_eos",
        "prompt_tokens": len(token_ids),
        "peak": round(peak, 4),
        "rms": round(rms, 4),
        "clipping_ratio": round(clipping_ratio, 6),
        "finite": finite,
        "seed": seed,
        "sampling": {"temperature": temperature, "top_k": top_k,
                     "repetition_penalty": rep_penalty},
        "timing_s": {
            "model_hash": round(hash_s, 2),
            "reference_encode": round(reference_encode_s, 2),
            "prefill": round(gen.prefill_s, 2),
            "generate": round(gen.generate_s, 2),
            "vocoder": round(vocoder_s, 2),
            "wall": round(time.time() - t_start, 2),
        },
        "timing_scope": "generation includes streaming vocoder and sink backpressure" if stream else "separate generation and vocoder",
        "runtime_versions": _runtime_versions(),
        "model_sha256": hashes,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


# ---------------------------------------------------------------------------
# CLI


class TtsArgumentParser(argparse.ArgumentParser):
    """argparse exits 2 on errors by default; the contract here is exit 4."""

    def error(self, message):  # noqa: D102
        self.print_usage(sys.stderr)
        sys.stderr.write(f"{self.prog}: error: {message}\n")
        raise SystemExit(4)


def _write_report(path: Path | None, payload: dict) -> None:
    if path is None:
        return
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    except OSError as e:
        print(f"warning: could not write report {path}: {e}", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    parser = TtsArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--api-contract-version", choices=("1", "2"), default="1")
    parser.add_argument("--text", required=True)
    parser.add_argument("--language", default="chinese",
                        help="canonical name (chinese|english|german|italian|"
                             "portuguese|spanish|japanese|korean|french|russian), "
                             "BCP-47 code (zh, en, ...), or auto")
    parser.add_argument("--reference-wav", required=True, type=Path,
                        help="voice-cloning reference audio (>= ~0.1 s at 24 kHz)")
    parser.add_argument("--conditioning-mode", choices=("xvector", "icl"), default="xvector")
    parser.add_argument("--reference-text", help="actual reference transcript; required only for explicit ICL")
    parser.add_argument("--reference-encoder", type=Path,
                        help="experimental ICL encoder .onnx path; sibling <graph>.data required")
    parser.add_argument("--out", required=True, type=Path, help="output wav path")
    parser.add_argument("--json-report", type=Path, default=None,
                        help="write a machine-readable report here (success or failure)")
    parser.add_argument("--max-frames", type=int, default=2048)
    parser.add_argument("--temperature", type=float, default=0.9)
    parser.add_argument("--top-k", type=int, default=50)
    parser.add_argument("--rep-penalty", type=float, default=1.05)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args(argv)

    # Argument validation BEFORE any model IO/hashing/loading: an unsupported
    # language must exit 4 in milliseconds, not fail exit 1 after an 11 s load.
    try:
        normalize_language(args.language)
        if args.api_contract_version == "2" and args.reference_encoder is not None:
            raise ValueError("API2 uses its packaged encoder; do not pass --reference-encoder")
        encoder = (args.model_dir / API2_GRAPHS["reference_encoder"]
                   if args.api_contract_version == "2" and args.conditioning_mode == "icl" else args.reference_encoder)
        validate_conditioning_options(args.conditioning_mode, args.reference_text, encoder)
    except ValueError as e:
        parser.error(str(e))  # raises SystemExit(4)

    if not args.text.strip():
        parser.error("--text must not be empty")
    if args.threads < 1 or not 1 <= args.max_frames <= 2048 or args.top_k < 0:
        parser.error("--threads must be positive; --max-frames must be 1..2048; --top-k must be nonnegative")
    if not math.isfinite(args.temperature) or args.temperature < 0 or not math.isfinite(args.rep_penalty) or args.rep_penalty <= 0:
        parser.error("--temperature must be finite and nonnegative; --rep-penalty must be finite and positive")

    missing = missing_dependencies()
    if missing:
        payload = {
            "status": "error",
            "conditioningMode": args.conditioning_mode,
            "error_type": "MissingDependency",
            "error": f"missing runtime dependencies: {', '.join(missing)}",
            "missing": missing,
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
        _write_report(args.json_report, payload)
        print(json.dumps(payload, ensure_ascii=False))
        return 2


    base = {
        "status": "error",
        "text": args.text,
        "language": args.language,
        "model_dir": str(args.model_dir),
        "reference_wav": str(args.reference_wav),
        "conditioningMode": args.conditioning_mode,
        "reference_text": args.reference_text,
        "reference_encoder": str(args.reference_encoder) if args.reference_encoder is not None else None,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    try:
        report = synthesize(args.model_dir, args.text, args.language, args.reference_wav,
                            args.out, max_frames=args.max_frames,
                            temperature=args.temperature, top_k=args.top_k,
                            rep_penalty=args.rep_penalty, seed=args.seed,
                            threads=args.threads, conditioning_mode=args.conditioning_mode,
                            reference_text=args.reference_text, reference_encoder=args.reference_encoder,
                            api_contract_version=args.api_contract_version)
    except (MissingDependency, FileNotFoundError) as e:
        payload = {**base, "error_type": type(e).__name__, "error": str(e)}
        _write_report(args.json_report, payload)
        print(json.dumps(payload, ensure_ascii=False))
        return 2
    except (SystemExit, KeyboardInterrupt):
        raise
    except Exception as e:  # noqa: BLE001 — CLI boundary, report honestly
        payload = {**base, "error_type": type(e).__name__, "error": str(e)}
        _write_report(args.json_report, payload)
        print(json.dumps(payload, ensure_ascii=False))
        return 1
    _write_report(args.json_report, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
