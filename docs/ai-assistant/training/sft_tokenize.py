"""Chat-template rendering and assistant-only label masks for the intent-slot SFT job.

Why this exists (training contract R2 and R3, sft-data-path section 9):
- The Hugging Face llama3.2 template adds a "Today Date" line that changes by day and that the served Ollama
  template does not emit. Training must see the prompt shape the served model sees, so the text is rendered here
  from a pinned copy of the Ollama template instead of calling apply_chat_template.
- The old trainer put one `text` field through SFTTrainer, so loss covered system and user tokens. Here every token
  outside an assistant answer gets label -100.

Pure Python. The tokenizer is injected as `encode(text) -> list[int]` (no special tokens added, special-token text
such as <|eot_id|> parsed as the special token) so the mask logic is testable without downloading a model.
Nothing here reads data, trains, or touches a model.
"""
from __future__ import annotations

IGNORE_INDEX = -100
BOS = "<|begin_of_text|>"
EOT = "<|eot_id|>"
# Pinned source for the rendering below: Ollama llama3.2:3b template blob
# sha256:966de95ca8a62200913e3f8bfbf84c8494536f1b94b49166851e76644e966396 (no tools, no date line).
OLLAMA_TEMPLATE_SHA256 = "966de95ca8a62200913e3f8bfbf84c8494536f1b94b49166851e76644e966396"
SYSTEM_PREFIX = "Cutting Knowledge Date: December 2023\n\n"


def _header(role: str) -> str:
    return f"<|start_header_id|>{role}<|end_header_id|>\n\n"


def render_segments(messages: list[dict]) -> list[tuple[str, bool]]:
    """Return [(text, trainable)] for one conversation, without BOS.

    Shape matches the Ollama template for a conversation whose last turn is the assistant answer, with one
    deliberate difference: the final assistant turn also gets <|eot_id|> so the model learns to stop (the template
    omits it only because at inference the model generates it).
    Raises ValueError on anything that would train on the wrong tokens.
    """
    if not isinstance(messages, list) or not messages:
        raise ValueError("messages must be a non-empty list")
    items = list(messages)
    system = ""
    if items[0].get("role") == "system":
        system = items.pop(0).get("content")
        if not isinstance(system, str):
            raise ValueError("system content must be a string")
    if not items:
        raise ValueError("conversation has no user or assistant turn")
    segments: list[tuple[str, bool]] = [(_header("system") + SYSTEM_PREFIX + system + EOT, False)]
    expected = "user"
    for message in items:
        role, content = message.get("role"), message.get("content")
        if role != expected:
            raise ValueError(f"expected role {expected!r}, got {role!r}")
        if not isinstance(content, str):
            raise ValueError(f"{role} content must be a string")
        if role == "user":
            segments.append((_header("user") + content + EOT, False))
            expected = "assistant"
        else:
            if not content.strip():
                raise ValueError("empty assistant answer: nothing to learn")
            segments.append((_header("assistant"), False))
            segments.append((content + EOT, True))
            expected = "user"
    if expected != "user":
        raise ValueError("conversation must end with an assistant answer")
    return segments


def render_text(messages: list[dict]) -> str:
    return BOS + "".join(text for text, _ in render_segments(messages))


def build_example(messages: list[dict], encode, bos_id: int, max_length: int) -> dict:
    """Token ids, labels and attention mask for one conversation. Never truncates silently."""
    ids = [bos_id]
    labels = [IGNORE_INDEX]
    for text, trainable in render_segments(messages):
        piece = list(encode(text))
        ids.extend(piece)
        labels.extend(piece if trainable else [IGNORE_INDEX] * len(piece))
    if len(ids) > max_length:
        raise ValueError(f"example is {len(ids)} tokens, over max_length {max_length}; refusing to truncate")
    if not any(label != IGNORE_INDEX for label in labels):
        raise ValueError("example has no trainable tokens")
    return {"input_ids": ids, "labels": labels, "attention_mask": [1] * len(ids)}


def check_segmentation(messages: list[dict], encode, bos_id: int) -> None:
    """Segment-wise ids must equal encoding the whole rendered text, or the mask is aligned to the wrong tokens."""
    whole = [bos_id] + list(encode("".join(text for text, _ in render_segments(messages))))
    parts = build_example(messages, encode, bos_id, max_length=len(whole) + 1)["input_ids"]
    if whole != parts:
        raise ValueError("segment-wise tokenization differs from whole-text tokenization")


def collate(examples: list[dict], pad_id: int) -> dict:
    """Right-pad a batch. Padding gets attention 0 and label -100."""
    if not examples:
        raise ValueError("empty batch")
    width = max(len(e["input_ids"]) for e in examples)
    batch = {"input_ids": [], "labels": [], "attention_mask": []}
    for e in examples:
        pad = width - len(e["input_ids"])
        batch["input_ids"].append(e["input_ids"] + [pad_id] * pad)
        batch["labels"].append(e["labels"] + [IGNORE_INDEX] * pad)
        batch["attention_mask"].append(e["attention_mask"] + [0] * pad)
    return batch
