#!/usr/bin/env python3
"""
Extracts raw MediaPipe landmarks, YOLO candidate boxes, and forehead pixel patches
from external test images into a lightweight JSON fixture file for Kotlin JUnit tests.
No images are stored in the repo.

Supports extracting:
  - All diverse selfies from tefillin_app/diverse_men_selfies_raw
  - All tefillin samples from tefillin_app/samples (OK, WRONG, Photos-1-001, cropped)
Preserves existing Ground Truth entries in algo_ground_truth.json and respects excluded_images.
"""

import os
import sys
import json
import base64
import cv2
import numpy as np
from PIL import Image

# Suppress MediaPipe C++ log noise
os.environ["GLOG_minloglevel"] = "2"

import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import onnxruntime as ort

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")
FACE_MODEL_PATH = os.path.join(ASSETS_DIR, "face_landmarker.task")
YOLO_MODEL_PATH = os.path.join(ASSETS_DIR, "best_fp16.onnx")

FIXTURES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "test", "resources")
FIXTURES_FILE = os.path.join(FIXTURES_DIR, "algo_test_fixtures.json")
GT_FILE = os.path.join(FIXTURES_DIR, "algo_ground_truth.json")

DEV_DIR = os.path.dirname(PROJECT_ROOT)
SELFIES_DIR = os.path.join(DEV_DIR, "tefillin_app", "diverse_men_selfies_raw")
SAMPLES_DIR = os.path.join(DEV_DIR, "tefillin_app", "samples")

sys.path.insert(0, SCRIPT_DIR)
import manage_gt

base_opts = python.BaseOptions(model_asset_path=FACE_MODEL_PATH)
opts = vision.FaceLandmarkerOptions(
    base_options=base_opts,
    running_mode=vision.RunningMode.IMAGE,
    num_faces=1,
    min_face_detection_confidence=0.35,
    min_face_presence_confidence=0.35,
)
detector = vision.FaceLandmarker.create_from_options(opts)
yolo_session = ort.InferenceSession(YOLO_MODEL_PATH)
yolo_input_name = yolo_session.get_inputs()[0].name


