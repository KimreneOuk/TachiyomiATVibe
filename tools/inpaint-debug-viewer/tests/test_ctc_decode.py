from server import ctc_decode_indices, ctc_decode_with_conf

DICT_SMALL = ["a", "b", "c", "d"]


def test_blank_at_0_decode_hello():
    indices = [0, 0, 0, 1, 0, 2, 0, 3, 0, 0, 0, 4, 0]
    result = ctc_decode_indices(indices, DICT_SMALL)
    assert result == "abcd"


def test_blank_at_0_collapse_repeats():
    indices = [0, 1, 1, 1, 0, 2, 2, 0]
    result = ctc_decode_indices(indices, DICT_SMALL)
    assert result == "ab"


def test_space_at_dict_plus_1():
    dict_size = len(DICT_SMALL)
    space_idx = dict_size + 1
    indices = [0, 1, 0, space_idx, 0, 2, 0]
    result = ctc_decode_indices(indices, DICT_SMALL)
    assert result == "a b"


def test_blank_end_convention_produces_garbage():
    dict_size = len(DICT_SMALL)
    blank_idx = dict_size
    indices = [0, 1, 0, 2, 0, 3, 0, 4, 0]
    text = ""
    decoder_table = DICT_SMALL + [" ", "__BLANK__"]
    prev = -1
    for idx in indices:
        if idx != blank_idx and idx != prev:
            if idx < len(decoder_table):
                text += decoder_table[idx]
        prev = idx
    assert text != "abcd"
    correct = ctc_decode_indices(indices, DICT_SMALL)
    assert correct == "abcd"
    assert text != correct


def test_blank_at_0_empty_indices():
    result = ctc_decode_indices([], DICT_SMALL)
    assert result == ""


def test_blank_at_0_all_blanks():
    result = ctc_decode_indices([0, 0, 0, 0], DICT_SMALL)
    assert result == ""


class TestCtcDecodeWithConf:
    def test_confidence_is_mean_of_non_blank_probs(self):
        idx = [0, 1, 0, 2, 0]
        prob = [0.99, 0.80, 0.99, 0.60, 0.99]
        text, conf = ctc_decode_with_conf(idx, prob, DICT_SMALL)
        assert text == "ab"
        assert conf == 0.70  # mean(0.80, 0.60)

    def test_confidence_excludes_space(self):
        dict_size = len(DICT_SMALL)
        space_idx = dict_size + 1
        idx = [0, 1, 0, space_idx, 0, 2, 0]
        prob = [0.99, 0.90, 0.99, 0.70, 0.99, 0.50, 0.99]
        text, conf = ctc_decode_with_conf(idx, prob, DICT_SMALL)
        assert text == "a b"
        assert conf == 0.70  # mean(0.90, 0.50) — space's 0.70 excluded

    def test_confidence_excludes_duplicates(self):
        idx = [0, 1, 1, 0, 2, 0]
        prob = [0.99, 0.80, 0.80, 0.99, 0.60, 0.99]
        text, conf = ctc_decode_with_conf(idx, prob, DICT_SMALL)
        assert text == "ab"
        assert conf == 0.70  # mean(0.80, 0.60) — dup not counted twice

    def test_confidence_zero_for_all_blanks(self):
        idx = [0, 0, 0]
        prob = [0.99, 0.99, 0.99]
        text, conf = ctc_decode_with_conf(idx, prob, DICT_SMALL)
        assert text == ""
        assert conf == 0.0
