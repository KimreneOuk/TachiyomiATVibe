from server import parse_numbered_lines, ocr_artifact_sanitize


class TestParseNumberedLines:
    def test_accept_well_formed(self):
        raw = "[0] hello\n[1] world\n[2] foo"
        result = parse_numbered_lines(raw, 3)
        assert result == ["hello", "world", "foo"]

    def test_out_of_range_rejected(self):
        raw = "[0] hello\n[5] out\n[1] world"
        result = parse_numbered_lines(raw, 2)
        assert result == ["hello", "world"]

    def test_duplicate_first_wins(self):
        raw = "[0] first\n[0] second\n[1] third"
        result = parse_numbered_lines(raw, 2)
        assert result == ["first", "third"]

    def test_blank_rejected(self):
        raw = "[0] hello\n[1] \n[2] world"
        result = parse_numbered_lines(raw, 3)
        assert result == ["hello", "", "world"]

    def test_no_prefix_no_fallback(self):
        raw = "hello\nworld\nfoo"
        result = parse_numbered_lines(raw, 3)
        assert result == ["", "", ""]

    def test_empty_input(self):
        result = parse_numbered_lines("", 3)
        assert result == ["", "", ""]

    def test_cjk_leak_rejected_for_non_cjk(self):
        raw = "[0] hello\n[1] こんにちは\n[2] world"
        result = parse_numbered_lines(raw, 3, allow_cjk=False)
        assert result == ["hello", "", "world"]

    def test_cjk_leak_accepted_for_cjk(self):
        raw = "[0] hello\n[1] こんにちは\n[2] world"
        result = parse_numbered_lines(raw, 3, allow_cjk=True)
        assert result == ["hello", "こんにちは", "world"]

    def test_mixed_prefix_variants_not_accepted(self):
        raw = "0. hello\n1: world\n[2] correct"
        result = parse_numbered_lines(raw, 3)
        assert result == ["", "", "correct"]

    def test_multiline_brackets(self):
        raw = "[0] line one\n[1] line two\nmore text"
        result = parse_numbered_lines(raw, 2)
        assert result == ["line one", "line two"]

    def test_repeated_blank_dominant(self):
        raw = "[0] \n[1] \n[2] real"
        result = parse_numbered_lines(raw, 3)
        assert result == ["", "", "real"]


class TestOcrArtifactSanitize:
    def test_strips_n0(self):
        assert ocr_artifact_sanitize("N0 hello") == " hello"

    def test_strips_ndeg(self):
        assert ocr_artifact_sanitize("N° world") == " world"

    def test_strips_nord(self):
        assert ocr_artifact_sanitize("Nº test") == " test"

    def test_strips_numero(self):
        assert ocr_artifact_sanitize("№ symbol") == " symbol"

    def test_strips_fullwidth(self):
        assert ocr_artifact_sanitize("Ｎ０ foo") == " foo"

    def test_no_artifact_unchanged(self):
        assert ocr_artifact_sanitize("hello world") == "hello world"

    def test_empty_string(self):
        assert ocr_artifact_sanitize("") == ""
