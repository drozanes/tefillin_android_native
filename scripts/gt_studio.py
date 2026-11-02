#!/usr/bin/env python3
"""
Tefillin CV Ground Truth & Algorithm Regression Studio.
A lightweight, modern local web application for:
  1. Inspecting face images and interactively dragging / setting hairline (trichion) ground truth.
  2. Inspecting tefillin samples and viewing / toggling expected Halacha statuses.
  3. Monitoring hairline mistake rates against recorded benchmarks.
  4. Executing native Kotlin algorithm regression tests directly from the browser.
"""

import os
import sys
import json
import base64
import urllib.parse
import subprocess
import webbrowser
import mimetypes
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
RESOURCES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "test", "resources")
FIXTURES_FILE = os.path.join(RESOURCES_DIR, "algo_test_fixtures.json")
GT_FILE = os.path.join(RESOURCES_DIR, "algo_ground_truth.json")
WEB_DIR = os.path.join(SCRIPT_DIR, "web")

DEV_DIR = os.path.dirname(PROJECT_ROOT)
SELFIES_DIR = os.path.join(DEV_DIR, "tefillin_app", "diverse_men_selfies_raw")
SAMPLES_DIR = os.path.join(DEV_DIR, "tefillin_app", "samples")

# Import calculation logic from manage_gt
sys.path.insert(0, SCRIPT_DIR)
import manage_gt


def find_sample_image(name):
    """Searches recursively for a tefillin sample image."""
    for root, _, files in os.walk(SAMPLES_DIR):
        if name in files:
            return os.path.join(root, name)
    return None


def get_halacha_details(tefillin_fixture, default_ratio=0.70):
    """Computes detailed halacha geometry and metrics for display in Studio."""
    hairline_ratio = manage_gt.compute_hairline_detection(tefillin_fixture)
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

    eye_dist = float(np.hypot(eye_r[0] - eye_l[0], eye_r[1] - eye_l[1]))
    face_up = mesh_top - nose
    u_up = face_up / max(1.0, float(np.hypot(face_up[0], face_up[1])))

    hairline_pt = glabella + u_up * (hairline_ratio * eye_dist)

    lowest_edge_vec = np.array([box["x"] - hairline_pt[0], box["bottomY"] - hairline_pt[1]])
    dist_above = float(lowest_edge_vec[0] * u_up[0] + lowest_edge_vec[1] * u_up[1])
    margin = eye_dist * 0.05
    is_clearly_below = dist_above < -margin
    is_borderline = abs(dist_above) <= margin

    a = glabella
    b = mesh_top
    p = np.array([box["x"], box["y"]])
    line_len = float(np.hypot(b[0] - a[0], b[1] - a[1]))
    horiz_offset = 0.0
    if line_len > 0.1:
        horiz_offset = float(((b[0] - a[0]) * (a[1] - p[1]) - (b[1] - a[1]) * (a[0] - p[0])) / line_len)

    max_center_tol = eye_dist * 0.08
    is_centered = abs(horiz_offset) < max_center_tol

    if is_clearly_below:
        status = "FOREHEAD_ERROR"
        is_kosher = False
    elif not is_centered:
        status = "OFF_CENTER"
        is_kosher = True
    else:
        status = "ALIGNED"
        is_kosher = True

    return {
        "status": status,
        "is_kosher": is_kosher,
        "is_borderline": bool(is_borderline),
        "borderline_margin": round(margin, 2),
        "hairline_ratio": float(hairline_ratio),
        "dist_above": round(dist_above, 2),
        "is_scalp": bool(dist_above >= 0.0),
        "horiz_offset": round(horiz_offset, 2),
        "max_center_tol": round(max_center_tol, 2),
        "is_centered": bool(is_centered),
        "eye_dist": round(eye_dist, 2),
    }


