#!/usr/bin/env python3
"""Offline UI translation contracts. Run with: python3 tools/test_localization.py

Uses only the standard library, so missing translations can be caught before the
Java/shader toolchains are installed. This does not launch or visually test Minecraft.
"""

from collections import Counter
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "src/main/resources/assets/caustica/lang"
JAVA_DIR = ROOT / "src/main/java"
OPTIONS_SOURCE = JAVA_DIR / "dev/comfyfluffy/caustica/client/RtVideoOptions.java"
PREFIX = "caustica.options."
LOCALES = {
    "de_de", "en_us", "es_es", "fr_fr", "it_it", "ja_jp", "ko_kr",
    "pt_br", "ru_ru", "zh_cn", "zh_tw",
}

# Brand/algorithm names, symbols and standard units are intentionally shared.
SHARED_KEYS = {
    PREFIX + key for key in (
        "rt.hdrHeader", "rt.dhHeader", "rt.dlssQuality.5", "rt.upscaler.dlss",
        "rt.tonemapOperator.agx", "rt.tonemapOperator.pbr_neutral",
        "rt.tonemapOperator.aces", "rt.tonemapOperator.filmic",
        "rt.tonemapOperator.psychov", "rt.reflex", "rt.reflexHeader",
        "rt.menuButton", "rt.fgEngine.fsr31", "value.seconds",
        "value.perMinute", "value.millimetres", "value.pixels", "value.nits",
    )
}
# These are also valid local words/loanwords, not untranslated English fallbacks.
LOCAL_SHARED_KEYS = {
    "de_de": {"rt.debugView.2", "rt.bloomHeader", "rt.bloomQuality.ultra"},
    "es_es": {
        "rt.generalHeader", "rt.exposureMode.manual", "rt.upscaler.fsr3",
        "rt.upscaler.xess", "rt.debugView.2", "voxy.chunks",
        "rt.bloomQuality.ultra", "rt.dofFocusMode.manual",
    },
    "fr_fr": {"rt.exposureMode.auto", "rt.dlssQuality.0", "rt.bloomQuality.ultra"},
    "it_it": {"rt.exposureMode.auto", "rt.debugView.2", "rt.bloomQuality.ultra"},
    "pt_br": {
        "sharc.header", "rt.exposureMode.manual", "rt.upscaler.fsr3",
        "rt.upscaler.xess", "rt.debugView.2", "voxy.chunks", "rt.bloomHeader",
        "rt.bloomQuality.ultra", "rt.dofFocusMode.manual",
    },
}

KEY_LITERAL = re.compile(r'"(caustica\.options\.[A-Za-z0-9_.]+)"')
TOOLTIP_HELPER = re.compile(
    r'\b(?:bool|percent|percentRange|multiplier|hundredths|exposureEvSlider|'
    r'intSlider|secondsOption|blocksOption|perMinuteOption|scaleOption|'
    r'cloudThicknessBlocks)\(\s*"(caustica\.options\.[A-Za-z0-9_.]+)"'
)
FORMAT = re.compile(r"%(?:(\d+)\$)?([A-Za-z%]|$)")
SELECTOR_VALUES = {
    "rt.dofFocusMode": ("manual", "auto"),
    "rt.exposureMode": ("auto", "manual"),
    "rt.tonemapOperator": ("agx", "pbr_neutral", "aces", "filmic", "linear", "psychov"),
    "rt.bloomQuality": ("low", "medium", "high", "ultra"),
    "rt.cloudStyle": ("classic", "volumetric"),
    "rt.dlssQuality": (3, 0, 1, 2, 5),
    "rt.dlssPreset": (0, 4, 5, 6),
    "rt.upscaler": ("none", "dlss", "fsr3", "xess"),
    "rt.debugView": tuple(range(14)),
}


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate translation key: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"Non-JSON constant: {value}")


def parse_catalog(text):
    catalog = json.loads(text, object_pairs_hook=unique_pairs, parse_constant=reject_constant)
    if not isinstance(catalog, dict) or not catalog:
        raise ValueError("A language file must be a nonempty JSON object")
    for key, value in catalog.items():
        if not key.startswith(PREFIX) or not isinstance(value, str) or not value.strip():
            raise ValueError(f"Invalid translation: {key} = {value!r}")
    return catalog


