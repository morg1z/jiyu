# Jiyu Translation Engine — Competitive Benchmark & Roadmap

**Date:** 2026-09-06
**Jiyu revision analyzed:** `5d008779c5fa14fd861f235047748530be0df6f5` (branch `master`)
**Method:** full source read of Jiyu's translation stack plus five open-source competitors, cloned/downloaded at pinned revisions and read directly (not from READMEs). Full per-project working reports (much more detail/evidence than this summary) live at:
`%LOCALAPPDATA%\Temp\claude\...\3e06c9be-2fd6-4a27-a16a-d0700ec687f0\scratchpad\audit\{00_jiyu_baseline, 01_comic_translate, 02_manga_translator_android, 03_saber_translator, 04_manga_image_translator, 05_comictranslator1}.md`
Every claim in those files (and summarized here) is tagged VERIFIED (read directly in source) or INFERRED (reasonable extrapolation); nothing is presented as VERIFIED on the strength of a README alone.

Projects analyzed, in order:
1. **Comic Translate** (ogkalu2/comic-translate) — `8f13ae5` — Apache-2.0 — desktop PySide6, Python
2. **Manga Translator Android** (jedzqer/manga-translator-android) — `699e606` — MIT — native Android, Kotlin
3. **Saber Translator** (MashiroSaber03/Saber-Translator) — `0a7367a` — GPL-3.0 — desktop Flask+Vue, Python
4. **Manga Image Translator** (zyddnys/manga-image-translator) — `95227a2` — GPL-3.0 — Python toolkit/server, the ecosystem's foundational project
5. **ComicTranslator1** (HF: LGJAS/ComicTranslator1, mirror of georgescutelnicu/Manga-Translator) — MIT — student demo, Flask

---

## 1. Current Jiyu score (0–10)

| Dimension | Score | One-line why |
|---|---|---|
| OCR | 8 | Real on-device manga-ocr transformer (JP) + ML Kit (everything else), measured auto-language thresholds |
| Detection | 7 | Own YOLOv8m + hand-written NMS/decode is solid, but only wired into the Japanese path |
| Translation | 7 | 5-6 free-tier LLM providers, real circuit breaker, JSON-mode — resilience is excellent, quality ceiling is small/free models |
| Context | 8 | Cross-bubble continuation, rolling tail, auto-learned glossary, cultural rules — genuinely ahead of the whole field (see §2) |
| Consistency | 7 | Glossary + rolling tail reduce drift; no cross-chapter memory |
| Rendering | 8 | Bubble-contour-shape-fit layout — confirmed ahead of **all five** competitors, five independent times |
| Performance | 6 | Measured tuning, but no GPU/NNAPI/XNNPACK execution provider, fixed concurrency constants |
| Reliability | 8 | 17-version cache-invalidation changelog, WorkManager survival, real circuit breaker — best-in-field |
| Android integration | 7 | Correct Hilt/Room/Compose/WorkManager idiom; no adaptive resource scaling |
| Novel translation | 6 | Solid dedicated pipeline; thinner than the manga path; **no competitor has an equivalent at all** |
| **Overall** | **7.3** | Substantially more sophisticated than a hobby "OCR + call an LLM" app. Structural gaps (no neural inpainting, no GPU-accelerated ONNX, fixed concurrency) are documented, deliberate $0-cost/on-device tradeoffs — not oversights. |

---

## 2. The headline finding

**Jiyu's context system and unattended-reliability architecture are ahead of all five competitors, independently confirmed five times over** (auto-learned persistent glossary, rolling cross-batch context tail, cross-bubble continuation detection, content-type/demographic cultural rules, multi-provider circuit breaker with exponential backoff). No competitor — including the much larger Saber Translator and the foundational Manga Image Translator — has an auto-learned glossary or a working multi-provider failover chain. **Jiyu's bubble-contour-shape-fit renderer is also confirmed ahead of the entire field, five independent times** — every competitor fits text into a bounding rectangle; none fits the actual bubble silhouette.

