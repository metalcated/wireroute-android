"""Build a silent, 30-second Play preview from genuine Android screen captures.

Requires ffmpeg. Does not synthesize UI, connect to a VPN, or publish video.
Override LISTING_FONT with a local sans-serif font when not running on macOS.
"""
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KIT = ROOT / "store-listing"
GRAPHICS = KIT / "en-US/graphics"
WORK = ROOT / "build/store-listing-video"
OUTPUT = KIT / "en-US/video/wireroute-preview-1080p.mp4"
FONT = os.environ.get("LISTING_FONT", "/System/Library/Fonts/Avenir Next.ttc")


def draw(value, x, y, size, color="F4F7FB"):
    # All copy is fixed below; reject ffmpeg filter metacharacters.
    if any(c in value for c in "':;\\"):
        raise ValueError("Unsupported character in preview text")
    return f"drawtext=fontfile='{FONT}':text='{value}':x={x}:y={y}:fontsize={size}:fontcolor=0x{color}"


def encode(inputs, graph, target, duration):
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *inputs,
        "-filter_complex", graph, "-map", "[out]", "-t", str(duration),
        "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "19",
        "-pix_fmt", "yuv420p", "-r", "30", "-movflags", "+faststart", str(target)
    ], check=True)


def main():
    if not Path(FONT).is_file():
        raise SystemExit("Set LISTING_FONT to an installed sans-serif font.")
    WORK.mkdir(parents=True, exist_ok=True)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    parts = []
    intro = WORK / "00-intro.mp4"
    encode(["-loop", "1", "-i", str(GRAPHICS / "feature-graphic-1024x500.png")],
           "[0:v]scale=1920:938,pad=1920:1080:0:71:0x111B2A,setsar=1[out]", intro, 3)
    parts.append(intro)
    slides = [
        ("01-home.png", ["Your network,", "within reach."],
         ["Connect to your own compatible endpoint.", "Including WireGuard on RouterOS 7."]),
        ("02-profiles.png", ["A place for", "every profile."],
         ["Keep home, office, and travel connections ready.", "Import a configuration or scan a QR code."]),
        ("03-profile-detail.png", ["Choose what", "takes the tunnel."],
         ["Split or full tunnel routing, per profile.", "Your routes stay under your control."]),
        ("05-encrypted-dns.png", ["Your DNS.", "Your choice."],
         ["Use profile DNS or encrypted DNS-over-HTTPS.", "Choose a preset or configure your own resolver."]),
        ("07-activity.png", ["See connection", "activity."],
         ["Review transfer rates and recent connections.", "Activity history stays on your device."]),
        ("08-settings.png", ["Make WireRoute", "your own."],
         ["Nordic Blue or Android System colors.", "Local history, exports, and diagnostics."]),
    ]
    for index, (filename, headline, detail) in enumerate(slides, start=1):
        screenshot = GRAPHICS / "phone-screenshots" / filename
        target = WORK / f"{index:02d}-screen.mp4"
        filters = [
            draw("WireRoute", 130, 126, 52),
            "drawbox=x=130:y=250:w=74:h=5:color=0x4C83F3:t=fill",
            draw(headline[0], 130, 355, 74),
            draw(headline[1], 130, 447, 74, "4C83F3"),
            draw(detail[0], 130, 594, 31, "BCCBDD"),
            draw(detail[1], 130, 643, 31, "BCCBDD"),
            draw("OPEN-SOURCE ANDROID VPN CLIENT", 130, 866, 24, "BCCBDD"),
            draw("Example profiles shown. A working VPN endpoint is required.", 130, 920, 22, "BCCBDD"),
        ]
        graph = ("[0:v]setsar=1[bg];[1:v]scale=540:960,setsar=1[screen];"
                 "[bg][screen]overlay=1250:60," + ",".join(filters) + "[out]")
        encode(["-f", "lavfi", "-i", "color=c=0x111B2A:s=1920x1080:r=30",
                "-loop", "1", "-i", str(screenshot)], graph, target, 4)
        parts.append(target)
        print(f"Rendered {filename}", flush=True)
    outro = WORK / "07-outro.mp4"
    graph = ("[0:v]setsar=1[bg];[1:v]scale=248:248,setsar=1[icon];"
             "[bg][icon]overlay=130:365," + ",".join([
                 draw("WireRoute", 454, 337, 94),
                 draw("Your networks. Your routes.", 460, 476, 57, "4C83F3"),
                 draw("WireGuard-compatible VPN client for Android", 463, 579, 34, "BCCBDD"),
                 draw("Bring your own VPN endpoint.", 463, 658, 30, "BCCBDD"),
             ]) + "[out]")
    encode(["-f", "lavfi", "-i", "color=c=0x111B2A:s=1920x1080:r=30",
            "-loop", "1", "-i", str(GRAPHICS / "app-icon-512.png")], graph, outro, 3)
    parts.append(outro)
    # Runtime-generated concat manifest; only the encoded, task-owned clips are included.
    playlist = WORK / "parts.txt"
    playlist.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts))
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat",
                    "-safe", "0", "-i", str(playlist), "-c", "copy",
                    "-movflags", "+faststart", str(OUTPUT)], check=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