def build_app_data():
    """Builds the comprehensive state bundle for the web UI."""
    fixtures = manage_gt.load_json(FIXTURES_FILE)
    gt_data = manage_gt.load_json(GT_FILE)

    hairline_gt = gt_data.get("hairline_gt", {})
    halacha_gt = gt_data.get("halacha_gt", {})
    benchmark = gt_data.get("benchmark", {})
    excluded_images = set(gt_data.get("excluded_images", []))

    faces_list = []
    mistakes_count = 0
    passes_count = 0
    active_faces_count = 0
    excluded_faces_count = 0

    for idx, face in enumerate(fixtures.get("faces", [])):
        name = face["name"]
        is_excluded = name in excluded_images
        detected = manage_gt.compute_hairline_detection(face)
        gt_info = hairline_gt.get(name, {})
        expected = gt_info.get("expected_ratio", 0.70)
        tol = gt_info.get("tolerance", 0.05)
        diff = abs(detected - expected)
        is_pass = diff <= tol

        if is_excluded:
            excluded_faces_count += 1
        else:
            active_faces_count += 1
            if is_pass:
                passes_count += 1
            else:
                mistakes_count += 1

        faces_list.append({
            "id": idx,
            "name": name,
            "width": face["width"],
            "height": face["height"],
            "landmarks": face["landmarks"],
            "detected_ratio": round(detected, 4),
            "expected_ratio": round(expected, 4),
            "tolerance": round(tol, 3),
            "diff": round(diff, 4),
            "is_pass": is_pass,
            "is_excluded": is_excluded,
            "image_url": f"/api/image?type=face&name={urllib.parse.quote(name)}",
        })

    mistake_percentage = round((mistakes_count / active_faces_count * 100) if active_faces_count > 0 else 0, 2)

    tefillin_list = []
    halacha_matches = 0
    active_tefillin_count = 0
    excluded_tefillin_count = 0

    for idx, item in enumerate(fixtures.get("tefillin", [])):
        name = item["name"]
        is_excluded = name in excluded_images
        details = get_halacha_details(item)
        gt_info = halacha_gt.get(name, {})
        expected_status = gt_info.get("expected_status", "ALIGNED")
        expected_kosher = gt_info.get("expected_kosher", True)
        notes = gt_info.get("notes", "")

        is_match = (details["status"] == expected_status) and (details["is_kosher"] == expected_kosher)
        if is_excluded:
            excluded_tefillin_count += 1
        else:
            active_tefillin_count += 1
            if is_match:
                halacha_matches += 1

        tefillin_list.append({
            "id": idx,
            "name": name,
            "rel_path": item.get("rel_path", name),
            "width": item["width"],
            "height": item["height"],
            "landmarks": item["landmarks"],
            "yolo_box": item["yoloBox"],
            "algo_details": details,
            "expected_status": expected_status,
            "expected_kosher": expected_kosher,
            "notes": notes,
            "is_match": is_match,
            "is_excluded": is_excluded,
            "image_url": f"/api/image?type=sample&name={urllib.parse.quote(name)}",
        })

    return {
        "benchmark": benchmark,
        "summary": {
            "total_faces": len(faces_list),
            "active_faces_count": active_faces_count,
            "excluded_faces_count": excluded_faces_count,
            "passes_count": passes_count,
            "mistakes_count": mistakes_count,
            "mistake_percentage": mistake_percentage,
            "benchmark_mistake_percentage": benchmark.get("best_mistake_percentage", 4.35),
            "total_tefillin": len(tefillin_list),
            "active_tefillin_count": active_tefillin_count,
            "excluded_tefillin_count": excluded_tefillin_count,
            "halacha_matches": halacha_matches,
        },
        "faces": faces_list,
        "tefillin": tefillin_list,
    }


