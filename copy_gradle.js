const fs = require('fs');
const path = require('path');

function copyFolderRecursiveSync(source, target) {
  if (!fs.existsSync(source)) {
    console.error("Source does not exist:", source);
    return;
  }
  
  const targetFolder = target;
  if (!fs.existsSync(targetFolder)) {
    fs.mkdirSync(targetFolder, { recursive: true });
  }

  const files = fs.readdirSync(source);
  files.forEach(function (file) {
    const curSource = path.join(source, file);
    const curTarget = path.join(targetFolder, file);
    if (fs.lstatSync(curSource).isDirectory()) {
      copyFolderRecursiveSync(curSource, curTarget);
    } else {
      fs.copyFileSync(curSource, curTarget);
      // Make sure it preserves execution permissions if on Unix
      try {
        fs.chmodSync(curTarget, 0o755);
      } catch (e) {}
    }
  });
}

try {
  console.log("Starting copy...");
  copyFolderRecursiveSync(
    "/Users/abhineshas/.gradle/wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx/gradle-9.5.1",
    "/Users/abhineshas/projects/facedetection/backend/gradle-bin"
  );
  console.log("Copy completed successfully!");
} catch (err) {
  console.error("Copy failed:", err);
}
