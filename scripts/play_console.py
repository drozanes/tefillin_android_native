#!/usr/bin/env python3
"""
Play Console CLI Helper for Tefillin Mirror
Automates Google Play Developer API operations:
- Checking release track status
- Uploading and publishing App Bundles (AAB)
- Fetching reviews
- Fetching crash & ANR reporting metrics
"""

import os
import sys
import argparse
import json
import subprocess
import warnings
from pathlib import Path

# Suppress python deprecation warnings from google-auth in terminal output
warnings.filterwarnings("ignore")

try:
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload
    from googleapiclient.errors import HttpError
except ImportError:
    print("Error: Missing Google API dependencies.")
    print("Please run inside the project virtualenv:")
    print("  ./.venv/bin/pip install google-api-python-client google-auth")
    sys.exit(1)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_KEY_FILE = PROJECT_ROOT / "play-service-account.json"
PACKAGE_NAME = "com.tefillin.mirror"
DEFAULT_AAB_PATH = PROJECT_ROOT / "app/build/outputs/bundle/release/app-release.aab"

SCOPES = [
    "https://www.googleapis.com/auth/androidpublisher",
    "https://www.googleapis.com/auth/playdeveloperreporting",
]

def get_publisher_service(key_file: Path):
    if not key_file.exists():
        print(f"Error: Service account key not found at: {key_file}")
        sys.exit(1)
    credentials = service_account.Credentials.from_service_account_file(
        str(key_file),
        scopes=SCOPES
    )
    return build("androidpublisher", "v3", credentials=credentials)

def get_reporting_service(key_file: Path):
    if not key_file.exists():
        print(f"Error: Service account key not found at: {key_file}")
        sys.exit(1)
    credentials = service_account.Credentials.from_service_account_file(
        str(key_file),
        scopes=SCOPES
    )
    return build("playdeveloperreporting", "v1beta1", credentials=credentials)

def cmd_status(args):
    service = get_publisher_service(args.key)
    print(f"Checking Google Play tracks for package: {PACKAGE_NAME}...")
    try:
        app_edit = service.edits().insert(packageName=PACKAGE_NAME, body={}).execute()
        edit_id = app_edit["id"]
        tracks_res = service.edits().tracks().list(packageName=PACKAGE_NAME, editId=edit_id).execute()
        service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()

        tracks = tracks_res.get("tracks", [])
        print("\n=== Play Console Track Status ===")
        for track in tracks:
            track_name = track.get("track")
            releases = track.get("releases", [])
            print(f"\nTrack: [{track_name.upper()}]")
            if not releases:
                print("  (No active releases)")
                continue
            for r in releases:
                v_codes = ", ".join(r.get("versionCodes", []))
                status = r.get("status", "unknown")
                name = r.get("name", "N/A")
                print(f"  - Release: {name}")
                print(f"    Version Code(s): {v_codes}")
                print(f"    Status: {status}")
                notes = r.get("releaseNotes", [])
                if notes:
                    print("    Notes:")
                    for note in notes:
                        print(f"      [{note.get('language')}]: {note.get('text')}")
        print("\n=================================")
    except HttpError as e:
        print(f"API Error: {e}")
        sys.exit(1)

def cmd_upload(args):
    aab_path = Path(args.aab)
    if not aab_path.exists():
        if args.build:
            print("AAB not found. Building release bundle with Gradle...")
            cmd = ["./gradlew", "bundleRelease"]
            res = subprocess.run(cmd, cwd=PROJECT_ROOT)
            if res.returncode != 0:
                print("Gradle build failed!")
                sys.exit(1)
        else:
            print(f"Error: Bundle file not found at: {aab_path}")
            print("Pass --build to build it automatically or run ./gradlew bundleRelease first.")
            sys.exit(1)

    service = get_publisher_service(args.key)
    track_name = args.track
    status = args.status

    print(f"Starting upload to Google Play track: '{track_name}'...")
    print(f"Bundle file: {aab_path} ({aab_path.stat().st_size / (1024*1024):.2f} MB)")

    try:
        # 1. Create edit
        edit_req = service.edits().insert(packageName=PACKAGE_NAME, body={})
        edit = edit_req.execute()
        edit_id = edit["id"]
        print(f"Created edit session: {edit_id}")

        # 2. Upload bundle
        print("Uploading App Bundle to Google Play (this may take 10-30 seconds)...")
        media = MediaFileUpload(str(aab_path), mimetype="application/octet-stream", resumable=True)
        bundle_upload = service.edits().bundles().upload(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            media_body=media
        ).execute()

        version_code = bundle_upload["versionCode"]
        print(f"Uploaded successfully! Assigned Version Code: {version_code}")

        # 3. Prepare release payload
        release_notes = []
        if args.notes_he:
            release_notes.append({"language": "he-IL", "text": args.notes_he})
        if args.notes_en:
            release_notes.append({"language": "en-US", "text": args.notes_en})

        release_body = {
            "name": args.name or f"v{version_code}",
            "versionCodes": [str(version_code)],
            "status": status,
        }
        if release_notes:
            release_body["releaseNotes"] = release_notes

        track_body = {
            "track": track_name,
            "releases": [release_body]
        }

        # 4. Update track
        print(f"Updating track '{track_name}' with version code {version_code} (status={status})...")
        service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=track_name,
            body=track_body
        ).execute()

        # 5. Commit edit
        print("Committing and publishing changes...")
        commit_res = service.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print(f"\nSUCCESS! Release successfully published to '{track_name}'!")
        print(f"Version Code: {version_code}")
        print(f"Commit ID: {commit_res.get('id')}")

    except HttpError as e:
        print(f"\nAPI Error during upload: {e}")
        sys.exit(1)

