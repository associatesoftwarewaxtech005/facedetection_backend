import os
import sys
import json
import base64
import numpy as np
import cv2

# ─── Configuration (overridable via environment variables) ───────────────────
LBPH_THRESHOLD     = float(os.getenv("LBPH_THRESHOLD",     "75.0"))
# Liveness: score below this threshold is treated as a spoof (photo / screen)
LIVENESS_THRESHOLD = float(os.getenv("LIVENESS_THRESHOLD", "0.25"))

def log(message):
    sys.stderr.write(str(message) + "\n")
    sys.stderr.flush()

def write_json(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()

# ─── Image decoding ──────────────────────────────────────────────────────────
def decode_image(input_data):
    """
    Decodes input that could be raw base64, a data URL, or wrapped in JSON.
    Returns a BGR numpy image or None on failure.
    """
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
    """
    Estimate whether a face region is a live person or a flat spoof surface.

    Real skin has rich micro-texture and sharp luminance transitions.
    Printed / displayed photos tend to be flatter:
      - lower Sobel gradient-magnitude standard deviation
      - lower Laplacian variance (texture sharpness)

    Returns a float in [0, 1].  Higher = more likely a real face.
    The backend compares this value against the configurable threshold.
    """
    try:
        pad = max(int(min(w, h) * 0.1), 4)
        x1  = max(0, x - pad);  y1 = max(0, y - pad)
        x2  = min(gray_img.shape[1], x + w + pad)
        y2  = min(gray_img.shape[0], y + h + pad)
        face = gray_img[y1:y2, x1:x2]
        if face.size == 0:
            return 0.0
        face64   = cv2.resize(face, (64, 64)).astype(np.float32)
        # Sobel gradient magnitude
        sx       = cv2.Sobel(face64, cv2.CV_32F, 1, 0, ksize=3)
        sy       = cv2.Sobel(face64, cv2.CV_32F, 0, 1, ksize=3)
        mag      = np.sqrt(sx ** 2 + sy ** 2)
        grad_std = float(mag.std())
        # Laplacian variance
        lap      = cv2.Laplacian(face64, cv2.CV_32F)
        lap_var  = float(lap.var())
        # Normalise (empirical divisors tuned for webcam images)
        # Real faces: grad_std ~20-60, lap_var ~200-1200
        # Spoof:      grad_std ~ 5-20, lap_var ~  20-200
        score_grad = min(1.0, grad_std / 45.0)
        score_lap  = min(1.0, lap_var  / 600.0)
        score      = 0.6 * score_grad + 0.4 * score_lap
        return round(float(score), 4)
    except Exception as e:
        log(f"Liveness computation error: {e}")
        return 0.0

# ─── Face helpers ─────────────────────────────────────────────────────────────
def crop_and_resize_face(img, x, y, w, h, size=(100, 100)):
    face      = img[y:y + h, x:x + w]
    face_gray = cv2.cvtColor(face, cv2.COLOR_BGR2GRAY)
    return cv2.resize(face_gray, size)

def detect_face_multiorientation(img, face_cascade):
    """
    Tries to detect faces in the original image.
    If no face is detected, tries rotating the image by 90, 270, and 180 degrees
    to handle mobile cameras that capture landscape frames.
    Returns (detected_list, gray_image, color_image)
    """
    # 0 degrees
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray, img

    # Try 90 degrees clockwise (mobile portrait orientation sensor mismatch)
    img_90 = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    gray_90 = cv2.cvtColor(img_90, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_90, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_90, img_90

    # Try 90 degrees counter-clockwise (270 degrees)
    img_270 = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
    gray_270 = cv2.cvtColor(img_270, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_270, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_270, img_270

    # Try 180 degrees (upside down)
    img_180 = cv2.rotate(img, cv2.ROTATE_180)
    gray_180 = cv2.cvtColor(img_180, cv2.COLOR_BGR2GRAY)
    detected = face_cascade.detectMultiScale(gray_180, scaleFactor=1.1, minNeighbors=4, minSize=(30, 30))
    if len(detected) > 0:
        return list(detected), gray_180, img_180

    return [], gray, img

# ─── Main ─────────────────────────────────────────────────────────────────────
def main():
    script_dir   = os.path.dirname(os.path.abspath(__file__))
    model_path   = os.path.join(script_dir, "lbph_model.yml")
    cascade_path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
    face_cascade = cv2.CascadeClassifier(cascade_path)

    mode = "detect"
    if len(sys.argv) > 1:
        mode = sys.argv[1].lower()

    try:
        # ── TRAIN ──────────────────────────────────────────────────────────────
        if mode == "train":
            input_data = sys.stdin.read().strip()
            if not input_data:
                write_json({"success": False, "message": "No training data provided.",
                            "processed": 0, "skipped": 0})
                return
            try:
                samples = json.loads(input_data)
            except json.JSONDecodeError as e:
                write_json({"success": False, "message": f"Invalid JSON: {e}",
                            "processed": 0, "skipped": 0})
                return
            if isinstance(samples, dict) and "samples" in samples:
                samples = samples["samples"]
            if not isinstance(samples, list):
                write_json({"success": False, "message": "Training data must be a list.",
                            "processed": 0, "skipped": 0})
                return

            faces, labels, skipped = [], [], 0
            for sample in samples:
                try:
                    label = sample.get("label")
                    if label is None:
                        skipped += 1; continue
                    label_val = int(label)
                    img_data  = sample.get("image")
                    if not img_data:
                        skipped += 1; continue
                    img = decode_image(img_data)
                    if img is None:
                        skipped += 1; continue
                    detected, gray, img_upright = detect_face_multiorientation(img, face_cascade)
                    if len(detected) == 0:
                        skipped += 1; continue
                    for (x, y, w, h) in detected:
                        faces.append(crop_and_resize_face(img_upright, x, y, w, h))
                        labels.append(label_val)
                        break
                except Exception as e:
                    log(f"Training sample error: {e}")
                    skipped += 1

            if not faces:
                write_json({"success": False, "message": "No valid faces found.",
                            "processed": 0, "skipped": skipped})
                return

            recognizer = cv2.face.LBPHFaceRecognizer_create()
            recognizer.train(faces, np.array(labels, dtype=np.int32))
            recognizer.save(model_path)
            write_json({"success": True,
                        "registeredLabels": len(set(labels)),
                        "processed": len(faces),
                        "skipped": skipped})

        # ── PREDICT ────────────────────────────────────────────────────────────
        elif mode == "predict":
            input_data = sys.stdin.read().strip()
            if not input_data:
                write_json({"faceDetected": False, "count": 0, "label": -1,
                            "confidence": 1.0, "livenessScore": 0.0,
                            "error": "No image data provided"})
                return

            img = decode_image(input_data)
            if img is None:
                write_json({"faceDetected": False, "count": 0, "label": -1,
                            "confidence": 1.0, "livenessScore": 0.0,
                            "error": "Could not decode image bytes"})
                return

            detected, gray, img = detect_face_multiorientation(img, face_cascade)

            if len(detected) == 0:
                write_json({"faceDetected": False, "count": 0, "label": -1,
                            "confidence": 1.0, "livenessScore": 0.0})
                return

            # Pick largest face for liveness + recognition
            best_face, max_area = None, -1
            for (x, y, w, h) in detected:
                area = w * h
                if area > max_area:
                    max_area  = area
                    best_face = (x, y, w, h)

            (x, y, w, h)   = best_face
            liveness_score = compute_liveness_score(gray, x, y, w, h)

            if not os.path.exists(model_path):
                write_json({"faceDetected": True, "count": len(detected),
                            "label": -1, "confidence": 1.0,
                            "livenessScore": liveness_score,
                            "error": "No trained biometric face model exists yet."})
                return

            recognizer        = cv2.face.LBPHFaceRecognizer_create()
            recognizer.read(model_path)
            face_img          = crop_and_resize_face(img, x, y, w, h)
            label, confidence = recognizer.predict(face_img)

            matched_label = int(label) if confidence <= LBPH_THRESHOLD else -1

            write_json({"faceDetected": True,
                        "count":        len(detected),
                        "label":        matched_label,
                        "confidence":   float(confidence),
                        "livenessScore": liveness_score})

        # ── DETECT (default) ───────────────────────────────────────────────────
        else:
            input_data = sys.stdin.read().strip()
            if not input_data:
                write_json({"faceDetected": False, "count": 0, "faces": [],
                            "livenessScore": 0.0, "error": "No image data provided"})
                return

            img = decode_image(input_data)
            if img is None:
                write_json({"faceDetected": False, "count": 0, "faces": [],
                            "livenessScore": 0.0, "error": "Could not decode image"})
                return

            detected, gray, img = detect_face_multiorientation(img, face_cascade)

            face_list, best_score = [], 0.0
            for (x, y, w, h) in detected:
                face_list.append({"x": int(x), "y": int(y),
                                  "width": int(w), "height": int(h)})
                s = compute_liveness_score(gray, x, y, w, h)
                if s > best_score:
                    best_score = s

            write_json({"faceDetected":  len(face_list) > 0,
                        "count":         len(face_list),
                        "faces":         face_list,
                        "livenessScore": best_score})

    except Exception as e:
        write_json({"faceDetected": False, "count": 0,
                    "livenessScore": 0.0,
                    "error": f"Unexpected Python error: {str(e)}"})

if __name__ == "__main__":
    main()
