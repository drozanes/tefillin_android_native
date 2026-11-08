#!/usr/bin/env python3
"""
Interactive Ground Truth & Benchmark Manager for Tefillin Native Algorithms.
Allows developers to easily view, edit, and update the Ground Truth for:
  1. Face hairline/trichion expected ratios and tolerances.
  2. Tefillin halachic expected alignment statuses.
  3. Hairline mistake rate benchmark thresholds.
"""

import os
import sys
import json
import base64
import argparse
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
RESOURCES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "test", "resources")
FIXTURES_FILE = os.path.join(RESOURCES_DIR, "algo_test_fixtures.json")
GT_FILE = os.path.join(RESOURCES_DIR, "algo_ground_truth.json")


def load_json(path):
    if not os.path.exists(path):
        print(f"Error: File not found: {path}")
        sys.exit(1)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"Saved changes to {os.path.relpath(path, PROJECT_ROOT)}")


def compute_hairline_detection(face_fixture):
    """Executes the exact visual hairline detection on the stored patch data."""
    lms = face_fixture["landmarks"]
    w, h = face_fixture["width"], face_fixture["height"]
    patch = face_fixture.get("patch")

    glabella = np.array([lms[8][0] * w, lms[8][1] * h])
    subnasale = np.array([lms[2][0] * w, lms[2][1] * h])
    nose = np.array([lms[6][0] * w, lms[6][1] * h])
    mesh_top = np.array([lms[10][0] * w, lms[10][1] * h])
    eye_l = np.array([lms[33][0] * w, lms[33][1] * h])
    eye_r = np.array([lms[263][0] * w, lms[263][1] * h])

    eye_dist = np.hypot(eye_r[0] - eye_l[0], eye_r[1] - eye_l[1])
    face_up = mesh_top - nose
    u_up = face_up / max(1.0, np.hypot(face_up[0], face_up[1]))

    dz = (lms[10][2] - lms[6][2]) * w
    dy = mesh_top[1] - nose[1]
    pitch_angle = np.arctan2(dz, abs(dy))
    ref_rad = np.radians(25.0)
    pitch_factor = max(0.60, np.cos(ref_rad + pitch_angle) / np.cos(ref_rad))

    raw_midface = np.hypot(glabella[0] - subnasale[0], glabella[1] - subnasale[1])
    midface_dist = max(raw_midface, eye_dist * 0.65)
    anatomical_trichion_dist = midface_dist * 1.08

    min_trichion_dist = max(eye_dist * 0.66 * pitch_factor, anatomical_trichion_dist * 0.90)
    max_trichion_dist = min(eye_dist * 0.90, anatomical_trichion_dist * 1.15)

    vis_dist = None
    if patch:
        ref_rad = np.radians(25.0)
        u_cross = np.array([-u_up[1], u_up[0]])
        scan_start = 0.60 * eye_dist * pitch_factor
        scan_end = 0.95 * eye_dist * pitch_factor
        ox, oy = patch["originX"], patch["originY"]
        pw, ph = patch["patchWidth"], patch["patchHeight"]
        gray_bytes = base64.b64decode(patch["grayBase64"])

        def get_gray(px, py):
            rx, ry = int(round(px)), int(round(py))
            lx, ly = rx - ox, ry - oy
            if 0 <= lx < pw and 0 <= ly < ph:
                return gray_bytes[ly * pw + lx]
            return -1

        skin_sum, skin_count = 0, 0
        for s in range(8):
            d = (0.35 + s * 0.02) * eye_dist
            g = get_gray(glabella[0] + u_up[0] * d, glabella[1] + u_up[1] * d)
            if g >= 0:
                skin_sum += g
                skin_count += 1
        if skin_count > 0:
            skin_med = skin_sum / skin_count
            step_px = max(2, int(round(eye_dist * 0.012)))
            num_steps = int(np.floor((scan_end - scan_start) / step_px))
            for i in range(num_steps):
                d = scan_start + i * step_px
                cx = glabella[0] + u_up[0] * d
                cy = glabella[1] + u_up[1] * d
                c_val = get_gray(cx, cy)
                if c_val < 0:
                    continue
                lat = eye_dist * 0.15
                lx, ly = cx - u_cross[0] * lat, cy - u_cross[1] * lat
                rx, ry = cx + u_cross[0] * lat, cy + u_cross[1] * lat
                l_val, r_val = get_gray(lx, ly), get_gray(rx, ry)
                is_dark_c = (c_val < skin_med - 24) or (c_val < skin_med * 0.76)
                is_dark_l = l_val >= 0 and ((l_val < skin_med - 20) or (l_val < skin_med * 0.80))
                is_dark_r = r_val >= 0 and ((r_val < skin_med - 20) or (r_val < skin_med * 0.80))
                vals = []
                for dy_off in range(-2, 3, 2):
                    for dx_off in range(-2, 3, 2):
                        v = get_gray(cx + dx_off, cy + dy_off)
                        if v >= 0:
                            vals.append(v)
                std_dev = np.std(vals) if vals else 0
                if (is_dark_c and (is_dark_l or is_dark_r)) or (std_dev > 18.0 and c_val < skin_med - 15):
                    vis_dist = d
                    break

    if vis_dist is not None:
        vis_bounded = np.clip(vis_dist, anatomical_trichion_dist - 0.05 * eye_dist, anatomical_trichion_dist + 0.05 * eye_dist)
        final_dist = 0.40 * anatomical_trichion_dist + 0.60 * vis_bounded
    else:
        final_dist = anatomical_trichion_dist

    return float(final_dist / eye_dist)


