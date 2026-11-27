"""Time matched shader variants with hardware counters and retain the GPU readbacks."""
import base64
import gzip
import hashlib
import json
import math
import re
import struct
from pathlib import Path

from PIL import Image


def read_uart_file(command, source, size):
    """Read bounded UART bursts and hash-check both individual chunks and the file."""
    if not re.fullmatch(r"/[A-Za-z0-9._/-]+", source):
        raise ValueError("invalid readback path")
    digest = re.search(r"(?m)^([0-9a-f]{64})  ", command("/bin/busybox sha256sum "+source)).group(1)
    for compressed in (True, False):
        path = "/tmp/mgpu-xfer.gz" if compressed else source
        if compressed:
            command("/bin/busybox gzip -c "+source+" > "+path)
        count = int(next(t for t in command("/bin/busybox wc -c < "+path).splitlines() if t.strip().isdigit()))
        raw = bytearray()
        chunk_size = 24 * 1024
        unit = 1536
        while len(raw) < count:
            offset = len(raw)
            request = ("/bin/busybox dd if={} of=/tmp/mgpu-xfer.part bs={} skip={} count={} 2>/dev/null"
                       " && /bin/busybox sha256sum /tmp/mgpu-xfer.part && /bin/busybox base64 /tmp/mgpu-xfer.part"
                       ).format(path,unit,offset//unit,chunk_size//unit)
            for attempt in range(4):
                text = command(request)
                match = re.search(r"(?m)^([0-9a-f]{64})  /tmp/mgpu-xfer.part$",text)
                if not match:
                    continue
                encoded = "".join(line for line in text[match.end():].splitlines()
                                  if re.fullmatch(r"[A-Za-z0-9+/]+=*",line))
                try:
                    block = base64.b64decode(encoded,validate=True)
                except ValueError:
                    continue
                if len(block)==min(chunk_size,count-offset) and hashlib.sha256(block).hexdigest()==match[1]:
                    raw.extend(block)
                    break
            else:
                if chunk_size == unit:
                    raise ValueError("UART chunk hash failed at {} in {}".format(offset,path))
                chunk_size //= 2
        try:
            data = gzip.decompress(raw) if compressed else bytes(raw)
        except (OSError, EOFError):
            continue
        if len(data)==size and hashlib.sha256(data).hexdigest()==digest:
            return data
    raise ValueError("UART file hash failed for "+source)


def paired_graphics(command, jobs, info, out, rays_only=False):
    out.mkdir(parents=True, exist_ok=False)
    results = {"software": {}, "hardware": {}}
    width, height = 320, 240
    for mode in (() if rays_only else ("software","hardware")):
        folder = out/mode/"twin_cubes"
        folder.mkdir(parents=True)
        rows = jobs("twin_cubes "+mode, "render /gpu/twin_cubes.scene /tmp/twin" +
                    (" software" if mode == "software" else ""), 1200)
        frame = rows[-1]
        if frame["pixels"] != width*height or frame["triangles"] != 24 or frame["render_cycles"] != sum(r["cycles"] for r in rows[:-1]):
            raise ValueError("incomplete raster stages")
        errors = {}
        for name in ("color.rgba","depth.f32","winner.u32"):
            raw = read_uart_file(command,"/tmp/twin/"+name,width*height*4)
            (folder/name).write_bytes(raw)
            ref = gzip.decompress((Path(__file__).resolve().parent/"references"/(name+".gz")).read_bytes())
            if len(raw) != len(ref):
                raise ValueError("different reference dimensions")
            if name == "depth.f32":
                values = [a[0] for a in struct.iter_unpack("<f",raw)]
                if not all(math.isfinite(z) for z in values):
                    raise ValueError("non-finite depth")
                errors[name] = max(abs(z-r[0]) for z,r in zip(values,struct.iter_unpack("<f",ref)))
                if errors[name] > 2e-6:
                    raise ValueError("depth differs from CPU reference")
            elif name == "color.rgba":
                errors[name] = max(abs(a-b) for a,b in zip(raw,ref))
                if errors[name] > 1:
                    raise ValueError("color differs from CPU reference")
            else:
                errors[name] = sum(a != b for a,b in zip(struct.iter_unpack("<I",raw),struct.iter_unpack("<I",ref)))
                if errors[name]:
                    raise ValueError("triangle ownership differs from CPU reference")
        Image.frombytes("RGBA",(width,height),(folder/"color.rgba").read_bytes()).save(str(folder/"color.png"))
        results[mode] = {"raster":frame, "stages":rows[:-1], "gpu_seconds":frame["render_cycles"]/1e8, "reference_errors":errors}

    # Ray tracing uses programmable SIMT with software math or an SFU instruction.
    # Layout is identical in the two jobs. All pixels are contiguous in the output.
    width,height = 256,192
    code,args,dst = 4096,8192,65536
    base = info["dma"]
    reference = (Path(__file__).resolve().parent/"gpu/ray-reference.rgba").read_bytes()
    digest = hashlib.sha256(reference).hexdigest()
    if (len(reference) != width*height*4 or
            not re.search(r"(?m)^" + digest + r"  /gpu/ray-reference.rgba$",
                          command("/bin/busybox sha256sum /gpu/ray-reference.rgba"))):
        raise ValueError("ray reference does not match the verified Linux payload")
    for mode,kernel in (("software","software"),("hardware","sfu")):
        descriptor = "/tmp/ray-desc"
        def upload_words(path, offset, *words):
            packed = struct.pack("<"+"I"*len(words), *words)
            command("echo "+base64.b64encode(packed).decode()+" | /bin/busybox base64 -d > "+path+
                    " && /bin/mgpu upload "+str(offset)+" "+path)
        program = "/gpu/ray-"+kernel+".bin"
        response = command("/bin/busybox wc -c < "+program)
        n = int(next(t.strip() for t in response.splitlines() if t.strip().isdigit()))
        if n % 4 or not 0 < n <= 4096:
            raise ValueError("bad ray program length")
        command("/bin/mgpu upload 4096 "+program)
        upload_words("/tmp/ray-arg", args, base+dst)
        command("/bin/mgpu upload 8196 /gpu/ray-params.bin")
        upload_words(descriptor,0,0x3144474d,0x10000,base+code,n//4,base+args,width*height//32,32,0)
        command("/bin/busybox dd if=/dev/zero of=/tmp/ray-empty bs=1024 count=192 && /bin/mgpu upload 65536 /tmp/ray-empty")
        row, = jobs("dense rays "+mode,"submit 0 65535 8192 672",1200)
        if row["fault"] or sum(sm[1] for sm in row["sm"]) != width*height//32:
            raise ValueError("incomplete ray dispatch")
        command("/bin/mgpu download 65536 196608 /tmp/ray-frame")
        data = read_uart_file(command,"/tmp/ray-frame",width*height*4)
        folder = out/mode
        folder.mkdir(exist_ok=True)
        (folder/"ray.rgba").write_bytes(data)
        Image.frombytes("RGBA",(width,height),data).save(str(folder/"ray.png"))
        error = max(abs(a-b) for a,b in zip(data,reference))
        if error > 1:
            raise ValueError("ray color error {} in {}".format(error, mode))
        results[mode]["ray"] = dict(row, width=width,height=height,spheres=20,max_channel_error=error,
                                    gpu_seconds=row["cycles"]/1e8)
    results["speedup"] = {label:results["software"][label][key]/results["hardware"][label][key]
                          for label,key in (("ray","cycles"),("raster","render_cycles"))
                          if label in results["software"]}
    (out/"results.json").write_text(json.dumps(results,indent=2)+"\n")


def indexed_scene(command, jobs, scene, out):
    from uart_stress import upload_uart

    data = scene.read_bytes()
    magic, vertices, triangles, flags = struct.unpack_from("<4I", data)
    if (magic != 0x3153474d or not 1 <= vertices <= 32 or not 1 <= triangles <= 32
            or flags > 3 or len(data) != 208 + 32*vertices + 12*triangles):
        raise ValueError("invalid indexed scene")
    upload_uart(command, 4096, data)
    command("/bin/busybox cp /tmp/mgst.in /tmp/complex.scene")
    for mode in ("hardware", "software"):
        folder = out / mode
        folder.mkdir(parents=True)
        cmd = "render /tmp/complex.scene /tmp/complex-frame"
        rows = jobs(mode + " indexed scene", cmd + (" software" if mode == "software" else ""), 1200)
        stages, frame = rows[:-1], rows[-1]
        expected_groups = [1, 1] + ([300] if mode == "hardware" else []) + [2400]
        if (frame["triangles"] != triangles or frame["pixels"] != 320*240
                or frame["render_cycles"] != sum(s["cycles"] for s in stages)
                or any(s["fault"] for s in stages)
                or [sum(sm[1] for sm in s["sm"]) for s in stages] != expected_groups):
            raise ValueError("incomplete indexed scene")
        for name in ("color.rgba", "depth.f32", "winner.u32"):
            raw = read_uart_file(command, "/tmp/complex-frame/" + name, 320*240*4)
            (folder/name).write_bytes(raw)
        (folder/"counters.json").write_text(json.dumps(rows, indent=2) + "\n")
