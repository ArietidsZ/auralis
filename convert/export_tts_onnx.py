#!/usr/bin/env python3
"""
Export Qwen3-TTS-12Hz-0.6B-Base to ONNX format with INT4 quantization.

This script leverages existing community ONNX exports where available,
and applies INT4 quantization for on-device NPU inference.

The TTS model consists of multiple sub-modules:
1. Speaker Encoder (ECAPA-TDNN) - ~34MB
2. Talker LM (prefill + decode) - ~1.7GB
3. Code Predictor - ~440MB
4. Vocoder - ~2.7MB
5. Embeddings - ~1.4GB

Requirements:
    - CUDA GPU with ≥8GB VRAM
    - pip install -r requirements.txt
"""

import os
import sys
import logging
import argparse
from pathlib import Path

import torch
import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

DEFAULT_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
DEFAULT_ONNX_REPO = "onnx-community/Qwen3-TTS-12Hz-0.6B-Base-ONNX"
DEFAULT_OUTPUT_DIR = Path(__file__).parent.parent / "models" / "tts"

# Sub-modules and their expected sizes (pre-quantization)
TTS_SUBMODULES = {
    "speaker_encoder": {"keep_precision": "int4", "desc": "ECAPA-TDNN speaker embedding"},
    "talker_lm_prefill": {"keep_precision": "int4", "desc": "Talker LM prefill pass"},
    "talker_lm_decode": {"keep_precision": "int4", "desc": "Talker LM single-step decode"},
    "code_predictor": {"keep_precision": "int4", "desc": "Multi-codebook code predictor"},
    "vocoder": {"keep_precision": "int4", "desc": "Speech vocoder"},
    "embeddings": {"keep_precision": "int4", "desc": "Token embeddings"},
}


def download_community_onnx(repo_id: str, output_dir: Path):
    """Download pre-exported ONNX models from HuggingFace community."""
    logger.info(f"Downloading community ONNX export from {repo_id}...")

    try:
        from huggingface_hub import snapshot_download

        local_dir = snapshot_download(
            repo_id=repo_id,
            local_dir=str(output_dir / "raw"),
            allow_patterns=["*.onnx", "*.json", "*.txt", "*.model"],
        )
        logger.info(f"Downloaded to {local_dir}")
        return Path(local_dir)
    except Exception as e:
        logger.error(f"Failed to download community ONNX: {e}")
        return None


def export_from_pytorch(model_id: str, output_dir: Path):
    """Export TTS model from PyTorch to ONNX (fallback if community ONNX unavailable)."""
    logger.info(f"Exporting TTS from PyTorch: {model_id}")

    from qwen_tts import Qwen3TTSModel

    model = Qwen3TTSModel.from_pretrained(
        model_id,
        device_map="cuda:0",
        dtype=torch.bfloat16,
    )

    # Export speaker encoder
    _export_speaker_encoder(model, output_dir)

    # Export talker LM
    _export_talker_lm(model, output_dir)

    # Export vocoder
    _export_vocoder(model, output_dir)

    return True


def _export_speaker_encoder(model, output_dir: Path):
    """Export the ECAPA-TDNN speaker encoder."""
    logger.info("Exporting speaker encoder...")
    encoder = getattr(model, "speaker_encoder", None)
    if encoder is None:
        logger.warning("Speaker encoder not found as direct attribute")
        return

    encoder.eval()
    # Input: (batch, samples) - raw waveform at 16kHz, ~3 seconds
    dummy_wav = torch.randn(1, 48000, device="cuda", dtype=torch.float32)

    output_path = output_dir / "speaker_encoder.onnx"
    with torch.no_grad():
        torch.onnx.export(
            encoder,
            (dummy_wav,),
            str(output_path),
            opset_version=17,
            input_names=["waveform"],
            output_names=["speaker_embedding"],
            dynamic_axes={
                "waveform": {0: "batch", 1: "samples"},
                "speaker_embedding": {0: "batch"},
            },
        )
    logger.info(f"Speaker encoder: {output_path.stat().st_size / 1e6:.1f} MB")


def _export_talker_lm(model, output_dir: Path):
    """Export the talker language model."""
    logger.info("Exporting talker LM...")
    lm = getattr(model, "talker_lm", None) or getattr(model, "model", None)
    if lm is None:
        logger.warning("Talker LM not found")
        return

    # This is a complex autoregressive model - export in decode mode
    lm.eval()
    hidden_size = 896  # 0.6B model hidden size

    # Prefill mode
    dummy_input_ids = torch.randint(0, 32000, (1, 50), device="cuda")
    output_path = output_dir / "talker_lm.onnx"

    try:
        with torch.no_grad():
            torch.onnx.export(
                lm,
                (dummy_input_ids,),
                str(output_path),
                opset_version=17,
                input_names=["input_ids"],
                output_names=["logits"],
                dynamic_axes={
                    "input_ids": {0: "batch", 1: "seq_len"},
                    "logits": {0: "batch", 1: "seq_len"},
                },
            )
        logger.info(f"Talker LM: {output_path.stat().st_size / 1e6:.1f} MB")
    except Exception as e:
        logger.error(f"Talker LM export failed: {e}")


