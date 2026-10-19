# PaddleOCR-VL-1.6 Integration & Feasibility Analysis

**Status:** Proposed / Architectural Decision Record & Implementation Plan  
**Target Branch:** `feat/introduce-ocr-and-yomitan`  
**Feature Branch:** `feat/paddleocr-vl-integration`  

---

## 1. Executive Summary

This document evaluates integrating **PaddleOCR-VL-1.6** into `komi-kun` as an alternative high-precision OCR engine alongside the current **PP-OCRv6 small** model.

While the current engine uses a traditional two-stage pipeline (**DBNet** text detection + **CTC** sequence recognition via ONNX Runtime), **PaddleOCR-VL-1.6** is a **0.9B parameter Vision-Language Model (VLM)** combining a dynamic-resolution SigLIP visual encoder with an ERNIE 4.5 language model backbone. 

### Key Takeaways
1. **Quality & Capabilities:** PaddleOCR-VL-1.6 achieves state-of-the-art results (96.33% on OmniDocBench v1.6 across 109 languages). It excels at dense text, irregular fonts, stylised manga lettering, and complex layouts.
2. **Resource Constraints:** Unlike PP-OCRv6 small (~16 MB total, ~50 ms latency, ~40 MB RAM), running a 0.9B VLM locally on mobile requires **~600 MB to 1.8 GB** model weights, **~1.2 GB to 2 GB RAM**, and takes **3–10 seconds per page** on a mobile CPU.
3. **Architecture Decision:**
   - Retain **PP-OCRv6 small** as the default, instantaneous on-device engine for real-time page turns.
   - Introduce **PaddleOCR-VL** as an opt-in **High-Quality Engine** behind the unified `OcrEngine` interface.
   - Support PaddleOCR-VL through a dual-mode strategy:
     - **Mode A (High Performance Client / Remote / LAN):** Connects to any local server, home PC, or cloud instance running `vLLM` or `llama-server` (OpenAI-compatible vision completions with `Spotting:` prompt).
     - **Mode B (On-Device Mobile Edge):** Path defined for Google AI Edge **LiteRT-LM** (`.litertlm` format via `litert-community/PaddleOCR-VL-1.6`) or mobile `llama.cpp` (`PaddlePaddle/PaddleOCR-VL-1.6-GGUF`) for flagship devices with 8GB+ RAM.
   - Provide seamless automatic fallback to PP-OCRv6 if the VL service is unreachable.

---

## 2. Technical Comparison: PP-OCRv6 Small vs PaddleOCR-VL-1.6

| Metric / Dimension | Current: PP-OCRv6 Small | Candidate: PaddleOCR-VL-1.6 |
|---|---|---|
| **Architecture** | 2-stage: DBNet (detection) + SVTR/MobileNetV1 CTC (recognition) | End-to-End Vision-Language Model (SigLIP ViT + ERNIE-4.5-0.3B/0.9B decoder) |
| **Inference Backend** | ONNX Runtime C API (`libpaddle_ocr.so`) | vLLM / llama.cpp / Google LiteRT-LM |
| **Model Size** | **~16 MB total** (det: 9.8 MB, rec: 21 MB uncompressed / 16 MB zip) | **~600 MB** (Q4_K_M GGUF) to **1.8 GB** (FP16 safetensors) |
| **Memory (RAM)** | **~30–50 MB** | **~1.2–2.0 GB** |
| **Inference Latency (Android CPU)** | **~40–80 ms** per page | **~3,000–10,000 ms (3–10 s)** per page |
| **Inference Latency (Server GPU / NPU)** | ~10 ms | ~150–400 ms |
| **Localization Output** | Exact line polygons (`LINE\|...\|bbox\|pts\|isVertical`) | Tokenized text spotting tags (`[ymin, xmin, ymax, xmax] text`) |
| **Supported Languages** | Unified CJK + Latin (100+ langs) | 109 languages + math, tables, seals |
| **Manga Furigana Handling** | Native spatial separation & size heuristic | End-to-end linguistic context |
| **Battery & Thermal Footprint** | Negligible (sub-100ms bursts) | High (sustained multi-second CPU/GPU load) |

---

## 3. How Text Spotting Works in PaddleOCR-VL-1.6

PaddleOCR-VL-1.6 supports end-to-end **Text Spotting** via task prompts. When prompted with `Spotting:`, the model outputs both the bounding coordinates and recognized text for each text region in the image:

