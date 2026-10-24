#!/usr/bin/env python3
"""
Extracts extra French words from the French Wiktionary (kaikki.org extract, CC BY-SA), to complement
the Grammalecte lexicon. Reads the JSONL on stdin, writes one normalized word per line on stdout.

    curl -sL https://kaikki.org/dictionary/downloads/fr/fr-extract.jsonl.gz | gunzip \
      | python3 -I tools/wiktionnaire.py | gzip > dictionaries/fr-wiktionnaire.txt.gz

Kept: lemmas that are common nouns, adjectives, verbs, adverbs or interjections, translated into other
languages (a good sign of an established word: at least one language, three for verbs since each verb brings
some fifty conjugated forms), not demonyms, acronyms, abbreviations, symbols, SMS spellings, names or errors;
plus their inflected forms (plurals, feminines, conjugations).
Only words of 5 letters or more: short words decide the game and are too noisy here, they are curated
by hand (dictionaries/additions.txt and the private lists).
"""
import json
import re
import sys
import unicodedata

POS = {"noun", "adj", "verb", "adv", "intj"}
BAD_CATEGORY = re.compile(r"Gentilés|Sigles|Abréviations|Acronymes|Symboles|SMS|Noms de famille|Prénoms|Erreurs|Fautes", re.I)
BAD_TAGS = {"abbreviation", "initialism", "acronym", "symbol"}
MIN_LENGTH = 5
MIN_TRANSLATION_LANGUAGES = {"verb": 3}  # default 1
LIGATURES = {"œ": "oe", "æ": "ae"}


def normalize(word):
    if not word or not word[0].islower():
        return None
    word = "".join(LIGATURES.get(c, c) for c in word)
    word = "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")
    if not (word.isascii() and word.isalpha() and MIN_LENGTH <= len(word) <= 15):
        return None
    return word.upper()


lemmas = set()       # original spellings of kept lemmas
own_forms = {}       # lemma -> forms listed in its own article (plural, feminine…)
form_entries = []    # (form, [lemmas it is a form of]) for separate form articles (conjugations…)

for line in sys.stdin:
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if e.get("lang_code") != "fr" or e.get("pos") not in POS:
        continue
    word = e.get("word")
    senses = e.get("senses", [])
    targets = [f.get("word") for s in senses for f in s.get("form_of", []) if f.get("word")]
    if targets:
        form_entries.append((word, targets))
        continue
    if any(BAD_CATEGORY.search(c) for c in e.get("categories", [])):
        continue
    tags = [set(s.get("tags", [])) for s in senses]
    if tags and all(t & BAD_TAGS for t in tags):
        continue
    languages = {t.get("lang") for t in e.get("translations", []) if t.get("lang")}
    if len(languages) < MIN_TRANSLATION_LANGUAGES.get(e["pos"], 1):
        continue
    lemmas.add(word)
    own_forms.setdefault(word, []).extend(f.get("form") for f in e.get("forms", []) if f.get("form"))

words = set()
for lemma in lemmas:
    for w in [lemma] + own_forms.get(lemma, []):
        n = normalize(w)
        if n:
            words.add(n)
for form, targets in form_entries:
    if any(t in lemmas for t in targets):
        n = normalize(form)
        if n:
            words.add(n)

sys.stdout.write("\n".join(sorted(words)) + "\n")
print(f"{len(lemmas)} lemmas, {len(words)} words", file=sys.stderr)
