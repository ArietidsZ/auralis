#!/usr/bin/env python3
"""Test original decode-with-empty-past as a unified FP32 talker session.

Isolated experiment: no production runner, graph, weight, or manifest changes.
The frozen sampling runner is reused through a narrow prefill session adapter.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import sys
import time

import numpy as np

CACHE = Path.home() / "Library/Caches/Auralis/tts"
POOL = CACHE / "upstream-fidelity/real-pool"
FROZEN = POOL / "protocol-fixed-sampled/tts_runner.py"
OUT = CACHE / "unified-talker"
MODEL = CACHE / "hf"


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4194304), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def runner():
    expected = json.loads((FROZEN.parent / "fp32/results.json").read_text())["runner_sha256"]
    if sha(FROZEN) != expected:
        raise RuntimeError("frozen runner changed")
    spec = importlib.util.spec_from_file_location("unified_frozen_runner", FROZEN)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module._import_heavy()
    if module.ort.__version__ != "1.24.2":
        raise RuntimeError("use the same ORT 1.24.2 overlay as the frozen baseline")
    return module


def session(tts, role):
    options = tts.ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    return tts.ort.InferenceSession(str(MODEL / f"{role}.onnx"), sess_options=options,
                                    providers=["CPUExecutionProvider"])


class CapturedInputs(Exception):
    pass


def prefill_inputs(tts, tables, config, tokenizer, embedding, text, language):
    """Capture frozen prompt construction before a graph executes; no fake output."""
    captured = {}

    class Capture:
        def run(self, names, inputs):
            captured.update({key: np.array(value, copy=True) for key, value in inputs.items()})
            raise CapturedInputs()

    bundle = type("Bundle", (), {"sessions": {"talker_prefill": Capture()}})()
    try:
        tts.generate_codes(bundle, tables, config, tts.build_prompt_ids(tokenizer, text), embedding,
                           language, top_k=50, seed=20260906, max_frames=384)
    except CapturedInputs:
        return captured
    raise RuntimeError("prompt capture did not reach the expected prefill boundary")


class PrefillViaDecode:
    """Expose prefill's outputs using the same real decode session used afterward."""
    def __init__(self, decode, config):
        self.decode = decode
        self.config = config
        self.seconds = None

    def run(self, names, inputs):
        if names is not None:
            raise ValueError("this experiment requests the full ordered output list")
        embedded = inputs["inputs_embeds"]
        count = embedded.shape[1]
        cfg = self.config.talker
        if embedded.shape != (1, count, cfg["hidden_size"]) or count < 1:
            raise ValueError("expected nonempty batch-one prompt")
        if inputs["position_ids"].shape != (3, 1, count) or inputs["attention_mask"].shape != (1, count):
            raise ValueError("prompt mask or position dimensions differ")
        past_k = np.zeros((cfg["num_hidden_layers"], 1, cfg["num_key_value_heads"], 0, cfg["head_dim"]), dtype=np.float32)
        past_v = past_k.copy()
        hidden_rows = []
        started = time.perf_counter()
        for index in range(count):
            output = self.decode.run(None, {
                "inputs_embeds": np.ascontiguousarray(embedded[:, index:index + 1]),
                "attention_mask": np.ascontiguousarray(inputs["attention_mask"][:, :index + 1]),
                "position_ids": np.ascontiguousarray(inputs["position_ids"][:, :, index:index + 1]),
                "past_keys": past_k,
                "past_values": past_v,
            })
            logits, hidden, past_k, past_v = output
            expected = (cfg["num_hidden_layers"], 1, cfg["num_key_value_heads"], index + 1, cfg["head_dim"])
            if past_k.shape != expected or past_v.shape != expected:
                raise RuntimeError("decode returned an unexpected cache length")
            hidden_rows.append(hidden)
        self.seconds = time.perf_counter() - started
        result = [logits, np.concatenate(hidden_rows, axis=1)]
        for layer in range(cfg["num_hidden_layers"]):
            result.extend([past_k[layer], past_v[layer]])
        return result


class PrefillViaDynamic:
    def __init__(self, decode, config):
        self.decode = decode
        cfg = config.talker
        self.empty = np.zeros((cfg["num_hidden_layers"], 1, cfg["num_key_value_heads"], 0, cfg["head_dim"]), dtype=np.float32)

    def run(self, names, inputs):
        if names is not None:
            raise ValueError("full ordered outputs required")
        return prefill_format(self.decode.run(None, dict(**inputs, past_keys=self.empty, past_values=self.empty)))


