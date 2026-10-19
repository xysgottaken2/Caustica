#!/usr/bin/env python3
"""Execute the production cloud shader on the CPU; optionally render diagnostic views.

Requires Python 3 + numpy, slangc and a C++17 compiler (g++ by default). No Minecraft, GPU,
noise reimplementation or checked-in binaries. CPU compilation ignores GPU-only capability
annotations (not shader code); separate SPIR-V compilation never ignores capabilities.
Generated C++/SPIR-V, metrics and optional PNGs stay under ignored build/cloud-validation/.
"""
from __future__ import annotations

import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import unittest

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build/cloud-validation"
PERIOD = 24576.0
METRICS = {}
PROBE = None


def build_probe(slangc, cxx):
    BUILD.mkdir(parents=True, exist_ok=True)
    cpp = BUILD / "probe.cpp"
    subprocess.run([slangc, str(ROOT / "tools/clouds/probe.slang"), "-I", str(ROOT / "shaders/world"),
                    "-target", "cpp", "-entry", "probe", "-ignore-capabilities", "-o", str(cpp)], check=True)
    # Generated suffixes can differ between Slang releases. Discover names, but require the exact
    # resource types/fields so a compiler ABI change fails at build time instead of corrupting memory.
    source = cpp.read_text()
    global_type, fields = re.search(r"struct (GlobalParams_\d+)\s*\{([^}]+)\}", source).groups()
    names = {name: re.search(r"\b(" + name + r"_\d+);", fields).group(1)
             for name in ("inputs", "outputs", "classicCells")}
    wrapper = BUILD / "probe-host.cpp"
    wrapper.write_text(f'''#include "probe.cpp"
extern "C" void evaluate(float* in, float* out, uint32_t count, uint32_t* cells) {{
    {global_type} params = {{}};
    params.{names['inputs']} = {{ reinterpret_cast<Vector<float, 4>*>(in), size_t(count) * 7 }};
    params.{names['outputs']} = {{ reinterpret_cast<Vector<float, 4>*>(out), count }};
    params.{names['classicCells']} = {{ cells, 2048 }};
    #pragma omp parallel for schedule(static)
    for (uint32_t i = 0; i < count; ++i) {{
        ComputeThreadVaryingInput thread = {{}};
        thread.groupID.x = i;
        probe_Thread(&thread, nullptr, &params);
    }}
}}
''')
    lib = BUILD / "probe.so"
    subprocess.run([cxx, "-std=c++17", "-O2", "-shared", "-fPIC", "-fopenmp", "-ffp-contract=off",
                    str(wrapper), "-o", str(lib)], check=True)
    return ctypes.CDLL(str(lib))


def requests(positions, *, mode=0, lo=16, hi=22, coverage=0.65, fill=0, anchor=(0, 0),
             base=0, opacity=1, direction=(0, 1, 0), distance=1000,
             light=(0.4, 0.8, 0.4472135955), radiance=4, ambient=(0.2, 0.3, 0.5),
             quality=1, style=0, shadow=1, dimension=0, frame=0):
    positions = np.asarray(positions, dtype=np.float32).reshape(-1, 3)
    a = np.zeros((len(positions), 7, 4), dtype=np.float32)
    a[:, 0, :3], a[:, 0, 3] = positions, mode
    a[:, 1] = (lo, hi, coverage, fill)
    a[:, 2] = (*anchor, base + hi / 2, opacity)
    a[:, 3] = (*direction, distance)
    a[:, 4] = (*light, radiance)
    a[:, 5] = (*ambient, quality)
    a[:, 6] = (style, shadow, dimension, frame)
    return a


def evaluate(a, cells=None):
    a = np.ascontiguousarray(a, dtype=np.float32)
    out = np.empty((len(a), 4), dtype=np.float32)
    if cells is None:
        cells = np.zeros(2048, dtype=np.uint32)
    cells = np.ascontiguousarray(cells, dtype=np.uint32)
    assert cells.shape == (2048,)
    PROBE.evaluate(a.ctypes.data, out.ctypes.data, len(a), cells.ctypes.data)
    if not np.isfinite(out).all():
        raise AssertionError("non-finite shader result")
    return out