def format_arguments(text):
    """Minecraft accepts %s, positional %1$s, and escaped %%; prose % is literal."""
    arguments = Counter()
    next_argument = 1
    for match in FORMAT.finditer(text):
        position, kind = match.groups()
        if kind == "%":
            continue
        if kind != "s":
            raise ValueError(f"Unsupported translation format: {match.group()!r}")
        argument = int(position) if position else next_argument
        if argument < 1:
            raise ValueError("Translation argument indices start at 1")
        arguments[argument] += 1
        if position is None:
            next_argument += 1
    return arguments


class LocalizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogs = {
            file.stem: parse_catalog(file.read_text(encoding="utf-8"))
            for file in sorted(LANG_DIR.glob("*.json"))
        }
        cls.english = cls.catalogs["en_us"]

    def test_all_eleven_locales_are_present(self):
        self.assertEqual(LOCALES, self.catalogs.keys())

    def test_every_locale_has_all_keys(self):
        for locale, catalog in self.catalogs.items():
            with self.subTest(locale=locale):
                self.assertEqual(self.english.keys(), catalog.keys())

    def test_format_arguments_match_english(self):
        for locale, catalog in self.catalogs.items():
            for key, value in catalog.items():
                with self.subTest(locale=locale, key=key):
                    self.assertEqual(format_arguments(self.english[key]), format_arguments(value))

    def test_english_fallbacks_are_not_mistaken_for_translations(self):
        for locale, catalog in self.catalogs.items():
            if locale == "en_us":
                continue
            allowed = SHARED_KEYS | {PREFIX + key for key in LOCAL_SHARED_KEYS.get(locale, ())}
            copied = [key for key, value in catalog.items()
                      if value == self.english[key] and key not in allowed]
            with self.subTest(locale=locale):
                self.assertEqual([], copied, "Translate English text, or document genuinely shared terms")

    def test_ui_translation_references_exist(self):
        for file in sorted(JAVA_DIR.rglob("*.java")):
            source = file.read_text(encoding="utf-8")
            for key in KEY_LITERAL.findall(source):
                with self.subTest(file=file.name, key=key):
                    if key.endswith("."):
                        self.assertTrue(any(entry.startswith(key) for entry in self.english))
                    else:
                        self.assertIn(key, self.english)
            # Shared widget builders append .tooltip at runtime rather than spelling it out.
            for caption in TOOLTIP_HELPER.findall(source):
                with self.subTest(file=file.name, caption=caption):
                    self.assertIn(caption + ".tooltip", self.english)

    def test_dynamically_selected_value_labels_exist(self):
        for caption, values in SELECTOR_VALUES.items():
            for value in values:
                with self.subTest(caption=caption, value=value):
                    self.assertIn(f"{PREFIX}{caption}.{value}", self.english)

    def test_unit_labels_and_off_are_not_hardcoded(self):
        source = OPTIONS_SOURCE.read_text(encoding="utf-8")
        for literal in (
            '" blocks"', '" entries"', '" frames"', '" samples"', '"Off"',
            '" s"', '" mm"', '" px"', '" us"', '" nits"', '"/min"',
        ):
            with self.subTest(literal=literal):
                self.assertNotIn(literal, source)


class ValidatorTest(unittest.TestCase):
    def test_duplicate_keys_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            parse_catalog('{"caustica.options.test": "First", "caustica.options.test": "Second"}')

    def test_blank_and_non_string_translations_are_rejected(self):
        for value in ("", "  ", None, 1, {}, []):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_catalog(json.dumps({"caustica.options.test": value}))

    def test_invalid_json_is_rejected(self):
        for text in ('{"caustica.options.test": "Text",}', '{"caustica.options.test": NaN}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_catalog(text)

    def test_positional_arguments_can_be_reordered(self):
        self.assertEqual(format_arguments("%s / %s"), format_arguments("%2$s / %1$s"))

    def test_literal_percentages_do_not_consume_arguments(self):
        self.assertEqual(Counter({1: 1}), format_arguments("%s blocks, 100% / 0% / %%"))

    def test_missing_and_repeated_arguments_are_distinguished(self):
        self.assertNotEqual(format_arguments("%s blocks"), format_arguments("blocks"))
        self.assertNotEqual(format_arguments("%s blocks"), format_arguments("%1$s / %1$s"))

    def test_invalid_formats_are_rejected(self):
        for text in ("%d", "%0$s", "%q", "trailing %"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                format_arguments(text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