def compare(left, right):
    if left.shape != right.shape:
        return dict(shape_left=list(left.shape), shape_right=list(right.shape), shape_equal=False, close=False)
    a, b = left.astype(np.float64).ravel(), right.astype(np.float64).ravel()
    finite = bool(np.isfinite(a).all() and np.isfinite(b).all())
    difference = a - b
    denominator = np.linalg.norm(a) * np.linalg.norm(b)
    return dict(shape=list(left.shape), shape_equal=True, finite=finite,
                max_abs=float(np.max(np.abs(difference))), rms=float(np.sqrt(np.mean(difference ** 2))),
                cosine=float(np.dot(a, b) / denominator) if denominator else None,
                close=finite and bool(np.allclose(left, right, rtol=1e-4, atol=1e-4)))


def prefill_format(outputs):
    logits, hidden, keys, values = outputs
    result = [logits, hidden]
    for layer in range(keys.shape[0]):
        result.extend([keys[layer], values[layer]])
    return result


def make_api2(args):
    """Expose the existing last-row Slice and preserve all weight bytes."""
    import onnx
    from onnx import numpy_helper
    source, target = OUT / "talker_unified.onnx", OUT / "talker_api2.onnx"
    source_data, target_data = source.with_suffix(".onnx.data"), target.with_suffix(".onnx.data")
    if target.exists() or target_data.exists():
        raise RuntimeError("refuse to overwrite an API2 artifact")
    manifest = json.loads((OUT / "dynamic-export.json").read_text())
    if sha(source) != manifest["files"][0]["sha256"] or sha(source_data) != manifest["files"][1]["sha256"]:
        raise RuntimeError("original dynamic graph or data changed")
    graph = onnx.load(str(source), load_external_data=False)
    embedded = next(value for value in graph.graph.input if value.name == "inputs_embeds")
    dimensions = embedded.type.tensor_type.shape.dim
    if dimensions[0].dim_value != 1 or dimensions[2].dim_value != 1024:
        raise RuntimeError("last-output proof requires fixed batch one and hidden size 1024")
    producers = {name: node for node in graph.graph.node for name in node.output}
    head = producers["logits"]
    if head.op_type != "MatMul":
        raise RuntimeError("unexpected logits producer")
    sliced_name = head.input[0]
    sliced = producers[sliced_name]
    if sliced.op_type != "Slice" or sliced.input[0] != "hidden_states" or len(sliced.input) != 5:
        raise RuntimeError("head is not fed by the expected last-hidden Slice")
    constants = []
    for name in sliced.input[1:]:
        node = producers[name]
        if node.op_type != "Constant":
            raise RuntimeError("Slice control is not a constant")
        constants.append(numpy_helper.to_array(next(attribute.t for attribute in node.attribute if attribute.type == onnx.AttributeProto.TENSOR)).tolist())
    if constants != [[-1], [9223372036854775807], [1], [1]]:
        raise RuntimeError("Slice does not select exactly the final sequence row")
    weights = next(tensor for tensor in graph.graph.initializer if tensor.name == head.input[1])
    if list(weights.dims) != [1024, 3072] or weights.data_type != onnx.TensorProto.FLOAT:
        raise RuntimeError("unexpected FP32 codec head")
    for node in graph.graph.node:
        for index, name in enumerate(node.input):
            if name == sliced_name:
                node.input[index] = "last_hidden_state"
        for index, name in enumerate(node.output):
            if name == sliced_name:
                node.output[index] = "last_hidden_state"
    for value in graph.graph.value_info:
        if value.name == sliced_name:
            value.name = "last_hidden_state"
    present = list(graph.graph.output)[2:]
    del graph.graph.output[:]
    graph.graph.output.extend([
        onnx.helper.make_tensor_value_info("logits", onnx.TensorProto.FLOAT, [1, 1, 3072]),
        onnx.helper.make_tensor_value_info("last_hidden_state", onnx.TensorProto.FLOAT, [1, 1, 1024]),
        *present,
    ])
    for tensor in graph.graph.initializer:
        for entry in tensor.external_data:
            if entry.key == "location":
                if entry.value != source_data.name:
                    raise RuntimeError("unexpected external initializer file")
                entry.value = target_data.name
    os.link(source_data, target_data)
    onnx.save_model(graph, str(target))
    onnx.checker.check_model(str(target))
    proof = dict(source_graph_sha256=sha(source), script_sha256=sha(__file__),
                 transformation="Rename existing head-input Slice output; remove full-history hidden public output; relocate identical external data",
                 slice=dict(starts=constants[0], ends=constants[1], axes=constants[2], steps=constants[3]),
                 head_weight_shape=list(weights.dims), constraints="batch=1; sequence_length>=1; past_length>=0; mask_length=sequence_length+past_length",
                 interface=dict(role="talker", outputs={"logits": [1, 1, 3072], "last_hidden_state": [1, 1, 1024],
                                 "present_keys": [28, 1, 8, "total_length", 128], "present_values": [28, 1, 8, "total_length", 128]}),
                 files=[dict(path=str(path), bytes=path.stat().st_size, sha256=sha(path)) for path in (target, target_data)])
    write_json(OUT / "api2-proof.json", proof)
    def signature(values):
        return {value.name: dict(dtype={onnx.TensorProto.FLOAT: "float32", onnx.TensorProto.INT64: "int64"}[value.type.tensor_type.elem_type],
                                shape=[dimension.dim_param or dimension.dim_value for dimension in value.type.tensor_type.shape.dim]) for value in values}
    write_json(OUT / "api2-interface.json", dict(role="talker", api_version=2,
        status="isolated experimental candidate; validation reports are separate",
        inputs=signature(graph.graph.input), outputs=signature(graph.graph.output),
        constraints=proof["constraints"], files=proof["files"],
        numerical_evidence="api2-validation.json", synthesis_evidence="api2/results.json"))
    print(json.dumps(proof, indent=2), flush=True)