def _export_vocoder(model, output_dir: Path):
    """Export the vocoder (codes → waveform)."""
    logger.info("Exporting vocoder...")
    vocoder = getattr(model, "vocoder", None)
    if vocoder is None:
        logger.warning("Vocoder not found")
        return

    vocoder.eval()
    dummy_codes = torch.randint(0, 1024, (1, 8, 100), device="cuda")  # (batch, codebooks, time)

    output_path = output_dir / "vocoder.onnx"
    with torch.no_grad():
        torch.onnx.export(
            vocoder,
            (dummy_codes,),
            str(output_path),
            opset_version=17,
            input_names=["codes"],
            output_names=["waveform"],
            dynamic_axes={
                "codes": {0: "batch", 2: "time"},
                "waveform": {0: "batch", 1: "samples"},
            },
        )
    logger.info(f"Vocoder: {output_path.stat().st_size / 1e6:.1f} MB")


def quantize_model_int4(onnx_path: Path, output_path: Path):
    """Apply INT4 block-wise quantization."""
    logger.info(f"Quantizing {onnx_path.name} to INT4...")

    try:
        from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
        import onnx

        model = onnx.load(str(onnx_path))
        quantizer = MatMul4BitsQuantizer(
            model=model,
            block_size=32,
            is_symmetric=True,
            accuracy_level=4,
        )
        quantizer.process()
        quantizer.model.save_model_to_file(str(output_path))

        orig_mb = onnx_path.stat().st_size / 1e6
        quant_mb = output_path.stat().st_size / 1e6
        logger.info(f"  {orig_mb:.1f} MB → {quant_mb:.1f} MB ({quant_mb / orig_mb * 100:.0f}%)")
    except ImportError:
        from onnxruntime.quantization import quantize_dynamic, QuantType

        logger.warning("INT4 quantizer unavailable. Using INT8 dynamic quantization.")
        quantize_dynamic(str(onnx_path), str(output_path), weight_type=QuantType.QInt8)


def quantize_fp16(onnx_path: Path, output_path: Path):
    """Convert model weights to FP16 (for small models like vocoder)."""
    logger.info(f"Converting {onnx_path.name} to FP16...")

    try:
        from onnxruntime.transformers import float16
        import onnx

        model = onnx.load(str(onnx_path))
        model_fp16 = float16.convert_float_to_float16(model, keep_io_types=True)
        onnx.save(model_fp16, str(output_path))

        orig_mb = onnx_path.stat().st_size / 1e6
        fp16_mb = output_path.stat().st_size / 1e6
        logger.info(f"  {orig_mb:.1f} MB → {fp16_mb:.1f} MB")
    except Exception as e:
        logger.warning(f"FP16 conversion failed, copying original: {e}")
        import shutil
        shutil.copy2(onnx_path, output_path)


def process_quantization(raw_dir: Path, output_dir: Path):
    """Apply INT4 block-wise quantization to ALL sub-modules uniformly."""
    output_dir.mkdir(parents=True, exist_ok=True)

    for onnx_file in sorted(raw_dir.rglob("*.onnx")):
        output_path = output_dir / f"{onnx_file.stem}_int4.onnx"
        quantize_model_int4(onnx_file, output_path)

    # Copy non-ONNX files (tokenizer, configs)
    import shutil
    for f in raw_dir.rglob("*"):
        if f.is_file() and f.suffix != ".onnx":
            dest = output_dir / f.relative_to(raw_dir)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)

    logger.info(f"\nQuantized models saved to {output_dir}")
    total_size = sum(f.stat().st_size for f in output_dir.rglob("*") if f.is_file())
    logger.info(f"Total size: {total_size / 1e9:.2f} GB")


def main():
    parser = argparse.ArgumentParser(description="Export Qwen3-TTS to ONNX with INT4 quantization")
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID, help="PyTorch model ID")
    parser.add_argument("--onnx-repo", default=DEFAULT_ONNX_REPO, help="Community ONNX repo")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--from-pytorch", action="store_true", help="Force export from PyTorch")
    parser.add_argument("--skip-quantize", action="store_true")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)

    if args.from_pytorch:
        raw_dir = args.output_dir / "raw"
        raw_dir.mkdir(exist_ok=True)
        export_from_pytorch(args.model_id, raw_dir)
    else:
        # Prefer community ONNX export
        raw_dir = download_community_onnx(args.onnx_repo, args.output_dir)
        if raw_dir is None:
            logger.info("Falling back to PyTorch export...")
            raw_dir = args.output_dir / "raw"
            raw_dir.mkdir(exist_ok=True)
            export_from_pytorch(args.model_id, raw_dir)

    if not args.skip_quantize and raw_dir:
        process_quantization(raw_dir, args.output_dir / "quantized")

    # Save model manifest for Android app
    manifest = {
        "model_name": "Qwen3-TTS-12Hz-0.6B-Base",
        "version": "1.0.0",
        "quantization": "INT4",
        "submodules": list(TTS_SUBMODULES.keys()),
    }
    import json
    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    logger.info(f"Manifest saved to {manifest_path}")

    logger.info("TTS ONNX export complete!")


if __name__ == "__main__":
    main()
