#!/usr/bin/env python3
"""Verify the rollback package and materialize the exact U50C board inputs."""
import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "baseline"
EVIDENCE = "c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad"
CPU = "5ec35882a6eb4040631c1cf9d322a754bd435907"
ROLLBACK = BASE / "rollback"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(path, expected):
    actual = digest(path)
    if actual != expected:
        raise ValueError(f"{path}: expected {expected}, got {actual}")
    return actual


def prepare(out, aurora):
    subprocess.run(["sha256sum", "--quiet", "-c", "files.sha256"], cwd=BASE, check=True)
    git = ["git", "-C", str(aurora)]
    if subprocess.check_output(git + ["rev-parse", "HEAD"], text=True).strip() != EVIDENCE:
        raise ValueError("integration worktree HEAD must match the frozen evidence commit")
    subprocess.run(git + ["diff", "--exit-code", CPU, EVIDENCE, "--", ".", ":(exclude)reports"], check=True)
    manifest = json.loads((ROLLBACK / "manifest.json").read_text())
    archive = ROLLBACK / "remote-evidence.tar.gz"
    check(archive, manifest["remote"]["evidence_archive_sha256"])
    build = manifest["build"]
    artifacts = {
        "generated/Aurora.v": ("generated/Aurora.v", manifest["source"]["generated_rtl_sha256"]),
        "vivado/aurora_u50c.runs/impl_1/u50c_top.bit": ("u50c_top.bit", build["bitstream_sha256"]),
        "u50c_top.ltx": ("u50c_top.ltx", build["ltx_sha256"]),
    }
    out.mkdir(parents=True, exist_ok=True)
    hashes = {}
    with tarfile.open(archive) as tar:
        request = json.load(tar.extractfile("request.json"))
        for member, (name, expected) in artifacts.items():
            dest = out / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open("wb") as target:
                shutil.copyfileobj(tar.extractfile(member), target)
            hashes[name] = check(dest, expected)
    for field, name in [("bit", "u50c_top.bit"), ("ltx", "u50c_top.ltx")]:
        if hashes[name] != request[field + "_sha256"]:
            raise ValueError(f"archived board request disagrees on {field}")
    check(ROLLBACK / "linux-uart.txt", manifest["linux"]["uart_transcript_sha256"])
    image = aurora / "sim/linux-boot-runs/v0.3/image/ram.dat"
    hashes["ram.dat"] = check(image, request["image_sha256"])
    shutil.copyfile(image, out / "ram.dat")
    for archive_name, subdir in [
        ("au50_boardfiles_v1_3_20211104.zip", "board_repo"),
        ("alveo-u50-xdc_20210505.zip", "vendor_xdc"),
    ]:
        with zipfile.ZipFile(BASE / "vendor" / archive_name) as vendor:
            vendor.extractall(out / subdir)
    result = {"evidence_commit": EVIDENCE, "cpu_commit": CPU, "sha256": hashes,
              "part": build["part"], "vivado": build["vivado"], "zero_ranges": request["zero_ranges"],
              "platform_lock_sha256": digest(BASE / "files.sha256")}
    (out / "manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "build/baseline")
    parser.add_argument("--aurora", type=Path, default=ROOT / ".worktrees/aurora")
    args = parser.parse_args()
    print(json.dumps(prepare(args.out.resolve(), args.aurora.resolve()), indent=2))