def points(n=2048, seed=17):
    rng = np.random.default_rng(seed)
    p = rng.uniform(-PERIOD, PERIOD, (n, 3)).astype(np.float32)
    p[:, 1] = rng.uniform(0, 22, n)
    return p


class Clouds(unittest.TestCase):
    def test_01_thickness_bounds_and_equal_endpoints(self):
        p = points()
        spans = []
        for lo in range(16, 23):
            for hi in range(lo, 23):
                v = evaluate(requests(p, lo=lo, hi=hi))
                self.assertTrue(np.all((v[:, 1] >= lo) & (v[:, 1] <= hi)))
                if lo == hi:
                    np.testing.assert_array_equal(v[:, 1], lo)
                else:
                    self.assertGreater(np.ptp(v[:, 1]), (hi - lo) * 0.98)
                spans.append([lo, hi, float(v[:, 1].min()), float(v[:, 1].max())])
        METRICS['thickness_ranges'] = spans

    def test_02_actual_density_never_leaks_outside_local_envelope(self):
        p = points(4096)
        for lo, hi in [(16, 22)] + [(i, i) for i in range(16, 23)]:
            a = requests(p, lo=lo, hi=hi, coverage=1)
            thickness = evaluate(a)[:, 1]
            for y in (-0.01, 0, hi, hi + 0.01, hi + 10):
                a[:, 0, 1] = y
                np.testing.assert_array_equal(evaluate(a)[:, 0], 0)
            for offset in (0, 0.001, 0.5, 10):
                a[:, 0, 1] = thickness + offset
                np.testing.assert_array_equal(evaluate(a)[:, 0], 0)
            a[:, 0, 1] = thickness * 0.35
            self.assertTrue(np.all(evaluate(a)[:, 0] > 0), "full-overcast bodies must actually exist")

    def test_03_density_and_silhouette_are_not_an_extruded_mask(self):
        x, z, y = np.meshgrid(np.arange(0, 192, 2), np.arange(0, 192, 2),
                              np.arange(0.25, 22, 0.5), indexing='ij')
        p = np.stack([x.ravel(), y.ravel(), z.ravel()], axis=1)
        v = evaluate(requests(p, lo=22, hi=22)).reshape(*x.shape, 4)
        d = v[..., 0]
        nonempty = d > 0.001
        cloud = np.any(nonempty, axis=2)
        self.assertGreater(cloud.mean(), 0.12)
        self.assertLess(cloud.mean(), 0.90)
        self.assertGreater(d[d > 0.01].std(), 0.08)
        # Identical thickness parameter, but silhouettes still differ because of 3D shape/erosion.
        tops = np.max(np.where(nonempty, y, 0), axis=2)[cloud]
        bases = np.min(np.where(nonempty, y, 100), axis=2)[cloud]
        spans = tops - bases
        self.assertGreater(tops.std(), 2.0)
        self.assertLessEqual(spans.max(), 22)
        self.assertGreater(tops.max(), 22 * 0.80, "profile must use the configured volume, not a thin sheet")
        correlation = np.corrcoef(d[:, :, 9].ravel(), d[:, :, 31].ravel())[0, 1]
        self.assertLess(correlation, 0.97)
        METRICS['morphology'] = dict(footprint_fraction=float(cloud.mean()),
            density_std=float(d[d > 0.01].std()), top_std_blocks=float(tops.std()),
            visible_span_min=float(spans.min()), visible_span_max=float(spans.max()),
            highest_density_sample=float(tops.max()), vertical_slice_correlation=float(correlation))

    def test_04_periodicity_of_complete_field_and_noise(self):
        # Dyadic coordinates keep the positions EXACTLY representable after adding +/-2 periods.
        # Testing arbitrary fractional floats as if that addition were exact conflates a different
        # input position (rounded at 73k blocks) with a periodicity error in the density field.
        a = requests(np.round(points(4096) * 16) / 16)
        ref = evaluate(a)
        errors = []
        for axis in (0, 2):
            for periods in (-2, -1, 1, 2):
                b = a.copy()
                b[:, 0, axis] += periods * PERIOD
                error = np.max(np.abs(evaluate(b) - ref), axis=0)
                errors.append(error.tolist())
                np.testing.assert_array_equal(evaluate(b), ref)
        raw = requests(points(4096))
        shifted = raw.copy()
        shifted[:, 0, 0] += 2 * PERIOD
        shifted[:, 0, 2] -= 2 * PERIOD
        roundoff = np.max(np.abs(evaluate(shifted) - evaluate(raw)), axis=0)
        self.assertLess(roundoff[0], 0.008) # input rounding at 73k blocks, not a discontinuous wrap
        self.assertLess(roundoff[1], 0.008)
        METRICS['non_dyadic_float32_wrap_roundoff'] = roundoff.tolist()
        p = points(2048) / 48
        a = requests(p, mode=3)
        ref = evaluate(a)
        for axis in (0, 1, 2):
            b = a.copy()
            b[:, 0, axis] += 512
            np.testing.assert_allclose(evaluate(b)[:, :2], ref[:, :2], atol=0.0003, rtol=0)
        METRICS['periodic_max_errors_density_thickness_coverage_sigma'] = np.max(errors, axis=0).tolist()

    def test_05_lattice_boundaries_and_anchor_wrap_are_continuous(self):
        rng = np.random.default_rng(74)
        p = rng.uniform(-8, 8, (2048, 3)).astype(np.float32)
        for axis in range(3):
            p[:, axis] = rng.integers(-8, 8, len(p))
            left, right = p.copy(), p.copy()
            left[:, axis] -= 0.0001
            right[:, axis] += 0.0001
            error = np.abs(evaluate(requests(left, mode=3))[:, :2]
                         - evaluate(requests(right, mode=3))[:, :2])
            self.assertLess(error.max(), 0.001)
        p = points(4096)
        errors = []
        for axis in (0, 2):
            for boundary in (0, 12, 24, 48, PERIOD / 2, PERIOD):
                previous = None
                # Convergence, not an arbitrary small-delta threshold: a discontinuity keeps its
                # jump as epsilon shrinks. These steps remain representable even at the anchor wrap.
                for epsilon in (1 / 16, 1 / 32, 1 / 64, 1 / 128):
                    left, right = p.copy(), p.copy()
                    left[:, axis], right[:, axis] = boundary - epsilon, boundary + epsilon
                    e = np.max(np.abs(evaluate(requests(left)) - evaluate(requests(right))), axis=0)
                    if previous is not None:
                        self.assertLess(e[0], previous[0] * 0.75 + 0.001)
                    previous = e
                self.assertLess(e[0], 0.025)
                self.assertLess(e[1], 0.02)
                errors.append(e.tolist())
        METRICS['boundary_max_errors_for_1_64_block_gap'] = np.max(errors, axis=0).tolist()

    def test_06_deterministic_order_frame_wind_and_rebasing(self):
        a = requests(np.round(points() * 16) / 16, anchor=(1536, 3072))
        ref = evaluate(a)
        order = np.random.default_rng(9).permutation(len(a))
        b = a[order].copy()
        b[:, 6, 3] = 120000
        np.testing.assert_array_equal(evaluate(b), ref[order])
        # Wind must translate the whole volume, not evolve different octaves independently.
        b = a.copy()
        b[:, 2, 0] += 0.03125
        c = a.copy()
        c[:, 0, 0] += 0.03125
        np.testing.assert_array_equal(evaluate(b), evaluate(c))
        b = a.copy()
        camera = np.array([768, 127, -384], dtype=np.float32)
        b[:, 0, :3] -= camera
        b[:, 2, :2] += camera[[0, 2]]
        b[:, 2, 2] -= camera[1]
        np.testing.assert_allclose(evaluate(b), ref, atol=0.0001, rtol=0)
        # Real wrap step: both anchors represent the same unwrapped wind displacement.
        a[:, 2, 0] = PERIOD - 0.03125
        b = a.copy()
        b[:, 2, 0] = 0.03125
        c = a.copy()
        c[:, 0, 0] += 0.0625
        np.testing.assert_allclose(evaluate(b), evaluate(c), atol=0.001, rtol=0)

    def test_07_weather_reaches_the_density_monotonically(self):
        p = points()
        p[:, 1] = 5
        previous = np.zeros(len(p))
        for fill in (0, 0.25, 0.5, 0.75, 1):
            density = evaluate(requests(p, coverage=0.2, fill=fill))[:, 0]
            self.assertTrue(np.all(density >= previous - 1e-6))
            previous = density
        self.assertTrue(np.all(previous > 0))
        np.testing.assert_array_equal(previous, evaluate(requests(p, coverage=1))[:, 0])
        np.testing.assert_array_equal(evaluate(requests(p, coverage=0))[:, 0], 0)

    def test_08_march_clipping_inside_rays_and_secondary_origins(self):
        p = points(512)
        p[:, 1] = -30
        a = requests(p, mode=1, coverage=1, distance=29)
        np.testing.assert_array_equal(evaluate(a), np.tile([0, 0, 0, 1], (len(p), 1)))
        a[:, 3, 3] = 100
        ref = evaluate(a)
        self.assertLess(ref[:, 3].mean(), 0.6)
        # Express the identical ray in a different camera coordinate system, including nonzero Y.
        b = a.copy()
        b[:, 0, :3] -= [12, 83, -24]
        b[:, 2, :2] += [12, -24]
        b[:, 2, 2] -= 83
        np.testing.assert_allclose(evaluate(b), ref, atol=0.001, rtol=0)
        p[:, 1] = 11
        a = requests(p, mode=1, coverage=1, direction=(1, 0, 0), distance=30)
        self.assertLess(evaluate(a)[:, 3].mean(), 0.4, "inside volume must not dissolve at deck centre")
        for y in (-0.01, 22.01):
            a[:, 0, 1] = y
            np.testing.assert_array_equal(evaluate(a)[:, 3], 1)
        previous = None
        for epsilon in (0.02, 0.01, 0.005, 0.0025):
            a[:, 0, 1] = 11 - epsilon
            left = evaluate(a)
            a[:, 0, 1] = 11 + epsilon
            error = np.max(np.abs(evaluate(a) - left))
            # An eroded edge can legitimately change opacity by 1% over .02 blocks. A camera-plane
            # discontinuity would NOT halve as the interval shrinks; test convergence explicitly.
            if previous is not None:
                self.assertLess(error, previous * 0.7 + 0.0002)
            previous = error
        self.assertLess(error, 0.003)
        METRICS['inside_ray_max_change_for_0_005_blocks'] = float(error)

    def test_09_optics_opacity_light_convergence_and_shadow_invariance(self):
        p = points(1024)
        p[:, 1] = -1
        a = requests(p, mode=1, coverage=0.85, light=(0, 1, 0))
        full = evaluate(a)
        a[:, 2, 3] = 0.25
        low_opacity = evaluate(a)
        self.assertTrue(np.all(low_opacity[:, 3] >= 0.75))
        np.testing.assert_allclose(low_opacity[:, :3], full[:, :3] * 0.25, atol=1e-6)
        np.testing.assert_allclose(low_opacity[:, 3], 1 - (1 - full[:, 3]) * 0.25, atol=1e-6)
        a[:, 2, 3] = 1
        a[:, 0, 3] = 2
        a[:, 3, 3] = 192
        optical_ref = evaluate(a)
        expected_t = np.exp(-optical_ref[:, 0])
        err_view = np.abs(full[:, 3] - expected_t)
        self.assertLess(err_view.mean(), 0.012)
        self.assertLess(err_view.max(), 0.09)
        a[:, 3, 3] = 6
        light = evaluate(a)
        err_light = np.abs(np.exp(-light[:, 0]) - expected_t)
        self.assertLess(err_light.mean(), 0.04)
        self.assertLess(err_light.max(), 0.22)
        b = a.copy()
        b[:, 0, 1] -= 150
        b[:, 2, 2] -= 150
        np.testing.assert_allclose(evaluate(b), light, atol=0.001, rtol=0)
        METRICS['optics'] = dict(view_T_error_mean=float(err_view.mean()), view_T_error_max=float(err_view.max()),
                               light_T_error_mean=float(err_light.mean()), light_T_error_max=float(err_light.max()))
        for quality in (0, 1):
            for radiance in (0, 0.03, 1, 4):
                a = requests(p, mode=1, coverage=0.85, quality=quality, radiance=radiance)
                v = evaluate(a)
                self.assertTrue(np.all(v >= 0))
                self.assertTrue(np.all(v[:, 3] <= 1))
        # Disabling the deck through opacity, coverage or dimension must leave the segment untouched.
        for kwargs in ({'opacity': 0}, {'coverage': 0}, {'dimension': 1}, {'dimension': 2}):
            v = evaluate(requests(p, mode=1, **kwargs))
            np.testing.assert_array_equal(v, np.tile([0, 0, 0, 1], (len(p), 1)))

    def test_10_classic_authored_boxes_and_missing_map_fallback(self):
        p = points(256)
        p[:, 1] = -20
        empty = np.zeros(2048, dtype=np.uint32)
        solid = np.full(2048, 0xFFFFFFFF, dtype=np.uint32)
        for thickness in (4, 55, 110):
            a = requests(p, mode=1, style=2, hi=thickness, coverage=1, opacity=0.9)
            np.testing.assert_array_equal(evaluate(a, empty)[:, 3], 1)
            full = evaluate(a, solid)
            np.testing.assert_allclose(full[:, 3], 0.1, atol=1e-6)
            a[:, 1, 0] = 22 # volumetric minimum must not alter classic boxes
            np.testing.assert_array_equal(evaluate(a, solid), full)
            a[:, 0, 0] += 3072
            np.testing.assert_allclose(evaluate(a, solid), full, atol=1e-6)
            a[:, 1, 3] = 1 # storm fills even an empty authored map
            np.testing.assert_allclose(evaluate(a, empty), full, atol=1e-6)
            a[:, 1, 3] = 0
            a[:, 6, 0] = 1 # missing map uses existing quantised procedural fallback
            fallback = evaluate(a)
            self.assertTrue(np.all((fallback[:, 3] >= 0.1 - 1e-6) & (fallback[:, 3] <= 1)))
            self.assertLess(fallback[:, 3].mean(), 0.9)

    def test_11_host_period_and_localization_contract(self):
        host = (ROOT / 'src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java').read_text()
        shader = (ROOT / 'shaders/world/clouds.slang').read_text()
        for source in (host, shader):
            period = re.search(r'CLOUD_FIELD_PERIOD_BLOCKS = ([0-9.]+);', source)
            self.assertEqual(float(period.group(1)), PERIOD)
        for locale in ('en_us', 'pt_br'):
            data = json.loads((ROOT / f'src/main/resources/assets/caustica/lang/{locale}.json').read_text())
            for key in ('cloudMinThickness', 'cloudMinThickness.tooltip', 'cloudMaxThickness',
                        'cloudMaxThickness.tooltip', 'cloudThickness.blocks'):
                self.assertTrue(data['caustica.options.rt.' + key])