```
[ymin, xmin, ymax, xmax] 素晴らしい！
[ymin, xmin, ymax, xmax] 剣聖と呼ばれた
[ymin, xmin, ymax, xmax] ライオネル様に
[ymin, xmin, ymax, xmax] 迫るご成長ですね
```

Coordinates are normalized (either floating-point `0.0–1.0` or scaled to `0–1000`).

### Coordinate Mapping to `EngineLine`
`PaddleOcrVlParser` converts these spotting lines into `EngineLine`:
1. **Coordinates:** `[ymin, xmin, ymax, xmax]` $\rightarrow$ `NormalizedBBox(left=xmin, top=ymin, right=xmax, bottom=ymax)`.
2. **Writing Direction:**
   - If $\frac{\text{height}}{\text{width}} \ge 1.25$ $\rightarrow$ `WritingDirection.TTB` (Vertical Japanese).
   - If $\frac{\text{width}}{\text{height}} \ge 1.25$ $\rightarrow$ `WritingDirection.LTR` (Horizontal).
   - Otherwise derived from Japanese character presence.
3. **Character Size & Metrics:** Normalized character dimension along the primary reading axis.

---

## 4. Integration Architecture

```
                  +-----------------------------------+
                  |         OcrPreferences            |
                  |  - ocrEngineType                  |
                  |  - paddleVlEndpoint               |
                  |  - paddleVlFallback               |
                  +-----------------+-----------------+
                                    |
                                    v
                         +--------------------+
                         |  RoutingOcrEngine  |
                         +----+----------+----+
                              |          |
           [PP_OCRV6_SMALL]   |          |   [PADDLEOCR_VL]
                              v          v
                  +---------------+  +---------------------+
                  |PaddleOcrEngine|  |  PaddleOcrVlEngine  |
                  | (ONNX Runtime)|  +----------+----------+
                  +---------------+             |
                                                v
                                     +--------------------+
                                     | PaddleOcrVlParser  |
                                     +--------------------+
                                                |
                                   (HTTP POST /v1/chat/completions)
                                                |
                                                v
                                    [vLLM / llama-server]
                                  PaddleOCR-VL-1.6 (0.9B)
```

### 1. `RoutingOcrEngine`
`RoutingOcrEngine` implements `OcrEngine` and dynamically dispatches `recognize(bytes, language)` based on `OcrPreferences.ocrEngineType`:
- If `PP_OCRV6_SMALL`: runs `PaddleOcrEngine` directly.
- If `PADDLEOCR_VL`: executes `PaddleOcrVlEngine`.
- If `PaddleOcrVlEngine` throws an exception (e.g. server offline or network timeout) and `paddleVlFallback` is enabled, it automatically executes `PaddleOcrEngine` and logs a warning.

### 2. Cache Signature Isolation
Cache keys include the engine generation and model type:
`cacheVariant = "paddle-v6-small"` vs `"paddle-vl-1.6"`.
This ensures switching between models never produces stale or corrupted cached pages.

---

## 5. Mobile Deployment Roadmap

### Phase 1 (Included in this PR): Server / High-Quality LAN Mode
- Users can run `vLLM` or `llama-server` on a PC, NAS, or home server:
  ```bash
  vllm serve PaddlePaddle/PaddleOCR-VL-1.6 --port 8080
  ```
  or
  ```bash
  llama-server -m PaddleOCR-VL-1.6-Q4_K_M.gguf --mmproj PaddleOCR-VL-1.6-mmproj.gguf --port 8080
  ```
- Android app sends page frames to the configured endpoint and displays the high-fidelity bounding boxes and text.

### Phase 2: On-Device LiteRT-LM Execution
- Google AI Edge **LiteRT-LM** offers native `.litertlm` execution for Android.
- Community weights (`litert-community/PaddleOCR-VL-1.6`) package the quantized model.
- Requires integrating `com.google.ai.edge.litert:litert-lm:0.1.0` AAR into `:core:ocr`.
- Gated behind a device capability check (minimum 8 GB RAM, OpenCL/Vulkan GPU delegate).

---

## 6. Verification Plan
- Unit tests for `PaddleOcrVlParser`: test coordinate parsing across bracket formats, XML box tags, JSON responses, and edge cases.
- Unit tests for `RoutingOcrEngine`: verify engine routing and automatic fallback behavior.
- UI settings test in `SettingsOcrScreen`: verify model selection, endpoint input, and fallback switches.
