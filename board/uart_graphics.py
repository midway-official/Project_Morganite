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


def paired_graphics(command, jobs, info, out):
    out.mkdir(parents=True, exist_ok=False)
    results = {}
    def read_file(source, size):
        response = command("/bin/busybox sha256sum " + source)
        digest = re.search(r"(?m)^([0-9a-f]{64})  ", response).group(1)
        response = command("/bin/busybox gzip -c " + source + " | /bin/busybox base64", 1800)
        encoded = "".join(s for s in response.splitlines() if re.fullmatch(r"[A-Za-z0-9+/]+=*", s))
        data = gzip.decompress(base64.b64decode(encoded, validate=True))
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("UART readback mismatch for " + source)
        return data

    width, height = 320, 240
    for mode in ("software","hardware"):
        folder = out/mode/"twin_cubes"
        folder.mkdir(parents=True)
        rows = jobs("twin_cubes "+mode, "render /gpu/twin_cubes.scene /tmp/twin" +
                    (" software" if mode == "software" else ""), 1200)
        frame = rows[-1]
        if frame["pixels"] != width*height or frame["triangles"] != 24 or frame["render_cycles"] != sum(r["cycles"] for r in rows[:-1]):
            raise ValueError("incomplete raster stages")
        errors = {}
        for name in ("color.rgba","depth.f32","winner.u32"):
            raw = read_file("/tmp/twin/"+name,width*height*4)
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
    reference = read_file("/gpu/ray-reference.rgba",width*height*4)
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
        data = read_file("/tmp/ray-frame",width*height*4)
        folder = out/mode
        (folder/"ray.rgba").write_bytes(data)
        Image.frombytes("RGBA",(width,height),data).save(str(folder/"ray.png"))
        error = max(abs(a-b) for a,b in zip(data,reference))
        if error > 1:
            raise ValueError("ray color error {} in {}".format(error, mode))
        results[mode]["ray"] = dict(row, width=width,height=height,spheres=20,max_channel_error=error,
                                    gpu_seconds=row["cycles"]/1e8)
    results["speedup"] = {label:results["software"][label][key]/results["hardware"][label][key]
                          for label,key in (("ray","cycles"),("raster","render_cycles"))}
    (out/"results.json").write_text(json.dumps(results,indent=2)+"\n")
