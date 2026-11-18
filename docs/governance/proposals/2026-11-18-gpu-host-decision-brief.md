# GPU-capable host decision brief

Status: proposed, not a purchase decision. Prepared 2026-11-18 for [iteration 2's assignment](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6973109514), from main `1ae865e59601dcd7698038a7521ad48d0a28e62f`. Codex owns this brief; Cowork owns independent review, merge sequencing and the PMO line; Nate owns purchase and commissioning approval.

## Recommendation and requirement

Keep the P520 W-2145/64GB as the lead candidate, conditional on evidence for the actual unit's PSU, GPU power leads, RAM and included drive hardware. Compare the cheaper HP Z4 G4 only after confirming its identity and Xeon configuration. Neither is purchase-ready from the evidence below.

The requested permanent tower must have ECC memory, at least two usable drive bays, NVMe capability and a credible GPU upgrade path. Initial use is Ollama inference and later C1 evaluation; training remains NOT_RUN. A tower purchase does not authorize training, protected-data release, host migration or installation. No work item is currently GPU-gated per Cowork; W-117 remains C2 corpus, then C1 evaluated retrieval, then C3. This brief does not change that order.

Prefer 64GB installed rather than an unpriced memory upgrade. Compare complete usable systems: tower + RAM + boot NVMe + display/compute GPU + required cables/caddies + shipping/tax. A cheap display card can enable setup but does not establish useful inference performance. Do not pay a CPU premium on an unmeasured assumption that it removes the GPU bottleneck.

## Evidence: platform capability is not delivered configuration

VERIFIED here means checked against the cited manufacturer/software documentation on 2026-11-18. It does not mean a used unit was inspected. UNVERIFIED covers peer-reported listings, current checkout prices, included parts and performance.

| Platform/source | VERIFIED platform facts | Still UNVERIFIED for the offered unit |
|---|---|---|
| [Lenovo P520 PSREF](https://psref.lenovo.com/syspool/Sys/PDF/ThinkStation/ThinkStation_P520/ThinkStation_P520_Spec.html) | Tower; DDR4 ECC RDIMM; two onboard M.2 slots; two standard 3.5-inch bays with optional expansion; 690/900/1000W PSU options. Display support is through discrete graphics. | Actual PSU and leads; installed DIMM part numbers; caddies/cables and free bays; M.2 mounting parts; BIOS access; GPU clearance. A platform option is not included hardware. |
| [HP Z4 G4 QuickSpecs, version 62](https://www8.hp.com/h20195/v2/getpdf.aspx/c05527757.pdf), pp. 7 and 21 | Xeon W variants use ECC RDIMM; Core X variants differ and use non-ECC UDIMM. Xeon PSU options include 465W with no GPU power leads, 750W with two 6-pin leads, and 1000W with four 6+2-pin leads. | Exact model/product number, CPU variant, PSU label, RAM population, drive mounting hardware and included GPU. The reported regulatory marking alone does not establish the offered machine's identity. |
| [Ollama hardware support](https://docs.ollama.com/gpu) | NVIDIA support depends on GPU compute capability and driver version; current docs specify capability 5.0+ with extra driver requirements for older cards. | Selected GPU, VRAM fit, model/quantization/context/concurrency, OS/driver combination and measured throughput. An included display GPU is not proof the intended workload fits. |

A 465W Z4 with no auxiliary GPU leads limits the upgrade path; it is not proof that every GPU or every possible training workload is impossible. Choose the actual GPU first, then verify connector type, power budget, card dimensions and cooling against the exact host. Do not assume adapters or a higher advertised PSU wattage establish compatibility.

## Candidate comparison

Prices below are dated peer reports, not verified current offers. Source: [Cowork research handoff](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6973109514) and [machine-purchase session handoff](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6973125482). No additional seller identities are introduced.

| Candidate | Peer-reported configuration / price | Decision value and unresolved gate |
|---|---|---|
| [P520 target](https://www.ebay.com/itm/178461667923) | W-2145, 64GB, no GPU/drive, excellent refurbished; $681.96 or best offer, free shipping, reported 1-year coverage | Lead candidate if actual PSU/leads and complete 64GB ECC configuration are confirmed. Requires boot storage and a display solution. |
| [Second P520 listing](https://www.ebay.com/itm/800772308402) | Same reported configuration, $786.94 | No verified added value justifying the higher reported price. |
| PCSP P520 | W-2135, 64GB, 900W; $665.91; reported 1-year warranty, 90-day returns, 20% restocking fee | Lower CPU tier may suffice for GPU-first inference. Exact configured product URL, DIMMs, included GPU/storage and applicable return terms need confirmation. |
| Newegg PCSP P520 | W-2145, 64GB, 512GB, P2000; $1,011.82; reported 30-day returns | More complete package; PSU/ECC configuration and workload suitability unresolved. Do not treat the included GPU as an approved compute choice. |
| Dell 5820 | W-2135, 32GB; $459.99 | Lower initial price, but RAM upgrade and 425/950W PSU uncertainty prevent a complete-cost comparison. |
| Lenovo P720 | Two Gold 6150 CPUs, P4000; $729.99 | Extra CPUs are not demonstrated value for this workload. RAM, PSU, storage, idle power and acoustics unknown. |
| HP Z4 G4 | W-2145, 32GB, 256GB, R5 430; $455, very good refurbished, reported 1-year coverage | Strong lower-cost alternative only if identity, ECC configuration and adequate PSU/leads are proven. Peer estimates $169-178 for added 32GB, giving $624-633 before other differences, tax or shipping. Exact listing link missing. |

**Price conflict to resolve:** Codex's web retrieval during this review returned an older indexed target-P520 page showing $729.96 and the [990 EVO Plus 2TB listing](https://www.ebay.com/itm/286209155520) at $339.00, while the purchase session reported $681.96 and $329.00 in Nate's cart. The retrieved pages have stale delivery dates/crawl metadata. Neither supersedes a current cart check. The peer-reported $1,010.96 subtotal excludes a GPU and any tax; it is not a usable-host total. No cart was opened or changed for this brief.

## Questions for the seller / owner to resolve

These are proposed questions, not sent messages.

1. Confirm the exact product/model number and CPU; provide BIOS/system-information and internal photos for the actual configuration shipped.
2. Photograph the PSU wattage/part label and all included GPU power leads. Which connectors are available without adapters?
3. Confirm each DIMM's part number, ECC registered type, capacity and slot population. Include the installed total and a memory diagnostic result.
4. Confirm two usable drive bays with trays, SATA data/power leads, and NVMe slot/mounting screws or required adapters.
5. Confirm the included GPU (or none), display outputs and a tested route into BIOS; any BIOS/admin password or startup fault?
6. Confirm warranty provider, coverage length, return window, restocking and return-shipping terms for this exact listing.
7. Confirm the final configured price and included components before comparing totals. For the Z4, resolve the identity/PSU questions before pricing RAM additions.

## Gates and next action

- **Now:** request the missing listing links and unit evidence through the existing purchase lane. No seller contact or spending by this lane.
- **Before Nate's buy decision:** reconcile current product/cart prices and complete totals; identify the intended model, quantization, context and concurrency; select a compatible compute GPU with a stated budget. No throughput or training-capacity claim yet.
- **After separately approved commissioning:** check ECC/drive health, BIOS access, GPU discovery and actual GPU offload; measure cold/warm latency, throughput, memory use, wall power and noise on an approved non-sealed workload. These checks are all NOT_RUN.
- **Review state:** source-linked docs only; no code, deployment, credential, cart, purchase, device command or training change. Independent exact-head review is required before Cowork merges. Backlog/roadmap reconciliation remains Cowork-owned under the iteration assignment; no new work ID is allocated here.