def compute_halacha_detection(tefillin_fixture, default_ratio=0.70):
    """Calculates the alignment status for a tefillin sample using full visual hairline scan."""
    hairline_ratio = compute_hairline_detection(tefillin_fixture)
    if hairline_ratio is None or np.isnan(hairline_ratio):
        hairline_ratio = default_ratio

    lms = tefillin_fixture["landmarks"]
    w, h = tefillin_fixture["width"], tefillin_fixture["height"]
    box = tefillin_fixture["yoloBox"]

    glabella = np.array([lms[8][0] * w, lms[8][1] * h])
    nose = np.array([lms[6][0] * w, lms[6][1] * h])
    mesh_top = np.array([lms[10][0] * w, lms[10][1] * h])
    eye_l = np.array([lms[33][0] * w, lms[33][1] * h])
    eye_r = np.array([lms[263][0] * w, lms[263][1] * h])

    eye_dist = np.hypot(eye_r[0] - eye_l[0], eye_r[1] - eye_l[1])
    face_up = mesh_top - nose
    u_up = face_up / max(1.0, np.hypot(face_up[0], face_up[1]))

    hairline_pt = glabella + u_up * (hairline_ratio * eye_dist)

    lowest_edge_vec = np.array([box["x"] - hairline_pt[0], box["bottomY"] - hairline_pt[1]])
    dist_above = lowest_edge_vec[0] * u_up[0] + lowest_edge_vec[1] * u_up[1]

    # Hairline boundary margin (±5% of eye distance): avoids strict errors when close to hairline
    margin = eye_dist * 0.05
    is_clearly_below = dist_above < -margin

    a = glabella
    b = mesh_top
    p = np.array([box["x"], box["y"]])
    line_len = np.hypot(b[0] - a[0], b[1] - a[1])
    horiz_offset = 0.0
    if line_len > 0.1:
        horiz_offset = ((b[0] - a[0]) * (a[1] - p[1]) - (b[1] - a[1]) * (a[0] - p[0])) / line_len

    is_centered = abs(horiz_offset) < (eye_dist * 0.08)

    if is_clearly_below:
        return "FOREHEAD_ERROR", False
    elif not is_centered:
        return "OFF_CENTER", True
    else:
        return "ALIGNED", True


