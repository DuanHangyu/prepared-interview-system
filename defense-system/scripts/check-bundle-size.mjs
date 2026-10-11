import { readdir, stat } from "node:fs/promises";
import { join, relative, resolve } from "node:path";

const projectRoot = resolve(import.meta.dirname, "..");
const distDirectory = join(projectRoot, "dist");
const maximumJavaScriptBytes = 500 * 1024;

async function findJavaScriptFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nestedFiles = await Promise.all(
    entries.map(async (entry) => {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        return findJavaScriptFiles(path);
      }
      return entry.isFile() && entry.name.endsWith(".js") ? [path] : [];
    })
  );
  return nestedFiles.flat();
}

const javaScriptFiles = await findJavaScriptFiles(distDirectory);
const oversizedFiles = [];

for (const file of javaScriptFiles) {
  const { size } = await stat(file);
  if (size > maximumJavaScriptBytes) {
    oversizedFiles.push({
      file: relative(projectRoot, file),
      size,
    });
  }
}

if (oversizedFiles.length > 0) {
  const details = oversizedFiles
    .sort((left, right) => right.size - left.size)
    .map(({ file, size }) => `- ${file}: ${(size / 1024).toFixed(1)} KiB`)
    .join("\n");
  throw new Error(
    `Production JavaScript bundle budget exceeded (${maximumJavaScriptBytes / 1024} KiB):\n${details}`
  );
}

const largestFile = await Promise.all(
  javaScriptFiles.map(async (file) => ({ file, size: (await stat(file)).size }))
).then((files) => files.sort((left, right) => right.size - left.size)[0]);

console.log(
  `Bundle budget passed: largest JavaScript file is ${relative(projectRoot, largestFile.file)} (${(
    largestFile.size / 1024
  ).toFixed(1)} KiB).`
);
