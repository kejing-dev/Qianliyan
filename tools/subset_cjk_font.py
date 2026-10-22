#!/usr/bin/env python
"""Subset Noto Sans SC (variable, OFL 1.1) to the characters the app's Chinese copy uses.

Re-run whenever the zh strings change:

    python tools/subset_cjk_font.py

Input : C:\\Windows\\Fonts\\NotoSansSC-VF.ttf  (override with --source)
Output: app/src/main/res/font/noto_sans_sc_subset.ttf  (the wght axis is kept)
        third_party/noto-sans-sc/OFL.txt + SUBSET.txt (what was included)

Latin letters and digits are NOT taken from this font: Noto Sans (res/font/noto_sans_*.ttf) comes
first in the typeface, this subset is its fallback, and the system font is the fallback after that.
"""
import argparse
import os
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
STRING_FILES = [
    os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
]
# CJK punctuation and the symbols the UI draws next to readings (grade glyphs, warnings, arrows).
EXTRA = (
    "，。、；：？！…—·（）《》〈〉“”‘’「」『』【】｜　～"
    "◆●○▲△■□◌✓✋⦸↔↕←→↑↓′″±×÷°‰№"
    "一二三四五六七八九十百千万零两个米厘毫英尺寸"
)
OFL_HEADER = "Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'.\n\n"
OFL_BODY = """This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
"""


def used_characters():
    chars = set(EXTRA)
    for path in STRING_FILES:
        text = open(path, encoding="utf-8").read()
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        for value in re.findall(r">([^<]*)<", text):
            chars.update(ch for ch in value if ord(ch) > 0x7F)
    chars.discard("\n")
    return chars


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=r"C:\Windows\Fonts\NotoSansSC-VF.ttf")
    ap.add_argument("--out", default=os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_sc_subset.ttf"))
    args = ap.parse_args()

    chars = used_characters()
    font = TTFont(args.source)
    cmap = font.getBestCmap()
    present = sorted(ch for ch in chars if ord(ch) in cmap)
    missing = sorted(ch for ch in chars if ord(ch) not in cmap)

    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.recalc_bounds = True
    options.drop_tables += ["DSIG"]
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=[ord(ch) for ch in present])
    subsetter.subset(font)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    font.save(args.out)

    check = TTFont(args.out)
    axes = [(a.axisTag, a.minValue, a.defaultValue, a.maxValue) for a in check["fvar"].axes] if "fvar" in check else []
    third = os.path.join(ROOT, "third_party", "noto-sans-sc")
    os.makedirs(third, exist_ok=True)
    with open(os.path.join(third, "OFL.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(OFL_HEADER + OFL_BODY)
    with open(os.path.join(third, "SUBSET.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("Noto Sans SC (variable), subset for Spatial Ruler. A Modified Version under the SIL OFL 1.1.\n")
        f.write(f"source: {os.path.basename(args.source)}  {font['name'].getDebugName(5)}\n")
        f.write(f"axes kept: {axes}\n")
        f.write(f"glyphs: {len(check.getGlyphOrder())}  characters: {len(present)}\n")
        f.write("characters: " + "".join(present) + "\n")
        f.write("not in the source font (left to the system fallback): " + " ".join(f"U+{ord(c):04X}" for c in missing) + "\n")
    size = os.path.getsize(args.out)
    sys.stdout.buffer.write(
        f"wrote {args.out} ({size} bytes, {len(check.getGlyphOrder())} glyphs, {len(present)} chars, axes {axes}); "
        f"missing in source: {[hex(ord(c)) for c in missing]}\n".encode("utf-8")
    )


if __name__ == "__main__":
    main()