def list_all(fixtures, gt_data):
    print("========================================================================================================")
    print("▶ SECTION 1: FACE IMAGES - HAIRLINE / TRICHION GROUND TRUTH vs DETECTED")
    print("========================================================================================================")
    hairline_gt = gt_data.get("hairline_gt", {})
    mistakes = []
    passes = []

    print(f" {'#':<3} | {'Image Name':<35} | {'Detected':<8} | {'GT Ratio':<8} | {'Tol':<5} | {'Diff':<6} | {'Result':<8}")
    print("-" * 92)

    for idx, face in enumerate(fixtures["faces"], 1):
        name = face["name"]
        detected = compute_hairline_detection(face)
        gt_info = hairline_gt.get(name, {})
        expected = gt_info.get("expected_ratio", 0.70)
        tol = gt_info.get("tolerance", 0.05)
        diff = abs(detected - expected)

        is_pass = diff <= tol
        status = "✅ PASS" if is_pass else "❌ MISTAKE"

        if is_pass:
            passes.append(name)
        else:
            mistakes.append((name, detected, expected, diff, tol))

        print(f" {idx:<3} | {name:<35} | {detected:<8.3f} | {expected:<8.3f} | ±{tol:<4.2f} | {diff:<6.3f} | {status:<8}")

    total_faces = len(fixtures["faces"])
    mistake_pct = round((len(mistakes) / max(1, total_faces)) * 100.0, 2)
    benchmark_pct = round(gt_data.get("benchmark", {}).get("best_mistake_percentage", 25.0), 2)

    print("-" * 92)
    is_benchmark_ok = (mistake_pct <= benchmark_pct + 0.05)
    benchmark_status = "✅ PASS (Within benchmark)" if is_benchmark_ok else "❌ FAIL (Regression: exceeds benchmark!)"
    print(f" Summary: {len(passes)}/{total_faces} passed | Mistakes: {len(mistakes)} ({mistake_pct:.1f}%) | Best Benchmark: {benchmark_pct:.1f}% | {benchmark_status}")

    print("\n========================================================================================================")
    print("▶ SECTION 2: TEFILLIN SAMPLES - HALACHA STATUS GROUND TRUTH vs DETECTED")
    print("========================================================================================================")
    halacha_gt = gt_data.get("halacha_gt", {})
    print(f" {'#':<3} | {'Sample Name':<35} | {'Detected Status':<16} | {'GT Expected':<16} | {'Result':<8}")
    print("-" * 92)

    for idx, tef in enumerate(fixtures["tefillin"], 1):
        name = tef["name"]
        detected_status, _ = compute_halacha_detection(tef, 0.70)
        gt_info = halacha_gt.get(name, {})
        expected_status = gt_info.get("expected_status", tef.get("expectedStatus", "ALIGNED"))

        is_match = (detected_status == expected_status)
        status_icon = "✅ MATCH" if is_match else "❌ MISMATCH"

        print(f" {idx:<3} | {name:<35} | {detected_status:<16} | {expected_status:<16} | {status_icon:<8}")
    print("-" * 92)


def set_hairline(fixtures, gt_data, query, ratio, tolerance=None):
    hairline_gt = gt_data.setdefault("hairline_gt", {})
    # Match face by full name or substring
    matched = [f["name"] for f in fixtures["faces"] if query in f["name"]]
    if not str(query).endswith(".jpg") and query.isdigit():
        idx = int(query) - 1
        if 0 <= idx < len(fixtures["faces"]):
            matched = [fixtures["faces"][idx]["name"]]

    if not matched:
        print(f"Error: No face image matched query: '{query}'")
        return False

    name = matched[0]
    entry = hairline_gt.setdefault(name, {})
    old_ratio = entry.get("expected_ratio", "None")
    entry["expected_ratio"] = round(float(ratio), 3)
    if tolerance is not None:
        entry["tolerance"] = round(float(tolerance), 3)

    print(f"Updated Hairline GT for {name}: {old_ratio} -> {entry['expected_ratio']} (tol: ±{entry.get('tolerance', 0.05)})")
    save_json(GT_FILE, gt_data)
    return True


def set_halacha(fixtures, gt_data, query, status):
    status = status.upper().strip()
    valid_statuses = [
        "ALIGNED", "FOREHEAD_ERROR", "OFF_CENTER",
        "CAMERA_TOO_LOW", "CAMERA_TOO_HIGH",
        "CAMERA_ANGLED_SIDEWAYS", "HEAD_TILTED",
        "NO_TEFILLIN"
    ]
    if status not in valid_statuses:
        print(f"Error: Invalid status '{status}'. Valid options: {', '.join(valid_statuses)}")
        return False

    halacha_gt = gt_data.setdefault("halacha_gt", {})
    matched = [t["name"] for t in fixtures["tefillin"] if query in t["name"]]
    if not str(query).endswith(".jpg") and query.isdigit():
        idx = int(query) - 1
        if 0 <= idx < len(fixtures["tefillin"]):
            matched = [fixtures["tefillin"][idx]["name"]]

    if not matched:
        print(f"Error: No tefillin sample matched query: '{query}'")
        return False

    name = matched[0]
    entry = halacha_gt.setdefault(name, {})
    old_status = entry.get("expected_status", "None")
    entry["expected_status"] = status
    entry["expected_kosher"] = (status in ["ALIGNED", "OFF_CENTER"])

    print(f"Updated Halacha GT for {name}: {old_status} -> {status} (isKosher: {entry['expected_kosher']})")
    save_json(GT_FILE, gt_data)
    return True


