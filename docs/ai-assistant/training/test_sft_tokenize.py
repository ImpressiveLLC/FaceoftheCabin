"""Tests for sft_tokenize: template shape, assistant-only mask, failure cases. Invented text only, no model.

The real-tokenizer test runs only when SFT_TOKENIZER_JSON points at the pinned tokenizer.json (sha256 below);
otherwise it is skipped and the run reports it as skipped, not passed.
"""
import hashlib
import os
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import sft_tokenize as st  # noqa: E402

PINNED_TOKENIZER_SHA256 = "6b9e4e7fb171f92fd137b777cc2714bf87d11576700a1dcd7a399e7bbe39537b"
SPECIALS = ["<|begin_of_text|>", "<|start_header_id|>", "<|end_header_id|>", "<|eot_id|>"]
SPECIAL_ID = {s: 1000 + i for i, s in enumerate(SPECIALS)}
BOS_ID, PAD_ID = SPECIAL_ID["<|begin_of_text|>"], 2000


def fake_encode(text):
    """Special tokens become one id; every other character becomes its own id. Deterministic, no model."""
    out = []
    for piece in re.split("(" + "|".join(re.escape(s) for s in SPECIALS) + ")", text):
        if piece in SPECIAL_ID:
            out.append(SPECIAL_ID[piece])
        else:
            out.extend(ord(c) for c in piece)
    return out


CONV = [
    {"role": "system", "content": "SYS"},
    {"role": "user", "content": "turn on the lamp"},
    {"role": "assistant", "content": '{"intent":"set_device_state"}'},
]


def test_render_matches_ollama_shape_exactly():
    expected = (
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n"
        "Cutting Knowledge Date: December 2023\n\nSYS<|eot_id|>"
        "<|start_header_id|>user<|end_header_id|>\n\nturn on the lamp<|eot_id|>"
        "<|start_header_id|>assistant<|end_header_id|>\n\n" '{"intent":"set_device_state"}<|eot_id|>'
    )
    assert st.render_text(CONV) == expected
    assert "Today Date" not in expected


def test_no_system_message_still_emits_the_system_block():
    text = st.render_text(CONV[1:])
    assert text.startswith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nCutting Knowledge Date: December 2023\n\n<|eot_id|>")


def test_only_assistant_answer_and_its_eot_are_trained():
    ex = st.build_example(CONV, fake_encode, BOS_ID, 512)
    trained = [i for i, lab in zip(ex["input_ids"], ex["labels"]) if lab != st.IGNORE_INDEX]
    assert trained == fake_encode('{"intent":"set_device_state"}<|eot_id|>')
    for ids, lab in zip(ex["input_ids"], ex["labels"]):
        assert lab in (st.IGNORE_INDEX, ids)  # a trained label is the token itself, never shifted or other
    assert ex["labels"][0] == st.IGNORE_INDEX  # BOS


def test_system_user_and_assistant_header_tokens_are_masked():
    ex = st.build_example(CONV, fake_encode, BOS_ID, 512)
    header = fake_encode("<|start_header_id|>assistant<|end_header_id|>\n\n")
    ids = ex["input_ids"]
    start = next(i for i in range(len(ids)) if ids[i:i + len(header)] == header)
    assert all(lab == st.IGNORE_INDEX for lab in ex["labels"][: start + len(header)])
    assert all(lab != st.IGNORE_INDEX for lab in ex["labels"][start + len(header):])


def test_segment_ids_equal_whole_text_ids():
    st.check_segmentation(CONV, fake_encode, BOS_ID)


def test_segmentation_check_fails_on_a_tokenizer_that_merges_across_the_boundary():
    def merging(text):  # merges the two newlines of the header with the next character: boundary-dependent
        return [hash(m) % 997 for m in re.findall(r"\n\n.|.", text, flags=re.S)]
    with pytest.raises(ValueError):
        st.check_segmentation(CONV, merging, BOS_ID)


def test_multi_turn_trains_every_assistant_answer_and_nothing_else():
    conv = CONV + [{"role": "user", "content": "and the fan"}, {"role": "assistant", "content": "ok2"}]
    ex = st.build_example(conv, fake_encode, BOS_ID, 1024)
    trained = [i for i, lab in zip(ex["input_ids"], ex["labels"]) if lab != st.IGNORE_INDEX]
    assert trained == fake_encode('{"intent":"set_device_state"}<|eot_id|>') + fake_encode("ok2<|eot_id|>")


@pytest.mark.parametrize("bad", [
    [],
    [{"role": "system", "content": "s"}],
    [{"role": "user", "content": "u"}],  # no assistant answer
    [{"role": "user", "content": "u"}, {"role": "assistant", "content": ""}],  # empty answer
    [{"role": "user", "content": "u"}, {"role": "assistant", "content": "   "}],
    [{"role": "assistant", "content": "a"}],  # starts with assistant
    [{"role": "user", "content": "u"}, {"role": "user", "content": "u2"}, {"role": "assistant", "content": "a"}],
    [{"role": "user", "content": 5}, {"role": "assistant", "content": "a"}],
    [{"role": "user", "content": "u"}, {"role": "tool", "content": "t"}],
])
def test_malformed_conversations_fail_safely(bad):
    with pytest.raises(ValueError):
        st.build_example(bad, fake_encode, BOS_ID, 512)


def test_truncation_is_refused_not_silent():
    with pytest.raises(ValueError, match="refusing to truncate"):
        st.build_example(CONV, fake_encode, BOS_ID, 20)


def test_padding_gets_attention_zero_and_label_ignore():
    a = st.build_example(CONV, fake_encode, BOS_ID, 512)
    b = st.build_example(CONV[1:], fake_encode, BOS_ID, 512)
    batch = st.collate([a, b], PAD_ID)
    widths = {len(v) for rows in batch.values() for v in rows}
    assert len(widths) == 1
    short = 1 if len(b["input_ids"]) < len(a["input_ids"]) else 0
    pad = len(batch["input_ids"][short]) - len(([a, b][short])["input_ids"])
    assert pad > 0
    assert batch["input_ids"][short][-pad:] == [PAD_ID] * pad
    assert batch["labels"][short][-pad:] == [st.IGNORE_INDEX] * pad
    assert batch["attention_mask"][short][-pad:] == [0] * pad
    with pytest.raises(ValueError):
        st.collate([], PAD_ID)


def test_template_is_deterministic_across_calls():
    assert st.render_text(CONV) == st.render_text(CONV)


@pytest.mark.skipif(not os.environ.get("SFT_TOKENIZER_JSON"), reason="set SFT_TOKENIZER_JSON to the pinned tokenizer.json")
def test_real_pinned_tokenizer_segmentation_and_mask():
    path = Path(os.environ["SFT_TOKENIZER_JSON"])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == PINNED_TOKENIZER_SHA256
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file(str(path))
    encode = lambda text: tok.encode(text, add_special_tokens=False).ids  # noqa: E731
    bos = tok.token_to_id("<|begin_of_text|>")
    assert bos == 128000
    st.check_segmentation(CONV, encode, bos)
    ex = st.build_example(CONV, encode, bos, 512)
    trained = [i for i, lab in zip(ex["input_ids"], ex["labels"]) if lab != st.IGNORE_INDEX]
    assert trained == encode('{"intent":"set_device_state"}<|eot_id|>')
    assert tok.token_to_id("<|eot_id|>") == trained[-1]