def compile_spirv(slangc):
    shaders = [p for p in (ROOT / 'shaders/world').glob('*.slang')
               if any(f'.{stage}.' in p.name for stage in ('rgen', 'rchit', 'rahit', 'rmiss'))]
    variants = [(p, p.stem, []) for p in sorted(shaders)]
    variants.append((ROOT / 'shaders/world/world.rgen.slang', 'world_ser.rgen',
                     ['-DCAUSTICA_ENABLE_EXT_SER', '-capability', 'spvShaderInvocationReorderEXT']))
    for p, name, options in variants:
        out = BUILD / (name + '.spv')
        subprocess.run([slangc, str(p), '-target', 'spirv', '-profile', 'spirv_1_5',
                        '-matrix-layout-column-major', '-warnings-as-errors', 'all',
                        '-warnings-disable', '41012', '-g', *options, '-o', str(out)], check=True)
        if shutil.which('spirv-val'):
            subprocess.run(['spirv-val', '--target-env', 'vulkan1.2', str(out)], check=True)
    METRICS['spirv_stages'] = len(variants)
    METRICS['spirv_validated'] = bool(shutil.which('spirv-val'))


def render_diagnostics():
    """Actual cloudSegment integration, simple sky only: NOT a Minecraft screenshot/quality proof."""
    from PIL import Image, ImageDraw
    width, height = 360, 200
    xx, yy = np.meshgrid((np.arange(width) + 0.5) / width * 2 - 1,
                         1 - (np.arange(height) + 0.5) / height * 2)
    for name, eye, target in [('below', (96, -24, -48), (96, 10, 112)),
                               ('above', (96, 42, -48), (96, 9, 112)),
                               ('side', (96, 12, -48), (96, 12, 112))]:
        eye, target = np.array(eye), np.array(target)
        forward = target - eye
        forward = forward / np.linalg.norm(forward)
        right = np.cross(forward, [0, 1, 0])
        right /= np.linalg.norm(right)
        up = np.cross(right, forward)
        rays = (forward + xx[..., None] * right * 0.75
                + yy[..., None] * up * 0.75 * height / width).reshape(-1, 3)
        rays /= np.linalg.norm(rays, axis=1)[:, None]
        sky = np.array([0.12, 0.28, 0.55])[None, :] * (1.0 + rays[:, 1:2] * 0.25)
        a = requests(np.broadcast_to(eye, rays.shape), mode=1, coverage=0.65, radiance=5,
                     light=(0.6, 0.6, -0.5291502622), ambient=(0.24, 0.32, 0.45))
        a[:, 3, :3] = rays
        v = evaluate(a)
        rgb = v[:, :3] + sky * v[:, 3:4]
        rgb = np.power(np.clip(1 - np.exp(-rgb * 1.6), 0, 1), 1 / 2.2)
        image = Image.fromarray((rgb.reshape(height, width, 3) * 255).astype(np.uint8))
        ImageDraw.Draw(image).text((8, 8), 'CPU shader diagnostic - NOT Minecraft', fill='white')
        image.save(BUILD / (name + '.png'))
    # A vertical slice exposes the hard envelope and internal density without a tone mapper hiding it.
    xx, yy = np.meshgrid(np.linspace(0, 192, 768), np.linspace(24, -2, 208))
    p = np.stack([xx.ravel(), yy.ravel(), np.full(xx.size, 96)], axis=1)
    v = evaluate(requests(p))
    density = v[:, 0].reshape(xx.shape)
    image = Image.fromarray((np.stack([density] * 3, axis=2) * 255).astype(np.uint8))
    image.save(BUILD / 'density-section.png')


def main():
    global PROBE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--slangc', default=os.environ.get('SLANGC', 'slangc'))
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'g++'))
    parser.add_argument('--spirv', action='store_true', help='also compile every production ray-tracing stage')
    parser.add_argument('--render', action='store_true', help='write optional CPU diagnostic PNGs (requires Pillow)')
    args = parser.parse_args()
    os.environ.setdefault('OMP_NUM_THREADS', '4')
    start = time.monotonic()
    PROBE = build_probe(args.slangc, args.cxx)
    PROBE.evaluate.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p]
    PROBE.evaluate.restype = None
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Clouds))
    if args.spirv:
        compile_spirv(args.slangc)
    if args.render:
        render_diagnostics()
    METRICS['seconds'] = time.monotonic() - start
    METRICS['tests_run'] = result.testsRun
    METRICS['failures'] = len(result.failures) + len(result.errors)
    (BUILD / 'metrics.json').write_text(json.dumps(METRICS, indent=2) + '\n')
    print(json.dumps(METRICS, indent=2))
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    sys.exit(main())
