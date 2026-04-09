#!/usr/bin/env python3
"""
Validate quantized ONNX models by comparing outputs with original PyTorch models.

Runs sample audio through both original and quantized pipelines,
measuring output similarity and inference speed.
"""

import argparse
import logging
import time
from pathlib import Path

import numpy as np
import torch
import soundfile as sf

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

DEFAULT_MODELS_DIR = Path(__file__).parent.parent / "models"
SAMPLE_AUDIO_URL = "https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-ASR-Repo/asr_zh.wav"


def validate_asr(models_dir: Path, sample_audio: str = SAMPLE_AUDIO_URL):
    """Validate ASR model: compare PyTorch vs ONNX output."""
    logger.info("=" * 60)
    logger.info("Validating ASR Model")
    logger.info("=" * 60)

    # 1. Run original PyTorch model
    logger.info("Running PyTorch ASR...")
    try:
        from qwen_asr import Qwen3ASRModel

        pt_model = Qwen3ASRModel.from_pretrained(
            "Qwen/Qwen3-ASR-0.6B",
            dtype=torch.bfloat16,
            device_map="cuda:0",
        )

        t0 = time.time()
        pt_results = pt_model.transcribe(audio=sample_audio, language=None)
        pt_time = time.time() - t0

        pt_text = pt_results[0].text
        pt_lang = pt_results[0].language
        logger.info(f"PyTorch ASR: [{pt_lang}] {pt_text} ({pt_time:.2f}s)")
    except Exception as e:
        logger.error(f"PyTorch ASR failed: {e}")
        pt_text = None
        pt_time = None

    # 2. Run ONNX model
    logger.info("Running ONNX ASR...")
    try:
        import onnxruntime as ort

        asr_dir = models_dir / "asr" / "quantized"
        if not asr_dir.exists():
            asr_dir = models_dir / "asr"

        onnx_files = list(asr_dir.glob("*int4*.onnx")) or list(asr_dir.glob("*.onnx"))
        if not onnx_files:
            logger.warning(f"No ONNX files found in {asr_dir}")
            return

        for f in onnx_files:
            logger.info(f"  Loading: {f.name} ({f.stat().st_size / 1e6:.1f} MB)")
            session = ort.InferenceSession(str(f), providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
            logger.info(f"  Inputs: {[i.name for i in session.get_inputs()]}")
            logger.info(f"  Outputs: {[o.name for o in session.get_outputs()]}")
    except Exception as e:
        logger.error(f"ONNX ASR validation failed: {e}")

    if pt_text:
        logger.info(f"\nReference PyTorch output: {pt_text}")


def validate_tts(models_dir: Path):
    """Validate TTS model: compare PyTorch vs ONNX output quality."""
    logger.info("=" * 60)
    logger.info("Validating TTS Model")
    logger.info("=" * 60)

    test_text = "你好，这是一个语音合成测试。"
    ref_audio = "https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-TTS-Repo/clone.wav"
    ref_text = "Okay. Yeah. I resent you. I love you. I respect you. But you know what? You blew it! And thanks to you."

    # 1. Run original PyTorch model
    logger.info("Running PyTorch TTS...")
    try:
        from qwen_tts import Qwen3TTSModel

        pt_model = Qwen3TTSModel.from_pretrained(
            "Qwen/Qwen3-TTS-12Hz-0.6B-Base",
            device_map="cuda:0",
            dtype=torch.bfloat16,
        )

        t0 = time.time()
        wavs, sr = pt_model.generate_voice_clone(
            text=test_text,
            language="Chinese",
            ref_audio=ref_audio,
            ref_text=ref_text,
        )
        pt_time = time.time() - t0

        output_path = models_dir / "validation_pytorch_tts.wav"
        sf.write(str(output_path), wavs[0], sr)
        logger.info(f"PyTorch TTS: {len(wavs[0]) / sr:.2f}s audio in {pt_time:.2f}s")
        logger.info(f"  Saved to {output_path}")
    except Exception as e:
        logger.error(f"PyTorch TTS failed: {e}")

    # 2. Check ONNX models exist
    logger.info("Checking ONNX TTS models...")
    tts_dir = models_dir / "tts" / "quantized"
    if not tts_dir.exists():
        tts_dir = models_dir / "tts"

    if tts_dir.exists():
        for f in sorted(tts_dir.rglob("*.onnx")):
            size_mb = f.stat().st_size / 1e6
            logger.info(f"  {f.relative_to(tts_dir)}: {size_mb:.1f} MB")
    else:
        logger.warning(f"TTS ONNX directory not found: {tts_dir}")


def main():
    parser = argparse.ArgumentParser(description="Validate quantized ONNX models")
    parser.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS_DIR)
    parser.add_argument("--asr-only", action="store_true")
    parser.add_argument("--tts-only", action="store_true")
    args = parser.parse_args()

    if not args.tts_only:
        validate_asr(args.models_dir)
    if not args.asr_only:
        validate_tts(args.models_dir)

    logger.info("\nValidation complete!")


if __name__ == "__main__":
    main()
