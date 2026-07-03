import re
import json
import os

# To use PDF extraction, install: pip install PyPDF2
try:
    import PyPDF2
except ImportError:
    PyPDF2 = None

def parse_questions_from_text(text, exam_type="JEE", subject="Physics", chapter="Unknown"):
    """
    Regex-based parser for common PYQ formats.
    Expects format:
    Q1. Question text here...
    (A) Option 1
    (B) Option 2
    (C) Option 3
    (D) Option 4
    Answer: A
    Explanation: Because...
    """
    # Split by "Q" followed by a number and period
    blocks = re.split(r'\nQ\d+\.', text)
    questions = []

    for block in blocks:
        if not block.strip(): continue
        
        try:
            # 1. Extract Question Text (everything before (A))
            q_text_match = re.search(r'(.*?)(?=\(A\))', block, re.DOTALL)
            if not q_text_match: continue
            q_text = q_text_match.group(1).strip()

            # 2. Extract Options
            options = []
            options.append(re.search(r'\(A\)\s*(.*?)(?=\(B\))', block, re.DOTALL).group(1).strip())
            options.append(re.search(r'\(B\)\s*(.*?)(?=\(C\))', block, re.DOTALL).group(1).strip())
            options.append(re.search(r'\(C\)\s*(.*?)(?=\(D\))', block, re.DOTALL).group(1).strip())
            # For D, we look for "Answer:" or end of block
            options.append(re.search(r'\(D\)\s*(.*?)(?=Answer:|$)', block, re.DOTALL).group(1).strip())

            # 3. Extract Answer
            ans_match = re.search(r'Answer:\s*([A-D])', block)
            correct_idx = ord(ans_match.group(1)) - ord('A') if ans_match else 0

            # 4. Extract Explanation
            expl_match = re.search(r'Explanation:\s*(.*)', block, re.DOTALL)
            explanation = expl_match.group(1).strip() if expl_match else ""

            questions.append({
                "examType": exam_type,
                "subject": subject,
                "chapter": chapter,
                "difficulty": "Medium",
                "year": 2023,
                "questionText": q_text,
                "options": options,
                "correctOptionIndex": correct_idx,
                "explanation": explanation,
                "isPremium": True
            })
        except Exception as e:
            print(f"⚠️ Error parsing block: {e}")
            continue

    return questions

def extract_from_pdf(pdf_path):
    if not PyPDF2:
        print("❌ Error: PyPDF2 not installed. Run 'pip install PyPDF2'")
        return ""
    
    text = ""
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        for page in reader.pages:
            text += page.extract_text() + "\n"
    return text

def convert(input_source, output_name, is_pdf=False, **meta):
    if is_pdf:
        text = extract_from_pdf(input_source)
    else:
        text = input_source

    questions = parse_questions_from_text(text, **meta)
    
    output_path = f"content/{meta['exam_type'].lower()}/{meta['subject'].lower()}/{output_name}.json"
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(questions, f, indent=2)
    
    print(f"✅ Success! Created {output_path} with {len(questions)} questions.")

if __name__ == "__main__":
    # SAMPLE USAGE (Paste your text or provide PDF path)
    sample_text = """
    Q1. What is the unit of Force?
    (A) Joule
    (B) Newton
    (C) Watt
    (D) Pascal
    Answer: B
    Explanation: Newton is the SI unit of force (N).
    """
    
    # Example: Convert raw text
    convert(sample_text, "units_and_dimensions", exam_type="JEE", subject="Physics", chapter="Units")
    
    # Example: Convert PDF (uncomment to use)
    # convert("path/to/my_pyqs.pdf", "kinematics_2023", is_pdf=True, exam_type="JEE", subject="Physics", chapter="Kinematics")
