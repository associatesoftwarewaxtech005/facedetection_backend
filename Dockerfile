# Use Eclipse Temurin JDK 17 base image (Ubuntu Jammy)
FROM eclipse-temurin:17-jdk-jammy

# Install Python 3, pip, virtual environment (venv), and shared libraries required by OpenCV (headless)
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    libgl1-mesa-glx \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Set the working directory inside the container
WORKDIR /app

# Copy all files of the backend repository into the container
COPY . .

# Initialize the Python virtual environment and install packages
RUN python3 -m venv python_biometric/.venv && \
    ./python_biometric/.venv/bin/pip install --upgrade pip && \
    ./python_biometric/.venv/bin/pip install -r python_biometric/requirements.txt

# Make Gradle wrapper executable
RUN chmod +x gradlew

# Build the Spring Boot application executable jar file
RUN ./gradlew bootJar --no-daemon

# Expose port (Railway will override this via the PORT environment variable)
EXPOSE 8082

# Start the Spring Boot application jar
CMD ["java", "-jar", "build/libs/loginsystem-0.0.1-SNAPSHOT.jar"]
