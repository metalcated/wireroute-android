"""Validate the Play upload kit and package only publication-ready assets.

The only image conversion removes a fully opaque alpha channel from screenshots.
It preserves the original capture and verifies that every RGB pixel is unchanged.
"""
import hashlib
import json
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KIT = ROOT / "store-listing"
UPLOAD = KIT / "en-US"
BUILD = ROOT / "build"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def png_info(path):
    data = path.read_bytes()
    require(data[:8] == b"\x89PNG\r\n\x1a\n", f"Not a PNG: {path}")
    width, height, depth, color = struct.unpack(">IIBB", data[16:26])
    require(depth == 8 and color in (2, 6), f"Expected 8-bit RGB/RGBA: {path}")
    return width, height, color


def raw(path, pixel_format, *filters):
    return subprocess.check_output([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path),
        *filters, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixel_format, "-"
    ])


def normalize_screenshot(path):
    _, _, color = png_info(path)
    if color == 2:
        return
    alpha = raw(path, "gray", "-vf", "alphaextract")
    require(alpha and min(alpha) == max(alpha) == 255, f"Non-opaque screenshot: {path}")
    original_rgb = hashlib.sha256(raw(path, "rgb24")).hexdigest()
    digest = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    relative = path.relative_to(UPLOAD)
    backup = BUILD / "store-listing-raw" / relative.parent / f"{path.stem}-{digest}.png"
    backup.parent.mkdir(parents=True, exist_ok=True)
    if not backup.exists():
        shutil.copy2(path, backup)
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(backup),
        "-frames:v", "1", "-pix_fmt", "rgb24", str(path)
    ], check=True)
    require(original_rgb == hashlib.sha256(raw(path, "rgb24")).hexdigest(),
            f"RGB pixels changed during conversion: {path}; original retained at {backup}")


def main():
    character_counts = {}
    for filename, limit in (("app-name.txt", 30), ("short-description.txt", 80),
                            ("full-description.txt", 4000), ("release-notes.txt", 500)):
        value = (UPLOAD / filename).read_text().strip()
        require(0 < len(value) <= limit, f"{filename} must contain 1–{limit} characters")
        character_counts[filename] = {"characters": len(value), "limit": limit}
    categories = {
        "phone-screenshots": ((1440, 2560), 8),
        "tablet-7-inch": ((1440, 2560), 5),
        "tablet-10-inch": ((1800, 3200), 5),
    }
    for category, (dimensions, expected_count) in categories.items():
        screenshots = sorted((UPLOAD / "graphics" / category).glob("*.png"))
        require(len(screenshots) == expected_count, f"Unexpected screenshot count for {category}")
        for path in screenshots:
            normalize_screenshot(path)
            width, height, color = png_info(path)
            require((width, height) == dimensions and color == 2, f"Wrong format: {path}")
            require(width * 16 == height * 9 and 1080 <= min(width, height)
                    and max(width, height) <= 3840, f"Wrong screenshot dimensions: {path}")
            require(path.stat().st_size < 8_000_000, f"Screenshot exceeds 8 MB: {path}")
        print(f"Validated {len(screenshots)} {category} images", flush=True)
    icon = UPLOAD / "graphics/app-icon-512.png"
    feature = UPLOAD / "graphics/feature-graphic-1024x500.png"
    require(png_info(icon) == (512, 512, 6) and icon.stat().st_size < 1_000_000,
            "Icon must be a 512px 32-bit PNG under 1 MB")
    require(png_info(feature) == (1024, 500, 2) and feature.stat().st_size < 15_000_000,
            "Feature graphic must be a 1024x500 RGB PNG under 15 MB")
    raw(icon, "rgba")
    raw(feature, "rgb24")
    video = UPLOAD / "video/wireroute-preview-1080p.mp4"
    probe = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries",
        "format=duration:stream=codec_name,width,height,pix_fmt,r_frame_rate",
        "-of", "json", str(video)
    ], text=True))
    stream = probe["streams"][0]
    require(stream["codec_name"] == "h264" and stream["width"] == 1920
            and stream["height"] == 1080 and stream["pix_fmt"] == "yuv420p"
            and stream["r_frame_rate"] == "30/1"
            and float(probe["format"]["duration"]) == 30, "Unexpected preview video format")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(video),
                    "-f", "null", "-"], check=True)
    paths = [KIT / "README.md", KIT / "source/feature-graphic-prompt.txt",
             *sorted(p for p in UPLOAD.rglob("*") if p.is_file())]
    assets = []
    for path in paths:
        require(path.suffix in {".txt", ".md", ".png", ".mp4"}, f"Unexpected upload file: {path}")
        data = path.read_bytes()
        entry = {"file": path.relative_to(KIT).as_posix(), "bytes": len(data),
                 "sha256": hashlib.sha256(data).hexdigest()}
        if path.suffix == ".png":
            w, h, c = png_info(path)
            entry.update(width=w, height=h, color="RGB" if c == 2 else "RGBA")
        assets.append(entry)
    manifest = {
        "locale": "en-US", "theme": "Nordic Blue", "application_id": "com.metalcated.wireroute",
        "captured_version": "1.0.20260315 (519)", "capture_base_commit": "a3e387dd",
        "validation": "passed", "character_counts": character_counts,
        "video": probe, "assets": assets,
    }
    manifest_path = KIT / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    BUILD.mkdir(parents=True, exist_ok=True)
    archive = BUILD / "WireRoute-Google-Play-Listing.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for path in [*paths, manifest_path]:
            output.write(path, "WireRoute-Google-Play-Listing/" + path.relative_to(KIT).as_posix())
    with zipfile.ZipFile(archive) as result:
        require(result.testzip() is None, "Archive CRC validation failed")
    print(json.dumps(character_counts, indent=2))
    print(f"Validated archive: {archive} ({archive.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
