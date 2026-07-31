import os
import sys
import json
import base64
import numpy as np
import cv2

# Set UTF-8 encoding for Windows stdout/stderr to prevent emoji/log encoding errors
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

# Quiet TensorFlow logs
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"

DEEPFACE_AVAILABLE = False
try:
    from deepface import DeepFace
    DEEPFACE_AVAILABLE = True
except Exception as e:
    sys.stderr.write(f"DeepFace module warning: {e}\n")

# ─── Configuration ───────────────────────────────────────────────────────────
LIVENESS_THRESHOLD = float(os.getenv("LIVENESS_THRESHOLD", "0.20"))

def log(message):
    sys.stderr.write(str(message) + "\n")
    sys.stderr.flush()

def write_json(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()

# ─── Image decoding ──────────────────────────────────────────────────────────
def decode_image(input_data):
    try:
        input_data = input_data.strip()
        if not input_data:
            return None
        if input_data.startswith("{") and input_data.endswith("}"):
            try:
                data = json.loads(input_data)
                img_b64 = (data.get("image")
                           or data.get("faceImage")
                           or data.get("capturedImage"))
                if img_b64:
                    input_data = img_b64.strip()
            except json.JSONDecodeError:
                pass
        if "," in input_data:
            input_data = input_data.split(",", 1)[1]
        img_bytes = base64.b64decode(input_data)
        nparr     = np.frombuffer(img_bytes, np.uint8)
        img       = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        return img
    except Exception as e:
        log(f"Error decoding image: {e}")
        return None

# ─── Liveness detection ──────────────────────────────────────────────────────
def compute_liveness_score(gray_img, x, y, w, h):
    try:
        pad = max(int(min(w, h) * 0.1), 4)
        x1  = max(0, x - pad);  y1 = max(0, y - pad)
        x2  = min(gray_img.shape[1], x + w + pad)
        y2  = min(gray_img.shape[0], y + h + pad)
        face = gray_img[y1:y2, x1:x2]
        if face.size == 0:
            return 0.0
        face64   = cv2.resize(face, (64, 64)).astype(np.float32)
        sx       = cv2.Sobel(face64, cv2.CV_32F, 1, 0, ksize=3)
        sy       = cv2.Sobel(face64, cv2.CV_32F, 0, 1, ksize=3)
        mag      = np.sqrt(sx ** 2 + sy ** 2)
        grad_std = float(mag.std())
        lap      = cv2.Laplacian(face64, cv2.CV_32F)
        lap_var  = float(lap.var())
        score_grad = min(1.0, grad_std / 45.0)
        score_lap  = min(1.0, lap_var  / 600.0)
        score      = 0.6 * score_grad + 0.4 * score_lap
        return round(float(score), 4)
    except Exception as e:
        log(f"Liveness computation error: {e}")
        return 0.0

# ─── Multi-orientation detection ─────────────────────────────────────────────
def detect_face_multiorientation(img, face_cascade):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray, img

    img_90 = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    gray_90 = cv2.cvtColor(img_90, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_90, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_90, img_90

    img_270 = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
    gray_270 = cv2.cvtColor(img_270, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_270, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_270, img_270

    img_180 = cv2.rotate(img, cv2.ROTATE_180)
    gray_180 = cv2.cvtColor(img_180, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_180, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_180, img_180

    return [], gray, img

# ─── DeepFace Feature Extraction ──────────────────────────────────────────────
def extract_deepface_embedding(img):
    if not DEEPFACE_AVAILABLE:
        return None
    try:
        results = DeepFace.represent(
            img_path=img,
            model_name="Facenet",
            enforce_detection=False,
            detector_backend="opencv"
        )
        if results and len(results) > 0 and "embedding" in results[0]:
            emb = [float(v) for v in results[0]["embedding"]]
            return emb
    except Exception as e:
        log(f"DeepFace extraction exception: {e}")
    return None

def extract_opencv_fallback_embedding(gray_img, x, y, w, h):
    try:
        pad = max(int(min(w, h) * 0.05), 2)
        x1 = max(0, x - pad); y1 = max(0, y - pad)
        x2 = min(gray_img.shape[1], x + w + pad)
        y2 = min(gray_img.shape[0], y + h + pad)
        face_roi = gray_img[y1:y2, x1:x2]
        if face_roi.size == 0:
            return []
        
        resized = cv2.resize(face_roi, (64, 64))
        equalized = cv2.equalizeHist(resized)
        
        sobelx = cv2.Sobel(equalized, cv2.CV_32F, 1, 0, ksize=3)
        sobely = cv2.Sobel(equalized, cv2.CV_32F, 0, 1, ksize=3)
        magnitude = cv2.magnitude(sobelx, sobely)
        
        mag_8x8 = cv2.resize(magnitude, (8, 8)).flatten()
        spatial_8x8 = cv2.resize(equalized.astype(np.float32), (8, 8)).flatten()
        
        vec = np.concatenate([spatial_8x8, mag_8x8])
        norm = np.linalg.norm(vec)
        if norm > 1e-6:
            vec = vec / norm
            
        return [round(float(v), 6) for v in vec]
    except Exception as e:
        log(f"OpenCV embedding fallback error: {e}")
        return []

# ─── Main ─────────────────────────────────────────────────────────────────────
def main():
    cascade_path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
    face_cascade = cv2.CascadeClassifier(cascade_path)

    mode = "predict"
    if len(sys.argv) > 1:
        mode = sys.argv[1].lower()

    try:
        input_data = sys.stdin.read().strip()
        if not input_data:
            write_json({"faceDetected": False, "count": 0, "embedding": [],
                        "livenessScore": 0.0, "error": "No image data provided"})
            return

        img = decode_image(input_data)
        if img is None:
            write_json({"faceDetected": False, "count": 0, "embedding": [],
                        "livenessScore": 0.0, "error": "Could not decode image"})
            return

        detected, gray, img_upright = detect_face_multiorientation(img, face_cascade)

        if len(detected) == 0:
            write_json({"faceDetected": False, "count": 0, "embedding": [],
                        "livenessScore": 0.0})
            return

        # Pick largest face region
        best_face, max_area = None, -1
        for (x, y, w, h) in detected:
            area = w * h
            if area > max_area:
                max_area  = area
                best_face = (x, y, w, h)

        (x, y, w, h)   = best_face
        liveness_score = compute_liveness_score(gray, x, y, w, h)
        embedding      = extract_deepface_embedding(img_upright)
        if not embedding or len(embedding) == 0:
            embedding = extract_opencv_fallback_embedding(gray, x, y, w, h)

        if mode == "train":
            write_json({"success": True, "message": "DeepFace neural network is pre-trained and stateless.",
                        "registeredLabels": 1, "processed": 1, "skipped": 0})
            return

        write_json({
            "faceDetected": True,
            "count": len(detected),
            "label": -1,
            "confidence": 0.0,
            "embedding": embedding if embedding else [],
            "livenessScore": liveness_score
        })

    except Exception as e:
        write_json({"faceDetected": False, "count": 0, "embedding": [],
                    "livenessScore": 0.0, "error": f"Unexpected Python error: {str(e)}"})

if __name__ == "__main__":
    main()