def export_dynamic(args):
    import gc
    import onnx
    import torch
    from torch import nn
    from qwen_tts import Qwen3TTSModel
    torch.set_num_threads(2)
    if (OUT / "talker_unified.onnx").exists() or (OUT / "talker_unified.onnx.data").exists():
        raise RuntimeError("refuse to overwrite the existing exported candidate")
    hf = Path.home() / ".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc"

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

    class UnifiedTalker(nn.Module):
        def __init__(self, talker):
            super().__init__()
            self.layers = talker.model.layers
            self.norm = talker.model.norm
            self.rotary = talker.model.rotary_emb
            self.head = talker.codec_head

        def forward(self, inputs_embeds, attention_mask, position_ids, past_keys, past_values):
            # Shape-derived ranges remain graph operations: no Python seq/past
            # conversion or input-shape-only patching is used.
            query = torch.arange(inputs_embeds.shape[1], device=inputs_embeds.device) + past_keys.shape[3]
            key = torch.arange(past_keys.shape[3] + inputs_embeds.shape[1], device=inputs_embeds.device)
            allowed = (key[None, :] <= query[:, None])[None, None] & attention_mask[:, None, None, :].bool()
            mask = torch.where(allowed, inputs_embeds.new_zeros(()), inputs_embeds.new_full((), torch.finfo(torch.float32).min))
            cache = TensorCache(past_keys, past_values)
            hidden = inputs_embeds
            positions = self.rotary(hidden, position_ids)
            for layer in self.layers:
                hidden = layer(hidden, attention_mask=mask, position_ids=position_ids[0],
                               past_key_values=cache, use_cache=True, cache_position=query,
                               position_embeddings=positions)[0]
            hidden = self.norm(hidden)
            return self.head(hidden[:, -1:]), hidden, torch.stack(cache.present_keys), torch.stack(cache.present_values)

    model = Qwen3TTSModel.from_pretrained(str(hf), dtype=torch.float32, attn_implementation="eager", local_files_only=True)
    wrapper = UnifiedTalker(model.model.talker).eval().requires_grad_(False)
    del model
    gc.collect()
    tts = runner()
    original = session(tts, "talker_prefill")
    with np.load(OUT / "xvector-english-inputs.npz") as data:
        prompt = {key: np.asarray(data[key]) for key in ("inputs_embeds", "attention_mask", "position_ids")}
    empty = np.zeros((28, 1, 8, 0, 128), dtype=np.float32)
    with torch.inference_mode():
        expected = original.run(None, prompt)
        actual = wrapper(*(torch.from_numpy(value) for value in [*prompt.values(), empty, empty]))
        actual = prefill_format([value.numpy() for value in actual])
        comparisons = [compare(left, right) for left, right in zip(expected, actual)]
        evidence = dict(source_hf_revision=hf.name, source_weights_sha256=sha(hf / "model.safetensors"),
                        script_sha256=sha(__file__), torch=torch.__version__, onnx=onnx.__version__,
                        wrapper_vs_original=comparisons, all_parameters_fp32=all(parameter.dtype == torch.float32 for parameter in wrapper.parameters()))
        write_json(OUT / "dynamic-export.json", evidence)
        if not all(row["close"] for row in comparisons):
            raise RuntimeError("official-module wrapper differs from the original prefill graph")
        del original, expected, actual
        gc.collect()
        # Trace a nontrivial seq=4,past=3 case; validate distinct seq lengths
        # and zero past against the real exported graph afterward.
        tensor = torch.from_numpy(prompt["inputs_embeds"][:, :4])
        arguments = (tensor, torch.ones(1, 7, dtype=torch.int64),
                     torch.arange(3, 7, dtype=torch.int64)[None, None].expand(3, 1, -1),
                     torch.zeros(28, 1, 8, 3, 128), torch.zeros(28, 1, 8, 3, 128))
        target = OUT / "talker_unified.onnx"
        torch.onnx.export(wrapper, arguments, str(target), dynamo=False, opset_version=18,
            input_names=["inputs_embeds", "attention_mask", "position_ids", "past_keys", "past_values"],
            output_names=["logits", "hidden_states", "present_keys", "present_values"],
            dynamic_axes={"inputs_embeds": {1: "sequence_length"}, "attention_mask": {1: "total_length"},
                          "position_ids": {2: "sequence_length"}, "past_keys": {3: "past_length"},
                          "past_values": {3: "past_length"}, "hidden_states": {1: "sequence_length"},
                          "present_keys": {3: "total_length"}, "present_values": {3: "total_length"}},
            do_constant_folding=True)
    del wrapper
    gc.collect()
    graph = onnx.load(str(target))
    data = target.with_suffix(".onnx.data")
    if data.exists():
        raise RuntimeError("refuse to overwrite an existing unified data artifact")
    onnx.save_model(graph, str(target), save_as_external_data=True, all_tensors_to_one_file=True,
                    location=data.name, size_threshold=1024)
    evidence["files"] = [dict(path=str(path), bytes=path.stat().st_size, sha256=sha(path)) for path in (target, data)]
    write_json(OUT / "dynamic-export.json", evidence)
    print("Exported true dynamic-sequence, dynamic-past FP32 graph", flush=True)


