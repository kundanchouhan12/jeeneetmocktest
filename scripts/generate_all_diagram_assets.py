"""
generate_all_diagram_assets.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Renders all deterministic STEM diagrams into optimized WebP assets and
distributes them into both:
1. `questions/diagrams/{exam}/{subject}/{q_id}.webp` (for GitHub / CDN hosting)
2. `app/src/main/assets/diagrams/{q_id}.webp` (for 100% offline, 0ms mobile loading)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import os
import sys
import hashlib

# Ensure scripts dir in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import stem_diagram_engine as sde
import diagram_processor as dp
from stem_diagram_pipeline import get_diagram_question_definitions

def generate_all():
    assets_dir = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "diagrams")
    os.makedirs(assets_dir, exist_ok=True)
    
    defns = get_diagram_question_definitions()
    print(f"🎨 Generating {len(defns)} catalog diagram assets...")

    generated = 0
    for idx, defn in enumerate(defns, 1):
        # Primary q_id
        q_id = "q_" + hashlib.md5(f"{defn.exam_type}_{defn.subject}_{defn.question_text}".encode("utf-8")).hexdigest()
        
        # Render PNG
        png_bytes = sde.render_diagram_for_spec(defn.spec_type, params=defn.params)
        webp_bytes = dp.compress_diagram_to_webp(png_bytes)
        if not webp_bytes:
            print(f"❌ Failed to compress WebP for Defn {idx} ({defn.spec_type})")
            continue

        # Save to questions/diagrams/
        rel_dir = os.path.join(REPO_ROOT, "questions", "diagrams", defn.exam_type.lower(), defn.subject.lower())
        os.makedirs(rel_dir, exist_ok=True)
        repo_file = os.path.join(rel_dir, f"{q_id}.webp")
        with open(repo_file, "wb") as f:
            f.write(webp_bytes)

        # Save to Android assets
        asset_file = os.path.join(assets_dir, f"{q_id}.webp")
        with open(asset_file, "wb") as f:
            f.write(webp_bytes)

        print(f"  ✅ [{defn.exam_type}-{defn.subject}] {defn.spec_type} -> {q_id}.webp ({len(webp_bytes)} bytes)")
        generated += 1

        # Also alias for punnett square in case legacy hash was used
        if defn.spec_type == "punnett_square":
            alt_id = "q_fb2a4d3e62ebffb4796c13deabbcc887"
            alt_repo_file = os.path.join(rel_dir, f"{alt_id}.webp")
            with open(alt_repo_file, "wb") as f:
                f.write(webp_bytes)
            alt_asset_file = os.path.join(assets_dir, f"{alt_id}.webp")
            with open(alt_asset_file, "wb") as f:
                f.write(webp_bytes)
            print(f"  ✅ [Alias generated] {defn.spec_type} -> {alt_id}.webp")

    # Also copy existing questions/diagrams to assets
    existing_dir = os.path.join(REPO_ROOT, "questions", "diagrams")
    for root, _, files in os.walk(existing_dir):
        for f in files:
            if f.endswith(".webp"):
                src_path = os.path.join(root, f)
                dst_path = os.path.join(assets_dir, f)
                if not os.path.exists(dst_path):
                    with open(src_path, "rb") as rf:
                        data = rf.read()
                    with open(dst_path, "wb") as wf:
                        wf.write(data)
                    print(f"  📦 Copied existing diagram to assets: {f}")

    all_assets = [f for f in os.listdir(assets_dir) if f.endswith(".webp")]
    print(f"\n🎉 Done! Total {len(all_assets)} diagrams now bundled in app/src/main/assets/diagrams/")

if __name__ == "__main__":
    generate_all()