def update_benchmark(fixtures, gt_data):
    hairline_gt = gt_data.get("hairline_gt", {})
    mistakes = 0
    total = len(fixtures["faces"])

    for face in fixtures["faces"]:
        detected = compute_hairline_detection(face)
        expected = hairline_gt.get(face["name"], {}).get("expected_ratio", 0.70)
        tol = hairline_gt.get(face["name"], {}).get("tolerance", 0.05)
        if abs(detected - expected) > tol:
            mistakes += 1

    mistake_pct = round((mistakes / max(1, total)) * 100.0, 2)
    bm = gt_data.setdefault("benchmark", {})
    old_pct = bm.get("best_mistake_percentage", 25.0)

    import datetime
    bm["best_mistake_percentage"] = mistake_pct
    bm["best_mistake_count"] = mistakes
    bm["total_faces"] = total
    bm["last_updated"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print(f"Updated Benchmark mistake rate: {old_pct}% -> {mistake_pct}% ({mistakes}/{total} mistakes)")
    save_json(GT_FILE, gt_data)


def exclude_image(gt_data, query):
    excluded = set(gt_data.get("excluded_images", []))
    excluded.add(query)
    gt_data["excluded_images"] = sorted(list(excluded))
    save_json(GT_FILE, gt_data)
    print(f"Excluded '{query}' from algorithm tests.")


def restore_image(gt_data, query):
    excluded = set(gt_data.get("excluded_images", []))
    matched = [img for img in excluded if query.lower() in img.lower()]
    if not matched:
        print(f"No excluded image matches '{query}'.")
        return
    for img in matched:
        excluded.remove(img)
        print(f"Restored '{img}' to active algorithm tests.")
    gt_data["excluded_images"] = sorted(list(excluded))
    save_json(GT_FILE, gt_data)


def main():
    parser = argparse.ArgumentParser(description="Tefillin Ground Truth & Benchmark Manager")
    parser.add_argument("--list", "-l", action="store_true", help="List all images, detected vs GT, and current mistake rate")
    parser.add_argument("--set-hairline", nargs="+", metavar=("IMAGE", "RATIO"), help="Set hairline GT expected ratio: --set-hairline selfie_001 0.65 [tol]")
    parser.add_argument("--set-halacha", nargs=2, metavar=("SAMPLE", "STATUS"), help="Set halacha GT expected status: --set-halacha cropped_images_5 ALIGNED")
    parser.add_argument("--update-benchmark", action="store_true", help="Record current mistake rate as the new best benchmark")
    parser.add_argument("--set-benchmark", type=float, help="Manually set allowed mistake rate threshold percentage (e.g. 20.0)")
    parser.add_argument("--exclude", metavar="IMAGE", help="Exclude an image from tests: --exclude image_name.jpg")
    parser.add_argument("--restore", metavar="IMAGE", help="Restore an excluded image: --restore image_name.jpg")
    parser.add_argument("--list-excluded", action="store_true", help="List all excluded images")

    args = parser.parse_args()

    fixtures = load_json(FIXTURES_FILE)
    gt_data = load_json(GT_FILE)

    if args.exclude:
        exclude_image(gt_data, args.exclude)
    elif args.restore:
        restore_image(gt_data, args.restore)
    elif args.list_excluded:
        excluded = gt_data.get("excluded_images", [])
        print(f"Excluded images ({len(excluded)}):")
        for x in excluded:
            print(f"  • {x}")
    elif args.set_hairline:
        query = args.set_hairline[0]
        ratio = float(args.set_hairline[1])
        tol = float(args.set_hairline[2]) if len(args.set_hairline) > 2 else None
        set_hairline(fixtures, gt_data, query, ratio, tol)
    elif args.set_halacha:
        query = args.set_halacha[0]
        status = args.set_halacha[1]
        set_halacha(fixtures, gt_data, query, status)
    elif args.update_benchmark:
        update_benchmark(fixtures, gt_data)
    elif args.set_benchmark is not None:
        bm = gt_data.setdefault("benchmark", {})
        bm["best_mistake_percentage"] = round(float(args.set_benchmark), 2)
        print(f"Benchmark threshold manually set to {args.set_benchmark}%")
        save_json(GT_FILE, gt_data)
    else:
        list_all(fixtures, gt_data)


if __name__ == "__main__":
    main()