def validate_dynamic(args):
    tts = runner()
    original = session(tts, "talker_prefill")
    decoder = session(tts, "talker_decode")
    options = tts.ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    target = OUT / ("talker_api2.onnx" if args.api2 else "talker_unified.onnx")
    unified = tts.ort.InferenceSession(str(target), sess_options=options, providers=["CPUExecutionProvider"])
    empty = np.zeros((28, 1, 8, 0, 128), dtype=np.float32)
    report = dict(runtime=tts.ort.__version__, graph_sha256=sha(target), api2=args.api2, cases=[])
    names = [node.name for node in original.get_outputs()]
    paths = sorted(OUT.glob("*-inputs.npz"))
    for path in paths:
        with np.load(path) as data:
            inputs = {key: np.asarray(data[key]) for key in ("inputs_embeds", "attention_mask", "position_ids")}
        started = time.perf_counter()
        expected = original.run(None, inputs)
        baseline_s = time.perf_counter() - started
        started = time.perf_counter()
        raw = unified.run(None, dict(**inputs, past_keys=empty, past_values=empty))
        candidate_s = time.perf_counter() - started
        actual = prefill_format(raw)
        expected_surface = list(expected)
        output_names = list(names)
        if args.api2:
            expected_surface[1] = expected[1][:, -1:]
            output_names[1] = "last_hidden_state"
            if raw[0].shape != (1, 1, 3072) or raw[1].shape != (1, 1, 1024):
                raise RuntimeError("API2 fixed output shape violated")
        comparisons = {key: compare(left, right) for key, left, right in zip(output_names, expected_surface, actual)}
        last_hidden = compare(expected[1][:, -1:], actual[1][:, -1:])
        next_inputs = dict(inputs_embeds=np.ascontiguousarray(inputs["inputs_embeds"][:, -1:]),
                           attention_mask=np.concatenate([inputs["attention_mask"], np.ones((1, 1), dtype=np.int64)], axis=1),
                           position_ids=np.ascontiguousarray(inputs["position_ids"][:, :, -1:] + 1))
        expected_next = decoder.run(None, dict(**next_inputs, past_keys=np.stack(expected[2::2]), past_values=np.stack(expected[3::2])))
        actual_next = unified.run(None, dict(**next_inputs, past_keys=raw[2], past_values=raw[3]))
        next_comparisons = {node.name: compare(left, right) for node, left, right in zip(decoder.get_outputs(), expected_next, actual_next)}
        row = dict(id=path.stem, rows=inputs["inputs_embeds"].shape[1], prefill=comparisons, continuation=next_comparisons,
                   status="pass" if all(value["close"] for value in [*comparisons.values(), *next_comparisons.values()]) else "numerical_mismatch",
                   last_hidden=last_hidden,
                   consumed_outputs_pass=last_hidden["close"] and all(value["close"] for key, value in comparisons.items() if key != "hidden_states") and all(value["close"] for value in next_comparisons.values()),
                   original_seconds_diagnostic=baseline_s, unified_seconds_diagnostic=candidate_s, load_average=os.getloadavg())
        report["cases"].append(row)
        write_json(OUT / ("api2-validation.json" if args.api2 else "dynamic-validation.json"), report)
        print(row["id"], row["rows"], row["status"], baseline_s, candidate_s, flush=True)