Where Jiyu is behind is narrower and more fixable than expected: **Android-specific performance engineering** (no execution-provider acceleration, no resource-adaptive concurrency, OCR confidence collected but never acted on) and **automatic quality-assurance loops** (Jiyu detects some bad-output patterns but never auto-retries; Manga Image Translator does, and it's the single best idea found in this whole benchmark).

---

## 3. Cross-project capability matrix

| Capability | Jiyu | Comic Translate | Manga Translator Android | Saber Translator | Manga Image Translator | ComicTranslator1 |
|---|---|---|---|---|---|---|
| OCR (JP model) | manga-ocr ONNX, hardened | manga-ocr + lighter **manga-ocr-mobile** option | dropped dedicated JP model for general PP-OCR | manga-ocr (same lineage) | manga-ocr **+ own Roformer 48px** | manga-ocr, unhardened, **input bug** |
| OCR confidence used? | No (logged only) | No | **Yes**, calibrated per-language | No | Partial (region-accept threshold) | No |
| Execution provider (ONNX) | None (plain CPU) | CPU-tuned thread count | **XNNPACK**, user-toggleable | n/a (PyTorch/ONNX desktop) | n/a (desktop) | n/a |
| Detection | YOLOv8m, JP-only + ML-Kit-merge | RT-DETR-v2 int8 + **tall-image slicer** | YOLO26n-seg + PP-OCR-det + **tiling** + **cross-page merge** | DBNet/CTD/YOLO + **2nd-stage merge-correction** | 6 backends + **panel-boundary reading order** | YOLOv8n, boxes only |
| Adaptive concurrency | Fixed `Semaphore` | n/a | **`DeviceResourcePolicy`** (live RAM/CPU-based) | n/a | n/a | n/a |
| Context/glossary | **Auto-learned, persistent, cultural rules** | Static free-text field only | Single style-hint string | User-curated glossary + **placeholder-protect** | File-based glossary, no auto-learn | None |
| Post-translation validation | Heuristics, **not auto-retried** | None | None | Post-hoc glossary-violation check | **Full auto-retry loop** (repetition + language-ratio) | None |
| Inpainting | **Two-tier classical CV** (unique in the field) | LaMa/AOT-GAN/MI-GAN (neural, heavy) | Single solid-fill, no 2nd tier | LaMa/lama_mpe/litelama | 5 options, default heaviest | Assume-white flood-fill only |
| Rendering (bubble fit) | **Contour-shape-aware — unique in the field** | Rectangle shrink-to-fit | `StaticLayout` rectangle | Rectangle + deep manual editing | Custom rasterizer, rectangle | Rectangle, black text only |
| Vertical CJK rendering | None | Full (Qt, not portable) | Full (native Canvas) | Full (Pillow) | Full (custom rasterizer) | None |
| Error recovery (unattended) | **5-6 provider circuit breaker** | None found | Single-endpoint fixed backoff | Fixed-count retry, no breaker | Bounded retry + fallback model | None |
| Cost model | **$0, pooled, no key required** | BYOK | BYOK | BYOK (+ Ollama local) | BYOK + local MT | Scraping wrappers / local MT |
| Novel/light-novel | **Dedicated pipeline** | n/a | n/a | Absent (verified) | Absent (verified) | n/a |
| Manual retry granularity | None (long-press edit only) | **Per-block/page re-run everywhere** | — | **Deep property editing** (font/color/rotation) | Manual box-correction (server mode) | None |
| Android native | **Yes** | No (desktop) | Yes | No (desktop) | No (server/desktop) | No (server) |

---

## 4. Best implementation per component (across all 6 projects)

- **Best OCR pipeline overall:** Jiyu's (hardened ONNX port with timeout/fallback) — but the **model file** worth swapping in is Comic Translate's `ogkalu/manga-ocr-mobile` (Apache-2.0, ~94MB vs ~150-200MB, KV-cache decode, garbage-output rejection).
- **Best OCR confidence handling:** Manga Translator Android (per-language calibrated thresholds that actually gate output).
- **Best text detection breadth:** Manga Image Translator (6 backends) — but breadth ≠ quality; its own maintainer says "DO NOT use craft for manga." The **useful** ideas are narrower: tall-image tiling (independently invented by Comic Translate *and* Manga Translator Android) and Kumiko-style panel-boundary reading order (Manga Image Translator only).
- **Best detector cross-validation idea:** Saber Translator's `SaberYOLO` second-stage merge-correction — a cheap independent check that catches over-merged bubbles.
- **Best context/glossary architecture:** Jiyu's, clearly, confirmed against all five competitors — nothing else has auto-learning. The one mechanism worth bolting on top: Saber Translator's placeholder-protect-and-restore (deterministic term preservation, not just prompt-hinting).
- **Best automatic quality-assurance loop:** Manga Image Translator's repetition/hallucination + target-language-ratio check with automatic region-then-batch retry — the single most valuable idea in the entire benchmark.
- **Best image cleanup:** Jiyu's two-tier classical CV, confirmed unique — no competitor has an equivalent "cheap tier for the common case, real tier for the hard case" design. For a genuine *neural* tier, AOT-GAN/MI-GAN (Comic Translate) are the only Android-plausible options; full LaMa and `lama_mpe` are both disqualified (see §5).
- **Best rendering/typography:** Jiyu's contour-fit renderer, confirmed unique across all five competitors, five independent times. Saber Translator's manual per-bubble property-editing UI is the best complementary *manual* tool.
- **Best Android architecture ideas:** Manga Translator Android's `DeviceResourcePolicy` (live memory/CPU-adaptive concurrency) and its XNNPACK execution-provider usage — both concrete, proven-on-Android, directly portable.
- **Best UX for manual correction:** Comic Translate (per-block/page re-run) + Saber Translator (deep property editing) combined — Jiyu currently only offers text-content editing.
- **Best novel/light-novel handling:** Jiyu's, by default — **no competitor has any novel/text pipeline at all** (all five are image-only tools).

---

## 5. A correction discovered mid-benchmark (worth flagging on its own)

Comic Translate and Saber Translator both independently labelled `lama_mpe` as the "lightweight/fast" LaMa variant worth benchmarking for Jiyu's neural-inpainting experiment. Reading Manga Image Translator (the upstream source of that model) directly falsified this: `LamaMPEInpainter`'s own docstring reads *"Better mark as deprecated and replace with lama large,"* and the project's own default is the heavier `lama_large`, not `lama_mpe`. **Conclusion: `lama_mpe` is off the table. AOT-GAN and MI-GAN remain the only live candidates** for a future Android inpainting experiment. This is exactly the kind of error that following secondhand competitor UI labels (rather than reading the actual upstream code) would have produced — worth remembering as a caution for future research of this kind.

---

## 6. Hidden techniques worth knowing about (spec §37)

- **Tall-image slicing before detection** (Comic Translate's `ImageSlicer`, independently reinvented by Manga Translator Android) — pure geometry, no model cost, solves the "detector trained on square crops vs. a 10,000px webtoon strip" mismatch Jiyu currently has no answer for.
- **Cross-page bubble merging** (Manga Translator Android) — reconstructs a single speech bubble that a source website's own page-splitting cut in half across two downloaded images. Different problem from Jiyu's same-page continuation detection; both should coexist.
- **Placeholder-protect-and-restore** (Saber Translator) — swap glossary/name terms for LLM-opaque tokens before the API call, restore verbatim after. Deterministic where prompt-hinting is only probabilistic.
- **Automatic repetition/hallucination + target-language-ratio validation with retry escalation** (Manga Image Translator) — the standout idea of the whole benchmark.
- **XNNPACK execution provider** (Manga Translator Android) — a config-only ONNX Runtime Mobile change with no model changes needed.
- **`DeviceResourcePolicy`** (Manga Translator Android) — live-memory/CPU-derived concurrency and bitmap-decode budgets, replacing fixed constants.
- **SaberYOLO second-stage merge-correction** (Saber Translator) — an independent reference detector used only to catch and reverse the primary detector's over-merges.

---

## 7. Full recommendation list (deduplicated across all five reports, priority-sorted)

### P1 — do these first (all independently high-ROI, mostly low-to-medium difficulty, no architecture rewrites)

1. **Adopt `manga-ocr-mobile`** (Comic Translate, Apache-2.0) to replace/A-B the current manga-ocr-base ONNX port — ~2x smaller, KV-cache decode loop, built-in garbage-output rejection. Files: `MangaOcrPipeline.kt`.
2. **Enable an XNNPACK (or equivalent) execution provider** for both existing ONNX sessions (bubble detector + manga-ocr). Config-only change, proven on a same-platform competitor. Files: `MangaOcrPipeline.kt`, `BubbleBoxDetector.kt`.
3. **Use OCR confidence to gate/reject output**, not just log it — for ML Kit immediately (per-script calibrated thresholds); for manga-ocr, needs the decode loop to also track/return token probability (bundle with recommendation #1, same function touched). Files: `OcrEngine.kt`, `MangaOcrPipeline.kt`.
4. **Adopt a device-resource-adaptive concurrency/memory-budget system** (`DeviceResourcePolicy`-style) to replace the fixed `Semaphore(5)`/`Semaphore(3)` constants — directly closes Jiyu's own documented "no adaptive scaling for low-end devices" gap. Files: new `util/DeviceResourcePolicy.kt`, `TranslateRepository.kt`.
5. **Adopt automatic post-translation validation with retry** (repetition/hallucination detection + target-language-ratio check via ML Kit Language ID, escalating region→batch retry) — the single best idea in this benchmark; converts Jiyu's existing detect-only heuristics into a real self-healing loop. Files: `TranslateRepository.kt`, new ML Kit Language ID dependency.
6. **Adopt placeholder-protect-and-restore for glossary/character-name terms** — an opt-in per-entry "protect exactly" flag (default off, to respect Czech grammatical case) giving deterministic term preservation instead of prompt-hoping. Files: `TranslateRepository.kt`, glossary data model, `GlossaryBottomSheet`, `GeminiUltraPrompt.kt`.
7. **Add a user-facing "retry this page/bubble" action** — pure UX, all underlying machinery (cache keys, per-page calls) already exists; only the cache-bypass entry point is missing. Files: `TranslationLayer.kt`, `TranslateRepository.kt`.

### P2 — real value, gated on a benchmark or more invasive rendering change

8. **Tall-image slicing** before bubble detection for webtoon/manhwa content, and extend YOLO-seeded detection to the manhwa/webtoon reader path (currently ML-Kit-line-merge-only there). Files: new `util/ImageSlicer.kt`, `WebtoonReader.kt`/`WebtoonSegment.kt`.
9. **Cross-page bubble merging** for webtoon/manhwa — reconstructs bubbles physically split by page-image boundaries. Harder part is rendering a bubble that spans two page bitmaps. Files: new `translate/CrossPageBubbleMerger.kt`, `WebtoonSegment.kt`/`TranslationLayer.kt`.
10. **EXPERIMENT: AOT-GAN or MI-GAN neural inpainting** as an opt-in tier specifically for the already-detected `bgUniform=false` textured-background case (not full LaMa, not `lama_mpe` — see §5). Requires an actual on-device latency benchmark before shipping. Files: new `translate/NeuralInpaint.kt`, `TextPatch.kt`.
11. **Second-stage detector merge-correction** — reuse Jiyu's existing YOLOv8m as an independent cross-check against ML-Kit-line-merge output for non-Japanese content, splitting back over-merged blocks. Performance-gated (running YOLO unconditionally on every page has real CPU cost) — pair with #4. Files: `BubbleMerge.kt`, `OcrEngine.kt`.
12. **Add an explicit "do not invent unstated speaker gender/identity" prompt rule** — extracted (cleanly, without the jailbreak wrapper it was found inside) from Manga Translator Android's prompt; addresses a real, common JP/KO→gendered-Latin-language failure mode. Files: `GeminiUltraPrompt.kt`.
13. **Expand manual bubble-editing UI** toward direct visual property controls (font/size/color/position/rotation, bulk-apply, manual box-draw for detection misses) — extends, doesn't replace, the automatic renderer. Files: `TranslationLayer.kt`, `ManualTranslationEntity`.

### P3 — nice-to-have, low cost, low-to-medium benefit

14. Optional user-editable "extra context" free-text field per manga (one-off narrative facts the auto-glossary can't infer).
15. Optional "bring your own custom endpoint" BYOK fallback tier, strictly additive, never the default/required path.
16. Custom font URL support (download-and-cache, small accessibility/personalization win).
17. Draggable bubble position (complements existing text-content editing).
18. Post-translation glossary-compliance warning check (cheap, reuses existing glossary data).
19. **EXPERIMENT**: bounded single-bubble vision-LLM OCR as a third-tier fallback, only after both existing OCR tiers fail — needs a hard rate cap to avoid quota blowup.
20. **EXPERIMENT**: panel-boundary-aware reading order (Kumiko-style) for non-grid page layouts — high porting difficulty, unmeasured real-world frequency in Jiyu's content.

### REJECT / DO NOT IMPLEMENT

- **BYOK-only translation model** (all three BYOK competitors) — incompatible with Jiyu's free-forever/no-key-required constraint; wholesale adoption rejected everywhere it appeared.
- **Full LaMa inpainting** — ~200MB, FFT-heavy, no evidence of acceptable mobile-CPU latency, confirmed independently by three projects.
- **`lama_mpe`** specifically — author-deprecated (see §5).
- **Stable-Diffusion-based inpainting** (Manga Image Translator) — the heaviest, most GPU-dependent option found anywhere in the benchmark.
- **RT-DETR detector architecture swap** — "NMS-free" is not an end-user quality win; Jiyu's existing hand-rolled NMS is already correct engineering.
- **CRAFT detector** — explicitly discouraged by its own maintainer (Manga Image Translator) for manga specifically.
- **Vertical CJK text rendering** — proven buildable (three of five competitors have it natively on their platform) but low-value for Jiyu's actual Latin-alphabet target languages; revisit only if Jiyu ever adds a vertical-script target.
- **Anti-refusal "jailbreak persona" prompting** (Manga Translator Android) — real ToS/account-suspension risk to Jiyu's *shared pooled* free-tier providers, plus genuinely less reliable output; the one legitimate rule buried inside it was extracted separately (P2 #12).
- **Whole-pipeline vision-LLM-direct-translate toggle** and **HQ multi-image multimodal mode** (Manga Translator Android, Saber Translator) — both quota-incompatible with Jiyu's shared-pool cost model; each would multiply token usage per page by a large factor for a coherence benefit Jiyu's existing rolling-context-tail + continuation-detection already covers more cheaply.
- **"Manga Insight" RAG / character-studio / AI-continuation-generation** (Saber Translator) — a large, real, but completely disconnected-from-translation subsystem; zero benefit to translation quality/consistency/speed/reliability, very high cost. Different product, not a pipeline improvement.
- **Plugin/extension architecture** (Saber Translator) — platform-inappropriate for Android; conflicts with Jiyu's native-Kotlin-only rule.
- **System-wide floating-overlay + `MediaProjection` screen translation** (Manga Translator Android) — technically proven, but a different product category (general screen-translation utility vs. an in-app reader) requiring `SYSTEM_ALERT_WINDOW`; this project's own recent Cloudflare-Turnstile work already declined that exact permission when an alternative existed — consistent precedent to not reach for it here either.
- **Ollama/Sakura local LLM** (Saber Translator) — needs desktop-class local compute; not realistic on a phone.
- **File-based multi-format glossary interop** (Manga Image Translator) — solves an import-compatibility problem Jiyu has no existing user base to justify.
- **Everything from ComicTranslator1** — smallest/least mature of the five, Jiyu is ahead on every single dimension, including a verified concrete defect (a color-inversion bug feeding every OCR call across all three of its entry points).

---

## 8. Ideal target architecture (after adopting the P1/P2 items above)

```
INPUT (page image / webtoon strip / novel chapter text)
  │
  ├─[IMAGE CONTENT: manga / manhwa / manhua / western comic]──────────────────
  │
  │   ├─ Tall-image? ──▶ ImageSlicer (tile + coordinate remap)          [NEW, P2]
  │   │
  │   ▼
  │   Bubble/text detection
  │     ├─ YOLOv8m (existing) — now run for ALL content, not JP-only    [enables P2 #11]
  │     ├─ ML-Kit-line-geometry merge (existing)
  │     └─ 2nd-stage merge-correction: YOLO boxes validate/split
  │        ML-Kit merge output                                          [NEW, P2]
  │   ▼
  │   Cross-page bubble merge (webtoon page-splits)                     [NEW, P2]
  │   ▼
  │   OCR
  │     ├─ Japanese → manga-ocr-mobile (swap), KV-cache decode,          [SWAP, P1]
  │     │   garbage-output filter, confidence tracked
  │     └─ else → ML Kit per-script, confidence-gated                   [P1]
  │     └─ (last resort, rate-capped) single-bubble vision-LLM OCR      [EXPERIMENT, P3]
  │   ▼
  │   Context assembly (UNCHANGED — Jiyu's strongest layer)
  │     auto-learned glossary + rolling tail + cross-bubble continuation
  │     + content-type/demographic cultural rules + [NEW] extra-context field [P3]
  │     + [NEW] "don't invent speaker gender/identity" rule              [P2]
  │   ▼
  │   Translation (UNCHANGED chain: Gemini→Groq→OpenRouter→Cerebras→Mistral→MLKit)
  │     + placeholder-protect-and-restore for exact-match terms          [NEW, P1]
  │     + [optional, additive] BYOK custom-endpoint fallback tier        [P3]
  │   ▼
  │   Automatic validation + retry loop                                  [NEW, P1]
  │     repetition/hallucination check, target-language-ratio check
  │     (ML Kit Language ID), region-then-batch auto-retry
  │     + post-translation glossary-compliance check                    [P3]
  │   ▼
  │   Inpainting / cleanup (UNCHANGED two-tier classical CV)
  │     + [EXPERIMENT] AOT-GAN/MI-GAN neural tier for bgUniform=false    [P2]
  │   ▼
  │   Rendering (UNCHANGED contour-shape-fit — already best-in-field)
  │     + manual property-editing UI (font/color/position/rotation)      [P2]
  │     + draggable position, custom font URL                            [P3]
  │   ▼
  │   Cache (UNCHANGED versioned Room cache, manual-edit overlay)
  │
  ├─[NOVEL / LIGHT NOVEL]───────────────────────────────────────────────────
  │   paragraph/sentence-safe chunking (UNCHANGED)
  │   → same glossary + rolling-tail context + placeholder-protect        [P1, shared]
  │   → translation chain (UNCHANGED)
  │   → automatic validation + retry (language-ratio check applies here too) [P1, shared]
  │   → cache (UNCHANGED)
  │
  ▼
Resource governor (device-adaptive concurrency, replacing fixed Semaphores) [NEW, P1]
  gates: OCR/detection concurrency, whether to even attempt on-device
  ONNX models on a low-memory device, XNNPACK toggle
```

Execution-acceleration (XNNPACK) and adaptive concurrency are cross-cutting — they wrap every ONNX call site and every batch-processing loop, not a single pipeline stage, so they're shown as the governor at the bottom rather than inline.

### Per-media-type notes
- **Manga**: full pipeline above, JP-optimized OCR path, RTL reading order — panel-boundary-aware reading order (EXPERIMENT, P3) is most relevant here for non-grid action-manga layouts.
- **Manhwa/Manhua**: same pipeline, LTR reading order, Korean/Chinese cultural rules — tall-image tiling and cross-page bubble merging (both P2) matter most here since these are the webtoon-format sources.
- **Western comics**: same pipeline, no honorific stripping needed reversed (already strips OCR-leaked Asian address terms) — lowest-priority for the new detector/tiling work since western sources are typically already page-shaped, not webtoon-strip-shaped.
- **Novels/light novels**: text-only pipeline shares glossary, placeholder-protect, translation chain, and the new automatic validation loop, but none of the image-side work (detection, inpainting, rendering) applies at all — keep these two pipelines architecturally separate, as Jiyu already correctly does.

---

## 9. Top 10 improvements (ranked by ROI)

| # | Improvement | Source | Priority | Difficulty | Expected benefit |
|---|---|---|---|---|---|
| 1 | Automatic post-translation validation + retry | Manga Image Translator | P1 | Medium | High |
| 2 | Device-resource-adaptive concurrency | Manga Translator Android | P1 | Low-Medium | High |
| 3 | Placeholder-protect-and-restore glossary terms | Saber Translator | P1 | Low-Medium | Medium |
| 4 | XNNPACK execution provider | Manga Translator Android | P1 | Low | Medium-High |
| 5 | OCR confidence gating | Manga Translator Android | P1 | Low (ML Kit) / Medium (manga-ocr) | Medium |
| 6 | `manga-ocr-mobile` model swap | Comic Translate | P1 | Medium | High |
| 7 | Per-page/bubble manual retry button | Comic Translate | P1 | Low-Medium | Medium |
| 8 | Tall-image slicing for webtoon detection | Comic Translate + Manga Translator Android (corroborated) | P2 | Medium | Medium-High (webtoon users) |
| 9 | Cross-page bubble merging | Manga Translator Android | P2 | Medium | Medium-High (webtoon users) |
| 10 | AOT-GAN/MI-GAN neural inpainting EXPERIMENT | Comic Translate, corrected by Manga Image Translator | P2 | High | Medium-High (textured backgrounds only) |

---

## 10. Roadmap

**Phase 1 — P1 items (all 7 in §7).** No architecture rewrites; mostly config changes, one new small utility class, and a few focused additions to `TranslateRepository.kt`/`OcrEngine.kt`. This phase alone should materially improve both reliability (auto-retry loop, resource-adaptive concurrency) and OCR speed/quality (XNNPACK, model swap, confidence gating) without touching Jiyu's already-best-in-field context or rendering systems.

**Phase 2 — P2 items (§7, items 8-13).** Webtoon/manhwa-specific detection improvements (tiling, cross-page merge, second-stage merge-correction) plus the neural-inpainting experiment. These are gated on real work: a rendering-architecture change to support a bubble spanning two page bitmaps, and an on-device latency benchmark before shipping any neural inpainter.

**Phase 3 — P3 items (§7, items 14-20).** UX polish (extra-context field, custom fonts, draggable bubbles, glossary-violation warnings) plus two open-ended EXPERIMENTs (bounded vision-LLM OCR fallback, panel-boundary reading order) that need their own scoping/measurement before committing engineering time.

**Phase 4 — Nothing.** There is no P0/critical tier in this report — no competitor's code revealed a correctness-breaking defect in Jiyu itself (the closest thing found was ComicTranslator1's own OCR-input color-inversion bug, which is *their* bug, not Jiyu's). This benchmark is a quality/performance improvement roadmap, not a bug-fix punch list.

---

## 11. The brutally honest answer

**What should actually change in Jiyu's translation engine?**

Not the parts everyone would guess. Jiyu's context system, glossary, error-recovery architecture, and rendering are already better than every competitor examined — including much larger, more heavily-resourced open-source projects. Rewriting or replacing any of those would be a regression, not an improvement, and this report explicitly recommends against it (KEEP, five times over, across five independent competitors).

What Jiyu is actually missing is two specific things, both narrow and both fixable without touching what already works:

1. **A closed-loop quality check.** Jiyu detects some bad-output patterns (verbatim copies, dropped sentences) but never acts on the detection — a flagged-bad translation still ships to the user. Manga Image Translator's automatic repetition/language-ratio validation with region-then-batch retry is a complete, working answer to exactly this gap, and it's cheap (pure string/language-ID analysis, no new model). This is P1 item #1 for a reason — it's the single highest-leverage change in this entire benchmark.

2. **Android performance engineering that never got done because nothing forced it.** No execution provider on either ONNX model, fixed concurrency constants regardless of device RAM, and OCR confidence that's measured but never used. None of this requires new architecture — it's exactly the kind of gap that shows up when a solo/small-team project builds a genuinely sophisticated pipeline (which Jiyu has) but hasn't yet had the chance to do a dedicated performance pass. Manga Translator Android — a same-platform, independently-developed competitor — already proves all three fixes work on real Android hardware.

Everything else in this report (tall-image tiling, cross-page merging, neural inpainting, deeper manual editing) is real, genuine value, but secondary — worth doing, gated appropriately, not urgent. If only one phase of this roadmap ever ships, it should be Phase 1: it's the highest ratio of (translation quality + reliability + speed) gained per line of code changed, and it touches none of the systems that are already Jiyu's strongest.
