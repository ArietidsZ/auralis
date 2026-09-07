#!/usr/bin/env python3
"""
Export Qwen3-ASR-0.6B to ONNX format with INT4 quantization.

This script:
1. Loads the Qwen3-ASR-0.6B PyTorch model
2. Exports encoder and decoder components to ONNX
3. Applies INT4 block-wise quantization
4. Saves optimized models for on-device inference

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

# Default paths
DEFAULT_MODEL_ID = "Qwen/Qwen3-ASR-0.6B"
DEFAULT_OUTPUT_DIR = Path(__file__).parent.parent / "models" / "asr"


def export_asr_encoder(model, output_dir: Path, opset_version: int = 17):
    """Export the audio encoder component to ONNX."""
    logger.info("Exporting ASR audio encoder to ONNX...")

    encoder = model.model.audio_encoder if hasattr(model.model, "audio_encoder") else None
    if encoder is None:
        logger.warning("Could not access audio encoder directly. Attempting full-model export.")
        return False

    encoder.eval()

    # Dummy input: (batch, channels, time_steps) - typical mel spectrogram
    dummy_audio = torch.randn(1, 80, 3000, device="cuda", dtype=torch.float32)

    output_path = output_dir / "asr_encoder.onnx"
    torch.onnx.export(
        encoder,
        (dummy_audio,),
        str(output_path),
        opset_version=opset_version,
        input_names=["audio_features"],
        output_names=["encoder_hidden_states"],
        dynamic_axes={
            "audio_features": {0: "batch", 2: "time"},
            "encoder_hidden_states": {0: "batch", 1: "sequence"},
        },
    )
    logger.info(f"Encoder exported to {output_path} ({output_path.stat().st_size / 1e6:.1f} MB)")
    return True


def export_asr_decoder(model, output_dir: Path, opset_version: int = 17):
    """Export the language model decoder component to ONNX."""
    logger.info("Exporting ASR decoder to ONNX...")

    decoder = model.model.language_model if hasattr(model.model, "language_model") else None
    if decoder is None:
        logger.warning("Could not access decoder directly. Attempting alternative access.")
        return False

    decoder.eval()

    # Dummy inputs for autoregressive decoding
    batch_size = 1
    seq_len = 128
    hidden_dim = decoder.config.hidden_size if hasattr(decoder, "config") else 896

    dummy_input_ids = torch.randint(0, 1000, (batch_size, seq_len), device="cuda")
    dummy_encoder_hidden = torch.randn(batch_size, 200, hidden_dim, device="cuda", dtype=torch.bfloat16)

    output_path = output_dir / "asr_decoder.onnx"
    with torch.no_grad():
        torch.onnx.export(
            decoder,
            (dummy_input_ids, dummy_encoder_hidden),
            str(output_path),
            opset_version=opset_version,
            input_names=["input_ids", "encoder_hidden_states"],
            output_names=["logits"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "seq_len"},
                "encoder_hidden_states": {0: "batch", 1: "encoder_seq"},
                "logits": {0: "batch", 1: "seq_len"},
            },
        )
    logger.info(f"Decoder exported to {output_path} ({output_path.stat().st_size / 1e6:.1f} MB)")
    return True


def quantize_model_int4(onnx_path: Path, output_path: Path):
    """Apply INT4 block-wise weight-only quantization to an ONNX model."""
    logger.info(f"Quantizing {onnx_path.name} to INT4...")

    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
        import onnx

        model = onnx.load(str(onnx_path))
        quantizer = MatMul4BitsQuantizer(
            model=model,
            block_size=32,
            is_symmetric=True,
            accuracy_level=4,  # Best accuracy
        )
        quantizer.process()
        quantizer.model.save_model_to_file(str(output_path))

        original_size = onnx_path.stat().st_size / 1e6
        quantized_size = output_path.stat().st_size / 1e6
        ratio = quantized_size / original_size * 100

        logger.info(
            f"Quantized: {original_size:.1f} MB → {quantized_size:.1f} MB "
            f"({ratio:.1f}% of original)"
        )
    except ImportError:
        logger.warning("MatMul4BitsQuantizer not available. Falling back to dynamic INT8.")
        quantize_dynamic(
            str(onnx_path),
            str(output_path),
            weight_type=QuantType.QInt8,
        )


def export_full_model_via_optimum(model_id: str, output_dir: Path):
    """
    Alternative export using Hugging Face Optimum library.
    This handles complex model architectures more robustly.
    """
    logger.info(f"Attempting export via Optimum for {model_id}...")

    try:
        from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
        from optimum.exporters.onnx import main_export

        main_export(
            model_name_or_path=model_id,
            output=str(output_dir / "optimum_export"),
            task="automatic-speech-recognition",
            device="cuda",
            fp16=True,
        )
        logger.info(f"Optimum export completed to {output_dir / 'optimum_export'}")
        return True
    except Exception as e:
        logger.error(f"Optimum export failed: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="Export Qwen3-ASR to ONNX with INT4 quantization")
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID, help="HuggingFace model ID")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR, help="Output directory")
    parser.add_argument("--skip-quantize", action="store_true", help="Skip INT4 quantization step")
    parser.add_argument("--use-optimum", action="store_true", help="Use Optimum for export")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    logger.info(f"Output directory: {args.output_dir}")

    if args.use_optimum:
        success = export_full_model_via_optimum(args.model_id, args.output_dir)
        if not success:
            logger.error("Optimum export failed. Try without --use-optimum flag.")
            sys.exit(1)
        return

    # Load the model
    logger.info(f"Loading model: {args.model_id}")
    from qwen_asr import Qwen3ASRModel

    model = Qwen3ASRModel.from_pretrained(
        args.model_id,
        dtype=torch.bfloat16,
        device_map="cuda:0",
    )

    # Export components
    encoder_ok = export_asr_encoder(model, args.output_dir)
    decoder_ok = export_asr_decoder(model, args.output_dir)

    if not (encoder_ok and decoder_ok):
        logger.warning("Component-level export failed. Trying Optimum fallback...")
        export_full_model_via_optimum(args.model_id, args.output_dir)

    # Quantize
    if not args.skip_quantize:
        for onnx_file in args.output_dir.glob("*.onnx"):
            if "int4" not in onnx_file.stem:
                quantized_path = onnx_file.with_name(f"{onnx_file.stem}_int4.onnx")
                quantize_model_int4(onnx_file, quantized_path)

    # Save tokenizer / preprocessor config
    logger.info("Saving tokenizer and preprocessor configs...")
    try:
        from transformers import AutoTokenizer, AutoFeatureExtractor

        tokenizer = AutoTokenizer.from_pretrained(args.model_id)
        tokenizer.save_pretrained(str(args.output_dir / "tokenizer"))

        feature_extractor = AutoFeatureExtractor.from_pretrained(args.model_id)
        feature_extractor.save_pretrained(str(args.output_dir / "feature_extractor"))
    except Exception as e:
        logger.warning(f"Could not save tokenizer/feature_extractor: {e}")

    logger.info("ASR ONNX export complete!")
    logger.info(f"Files in {args.output_dir}:")
    for f in sorted(args.output_dir.rglob("*")):
        if f.is_file():
            logger.info(f"  {f.relative_to(args.output_dir)} ({f.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
