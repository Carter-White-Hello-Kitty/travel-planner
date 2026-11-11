"""Upload a real PNG through the backend, download its returned URL, and save it locally.

Run with the backend, Docker services, S3 credentials, and CloudFront configured:
    SMOKE_URL=http://localhost:18085 python3 scripts/smoke/photo-upload-smoke.py

This smoke creates a test user and an S3 object. It is not run in CI.
"""

import json
import os
import struct
import urllib.error
import urllib.request
import uuid
import zlib
import sys
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from util.smoke_common import SmokeClient


OUTPUT = Path(__file__).resolve().parents[1] / "results" / "photo-upload-result.png"


def png_chunk(kind, data):
    content = kind + data
    return struct.pack(">I", len(data)) + content + struct.pack(">I", zlib.crc32(content) & 0xFFFFFFFF)


def sample_png():
    width, height = 96, 64
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter type: none
        for x in range(width):
            rows.extend((255 * x // (width - 1), 255 * y // (height - 1),
                         80 if (x // 16 + y // 16) % 2 else 200))
    return (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(bytes(rows)))
            + png_chunk(b"IEND", b""))


def configured_base_url():
    override = os.environ.get("PHOTO_SMOKE_BASE_URL")
    if override:
        return override.rstrip("/")
    env_file = Path(__file__).resolve().parents[2] / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith("travel.photos.base-url="):
                return line.split("=", 1)[1].strip().rstrip("/")
    raise AssertionError("Set PHOTO_SMOKE_BASE_URL or travel.photos.base-url in .env")


def main():
    client = SmokeClient()
    email = "photo-smoke-" + uuid.uuid4().hex + "@example.com"
    password = "LocalPhotoSmokePassword2026!"
    client.call("/auth/register", {"email": email, "password": password}, expected=201)
    token = client.call("/auth/login", {"email": email, "password": password})["data"]["accessToken"]

    image = sample_png()
    boundary = "PhotoSmoke" + uuid.uuid4().hex
    body = (f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="file"; filename="photo-smoke.png"\r\n'
            "Content-Type: image/png\r\n\r\n").encode() + image + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        client.base + "/travel-posts/photos",
        data=body,
        headers={
            "Origin": "http://localhost:3000",
            "X-Requested-With": "XMLHttpRequest",
            "X-XSRF-TOKEN": client.call("/auth/csrf")["data"]["token"],
            "Authorization": "Bearer " + token,
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json",
        },
    )
    try:
        response = client.opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        with error:
            payload = json.load(error)
        code = payload.get("error", {}).get("code", "unknown")
        raise AssertionError(f"/travel-posts/photos: HTTP {error.code}, error code {code}") from None
    with response:
        if response.status != 201:
            raise AssertionError(f"/travel-posts/photos: HTTP {response.status}, expected 201")
        payload = json.load(response)

    url = payload.get("data", {}).get("url")
    expected_base = configured_base_url()
    if (not isinstance(url, str) or not url.startswith(expected_base + "/travel-photos/")
            or not url.endswith(".png") or urlparse(url).scheme != "https"):
        raise AssertionError("Upload response did not return a PNG URL on the configured image domain")

    with urllib.request.urlopen(url, timeout=30) as download:
        if download.status != 200 or not download.headers.get("Content-Type", "").startswith("image/png"):
            raise AssertionError("The returned image URL did not serve a PNG")
        returned = download.read(len(image) + 1)
    if returned != image:
        raise AssertionError("The downloaded image differs from the uploaded PNG")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(returned)
    print("PASS: authenticated photo upload returned a readable image URL with identical PNG bytes")
    print(f"Image URL: {url}")
    print(f"Saved image: {OUTPUT}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