def cmd_reviews(args):
    service = get_publisher_service(args.key)
    print(f"Fetching reviews for: {PACKAGE_NAME}...")
    try:
        res = service.reviews().list(packageName=PACKAGE_NAME).execute()
        reviews = res.get("reviews", [])
        if not reviews:
            print("No reviews found yet.")
            return

        print(f"\nFound {len(reviews)} review(s):")
        for rev in reviews:
            author = rev.get("authorName", "Anonymous")
            comments = rev.get("comments", [])
            for c in comments:
                user_comm = c.get("userComment", {})
                star_rating = user_comm.get("starRating", 0)
                text = user_comm.get("text", "").strip()
                device = user_comm.get("deviceMetadata", {}).get("productName", "")
                stars = "★" * star_rating + "☆" * (5 - star_rating)
                print(f"\n[{stars}] by {author} ({device}):")
                print(f"  \"{text}\"")
    except HttpError as e:
        print(f"API Error fetching reviews: {e}")
        sys.exit(1)

def cmd_reports(args):
    try:
        service = get_reporting_service(args.key)
        metric_set = f"apps/{PACKAGE_NAME}/crashRateMetricSet"
        print(f"Fetching Play Vitals Crash & ANR reports for {PACKAGE_NAME}...")
        res = service.vitals().crashrate().get(name=metric_set).execute()
        print(json.dumps(res, indent=2))
    except HttpError as e:
        err_msg = str(e)
        if "SERVICE_DISABLED" in err_msg or "has not been used" in err_msg:
            print("\nNotice: Google Play Developer Reporting API is currently disabled in your Google Cloud Project.")
            print("To enable crash & performance reports via API:")
            print("1. Open: https://console.cloud.google.com/apis/library/playdeveloperreporting.googleapis.com")
            print("2. Click 'Enable'.")
            print("3. You can also view full crash & ANR reports directly in Play Console under 'Quality' -> 'Android Vitals'.")
        else:
            print(f"Reporting API response: {e}")

def main():
    parser = argparse.ArgumentParser(description="Google Play Developer API helper for Tefillin Mirror")
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY_FILE, help="Path to service account JSON file")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # status
    parser_status = subparsers.add_parser("status", help="Get current tracks and release status")
    parser_status.set_defaults(func=cmd_status)

    # upload
    parser_upload = subparsers.add_parser("upload", help="Build and upload release bundle to Play Console")
    parser_upload.add_argument("--aab", default=str(DEFAULT_AAB_PATH), help="Path to .aab file")
    parser_upload.add_argument("--track", default="internal", choices=["internal", "alpha", "beta", "production"], help="Target track")
    parser_upload.add_argument("--status", default="completed", choices=["completed", "draft", "inProgress", "halted"], help="Release status")
    parser_upload.add_argument("--name", default=None, help="Release name")
    parser_upload.add_argument("--notes-he", default="עדכון גרסה ותיקוני ממשק", help="Release notes in Hebrew")
    parser_upload.add_argument("--notes-en", default="Bug fixes and UI improvements", help="Release notes in English")
    parser_upload.add_argument("--build", action="store_true", help="Build release bundle with Gradle if missing")
    parser_upload.set_defaults(func=cmd_upload)

    # reviews
    parser_reviews = subparsers.add_parser("reviews", help="Fetch user reviews and ratings")
    parser_reviews.set_defaults(func=cmd_reviews)

    # reports
    parser_reports = subparsers.add_parser("reports", help="Check crash & ANR statistics")
    parser_reports.set_defaults(func=cmd_reports)

    args = parser.parse_args()
    args.func(args)

if __name__ == "__main__":
    main()