class GTStudioHandler(BaseHTTPRequestHandler):
    def send_json(self, status_code, payload, is_head=False):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if not is_head:
            self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET(is_head=True)

    def do_GET(self, is_head=False):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/" or path == "/index.html":
            html_path = os.path.join(WEB_DIR, "index.html")
            if not os.path.exists(html_path):
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b"index.html not found")
                return

            with open(html_path, "rb") as f:
                content = f.read()

            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            if not is_head:
                self.wfile.write(content)
            return

        if path == "/api/data":
            try:
                data = build_app_data()
                self.send_json(200, data, is_head=is_head)
            except Exception as e:
                self.send_json(500, {"error": str(e)}, is_head=is_head)
            return

        if path == "/api/image":
            query = urllib.parse.parse_qs(parsed.query)
            img_type = query.get("type", [""])[0]
            name = query.get("name", [""])[0]

            if not name:
                self.send_response(400)
                self.end_headers()
                return

            img_path = None
            if img_type == "face":
                candidate = os.path.join(SELFIES_DIR, name)
                if os.path.exists(candidate):
                    img_path = candidate
            elif img_type == "sample":
                img_path = find_sample_image(name)

            if not img_path or not os.path.exists(img_path):
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b"Image file not found on disk")
                return

            mime, _ = mimetypes.guess_type(img_path)
            if not mime:
                mime = "image/jpeg"

            try:
                with open(img_path, "rb") as f:
                    content = f.read()

                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(content)))
                self.send_header("Cache-Control", "public, max-age=3600")
                self.end_headers()
                if not is_head:
                    self.wfile.write(content)
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode())
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"

        try:
            payload = json.loads(body) if body else {}
        except json.JSONDecodeError:
            self.send_json(400, {"error": "Invalid JSON"})
            return

        gt_data = manage_gt.load_json(GT_FILE)

        if path == "/api/save-hairline":
            name = payload.get("name")
            expected_ratio = payload.get("expected_ratio")
            tolerance = payload.get("tolerance", 0.05)

            if not name or expected_ratio is None:
                self.send_json(400, {"error": "Missing name or expected_ratio"})
                return

            if "hairline_gt" not in gt_data:
                gt_data["hairline_gt"] = {}

            gt_data["hairline_gt"][name] = {
                "expected_ratio": round(float(expected_ratio), 4),
                "tolerance": round(float(tolerance), 3),
            }
            manage_gt.save_json(GT_FILE, gt_data)
            self.send_json(200, {"success": True, "data": build_app_data()})
            return

        if path == "/api/save-halacha":
            name = payload.get("name")
            expected_status = payload.get("expected_status")
            expected_kosher = payload.get("expected_kosher")
            notes = payload.get("notes", "")

            if not name or not expected_status:
                self.send_json(400, {"error": "Missing name or expected_status"})
                return

            if "halacha_gt" not in gt_data:
                gt_data["halacha_gt"] = {}

            entry = gt_data["halacha_gt"].get(name, {})
            entry["expected_status"] = str(expected_status)
            if expected_kosher is not None:
                entry["expected_kosher"] = bool(expected_kosher)
            if notes is not None:
                entry["notes"] = str(notes)

            gt_data["halacha_gt"][name] = entry
            manage_gt.save_json(GT_FILE, gt_data)
            self.send_json(200, {"success": True, "data": build_app_data()})
            return

        if path == "/api/update-benchmark":
            data = build_app_data()
            mistakes = data["summary"]["mistakes_count"]
            total = data["summary"]["total_faces"]
            pct = data["summary"]["mistake_percentage"]

            bm = gt_data.setdefault("benchmark", {})
            bm["best_mistake_percentage"] = pct
            bm["best_mistake_count"] = mistakes
            bm["total_faces"] = total
            bm["notes"] = f"Updated via GT Studio (mistake rate {pct}%)"
            bm["last_updated"] = datetime.now().isoformat()

            active_tef = data["summary"].get("active_tefillin_count", 0)
            halacha_matches = data["summary"].get("halacha_matches", 0)
            if active_tef > 0:
                halacha_mistakes = active_tef - halacha_matches
                bm["best_halacha_mistake_count"] = halacha_mistakes
                bm["best_halacha_mistake_percentage"] = round((halacha_mistakes / active_tef) * 100.0, 2)
                bm["total_tefillin"] = active_tef

            manage_gt.save_json(GT_FILE, gt_data)
            self.send_json(200, {"success": True, "benchmark": gt_data["benchmark"]})
            return

        if path == "/api/exclude-image":
            name = payload.get("name")
            exclude = payload.get("exclude", True)

            if not name:
                self.send_json(400, {"error": "Missing image name"})
                return

            excluded = set(gt_data.get("excluded_images", []))
            if exclude:
                excluded.add(name)
            else:
                excluded.discard(name)

            gt_data["excluded_images"] = sorted(list(excluded))
            manage_gt.save_json(GT_FILE, gt_data)
            self.send_json(200, {"success": True, "data": build_app_data()})
            return

        if path == "/api/run-tests":
            script_path = os.path.join(SCRIPT_DIR, "test_algo.sh")
            try:
                proc = subprocess.run(
                    [script_path],
                    cwd=PROJECT_ROOT,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=45,
                )
                self.send_json(200, {
                    "exit_code": proc.returncode,
                    "output": proc.stdout,
                    "passed": proc.returncode == 0,
                })
            except Exception as e:
                self.send_json(500, {"error": str(e), "passed": False})
            return

        self.send_response(404)
        self.end_headers()


def start_server(port=8080, open_browser=True):
    for p in range(port, port + 20):
        try:
            server = ThreadingHTTPServer(("127.0.0.1", p), GTStudioHandler)
            url = f"http://127.0.0.1:{p}"
            print("=" * 80)
            print(f"🚀 Tefillin Ground Truth Studio is running at: {url}")
            print("   Press Ctrl+C to stop the server.")
            print("=" * 80)
            if open_browser:
                webbrowser.open(url)
            server.serve_forever()
            return
        except OSError as e:
            if "Address already in use" in str(e):
                continue
            raise e
    print("Could not find an open port.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Tefillin Ground Truth Studio Web App")
    parser.add_argument("--port", type=int, default=8080, help="Server port (default: 8080)")
    parser.add_argument("--no-browser", action="store_true", help="Do not open browser automatically")
    args = parser.parse_args()

    start_server(port=args.port, open_browser=not args.no_browser)
