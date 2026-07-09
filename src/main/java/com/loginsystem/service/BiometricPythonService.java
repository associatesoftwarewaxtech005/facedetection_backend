package com.loginsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class BiometricPythonService {

    @Value("${biometric.python-executable}")
    private String pythonCommand;

    @Value("${biometric.script-path}")
    private String scriptPath;

    @Value("${biometric.lbph-threshold:75.0}")
    private double lbphThreshold;

    @Value("${biometric.liveness-threshold:0.25}")
    private double livenessThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class Face {
        public int x;
        public int y;
        public int width;
        public int height;

        // Default constructor for Jackson
        public Face() {}

        public Face(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public static class DetectionResult {
        public boolean faceDetected;
        public int count;
        public List<Face> faces = new ArrayList<>();
        public double livenessScore;
        public String error;

        public DetectionResult() {}
    }

    public static class RecognitionResult {
        public boolean faceDetected;
        public int count;
        public Integer label;
        public Double confidence;
        public double livenessScore;
        public String error;

        public RecognitionResult() {}
    }

    public static class TrainSample {
        public String label;
        public String image;

        public TrainSample(String label, String image) {
            this.label = label;
            this.image = image;
        }
    }

    private static class ProcessOutput {
        public int exitCode;
        public String stdout;
        public String stderr;
        public boolean timedOut;
    }

    /**
     * Executes the Python script in a subprocess with safe stream consumption and timeout.
     */
    private ProcessOutput executePythonScript(String mode, String inputPayload) {
        ProcessOutput result = new ProcessOutput();
        try {
            File scriptFile = new File(scriptPath);
            String resolvedScriptPath = scriptFile.getAbsolutePath();

            List<String> command = new ArrayList<>();
            command.add(pythonCommand);
            command.add(resolvedScriptPath);
            if (mode != null) {
                command.add(mode);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            // Set threshold configurations in subprocess environment
            pb.environment().put("LBPH_THRESHOLD", String.valueOf(lbphThreshold));
            pb.environment().put("LIVENESS_THRESHOLD", String.valueOf(livenessThreshold));

            Process process = pb.start();

            // Write input payload to stdin and close it immediately to signal end of stream
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(inputPayload);
                writer.flush();
            }

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuilder.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            stdoutThread.start();
            stderrThread.start();

            // Wait with a 60 second timeout
            boolean completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);

            stdoutThread.join(5000);
            stderrThread.join(5000);

            if (!completed) {
                process.destroyForcibly();
                result.timedOut = true;
                result.exitCode = -1;
                result.stdout = stdoutBuilder.toString().trim();
                result.stderr = stderrBuilder.toString().trim() + "\n[Java error] Subprocess execution timed out after 60 seconds.";
                return result;
            }

            result.exitCode = process.exitValue();
            result.stdout = stdoutBuilder.toString().trim();
            result.stderr = stderrBuilder.toString().trim();
            return result;

        } catch (Exception e) {
            result.exitCode = -1;
            result.stdout = "";
            result.stderr = "Java exception executing Python script: " + e.getMessage();
            return result;
        }
    }

    /**
     * Executes the Python script, feeds the Base64 image to stdin, and returns the JSON output.
     */
    public DetectionResult detectFaces(String base64Image) {
        if (base64Image == null || base64Image.trim().isEmpty()) {
            DetectionResult result = new DetectionResult();
            result.faceDetected = false;
            result.count = 0;
            result.error = "Empty image payload";
            return result;
        }

        // Clean up data URL prefix if present
        if (base64Image.contains(",")) {
            base64Image = base64Image.split(",")[1];
        }

        ProcessOutput output = executePythonScript(null, base64Image);

        if (output.timedOut) {
            System.err.println("Face detection timed out. Stderr: " + output.stderr);
            DetectionResult errResult = new DetectionResult();
            errResult.faceDetected = false;
            errResult.count = 0;
            errResult.error = "Python script timed out after 60 seconds.";
            return errResult;
        }

        if (output.exitCode != 0) {
            System.err.println("Face detection failed. Exit code: " + output.exitCode + ". Stderr: " + output.stderr);
            DetectionResult errResult = new DetectionResult();
            errResult.faceDetected = false;
            errResult.count = 0;
            errResult.error = "Python script exited with code " + output.exitCode + ". Error: " + output.stderr;
            return errResult;
        }

        // Log stderr at debug level (simulate by printing if needed, or logger if available)
        String stderr = output.stderr.trim();
        if (!stderr.isEmpty()) {
            System.out.println("Python stderr: " + stderr);
        }

        try {
            return objectMapper.readValue(output.stdout, DetectionResult.class);
        } catch (Exception e) {
            DetectionResult errResult = new DetectionResult();
            errResult.faceDetected = false;
            errResult.count = 0;
            errResult.error = "Failed to parse Python JSON output: '" + output.stdout + "'. Error: " + e.getMessage();
            return errResult;
        }
    }

    /**
     * Executes Python face recognizer script in 'train' mode.
     */
    public boolean trainModel(List<TrainSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return false;
        }

        try {
            String jsonPayload = objectMapper.writeValueAsString(samples);
            ProcessOutput output = executePythonScript("train", jsonPayload);

            if (output.timedOut) {
                System.err.println("Face training timed out. Stderr: " + output.stderr);
                return false;
            }

            if (output.exitCode != 0) {
                System.err.println("Face training failed. Exit code: " + output.exitCode + ". Stderr: " + output.stderr);
                return false;
            }

            String stderr = output.stderr.trim();
            if (!stderr.isEmpty()) {
                System.out.println("Python stderr: " + stderr);
            }

            boolean success = output.stdout.contains("\"success\": true");
            if (!success) {
                System.err.println("Face training response status was not success. Output: " + output.stdout);
            }
            return success;

        } catch (Exception e) {
            System.err.println("Exception training face model: " + e.getMessage());
            return false;
        }
    }

    /**
     * Executes Python face recognizer script in 'predict' mode.
     */
    public RecognitionResult recognizeFace(String base64Image) {
        if (base64Image == null || base64Image.trim().isEmpty()) {
            RecognitionResult result = new RecognitionResult();
            result.faceDetected = false;
            result.error = "Empty image payload";
            return result;
        }

        if (base64Image.contains(",")) {
            base64Image = base64Image.split(",")[1];
        }

        ProcessOutput output = executePythonScript("predict", base64Image);

        if (output.timedOut) {
            System.err.println("Face recognition timed out. Stderr: " + output.stderr);
            RecognitionResult errResult = new RecognitionResult();
            errResult.faceDetected = false;
            errResult.error = "Python script timed out after 60 seconds.";
            return errResult;
        }

        if (output.exitCode != 0) {
            System.err.println("Face recognition failed. Exit code: " + output.exitCode + ". Stderr: " + output.stderr);
            RecognitionResult errResult = new RecognitionResult();
            errResult.faceDetected = false;
            errResult.error = "Python script exited with code " + output.exitCode + ". Error: " + output.stderr;
            return errResult;
        }

        String stderr = output.stderr.trim();
        if (!stderr.isEmpty()) {
            System.out.println("Python stderr: " + stderr);
        }

        try {
            return objectMapper.readValue(output.stdout, RecognitionResult.class);
        } catch (Exception e) {
            RecognitionResult errResult = new RecognitionResult();
            errResult.faceDetected = false;
            errResult.error = "Failed to parse Python JSON output: '" + output.stdout + "'. Error: " + e.getMessage();
            return errResult;
        }
    }
}

