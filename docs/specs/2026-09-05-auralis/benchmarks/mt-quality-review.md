# HY-MT quality ablation — 2026-09-07

The smallest supported change is to stop supplying context by default. Keep the official single-segment prompt and greedy decoding. The tested context template can translate the history instead of the current text on **both** deployed STQ and official Q4_K_M. Increasing precision or adding the recommended sampling parameters does not reliably fix that behavior. No core, application, manifest, or build files were changed by this experiment.

This is a fixed 14-case functional experiment on the development Mac under concurrent workloads. It is not a production acceptance set, a blinded translation benchmark, or a physical-device performance measurement. Cases were fixed before either candidate was run. All outputs, including failures, are retained. Manual findings are semantic judgments by one reviewer, not COMET/BLEU scores.

## Evidence and reproduction

- Driver: [mt_quality_ablation.py](mt_quality_ablation.py).
- Exact inputs and expected properties: the driver's `CASES`, also copied into every `results.json`.
- All outputs and per-case findings: [mt-quality-all-outputs.md](mt-quality-all-outputs.md).
- Official model metadata: [mt-quality-model-provenance.json](mt-quality-model-provenance.json).
- Pinned model card: [mt-official-model-card.md](mt-official-model-card.md).
- Six greedy CLI/native checks: [mt-quality-cli-parity.json](mt-quality-cli-parity.json), all exact matches, including STQ weather leakage and Q4 temperature replacement.
- Machine-readable manual findings: [mt-quality-semantic-review.json](mt-quality-semantic-review.json).

Run from the repository root:

```sh
python3 docs/specs/2026-09-05-auralis/benchmarks/mt_quality_ablation.py \
  --model /Users/arietids/Library/Caches/Auralis/mt/models/Hy-MT1.5-1.8B-1.25bit-stq43.gguf \
  --output docs/specs/2026-09-05-auralis/benchmarks/mt-quality-stq

python3 docs/specs/2026-09-05-auralis/benchmarks/mt_quality_ablation.py \
  --model /Users/arietids/Library/Caches/Auralis/mt/quality/HY-MT1.5-1.8B-Q4_K_M.gguf \
  --output docs/specs/2026-09-05-auralis/benchmarks/mt-quality-q4
```

For each model the additional controlled sampling runs use `--phase sampling --seed 123` and `--seed 2026`, with separate output directories `mt-quality-{stq,q4}-seed{123,2026}`. Literal model-card CLI parameter checks use `--phase sampling --sampling-policy card-cli`, seed 42, and directories `mt-quality-{stq,q4}-card-cli`. Every CLI argument vector, unedited stdout, and stderr file is saved. Native outputs are directly read from the same C ABI used by `convert/mt_runner.py`.

## Documentation and sampling controls

Context7 resolution returned Tencent's Hunyuan cloud-service documentation, and its query returned cloud APIs rather than the local HY-MT model. That source was not used to infer local prompts. The fallback was the [official HY-MT repository](https://github.com/Tencent-Hunyuan/Hy-MT) and [official GGUF card at the pinned revision](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/blob/265b2e615a7dc9b06c435dc878829ad99a512ba2/README.md).

The current native contextual prompt already matches the official contextual template on this corpus, including the instruction not to translate the preceding context. The Chinese prompt uses 中文/英语 as target names. No system message is supplied. The model's embedded Jinja chat template is used. Deleting context selects the official ordinary single-segment template, without inventing another prompt.

The card recommends temperature 0.7, top-k 20, top-p 0.6, and repetition penalty 1.05. Two sampling controls are distinguished:

- `sampling-*`: those four values, explicit `penalties;temperature;top_k;top_p`, min-p disabled, and a 2048-token repetition window. Seeds 42/123/2026. This makes the sampler order explicit; it is **not** a claim to reproduce the literal card CLI's implicit defaults.
- `card-cli-*`: exactly the four card sampling flags, retaining this pinned CLI's defaults for the remaining samplers, including min-p 0.05 and repetition window 64. Seed 42. This checks whether the explicit controlled sampler policy caused the main finding.

All inference uses four generation and batch threads, CPU execution (`-ngl 0`), a 2048-token context, a 256-token output limit, no warmup for CLI runs, and a single prompt at a time. Native uses its existing 256-token logical batch; CLI uses 512. Native greedy is argmax. The six same-prompt greedy CLI/native checks match exactly despite the configured batch-capacity difference. The CLI's displayed *example* chat template contains a sample system message; that banner is not the actual input conversation.

## Pinned artifacts

| Artifact | Revision / SHA256 | Bytes |
|---|---|---:|
| AngelSlim STQ source | HF `1bed36c0a8f5a0eddf77987b02ba66d4c268aca2`; supplied type-43 conversion SHA256 `e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971` | 461,860,704 |
| Official Tencent Q4_K_M | HF `265b2e615a7dc9b06c435dc878829ad99a512ba2`; downloaded SHA256 `4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7`, verified against HF LFS metadata | 1,133,080,512 |
| Runtime source | `1e411d8f5a1e23525fa3265dfb4bd76265465397` | — |
| `libhymt_core.dylib` | `4e1f56a710b121d3cde5cb9518dea294f973291b681d5ac9f1cca3755a9847a8` | — |
| `build-host/bin/llama-completion` | `0546a7ad69b42b34eb2b6e503da7545c836f967688b8794c8f2bd4014713e56c` | — |

