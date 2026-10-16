#!/usr/bin/env python3
"""Export the model's base audio profiles without executing model code.

The mood-tag batch tool reads these profiles and uses the backend's shared
MoodTrackScorer for all distance calculations. This AST exporter keeps the
profile table in Model-Detect_CamXuc/music_mapping.py as the source of truth.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
from pathlib import Path


DEFAULT_SOURCE = (
    Path(__file__).resolve().parents[2]
    / "Model-Detect_CamXuc"
    / "DetectEmotions"
    / "music_mapping.py"
)
REQUIRED_FIELDS = (
    "valence",
    "energy",
    "danceability",
    "acousticness",
    "mode",
    "target_tempo",
    "seed_genres",
    "description",
)


def find_profiles_assignment(tree: ast.Module) -> ast.Dict:
    for node in tree.body:
        targets = []
        if isinstance(node, ast.Assign):
            targets = node.targets
        elif isinstance(node, ast.AnnAssign):
            targets = [node.target]
        if any(isinstance(target, ast.Name) and target.id == "BASE_EMOTION_PROFILES" for target in targets):
            if not isinstance(node.value, ast.Dict):
                raise ValueError("BASE_EMOTION_PROFILES must be a literal dictionary")
            return node.value
    raise ValueError("BASE_EMOTION_PROFILES was not found in model source")


def export_profiles(source: Path) -> dict:
    source_text = source.read_text(encoding="utf-8")
    tree = ast.parse(source_text, filename=str(source))
    profiles_node = find_profiles_assignment(tree)
    profiles = []

    for label_node, profile_node in zip(profiles_node.keys, profiles_node.values):
        label = ast.literal_eval(label_node)
        if not isinstance(profile_node, ast.Call) or not isinstance(profile_node.func, ast.Name) or profile_node.func.id != "AudioProfile":
            raise ValueError(f"Profile {label!r} is not an AudioProfile constructor")
        fields = {keyword.arg: ast.literal_eval(keyword.value) for keyword in profile_node.keywords if keyword.arg}
        missing = [field for field in REQUIRED_FIELDS if field not in fields]
        if missing:
            raise ValueError(f"Profile {label!r} is missing fields: {', '.join(missing)}")
        if not isinstance(label, str) or not isinstance(fields["seed_genres"], list):
            raise ValueError(f"Profile {label!r} has invalid label or seed_genres")
        profiles.append(
            {
                "label": label,
                "tag": re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_"),
                "valence": fields["valence"],
                "energy": fields["energy"],
                "danceability": fields["danceability"],
                "acousticness": fields["acousticness"],
                "mode": fields["mode"],
                "targetTempo": fields["target_tempo"],
                "seedGenres": fields["seed_genres"],
                "description": fields["description"],
            }
        )

    if not profiles:
        raise ValueError("No emotion profiles were exported")
    tags = [profile["tag"] for profile in profiles]
    if len(tags) != len(set(tags)):
        raise ValueError("Emotion labels produce duplicate canonical tags")

    return {
        "schemaVersion": 1,
        "sourcePath": str(source.resolve()),
        "sourceSha256": hashlib.sha256(source_text.encode("utf-8")).hexdigest(),
        "profiles": profiles,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="Path to DetectEmotions/music_mapping.py")
    parser.add_argument("--output", type=Path, required=True, help="Output JSON profile bundle")
    args = parser.parse_args()

    if not args.source.is_file():
        parser.error(f"Model profile source does not exist: {args.source}")

    bundle = export_profiles(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(bundle['profiles'])} profiles from {bundle['sourcePath']}")
    print(f"Source SHA-256: {bundle['sourceSha256']}")
    print(f"Output: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
