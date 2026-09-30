"""
seed_pyq_diagram_questions.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Generates, optimizes to WebP, uploads to Firebase Cloud Storage, and seeds
authentic JEE & NEET STEM diagram questions directly into Firestore.

Also updates today's Daily Vault (2026-09-30) so students immediately see
diagram-based questions with pinch-to-zoom in the app!
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import os
import sys
import uuid
import datetime
import hashlib
import firebase_admin
from firebase_admin import credentials, firestore, storage

# Ensure scripts dir in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import stem_diagram_engine as sde
import diagram_processor as dp

SERVICE_ACCOUNT_PATH = os.path.join(SCRIPT_DIR, "serviceAccountKey.json")
DEFAULT_BUCKET_NAME = "apps-273d9.firebasestorage.app"


def init_firebase():
    if firebase_admin._apps:
        return firestore.client(), storage.bucket(DEFAULT_BUCKET_NAME)
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print(f"❌ Error: Service account key not found at {SERVICE_ACCOUNT_PATH}")
        sys.exit(1)
    cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
    firebase_admin.initialize_app(cred, {'storageBucket': DEFAULT_BUCKET_NAME})
    return firestore.client(), storage.bucket(DEFAULT_BUCKET_NAME)


def seed_diagram_questions(target_vault_date: str = "2026-09-30"):
    db, bucket = init_firebase()
    print("🚀 Initializing STEM Diagram Generation & Seeding Pipeline...")

    questions_ref = db.collection("questions")
    metadata_ref = db.collection("metadata")

    # High-yield questions with dedicated generator specs
    diagram_specs = [
        # 1. JEE Physics - Optics
        {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Optics",
            "questionText": "A ray of light is incident on a convex lens as shown in the ray diagram. If the focal length of the lens is $f = +20\\,\\mathrm{cm}$ and an object of height $1.2\\,\\mathrm{cm}$ is placed at $u = -35\\,\\mathrm{cm}$, what is the nature and position of the image formed?",
            "options": [
                "Real, inverted at $v = +46.7\\,\\mathrm{cm}$",
                "Virtual, erect at $v = -46.7\\,\\mathrm{cm}$",
                "Real, erect at $v = +20.0\\,\\mathrm{cm}$",
                "Virtual, inverted at $v = -20.0\\,\\mathrm{cm}$"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "Using lens formula: $\\frac{1}{v} - \\frac{1}{u} = \\frac{1}{f} \\implies \\frac{1}{v} = \\frac{1}{20} + \\frac{1}{-35} = \\frac{7 - 4}{140} = \\frac{3}{140} \\implies v = +46.67\\,\\mathrm{cm}$. Since $v > 0$, image is real and inverted on the other side of the lens.",
            "difficulty": "Medium",
            "spec_type": "optics"
        },
        # 2. JEE Physics - Current Electricity
        {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Current Electricity",
            "questionText": "In the balanced Wheatstone bridge circuit shown in the diagram, find the equivalent resistance between terminals A and C.",
            "options": [
                "$7.2\\,\\Omega$",
                "$4.8\\,\\Omega$",
                "$10.5\\,\\Omega$",
                "$14.0\\,\\Omega$"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "Since $\\frac{R_1}{R_2} = \\frac{4}{8} = 0.5$ and $\\frac{R_3}{R_4} = \\frac{6}{12} = 0.5$, the bridge is in balance ($I_G = 0$). Branch ADC has $4 + 8 = 12\\,\\Omega$ and branch ABC has $6 + 12 = 18\\,\\Omega$. $R_{eq} = \\frac{12 \\times 18}{12 + 18} = \\frac{216}{30} = 7.2\\,\\Omega$.",
            "difficulty": "Medium",
            "spec_type": "circuit"
        },
        # 3. JEE Physics - Thermodynamics
        {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Thermodynamics",
            "questionText": "An ideal gas is taken through the cyclic thermodynamic process A $\\to$ B $\\to$ C $\\to$ D $\\to$ A shown in the P-V indicator diagram. What is the net work done by the gas during one complete cycle?",
            "options": [
                "$+750\\,\\mathrm{J}$",
                "$+1500\\,\\mathrm{J}$",
                "$-750\\,\\mathrm{J}$",
                "Zero"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "The net work done during a cyclic process in a P-V diagram equals the enclosed area. $W = (P_2 - P_1)(V_2 - V_1) = (4.5 - 2.0)\\times 10^5\\,\\mathrm{Pa} \\times (5.0 - 2.0)\\times 10^{-3}\\,\\mathrm{m^3} = 2.5 \\times 10^5 \\times 3.0 \\times 10^{-3} = +750\\,\\mathrm{J}$ (clockwise cycle indicates positive net work).",
            "difficulty": "Medium",
            "spec_type": "pv_cycle"
        },
        # 4. JEE Physics - Motion In One Dimension
        {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Motion In One Dimension",
            "questionText": "The velocity-time ($v-t$) motion profile of a particle is shown in the figure. Calculate the total displacement covered by the particle from $t = 0$ to $t = 12\\,\\mathrm{s}$.",
            "options": [
                "$160\\,\\mathrm{m}$",
                "$200\\,\\mathrm{m}$",
                "$240\\,\\mathrm{m}$",
                "$120\\,\\mathrm{m}$"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "Displacement is given by the total area under the $v-t$ graph. For the trapezoid: $\\mathrm{Area} = \\frac{1}{2}(\\text{sum of parallel sides}) \\times \\text{height} = \\frac{1}{2}(12 + 4) \\times 20 = 8 \\times 20 = 160\\,\\mathrm{m}$.",
            "difficulty": "Easy",
            "spec_type": "kinematics"
        },
        # 5. JEE Chemistry - Chemical Kinetics
        {
            "examType": "JEE",
            "subject": "Chemistry",
            "chapter": "Chemical Kinetics",
            "questionText": "Based on the reaction coordinate potential energy profile shown in the diagram, what is the effect of adding a positive catalyst on the activation energy ($E_a$) and enthalpy change ($\\Delta H$)?",
            "options": [
                "$E_a$ decreases, while $\\Delta H$ remains unchanged",
                "Both $E_a$ and $\\Delta H$ decrease proportionally",
                "$E_a$ increases, while $\\Delta H$ decreases",
                "$\\Delta H$ decreases, while $E_a$ remains unchanged"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "A catalyst provides an alternative reaction pathway with a lower activation energy barrier ($E_a$), but does not alter the thermodynamic initial state of reactants or final state of products, leaving $\\Delta H$ completely unchanged.",
            "difficulty": "Easy",
            "spec_type": "reaction_energy"
        },
        # 6. NEET Biology - Genetics
        {
            "examType": "NEET",
            "subject": "Biology",
            "chapter": "Genetics",
            "questionText": "Analyze the human pedigree chart shown in the figure. What is the most probable pattern of inheritance for the indicated trait?",
            "options": [
                "Autosomal Dominant",
                "Autosomal Recessive",
                "X-linked Recessive",
                "Mitochondrial Inheritance"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "Key observations: (1) The trait appears in every generation without skipping; (2) Each affected offspring has at least one affected parent; (3) Both males and females are affected with equal frequency. These criteria establish an Autosomal Dominant inheritance pattern.",
            "difficulty": "Medium",
            "spec_type": "pedigree"
        },
        # 7. NEET Physics - Optics
        {
            "examType": "NEET",
            "subject": "Physics",
            "chapter": "Optics",
            "questionText": "An object is placed at a distance of $35\\,\\mathrm{cm}$ in front of a thin convex lens of focal length $20\\,\\mathrm{cm}$ as shown in the diagram. Find the image distance $v$ and linear magnification $m$.",
            "options": [
                "$v = +46.7\\,\\mathrm{cm},\\ m = -1.33$",
                "$v = -46.7\\,\\mathrm{cm},\\ m = +1.33$",
                "$v = +20.0\\,\\mathrm{cm},\\ m = -1.00$",
                "$v = +35.0\\,\\mathrm{cm},\\ m = +1.00$"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "By the lens equation: $\\frac{1}{v} - \\frac{1}{u} = \\frac{1}{f} \\implies \\frac{1}{v} = \\frac{1}{20} - \\frac{1}{35} = \\frac{7-4}{140} = \\frac{3}{140} \\implies v = +46.67\\,\\mathrm{cm}$. Magnification $m = \\frac{v}{u} = \\frac{+46.67}{-35} = -1.33$ (magnified, inverted).",
            "difficulty": "Medium",
            "spec_type": "optics"
        },
        # 8. NEET Physics - Current Electricity
        {
            "examType": "NEET",
            "subject": "Physics",
            "chapter": "Current Electricity",
            "questionText": "In the Wheatstone bridge circuit shown in the diagram, what is the current through the galvanometer $G$ when the battery voltage is $12\\,\\mathrm{V}$?",
            "options": [
                "$0\\,\\mathrm{A}$ (Zero)",
                "$1.0\\,\\mathrm{A}$",
                "$0.5\\,\\mathrm{A}$",
                "$2.0\\,\\mathrm{A}$"
            ],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "The bridge resistances satisfy the balance condition: $\\frac{R_1}{R_2} = \\frac{4}{8} = 0.5$ and $\\frac{R_3}{R_4} = \\frac{6}{12} = 0.5$. Since the potential at node B equals the potential at node D, no current flows through the central galvanometer branch ($I_G = 0$).",
            "difficulty": "Easy",
            "spec_type": "circuit"
        }
    ]

    seeded_docs = []

    for item in diagram_specs:
        spec_type = item.pop("spec_type")
        raw_png = sde.render_diagram_for_spec(spec_type)
        q_id = "q_" + hashlib.md5(f"{item['examType']}_{item['subject']}_{item['questionText']}".encode('utf-8')).hexdigest()
        item["id"] = q_id

        # Compress to WebP and upload to Firebase Storage
        print(f"  🎨 Generating WebP diagram for [{item['examType']} - {item['subject']}] ({spec_type})...")
        cdn_url = dp.process_and_upload_diagram(raw_png, item["examType"], item["subject"], q_id, is_solution=False, bucket=bucket)

        if not cdn_url:
            print(f"    ⚠️ Upload failed for {q_id}, skipping.")
            continue

        item["imageUrl"] = cdn_url
        item["packId"] = "allaccessyearly"
        item["isDailyVault"] = False
        item["createdAt"] = firestore.SERVER_TIMESTAMP

        # Save to main question bank
        questions_ref.document(q_id).set(item)
        print(f"    ✅ Uploaded & saved to question bank: {q_id} -> {cdn_url[:65]}...")
        seeded_docs.append(item)

    print(f"\n🎉 Successfully seeded {len(seeded_docs)} diagram questions into the bank!")

    # ─────────────────────────────────────────────────────────────────────────
    # Inject into Today's Daily Vault (2026-09-30)
    # ─────────────────────────────────────────────────────────────────────────
    print(f"\n⚡ Updating Today's Daily Vault ({target_vault_date}) with diagram questions...")

    today_vault_query = (
        questions_ref
        .where("vaultDate", "==", target_vault_date)
        .where("isDailyVault", "==", True)
        .get()
    )

    today_docs = list(today_vault_query)
    print(f"  Found {len(today_docs)} existing vault questions for {target_vault_date}.")

    jee_vault = [d for d in today_docs if d.to_dict().get("examType") == "JEE"]
    neet_vault = [d for d in today_docs if d.to_dict().get("examType") == "NEET"]

    timestamp = int(datetime.datetime.now().timestamp())
    batch = db.batch()

    # Inject into JEE vault (replace 3 questions with diagram questions)
    jee_diagram_items = [d for d in seeded_docs if d["examType"] == "JEE"][:4]
    jee_group_id = f"jee_vault_{target_vault_date}_{timestamp}"

    for i, diag_q in enumerate(jee_diagram_items):
        if i < len(jee_vault):
            target_doc_ref = jee_vault[i].reference
        else:
            target_doc_ref = questions_ref.document(f"vault_{target_vault_date}_{diag_q['id']}")

        payload = dict(diag_q)
        payload["isDailyVault"] = True
        payload["vaultDate"] = target_vault_date
        payload["vaultGroupId"] = jee_group_id
        batch.set(target_doc_ref, payload)

    # Inject into NEET vault (replace 3 questions with diagram questions)
    neet_diagram_items = [d for d in seeded_docs if d["examType"] == "NEET"][:3]
    neet_group_id = f"neet_vault_{target_vault_date}_{timestamp}"

    for i, diag_q in enumerate(neet_diagram_items):
        if i < len(neet_vault):
            target_doc_ref = neet_vault[i].reference
        else:
            target_doc_ref = questions_ref.document(f"vault_{target_vault_date}_{diag_q['id']}")

        payload = dict(diag_q)
        payload["isDailyVault"] = True
        payload["vaultDate"] = target_vault_date
        payload["vaultGroupId"] = neet_group_id
        batch.set(target_doc_ref, payload)

    # Bump metadata question_bank version counter so client app syncs immediately!
    metadata_ref.document("question_bank").set({
        "last_updated": firestore.SERVER_TIMESTAMP,
        "version": timestamp,
        "vault_date": target_vault_date,
        "jee_vault_group_id": jee_group_id,
        "neet_vault_group_id": neet_group_id,
        "diagram_support_enabled": True
    }, merge=True)

    batch.commit()
    print(f"🎉 Daily Vault for {target_vault_date} successfully updated with diagram questions!")
    print(f"   • JEE Group ID : {jee_group_id}")
    print(f"   • NEET Group ID: {neet_group_id}")
    print("   • Mobile clients will auto-sync on next app open!")


if __name__ == "__main__":
    seed_diagram_questions()