def probe(args):
    tts = runner()
    OUT.mkdir(parents=True, exist_ok=True)
    config, tables, tokenizer = tts.load_config(MODEL), tts.EmbeddingTables(MODEL), tts.load_tokenizer(MODEL)
    speaker = session(tts, "speaker_encoder")
    embedding = tts.extract_speaker_embedding(type("Bundle", (), {"sessions": {"speaker_encoder": speaker}})(), POOL / "121-reference24.wav")
    loaded = {}
    for role in ("talker_prefill", "talker_decode"):
        started = time.perf_counter()
        loaded[role] = session(tts, role)
        print(role, "loaded in", time.perf_counter() - started, "seconds", flush=True)
    decode = loaded["talker_decode"]
    report = dict(runner_sha256=sha(FROZEN), script_sha256=sha(__file__), runtime=tts.ort.__version__,
                  tolerance=dict(rtol=1e-4, atol=1e-4), graph_inputs={role: [dict(name=value.name, shape=value.shape, type=value.type) for value in graph.get_inputs()] for role, graph in loaded.items()}, cases=[])
    prompts = {}
    texts = json.loads((POOL / "predeclared-criteria.json").read_text())["synthesis_texts"]
    for language, text in texts.items():
        prompts[f"xvector-{language}"] = prefill_inputs(tts, tables, config, tokenizer, embedding, text, language)
    for path in args.inputs:
        with np.load(path) as data:
            prompts[path.stem] = {key: np.asarray(data[key]) for key in ("inputs_embeds", "attention_mask", "position_ids")}
        # Official PyTorch get_rope_index may return integral float positions;
        # these fixed ONNX graphs require int64. Preserve every numeric value.
        for key in ("attention_mask", "position_ids"):
            value = prompts[path.stem][key]
            integer = value.astype(np.int64)
            if not np.isfinite(value).all() or not np.array_equal(value, integer):
                raise ValueError("nonintegral mask or position in official input")
            prompts[path.stem][key] = integer
    names = [output.name for output in loaded["talker_prefill"].get_outputs()]
    for name, inputs in prompts.items():
        np.savez(OUT / f"{name}-inputs.npz", **inputs)
        adapter = PrefillViaDecode(decode, config)
        row = dict(id=name, rows=inputs["inputs_embeds"].shape[1], load_average=os.getloadavg())
        try:
            started = time.perf_counter()
            baseline = loaded["talker_prefill"].run(None, inputs)
            row["baseline_seconds_diagnostic"] = time.perf_counter() - started
            unified = adapter.run(None, inputs)
            comparisons = {key: compare(left, right) for key, left, right in zip(names, baseline, unified)}
            row.update(status="pass" if len(baseline) == len(unified) and all(value["close"] for value in comparisons.values()) else "numerical_mismatch",
                       unified_seconds_diagnostic=adapter.seconds, outputs=comparisons,
                       last_hidden=compare(baseline[1][:, -1:], unified[1][:, -1:]))
        except Exception as exc:
            row.update(status="failed", error=f"{type(exc).__name__}: {exc}")
        report["cases"].append(row)
        write_json(OUT / "probe.json", report)
        print(name, row["status"], "rows", row["rows"], "baseline_s", row.get("baseline_seconds_diagnostic"), "unified_s", row.get("unified_seconds_diagnostic"), flush=True)


