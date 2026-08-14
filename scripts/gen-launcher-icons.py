#!/usr/bin/env python3
"""Generate launcher icons.

  ic_launcher            rounded square + empty padding
  ic_launcher_round      perfect circle (full bleed)
  ic_launcher_foreground rounded square in the adaptive safe zone

Writes lossless WebP. Run:

  python3 scripts/gen-launcher-icons.py
  python3 scripts/gen-launcher-icons.py /path/to/source.png
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

DEFAULT_SRC = Path(
    "/Users/shirasawa/Pictures/out 5/306dc2bc-57ad-4f99-bc52-02a24f52e97c.png"
)
RES = Path(__file__).resolve().parents[1] / "app/src/main/res"

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

LEGACY_RATIO = 0.84
FOREGROUND_RATIO = 0.66
CORNER_RADIUS = 0.22
AA_PX = 1.75
SUPERSAMPLE = 4

WEBP_KW = dict(format="WEBP", lossless=True, quality=100, method=6, exact=True)


def sample_backdrop(src: Image.Image) -> tuple[int, int, int]:
    arr = np.asarray(src.convert("RGB"), dtype=np.int32)
    h, w = arr.shape[:2]
    band = max(4, min(h, w) // 40)
    strips = np.concatenate(
        [
            arr[:band].reshape(-1, 3),
            arr[-band:].reshape(-1, 3),
            arr[:, :band].reshape(-1, 3),
            arr[:, -band:].reshape(-1, 3),
        ]
    )
    c = np.median(strips, axis=0).astype(np.int32)
    return int(c[0]), int(c[1]), int(c[2])


def _grid(size: int) -> tuple[np.ndarray, np.ndarray]:
    yy, xx = np.mgrid[:size, :size]
    c = (size - 1) / 2.0
    return xx.astype(np.float64) - c, yy.astype(np.float64) - c


def sdf_to_alpha(dist: np.ndarray, aa: float = AA_PX) -> np.ndarray:
    return np.clip(0.5 - dist / aa, 0.0, 1.0)


def circle_alpha(size: int, inset: float, aa: float) -> np.ndarray:
    dx, dy = _grid(size)
    radius = size / 2.0 - inset
    dist = np.sqrt(dx * dx + dy * dy) - radius
    return sdf_to_alpha(dist, aa)


def rounded_rect_alpha(size: int, radius_ratio: float, inset: float, aa: float) -> np.ndarray:
    dx, dy = _grid(size)
    radius = min(size / 2.0 - inset, size * radius_ratio)
    half = size / 2.0 - inset - radius
    ax = np.abs(dx) - half
    ay = np.abs(dy) - half
    dist = np.sqrt(np.maximum(ax, 0.0) ** 2 + np.maximum(ay, 0.0) ** 2)
    dist += np.minimum(np.maximum(ax, ay), 0.0) - radius
    return sdf_to_alpha(dist, aa)


def apply_alpha(rgb: Image.Image, alpha: np.ndarray) -> Image.Image:
    rgba = rgb.convert("RGBA")
    a = Image.fromarray(np.clip(alpha * 255.0, 0, 255).astype(np.uint8), mode="L")
    rgba.putalpha(a)
    return rgba


def resize_rgb(src: Image.Image, size: int) -> Image.Image:
    return src.convert("RGB").resize((size, size), Image.Resampling.LANCZOS)


def downscale_premultiplied(im: Image.Image, size: int) -> Image.Image:
    arr = np.asarray(im.convert("RGBA")).astype(np.float32) / 255.0
    a = arr[..., 3:4]
    premul = np.concatenate([arr[..., :3] * a, a], axis=2)
    channels = [
        Image.fromarray(np.clip(premul[..., i] * 255.0, 0, 255).astype(np.uint8), "L").resize(
            (size, size), Image.Resampling.LANCZOS
        )
        for i in range(4)
    ]
    stacked = np.stack([np.asarray(c) for c in channels], axis=2).astype(np.float32) / 255.0
    alpha = stacked[..., 3:4]
    rgb = np.divide(
        stacked[..., :3],
        np.clip(alpha, 1e-6, 1.0),
        out=np.zeros_like(stacked[..., :3]),
        where=alpha > 1e-4,
    )
    out = np.dstack([np.clip(rgb, 0, 1), np.clip(stacked[..., 3], 0, 1)])
    return Image.fromarray(np.clip(out * 255.0, 0, 255).astype(np.uint8), "RGBA")


def padded_rounded(src: Image.Image, canvas: int, inner_ratio: float) -> Image.Image:
    inner = max(8, int(round(canvas * inner_ratio)))
    big = inner * SUPERSAMPLE
    logo = apply_alpha(
        resize_rgb(src, big),
        rounded_rect_alpha(
            big,
            CORNER_RADIUS,
            inset=0.75 * SUPERSAMPLE,
            aa=AA_PX * SUPERSAMPLE,
        ),
    )
    logo = downscale_premultiplied(logo, inner)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    xy = ((canvas - inner) // 2, (canvas - inner) // 2)
    out.alpha_composite(logo, xy)
    return out


def perfect_circle(src: Image.Image, canvas: int) -> Image.Image:
    big = canvas * SUPERSAMPLE
    img = apply_alpha(
        resize_rgb(src, big),
        circle_alpha(big, inset=1.0 * SUPERSAMPLE, aa=AA_PX * SUPERSAMPLE),
    )
    return downscale_premultiplied(img, canvas)


def save_webp(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, **WEBP_KW)


def write_background_color(rgb: tuple[int, int, int]) -> None:
    path = RES / "values" / "ic_launcher_background.xml"
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}</color>\n'
        "</resources>\n",
        encoding="utf-8",
    )


def remove_png_icons() -> None:
    for path in RES.glob("mipmap-*/ic_launcher*.png"):
        path.unlink()
        print(f"removed {path.name}")


def main() -> int:
    src_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SRC
    if not src_path.is_file():
        print(f"missing source: {src_path}", file=sys.stderr)
        return 1

    src = Image.open(src_path).convert("RGB")
    backdrop = sample_backdrop(src)
    print(f"source {src_path}")
    print(f"backdrop #{backdrop[0]:02X}{backdrop[1]:02X}{backdrop[2]:02X}")

    for dens, size in LEGACY.items():
        save_webp(padded_rounded(src, size, LEGACY_RATIO), RES / f"mipmap-{dens}" / "ic_launcher.webp")
        save_webp(perfect_circle(src, size), RES / f"mipmap-{dens}" / "ic_launcher_round.webp")

    for dens, size in FOREGROUND.items():
        save_webp(
            padded_rounded(src, size, FOREGROUND_RATIO),
            RES / f"mipmap-{dens}" / "ic_launcher_foreground.webp",
        )

    write_background_color(backdrop)
    remove_png_icons()
    print("wrote lossless WebP launcher icons")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