def detect_yolo(bgr_img):
    h, w = bgr_img.shape[:2]
    scale = min(640.0 / w, 640.0 / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    resized = cv2.resize(bgr_img, (nw, nh))

    canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
    pad_x = (640 - nw) // 2
    pad_y = (640 - nh) // 2
    canvas[pad_y:pad_y + nh, pad_x:pad_x + nw] = resized

    blob = canvas[:, :, ::-1].transpose(2, 0, 1).astype(np.float32) / 255.0
    blob = np.expand_dims(blob, axis=0)

    outputs = yolo_session.run(None, {yolo_input_name: blob})[0]
    preds = outputs[0].T

    valid = preds[preds[:, 4] > 0.35]
    if len(valid) == 0:
        return None

    best = valid[np.argmax(valid[:, 4])]
    cx = (best[0] - pad_x) / scale
    cy = (best[1] - pad_y) / scale
    bw = best[2] / scale
    bh = best[3] / scale

    return {
        "x": round(float(cx), 2),
        "y": round(float(cy), 2),
        "boxWidth": round(float(bw), 2),
        "boxHeight": round(float(bh), 2),
        "bottomY": round(float(cy + bh / 2.0), 2),
        "confidence": round(float(best[4]), 4),
    }


def extract_forehead_patch(gray_img, glabella_pt, eye_dist):
    h, w = gray_img.shape[:2]
    gx, gy = glabella_pt
    x_min = max(0, int(round(gx - 0.45 * eye_dist)))
    x_max = min(w, int(round(gx + 0.45 * eye_dist)))
    y_min = max(0, int(round(gy - 1.25 * eye_dist)))
    y_max = min(h, int(round(gy + 0.15 * eye_dist)))

    if x_max <= x_min or y_max <= y_min:
        return None

    patch = gray_img[y_min:y_max, x_min:x_max]
    b64 = base64.b64encode(patch.tobytes()).decode("ascii")

    return {
        "originX": x_min,
        "originY": y_min,
        "patchWidth": int(x_max - x_min),
        "patchHeight": int(y_max - y_min),
        "grayBase64": b64,
    }


def main():
    print(f"Extracting test fixtures from:")
    print(f"  Selfies: {SELFIES_DIR}")
    print(f"  Samples: {SAMPLES_DIR}")
    os.makedirs(FIXTURES_DIR, exist_ok=True)

    # Load existing ground truth data
    gt_data = {}
    if os.path.exists(GT_FILE):
        gt_data = manage_gt.load_json(GT_FILE)

    hairline_gt = gt_data.get("hairline_gt", {})
    halacha_gt = gt_data.get("halacha_gt", {})
    excluded_images = set(gt_data.get("excluded_images", []))

    # 1. Face images for Trichion detection (Diverse Selfies)
    face_fixtures = []
    face_candidates = sorted([
        f for f in os.listdir(SELFIES_DIR)
        if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
    ])

    print(f"\nProcessing {len(face_candidates)} selfie images...")
    for idx, fname in enumerate(face_candidates, 1):
        fpath = os.path.join(SELFIES_DIR, fname)
        try:
            pil_img = Image.open(fpath).convert("RGB")
            rgb_arr = np.array(pil_img)
            w, h = pil_img.size
            gray = cv2.cvtColor(rgb_arr, cv2.COLOR_RGB2GRAY)

            mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_arr)
            res = detector.detect(mp_img)
            if not res.face_landmarks:
                print(f"  [{idx}/{len(face_candidates)}] Skipping (no front face): {fname}")
                continue

            lms = [
                [round(lm.x, 5), round(lm.y, 5), round(lm.z, 5)]
                for lm in res.face_landmarks[0]
            ]

            glabella = np.array([lms[8][0] * w, lms[8][1] * h])
            eye_left = np.array([lms[33][0] * w, lms[33][1] * h])
            eye_right = np.array([lms[263][0] * w, lms[263][1] * h])
            eye_dist = np.hypot(eye_right[0] - eye_left[0], eye_right[1] - eye_left[1])

            patch = extract_forehead_patch(gray, glabella, eye_dist)

            fixture_entry = {
                "name": fname,
                "width": w,
                "height": h,
                "landmarks": lms,
                "patch": patch,
            }
            face_fixtures.append(fixture_entry)

            # Auto-initialize GT if not already defined
            if fname not in hairline_gt:
                detected_ratio = manage_gt.compute_hairline_detection(fixture_entry)
                hairline_gt[fname] = {
                    "expected_ratio": round(float(detected_ratio), 4),
                    "tolerance": 0.05,
                }
        except Exception as e:
            print(f"  Error reading {fname}: {e}")

    print(f"Successfully extracted {len(face_fixtures)} face fixtures.")

    # 2. Tefillin images for alignment classification
    tefillin_fixtures = []
    sample_files = []
    for root, _, files in os.walk(SAMPLES_DIR):
        for f in sorted(files):
            if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
                sample_files.append((root, f))

    print(f"\nProcessing {len(sample_files)} tefillin sample images...")
    for idx, (root, fname) in enumerate(sample_files, 1):
        fpath = os.path.join(root, fname)
        rel_dir = os.path.relpath(root, SAMPLES_DIR)
        try:
            pil_img = Image.open(fpath).convert("RGB")
            rgb_arr = np.array(pil_img)
            w, h = pil_img.size
            gray = cv2.cvtColor(rgb_arr, cv2.COLOR_RGB2GRAY)
            bgr = cv2.cvtColor(rgb_arr, cv2.COLOR_RGB2BGR)

            mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_arr)
            res = detector.detect(mp_img)
            if not res.face_landmarks:
                print(f"  [{idx}/{len(sample_files)}] Skipping (no face): {fname}")
                continue

            box = detect_yolo(bgr)
            if box is None:
                print(f"  [{idx}/{len(sample_files)}] Skipping (no tefillin box): {fname}")
                continue

            lms = [
                [round(lm.x, 5), round(lm.y, 5), round(lm.z, 5)]
                for lm in res.face_landmarks[0]
            ]

            glabella = np.array([lms[8][0] * w, lms[8][1] * h])
            eye_left = np.array([lms[33][0] * w, lms[33][1] * h])
            eye_right = np.array([lms[263][0] * w, lms[263][1] * h])
            eye_dist = np.hypot(eye_right[0] - eye_left[0], eye_right[1] - eye_left[1])

            patch = extract_forehead_patch(gray, glabella, eye_dist)

            fixture_entry = {
                "name": fname,
                "rel_path": os.path.join(rel_dir, fname) if rel_dir != "." else fname,
                "width": w,
                "height": h,
                "landmarks": lms,
                "yoloBox": box,
                "patch": patch,
            }
            tefillin_fixtures.append(fixture_entry)

            # Auto-initialize GT if not already defined
            if fname not in halacha_gt:
                status, is_kosher = manage_gt.compute_halacha_detection(fixture_entry)
                note = f"Sample from {rel_dir}"
                if "WRONG" in rel_dir.upper():
                    status = "FOREHEAD_ERROR"
                    is_kosher = False
                    note = "WRONG folder - forehead error"
                elif "OK" in rel_dir.upper() and status == "FOREHEAD_ERROR":
                    status = "ALIGNED"
                    is_kosher = True
                    note = "OK folder sample"

                halacha_gt[fname] = {
                    "expected_status": status,
                    "expected_kosher": is_kosher,
                    "notes": note,
                }
        except Exception as e:
            print(f"  Error processing sample {fname}: {e}")

    print(f"Successfully extracted {len(tefillin_fixtures)} tefillin sample fixtures.")

    # Write out algo_test_fixtures.json
    output_data = {
        "version": "2.0",
        "description": "Pre-extracted facial landmarks, YOLO candidate boxes, and forehead patches for all tefillin & face test images.",
        "faces": face_fixtures,
        "tefillin": tefillin_fixtures,
    }

    with open(FIXTURES_FILE, "w", encoding="utf-8") as f:
        json.dump(output_data, f, ensure_ascii=False)

    fixtures_size_mb = os.path.getsize(FIXTURES_FILE) / (1024 * 1024)
    print(f"\nWritten {len(face_fixtures)} faces and {len(tefillin_fixtures)} tefillin to {FIXTURES_FILE} ({fixtures_size_mb:.2f} MB)")

    # Calculate mistake rate on non-excluded faces
    active_faces = [f for f in face_fixtures if f["name"] not in excluded_images]
    mistakes = 0
    for face in active_faces:
        name = face["name"]
        det = manage_gt.compute_hairline_detection(face)
        gt_info = hairline_gt.get(name, {})
        exp = gt_info.get("expected_ratio", 0.70)
        tol = gt_info.get("tolerance", 0.05)
        if abs(det - exp) > tol:
            mistakes += 1

    total_active = len(active_faces)
    pct = round((mistakes / total_active * 100) if total_active > 0 else 0, 2)

    gt_data["hairline_gt"] = hairline_gt
    gt_data["halacha_gt"] = halacha_gt
    gt_data["excluded_images"] = sorted(list(excluded_images))
    gt_data["benchmark"] = {
        "best_mistake_percentage": pct,
        "best_mistake_count": mistakes,
        "total_faces": total_active,
        "notes": f"Baseline for full dataset of {total_active} active faces ({mistakes} mistakes, {pct}%)",
    }

    manage_gt.save_json(GT_FILE, gt_data)
    print(f"Updated Ground Truth benchmark: {mistakes}/{total_active} mistakes ({pct}%)")


if __name__ == "__main__":
    main()