def synth(args):
    import soundfile as sf
    tts = runner()
    probe_result = json.loads((OUT / "probe.json").read_text())
    if not all(case["status"] == "pass" for case in probe_result["cases"] if case["id"].startswith("xvector-")):
        raise RuntimeError("short-prefix numerical gate failed")
    config, tables, tokenizer = tts.load_config(MODEL), tts.EmbeddingTables(MODEL), tts.load_tokenizer(MODEL)
    sessions = {role: session(tts, role) for role in ("speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder")}
    baseline_bundle = type("Bundle", (), {"sessions": sessions})()
    embedding = tts.extract_speaker_embedding(baseline_bundle, POOL / "121-reference24.wav")
    unified_sessions = dict(sessions)
    destination = OUT
    if args.dynamic or args.api2:
        validation = json.loads((OUT / ("api2-validation.json" if args.api2 else "dynamic-validation.json")).read_text())
        if len(validation["cases"]) != 6 or not all(case["consumed_outputs_pass"] for case in validation["cases"]):
            raise RuntimeError("dynamic graph numerical gate failed or incomplete")
        options = tts.ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        dynamic = tts.ort.InferenceSession(str(OUT / ("talker_api2.onnx" if args.api2 else "talker_unified.onnx")), sess_options=options, providers=["CPUExecutionProvider"])
        unified_sessions["talker_decode"] = dynamic
        unified_sessions["talker_prefill"] = PrefillViaDynamic(dynamic, config)
        destination = OUT / ("api2" if args.api2 else "dynamic")
        destination.mkdir(exist_ok=True)
    else:
        unified_sessions["talker_prefill"] = PrefillViaDecode(sessions["talker_decode"], config)
    unified_bundle = type("Bundle", (), {"sessions": unified_sessions})()
    results = dict(implementation="api2_last_hidden" if args.api2 else "dynamic_sequence" if args.dynamic else "tokenwise_decode", runtime=tts.ort.__version__, runner_sha256=sha(FROZEN), sampling=dict(temperature=.9, top_k=50, rep_penalty=1.05, seed=20260906, max_frames=384), cases=[], pairs=[])
    texts = json.loads((POOL / "predeclared-criteria.json").read_text())["synthesis_texts"]
    for language, text in texts.items():
        generated = {}
        waves = {}
        for mode, bundle in (("baseline", baseline_bundle), ("unified", unified_bundle)):
            cid = f"121-{language}-{mode}"
            started = time.perf_counter()
            row = dict(id=cid, voice="121", language=language, text=text, repeat=0)
            try:
                generated[mode] = tts.generate_codes(bundle, tables, config, tts.build_prompt_ids(tokenizer, text), embedding, language, **results["sampling"])
                waves[mode] = sessions["vocoder"].run(None, {"codes": generated[mode].codes[None]})[0].reshape(-1)
                wave = waves[mode]
                if not np.isfinite(wave).all():
                    raise RuntimeError("vocoder returned non-finite PCM")
                path = destination / f"{cid}.wav"
                sf.write(path, np.clip(wave, -1, 1), 24000)
                np.save(destination / f"{cid}-codes.npy", generated[mode].codes)
                row.update(status="success", audio_path=str(path), frames=generated[mode].frames, eos=generated[mode].frames < 384, group0_tokens=generated[mode].group0_tokens, finite=True,
                           clipping_ratio=float(np.mean(np.abs(wave) >= .999)), prefill_seconds_diagnostic=generated[mode].prefill_s)
            except Exception as exc:
                row.update(status="synthesis_failed", error=f"{type(exc).__name__}: {exc}")
            row.update(seconds_diagnostic=time.perf_counter() - started, maxrss_bytes_diagnostic=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss, load_average=os.getloadavg())
            results["cases"].append(row)
            write_json(destination / "results.json", results)
            print(cid, row["status"], row.get("frames"), row.get("error"), flush=True)
        if set(generated) == set(waves) == {"baseline", "unified"}:
            results["pairs"].append(dict(language=language,
                group0_equal=generated["baseline"].group0_tokens == generated["unified"].group0_tokens,
                all_codes_equal=bool(np.array_equal(generated["baseline"].codes, generated["unified"].codes)),
                wave=compare(waves["baseline"], waves["unified"])))
            write_json(destination / "results.json", results)