Q4_K_M is in the persistent cache at `/Users/arietids/Library/Caches/Auralis/mt/quality/HY-MT1.5-1.8B-Q4_K_M.gguf`. It was downloaded directly from the fixed HF revision, without global network configuration changes. It costs 671,219,808 additional bytes, or 2.453 times the model storage. Resident memory, energy, and phone latency were not measured.

The [AngelSlim model card](https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF) identifies a fine-tuned 1.25-bit lineage over the base translation model. The comparison therefore changes deployed candidate weights and quantization, not just bit width on otherwise identical weights. It cannot isolate quantization as the sole cause of a quality difference.

## Findings

All 280 quality calls and six separate parity calls completed successfully. The table counts cases with clear semantic failures, excluding separately recorded subtler deadline/specificity findings; the denominator is 14 fixed cases. A zero does not establish production quality.

| Candidate | Greedy, no context | Greedy, context | Controlled sampling, no context (seeds 42/123/2026) | Controlled sampling, context (seeds 42/123/2026) | Literal card CLI, no context | Literal card CLI, context |
|---|---:|---:|---|---|---:|---:|
| STQ | 1/14 | 2/14 | 2/14, 1/14, 2/14 | 2/14, 2/14, 2/14 | 2/14 | 2/14 |
| Q4_K_M | 0/14 | 4/14 | 0/14, 0/14, 0/14 | 4/14, 4/14, 4/14 | 0/14 | 4/14 |

The STQ greedy contextual museum case reproduces the reported failure: `It’s nice weather today.` precedes the invitation. Removing context removes the extra assertion. The STQ contextual Japanese case with unknown source changes an ongoing event into `今天会下雨。`; without context it outputs `今天正在下雨。`.

Official Q4_K_M is not a context fix. Greedy contextual inference fails on four other cases: temperature is replaced entirely by the preceding wind-speed sentence; Japanese current rain is preceded by yesterday's snow; the Zurich train adds the cancelled Paris train; and package B-17 adds the opened package A-12. Controlled sampling retains those failure classes across all three seeds. Some outputs drop the requested translation altogether.

On STQ, controlled sampling removes the museum weather sentence but reverses a prohibition in all three seeds. `Do not open package B-17 until Monday.` becomes an instruction to open it before Monday. Seed 2026 also changes B-17 to A-12, the history's identifier. This is not an acceptable trade for fixing one extra sentence. No prompt was tuned to an individual failing case.

The literal card CLI check reaches the same decision: STQ reverses the B-17 prohibition both with and without context; Q4 still leaks history in four contextual cases. Thus the result does not depend on the controlled sampler order or repetition-window choice. The literal card arm was run at seed 42 only; its stochastic failure rate is not estimated.

Context has a real benefit in the ambiguity case: STQ without context translates `bank` as `长椅` (bench), while context produces `河岸`. Q4 produces the riverbank sense without context. This is evidence for a broader Q4 evaluation, not evidence that Q4 is uniformly superior. Both candidates still require a representative held-out corpus before a quality claim.

Subtler findings are retained separately from clear failures: `before Friday` often becomes `by Friday`, weakening the deadline boundary; Q4 sometimes turns an unspecified `It` into `会议` without supplied context. Resolving `She` to Dr. Chen *with* the provided sister context is accepted as contextual reference resolution. Without context, `bank` can validly mean a financial institution or riverbank, but cannot normally mean a bench.

## Occam selection

1. **Delete default context forwarding.** Select the existing single-segment translation path. This removes the observed source of cross-turn leakage without adding classifiers, post-processing, retries, prompt tags, or language-specific heuristics. The cost is losing useful disambiguation such as the bank example. Context should remain disabled until it passes a separate held-out acceptance set; do not silently infer that official prompt wording guarantees compliance.
2. **Keep greedy as the current decoding candidate.** Neither the recommended parameter values nor the controlled seed sweep establish a quality advantage sufficient to justify introducing stochastic output. The prohibition inversion is a stronger failure than the one museum leakage removed by sampling.
3. **Do not replace STQ with Q4 solely to fix context.** Q4 gives a useful no-context lexical improvement on this set and is available as a pinned candidate. A 2.453-fold storage increase needs a held-out quality benefit plus real-device memory/latency acceptance. This set does not justify shipping the larger default automatically.
4. **Do not add a prompt abstraction.** Current context wording is already official. The minimal necessary template is the existing no-context official template. Only experiment code/data were added in this lane; implementation and product acceptance remain the root task's responsibility.

The next acceptance corpus should independently cover target-language correctness, codes/unknown source, negation scope, identifier preservation, temporal boundaries, names, pronoun/lexical ambiguity, and conversational context leakage. Keep these 14 cases as explicit regressions but do not use them as the held-out benchmark.
