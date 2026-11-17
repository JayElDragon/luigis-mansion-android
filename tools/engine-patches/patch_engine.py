"""Heat patches for the Luigi's Mansion Recompiled Android engines (libmain.so, libmain_pd.so).

The engines are not in this repository (they contain the recompiled game). Build them yourself, then run:
    python patch_engine.py <folder with libmain.so / libmain_pd.so>
Each patch checks the exact original instructions first and refuses to touch anything else; a .orig copy is kept.

1. SDL_DelayPrecise -> SDL_DelayNS (plain sleep). SDL3's precise delay busy-waits (yield loop) for the last
   part of every frame wait; at 120 fps that kept a core spinning.
2. Game timer (recomp::sched timer thread): it slept until 3 ms before each timed interrupt and then busy-yielded
   those 3 ms. The spin window becomes 0.5 ms (both the threshold and the sleep deadline constant).
"""
import os, shutil, struct, subprocess, sys

W = lambda v: struct.pack("<I", v)


def find_all(buf, pat):
    out, i = [], buf.find(pat)
    while i >= 0:
        if i % 4 == 0: out.append(i)
        i = buf.find(pat, i + 4)
    return out


def text_delta(path):
    """vaddr - file offset of the executable LOAD segment (llvm-readelf from the NDK must be on PATH)."""
    out = subprocess.run(["llvm-readelf", "-lW", path], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if p and p[0] == "LOAD" and "E" in p[6:-1]:
            return int(p[2], 16) - int(p[1], 16)
    raise SystemExit("no executable LOAD segment found")


def symbols(path):
    out = subprocess.run(["llvm-nm", "-D", "--defined-only", path], capture_output=True, text=True).stdout
    return {p[2]: int(p[0], 16) for p in (l.split() for l in out.splitlines()) if len(p) == 3}


def patch_delay(path, buf):
    s = symbols(path)
    precise, ns = s["SDL_DelayPrecise"], s["SDL_DelayNS"]
    off = precise - text_delta(path)
    new = W(0x14000000 | (((ns - precise) >> 2) & 0x3FFFFFF))
    if buf[off:off + 4] == new: return "delay: already patched"
    if buf[off:off + 4] != bytes.fromhex("fd7bbda9"): raise SystemExit(f"delay: unexpected bytes at {off:#x}")
    buf[off:off + 4] = new
    return f"delay: patched at {off:#x}"


def patch_timer(buf):
    THR_OLD, THR_NEW = [W(0x5298D837), W(0x72A005B7)], [W(0x52942437), W(0x72A000F7)]   # 3000001 -> 500001
    DL_OLD, DL_NEW = W(0x9298D7E8) + W(0xF2BFFA48), W(0x929423E8) + W(0xF2BFFF08)        # -3000000 -> -500000
    hits = []
    for a in find_all(buf, THR_OLD[0]):
        k = [a + 4 * j for j in range(1, 16) if buf[a + 4 * j:a + 4 * j + 4] == THR_OLD[1]]
        d = [a + 4 * j for j in range(1, 64) if buf[a + 4 * j:a + 4 * j + 8] == DL_OLD]
        if k and d: hits.append((a, k[0], d[0]))
    if not hits and find_all(buf, DL_NEW): return "timer: already patched"
    if len(hits) != 1: raise SystemExit(f"timer: expected 1 site, found {len(hits)}")
    a, k, d = hits[0]
    buf[a:a + 4], buf[k:k + 4], buf[d:d + 8] = THR_NEW[0], THR_NEW[1], DL_NEW
    return f"timer: patched at {a:#x}/{k:#x}/{d:#x}"


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "."
    for name in ("libmain.so", "libmain_pd.so"):
        path = os.path.join(folder, name)
        if not os.path.isfile(path): print(name, "not found, skipped"); continue
        buf = bytearray(open(path, "rb").read())
        msgs = [patch_delay(path, buf), patch_timer(buf)]
        if not os.path.exists(path + ".orig"): shutil.copy2(path, path + ".orig")
        open(path, "wb").write(buf)
        print(name, "|", "; ".join(msgs))


if __name__ == "__main__":
    main()
