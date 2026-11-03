#!/usr/bin/env python3
"""152 signed Cloudflare update manifests. Requires: py -m pip install cryptography"""
import argparse
import base64
import getpass
import hashlib
import json
from pathlib import Path
import zipfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

DEFAULT_PRIVATE = Path.home() / ".152-signing" / "private.pem"
DEFAULT_PUBLIC = Path.home() / ".152-signing" / "public.txt"
DOMAIN = "https://152-updates.torclassic01.workers.dev"
MAX_SIZE = 25 * 1024 * 1024


def payload(version, minecraft, download, sha256, size):
    return ("152-update-manifest-v1\n"
            + version + "\n" + minecraft + "\n" + download + "\n"
            + sha256 + "\n" + str(size) + "\n").encode("utf-8")


def keygen(args):
    if args.private.exists() or args.public.exists():
        raise SystemExit("Key file already exists. Refusing to overwrite signing identity.")
    password = getpass.getpass("Choose a strong private-key password: ").encode("utf-8")
    confirmation = getpass.getpass("Repeat password: ").encode("utf-8")
    if not password or password != confirmation:
        raise SystemExit("Passwords must match and must not be empty.")
    key = Ed25519PrivateKey.generate()
    private = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.BestAvailableEncryption(password))
    pub = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    args.private.parent.mkdir(parents=True, exist_ok=True)
    args.public.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create prevents silent key replacement.
    with args.private.open("xb") as stream:
        stream.write(private)
    with args.public.open("x", encoding="ascii") as stream:
        stream.write(base64.b64encode(pub).decode("ascii") + "\n")
    print("Private key stored locally:", args.private)
    print("Public key (safe to commit):", args.public)
    print("Put ONLY the public file's Base64 line into src/main/resources/assets/untitled/update-public-key.txt")
    print("Keep the private key and its password outside the repo and cloud.")


def sign(args):
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit("JAR not found: " + str(jar))
    expected = "152-" + args.version + ".jar"
    if jar.name != expected:
        raise SystemExit("JAR filename must be " + expected)
    size = jar.stat().st_size
    if size < 1 or size > MAX_SIZE:
        raise SystemExit("JAR size is outside Static Assets limits")
    with zipfile.ZipFile(jar) as archive:
        meta = json.loads(archive.read("fabric.mod.json").decode("utf-8"))
        if meta.get("id") != "untitled" or meta.get("version") != args.version:
            raise SystemExit("JAR mod id/version do not match release")
        if "untitled/untitled/client/UntitledClient.class" not in archive.namelist():
            raise SystemExit("Client entrypoint missing")
    with jar.open("rb") as stream:
        sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    url = DOMAIN + "/downloads/" + expected
    password = getpass.getpass("Private-key password: ").encode("utf-8")
    key = serialization.load_pem_private_key(args.private.read_bytes(), password=password)
    if not isinstance(key, Ed25519PrivateKey):
        raise SystemExit("Expected an Ed25519 private key")
    message = payload(args.version, args.minecraft, url, sha256, size)
    signature = base64.b64encode(key.sign(message)).decode("ascii")
    manifest = {
        "version": args.version,
        "minecraft": args.minecraft,
        "download": url,
        "sha256": sha256,
        "size": size,
        "signature": signature,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    pub_der = base64.b64decode(args.public.read_text(encoding="ascii").strip())
    pub = serialization.load_der_public_key(pub_der)
    if not isinstance(pub, Ed25519PublicKey):
        raise SystemExit("Expected an Ed25519 public key")
    pub.verify(base64.b64decode(signature), message)
    print("Signed manifest and verified locally:", args.out)
    print("SHA-256:", sha256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    g = sub.add_parser("keygen", help="Create a local encrypted Ed25519 private key")
    g.add_argument("--private", type=Path, default=DEFAULT_PRIVATE)
    g.add_argument("--public", type=Path, default=DEFAULT_PUBLIC)
    g.set_defaults(fn=keygen)
    s = sub.add_parser("sign", help="Sign an actual built JAR manifest")
    s.add_argument("--jar", type=Path, required=True)
    s.add_argument("--version", required=True)
    s.add_argument("--minecraft", default="1.21.4")
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--private", type=Path, default=DEFAULT_PRIVATE)
    s.add_argument("--public", type=Path, default=DEFAULT_PUBLIC)
    s.set_defaults(fn=sign)
    args = parser.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