def perf(args):
    """Fresh-process talker-only memory/load, then cached-prefix execution.

    Run only in a coordinated quiet window. Filesystem cache is uncontrolled;
    these are process-cold measurements, not device or storage-cold SLOs.
    """
    if args.variant not in ("baseline", "dynamic"):
        raise ValueError("retained-session variant must be baseline or dynamic")
    quality = json.loads((OUT / "dynamic/results.json").read_text())
    if len(quality["pairs"]) != 2 or not all(pair["all_codes_equal"] and pair["wave"]["max_abs"] == 0 for pair in quality["pairs"]):
        raise RuntimeError("bilingual exact-code/wave gate not satisfied")
    target = OUT / "perf" / f"{args.variant}-{args.label}.json"
    target.parent.mkdir(exist_ok=True)
    if target.exists():
        raise RuntimeError("refuse to overwrite an existing performance run")
    tts = runner()
    started = time.perf_counter()
    if args.variant == "baseline":
        prefill = session(tts, "talker_prefill")
        decode = session(tts, "talker_decode")
    else:
        options = tts.ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        decode = tts.ort.InferenceSession(str(OUT / "talker_unified.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        prefill = PrefillViaDynamic(decode, tts.load_config(MODEL))
    report = dict(variant=args.variant, label=args.label, runtime=tts.ort.__version__,
                  load_seconds=time.perf_counter() - started,
                  maxrss_after_load_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                  load_average=os.getloadavg(), trials=[],
                  scope="talker-only fresh process; cached filesystem; no device SLO claim")
    files = ["xvector-english-inputs.npz", "prefill-260-english-inputs.npz", "prefill-121-english-inputs.npz"]
    for file in files:
        with np.load(OUT / file) as data:
            feed = {key: np.asarray(data[key]) for key in ("inputs_embeds", "attention_mask", "position_ids")}
        for repeat in range(3):
            started = time.perf_counter()
            outputs = prefill.run(None, feed)
            prefill_s = time.perf_counter() - started
            next_feed = dict(inputs_embeds=np.ascontiguousarray(feed["inputs_embeds"][:, -1:]),
                             attention_mask=np.ones((1, feed["inputs_embeds"].shape[1] + 1), dtype=np.int64),
                             position_ids=np.ascontiguousarray(feed["position_ids"][:, :, -1:] + 1),
                             past_keys=np.stack(outputs[2::2]), past_values=np.stack(outputs[3::2]))
            started = time.perf_counter()
            result = decode.run(None, next_feed)
            decode_s = time.perf_counter() - started
            if not all(np.isfinite(value).all() for value in result):
                raise RuntimeError("non-finite performance trial")
            report["trials"].append(dict(rows=feed["inputs_embeds"].shape[1], repeat=repeat,
                                          prefill_seconds=prefill_s, next_decode_seconds=decode_s))
    report["maxrss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    write_json(target, report)
    print(json.dumps(report, indent=2), flush=True)


def perf_lifecycle(args):
    """Current per-turn release policy versus one retained unified session."""
    import gc
    import subprocess
    if args.variant not in ("serial", "dynamic"):
        raise ValueError("lifecycle variant must be serial or dynamic")
    quality = json.loads((OUT / "dynamic/results.json").read_text())
    if len(quality["pairs"]) != 2 or not all(pair["all_codes_equal"] and pair["wave"]["max_abs"] == 0 for pair in quality["pairs"]):
        raise RuntimeError("exact-code/wave quality gate not satisfied")
    target = OUT / "perf" / f"lifecycle-{args.variant}-{args.label}.json"
    if target.exists():
        raise RuntimeError("refuse to overwrite a lifecycle run")
    tts = runner()

    def rss():
        return int(subprocess.check_output(["/bin/ps", "-o", "rss=", "-p", str(os.getpid())], text=True).strip()) * 1024

    report = dict(variant=args.variant, runtime=tts.ort.__version__, load_average=os.getloadavg(),
                  scope="talker-only; 3 rounds x 10/98/116 rows; same-process session lifecycle; cached filesystem",
                  initial_rss_bytes=rss(), initial_shared_load_seconds=0.0, trials=[])
    experiment_start = time.perf_counter()
    if args.variant == "dynamic":
        started = time.perf_counter()
        options = tts.ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        shared = tts.ort.InferenceSession(str(OUT / "talker_unified.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        shared_prefill = PrefillViaDynamic(shared, tts.load_config(MODEL))
        report["initial_shared_load_seconds"] = time.perf_counter() - started
    report["ready_rss_bytes"] = rss()
    files = ["xvector-english-inputs.npz", "prefill-260-english-inputs.npz", "prefill-121-english-inputs.npz"]
    for round_index in range(3):
        for file in files:
            with np.load(OUT / file) as data:
                feed = {key: np.asarray(data[key]) for key in ("inputs_embeds", "attention_mask", "position_ids")}
            started_turn = time.perf_counter()
            trial = dict(round=round_index, rows=feed["inputs_embeds"].shape[1],
                         prefill_create_seconds=0.0, prefill_release_seconds=0.0,
                         decode_create_seconds=0.0, decode_release_seconds=0.0)
            if args.variant == "serial":
                started = time.perf_counter()
                prefill = session(tts, "talker_prefill")
                trial["prefill_create_seconds"] = time.perf_counter() - started
            else:
                prefill = shared_prefill
            started = time.perf_counter()
            outputs = prefill.run(None, feed)
            keys, values = np.stack(outputs[2::2]), np.stack(outputs[3::2])
            # Retain only copied cache tensors across the release boundary,
            # matching the production Float-array ownership contract.
            del outputs
            trial["prefill_compute_copy_seconds"] = time.perf_counter() - started
            if args.variant == "serial":
                started = time.perf_counter()
                del prefill
                gc.collect()
                trial["prefill_release_seconds"] = time.perf_counter() - started
                trial["after_prefill_release_rss_bytes"] = rss()
                started = time.perf_counter()
                decode = session(tts, "talker_decode")
                trial["decode_create_seconds"] = time.perf_counter() - started
            else:
                decode = shared
            next_feed = dict(inputs_embeds=np.ascontiguousarray(feed["inputs_embeds"][:, -1:]),
                             attention_mask=np.ones((1, feed["inputs_embeds"].shape[1] + 1), dtype=np.int64),
                             position_ids=np.ascontiguousarray(feed["position_ids"][:, :, -1:] + 1),
                             past_keys=keys, past_values=values)
            started = time.perf_counter()
            decoded = decode.run(None, next_feed)
            trial["decode_compute_seconds"] = time.perf_counter() - started
            if not all(np.isfinite(value).all() for value in decoded):
                raise RuntimeError("non-finite lifecycle output")
            del decoded, next_feed, keys, values
            if args.variant == "serial":
                started = time.perf_counter()
                del decode
                gc.collect()
                trial["decode_release_seconds"] = time.perf_counter() - started
            trial["turn_rss_bytes"] = rss()
            trial["turn_wall_seconds"] = time.perf_counter() - started_turn
            trial["accounted_stage_seconds"] = sum(value for key, value in trial.items() if key.endswith("_seconds") and key != "turn_wall_seconds")
            report["trials"].append(trial)
            write_json(target, report)
            print(args.variant, round_index, trial["rows"], trial["turn_wall_seconds"], flush=True)
    report["experiment_wall_seconds"] = time.perf_counter() - experiment_start
    report["peak_rss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    report["retained_rss_bytes"] = rss()
    if args.variant == "dynamic":
        # Drop every alias, including the adapter, before measuring release.
        del prefill, decode, shared_prefill, shared
        gc.collect()
    report["final_released_rss_bytes"] = rss()
    write_json(target, report)


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["probe", "synth", "export-dynamic", "validate-dynamic", "make-api2", "perf", "perf-lifecycle"])
    parser.add_argument("--inputs", type=Path, action="append", default=[], help="additional real prefill input NPZ")
    parser.add_argument("--dynamic", action="store_true", help="use the exported dynamic-sequence graph in synthesis")
    parser.add_argument("--api2", action="store_true", help="validate or synthesize with the final last-hidden-only API2 graph")
    parser.add_argument("--variant", choices=["baseline", "serial", "dynamic"], default="baseline")
    parser.add_argument("--label", default="run")
    parser.add_argument("--output-dir", type=Path, default=OUT, help="use a fresh directory to rebuild without replacing frozen artifacts")
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    if args.api2 and args.mode not in ("synth", "validate-dynamic"):
        parser.error("--api2 applies only to numerical validation and synthesis; no API2 performance run is defined")
    if not args.label.replace("-", "").replace("_", "").isalnum():
        parser.error("--label must contain only letters, digits, underscores or hyphens")
    snapshots = OUT / "script-snapshots"
    snapshots.mkdir(parents=True, exist_ok=True)
    (snapshots / f"{sha(__file__)}.py").write_bytes(Path(__file__).read_bytes())
    {"probe": probe, "synth": synth, "export-dynamic": export_dynamic, "validate-dynamic": validate_dynamic, "make-api2": make_api2, "perf": perf, "perf-lifecycle": perf_lifecycle}[args.mode](args)


if __name__ == "__main__":
    main()
